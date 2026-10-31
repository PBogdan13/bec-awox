package ro.bec.awox

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Dala din meniul rapid: o atingere aprinde sau stinge becul. */
class PowerTileService : TileService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (Bulb.lastOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Bec"
        tile.updateTile()
    }

    override fun onClick() {
        val prefs = getSharedPreferences("bec", MODE_PRIVATE)
        val mac = prefs.getString("mac", null)
        val allowed = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (mac == null || !allowed) {
            openApp(); return
        }
        val name = prefs.getString("name", "unpaired") ?: "unpaired"
        val pass = prefs.getString("pass", "1234") ?: "1234"
        Bulb.connect(this, mac, name, pass) {
            // becul trimite starea imediat după conectare; așteptăm puțin ca să știm dacă e aprins
            main.postDelayed({
                Bulb.setPower(!Bulb.lastOn)
                refresh()
                main.postDelayed({ if (!Bulb.uiAttached && !Bulb.keepAlive) Bulb.disconnect() }, 4000)
            }, 700)
        }
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
