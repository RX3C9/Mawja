package app.mawja.android.ui.components.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import app.mawja.android.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.mawja.android.data.model.SponsorBlockSegment
import org.schabi.newpipe.extractor.stream.StreamSegment
import kotlin.math.abs

/**
 * The thin strip under the video that [MediaSeekBar] shows when the controls are hidden.
 *
 * It cannot be a Material `Slider`: `SliderImpl` applies `minimumInteractiveComponentSize()`, so it
 * would claim 48dp of touch height against a bar that sits flush with the video and is 14dp tall.
 * It draws the same track and runs its own pointer handling at its real size.
 */
@Composable
internal fun EdgeAlignedSeekbar(
    displayValueProvider: () -> Float,
    enabled: Boolean,
    chapters: List<StreamSegment>,
    sponsorSegments: List<SponsorBlockSegment>,
    sponsorColors: Map<String, Color>,
    duration: Long,
    bufferedValue: Float,
    colors: SeekTrackColors,
    expansionProvider: () -> Float,
    thumbScaleProvider: () -> Float,
    onScrub: (Float) -> Unit,
    onPointerActiveChange: (Boolean) -> Unit,
) {
    val thumbFillColor = colors.playhead
    val accentColor = colors.active
    val seekLabel = stringResource(R.string.a11y_seek_bar)

    Canvas(
        modifier =
            Modifier
                .fillMaxSize()
                // TalkBack (#160): the strip draws itself and handles its own touches, so it states
                // the playhead and takes the volume-key / swipe adjust gesture as a seek.
                .semantics {
                    contentDescription = seekLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(displayValueProvider().coerceIn(0f, 1f), 0f..1f)
                    if (enabled) {
                        setProgress { target ->
                            onPointerActiveChange(true)
                            onScrub(target.coerceIn(0f, 1f))
                            onPointerActiveChange(false)
                            true
                        }
                    }
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput

                    fun valueForX(x: Float): Float {
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        return (x / width).coerceIn(0f, 1f)
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onPointerActiveChange(true)
                        down.consume()
                        onScrub(valueForX(down.position.x))

                        try {
                            var activePointerId = down.id
                            var lastValue = valueForX(down.position.x)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change =
                                    event.changes.firstOrNull { it.id == activePointerId }
                                        ?: event.changes.firstOrNull { it.pressed }
                                        ?: break

                                activePointerId = change.id
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }

                                if (change.positionChange() != Offset.Zero) {
                                    val newValue = valueForX(change.position.x)
                                    if (abs(newValue - lastValue) > 0.0001f) {
                                        lastValue = newValue
                                        onScrub(newValue)
                                    }
                                }
                                change.consume()
                            }
                        } finally {
                            onPointerActiveChange(false)
                        }
                    }
                },
    ) {
        val displayValue = displayValueProvider()
        val expansion = expansionProvider()
        drawSeekTrack(
            fraction = displayValue,
            expansion = expansion,
            chapters = chapters,
            sponsorSegments = sponsorSegments,
            sponsorColors = sponsorColors,
            duration = duration,
            bufferedValue = bufferedValue,
            colors = colors,
            bottomAligned = true,
        )

        val scale = thumbScaleProvider()
        if (scale > 0f) {
            val trackHeightPx = lerp(RestTrackHeight.toPx(), ActiveTrackHeight.toPx(), expansion)
            val trackCenterY = size.height - trackHeightPx / 2f
            val width = size.width
            val thumbRadius = 7.dp.toPx() * scale
            val thumbX =
                if (width > thumbRadius * 2f) {
                    (width * displayValue).coerceIn(thumbRadius, width - thumbRadius)
                } else {
                    width * displayValue
                }
            drawCircle(
                color = accentColor.copy(alpha = 0.24f),
                radius = thumbRadius + 8.dp.toPx(),
                center = Offset(thumbX, trackCenterY),
            )
            drawCircle(
                color = thumbFillColor,
                radius = thumbRadius,
                center = Offset(thumbX, trackCenterY),
            )
            drawCircle(
                color = accentColor,
                radius = thumbRadius,
                center = Offset(thumbX, trackCenterY),
                style = Stroke(width = 3.dp.toPx()),
            )
        }
    }
}
