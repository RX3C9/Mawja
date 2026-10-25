package app.mawja.android.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.History
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.data.local.VideoHistoryEntry
import app.mawja.android.data.localmedia.LocalMediaIds
import app.mawja.android.data.model.toMusicTrack
import app.mawja.android.data.model.toVideo
import app.mawja.android.data.music.model.MusicTrack
import app.mawja.android.data.shorts.queue.ShortsQueueSource
import app.mawja.android.ui.components.layout.LocalFlowBottomInsets
import app.mawja.android.ui.components.layout.floatAboveBottomChrome
import app.mawja.android.ui.components.layout.topbar.FlowTopBar
import app.mawja.android.ui.components.layout.topbar.FlowTopBarMenuItem
import app.mawja.android.ui.components.layout.topbar.FlowTopBarOverflow
import app.mawja.android.ui.components.library.HistoryFilterRow
import app.mawja.android.ui.components.library.HistoryList
import app.mawja.android.ui.components.shared.FlowAlertDialog
import app.mawja.android.ui.components.shared.FlowEmptyState
import app.mawja.android.ui.components.shared.FlowSearchField
import app.mawja.android.ui.components.shared.FlowSelectionToolbar
import app.mawja.android.ui.components.music.sheet.LocalMusicMenus
import app.mawja.android.ui.components.shared.quickactions.VideoQuickActionsBottomSheet
import app.mawja.android.ui.components.shared.showUndoSnackbar
import app.mawja.android.ui.selection.MultiSelection
import app.mawja.android.ui.selection.deleteSelectionAction
import app.mawja.android.ui.selection.rememberSelectionActions
import kotlinx.coroutines.launch

private val SearchFieldPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

