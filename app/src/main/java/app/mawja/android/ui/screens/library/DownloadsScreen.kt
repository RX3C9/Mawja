package app.mawja.android.ui.screens.library

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.data.local.dao.DownloadCollectionSummary
import app.mawja.android.data.music.DownloadedTrack
import app.mawja.android.data.music.model.toVideo
import app.mawja.android.data.video.DownloadedVideo
import app.mawja.android.ui.components.music.sheet.LocalMusicMenus
import app.mawja.android.ui.components.shared.quickactions.VideoQuickActionsBottomSheet
import app.mawja.android.ui.selection.MultiSelection
import app.mawja.android.ui.selection.deleteSelectionAction
import app.mawja.android.ui.selection.rememberSelectionActions
import app.mawja.android.data.video.storage.StorageAccess
import app.mawja.android.ui.components.layout.topbar.FlowTopBar
import app.mawja.android.ui.components.library.ActiveDownloadActions
import app.mawja.android.ui.components.library.DownloadCollectionsShelf
import app.mawja.android.ui.components.library.DownloadsRecoveryCard
import app.mawja.android.ui.components.library.DownloadsStorageCard
import app.mawja.android.ui.components.library.LibraryKindHeader
import app.mawja.android.ui.components.library.LibrarySelection
import app.mawja.android.ui.components.library.MusicDownloadsList
import app.mawja.android.ui.components.library.RemoveDownloadCollectionDialog
import app.mawja.android.ui.components.library.VideosDownloadsList
import app.mawja.android.ui.components.library.libraryHeaderIsOneRow
import app.mawja.android.ui.components.shared.FlowAlertDialog
import app.mawja.android.ui.components.shared.FlowSelectionAction
import app.mawja.android.ui.components.shared.FlowSelectionToolbar
import app.mawja.android.ui.components.shared.FlowSortChip
import app.mawja.android.ui.components.shared.MediaKind
import app.mawja.android.ui.components.shared.dismissKeyboardOnPress
import app.mawja.android.ui.components.shared.flowGridColumns

