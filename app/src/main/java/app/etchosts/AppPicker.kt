package app.etchosts

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast

/** Multi-select dialog over the installed apps that can use the network. */
object AppPicker {
    class Row(val pkg: String, val label: String)

    /** Loads the candidate list; slow-ish, call off the main thread. */
    fun installed(pm: PackageManager, self: String): List<Row> =
        pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != self && it.enabled }
            .filter { pm.checkPermission("android.permission.INTERNET", it.packageName) == PackageManager.PERMISSION_GRANTED }
            .map { Row(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()

    fun show(activity: Activity, rows: List<Row>, selected: Set<String>, onDone: (Set<String>) -> Unit) {
        if (rows.isEmpty()) {
            Toast.makeText(activity, "No apps found", Toast.LENGTH_LONG).show()
            return
        }
        val chosen = selected.toMutableSet()
        val view = activity.layoutInflater.inflate(R.layout.dialog_apps, null)
        val list = view.findViewById<ListView>(R.id.list)
        val adapter = Adapter(rows, chosen)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val pkg = adapter.shown[pos].pkg
            if (!chosen.remove(pkg)) chosen.add(pkg)
            adapter.notifyDataSetChanged()
        }
        view.findViewById<EditText>(R.id.filter).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = adapter.filter(s?.toString() ?: "")
        })
        AlertDialog.Builder(activity)
            .setTitle("Choose apps")
            .setView(view)
            .setPositiveButton("Done") { _, _ -> onDone(chosen) }
            .setNeutralButton("Clear") { _, _ -> onDone(emptySet()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class Adapter(private val all: List<Row>, private val chosen: Set<String>) : BaseAdapter() {
        var shown: List<Row> = all

        fun filter(q: String) {
            val needle = q.trim().lowercase()
            shown = if (needle.isEmpty()) all else all.filter {
                it.label.lowercase().contains(needle) || it.pkg.lowercase().contains(needle)
            }
            notifyDataSetChanged()
        }

        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = shown[position].pkg.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = (convertView ?: View.inflate(parent.context, android.R.layout.simple_list_item_multiple_choice, null))
                as CheckedTextView
            val row = shown[position]
            v.text = "${row.label}\n${row.pkg}"
            v.isChecked = row.pkg in chosen
            return v
        }
    }
}
