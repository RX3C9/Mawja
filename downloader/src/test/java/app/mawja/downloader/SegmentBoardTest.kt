/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentBoardTest {
    private val inFlight = 64L * 1024
    private val minSteal = 512L * 1024

    @Test
    fun planCoversEveryByteOnce() {
        val total = 100L * 1024 * 1024 + 7
        val segs = RangeMath.plan(total, 8)
        assertEquals(8, segs.size)
        assertEquals(0L, segs.first().start)
        assertEquals(total - 1, segs.last().end)
        segs.zipWithNext().forEach { (a, b) -> assertEquals(a.end + 1, b.start) }
        assertEquals(total, segs.sumOf { it.length })
    }

    @Test
    fun planClampsConnectionsAndSmallFiles() {
        assertEquals(32, RangeMath.plan(100L * 1024 * 1024, 64).size)
        assertEquals(1, RangeMath.plan(500_000, 8).size)
        assertEquals(1, RangeMath.plan(100L * 1024 * 1024, 0).size)
        assertTrue(RangeMath.plan(0, 8).isEmpty())
        assertTrue(RangeMath.plan(-5, 8).isEmpty())
    }

    @Test
    fun connectionPolicyFollowsTier() {
        assertEquals(8, ConnectionPolicy.connections(0, royal = false))
        assertEquals(8, ConnectionPolicy.connections(32, royal = false))
        assertEquals(32, ConnectionPolicy.connections(32, royal = true))
        assertEquals(1, ConnectionPolicy.connections(1, royal = false))
        assertEquals(3, ConnectionPolicy.parallel(0, royal = false))
        assertEquals(3, ConnectionPolicy.parallel(10, royal = false))
        assertEquals(10, ConnectionPolicy.parallel(10, royal = true))
    }

    @Test
    fun rangeHeaderAsksForWhatIsLeft() {
        assertEquals("bytes=140-199", RangeMath.header(Segment(100, 199, done = 40)))
    }

    @Test
    fun parsesContentRange() {
        assertEquals(ContentRange(0, 0, 1234), RangeMath.parseContentRange("bytes 0-0/1234"))
        assertEquals(ContentRange(5, 9, -1), RangeMath.parseContentRange("bytes 5-9/*"))
        assertNull(RangeMath.parseContentRange("bytes 9-5/100"))
        assertNull(RangeMath.parseContentRange("bytes 0-100/50"))
        assertNull(RangeMath.parseContentRange("nonsense"))
        assertNull(RangeMath.parseContentRange(null))
    }

    @Test
    fun answersChecksStartAndSize() {
        val range = ContentRange(0, 0, 1234)
        assertTrue(RangeMath.answers(range, 0, 1234))
        assertTrue(RangeMath.answers(range, 0, -1))
        assertFalse(RangeMath.answers(range, 1, 1234))
        assertFalse(RangeMath.answers(range, 0, 999))
        assertFalse(RangeMath.answers(null, 0, 1234))
    }

    @Test
    fun stealCutsTheBiggestPartInHalfPastTheInFlightBytes() {
        val steal = RangeMath.steal(listOf(Segment(0, 9_999_999)), minSteal, inFlight)
        assertNotNull(steal)
        steal!!
        assertEquals(0, steal.index)
        assertEquals(5_032_767L, steal.newEnd)
        assertEquals(Segment(5_032_768, 9_999_999), steal.added)
        assertTrue(steal.newEnd + 1 >= inFlight)
    }

    @Test
    fun stealRefusesSmallOrFinishedParts() {
        assertNull(RangeMath.steal(listOf(Segment(0, 999_999)), minSteal, 0))
        assertNull(RangeMath.steal(listOf(Segment(0, 99, done = 100)), minSteal, 0))
        assertNull(RangeMath.steal(emptyList(), minSteal, 0))
    }

    @Test
    fun boardClaimsStealsAndAdvances() {
        val board = SegmentBoard(listOf(Segment(0, 3_999_999), Segment(4_000_000, 7_999_999)), minSteal, inFlight)
        assertEquals(0, board.claim())
        assertEquals(1, board.claim())
        assertNull(board.claim())

        val added = board.steal()
        assertEquals(2, added)
        assertEquals(2_032_767L, board.part(0).end)
        assertEquals(Segment(2_032_768, 3_999_999), board.part(2))
        assertNull(board.claim())
        assertEquals(8_000_000L, board.snapshot().sumOf { it.length })

        assertEquals(2_032_768L, board.advance(0, 10_000_000))
        assertEquals(0L, board.remaining(0))
        board.release(0)
        assertNull(board.claim())

        assertEquals(100L, board.advance(1, 100))
        assertEquals(2_032_868L, board.downloaded())
        assertFalse(board.isComplete())
    }

    @Test
    fun releasedUnfinishedPartCanBeClaimedAgain() {
        val board = SegmentBoard(listOf(Segment(0, 999)), minSteal, inFlight)
        assertEquals(0, board.claim())
        board.release(0)
        assertEquals(0, board.claim())
        board.advance(0, 1000)
        assertTrue(board.isComplete())
    }

    @Test
    fun stealNeverCutsIntoABufferAlreadyRead() {
        // A worker reads one buffer (== inFlight) from its part, another worker steals before the write.
        val board = SegmentBoard(listOf(Segment(0, 9_999_999)), minSteal, inFlight)
        val mine = board.claim()!!
        val read = minOf(inFlight, board.remaining(mine))
        assertNotNull(board.steal())
        assertTrue(board.remaining(mine) >= read)
        assertEquals(read, board.advance(mine, read))
        assertEquals(10_000_000L, board.snapshot().sumOf { it.length })
    }
}