@Composable
fun DownloadsScreen(
    onBackClick: () -> Unit,
    onVideoClick: (videos: List<DownloadedVideo>, startIndex: Int) -> Unit,
    onMusicClick: (List<DownloadedTrack>, Int) -> Unit,
    onOpenCollection: (DownloadCollectionSummary) -> Unit,
    onHomeClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var access by remember { mutableStateOf(StorageAccess.read(context)) }
    var recoveryDismissed by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        access = StorageAccess.read(context)
        viewModel.scanIfMoreIsReadable()
        onPauseOrDispose { }
    }
    val accessRequest =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            access = StorageAccess.read(context)
            when {
                access.video || access.audio -> viewModel.findEarlierDownloads()
                (context as? Activity)?.canStillAsk() == false -> context.openAppPermissionSettings()
            }
        }
    val recoveredMessage = { count: Int ->
        if (count > 0) {
            context.resources.getQuantityString(R.plurals.downloads_recovered_count, count, count)
        } else {
            context.getString(R.string.downloads_recovered_none)
        }
    }
    LaunchedEffect(viewModel) { viewModel.recovered.collect { snackbarHostState.showSnackbar(recoveredMessage(it)) } }
    val deletePrompt = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    LaunchedEffect(viewModel) { viewModel.deleteConsent.collect { deletePrompt.launch(IntentSenderRequest.Builder(it).build()) } }
    var selectedKind by rememberSaveable { mutableStateOf(MediaKind.Videos) }
    var pendingDeletion by remember { mutableStateOf<PendingDeletion?>(null) }
    var removeIncompleteOf by remember { mutableStateOf<MediaKind?>(null) }
    var removingCollection by remember { mutableStateOf<DownloadCollectionSummary?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(emptySet<String>()) }
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val exitSelection = {
        selectionMode = false
        selectedIds = emptySet()
    }
    val shownIds =
        if (selectedKind ==
            MediaKind.Videos
        ) {
            uiState.downloadedVideos.map { it.video.id }
        } else {
            uiState.downloadedMusic.map { it.track.videoId }
        }

    BackHandler(enabled = selectionMode) { exitSelection() }

    val selection =
        LibrarySelection(
            active = selectionMode,
            ids = selectedIds,
            // Multi-select (#84): a long press starts selecting with that download.
            onLongPress = { id ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                selectionMode = true
                selectedIds = setOf(id)
            },
        ) { id ->
            val next = MultiSelection(true, selectedIds).toggle(id)
            selectionMode = next.active
            selectedIds = next.ids
        }
    var menuVideo by remember { mutableStateOf<DownloadedVideo?>(null) }
    val musicMenus = LocalMusicMenus.current
    val selectedVideos = {
        if (selectedKind == MediaKind.Videos) {
            uiState.downloadedVideos.filter { it.video.id in selectedIds }.map { it.video }
        } else {
            uiState.downloadedMusic.filter { it.track.videoId in selectedIds }.map { it.track.toVideo().copy(isMusic = true) }
        }
    }
    val selectionActions =
        rememberSelectionActions(
            videos = selectedVideos,
            onDone = exitSelection,
            delete = deleteSelectionAction(stringResource(R.string.action_delete)) { pendingDeletion = PendingDeletion(selectedIds, title = null) },
            more = {
                val id = selectedIds.firstOrNull()
                exitSelection()
                if (selectedKind == MediaKind.Videos) {
                    menuVideo = uiState.downloadedVideos.firstOrNull { it.video.id == id }
                } else {
                    uiState.downloadedMusic.firstOrNull { it.track.videoId == id }?.let { musicMenus.openSong(it.track) }
                }
            },
            count = selectedIds.size,
            offerDownload = false,
        )
    val activeActions =
        ActiveDownloadActions(
            onPause = viewModel::pauseVideoDownload,
            onResume = viewModel::resumeVideoDownload,
            onRetry = viewModel::retryVideoDownload,
            onCancel = { id, title -> pendingDeletion = PendingDeletion(setOf(id), title) },
            onCancelAll = { removeIncompleteOf = selectedKind },
        )
    val sortChip: @Composable () -> Unit = {
        FlowSortChip(
            options = DownloadSort.entries,
            selected = uiState.sort,
            default = DownloadSort.NEWEST,
            label = { stringResource(it.labelRes) },
            onSelected = viewModel::setSort,
        )
    }
    val onRemoveCollection: (DownloadCollectionSummary) -> Unit = { removingCollection = it }
    val sortInHeader = libraryHeaderIsOneRow()
    val listHeader: @Composable () -> Unit = {
        Column {
            DownloadsStorageCard(uiState.storage.videoBytes, uiState.storage.musicBytes, uiState.storage.freeBytes)
            if (!sortInHeader) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    sortChip()
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            FlowTopBar(
                title =
                    if (selectionMode) {
                        pluralStringResource(R.plurals.selected_count_template, selectedIds.size, selectedIds.size)
                    } else {
                        stringResource(R.string.downloads_title)
                    },
                onBack = if (selectionMode) exitSelection else onBackClick,
                actions = {
                    if (selectionMode) {
                        IconButton(onClick = { selectedIds = if (selectedIds.size == shownIds.size) emptySet() else shownIds.toSet() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.select_all))
                        }
                    } else if (shownIds.isNotEmpty()) {
                        IconButton(onClick = { selectionMode = true }) {
                            Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.select_videos))
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                LibraryKindHeader(
                    query = uiState.query,
                    onQueryChange = viewModel::setQuery,
                    placeholder = stringResource(R.string.downloads_search_hint),
                    selectedKind = selectedKind,
                    onKindSelected = {
                        exitSelection()
                        selectedKind = it
                    },
                    trailing = if (sortInHeader) sortChip else null,
                )
                val hasNothing =
                    uiState.totalVideoCount == 0 &&
                        uiState.totalMusicCount == 0 &&
                        uiState.incompleteVideoDownloads.isEmpty() &&
                        uiState.incompleteMusicDownloads.isEmpty()
                if (hasNothing && !access.seesAllDownloads && !recoveryDismissed) {
                    DownloadsRecoveryCard(
                        onAllow = { accessRequest.launch(downloadReadPermissions()) },
                        onDismiss = { recoveryDismissed = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                val musicColumns = flowGridColumns(compact = 1, medium = 1, expanded = 2)
                Crossfade(
                    targetState = selectedKind,
                    animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                    label = "downloads_kind",
                    modifier = Modifier.weight(1f).dismissKeyboardOnPress { focusManager.clearFocus() },
                ) { kind ->
                    when (kind) {
                        MediaKind.Videos -> {
                            VideosDownloadsList(
                                header = listHeader,
                                shelf = { inset ->
                                    DownloadCollectionsShelf(uiState.videoCollections, onOpenCollection, onRemoveCollection, inset)
                                },
                                videos = uiState.downloadedVideos,
                                totalCount = uiState.totalVideoCount,
                                incomplete = uiState.incompleteVideoDownloads,
                                progress = uiState.progress,
                                mergingIds = uiState.mergingVideoIds,
                                query = uiState.query,
                                selection = selection,
                                activeActions = activeActions,
                                isRefreshing = uiState.isScanning,
                                onRefresh = viewModel::rescan,
                                onVideoClick = onVideoClick,
                                onDelete = { id, title -> pendingDeletion = PendingDeletion(setOf(id), title) },
                                onHomeClick = onHomeClick,
                            )
                        }

                        MediaKind.Music -> {
                            MusicDownloadsList(
                                header = listHeader,
                                shelf = { inset ->
                                    DownloadCollectionsShelf(uiState.musicCollections, onOpenCollection, onRemoveCollection, inset)
                                },
                                tracks = uiState.downloadedMusic,
                                totalCount = uiState.totalMusicCount,
                                incomplete = uiState.incompleteMusicDownloads,
                                progress = uiState.progress,
                                columns = musicColumns,
                                query = uiState.query,
                                selection = selection,
                                activeActions = activeActions,
                                isRefreshing = uiState.isScanning,
                                onRefresh = viewModel::rescan,
                                onMusicClick = onMusicClick,
                                onHomeClick = onHomeClick,
                            )
                        }
                    }
                }
            }
            FlowSelectionToolbar(
                visible = selectionMode && selectedIds.isNotEmpty(),
                summary = pluralStringResource(R.plurals.selected_count_template, selectedIds.size, selectedIds.size),
                actions = selectionActions.actions,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    selectionActions.sheet()
    menuVideo?.let { video ->
        VideoQuickActionsBottomSheet(
            video = video.video,
            onDismiss = { menuVideo = null },
            onRemoveFromCollection = { pendingDeletion = PendingDeletion(setOf(video.video.id), video.video.title) },
            removeFromCollectionLabel = stringResource(R.string.delete),
            removeFromCollectionIcon = Icons.Outlined.Delete,
        )
    }

    pendingDeletion?.let { deletion ->
        DeleteDownloadsDialog(
            deletion = deletion,
            onDismiss = { pendingDeletion = null },
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                viewModel.deleteDownloads(deletion.ids)
                pendingDeletion = null
                exitSelection()
            },
        )
    }

    removingCollection?.let { summary ->
        val remove = { deleteFiles: Boolean ->
            viewModel.removeCollection(summary.collection.id, deleteFiles)
            removingCollection = null
        }
        RemoveDownloadCollectionDialog(
            title = summary.collection.title,
            onDeleteFiles = { remove(true) },
            onKeepFiles = { remove(false) },
            onDismiss = { removingCollection = null },
        )
    }

    removeIncompleteOf?.let { kind ->
        val count = if (kind == MediaKind.Videos) uiState.incompleteVideoDownloads.size else uiState.incompleteMusicDownloads.size
        FlowAlertDialog(
            onDismissRequest = { removeIncompleteOf = null },
            title = { Text(stringResource(R.string.remove_incomplete_downloads)) },
            text = { Text(pluralStringResource(R.plurals.remove_incomplete_downloads_message, count, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeIncompleteDownloads(audioOnly = kind == MediaKind.Music)
                        removeIncompleteOf = null
                    },
                ) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { removeIncompleteOf = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun DeleteDownloadsDialog(
    deletion: PendingDeletion,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_download_dialog_title)) },
        text = {
            Text(
                if (deletion.title != null) {
                    stringResource(R.string.delete_download_dialog_text, deletion.title)
                } else {
                    pluralStringResource(R.plurals.delete_downloads_dialog_text, deletion.ids.size, deletion.ids.size)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val SelectionSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/** What a confirmed delete removes: one download named by [title], or a selection. */
private data class PendingDeletion(
    val ids: Set<String>,
    val title: String?,
)
