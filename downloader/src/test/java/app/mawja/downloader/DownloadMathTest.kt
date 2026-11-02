/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DownloadMathTest {
    @Test
    fun splitCoversEveryByteOnce() {
        val total = 100L * 1024 * 1024 + 7
        val segs = DownloadMath.split(total)
        assertEquals(16, segs.size)
        assertEquals(0L, segs.first().start)
        assertEquals(total - 1, segs.last().end)
        segs.zipWithNext().forEach { (a, b) -> assertEquals(a.end + 1, b.start) }
        assertEquals(total, segs.sumOf { it.length })
    }

    @Test
    fun smallFilesGetFewerSegments() {
        assertEquals(1, DownloadMath.split(500_000).size)
        assertEquals(3, DownloadMath.split(3L * 1024 * 1024 + 10).size)
        assertTrue(DownloadMath.split(0).isEmpty())
        assertTrue(DownloadMath.split(-1).isEmpty())
    }

    @Test
    fun segmentResumePoint() {
        val s = Segment(100, 199, done = 40)
        assertEquals(140L, s.next)
        assertFalse(s.complete)
        assertTrue(s.copy(done = 100).complete)
        assertEquals(140L, DownloadMath.downloaded(listOf(s, Segment(200, 299, 100))))
    }

    @Test
    fun retryBackoffIsCapped() {
        assertEquals(1000L, DownloadMath.retryDelayMs(0))
        assertEquals(2000L, DownloadMath.retryDelayMs(1))
        assertEquals(30_000L, DownloadMath.retryDelayMs(10))
    }

    @Test
    fun eta() {
        assertEquals(-1L, DownloadMath.etaSeconds(100, 0))
        assertEquals(0L, DownloadMath.etaSeconds(0, 0))
        assertEquals(4L, DownloadMath.etaSeconds(1000, 300))
    }

    @Test
    fun limiterPacesBytes() {
        var now = 0L
        val limiter = SpeedLimiter(limitBps = 1000) { now }
        assertEquals(0L, limiter.reserve(500))
        assertEquals(500L, limiter.reserve(500))
        now = 2000
        assertEquals(0L, limiter.reserve(100))
        limiter.limitBps = 0
        assertEquals(0L, limiter.reserve(1_000_000))
    }

    @Test
    fun meterMeasuresWindow() {
        var now = 0L
        val meter = SpeedMeter(windowMs = 3000) { now }
        meter.add(1000)
        now = 1000
        meter.add(1000)
        now = 2000
        assertEquals(1000L, meter.bytesPerSecond())
        assertEquals(2, meter.history().size)
        now = 10_000
        assertEquals(0L, meter.bytesPerSecond())
    }

    @Test
    fun queueOrderAndRules() {
        fun c(
            id: String,
            p: Int = 0,
            t: Long = 0,
            wifi: Boolean = false,
            nb: Long = 0,
            paused: Boolean = false,
        ) = QueuePolicy.Candidate(id, p, t, wifi, nb, paused)
        val list = listOf(c("a", t = 1), c("b", p = 5, t = 2), c("c", t = 0), c("w", wifi = true), c("n", nb = 100), c("p", paused = true))
        assertEquals(listOf("b", "c", "a"), QueuePolicy.pick(list, 3, now = 50, unmetered = false, globalWifiOnly = false))
        assertEquals(listOf("b", "c", "w", "a", "n"), QueuePolicy.pick(list, 9, now = 100, unmetered = true, globalWifiOnly = false))
        assertTrue(QueuePolicy.pick(list, 9, now = 100, unmetered = false, globalWifiOnly = true).isEmpty())
    }

    @Test
    fun tonightIsNextOneAm() {
        val now = Calendar.getInstance().apply { set(2026, 10, 2, 18, 0, 0) }
        val t = Calendar.getInstance().apply { timeInMillis = QueuePolicy.tonight(now) }
        assertEquals(3, t.get(Calendar.DAY_OF_MONTH))
        assertEquals(1, t.get(Calendar.HOUR_OF_DAY))
        val early = Calendar.getInstance().apply { set(2026, 10, 2, 0, 30, 0) }
        assertEquals(2, Calendar.getInstance().apply { timeInMillis = QueuePolicy.tonight(early) }.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun links() {
        assertEquals("https://x.com/a.zip", LinkRules.firstUrl("look https://x.com/a.zip."))
        assertNull(LinkRules.firstUrl("no link"))
        assertTrue(LinkRules.isYouTube("https://youtu.be/abc"))
        assertTrue(LinkRules.isYouTube("https://music.youtube.com/watch?v=1"))
        assertFalse(LinkRules.isYouTube("https://example.com/youtube.com/"))
        assertEquals("a b.zip", LinkRules.fileName("https://x.com/a%20b.zip?x=1", null))
        assertEquals("r.pdf", LinkRules.fileName("https://x.com/dl", "attachment; filename=\"r.pdf\""))
        assertEquals("ملف.mp3", LinkRules.fileName("https://x.com/dl", "attachment; filename*=UTF-8''%D9%85%D9%84%D9%81.mp3"))
        assertEquals("download", LinkRules.fileName("https://x.com/", null))
    }
}
