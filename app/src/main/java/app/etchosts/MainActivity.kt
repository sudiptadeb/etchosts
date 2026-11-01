package app.etchosts

import android.app.Activity
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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val reqVpn = 1
    private val reqImport = 2
    private val maxImport = 1 shl 20

    private lateinit var toggle: Switch
    private lateinit var status: TextView
    private lateinit var hostsBox: EditText
    private lateinit var appsBtn: Button
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
        val root = findViewById<android.view.View>(R.id.root)
        root.setOnApplyWindowInsetsListener { v, insets -> // targetSdk 35 is edge-to-edge
            val pad = (16 * resources.displayMetrics.density).toInt()
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left + pad, i.top + pad, i.right + pad, i.bottom + pad)
            }
            insets
        }
        toggle = findViewById(R.id.toggle)
        status = findViewById(R.id.status)
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

        val group = findViewById<RadioGroup>(R.id.upstream)
        val current = prefs.getString(Prefs.UPSTREAM, Upstream.CHOICES.keys.first())
        Upstream.CHOICES.entries.forEachIndexed { i, (key, label) ->
            val rb = RadioButton(this).apply { id = 100 + i; text = label; tag = key }
            group.addView(rb)
            if (key == current) rb.isChecked = true
        }
        group.setOnCheckedChangeListener { g, id ->
            prefs.edit().putString(Prefs.UPSTREAM, g.findViewById<RadioButton>(id).tag as String).apply()
        }

        val appMode = findViewById<RadioGroup>(R.id.appMode)
        appMode.check(
            when (Prefs.appMode(prefs)) {
                Prefs.MODE_EXCLUDE -> R.id.modeExclude
                Prefs.MODE_ONLY -> R.id.modeOnly
                else -> R.id.modeAll
            }
        )
        appMode.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.modeExclude -> Prefs.MODE_EXCLUDE
                R.id.modeOnly -> Prefs.MODE_ONLY
                else -> Prefs.MODE_ALL
            }
            prefs.edit().putString(Prefs.APP_MODE, mode).apply()
            refresh()
            if (mode != Prefs.MODE_ALL && Prefs.appList(prefs).isEmpty()) pickApps()
        }
        appsBtn = findViewById(R.id.appsBtn)
        appsBtn.setOnClickListener { pickApps() }

        val notif = findViewById<Switch>(R.id.notif)
        notif.isChecked = Prefs.notification(prefs)
        notif.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.NOTIFICATION, on).apply() }

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
        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, reqVpn) else DnsVpnService.start(this)
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
        updating = true
        toggle.isChecked = DnsVpnService.running
        updating = false
        val prefs = Prefs.get(this)
        val mode = Prefs.appMode(prefs)
        val apps = Prefs.appList(prefs)
        appsBtn.text = if (apps.isEmpty()) "Choose apps" else "Choose apps (${apps.size})"
        val t = HostsTable.parse(hostsBox.text.toString())
        val bad = if (t.badLines.isEmpty()) "" else " | bad lines: ${t.badLines.take(5).joinToString()}"
        val scope = if (mode == Prefs.MODE_ALL) "" else " | ${Prefs.scopeText(mode, apps)}"
        status.text = if (DnsVpnService.running) {
            "On. ${t.size} names$scope | answered locally ${DnsVpnService.local.get()}, " +
                "forwarded ${DnsVpnService.forwarded.get()}, failed ${DnsVpnService.failed.get()}$bad"
        } else {
            "Off. ${t.size} names$scope$bad"
        }
    }
}
