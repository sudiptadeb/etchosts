package app.etchosts

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val HOSTS = "hosts"
    const val UPSTREAM = "upstream"

    /** Which apps the DNS override applies to: [MODE_ALL], [MODE_EXCLUDE] or [MODE_ONLY]. */
    const val APP_MODE = "app_mode"
    /** Package names the mode refers to (a string set). */
    const val APP_LIST = "app_list"
    /** Show the "running" notification normally (true) or minimised (false). */
    const val NOTIFICATION = "notification"

    const val MODE_ALL = "all"
    const val MODE_EXCLUDE = "exclude"
    const val MODE_ONLY = "only"

    const val SAMPLE = "# IP  hostname [hostname...]\n" +
        "# 192.0.2.10   app.example.test api.example.test\n" +
        "# 2001:db8::10 app.example.test\n" +
        "# 192.0.2.20   *.dev.example.test\n"

    fun get(ctx: Context): SharedPreferences = ctx.getSharedPreferences("etchosts", Context.MODE_PRIVATE)

    fun appMode(p: SharedPreferences): String = p.getString(APP_MODE, MODE_ALL) ?: MODE_ALL

    /** A private copy: the set returned by SharedPreferences must not be modified. */
    fun appList(p: SharedPreferences): Set<String> = p.getStringSet(APP_LIST, null)?.toSet() ?: emptySet()

    fun notification(p: SharedPreferences): Boolean = p.getBoolean(NOTIFICATION, true)

    /** Human-readable scope, used by the status line and the notification. */
    fun scopeText(mode: String, apps: Set<String>): String = when (mode) {
        MODE_EXCLUDE -> if (apps.isEmpty()) "all apps" else "all apps except ${apps.size}"
        MODE_ONLY -> "only ${apps.size} ${if (apps.size == 1) "app" else "apps"}"
        else -> "all apps"
    }
}
