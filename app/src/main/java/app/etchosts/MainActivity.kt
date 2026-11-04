package app.etchosts

import android.app.Activity
import android.app.AlertDialog
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.ViewGroup
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.sudiptadeb.amarit.Amarit
import io.github.sudiptadeb.amarit.Updater

class MainActivity : Activity() {
    private val reqVpn = 1
    private val reqImport = 2
    private val maxImport = 1 shl 20

    private lateinit var toggle: Switch
    private lateinit var status: TextView
    private lateinit var warning: TextView
    private lateinit var hostsBox: EditText
    private lateinit var appsBtn: Button
    private var updater: Updater? = null
    private var installedApps: List<AppPicker.Row>? = null
    private val ui = Handler(Looper.getMainLooper())
    private var updating = false
    private var dirty = false
    private val save = Runnable {
        dirty = false
        Prefs.get(this).edit().putString(Prefs.HOSTS, hostsBox.text.toString()).apply()
        refresh()
    }
    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        try {
            updater = Amarit.start(this) // update prompts, configured by the repository's amarit.json
        } catch (e: Exception) { // an updater must never take the app down
            android.util.Log.w(DnsVpnService.TAG, "update check not started", e)
        }
        val root = findViewById<android.view.View>(R.id.root)
        root.setOnApplyWindowInsetsListener { v, insets -> // targetSdk 35 is edge-to-edge
            val pad = (16 * resources.displayMetrics.density).toInt()
            if (Build.VERSION.SDK_INT >= 30) {
                // The keyboard shrinks the scroll view (a margin, not padding): ScrollView then
                // keeps the focused editor and its cursor above the keyboard by itself.
                val sys = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                val lp = v.layoutParams as ViewGroup.MarginLayoutParams
                if (lp.bottomMargin != ime) { lp.bottomMargin = ime; v.layoutParams = lp }
                v.setPadding(sys.left + pad, sys.top + pad, sys.right + pad, maxOf(sys.bottom - ime, 0) + pad)
            }
            insets
        }
        toggle = findViewById(R.id.toggle)
        status = findViewById(R.id.status)
        warning = findViewById(R.id.warning)
        hostsBox = findViewById(R.id.hosts)
        val prefs = Prefs.get(this)

