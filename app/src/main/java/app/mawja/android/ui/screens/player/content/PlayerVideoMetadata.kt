package app.mawja.android.ui.screens.player.content

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import app.mawja.android.R
import app.mawja.android.data.model.Video
import app.mawja.android.data.model.VideoCollaborator
import app.mawja.android.data.model.needsCollaboratorResolution
import app.mawja.android.data.model.toVideo
import app.mawja.android.data.model.uploadDateMillis
import app.mawja.android.data.repository.VideoCollaboratorResolver
import app.mawja.android.ui.components.rememberDeArrowResult
import app.mawja.android.ui.components.shared.card.LocalVideoCardPreferences
import app.mawja.android.ui.components.shared.card.rememberKnownOriginalTitle
import app.mawja.android.ui.components.shared.rememberDateDisplaySettings
import app.mawja.android.ui.screens.player.state.VideoPlayerUiState
import app.mawja.android.utils.DateContext
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Display values derived from the played [Video] and the loaded stream info.
 */
@Immutable
internal data class PlayerVideoMetadata(
    val resolvedVideoTitle: String,
    val resolvedCollaborators: List<VideoCollaborator>,
    val resolvedChannelName: String,
    val streamUploadDate: String?,
    val dialogVideo: Video,
)

@Composable
internal fun rememberPlayerVideoMetadata(
    video: Video,
    uiState: VideoPlayerUiState,
    deArrowEnabled: Boolean,
    context: Context,
): PlayerVideoMetadata {
    val deArrowResult = rememberDeArrowResult(video, deArrowEnabled, useRandomFrame = false)
    val cachedOriginal = rememberKnownOriginalTitle(video.id, LocalVideoCardPreferences.current.originalTitlesEnabled)
    val resolvedVideoTitle = deArrowResult?.title ?: cachedOriginal ?: video.title
    val needsCollaboratorResolution = video.needsCollaboratorResolution()
    val resolvedCollaborators by produceState(
        initialValue = video.collaborators,
        key1 = video.id,
        key2 = video.collaborators,
        key3 = needsCollaboratorResolution,
    ) {
        value =
            if (needsCollaboratorResolution) {
                VideoCollaboratorResolver.resolve(video.id)
            } else {
                video.collaborators
            }
    }
    val resolvedChannelName =
        remember(video.channelName, resolvedCollaborators) {
            resolvedCollaborators
                .map { it.name }
                .filter { it.isNotBlank() }
                .takeIf { it.size > 1 }
                ?.joinToString(" ${context.getString(R.string.conjunction_and)} ")
                ?: video.channelName
        }
    val dateSettings = rememberDateDisplaySettings()
    val streamUploadDate =
        remember(video.uploadDate, uiState.isArchivedLivestream, dateSettings) {
            val rawDate = video.uploadDate.takeIf { it.isNotBlank() } ?: return@remember null
            if (uiState.isArchivedLivestream && !rawDate.startsWith("Streamed", ignoreCase = true)) {
                context.getString(R.string.streamed_date_template, rawDate)
            } else {
                rawDate
            }
        }
    val dialogVideo = video

    return PlayerVideoMetadata(
        resolvedVideoTitle = resolvedVideoTitle,
        resolvedCollaborators = resolvedCollaborators,
        resolvedChannelName = resolvedChannelName,
        streamUploadDate = streamUploadDate,
        dialogVideo = dialogVideo,
    )
}
