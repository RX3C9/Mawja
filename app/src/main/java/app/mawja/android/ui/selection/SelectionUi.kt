/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.selection

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.data.model.Video
import app.mawja.android.ui.components.shared.CollectionEditDialog
import app.mawja.android.ui.components.shared.CollectionTarget
import app.mawja.android.ui.components.shared.FlowSelectionAction
import app.mawja.android.ui.components.shared.MergeIntoCollectionSheet
import app.mawja.android.ui.components.shared.quickactions.QuickActionsViewModel
import app.mawja.android.ui.components.shared.quickactions.sharedQuickActionsViewModel
import kotlinx.coroutines.launch

/**
 * The bulk actions shared by every multi-select list (#84), in toolbar order: play next, add to
 * playlist, download, then the list's own [delete] and, with one row ticked, [more] for its menu.
 * Each runs on [videos] (already in list order) and confirms on the app's snackbar.
 */
@Composable
fun rememberSelectionActions(
    videos: () -> List<Video>,
    onDone: () -> Unit,
    delete: FlowSelectionAction? = null,
    more: (() -> Unit)? = null,
    count: Int,
    offerDownload: Boolean = true,
    actions: SelectionActionsViewModel = hiltViewModel(),
    quickActions: QuickActionsViewModel = sharedQuickActionsViewModel(),
): SelectionActionsState {
    val context = LocalContext.current
    var addTargets by remember { mutableStateOf<List<Video>?>(null) }
    val playNextLabel = stringResource(R.string.play_next)
    val addLabel = stringResource(R.string.add_to_playlist)
    val downloadLabel = stringResource(R.string.download)
    val moreLabel = stringResource(R.string.more_options)
    val list =
        buildList {
            add(
                FlowSelectionAction(Icons.AutoMirrored.Outlined.PlaylistPlay, playNextLabel) {
                    val picked = videos()
                    if (picked.isNotEmpty()) {
                        actions.playNext(picked)
                        quickActions.announce(context.resources.getQuantityString(R.plurals.selection_play_next_done, picked.size, picked.size))
                    }
                    onDone()
                },
            )
            add(
                FlowSelectionAction(Icons.AutoMirrored.Outlined.PlaylistAdd, addLabel) {
                    val picked = videos().filterNot { app.mawja.android.data.localmedia.LocalMediaIds.isLocal(it.id) }
                    if (picked.isNotEmpty()) addTargets = picked
                },
            )
            if (offerDownload) add(
                FlowSelectionAction(Icons.Outlined.Download, downloadLabel) {
                    val picked = videos().filterNot { app.mawja.android.data.localmedia.LocalMediaIds.isLocal(it.id) }
                    if (picked.isNotEmpty()) {
                        actions.download(picked)
                        quickActions.announce(context.resources.getQuantityString(R.plurals.ui_downloading_videos, picked.size, picked.size))
                    }
                    onDone()
                },
            )
            if (delete != null) add(delete)
            if (more != null && count == 1) add(FlowSelectionAction(Icons.Outlined.MoreVert, moreLabel, more))
        }
    return SelectionActionsState(
        actions = list,
        sheet = {
            addTargets?.let { picked ->
                BulkAddToPlaylistSheet(
                    videos = picked,
                    actions = actions,
                    quickActions = quickActions,
                    onDismiss = { addTargets = null },
                    onAdded = {
                        addTargets = null
                        onDone()
                    },
                )
            }
        },
    )
}

/** What [rememberSelectionActions] gives a screen: the toolbar buttons and the sheet to place once. */
class SelectionActionsState(
    val actions: List<FlowSelectionAction>,
    val sheet: @Composable () -> Unit,
)

@Composable
private fun BulkAddToPlaylistSheet(
    videos: List<Video>,
    actions: SelectionActionsViewModel,
    quickActions: QuickActionsViewModel,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val targets by actions.userPlaylists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val announce = { name: String? ->
        quickActions.announce(
            if (name == null) {
                context.getString(R.string.selection_add_failed)
            } else {
                context.resources.getQuantityString(R.plurals.selection_added_to_playlist, videos.size, videos.size, name)
            },
        )
    }
    MergeIntoCollectionSheet(
        targets = remember(targets) { targets.map { CollectionTarget(it.id, it.name, it.thumbnailUrl, it.videoCount) } },
        placeholder = Icons.AutoMirrored.Filled.PlaylistPlay,
        itemCountLabel = { pluralStringResource(R.plurals.videos_count_template, it, it) },
        createLabel = stringResource(R.string.create_new_playlist),
        onSelect = { target ->
            scope.launch {
                announce(actions.addToPlaylist(target.id, videos))
                onAdded()
            }
        },
        onCreateNew = { creating = true },
        onDismiss = onDismiss,
    )
    if (creating) {
        CollectionEditDialog(
            title = stringResource(R.string.create_new_playlist),
            confirmLabel = stringResource(R.string.create),
            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
            onDismiss = { creating = false },
            onConfirm = { name, description ->
                scope.launch {
                    announce(actions.createPlaylistWith(name, description, videos))
                    onAdded()
                }
            },
        )
    }
}

/** The list's own delete/remove button, for [rememberSelectionActions]. */
fun deleteSelectionAction(
    label: String,
    onClick: () -> Unit,
): FlowSelectionAction = FlowSelectionAction(Icons.Outlined.Delete, label, onClick)
