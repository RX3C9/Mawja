package app.mawja.android.ui.components.videoplayer.gesture

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import app.mawja.android.player.EnhancedPlayerManager

private val LongPressBottomExclusion = 40.dp
private val LongPressBottomExclusionFullscreen = 28.dp

internal fun Modifier.playerTapGestures(
    isSpeedBoostActive: State<Boolean>,
    onSpeedBoostChange: State<(Boolean) -> Unit>,
    showControls: State<Boolean>,
    onShowControlsChange: State<(Boolean) -> Unit>,
    onShowSeekBackChange: State<(Boolean) -> Unit>,
    onShowSeekForwardChange: State<(Boolean) -> Unit>,
    onSeekAccumulate: State<(Int) -> Unit>,
    currentPosition: State<() -> Long>,
    duration: State<Long>,
    onNormalSpeedChange: State<(Float) -> Unit>,
    isFullscreen: State<Boolean>,
    doubleTapSeekMs: State<Long>,
    seekZoneFraction: State<Float>,
    seekZoneReachesEdges: State<Boolean>,
    longPressPlaybackSpeed: State<Float>,
    isSeekForwardActive: State<Boolean>,
    isSeekBackActive: State<Boolean>,
    sideEdgeIgnorePx: State<Float>,
    onTapWindowChange: State<(Boolean) -> Unit>,
    onSeekRipple: State<(Offset) -> Unit>,
    onHoldSpeedChange: State<(Float) -> Unit>,
    haptics: HapticFeedback,
): Modifier {
    val currentIsSpeedBoostActive by isSpeedBoostActive
    val currentOnSpeedBoostChange by onSpeedBoostChange
    val currentShowControls by showControls
    val currentOnShowControlsChange by onShowControlsChange
    val currentOnShowSeekBackChange by onShowSeekBackChange
    val currentOnShowSeekForwardChange by onShowSeekForwardChange
    val currentOnSeekAccumulate by onSeekAccumulate
    val currentPositionProvider by currentPosition
    val currentDuration by duration
    val currentOnNormalSpeedChange by onNormalSpeedChange
    val currentIsFullscreen by isFullscreen
    val currentDoubleTapSeekMs by doubleTapSeekMs
    val currentSeekZoneFraction by seekZoneFraction
    val currentSeekZoneReachesEdges by seekZoneReachesEdges
    val currentLongPressPlaybackSpeed by longPressPlaybackSpeed
    val currentIsSeekForwardActive by isSeekForwardActive
    val currentIsSeekBackActive by isSeekBackActive
    val currentSideEdgeIgnorePx by sideEdgeIgnorePx
    val currentOnTapWindowChange by onTapWindowChange
    val currentOnSeekRipple by onSeekRipple
    val currentOnHoldSpeedChange by onHoldSpeedChange

    return this.pointerInput(Unit) {
        var accumulatedForwardMs = 0L
        var accumulatedBackMs = 0L
        var lastForwardTapTime = 0L
        var lastBackTapTime = 0L
        var pendingForwardTargetMs: Long? = null
        var pendingBackTargetMs: Long? = null
        var speedBeforeLongPress: Float? = null
        var holdBoostSpeed = Float.NaN
        var lastHoldSpeed = Float.NaN

        var revealedOnTap = false
        var hidePending = false

        fun zoneOf(x: Float): TapZone =
            tapZoneOf(
                x = x,
                width = size.width.toFloat(),
                sideFraction = if (currentDoubleTapSeekMs > 0L) currentSeekZoneFraction else 0f,
                edgePx = tapEdgePx(currentSeekZoneReachesEdges, currentSideEdgeIgnorePx),
            )

        fun applyZoneSeek(forward: Boolean) {
            val manager = EnhancedPlayerManager.getInstance()
            val player = manager.getPlayer()
            val isLive = manager.playerState.value.isLive || player?.isCurrentMediaItemLive == true
            val playerPosition = player?.currentPosition ?: currentPositionProvider()
            val step = currentDoubleTapSeekMs
            val now = SystemClock.uptimeMillis()

            val target =
                if (forward) {
                    currentOnShowSeekBackChange(false)
                    accumulatedBackMs = 0L
                    lastBackTapTime = 0L
                    pendingBackTargetMs = null

                    val continuing = now - lastForwardTapTime < DOUBLE_TAP_SEEK_WINDOW_MS
                    accumulatedForwardMs = if (continuing) accumulatedForwardMs + step else step
                    lastForwardTapTime = now
                    val base = pendingForwardTargetMs?.takeIf { continuing } ?: playerPosition
                    (base + step).coerceAtMost(currentDuration).also {
                        pendingForwardTargetMs = it
                        currentOnSeekAccumulate((accumulatedForwardMs / 1000L).toInt())
                        currentOnShowSeekForwardChange(true)
                    }
                } else {
                    currentOnShowSeekForwardChange(false)
                    accumulatedForwardMs = 0L
                    lastForwardTapTime = 0L
                    pendingForwardTargetMs = null

                    val continuing = now - lastBackTapTime < DOUBLE_TAP_SEEK_WINDOW_MS
                    accumulatedBackMs = if (continuing) accumulatedBackMs + step else step
                    lastBackTapTime = now
                    val base = pendingBackTargetMs?.takeIf { continuing } ?: playerPosition
                    (base - step).coerceAtLeast(0L).also {
                        pendingBackTargetMs = it
                        currentOnSeekAccumulate(-(accumulatedBackMs / 1000L).toInt())
                        currentOnShowSeekBackChange(true)
                    }
                }

            if (isLive) manager.seekToLiveTimeline(target) else manager.seekTo(target)
            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        }

        fun togglePlayPause() {
            val manager = EnhancedPlayerManager.getInstance()
            val player = manager.getPlayer() ?: return
            when {
                player.playbackState == Player.STATE_ENDED -> manager.replay()
                player.isPlaying -> manager.pause()
                else -> manager.play()
            }
        }

        detectPlayerTaps(
            isEdge = { offset -> zoneOf(offset.x) == TapZone.EDGE },
            isNearEdge = { offset -> offset.x < currentSideEdgeIgnorePx || offset.x > size.width - currentSideEdgeIgnorePx },
            onTapWindowChange = { open -> currentOnTapWindowChange(open) },
            onTapUp = { offset ->
                if (!currentIsSpeedBoostActive) {
                    val zone = zoneOf(offset.x)
                    val continuesActiveSeek =
                        (zone == TapZone.BACK && currentIsSeekBackActive) ||
                            (zone == TapZone.FORWARD && currentIsSeekForwardActive)
                    when {
                        continuesActiveSeek -> {
                            currentOnSeekRipple(offset)
                            applyZoneSeek(forward = zone == TapZone.FORWARD)
                        }

                        !currentShowControls -> {
                            currentOnShowControlsChange(true)
                            revealedOnTap = true
                            hidePending = false
                        }

                        else -> {
                            hidePending = true
                            revealedOnTap = false
                        }
                    }
                }
            },
            onSingleTapConfirmed = {
                if (hidePending) currentOnShowControlsChange(false)
                hidePending = false
                revealedOnTap = false
            },
            onDoubleTap = { offset ->
                hidePending = false
                val zone = zoneOf(offset.x)
                if (zone != TapZone.CENTER && revealedOnTap) {
                    currentOnShowControlsChange(false)
                }
                revealedOnTap = false

                if (zone == TapZone.BACK || zone == TapZone.FORWARD) currentOnSeekRipple(offset)
                when (zone) {
                    TapZone.BACK -> applyZoneSeek(forward = false)
                    TapZone.FORWARD -> applyZoneSeek(forward = true)
                    TapZone.CENTER -> togglePlayPause()
                    TapZone.EDGE -> Unit
                }
            },
            onLongPress = { offset ->
                if (currentLongPressPlaybackSpeed <= 0f) return@detectPlayerTaps false

                val bottomExclusion = if (currentIsFullscreen) LongPressBottomExclusionFullscreen else LongPressBottomExclusion
                if (offset.y > size.height - bottomExclusion.toPx()) return@detectPlayerTaps false

                val manager = EnhancedPlayerManager.getInstance()
                val player = manager.getPlayer()
                if (player != null && !currentIsSpeedBoostActive) {
                    val restoreSpeed =
                        manager.playerState.value.playbackSpeed
                            .takeIf { it > 0f }
                            ?: player.playbackParameters.speed
                    speedBeforeLongPress = restoreSpeed
                    currentOnNormalSpeedChange(restoreSpeed)
                    currentOnSpeedBoostChange(true)
                    val boosted =
                        PlayerSpeedBoost.boostedPlaybackSpeed(
                            currentSpeed = restoreSpeed,
                            targetSpeed = currentLongPressPlaybackSpeed,
                        )
                    manager.setPlaybackSpeed(boosted)
                    // Live streams keep their speed, so a hold there never tunes it by sliding.
                    val isLive = manager.playerState.value.isLive || player.isCurrentMediaItemLive
                    holdBoostSpeed = if (isLive) Float.NaN else boosted
                    lastHoldSpeed = boosted
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    true
                } else {
                    false
                }
            },
            onLongPressDrag = { travel ->
                val boost = holdBoostSpeed
                if (!boost.isNaN()) {
                    val next =
                        HoldSpeedDrag.speedFor(
                            boostSpeed = boost,
                            dx = travel.x,
                            dy = travel.y,
                            stepTravelPx = HoldSpeedStepTravel.toPx(),
                        )
                    if (next != lastHoldSpeed) {
                        EnhancedPlayerManager.getInstance().setPlaybackSpeed(next)
                        lastHoldSpeed = next
                        currentOnHoldSpeedChange(next)
                        haptics.performHapticFeedback(
                            if (next == HoldSpeedDrag.MIN_SPEED || next == HoldSpeedDrag.MAX_SPEED) {
                                HapticFeedbackType.GestureThresholdActivate
                            } else {
                                HapticFeedbackType.SegmentFrequentTick
                            },
                        )
                    }
                }
            },
            onLongPressReleased = {
                holdBoostSpeed = Float.NaN
                lastHoldSpeed = Float.NaN
                currentOnHoldSpeedChange(0f)
                val restoreSpeed = speedBeforeLongPress
                if (restoreSpeed != null) {
                    EnhancedPlayerManager.getInstance().setPlaybackSpeed(restoreSpeed)
                    currentOnNormalSpeedChange(restoreSpeed)
                    speedBeforeLongPress = null
                    currentOnSpeedBoostChange(false)
                    haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                }
            },
        )
    }
}

