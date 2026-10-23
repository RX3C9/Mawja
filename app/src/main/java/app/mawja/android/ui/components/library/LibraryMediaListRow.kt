package app.mawja.android.ui.components.library

import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import app.mawja.android.data.localmedia.LocalMediaIds
import app.mawja.android.data.model.Video
import app.mawja.android.data.music.model.MusicTrack
import app.mawja.android.ui.components.music.item.MusicTrackItem
import app.mawja.android.ui.components.music.sheet.LocalMusicMenus
import app.mawja.android.ui.components.shared.MediaRow
import app.mawja.android.ui.components.shared.MediaRowDefaults
import app.mawja.android.ui.components.shared.MediaThumbnail
import app.mawja.android.ui.components.shared.MediaThumbnailDefaults
import app.mawja.android.ui.components.shared.card.LocalVideoCardPreferences
import app.mawja.android.ui.components.shared.quickactions.VideoQuickActionsBottomSheet

/**
 * A library entry as a row. Long press opens the item's menu, where [removeLabel] runs
 * [onRemove] as the screen's own remove, beside the inline [action] that does the same.
 * Files on the device get no online menu: its rows would write a local id into history,
 * likes and sync as if it were a YouTube video.
 */
@Composable
internal fun LibraryMediaListRow(
    track: MusicTrack,
    video: Video,
    isMusic: Boolean,
    title: String,
    onVideoClick: () -> Unit,
    onMusicClick: () -> Unit,
    removeLabel: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    thumbnailUrl: String? = null,
    durationSeconds: Int? = null,
    thumbnailWidth: Dp = MediaThumbnailDefaults.VideoWidth,
    showWatchProgress: Boolean = LocalVideoCardPreferences.current.showWatchProgress,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    action: @Composable () -> Unit,
) {
    val isLocal = LocalMediaIds.isLocal(track.videoId)
    // With multi-select (#84) a long press starts selecting; the item's menu is then one tap
    // away in the selection toolbar.
    if (isMusic) {
        val musicMenus = LocalMusicMenus.current
        MusicTrackItem(
            track = track,
            onClick = onMusicClick,
            modifier = modifier,
            showMenu = false,
            containerColor =
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = MediaRowDefaults.SELECTION_ALPHA) else Color.Transparent,
            leadingContent = if (selectionMode) ({ Checkbox(checked = selected, onCheckedChange = { onMusicClick() }) }) else null,
            trailingContent = if (selectionMode) null else ({ action() }),
            onLongClick =
                when {
                    selectionMode -> null
                    onLongPress != null -> onLongPress
                    isLocal -> null
                    else -> ({ musicMenus.openSong(track) })
                },
        )
    } else {
        var showMenu by remember { mutableStateOf(false) }
        MediaRow(
            title = title,
            modifier = modifier,
            subtitle = subtitle,
            selected = selected,
            onClick = onVideoClick,
            onLongClick =
                when {
                    selectionMode -> null
                    onLongPress != null -> onLongPress
                    isLocal -> null
                    else -> ({ showMenu = true })
                },
            leading = if (selectionMode) ({ Checkbox(checked = selected, onCheckedChange = { onVideoClick() }) }) else null,
            trailing = if (selectionMode) null else ({ action() }),
        ) {
            MediaThumbnail(
                videoId = track.videoId,
                thumbnailUrl = thumbnailUrl,
                durationSeconds = durationSeconds,
                showWatchProgress = showWatchProgress,
                width = thumbnailWidth,
            )
        }
        if (showMenu) {
            VideoQuickActionsBottomSheet(
                video = video,
                onDismiss = { showMenu = false },
                onRemoveFromCollection = onRemove,
                removeFromCollectionLabel = removeLabel,
            )
        }
    }
}
