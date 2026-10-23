package app.mawja.android.ui.screens.player.effects

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.mawja.android.player.display.ZoomFill
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import app.mawja.android.ui.screens.player.state.PlayerScreenState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** A new video starts unzoomed, and the zoom read-out fades on its own after each pinch. */
@Composable
internal fun PlayerZoomEffects(
    videoId: String,
    screenState: PlayerScreenState,
    onResetVideoAspectRatio: () -> Unit,
) {
    LaunchedEffect(videoId) {
        onResetVideoAspectRatio()
        screenState.zoomScale = 1f
        screenState.zoomOffsetX = 0f
        screenState.zoomOffsetY = 0f
        screenState.showZoomIndicator = false
        screenState.zoomIndicatorSequence = 0
    }

    LaunchedEffect(screenState) {
        snapshotFlow { screenState.zoomIndicatorSequence }
            .collectLatest {
                if (!screenState.showZoomIndicator) return@collectLatest
                delay(if (screenState.zoomScale > 1.02f) 900 else 600)
                screenState.showZoomIndicator = false
            }
    }
}

/**
 * Remembers the Fit / Fill / Zoom choice across videos and app restarts (#87): the stored mode is
 * applied once when the player opens, and every later change is written back.
 */
@Composable
internal fun RememberResizeModeEffect(screenState: PlayerScreenState) {
    val context = LocalContext.current
    LaunchedEffect(screenState) {
        val prefs = context.getSharedPreferences(RESIZE_PREFS, Context.MODE_PRIVATE)
        screenState.resizeMode = ZoomFill.storedResizeMode(prefs.getInt(RESIZE_KEY, ZoomFill.RESIZE_FIT))
        snapshotFlow { screenState.resizeMode }
            .drop(1)
            .collect { mode -> prefs.edit().putInt(RESIZE_KEY, mode).apply() }
    }
}

private const val RESIZE_PREFS = "mawja_player_display"
private const val RESIZE_KEY = "resize_mode"

@Composable
internal fun TouchLockDisableEffect(
    screenState: PlayerScreenState,
    lockModeEnabled: Boolean,
) {
    LaunchedEffect(lockModeEnabled) {
        if (!lockModeEnabled && screenState.isTouchLocked) {
            screenState.isTouchLocked = false
        }
    }
}