        hostsBox.setText(prefs.getString(Prefs.HOSTS, Prefs.SAMPLE))
        hostsBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                dirty = true; ui.removeCallbacks(save); ui.postDelayed(save, 500)
            }
        })

        val upstreamKeys = Upstream.CHOICES.keys.toList()
        val current = prefs.getString(Prefs.UPSTREAM, upstreamKeys.first())
        spinner(R.id.upstream, Upstream.CHOICES.values.toList(), upstreamKeys.indexOf(current).coerceAtLeast(0)) { i ->
            prefs.edit().putString(Prefs.UPSTREAM, upstreamKeys[i]).apply()
        }

        val modes = listOf(Prefs.MODE_ALL, Prefs.MODE_EXCLUDE, Prefs.MODE_ONLY)
        spinner(
            R.id.appMode, listOf("All apps", "All apps except the chosen ones", "Only the chosen apps"),
            modes.indexOf(Prefs.appMode(prefs)).coerceAtLeast(0),
        ) { i ->
            val mode = modes[i]
            if (mode == Prefs.appMode(prefs)) return@spinner
            prefs.edit().putString(Prefs.APP_MODE, mode).apply()
            refresh()
            if (mode != Prefs.MODE_ALL && Prefs.appList(prefs).isEmpty()) pickApps()
        }
        appsBtn = findViewById(R.id.appsBtn)
        appsBtn.setOnClickListener { pickApps() }

        val notif = findViewById<Switch>(R.id.notif)
        notif.isChecked = Prefs.notification(prefs)
        notif.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.NOTIFICATION, on).apply() }

        // Updates: "stable" follows the v* releases; "latest" offers every push to main.
        val devChannel = findViewById<Switch>(R.id.devChannel)
        devChannel.isChecked = updater?.channel == "latest"
        devChannel.setOnCheckedChangeListener { _, on -> updater?.channel = if (on) "latest" else "stable" }
        findViewById<Button>(R.id.checkUpdate).setOnClickListener {
            val u = updater ?: return@setOnClickListener
            u.checkNow(this) { r ->
                r.onSuccess { if (!it.update) Toast.makeText(this, "Up to date (${it.reason})", Toast.LENGTH_LONG).show() }
                r.onFailure { Toast.makeText(this, "Update check failed: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }

        findViewById<Button>(R.id.recentBtn).setOnClickListener { showRecent() }

        findViewById<Button>(R.id.importBtn).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            startActivityForResult(i, reqImport)
        }

        toggle.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            if (on) requestStart() else DnsVpnService.stop()
        }

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
    }

    override fun onResume() {
        super.onResume()
        DnsVpnService.onStateChange = { refresh() }
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        DnsVpnService.onStateChange = null
        ui.removeCallbacks(tick)
        if (dirty) { ui.removeCallbacks(save); save.run() }
    }

    /** A drop-down over [labels], preselected at [selected]; [onPick] fires only on a user's pick. */
    private fun spinner(id: Int, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val sp = findViewById<Spinner>(id)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sp.setSelection(selected, false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, idL: Long) {
                if (pos != selected || sp.tag == true) onPick(pos)
                sp.tag = true // the first callback is the preselection, not a pick
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun pickApps() {
        val prefs = Prefs.get(this)
        val cached = installedApps
        if (cached != null) {
            AppPicker.show(this, cached, Prefs.appList(prefs)) { saveApps(it) }
            return
        }
        appsBtn.isEnabled = false
        Thread {
            val rows = try { AppPicker.installed(packageManager, packageName) } catch (e: Exception) { emptyList() }
            ui.post {
                installedApps = rows
                appsBtn.isEnabled = true
                if (!isFinishing) AppPicker.show(this, rows, Prefs.appList(prefs)) { saveApps(it) }
            }
        }.start()
    }

    private fun saveApps(apps: Set<String>) {
        Prefs.get(this).edit().putStringSet(Prefs.APP_LIST, apps).apply()
        refresh()
    }

    private fun requestStart() {
        ui.removeCallbacks(save); save.run()
        val prefs = Prefs.get(this)
        if (Prefs.appMode(prefs) == Prefs.MODE_ONLY && Prefs.appList(prefs).isEmpty()) {
            Toast.makeText(this, "Choose at least one app, or pick another app mode", Toast.LENGTH_LONG).show()
            refresh()
            return
        }
        if (otherVpnActive()) {
            AlertDialog.Builder(this)
                .setTitle("Another VPN is on")
                .setMessage(
                    "Android runs only one VPN at a time. Turning on the DNS override disconnects the " +
                        "other VPN (for example a mesh or work VPN), and turning that one on again turns this off."
                )
                .setPositiveButton("Turn on anyway") { _, _ -> startVpn() }
                .setNegativeButton("Cancel") { _, _ -> refresh() }
                .setOnCancelListener { refresh() }
                .show()
            return
        }
        startVpn()
    }

    private fun startVpn() {
        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, reqVpn) else DnsVpnService.start(this)
    }

    /** Whether some VPN other than ours is up (ours is never up when this is asked). */
    private fun otherVpnActive(): Boolean {
        if (DnsVpnService.running) return false
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        return try {
            @Suppress("DEPRECATION")
            cm.allNetworks.any {
                val c = cm.getNetworkCapabilities(it) ?: return@any false
                // Our own tun can linger a moment after stopping; Android reveals the owner only to the owner.
                c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    !(Build.VERSION.SDK_INT >= 29 && c.ownerUid == android.os.Process.myUid())
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Strict Private DNS (a hostname) sends every lookup to that server, around this app. */
    private fun privateDnsHost(): String? {
        if (Build.VERSION.SDK_INT < 28) return null
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        return try {
            cm.activeNetwork?.let { cm.getLinkProperties(it) }?.privateDnsServerName
        } catch (e: Exception) {
            null
        }
    }

    private fun showRecent() {
        val lines = DnsVpnService.recentLookups()
        val text = when {
            lines.isNotEmpty() -> lines.joinToString("\n")
            DnsVpnService.running -> "No lookups yet. Open an app that uses one of your names."
            else -> "Turn the override on to see lookups here."
        }
        val tv = TextView(this).apply {
            this.text = text
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("Recent lookups (newest first)")
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .show()
    }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            reqVpn -> if (resultCode == RESULT_OK) DnsVpnService.start(this) else {
                Toast.makeText(this, "VPN permission was not granted", Toast.LENGTH_LONG).show()
                refresh()
            }
            reqImport -> data?.data?.takeIf { resultCode == RESULT_OK }?.let { uri ->
                try {
                    val bytes = contentResolver.openInputStream(uri)!!.use { s ->
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val n = s.read(chunk)
                            if (n < 0) break
                            buf.write(chunk, 0, n)
                            if (buf.size() > maxImport) throw IllegalArgumentException("file is larger than 1 MB")
                        }
                        buf.toByteArray()
                    }
                    hostsBox.setText(String(bytes, Charsets.UTF_8))
                } catch (e: Exception) {
                    Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refresh() {
        // onRevoke is the normal signal that another VPN took over; this catches builds that skip it.
        if (DnsVpnService.running && VpnService.prepare(this) != null) DnsVpnService.lostToAnotherVpn()
        updating = true
        toggle.isChecked = DnsVpnService.running
        updating = false
        val prefs = Prefs.get(this)
        val mode = Prefs.appMode(prefs)
        val apps = Prefs.appList(prefs)
        appsBtn.text = if (apps.isEmpty()) "Choose apps" else "Choose apps (${apps.size})"
        val t = HostsTable.parse(hostsBox.text.toString())
        val bad = if (t.badLines.isEmpty()) "" else "\nCould not parse lines ${t.badLines.take(5).joinToString()}"
        val names = "${t.size} ${if (t.size == 1) "name" else "names"}"
        val scope = if (mode == Prefs.MODE_ALL) "" else " \u00b7 ${Prefs.scopeText(mode, apps)}"
        status.text = if (DnsVpnService.running) {
            "On \u00b7 $names$scope\nAnswered locally ${DnsVpnService.local.get()} \u00b7 " +
                "forwarded ${DnsVpnService.forwarded.get()} \u00b7 failed ${DnsVpnService.failed.get()}$bad"
        } else {
            "Off \u00b7 $names$scope$bad"
        }
        val warn = mutableListOf<String>()
        if (!DnsVpnService.running) DnsVpnService.stopReason?.let { warn.add(it) }
        if (!DnsVpnService.running && DnsVpnService.stopReason != DnsVpnService.REASON_TAKEN_OVER && otherVpnActive()) {
            warn.add("Another VPN is on. Android runs one VPN at a time: turning this on will disconnect it.")
        }
        privateDnsHost()?.let {
            warn.add(
                "Private DNS is set to $it (Settings > Network > Private DNS). Android sends lookups " +
                    "there instead of to this app, so hosts entries will not apply. Set it to Automatic or Off."
            )
        }
        if (DnsVpnService.running && t.size > 0 && DnsVpnService.local.get() == 0L &&
            DnsVpnService.forwarded.get() > 200
        ) {
            warn.add(
                "None of your names has been looked up yet. Check that the app you test with is not " +
                    "excluded, and that it does not use its own secure DNS (a browser's \"Use secure DNS\" " +
                    "setting bypasses the system resolver). Recent lookups shows what arrives."
            )
        }
        warning.text = warn.joinToString("\n\n")
        warning.visibility = if (warn.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }
}
