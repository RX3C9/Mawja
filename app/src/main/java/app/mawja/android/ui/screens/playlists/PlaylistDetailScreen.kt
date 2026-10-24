package app.mawja.android.ui.screens.playlists

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.data.model.Video
import app.mawja.android.ui.components.library.PlaylistDetailTopBar
import app.mawja.android.ui.components.library.PlaylistHeader
import app.mawja.android.ui.components.library.PlaylistHeaderActions
import app.mawja.android.ui.components.library.PlaylistHeaderPane
import app.mawja.android.ui.components.library.PlaylistSearchBar
import app.mawja.android.ui.components.library.PlaylistSortChip
import app.mawja.android.ui.components.library.PlaylistSortOrder
import app.mawja.android.ui.components.shared.FlowErrorState
import app.mawja.android.ui.components.shared.FlowLoadingIndicator
import app.mawja.android.ui.components.shared.FlowSelectionAction
import app.mawja.android.ui.components.shared.FlowSelectionToolbar
import app.mawja.android.ui.components.shared.quickactions.VideoQuickActionsBottomSheet
import app.mawja.android.ui.selection.MultiSelection
import app.mawja.android.ui.selection.deleteSelectionAction
import app.mawja.android.ui.selection.rememberSelectionActions
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.mawja.android.ui.components.shared.FlowSidePanes
import app.mawja.android.ui.components.shared.quickactions.sharedQuickActionsViewModel
import app.mawja.android.ui.components.shared.rememberFlowPaneState
import app.mawja.android.ui.components.shared.rememberReorderableLazyListState
import app.mawja.android.ui.components.shared.rememberShareLinksWithoutText
import app.mawja.android.utils.PLAYLIST_FILE_MIME_TYPE
import app.mawja.android.utils.filterBySearch
import app.mawja.android.utils.sharePlaylist
import app.mawja.android.utils.sharePlaylistFile
import kotlinx.coroutines.launch