@Composable
fun HistoryScreen(
    onVideoClick: (MusicTrack) -> Unit,
    onBackClick: () -> Unit,
    onShortsQueue: (ShortsQueueSource) -> Unit = {},
    onMusicClick: (MusicTrack, List<MusicTrack>) -> Unit = { track, _ -> onVideoClick(track) },
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val displayEntries by viewModel.displayEntries.collectAsStateWithLifecycle()
    val availableYears by viewModel.availableYears.collectAsStateWithLifecycle()
    val bestWatchStart by viewModel.bestWatchWindowStart.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val contentFilter by viewModel.contentFilter.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val yearFilter by viewModel.yearFilter.collectAsStateWithLifecycle()

    var showClearDialog by remember { mutableStateOf(false) }
    var showClearShortsDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val removedLabel = stringResource(R.string.removed_from_history)
    val undoLabel = stringResource(R.string.action_undo)
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val onRemove: (VideoHistoryEntry) -> Unit = { entry ->
        viewModel.removeFromHistory(entry.videoId)
        scope.launch {
            val result = snackbarHostState.showUndoSnackbar(message = removedLabel, actionLabel = undoLabel)
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreHistoryEntry(entry)
            }
        }
    }

    // Multi-select (#84): long press a row to start, then tap rows; Back or the top bar's back leaves.
    var selection by remember { mutableStateOf(MultiSelection()) }
    val shownIds = remember(displayEntries) { displayEntries.map { it.videoId } }
    LaunchedEffect(shownIds) { selection = selection.retain(shownIds) }
    LaunchedEffect(selection.active) { if (selection.active) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    BackHandler(enabled = selection.active) { selection = selection.clear() }
    val selectedEntries = { selection.pick(displayEntries) { it.videoId } }
    val removeSelected = {
        val removed = selectedEntries()
        selection = selection.clear()
        if (removed.isNotEmpty()) {
            viewModel.removeFromHistory(removed)
            scope.launch {
                val message = context.resources.getQuantityString(R.plurals.selection_history_removed, removed.size, removed.size)
                val result = snackbarHostState.showUndoSnackbar(message = message, actionLabel = undoLabel)
                if (result == SnackbarResult.ActionPerformed) viewModel.restoreHistoryEntries(removed)
            }
        }
    }
    var menuEntry by remember { mutableStateOf<VideoHistoryEntry?>(null) }
    val musicMenus = LocalMusicMenus.current
    val selectionActions =
        rememberSelectionActions(
            videos = { selectedEntries().map { it.toVideo().copy(isMusic = it.isMusic) } },
            onDone = { selection = selection.clear() },
            delete = deleteSelectionAction(stringResource(R.string.action_delete)) { removeSelected() },
            more = {
                selectedEntries().firstOrNull()?.let { entry ->
                    selection = selection.clear()
                    if (entry.isMusic) {
                        if (!LocalMediaIds.isLocal(entry.videoId)) musicMenus.openSong(entry.toMusicTrack())
                    } else {
                        menuEntry = entry
                    }
                }
            },
            count = selection.count,
        )

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            if (selection.active) {
                FlowTopBar(
                    title = pluralStringResource(R.plurals.selected_count_template, selection.count, selection.count),
                    onBack = { selection = selection.clear() },
                    actions = {
                        val allSelected = selection.allSelected(shownIds)
                        IconButton(onClick = { selection = selection.toggleAll(shownIds) }) {
                            Icon(
                                imageVector = if (allSelected) Icons.Outlined.CheckBox else Icons.Default.SelectAll,
                                contentDescription = stringResource(R.string.select_all),
                            )
                        }
                    },
                )
            } else {
            FlowTopBar(
                title = stringResource(R.string.library_history_label),
                onBack = onBackClick,
                actions = {
                    FlowTopBarOverflow(
                        items =
                            buildList {
                                if (uiState.shortsEnabled) {
                                    add(
                                        FlowTopBarMenuItem(
                                            label = stringResource(R.string.history_delete_shorts),
                                            enabled = uiState.historyEntries.any { it.isShort },
                                            onClick = { showClearShortsDialog = true },
                                        ),
                                    )
                                }
                                add(
                                    FlowTopBarMenuItem(
                                        label = stringResource(R.string.clear_all),
                                        enabled = uiState.historyEntries.isNotEmpty(),
                                        onClick = { showClearDialog = true },
                                    ),
                                )
                            },
                    )
                },
            )
            }
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState, Modifier.floatAboveBottomChrome(LocalFlowBottomInsets.current))
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(paddingValues),
        ) {
            FlowSearchField(
                query = searchQuery,
                onQueryChange = viewModel::setSearchQuery,
                placeholder = stringResource(R.string.search_watch_history),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(SearchFieldPadding),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                    )
                },
            )

            HistoryFilterRow(
                shortsEnabled = uiState.shortsEnabled,
                selectedFilter = contentFilter,
                onFilterSelected = viewModel::setContentFilter,
                selectedSort = sortOrder,
                onSortSelected = viewModel::setSortOrder,
                selectedYear = yearFilter,
                availableYears = availableYears,
                onYearSelected = viewModel::setYearFilter,
            )

            bestWatchStart?.let { start ->
                Text(
                    text = stringResource(R.string.history_best_watch_time, BestWatchTime.formatWindow(start)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                displayEntries.isEmpty() -> {
                    FlowEmptyState(
                        modifier = Modifier.fillMaxSize(),
                        title =
                            if (uiState.historyEntries.isEmpty()) {
                                stringResource(R.string.empty_watch_history)
                            } else {
                                stringResource(R.string.history_no_results)
                            },
                        subtitle =
                            if (uiState.historyEntries.isEmpty()) {
                                stringResource(R.string.empty_watch_history_body)
                            } else {
                                stringResource(R.string.history_no_results_body)
                            },
                        icon = Icons.Outlined.History,
                    )
                }

                else -> {
                    HistoryList(
                        entries = displayEntries,
                        shortVideos = uiState.shortVideos,
                        selectedFilter = contentFilter,
                        onVideoClick = onVideoClick,
                        onShortClick = { row, tapped -> onShortsQueue(viewModel.shortsRowSource(row, tapped)) },
                        onMusicClick = onMusicClick,
                        onRemove = onRemove,
                        selection = selection,
                        onSelectionChange = { selection = it },
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.BottomCenter) {
            FlowSelectionToolbar(
                visible = selection.active && selection.count > 0,
                summary = pluralStringResource(R.plurals.selected_count_template, selection.count, selection.count),
                actions = selectionActions.actions,
            )
        }
    }
    selectionActions.sheet()
    menuEntry?.let { entry ->
        VideoQuickActionsBottomSheet(
            video = remember(entry) { entry.toVideo() },
            onDismiss = { menuEntry = null },
            onRemoveFromCollection = { onRemove(entry) },
            removeFromCollectionLabel = stringResource(R.string.remove_from_history),
        )
    }

    if (showClearDialog) {
        FlowAlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.clear_watch_history_alert_title)) },
            text = { Text(stringResource(R.string.clear_watch_history_alert_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        showClearDialog = false
                    },
                ) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showClearShortsDialog) {
        FlowAlertDialog(
            onDismissRequest = { showClearShortsDialog = false },
            title = { Text(stringResource(R.string.history_delete_shorts_title)) },
            text = { Text(stringResource(R.string.history_delete_shorts_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearShortsHistory()
                        showClearShortsDialog = false
                    },
                ) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearShortsDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
