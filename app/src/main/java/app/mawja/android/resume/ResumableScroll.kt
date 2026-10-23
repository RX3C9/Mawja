/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.resume

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.snapshotFlow

/**
 * Instant Resume (#75) for one list: puts it back where it was when this page was reopened after the
 * process died (once the items are loaded, waiting at most 8 s), and remembers where it is after it
 * has been still for a moment.
 */
@OptIn(FlowPreview::class)
@Composable
fun ResumableScroll(
    state: LazyListState,
    key: String,
) {
    LaunchedEffect(state, key) {
        InstantResume.takeScroll(key)?.let { spot ->
            val ready = withTimeoutOrNull(8_000) { snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > spot.index } }
            if (ready != null && state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) {
                state.scrollToItem(spot.index, spot.offset)
            }
        }
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .debounce(InstantResumePolicy.SCROLL_SETTLE_MS)
            .collect { (index, offset) -> InstantResume.recordScroll(key, index, offset) }
    }
}

@OptIn(FlowPreview::class)
@Composable
fun ResumableScroll(
    state: LazyGridState,
    key: String,
) {
    LaunchedEffect(state, key) {
        InstantResume.takeScroll(key)?.let { spot ->
            val ready = withTimeoutOrNull(8_000) { snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > spot.index } }
            if (ready != null && state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) {
                state.scrollToItem(spot.index, spot.offset)
            }
        }
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .debounce(InstantResumePolicy.SCROLL_SETTLE_MS)
            .collect { (index, offset) -> InstantResume.recordScroll(key, index, offset) }
    }
}
