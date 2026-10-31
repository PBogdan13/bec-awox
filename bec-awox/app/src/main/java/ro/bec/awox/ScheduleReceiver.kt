package ro.bec.awox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Primește alarmele programărilor și repornirea telefonului. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(Schedules.EXTRA_ID, -1)
        if (id < 0) {
            // repornire, schimbare de oră sau actualizarea aplicației: refacem alarmele
            Schedules.scheduleAll(ctx)
            return
        }
        val list = Schedules.load(ctx)
        val s = list.firstOrNull { it.id == id }
        if (s == null || !s.enabled) {
            Schedules.scheduleAll(ctx); return
        }
        // o programare de o singură dată se dezactivează după ce rulează
        if (s.days == 0) Schedules.save(ctx, list.map { if (it.id == id) it.copy(enabled = false) else it })
        else Schedules.scheduleAll(ctx)

        val svc = Intent(ctx, ScheduleService::class.java).putExtra(Schedules.EXTRA_ID, id)
            .putExtra("on", s.turnOn).putExtra("fade", s.fadeMin).putExtra("bri", s.brightness)
        try {
            ctx.startForegroundService(svc)
        } catch (_: Exception) {
        }
    }
}
