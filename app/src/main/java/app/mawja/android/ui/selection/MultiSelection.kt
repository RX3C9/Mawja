/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.selection

/**
 * Multi-select state for a list (#84), as an immutable value a screen keeps in Compose state.
 * Long press starts selection mode with the pressed row; taps then toggle rows; unticking the last
 * row leaves selection mode. A mode entered from the top bar starts empty and stays until left.
 */
data class MultiSelection(
    val active: Boolean = false,
    val ids: Set<String> = emptySet(),
) {
    val count: Int get() = ids.size

    operator fun contains(id: String): Boolean = id in ids

    /** Selection mode with nothing ticked yet (the top bar's Select button). */
    fun enter(): MultiSelection = if (active) this else MultiSelection(active = true)

    /** A long press: enters selection mode with [id] ticked, or toggles it when already selecting. */
    fun longPress(id: String): MultiSelection = if (active) toggle(id) else MultiSelection(active = true, ids = setOf(id))

    /** A tap while selecting; leaves selection mode when the last ticked row is unticked. */
    fun toggle(id: String): MultiSelection {
        val next = if (id in ids) ids - id else ids + id
        return if (next.isEmpty()) MultiSelection() else MultiSelection(active = true, ids = next)
    }

    /** True when every one of [shown] is ticked (and there is at least one). */
    fun allSelected(shown: Collection<String>): Boolean = shown.isNotEmpty() && ids.containsAll(shown)

    /** Select all: ticks every one of [shown], or unticks them when all already are (rows hidden by a search stay as they are). */
    fun toggleAll(shown: Collection<String>): MultiSelection {
        if (shown.isEmpty()) return this
        val next = if (ids.containsAll(shown)) ids - shown.toSet() else ids + shown
        return MultiSelection(active = true, ids = next)
    }

    /** Drops ticks for rows that are gone; an empty list ends selection mode. */
    fun retain(existing: Collection<String>): MultiSelection {
        if (!active) return this
        if (existing.isEmpty()) return MultiSelection()
        val set = existing as? Set<String> ?: existing.toHashSet()
        val next = ids.filterTo(LinkedHashSet()) { it in set }
        return if (next.size == ids.size) this else copy(ids = next)
    }

    fun clear(): MultiSelection = MultiSelection()

    /** The ticked items of [items] in list order, for bulk actions that care about order (play next, add to playlist). */
    fun <T> pick(
        items: List<T>,
        key: (T) -> String,
    ): List<T> = items.filter { key(it) in ids }

    companion object {
        /**
         * Play next inserts each item right after the current one, so feeding them in reverse
         * keeps the list order in the queue.
         */
        fun <T> playNextOrder(picked: List<T>): List<T> = picked.asReversed()
    }
}
