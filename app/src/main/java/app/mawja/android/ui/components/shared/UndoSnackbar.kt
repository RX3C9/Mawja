/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.components.shared

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.withTimeoutOrNull

/** How long a deletion's Undo stays on screen (#82). */
const val UNDO_SNACKBAR_MILLIS = 6_000L

/**
 * Shows [message] with an Undo action for [UNDO_SNACKBAR_MILLIS] (Compose's own durations are
 * 4 s or 10 s). Running out the clock removes it like a timed-out snackbar.
 */
suspend fun SnackbarHostState.showUndoSnackbar(
    message: String,
    actionLabel: String,
): SnackbarResult {
    currentSnackbarData?.dismiss()
    return withTimeoutOrNull(UNDO_SNACKBAR_MILLIS) {
        showSnackbar(message = message, actionLabel = actionLabel, duration = SnackbarDuration.Indefinite)
    } ?: SnackbarResult.Dismissed
}
