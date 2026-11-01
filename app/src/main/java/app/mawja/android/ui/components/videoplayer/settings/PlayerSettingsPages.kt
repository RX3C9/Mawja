package app.mawja.android.ui.components.videoplayer.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mawja.android.R
import app.mawja.android.player.AudioTrackOption
import app.mawja.android.player.QualityOption
import app.mawja.android.player.SubtitleOption
import app.mawja.android.player.state.SubtitleOrigin
import app.mawja.android.player.stream.VideoCodecUtils
import app.mawja.android.player.subtitle.DualSubtitles
import app.mawja.android.utils.LanguageCatalog
import kotlinx.coroutines.launch
import app.mawja.android.ui.components.shared.FlowNavRow
import app.mawja.android.ui.components.shared.FlowRowGroup
import app.mawja.android.ui.components.shared.FlowSectionHeader
import app.mawja.android.ui.components.shared.FlowSelectionRow
import app.mawja.android.ui.components.shared.MediaAudioTrackRow
import app.mawja.android.ui.components.shared.MediaPlaybackSpeedPicker
import app.mawja.android.ui.components.shared.MediaQualitySelectorContent
import app.mawja.android.ui.components.shared.MediaQualitySelectorOption
import app.mawja.android.ui.components.shared.audioTrackFallbackLabel
import app.mawja.android.ui.components.shared.flowRowGroupShape

@Composable
internal fun PlayerSettingsQualityPage(
    availableQualities: List<QualityOption>,
    currentQuality: Int,
    currentQualityKey: String?,
    useGroupedQualitySelector: Boolean,
    onQualitySelected: (QualityOption) -> Unit,
) {
    val autoLabel = stringResource(R.string.quality_auto)
    val selectorOptions =
        availableQualities.map { quality ->
            MediaQualitySelectorOption(
                item = quality,
                height = quality.height,
                label = if (quality.height == 0) autoLabel else quality.displayLabel(),
                codecKey = quality.codecKey,
                codecLabel =
                    quality.codecKey
                        .takeIf { it.isNotBlank() }
                        ?.let(VideoCodecUtils::codecLabelFromKey)
                        .orEmpty(),
                selected = quality.isSelected(currentQuality, currentQualityKey),
            )
        }

    MediaQualitySelectorContent(
        options = selectorOptions,
        groupedByResolution = useGroupedQualitySelector,
        onOptionSelected = onQualitySelected,
    )
}

private fun QualityOption.displayLabel(): String = label.takeIf { it.isNotBlank() } ?: "${height}p"

private fun QualityOption.isSelected(
    currentQuality: Int,
    currentQualityKey: String?,
): Boolean =
    if (height == 0) {
        currentQuality == 0
    } else {
        streamKey != null && streamKey == currentQualityKey
    }

@Composable
internal fun PlayerSettingsSpeedPage(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onSpeedSelectionFinished: () -> Unit,
) {
    val context = LocalContext.current
    val playerPrefs =
        remember {
            app.mawja.android.data.local
                .PlayerPreferences(context)
        }
    val customSpeedsEnabled by playerPrefs.customSpeedsEnabled.collectAsState(initial = false)
    val customSpeedPresetsRaw by playerPrefs.customSpeedPresets.collectAsState(initial = "")
    val speedSliderEnabled by playerPrefs.speedSliderEnabled.collectAsState(initial = false)

    MediaPlaybackSpeedPicker(
        currentSpeed = currentSpeed,
        sliderEnabled = speedSliderEnabled,
        customSpeedsEnabled = customSpeedsEnabled,
        customSpeedPresetsRaw = customSpeedPresetsRaw,
        onSpeedSelected = onSpeedSelected,
        onSpeedRowSelected = { onSpeedSelectionFinished() },
    )
}

@Composable
internal fun PlayerSettingsAudioPage(
    availableAudioTracks: List<AudioTrackOption>,
    currentAudioTrack: Int,
    onTrackSelected: (Int) -> Unit,
) {
    FlowRowGroup {
        availableAudioTracks.forEachIndexed { index, track ->
            MediaAudioTrackRow(
                label = audioTrackDisplayLabel(track, index),
                supportingText = track.language.takeIf { it.isNotBlank() },
                selected = index == currentAudioTrack,
                shape = flowRowGroupShape(index, availableAudioTracks.size),
                onClick = { onTrackSelected(index) },
            )
        }
    }
}