@Composable
fun PlaylistDetailScreen(
    onNavigateBack: () -> Unit,
    onPlayPlaylist: (videos: List<Video>, startIndex: Int, shuffle: Boolean, title: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sortedVideos by viewModel.sortedVideos.collectAsStateWithLifecycle()
    val downloadBatch by viewModel.downloadBatch.collectAsStateWithLifecycle()
    val downloadedCollection by viewModel.downloadedCollection.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val mergeTargets by viewModel.userCreatedPlaylists.collectAsStateWithLifecycle()
    val quickActions = sharedQuickActionsViewModel()
    val context = LocalContext.current
    val shareLinkOnly by rememberShareLinksWithoutText()

    var dialog by remember { mutableStateOf<PlaylistDialog?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectionMode by remember { mutableStateOf(false) }
    var displayVideos by remember { mutableStateOf(sortedVideos) }
    var searchQuery by rememberSaveable { mutableStateOf<String?>(null) }
    val isSearching = searchQuery != null
    val shownVideos =
        remember(displayVideos, searchQuery) { displayVideos.filterBySearch(searchQuery.orEmpty()) { "${it.title} ${it.channelName}" } }
    val positions =
        remember(displayVideos, isSearching) {
            if (isSearching) displayVideos.withIndex().associate { (index, video) -> video.id to index + 1 } else emptyMap()
        }
    val scope = rememberCoroutineScope()
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PLAYLIST_FILE_MIME_TYPE)) { target ->
            target?.let(viewModel::exportTo)
        }
    val m3uLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(app.mawja.android.data.playlist.PlaylistListFormat.M3U.mimeType)) { target ->
            target?.let { viewModel.exportListTo(it, app.mawja.android.data.playlist.PlaylistListFormat.M3U) }
        }
    val csvLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(app.mawja.android.data.playlist.PlaylistListFormat.CSV.mimeType)) { target ->
            target?.let { viewModel.exportListTo(it, app.mawja.android.data.playlist.PlaylistListFormat.CSV) }
        }
    val listState = rememberLazyListState()
    app.mawja.android.resume.ResumableScroll(listState, "playlist")
    // Launcher shortcuts (#79): the playlist opened last is offered first on the app icon.
    val shortcutContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(viewModel.playlistId, uiState.playlistName) {
        if (uiState.playlistName.isNotBlank()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                app.mawja.android.shortcuts.AppShortcuts.recordPlaylist(shortcutContext, viewModel.playlistId, uiState.playlistName, isMusic = false)
            }
        }
    }
    val panes = rememberFlowPaneState()
    val twoPane = panes.showsSidePane

    val isUserCreated = uiState.isLocalPlaylist && !uiState.isSaved
    val canReorder = isUserCreated && sortOrder == PlaylistSortOrder.MANUAL && !isSearching
    // A saved YouTube playlist mirrors the original; its next sync would bring removed videos back.
    val canModify = uiState.isLocalPlaylist && !uiState.isSaved
    val exitSelection = {
        selectionMode = false
        selectedIds = emptySet()
    }

    val haptic = LocalHapticFeedback.current
    var menuVideo by remember { mutableStateOf<Video?>(null) }
    val removeLabel = stringResource(R.string.remove)
    val selectionActions =
        rememberSelectionActions(
            videos = { displayVideos.filter { it.id in selectedIds } },
            onDone = exitSelection,
            delete = if (canModify) deleteSelectionAction(removeLabel) { dialog = PlaylistDialog.RemoveSelected(selectedIds) } else null,
            more = {
                displayVideos.firstOrNull { it.id in selectedIds }?.let { video ->
                    exitSelection()
                    menuVideo = video
                }
            },
            count = selectedIds.size,
            quickActions = quickActions,
        )

    val reorderState =
        rememberReorderableLazyListState(
            listState = listState,
            itemIndexOffset = if (twoPane || isSearching) 0 else 1,
            onMove = { from, to -> displayVideos = displayVideos.toMutableList().apply { add(to, removeAt(from)) } },
            onDragStopped = { viewModel.reorderVideos(displayVideos.map { it.id }) },
        )

    // Held while a drag is in progress, so a sync or metadata update can't replace the list mid-drag.
    LaunchedEffect(sortedVideos, reorderState.isDragging) {
        if (reorderState.isDragging) return@LaunchedEffect
        displayVideos = sortedVideos
        selectedIds = selectedIds.intersect(sortedVideos.mapTo(HashSet()) { it.id })
        if (sortedVideos.isEmpty()) exitSelection()
    }

    LaunchedEffect(searchQuery) {
        if (isSearching) listState.scrollToItem(0)
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> quickActions.announce(message.resolve(context), message.undo) }
    }

    BackHandler(enabled = selectionMode) { exitSelection() }
    BackHandler(enabled = isSearching && !selectionMode) { searchQuery = null }

    val showCollapsedTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val headerState =
        rememberPlaylistHeaderState(
            uiState,
            displayVideos,
            downloadBatch,
            isDownloaded =
                downloadedCollection?.isComplete == true,
        )
    val headerActions =
        PlaylistHeaderActions(
            onPlayAll = { if (displayVideos.isNotEmpty()) onPlayPlaylist(displayVideos, 0, false, uiState.playlistName) },
            onShuffle = {
                if (displayVideos.isNotEmpty()) {
                    onPlayPlaylist(
                        displayVideos,
                        displayVideos.indices.random(),
                        true,
                        uiState.playlistName,
                    )
                }
            },
            onDownloadAll = { dialog = PlaylistDialog.DownloadAll },
            onSaveToggle = { if (uiState.isSaved) viewModel.unsaveFromLibrary() else viewModel.saveToLibrary() },
            onAddAll = { dialog = PlaylistDialog.AddAll },
            onShare = {
                if (!isUserCreated) {
                    sharePlaylist(context, viewModel.playlistId, uiState.playlistName, shareLinkOnly)
                } else {
                    scope.launch {
                        val file = viewModel.shareableFile()
                        if (file != null) {
                            sharePlaylistFile(context, file, uiState.playlistName)
                        } else {
                            quickActions.announce(context.getString(R.string.playlist_share_failed))
                        }
                    }
                }
            },
            onExport = { exportLauncher.launch(viewModel.exportFileName) },
            onExportList = { format ->
                val launcher = if (format == app.mawja.android.data.playlist.PlaylistListFormat.M3U) m3uLauncher else csvLauncher
                launcher.launch(viewModel.exportFileName(format))
            },
            onEdit = { dialog = PlaylistDialog.Edit },
            onDelete = { dialog = PlaylistDialog.Delete },
        )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            PlaylistDetailTopBar(
                title = uiState.playlistName,
                showTitle = (showCollapsedTitle && !twoPane) || selectionMode,
                inSelectionMode = selectionMode,
                selectedCount = selectedIds.size,
                allSelected = shownVideos.isNotEmpty() && shownVideos.all { it.id in selectedIds },
                canSelect = displayVideos.isNotEmpty(),
                search =
                    PlaylistSearchBar(
                        query = searchQuery,
                        onOpen = { searchQuery = "" },
                        onQueryChange = { searchQuery = it },
                        onClose = { searchQuery = null },
                    ),
                onNavigateBack = onNavigateBack,
                onEnterSelection = { selectionMode = true },
                onClearSelection = exitSelection,
                onSelectAll = {
                    val shownIds = shownVideos.mapTo(HashSet()) { it.id }
                    selectedIds = if (selectedIds.containsAll(shownIds)) selectedIds - shownIds else selectedIds + shownIds
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> {
                    FlowLoadingIndicator()
                }

                uiState.errorMessage != null -> {
                    FlowErrorState(error = uiState.errorMessage.orEmpty(), onRetry = viewModel::retry)
                }

                else -> {
                    val sortChip: @Composable () -> Unit = {
                        PlaylistSortChip(
                            options = PlaylistSortOrder.availableFor(uiState.isLocalPlaylist, uiState.isLikes),
                            selected = sortOrder,
                            onSelected = viewModel::setSortOrder,
                            default = PlaylistSortOrder.defaultFor(uiState.isLikes),
                        )
                    }
                    val list: @Composable (header: (@Composable () -> Unit)?) -> Unit = { header ->
                        PlaylistDetailList(
                            videos = shownVideos,
                            mode =
                                PlaylistListMode(
                                    canReorder = canReorder,
                                    canModify = canModify,
                                    selectionMode = selectionMode,
                                    selectedIds = selectedIds,
                                    showAddedDate = sortOrder.showsDateAdded,
                                    isWatchLater = uiState.isWatchLater,
                                    isLikes = uiState.isLikes,
                                    searchQuery = searchQuery.orEmpty(),
                                ),
                            isLoadingMore = uiState.isLoadingMore,
                            listState = listState,
                            reorderState = reorderState,
                            onVideoClick = { index, video ->
                                if (selectionMode) {
                                    // Unticking the last row leaves selection mode, as in every multi-select list (#84).
                                    val next = MultiSelection(true, selectedIds).toggle(video.id)
                                    selectionMode = next.active
                                    selectedIds = next.ids
                                } else {
                                    onPlayPlaylist(displayVideos, positions[video.id]?.minus(1) ?: index, false, uiState.playlistName)
                                }
                            },
                            onRemove = { viewModel.removeVideo(it.id) },
                            onLongPress = { video ->
                                if (!selectionMode) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selectionMode = true
                                    selectedIds = setOf(video.id)
                                }
                            },
                            header = header,
                        )
                    }
                    FlowSidePanes(
                        panes = panes,
                        sidePaneWidth = HeaderPaneWidth,
                        sidePane = {
                            PlaylistHeaderSurface(
                                headerState.thumbnailUrl,
                            ) { PlaylistHeaderPane(state = headerState, actions = headerActions) }
                        },
                        mainPane = {
                            if (twoPane || isSearching) {
                                Column {
                                    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { sortChip() }
                                    list(null)
                                }
                            } else {
                                list { PlaylistHeader(state = headerState, actions = headerActions, sortChip = sortChip) }
                            }
                        },
                    )
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
            video = video,
            onRemoveFromCollection = if (canModify) ({ viewModel.removeVideo(video.id) }) else null,
            removeFromCollectionLabel =
                if (canModify) {
                    stringResource(
                        when {
                            uiState.isLikes -> R.string.unlike
                            uiState.isWatchLater -> R.string.remove_from_watch_later
                            else -> R.string.remove_from_playlist_action
                        },
                    )
                } else {
                    null
                },
            onDismiss = { menuVideo = null },
        )
    }

    PlaylistDetailDialogs(
        dialog = dialog,
        uiState = uiState,
        mergeTargets = mergeTargets,
        viewModel = viewModel,
        onDismiss = { dialog = null },
        onDeleted = onNavigateBack,
        onRemovedSelected = exitSelection,
    )
}

private fun PlaylistUiMessage.resolve(context: Context): String =
    when {
        pluralRes != 0 -> context.resources.getQuantityString(pluralRes, count, *args.toTypedArray())
        args.isEmpty() -> context.getString(stringRes)
        else -> context.getString(stringRes, *args.toTypedArray())
    }
