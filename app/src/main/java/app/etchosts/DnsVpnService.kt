package app.etchosts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * DNS-only VPN: the tun owns just the fake resolver addresses, so the only
 * packets that arrive here are DNS queries. Everything else stays on the
 * normal network.
 */
class DnsVpnService : VpnService(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        const val TAG = "etchosts"
        const val ACTION_STOP = "app.etchosts.STOP"
        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 1
        private const val MAX_IN_FLIGHT = 16

        // Fake resolvers: routed into the tun, never really exist.
        private const val ADDR4 = "10.255.255.1"
        private const val DNS4 = "10.255.255.2"
        private const val ADDR6 = "fd00:e7c:4057::1"
        private const val DNS6 = "fd00:e7c:4057::2"

        @Volatile var running = false
            private set
        val local = AtomicLong()
        val forwarded = AtomicLong()
        val failed = AtomicLong()

        /** Called on the main thread whenever [running] changes. */
        var onStateChange: (() -> Unit)? = null

        @Volatile private var instance: DnsVpnService? = null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, DnsVpnService::class.java))
        }

        fun stop() {
            instance?.let { it.shutdown(); it.stopSelf() }
        }

        private fun debug(msg: String) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, msg)
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var wakeRead: FileDescriptor? = null
    private var wakeWrite: FileDescriptor? = null
    private var reader: Thread? = null
    private var pool: ThreadPoolExecutor? = null
    private lateinit var prefs: SharedPreferences
    @Volatile private var hosts = HostsTable.EMPTY
    @Volatile private var upstream: Upstream? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (tun == null && !startTunnel()) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onRevoke() { // another VPN took over, or the user revoked consent
        shutdown()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun startTunnel(): Boolean {
        prefs = Prefs.get(this)
        loadPrefs()
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress(ADDR4, 32)
            .addAddress(ADDR6, 128)
            .addDnsServer(DNS4)
            .addDnsServer(DNS6)
            .addRoute(DNS4, 32)
            .addRoute(DNS6, 128)
            .setBlocking(true)
        // Our own upstream lookups (DoH hostnames) must not loop back into the tun.
        b.addDisallowedApplication(packageName)
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        val fd = try { b.establish() } catch (e: Exception) { Log.w(TAG, "establish failed", e); null }
            ?: return false
        tun = fd
        val pipe = Os.pipe()
        wakeRead = pipe[0]; wakeWrite = pipe[1]
        pool = ThreadPoolExecutor(MAX_IN_FLIGHT, MAX_IN_FLIGHT, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64))
            .apply { allowCoreThreadTimeOut(true) }
        prefs.registerOnSharedPreferenceChangeListener(this)
        reader = Thread({ readLoop(fd.fileDescriptor) }, "etchosts-tun").apply { start() }
        setRunning(true)
        Log.i(TAG, "started, ${hosts.size} names, upstream ${prefs.getString(Prefs.UPSTREAM, null)}")
        return true
    }

    private fun loadPrefs() {
        hosts = HostsTable.parse(prefs.getString(Prefs.HOSTS, "") ?: "")
        upstream = Upstream.of(prefs.getString(Prefs.UPSTREAM, null)) { protect(it) }
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
        loadPrefs() // hosts and upstream edits apply live
        debug("reloaded: ${hosts.size} names")
        notifyManager().notify(NOTIF_ID, buildNotification())
    }

    private fun readLoop(fd: FileDescriptor) {
        val input = FileInputStream(fd)
        val out = FileOutputStream(fd)
        val buf = ByteArray(32767)
        val fds = arrayOf(
            StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() },
            StructPollfd().apply { this.fd = wakeRead; events = OsConstants.POLLIN.toShort() },
        )
        try {
            while (true) {
                fds[0].revents = 0; fds[1].revents = 0
                try {
                    Os.poll(fds, -1)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue else throw e
                }
                if (fds[1].revents.toInt() != 0) break // stop requested
                val ev = fds[0].revents.toInt()
                if (ev and OsConstants.POLLIN == 0) {
                    if (ev and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) break
                    continue
                }
                val n = input.read(buf)
                if (n <= 0) break
                handle(buf, n, out)
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "tun reader stopped", e)
        }
    }

    private fun handle(buf: ByteArray, n: Int, out: FileOutputStream) {
        val pkt = Packet.parse(buf, n) ?: return
        if (pkt.dstPort != 53) return
        val q = Dns.parseQuery(pkt.payload) ?: return
        val hit = hosts.lookup(q.name)
        if (hit != null) {
            local.incrementAndGet()
            debug("local ${q.name} type ${q.type}")
            write(out, pkt.reply(Dns.fromHosts(pkt.payload, q, hit)))
            return
        }
        val up = upstream ?: return
        try {
            pool?.execute {
                val resp = try {
                    up.resolve(pkt.payload).also { forwarded.incrementAndGet() }
                } catch (e: Exception) {
                    failed.incrementAndGet()
                    debug("upstream failed for ${q.name}: $e")
                    Dns.error(pkt.payload, q, Dns.RCODE_SERVFAIL)
                }
                write(out, pkt.reply(resp))
            }
        } catch (e: RejectedExecutionException) { // too many in flight
            failed.incrementAndGet()
            write(out, pkt.reply(Dns.error(pkt.payload, q, Dns.RCODE_SERVFAIL)))
        }
    }

    private fun write(out: FileOutputStream, packet: ByteArray) {
        try {
            synchronized(out) { out.write(packet) }
        } catch (e: IOException) {
            debug("tun write failed: $e")
        }
    }

    @Synchronized
    private fun shutdown() {
        if (tun == null && !running) return
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(this)
        wakeWrite?.let { try { Os.write(it, byteArrayOf(1), 0, 1) } catch (_: Exception) {} }
        reader?.join(2000) // the reader leaves poll() and stops touching the fd
        reader = null
        pool?.shutdownNow(); pool = null
        try { tun?.close() } catch (_: IOException) {}
        tun = null
        listOfNotNull(wakeRead, wakeWrite).forEach { try { Os.close(it) } catch (_: Exception) {} }
        wakeRead = null; wakeWrite = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        setRunning(false)
        Log.i(TAG, "stopped")
    }

    private fun setRunning(r: Boolean) {
        running = r
        instance = if (r) this else null
        TileService.requestListeningState(this, ComponentName(this, ToggleTile::class.java))
        Handler(Looper.getMainLooper()).post { onStateChange?.invoke() }
    }

    private fun notifyManager() = getSystemService(NotificationManager::class.java)

    private fun goForeground() {
        notifyManager().createNotificationChannel(
            NotificationChannel(CHANNEL, "Running", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("DNS override on")
            .setContentText("${hosts.size} names from your hosts list")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
