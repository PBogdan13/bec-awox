package ro.bec.awox

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Rulează o programare: se conectează la bec, îl aprinde sau îl stinge,
 * iar la variantele treptate schimbă luminozitatea pas cu pas.
 */
class ScheduleService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val turnOn = intent?.getBooleanExtra("on", true) ?: true
        val fade = intent?.getIntExtra("fade", 0) ?: 0
        val bri = intent?.getIntExtra("bri", 100) ?: 100

        startInForeground(if (turnOn) "Aprind becul" + (if (fade > 0) " treptat" else "") else "Sting becul" + (if (fade > 0) " treptat" else ""))

        if (running) return START_NOT_STICKY
        val allowed = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences("bec", MODE_PRIVATE)
        if (!allowed || prefs.getString("mac", null) == null) {
            finish(); return START_NOT_STICKY
        }
        running = true
        Bulb.keepAlive = true
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BeculMeu:programare")
            ?.apply { acquire((fade + 3) * 60_000L) }

        attempt(1) { if (turnOn) doOn(fade, bri) else doOff(fade) }
        return START_NOT_STICKY
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel("programari", "Programări", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "programari")
            .setSmallIcon(R.drawable.ic_bulb)
            .setContentTitle("Becul Meu")
            .setContentText(text)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1, n)
    }

    /** Se conectează, cu până la 3 încercări, apoi rulează acțiunea. */
    private fun attempt(n: Int, action: () -> Unit) {
        val prefs = getSharedPreferences("bec", MODE_PRIVATE)
        val mac = prefs.getString("mac", null) ?: return finish()
        var started = false
        Bulb.connect(this, mac, prefs.getString("name", "unpaired") ?: "unpaired", prefs.getString("pass", "1234") ?: "1234") {
            started = true
            // becul trimite starea imediat după conectare
            main.postDelayed(action, 800)
        }
        main.postDelayed({
            if (!started) {
                if (n < 3) {
                    Bulb.disconnect(); attempt(n + 1, action)
                } else finish()
            }
        }, 20_000)
    }

    /** Rulează pasul doar cu becul conectat; dacă s-a pierdut legătura, se reconectează întâi. */
    private fun whenConnected(step: () -> Unit) {
        if (Bulb.state == Bulb.State.ON) step()
        else {
            val prefs = getSharedPreferences("bec", MODE_PRIVATE)
            val mac = prefs.getString("mac", null) ?: return
            Bulb.connect(this, mac, prefs.getString("name", "unpaired") ?: "unpaired", prefs.getString("pass", "1234") ?: "1234", step)
        }
    }

    private fun doOn(fade: Int, briPct: Int) {
        val target = (briPct.coerceIn(10, 100) * 127 / 100).coerceIn(1, 127)
        if (fade <= 0) {
            Bulb.setPower(true)
            Bulb.setWhiteBri(target)
            main.postDelayed({ finish() }, 2000)
            return
        }
        Bulb.setWhiteBri(1)
        Bulb.setPower(true)
        ramp(fade, 1, target, { Bulb.setWhiteBri(it) }) { finish() }
    }

    private fun doOff(fade: Int) {
        if (fade <= 0) {
            Bulb.setPower(false)
            main.postDelayed({ finish() }, 2000)
            return
        }
        val st = Bulb.lastStatus
        if (st != null && !st.on) {
            finish(); return
        }
        if (st != null && st.colorMode) {
            ramp(fade, st.colorBri.coerceIn(10, 100), 10, { Bulb.setColorBri(it) }) { powerOffAndFinish() }
        } else {
            val from = st?.whiteBri?.coerceIn(1, 127) ?: 127
            ramp(fade, from, 1, { Bulb.setWhiteBri(it) }) { powerOffAndFinish() }
        }
    }

    private fun powerOffAndFinish() {
        whenConnected { Bulb.setPower(false) }
        main.postDelayed({ finish() }, 2000)
    }

    /** Trece luminozitatea de la `from` la `to` în `minutes` minute, în pași egali. */
    private fun ramp(minutes: Int, from: Int, to: Int, set: (Int) -> Unit, done: () -> Unit) {
        val steps = 60
        val interval = minutes * 60_000L / steps
        fun step(i: Int) {
            if (i > steps) {
                done(); return
            }
            val v = from + (to - from) * i / steps
            whenConnected { set(v) }
            main.postDelayed({ step(i + 1) }, interval)
        }
        step(1)
    }

    private fun finish() {
        running = false
        Bulb.keepAlive = false
        if (!Bulb.uiAttached) Bulb.disconnect()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
