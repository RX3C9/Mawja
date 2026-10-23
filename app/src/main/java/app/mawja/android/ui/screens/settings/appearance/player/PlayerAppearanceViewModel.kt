package app.mawja.android.ui.screens.settings.appearance.player

import dagger.hilt.android.lifecycle.HiltViewModel
import app.mawja.android.data.local.DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP
import app.mawja.android.data.local.DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP
import app.mawja.android.data.local.GestureOverlayStyle
import app.mawja.android.data.local.MiniBarSwipeAction
import app.mawja.android.data.local.MusicPlainControlColors
import app.mawja.android.data.local.MusicPlayerBackgroundStyle
import app.mawja.android.data.local.PlayerOverlayPreferences
import app.mawja.android.data.local.PlayerPreferences
import app.mawja.android.data.local.ScrubPreviewStyle
import app.mawja.android.data.local.SeekbarPaddingMode
import app.mawja.android.data.local.ShortsPlayerUiMode
import app.mawja.android.data.local.SliderStyle
import app.mawja.android.ui.screens.settings.SettingsViewModel
import javax.inject.Inject

@HiltViewModel
class PlayerAppearanceViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
    ) : SettingsViewModel() {
        private val overlayDefaults = PlayerOverlayPreferences()

        val sliderStyle = preferences.sliderStyle.asState(SliderStyle.COMPACT)
        val portraitSeekbarMode = preferences.portraitSeekbarPaddingMode.asState(SeekbarPaddingMode.FULL_WIDTH)
        val portraitSeekbarCustom = preferences.portraitSeekbarCustomPaddingDp.asState(DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP)
        val fullscreenSeekbarMode = preferences.fullscreenSeekbarPaddingMode.asState(SeekbarPaddingMode.DEFAULT)
        val fullscreenSeekbarCustom = preferences.fullscreenSeekbarCustomPaddingDp.asState(DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP)
        val scrubPreview = preferences.scrubPreviewStyle.asState(overlayDefaults.scrubPreviewStyle)
        val frameStepButtons = preferences.frameStepButtonsEnabled.asState(overlayDefaults.frameStepButtonsEnabled)
        val screenshotButton = preferences.screenshotButtonEnabled.asState(overlayDefaults.screenshotButtonEnabled)
        val musicBackground = preferences.musicPlayerBackgroundStyle.asState(MusicPlayerBackgroundStyle.BLUR_GRADIENT)
        val hideMusicArtwork = preferences.hideMusicPlayerArtwork.asState(false)
        val followLightTheme = preferences.musicPlayerFollowLightTheme.asState(false)
        val artworkControlColors = preferences.musicArtworkControlColors.asState(true)
        val animatedArtwork = preferences.animatedArtwork.asState(false)
        val animatedArtworkWifiOnly = preferences.animatedArtworkWifiOnly.asState(true)
        val plainControlColors = preferences.musicPlainControlColors.asState(MusicPlainControlColors.MONOCHROME)
        val sleepTimerButton = preferences.musicSleepTimerButton.asState(false)
        val adaptivePlayerSize = preferences.adaptivePlayerSizeEnabled.asState(true)
        val ambientMode = preferences.videoAmbientModeEnabled.asState(false)
        val groupedQualitySelector = preferences.groupedQualitySelectorEnabled.asState(false)
        val gestureOverlay = preferences.gestureOverlayStyle.asState(GestureOverlayStyle.CIRCULAR)
        val shortsUiMode = preferences.shortsPlayerUiMode.asState(ShortsPlayerUiMode.DEFAULT)
        val miniPlayerScale = preferences.miniPlayerScale.asState(MiniPlayerSize.NORMAL.scale)
        val miniPlayerSkip = preferences.miniPlayerShowSkipControls.asState(false)
        val miniPlayerNextPrev = preferences.miniPlayerShowNextPrevControls.asState(false)
        val miniBarSwipeLeft = preferences.miniBarSwipeLeftAction.asState(MiniBarSwipeAction.CLOSE)
        val miniBarSwipeRight = preferences.miniBarSwipeRightAction.asState(MiniBarSwipeAction.CLOSE)

        fun setSliderStyle(value: SliderStyle) = write { preferences.setSliderStyle(value) }

        fun setPortraitSeekbarMode(value: SeekbarPaddingMode) = write { preferences.setPortraitSeekbarPaddingMode(value) }

        fun setPortraitSeekbarCustom(value: Int) = write { preferences.setPortraitSeekbarCustomPaddingDp(value) }

        fun setFullscreenSeekbarMode(value: SeekbarPaddingMode) = write { preferences.setFullscreenSeekbarPaddingMode(value) }

        fun setFullscreenSeekbarCustom(value: Int) = write { preferences.setFullscreenSeekbarCustomPaddingDp(value) }

        fun setScrubPreview(value: ScrubPreviewStyle) = write { preferences.setScrubPreviewStyle(value) }

        fun setFrameStepButtons(value: Boolean) = write { preferences.setFrameStepButtonsEnabled(value) }

        fun setScreenshotButton(value: Boolean) = write { preferences.setScreenshotButtonEnabled(value) }

        fun setMusicBackground(value: MusicPlayerBackgroundStyle) = write { preferences.setMusicPlayerBackgroundStyle(value) }

        fun setHideMusicArtwork(value: Boolean) = write { preferences.setHideMusicPlayerArtwork(value) }

        fun setFollowLightTheme(value: Boolean) = write { preferences.setMusicPlayerFollowLightTheme(value) }

        fun setArtworkControlColors(value: Boolean) = write { preferences.setMusicArtworkControlColors(value) }

        fun setAnimatedArtwork(value: Boolean) = write { preferences.setAnimatedArtwork(value) }

        fun setAnimatedArtworkWifiOnly(value: Boolean) = write { preferences.setAnimatedArtworkWifiOnly(value) }

        fun setPlainControlColors(value: MusicPlainControlColors) = write { preferences.setMusicPlainControlColors(value) }

        fun setSleepTimerButton(value: Boolean) = write { preferences.setMusicSleepTimerButton(value) }

        fun setAdaptivePlayerSize(value: Boolean) = write { preferences.setAdaptivePlayerSizeEnabled(value) }

        fun setAmbientMode(value: Boolean) = write { preferences.setVideoAmbientModeEnabled(value) }

        fun setGroupedQualitySelector(value: Boolean) = write { preferences.setGroupedQualitySelectorEnabled(value) }

        fun setGestureOverlay(value: GestureOverlayStyle) = write { preferences.setGestureOverlayStyle(value) }

        fun setShortsUiMode(value: ShortsPlayerUiMode) = write { preferences.setShortsPlayerUiMode(value) }

        fun setMiniPlayerSize(value: MiniPlayerSize) = write { preferences.setMiniPlayerScale(value.scale) }

        fun setMiniPlayerSkip(value: Boolean) = write { preferences.setMiniPlayerShowSkipControls(value) }

        fun setMiniPlayerNextPrev(value: Boolean) = write { preferences.setMiniPlayerShowNextPrevControls(value) }

        fun setMiniBarSwipeLeft(value: MiniBarSwipeAction) = write { preferences.setMiniBarSwipeLeftAction(value) }

        fun setMiniBarSwipeRight(value: MiniBarSwipeAction) = write { preferences.setMiniBarSwipeRightAction(value) }
    }

/** The three mini player sizes the setting offers, stored as the player's scale factor. */
enum class MiniPlayerSize(
    val scale: Float,
) {
    SMALL(0.35f),
    NORMAL(0.45f),
    LARGE(0.55f),
    ;

    companion object {
        fun nearest(scale: Float): MiniPlayerSize = entries.minBy { kotlin.math.abs(it.scale - scale) }
    }
}
