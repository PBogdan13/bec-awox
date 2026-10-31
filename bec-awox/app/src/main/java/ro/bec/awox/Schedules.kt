package ro.bec.awox

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** O programare: la ora dată aprinde sau stinge becul, instant sau treptat. */
data class Schedule(
    val id: Int,
    val hour: Int,
    val minute: Int,
    /** bit 0 = luni ... bit 6 = duminică; 0 = o singură dată */
    val days: Int,
    val turnOn: Boolean,
    /** minute de aprindere/stingere treptată; 0 = instant */
    val fadeMin: Int,
    /** luminozitatea la aprindere, 10..100 % */
    val brightness: Int,
    val enabled: Boolean
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("h", hour).put("m", minute).put("d", days)
        .put("on", turnOn).put("f", fadeMin).put("b", brightness).put("e", enabled)

    fun timeText() = "%02d:%02d".format(hour, minute)

    fun daysText(): String {
        if (days == 0) return "o singură dată"
        if (days == 0x7f) return "zilnic"
        if (days == 0x1f) return "luni–vineri"
        if (days == 0x60) return "weekend"
        return DAY_NAMES.filterIndexed { i, _ -> (days and (1 shl i)) != 0 }.joinToString(" ")
    }

    fun actionText(): String {
        val what = if (turnOn) "Aprinde" else "Stinge"
        val how = if (fadeMin == 0) "" else " treptat în $fadeMin min"
        val bri = if (turnOn) ", $brightness%" else ""
        return what + how + bri
    }

    companion object {
        val DAY_NAMES = listOf("L", "Ma", "Mi", "J", "V", "S", "D")

        fun fromJson(o: JSONObject) = Schedule(
            o.getInt("id"), o.getInt("h"), o.getInt("m"), o.optInt("d", 0),
            o.optBoolean("on", true), o.optInt("f", 0), o.optInt("b", 100), o.optBoolean("e", true)
        )
    }
}

object Schedules {
    const val EXTRA_ID = "schedule_id"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("bec", Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Schedule> {
        val raw = prefs(ctx).getString("schedules", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { Schedule.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(ctx: Context, list: List<Schedule>) {
        val arr = JSONArray()
        list.sortedWith(compareBy({ it.hour }, { it.minute })).forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString("schedules", arr.toString()).apply()
        scheduleAll(ctx)
    }

    fun newId(list: List<Schedule>) = (list.maxOfOrNull { it.id } ?: 0) + 1

    /** Următoarea dată când trebuie să pornească programarea, în milisecunde; -1 dacă nu există. */
    fun nextTrigger(s: Schedule, now: Calendar = Calendar.getInstance()): Long {
        for (d in 0..7) {
            val c = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, d)
                set(Calendar.HOUR_OF_DAY, s.hour)
                set(Calendar.MINUTE, s.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis <= now.timeInMillis) continue
            if (s.days == 0) return c.timeInMillis
            val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // luni = 0
            if ((s.days and (1 shl dow)) != 0) return c.timeInMillis
        }
        return -1
    }

    private fun pending(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, ScheduleReceiver::class.java).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Reprogramează alarmele pentru toate programările active. */
    fun scheduleAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val list = load(ctx)
        // anulăm și alarmele rămase de la programări șterse
        for (id in 1..(list.maxOfOrNull { it.id } ?: 0) + 20) am.cancel(pending(ctx, id))
        for (s in list) {
            if (!s.enabled) continue
            val at = nextTrigger(s)
            if (at < 0) continue
            val pi = pending(ctx, s.id)
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Text scurt despre următoarea programare activă, pentru ecranul principal. */
    fun nextText(ctx: Context): String? {
        val now = Calendar.getInstance()
        val next = load(ctx).filter { it.enabled }
            .map { it to nextTrigger(it, now) }
            .filter { it.second > 0 }
            .minByOrNull { it.second } ?: return null
        val c = Calendar.getInstance().apply { timeInMillis = next.second }
        val dayDiff = c.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR)
        val day = when {
            dayDiff == 0 -> "azi"
            dayDiff == 1 || dayDiff == 1 - now.getActualMaximum(Calendar.DAY_OF_YEAR) -> "mâine"
            else -> listOf("luni", "marți", "miercuri", "joi", "vineri", "sâmbătă", "duminică")[(c.get(Calendar.DAY_OF_WEEK) + 5) % 7]
        }
        return "Următoarea: $day la ${next.first.timeText()}, ${next.first.actionText().lowercase()}"
    }
}
