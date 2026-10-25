package app.mawja.android.ui.screens.history

import java.util.Calendar
import java.util.TimeZone

/** #136: suggests the 2-hour window the user most often watches in, from history timestamps. */
object BestWatchTime {
    const val MIN_SAMPLES = 10
    const val WINDOW_HOURS = 2

    fun hourHistogram(timestamps: List<Long>, zone: TimeZone = TimeZone.getDefault()): IntArray {
        val hours = IntArray(24)
        val cal = Calendar.getInstance(zone)
        for (ts in timestamps) {
            if (ts <= 0L) continue
            cal.timeInMillis = ts
            hours[cal.get(Calendar.HOUR_OF_DAY)]++
        }
        return hours
    }

    /** Start hour (0-23) of the busiest window, wrapping past midnight; null if too little data. */
    fun peakWindowStart(histogram: IntArray): Int? {
        if (histogram.size != 24 || histogram.sum() < MIN_SAMPLES) return null
        var best = 0
        var bestCount = -1
        for (start in 0 until 24) {
            var c = 0
            for (k in 0 until WINDOW_HOURS) c += histogram[(start + k) % 24]
            if (c > bestCount) {
                bestCount = c
                best = start
            }
        }
        return best
    }

    fun suggest(timestamps: List<Long>, zone: TimeZone = TimeZone.getDefault()): Int? =
        peakWindowStart(hourHistogram(timestamps, zone))

    fun formatWindow(start: Int): String = "%02d:00–%02d:00".format(start, (start + WINDOW_HOURS) % 24)
}