/**
 * Tap detection that reports the up event before the double-tap window has resolved.
 *
 * `detectTapGestures` withholds its tap callback until the double-tap timeout expires whenever an
 * `onDoubleTap` is supplied. The player needs the opposite ordering: [onTapUp] runs the instant the
 * finger lifts, so the controls reveal and a running seek accumulation continues without the wait,
 * while [onSingleTapConfirmed] still fires only once the window closes with no second touch.
 *
 * A touch that starts on [isEdge] may belong to the system back gesture, whose down reaches the app
 * before the system cancels it: such a touch never opens the double-tap window and never counts as a
 * second tap. The double tap fires on the second up, which a cancel never is. [isNearEdge] is the
 * same strip whatever the zone setting: a slow back swipe can outlast the long-press timeout before
 * the system takes it, so a touch starting there never long-presses even where taps reach the edge.
 *
 * [onTapWindowChange] reports the double-tap window, so the controls the first tap revealed can
 * leave the second tap to this detector (#1308). It is closed in a finally: the modifier is removed
 * mid-gesture on minimise, PiP and touch lock, and a window left open would lock the controls.
 */
private suspend fun PointerInputScope.detectPlayerTaps(
    isEdge: (Offset) -> Boolean,
    isNearEdge: (Offset) -> Boolean,
    onTapWindowChange: (Boolean) -> Unit,
    onTapUp: (Offset) -> Unit,
    onSingleTapConfirmed: () -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Boolean,
    onLongPressDrag: (Offset) -> Unit,
    onLongPressReleased: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startsAtEdge = isEdge(down.position)

        val firstUp =
            if (startsAtEdge || isNearEdge(down.position)) {
                awaitStillUp(down)
            } else {
                try {
                    withTimeout(viewConfiguration.longPressTimeoutMillis) { awaitStillUp(down) }
                } catch (_: PointerEventTimeoutCancellationException) {
                    if (onLongPress(down.position)) {
                        awaitHoldRelease(down, onLongPressDrag)
                    } else {
                        waitForUpOrCancellation()
                    }
                    onLongPressReleased()
                    return@awaitEachGesture
                }
            } ?: return@awaitEachGesture

        onTapUp(firstUp.position)
        if (startsAtEdge) {
            onSingleTapConfirmed()
            return@awaitEachGesture
        }

        onTapWindowChange(true)
        val secondUp =
            try {
                val secondDown =
                    withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                        awaitFirstDown(requireUnconsumed = false)
                    }
                secondDown?.takeUnless { isEdge(it.position) }?.let { awaitStillUp(it) }
            } finally {
                onTapWindowChange(false)
            }
        if (secondUp == null) {
            onSingleTapConfirmed()
            return@awaitEachGesture
        }

        onDoubleTap(secondUp.position)
    }
}

/** The up of [down], or null once it moves past touch slop, leaves the bounds or is consumed. */
private suspend fun AwaitPointerEventScope.awaitStillUp(down: PointerInputChange): PointerInputChange? {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return null
        if (change.changedToUp()) return change
        if (change.isConsumed || change.isOutOfBounds(size, extendedTouchPadding)) return null
        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return null
    }
}

/**
 * Follows a boosted hold until the finger lifts, reporting its travel from [down] so sliding while
 * holding tunes the speed. Read on the initial pass and consumed once it moves, so the seek,
 * brightness and volume drags underneath, and the player sheet, leave a tuning hold alone.
 */
private suspend fun AwaitPointerEventScope.awaitHoldRelease(
    down: PointerInputChange,
    onDrag: (Offset) -> Unit,
) {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: return
        if (!change.pressed) return
        if (change.isConsumed) return
        if (change.positionChanged()) {
            onDrag(change.position - down.position)
            change.consume()
        }
    }
}
