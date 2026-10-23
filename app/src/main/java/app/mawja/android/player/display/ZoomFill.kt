package app.mawja.android.player.display

import kotlin.math.abs
import kotlin.math.max

/**
 * Pinch to fill (#87): the pinch zoom snaps to the exact scale at which a fitted video fills a
 * tall screen edge to edge, and the Fit / Fill / Zoom choice is remembered between videos.
 */
object ZoomFill {
    const val RESIZE_FIT = 0
    const val RESIZE_FILL = 1
    const val RESIZE_ZOOM = 2

    /** Share of the fill scale within which a released pinch snaps onto it. */
    const val SNAP_TOLERANCE = 0.08f

    /**
     * The zoom at which a video letterboxed by Fit covers a [viewWidth]×[viewHeight] view. 1 when
     * the shapes already match or anything is unknown.
     */
    fun fillScale(
        videoAspectRatio: Float,
        viewWidth: Int,
        viewHeight: Int,
    ): Float {
        if (videoAspectRatio <= 0f || viewWidth <= 0 || viewHeight <= 0 || videoAspectRatio.isNaN()) return 1f
        val viewAspect = viewWidth.toFloat() / viewHeight
        return max(viewAspect / videoAspectRatio, videoAspectRatio / viewAspect).coerceAtLeast(1f)
    }

    /** Where a released pinch rests: on the fill scale when close to it, otherwise where it is. */
    fun snap(
        scale: Float,
        fillScale: Float,
        tolerance: Float = SNAP_TOLERANCE,
    ): Float {
        if (fillScale <= 1.01f) return scale
        return if (abs(scale - fillScale) / fillScale <= tolerance) fillScale else scale
    }

    /** A stored Fit / Fill / Zoom choice, or Fit when nothing (or something unknown) was stored. */
    fun storedResizeMode(stored: Int?): Int =
        when (stored) {
            RESIZE_FILL, RESIZE_ZOOM -> stored
            else -> RESIZE_FIT
        }
}
