package app.mawja.android.player.screenshot

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.roundToInt

/** Pure parts of the clean, full-resolution player screenshot (#91). */
object ScreenshotPlan {
    /** Folder under the shared Pictures collection. */
    const val RELATIVE_DIR = "Pictures/Mawja"
    const val DIR_NAME = "Mawja"

    /** Longest side a capture may have; 8K and beyond is scaled down so the bitmap stays sane. */
    const val MAX_SIDE = 7680

    private const val MAX_TITLE_CHARS = 60

    /**
     * Pixel size of the capture: the decoded video frame (stretched by its pixel aspect ratio), so the
     * picture is the video's own resolution rather than the screen's. Falls back to the on-screen
     * surface size when the video size is unknown. Null when neither is known.
     */
    fun captureSize(
        videoWidth: Int,
        videoHeight: Int,
        pixelWidthHeightRatio: Float,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ): Pair<Int, Int>? {
        var w: Int
        var h: Int
        if (videoWidth > 0 && videoHeight > 0) {
            val ratio = if (pixelWidthHeightRatio > 0f && !pixelWidthHeightRatio.isNaN()) pixelWidthHeightRatio else 1f
            w = (videoWidth * ratio).roundToInt()
            h = videoHeight
        } else if (surfaceWidth > 0 && surfaceHeight > 0) {
            w = surfaceWidth
            h = surfaceHeight
        } else {
            return null
        }
        val longest = max(w, h)
        if (longest > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / longest
            w = (w * k).roundToInt().coerceAtLeast(1)
            h = (h * k).roundToInt().coerceAtLeast(1)
        }
        return w to h
    }

    /** "Mawja_<title>_<h-mm-ss>_<yyyyMMdd-HHmmss>.png" with characters file systems refuse removed. */
    fun fileName(
        title: String?,
        positionMs: Long,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val cleanTitle =
            (title ?: "")
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
                .replace(Regex("\\s+"), "_")
                .trim('_', '.', ' ')
                .take(MAX_TITLE_CHARS)
                .trimEnd('_', '.')
        val totalSeconds = (positionMs.coerceAtLeast(0L) / 1000L)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val at = if (h > 0) String.format(Locale.US, "%d-%02d-%02d", h, m, s) else String.format(Locale.US, "%02d-%02d", m, s)
        val stamp =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .apply { this.timeZone = timeZone }
                .format(Date(nowMillis))
        val base = if (cleanTitle.isEmpty()) "Mawja" else "Mawja_$cleanTitle"
        return "${base}_${at}_$stamp.png"
    }
}
