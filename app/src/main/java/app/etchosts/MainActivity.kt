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
        val bad = if (t.badLines.isEmpty()) "" else "\nCould not parse lines ${t.badLines.take(5).joinToString()}"
        val names = "${t.size} ${if (t.size == 1) "name" else "names"}"
        val scope = if (mode == Prefs.MODE_ALL) "" else " \u00b7 ${Prefs.scopeText(mode, apps)}"
        status.text = if (DnsVpnService.running) {
            "On \u00b7 $names$scope\nAnswered locally ${DnsVpnService.local.get()} \u00b7 " +
                "forwarded ${DnsVpnService.forwarded.get()} \u00b7 failed ${DnsVpnService.failed.get()}$bad"
        } else {
            "Off \u00b7 $names$scope$bad"
        }
    }
}
