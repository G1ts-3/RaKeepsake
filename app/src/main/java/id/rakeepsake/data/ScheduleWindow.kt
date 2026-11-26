package id.rakeepsake.data

import java.util.Calendar

/**
 * Pengelola jendela waktu aktif perekaman (mengikuti pola dyrecord ScheduleWindow).
 * Mendukung rentang dalam satu hari (misal 18:00 - 23:00) maupun rentang lintas tengah malam (misal 21:00 - 02:00).
 */
object ScheduleWindow {
    fun contains(startMinutes: Int, endMinutes: Int, currentMinutes: Int): Boolean {
        if (startMinutes == endMinutes) return true
        return if (startMinutes < endMinutes) {
            currentMinutes in startMinutes until endMinutes
        } else {
            // Lintas tengah malam: misal 21:00 (1260) sampai 02:00 (120)
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    fun inWindow(
        enabled: Boolean,
        startMinutes: Int,
        endMinutes: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!enabled) return true
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return contains(startMinutes, endMinutes, currentMinutes)
    }

    fun formatMinutes(minutes: Int): String {
        val h = (minutes / 60) % 24
        val m = minutes % 60
        return "%02d:%02d".format(h, m)
    }
}
