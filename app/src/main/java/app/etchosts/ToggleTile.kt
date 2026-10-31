package app.etchosts

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class ToggleTile : TileService() {
    override fun onStartListening() {
        val t = qsTile ?: return
        t.state = if (DnsVpnService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }

    override fun onClick() {
        if (DnsVpnService.running) {
            DnsVpnService.stop()
        } else if (VpnService.prepare(this) != null) {
            openApp() // VPN consent needs an Activity
        } else {
            try {
                DnsVpnService.start(this)
            } catch (e: Exception) { // background-start restrictions on some builds
                openApp()
            }
        }
        onStartListening()
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
