package app.etchosts

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val HOSTS = "hosts"
    const val UPSTREAM = "upstream"

    const val SAMPLE = "# IP  hostname [hostname...]\n" +
        "# 192.0.2.10   app.example.test api.example.test\n" +
        "# 2001:db8::10 app.example.test\n" +
        "# 192.0.2.20   *.dev.example.test\n"

    fun get(ctx: Context): SharedPreferences = ctx.getSharedPreferences("etchosts", Context.MODE_PRIVATE)
}
