/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader

import java.util.ArrayDeque

/** One byte range of a file, inclusive, with how much of it is already on disk. */
data class Segment(
    val start: Long,
    val end: Long,
    val done: Long = 0,
) {
    val length: Long get() = end - start + 1
    val next: Long get() = start + done
    val complete: Boolean get() = done >= length
}

/** Pure planning and pacing rules of the download engine (JVM-tested). */
object DownloadMath {
    const val MAX_SEGMENTS = 16
    const val MIN_SEGMENT_BYTES = 1L shl 20 // 1 MiB

    /**
     * Splits [total] bytes into at most [maxSegments] contiguous ranges of at least [minSegment]
     * bytes each (the last one absorbs the remainder). One segment when the size is unknown.
     */
    fun split(
        total: Long,
        maxSegments: Int = MAX_SEGMENTS,
        minSegment: Long = MIN_SEGMENT_BYTES,
    ): List<Segment> {
        if (total <= 0) return emptyList()
        val count = (total / minSegment).coerceIn(1, maxSegments.coerceIn(1, MAX_SEGMENTS).toLong()).toInt()
        val size = total / count
        return List(count) { i ->
            val start = i * size
            val end = if (i == count - 1) total - 1 else start + size - 1
            Segment(start, end)
        }
    }

    /** Bytes on disk across [segments]. */
    fun downloaded(segments: List<Segment>): Long = segments.sumOf { it.done.coerceAtMost(it.length) }

    /** Wait before retry [attempt] (0-based): 1 s, 2 s, 4 s … capped at 30 s. */
    fun retryDelayMs(attempt: Int): Long = (1000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)

    const val MAX_RETRIES = 8

    /** Seconds left at [bytesPerSecond], or -1 when unknown. */
    fun etaSeconds(
        remaining: Long,
        bytesPerSecond: Long,
    ): Long = if (remaining <= 0) 0 else if (bytesPerSecond <= 0) -1 else (remaining + bytesPerSecond - 1) / bytesPerSecond

    /** The speed-limit choices offered in settings, bytes per second; 0 = unlimited. */
    val SPEED_LIMITS = listOf(0L, 256L * 1024, 512L * 1024, 1L shl 20, 2L shl 20, 5L shl 20)
}

/**
 * Token bucket shared by every transfer: [reserve] returns how long the caller must sleep before
 * writing those bytes so the total stays under [limitBps]. A limit of 0 never waits.
 */
class SpeedLimiter(
    @Volatile var limitBps: Long = 0,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var nextFreeAt = 0L

    @Synchronized
    fun reserve(bytes: Int): Long {
        val limit = limitBps
        if (limit <= 0) {
            nextFreeAt = 0
            return 0
        }
        val now = clock()
        val start = maxOf(now, nextFreeAt)
        nextFreeAt = start + bytes * 1000L / limit
        return (start - now).coerceAtLeast(0)
    }
}

/** Rolling speed: bytes per second over the last [windowMs], plus one sample per second for a graph. */
class SpeedMeter(
    private val windowMs: Long = 3_000,
    private val historySize: Int = 60,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val events = ArrayDeque<LongArray>()
    private val history = ArrayDeque<Long>()
    private var lastSampleAt = -1L

    @Synchronized
    fun add(bytes: Long) {
        val now = clock()
        events.addLast(longArrayOf(now, bytes))
        trim(now)
        sample(now)
    }

    @Synchronized
    fun bytesPerSecond(): Long {
        val now = clock()
        trim(now)
        sample(now)
        return current(now)
    }

    @Synchronized
    fun history(): List<Long> = history.toList()

    private fun current(now: Long): Long {
        if (events.isEmpty()) return 0
        val sum = events.sumOf { it[1] }
        val span = (now - events.first()[0]).coerceIn(1_000L, windowMs)
        return sum * 1000 / span
    }

    private fun trim(now: Long) {
        while (events.isNotEmpty() && now - events.first()[0] > windowMs) events.removeFirst()
    }

    private fun sample(now: Long) {
        if (lastSampleAt < 0) lastSampleAt = now
        while (now - lastSampleAt >= 1_000) {
            lastSampleAt += 1_000
            history.addLast(current(now))
            while (history.size > historySize) history.removeFirst()
        }
    }
}

/** Which queued downloads may start now, in what order. */
object QueuePolicy {
    data class Candidate(
        val id: String,
        val priority: Int,
        val createdAt: Long,
        val wifiOnly: Boolean,
        val notBefore: Long,
        val paused: Boolean,
    )

    /** Eligible ids, highest priority first, then oldest first, at most [slots]. */
    fun pick(
        queued: List<Candidate>,
        slots: Int,
        now: Long,
        unmetered: Boolean,
        globalWifiOnly: Boolean,
    ): List<String> =
        queued
            .asSequence()
            .filter { !it.paused && now >= it.notBefore && (unmetered || !(it.wifiOnly || globalWifiOnly)) }
            .sortedWith(compareByDescending<Candidate> { it.priority }.thenBy { it.createdAt })
            .take(slots.coerceAtLeast(0))
            .map { it.id }
            .toList()

    /** Next 01:00 local time after [now] for "download tonight". */
    fun tonight(
        now: java.util.Calendar,
    ): Long {
        val c = now.clone() as java.util.Calendar
        c.set(java.util.Calendar.HOUR_OF_DAY, 1)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        if (!c.after(now)) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }
}

/** Link handling for "share to Mawja Downloader" and the clipboard. */
object LinkRules {
    private val URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val YOUTUBE = Regex("""^https?://((www|m|music)\.)?(youtube\.com|youtu\.be)/""", RegexOption.IGNORE_CASE)

    /** The first http(s) link in shared text, trimmed of trailing punctuation. */
    fun firstUrl(text: String?): String? = text?.let { URL.find(it)?.value?.trimEnd('.', ',', ')', ']', '!', '?') }

    /** YouTube pages are opened in Mawja (which picks quality and format), not fetched as files. */
    fun isYouTube(url: String): Boolean = YOUTUBE.containsMatchIn(url)

    /** A file name from Content-Disposition or the URL path. */
    fun fileName(
        url: String,
        contentDisposition: String?,
    ): String {
        val fromHeader =
            contentDisposition?.let { cd ->
                Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)?.let {
                    runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull()
                } ?: Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
            }
        val fromPath =
            url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/').let {
                runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            }
        return (fromHeader ?: fromPath).replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "download" }
    }
}
