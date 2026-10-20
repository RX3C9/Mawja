package app.mawja.android.ui.screens.settings.playback

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.mawja.android.R
import app.mawja.android.data.local.SpeedSwipeSide
import app.mawja.android.player.stream.CaptionTrackResolver
import app.mawja.android.ui.components.shared.playbackSpeedLabel
import app.mawja.android.utils.LanguageCatalog
import app.mawja.android.utils.formatMultiplierLabel

@Composable
internal fun countdownLabel(seconds: Int): String =
    if (seconds <= 0) {
        stringResource(R.string.player_settings_autoplay_countdown_none)
    } else {
        pluralStringResource(R.plurals.player_settings_autoplay_countdown_seconds_template, seconds, seconds)
    }

@Composable
internal fun longPressSpeedLabel(speed: Float): String =
    if (speed <= 0f) stringResource(R.string.player_appearance_long_press_speed_disabled) else playbackSpeedLabel(speed)

@Composable
internal fun speedSwipeSideLabel(side: SpeedSwipeSide): String =
    stringResource(
        when (side) {
            SpeedSwipeSide.OFF -> R.string.player_settings_speed_swipe_off
            SpeedSwipeSide.LEFT -> R.string.player_settings_speed_swipe_left
            SpeedSwipeSide.RIGHT -> R.string.player_settings_speed_swipe_right
        },
    )

@Composable
internal fun speedSwipeStepLabel(step: Float): String =
    stringResource(R.string.player_settings_speed_swipe_step_value, formatMultiplierLabel(step))

@Composable
internal fun doubleTapSeekLabel(seconds: Int): String =
    if (seconds <= 0) {
        stringResource(R.string.player_settings_double_tap_seek_off)
    } else {
        pluralStringResource(R.plurals.player_settings_autoplay_countdown_seconds_template, seconds, seconds)
    }

@Composable
internal fun audioLanguageLabel(code: String): String =
    if (code == PlaybackSettingsViewModel.ORIGINAL_AUDIO) {
        stringResource(R.string.player_settings_audio_original)
    } else {
        LanguageCatalog.displayName(code)
    }

@Composable
internal fun subtitleLanguageLabel(code: String): String =
    if (code == CaptionTrackResolver.NO_PREFERRED_LANGUAGE) {
        stringResource(R.string.player_settings_subtitle_language_none)
    } else {
        LanguageCatalog.displayName(code)
    }

@Composable
internal fun shortsModeLabel(
    mode: String,
    seconds: Int,
): String =
    when (mode) {
        ShortsPlaybackModes.AUTO_NEXT -> {
            stringResource(R.string.player_settings_shorts_playback_mode_auto_next)
        }

        ShortsPlaybackModes.AUTO_INTERVAL -> {
            pluralStringResource(R.plurals.player_settings_shorts_playback_mode_auto_interval_summary, seconds, seconds)
        }

        else -> {
            stringResource(R.string.player_settings_shorts_playback_mode_loop)
        }
    }
