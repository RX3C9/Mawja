/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.cloudsync

/**
 * Fractional order keys for playlist entries (docs/cloud-sync.md §2): base-62 strings that sort as
 * plain strings, so a new key can always be made between two neighbours without renumbering the
 * rest. Every entry is its own sync register, so an add on one device and an add or a move on
 * another both survive a merge; the order of the result is the order of these keys. Keys never end
 * in '0', which keeps room below every key. Pure.
 */
object OrderKeys {
    const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    const val MAX_LENGTH = 64
    private const val BASE = 62
    private val VALID = Regex("^[0-9A-Za-z]{0,63}[1-9A-Za-z]$")

    fun isValid(key: String?): Boolean = key != null && VALID.matches(key)

    /** A key strictly between [a] (null = before everything) and [b] (null = after everything). */
    fun between(
        a: String?,
        b: String?,
    ): String {
        val lo = a.orEmpty()
        require(b == null || lo < b) { "order keys out of order: $a >= $b" }
        return when {
            b == null -> after(lo)
            lo.isEmpty() -> before(b)
            else -> midpoint(lo, b)
        }
    }

    /** Appending steps the first digit by one (61 appends per character), not by halves. */
    private fun after(a: String): String {
        if (a.isEmpty()) return DIGITS[BASE / 2].toString()
        val da = digit(a[0])
        return if (da < BASE - 1) DIGITS[da + 1].toString() else DIGITS[da].toString() + after(a.substring(1))
    }

    /** Prepending (newest-first lists) steps down by one digit the same way. */
    private fun before(b: String): String {
        val db = digit(b[0])
        return when {
            db > 1 -> DIGITS[db - 1].toString()
            db == 1 && b.length > 1 -> "1"
            db == 1 -> "0" + DIGITS[BASE / 2]
            else -> "0" + before(b.substring(1))
        }
    }

    private fun digit(c: Char): Int = DIGITS.indexOf(c)

    private fun midpoint(
        a: String,
        b: String?,
    ): String {
        if (b != null) {
            // Shared prefix (a padded with '0'): keep it and recurse on the rest.
            var n = 0
            while (n < b.length && (if (n < a.length) a[n] else '0') == b[n]) n++
            require(n < b.length) { "no room between $a and $b" }
            if (n > 0) return b.substring(0, n) + midpoint(if (n < a.length) a.substring(n) else "", b.substring(n))
        }
        val da = if (a.isNotEmpty()) digit(a[0]) else 0
        val db = if (b != null) digit(b[0]) else BASE
        if (db - da > 1) return DIGITS[(da + db + 1) / 2].toString()
        // Neighbouring first digits.
        if (b != null && b.length > 1) return b.substring(0, 1)
        return DIGITS[da].toString() + midpoint(if (a.isNotEmpty()) a.substring(1) else "", null)
    }

    /** [n] ascending keys strictly between [a] and [b], spread out so they stay short. */
    fun keysBetween(
        a: String?,
        b: String?,
        n: Int,
    ): List<String> {
        if (n <= 0) return emptyList()
        if (n == 1) return listOf(between(a, b))
        val lo = a.orEmpty()
        require(b == null || lo < b) { "order keys out of order: $a >= $b" }
        // Bisect (not step) so a long run stays short.
        val mid = midpoint(lo, b)
        val left = n / 2
        return keysBetween(a, mid, left) + mid + keysBetween(mid, b, n - left - 1)
    }

    /**
     * Keys for [ids] in this display order. Ids whose [prior] key still sits in order (the longest
     * increasing run of prior keys) keep it, so an unchanged entry is not rewritten; new and moved
     * entries get fresh keys between their kept neighbours. Falls back to an even respread when a
     * key would grow past [MAX_LENGTH].
     */
    fun assign(
        ids: List<String>,
        prior: Map<String, String>,
    ): List<String> {
        if (ids.isEmpty()) return emptyList()
        val existing = ids.map { id -> prior[id]?.takeIf(::isValid) }
        val out = arrayOfNulls<String>(ids.size)
        longestIncreasing(existing).forEach { out[it] = existing[it] }
        var i = 0
        while (i < ids.size) {
            if (out[i] != null) {
                i++
                continue
            }
            var j = i
            while (j < ids.size && out[j] == null) j++
            val lo = if (i > 0) out[i - 1] else null
            val hi = if (j < ids.size) out[j] else null
            keysBetween(lo, hi, j - i).forEachIndexed { k, key -> out[i + k] = key }
            i = j
        }
        val keys = out.map { it!! }
        return if (keys.any { it.length > MAX_LENGTH }) keysBetween(null, null, ids.size) else keys
    }

    /** Indices of a longest strictly increasing subsequence of the non-null [keys] (patience sort). */
    internal fun longestIncreasing(keys: List<String?>): List<Int> {
        val tails = ArrayList<Int>() // index of the smallest tail of each run length
        val previous = IntArray(keys.size) { -1 }
        keys.forEachIndexed { index, key ->
            if (key == null) return@forEachIndexed
            var lo = 0
            var hi = tails.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (keys[tails[mid]]!! < key) lo = mid + 1 else hi = mid
            }
            if (lo > 0) previous[index] = tails[lo - 1]
            if (lo == tails.size) tails += index else tails[lo] = index
        }
        if (tails.isEmpty()) return emptyList()
        val out = ArrayList<Int>(tails.size)
        var cursor = tails.last()
        while (cursor >= 0) {
            out += cursor
            cursor = previous[cursor]
        }
        return out.reversed()
    }
}

/** Per-collection text map `key<TAB>value`, one line per key (order keys). Damage-tolerant. */
object SyncTextMapCodec {
    fun encode(map: Map<String, String>): String =
        map.entries
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}\t${it.value}" }

    fun decode(
        text: String?,
        valueOk: (String) -> Boolean = { true },
    ): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = HashMap<String, String>()
        text.lineSequence().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            val key = line.substring(0, tab)
            val value = line.substring(tab + 1).trim()
            if (SyncMerge.isValidKey(key) && valueOk(value)) out[key] = value
        }
        return out
    }
}