@Composable
internal fun PlayerSettingsSubtitlesPage(
    availableSubtitles: List<SubtitleOption>,
    selectedSubtitleUrl: String?,
    subtitlesEnabled: Boolean,
    onSubtitleSelected: (Int) -> Unit,
    onDisableSubtitles: () -> Unit,
    onShowStyleCustomizer: () -> Unit,
    onAddSubtitleFile: (() -> Unit)? = null,
    subtitleOffsetMs: Long = 0L,
    onSubtitleOffsetChange: ((Long) -> Unit)? = null,
) {
    val rowCount = availableSubtitles.size + 1
    FlowRowGroup {
        FlowSelectionRow(
            title = stringResource(R.string.off),
            selected = !subtitlesEnabled,
            shape = flowRowGroupShape(0, rowCount),
            onClick = onDisableSubtitles,
        )
        availableSubtitles.forEachIndexed { index, subtitle ->
            FlowSelectionRow(
                title = subtitleRowTitle(subtitle, index),
                supportingText =
                    when (subtitle.origin) {
                        SubtitleOrigin.FILE -> {
                            subtitle.detail?.takeIf { it != subtitle.label }
                                ?: stringResource(R.string.subtitle_origin_file)
                        }

                        SubtitleOrigin.EMBEDDED -> {
                            stringResource(R.string.subtitle_origin_embedded)
                        }

                        SubtitleOrigin.ONLINE -> {
                            subtitle.language.takeIf { it.isNotBlank() }
                        }
                    },
                selected = subtitle.url == selectedSubtitleUrl && subtitlesEnabled,
                shape = flowRowGroupShape(index + 1, rowCount),
                onClick = { onSubtitleSelected(index) },
            )
        }
    }
    if (onSubtitleOffsetChange != null && subtitlesEnabled) {
        Spacer(modifier = Modifier.height(SubtitleStyleRowSpacing))
        SubtitleTimingCard(offsetMs = subtitleOffsetMs, onOffsetChange = onSubtitleOffsetChange)
    }
    if (subtitlesEnabled) {
        Spacer(modifier = Modifier.height(SubtitleStyleRowSpacing))
        SecondSubtitleRows(
            primaryLanguage = availableSubtitles.firstOrNull { it.url == selectedSubtitleUrl }?.language,
        )
    }
    Spacer(modifier = Modifier.height(SubtitleStyleRowSpacing))
    val navRows = if (onAddSubtitleFile != null) 2 else 1
    FlowRowGroup {
        if (onAddSubtitleFile != null) {
            FlowNavRow(
                title = stringResource(R.string.subtitle_add_file),
                leadingIcon = Icons.Filled.FileOpen,
                shape = flowRowGroupShape(0, navRows),
                onClick = onAddSubtitleFile,
            )
        }
        FlowNavRow(
            title = stringResource(R.string.subtitle_style),
            leadingIcon = Icons.Filled.Tune,
            shape = flowRowGroupShape(navRows - 1, navRows),
            onClick = onShowStyleCustomizer,
        )
    }
}

/**
 * Dual subtitles in the player: Off, Arabic, English, and the saved language when it is another
 * one. The choice is the same saved setting as Settings > Player > Second subtitle language.
 */
@Composable
private fun SecondSubtitleRows(primaryLanguage: String?) {
    val context = LocalContext.current
    val preferences = remember { app.mawja.android.data.local.PlayerPreferences(context) }
    val scope = rememberCoroutineScope()
    val saved by preferences.secondarySubtitleLanguage.collectAsState(initial = DualSubtitles.OFF)
    val choices =
        buildList {
            add(DualSubtitles.OFF)
            add("ar")
            add("en")
            if (saved != DualSubtitles.OFF && none { DualSubtitles.sameLanguage(it, saved) }) add(saved)
        }.filter { it == DualSubtitles.OFF || !DualSubtitles.sameLanguage(it, primaryLanguage) || it == saved }
    FlowSectionHeader(text = stringResource(R.string.dual_subtitles_player_header))
    FlowRowGroup {
        choices.forEachIndexed { index, code ->
            val title =
                if (code == DualSubtitles.OFF) {
                    stringResource(R.string.off)
                } else {
                    LanguageCatalog.displayName(code)
                }
            FlowSelectionRow(
                title = title,
                supportingText =
                    if (code != DualSubtitles.OFF && DualSubtitles.sameLanguage(code, primaryLanguage)) {
                        stringResource(R.string.dual_subtitles_same_as_primary)
                    } else {
                        null
                    },
                selected = if (code == DualSubtitles.OFF) saved == DualSubtitles.OFF else DualSubtitles.sameLanguage(code, saved),
                shape = flowRowGroupShape(index, choices.size),
                onClick = { scope.launch { preferences.setSecondarySubtitleLanguage(code) } },
            )
        }
    }
}

/** The track's name, with what kind of track it is when that is not a plain authored one. */
@Composable
private fun subtitleRowTitle(
    subtitle: SubtitleOption,
    index: Int,
): String {
    val name = subtitle.label.ifBlank { stringResource(R.string.subtitle_track_number, index + 1) }
    val kind =
        when {
            subtitle.isTranslated -> stringResource(R.string.subtitle_translated)
            subtitle.isAutoGenerated -> stringResource(R.string.quality_auto)
            subtitle.isForced -> stringResource(R.string.subtitle_forced)
            else -> return name
        }
    return stringResource(R.string.subtitle_auto_generated_template, name, kind)
}

private val SubtitleStyleRowSpacing = 12.dp

@Composable
internal fun audioTrackDisplayLabel(
    track: AudioTrackOption?,
    fallbackIndex: Int,
): String = track?.label?.takeIf { it.isNotBlank() } ?: audioTrackFallbackLabel(fallbackIndex)
