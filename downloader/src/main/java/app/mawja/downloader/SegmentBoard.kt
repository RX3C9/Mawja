/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader

/** How many connections and parallel downloads each tier gets (plan §6: free 8 / 3, Royal 32 / unlimited). */
object ConnectionPolicy {
    const val FREE_CONNECTIONS = 8
    const val ROYAL_CONNECTIONS = 32
    const val FREE_PARALLEL = 3

    /** "Unlimited" in practice: more than this many at once only splits the same bandwidth thinner. */
    const val ROYAL_PARALLEL = 10
    const val DEFAULT_CONNECTIONS = 8

    /** The connection choices offered in settings. */
    val CONNECTION_CHOICES = listOf(1, 2, 4, 8, 16, 32)

    fun maxConnections(royal: Boolean): Int = if (royal) ROYAL_CONNECTIONS else FREE_CONNECTIONS

    fun maxParallel(royal: Boolean): Int = if (royal) ROYAL_PARALLEL else FREE_PARALLEL

    /** The connections a download may open: what was asked (0 = default), clamped to the tier. */
    fun connections(
        requested: Int,
        royal: Boolean,
    ): Int = (if (requested <= 0) DEFAULT_CONNECTIONS else requested).coerceIn(1, maxConnections(royal))

    fun parallel(
        requested: Int,
        royal: Boolean,
    ): Int = (if (requested <= 0) FREE_PARALLEL else requested).coerceIn(1, maxParallel(royal))
}

/** A parsed `Content-Range: bytes start-end/total` header (total -1 when the server sent `*`). */
data class ContentRange(
    val start: Long,
    val end: Long,
    val total: Long,
)

/** Range math for planning and checking part requests. */
object RangeMath {
    private val CONTENT_RANGE = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)

    /** Splits [total] bytes into up to [connections] parts of at least [minSegment] bytes (up to 32). */
    fun plan(
        total: Long,
        connections: Int,
        minSegment: Long = DownloadMath.MIN_SEGMENT_BYTES,
    ): List<Segment> {
        if (total <= 0) return emptyList()
        val count = (total / minSegment.coerceAtLeast(1)).coerceIn(1, connections.coerceIn(1, ConnectionPolicy.ROYAL_CONNECTIONS).toLong()).toInt()
        val size = total / count
        return List(count) { i ->
            val start = i * size
            val end = if (i == count - 1) total - 1 else start + size - 1
            Segment(start, end)
        }
    }

    /** The `Range` header value that asks for what is left of [segment]. */
    fun header(segment: Segment): String = "bytes=${segment.next}-${segment.end}"

    fun parseContentRange(header: String?): ContentRange? {
        val m = header?.let { CONTENT_RANGE.matchEntire(it) } ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull() ?: return null
        if (end < start) return null
        val total = m.groupValues[3].toLongOrNull() ?: -1L
        if (total in 0..end) return null
        return ContentRange(start, end, total)
    }

    /**
     * True when a 206 answer really holds the bytes asked for: it starts at [from] and belongs to a
     * file of the expected size. A shorter answer is fine (the part loop asks again for the rest).
     */
    fun answers(
        range: ContentRange?,
        from: Long,
        expectedTotal: Long,
    ): Boolean = range != null && range.start == from && (expectedTotal <= 0 || range.total <= 0 || range.total == expectedTotal)

    /**
     * Work stealing: the part with the most bytes left is cut in half, the far half becomes a new
     * part. Returns the index to shrink, its new end and the new part, or null when no part has at
     * least [minSteal] bytes left (splitting smaller parts costs more in a new connection than it saves).
     * [inFlight] bytes past each part's resume point may already be on their way and are never stolen.
     */
    fun steal(
        segments: List<Segment>,
        minSteal: Long,
        inFlight: Long = 0,
    ): Steal? {
        var best = -1
        var bestLeft = 0L
        segments.forEachIndexed { i, s ->
            val left = s.length - s.done
            if (left > bestLeft) {
                best = i
                bestLeft = left
            }
        }
        if (best < 0) return null
        val victim = segments[best]
        val stealable = bestLeft - inFlight.coerceAtLeast(0)
        if (stealable < minSteal.coerceAtLeast(2) * 2) return null
        val cut = victim.next + inFlight.coerceAtLeast(0) + stealable / 2
        return Steal(best, cut - 1, Segment(cut, victim.end))
    }

    data class Steal(
        val index: Int,
        val newEnd: Long,
        val added: Segment,
    )
}

/**
 * Thread-safe live view of a download's parts: workers [claim] a part, write into it, [advance]
 * it, and when nothing is left to claim they [steal] half of the slowest part. Parts only ever
 * shrink at their far end, so a worker that is reading never loses the bytes it is about to write.
 */
class SegmentBoard(
    initial: List<Segment>,
    private val minSteal: Long = DownloadMath.MIN_SEGMENT_BYTES / 2,
    private val inFlight: Long = 0,
) {
    private val parts = initial.toMutableList()
    private val claimed = mutableSetOf<Int>()

    @Synchronized
    fun snapshot(): List<Segment> = parts.toList()

    @Synchronized
    fun downloaded(): Long = DownloadMath.downloaded(parts)

    @Synchronized
    fun isComplete(): Boolean = parts.all { it.complete }

    /** An unfinished part nobody works on, now owned by the caller; null when there is none. */
    @Synchronized
    fun claim(): Int? {
        val i = parts.indices.firstOrNull { it !in claimed && !parts[it].complete } ?: return null
        claimed += i
        return i
    }

    /** Splits the part with the most left and hands the new half to the caller (already claimed). */
    @Synchronized
    fun steal(): Int? {
        val plan = RangeMath.steal(parts, minSteal, inFlight) ?: return null
        val victim = parts[plan.index]
        parts[plan.index] = victim.copy(end = plan.newEnd)
        parts += plan.added
        val added = parts.lastIndex
        claimed += added
        return added
    }

    @Synchronized
    fun release(index: Int) {
        claimed -= index
    }

    @Synchronized
    fun part(index: Int): Segment = parts[index]

    /** Bytes still to fetch in part [index]; can drop while reading when another worker steals. */
    @Synchronized
    fun remaining(index: Int): Long = (parts[index].length - parts[index].done).coerceAtLeast(0)

    /** Records [bytes] written at the part's resume point; returns how many counted (never past the end). */
    @Synchronized
    fun advance(
        index: Int,
        bytes: Long,
    ): Long {
        val p = parts[index]
        val counted = bytes.coerceIn(0, (p.length - p.done).coerceAtLeast(0))
        parts[index] = p.copy(done = p.done + counted)
        return counted
    }
}
