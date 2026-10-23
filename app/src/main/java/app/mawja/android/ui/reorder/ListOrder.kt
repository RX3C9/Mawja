/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.reorder

/**
 * Pure helpers behind drag-and-drop ordering (#83): moving one item, and laying a stored order
 * (a list of keys) over the current defaults so items added in a later version, or ones the
 * stored order never knew, still show up near where they normally sit.
 */
object ListOrder {
    private const val SEPARATOR = ','

    /** [list] with the item at [from] moved to [to]; out-of-range or no-op moves return [list] unchanged. */
    fun <T> move(
        list: List<T>,
        from: Int,
        to: Int,
    ): List<T> {
        if (from == to || from !in list.indices || to !in list.indices) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * [defaults] reordered by [stored] keys. Keys not among the defaults are dropped, duplicates
     * keep their first place, and a default the stored order lacks is put right after the default
     * that precedes it (or first when nothing precedes it), so a new section lands in its usual spot.
     */
    fun <T> apply(
        defaults: List<T>,
        stored: List<String>,
        key: (T) -> String,
    ): List<T> {
        if (stored.isEmpty()) return defaults
        val byKey = LinkedHashMap<String, T>()
        defaults.forEach { byKey.putIfAbsent(key(it), it) }
        val result = ArrayList<T>(defaults.size)
        val placed = HashSet<String>()
        stored.forEach { k ->
            val item = byKey[k]
            if (item != null && placed.add(k)) result.add(item)
        }
        defaults.forEachIndexed { index, item ->
            val k = key(item)
            if (!placed.add(k)) return@forEachIndexed
            val previous = (index - 1 downTo 0).map { key(defaults[it]) }.firstOrNull { it in placed && it != k }
            val at = previous?.let { p -> result.indexOfFirst { key(it) == p } + 1 } ?: 0
            result.add(at, item)
        }
        return result
    }

    /** True when [order] lists exactly the [defaults] keys in default order (nothing to store). */
    fun <T> isDefault(
        defaults: List<T>,
        order: List<T>,
        key: (T) -> String,
    ): Boolean = defaults.map(key) == order.map(key)

    fun encode(keys: List<String>): String = keys.filter { it.isNotBlank() && SEPARATOR !in it }.distinct().joinToString(SEPARATOR.toString())

    fun decode(stored: String?): List<String> =
        stored
            .orEmpty()
            .split(SEPARATOR)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
}
