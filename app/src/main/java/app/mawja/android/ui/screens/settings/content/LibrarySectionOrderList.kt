/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mawja.android.R
import app.mawja.android.ui.components.library.LibrarySection
import app.mawja.android.ui.components.shared.ReorderHandle
import app.mawja.android.ui.reorder.ListOrder
import sh.calvin.reorderable.ReorderableColumn

/**
 * Library sections in the order Library shows them (#83). Rows drag by their handle with a haptic
 * tick per step; TalkBack gets Move up / Move down, since a drag is out of a screen reader's reach.
 */
@Composable
internal fun LibrarySectionOrderList(
    sections: List<LibrarySection>,
    onOrderChanged: (List<LibrarySection>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    // Held locally so a drop shows its result at once instead of snapping back until DataStore emits.
    var items by remember(sections) { mutableStateOf(sections) }

    fun move(
        from: Int,
        to: Int,
    ): Boolean {
        val updated = ListOrder.move(items, from, to)
        if (updated === items) return false
        items = updated
        onOrderChanged(updated)
        return true
    }

    val moveUpLabel = stringResource(R.string.move_up)
    val moveDownLabel = stringResource(R.string.move_down)
    Column(modifier = modifier.padding(vertical = 8.dp, horizontal = 16.dp)) {
        ReorderableColumn(
            list = items,
            onSettle = { from, to ->
                move(from, to)
                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
            },
            onMove = { haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) },
        ) { index, section, isDragging ->
            key(section) {
                ReorderableItem {
                    Surface(
                        color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        customActions =
                                            buildList {
                                                if (index > 0) add(CustomAccessibilityAction(moveUpLabel) { move(index, index - 1) })
                                                if (index < items.lastIndex) {
                                                    add(CustomAccessibilityAction(moveDownLabel) { move(index, index + 1) })
                                                }
                                            }
                                    },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = section.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                modifier =
                                    Modifier
                                        .size(LocalMinimumInteractiveComponentSize.current)
                                        .draggableHandle(
                                            onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                        ),
                                contentAlignment = Alignment.Center,
                            ) {
                                ReorderHandle()
                            }
                        }
                    }
                }
            }
        }
    }
}
