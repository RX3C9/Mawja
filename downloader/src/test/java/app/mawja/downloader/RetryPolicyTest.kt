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

class RetryPolicyTest {
    @Test
    fun classifiesAnswers() {
        listOf(0, 408, 429, 500, 502, 503, 504, 599).forEach { assertEquals("$it", RetryPolicy.Outcome.RETRY, RetryPolicy.classify(it)) }
        listOf(401, 403, 410).forEach { assertEquals("$it", RetryPolicy.Outcome.EXPIRED, RetryPolicy.classify(it)) }
        listOf(400, 404, 416).forEach { assertEquals("$it", RetryPolicy.Outcome.FATAL, RetryPolicy.classify(it)) }
        assertEquals(RetryPolicy.Outcome.EXPIRED, HttpFailure(403).outcome)
    }

    @Test
    fun backoffDoublesWithEqualJitterAndCaps() {
        assertEquals(500L, RetryPolicy.delayMs(0, random = 0.0))
        assertEquals(999L, RetryPolicy.delayMs(0, random = 0.999_999))
        assertEquals(4_000L, RetryPolicy.delayMs(3, random = 0.0))
        assertEquals(15_000L, RetryPolicy.delayMs(10, random = 0.0))
        assertTrue(RetryPolicy.delayMs(10, random = 0.99) < 30_000L)
    }

    @Test
    fun retryAfterWinsWhenSane() {
        assertEquals(2_000L, RetryPolicy.delayMs(0, retryAfterMs = 2_000, random = 0.0))
        assertEquals(0L, RetryPolicy.delayMs(4, retryAfterMs = 0, random = 0.0))
        // Ten minutes is more than we honour: plain backoff instead.
        assertEquals(500L, RetryPolicy.delayMs(0, retryAfterMs = 10 * 60_000L, random = 0.0))
    }

    @Test
    fun parsesRetryAfter() {
        assertEquals(120_000L, RetryPolicy.parseRetryAfter("120"))
        assertEquals(0L, RetryPolicy.parseRetryAfter("-5"))
        assertNull(RetryPolicy.parseRetryAfter(null))
        assertNull(RetryPolicy.parseRetryAfter("  "))
        assertNull(RetryPolicy.parseRetryAfter("soon"))
        val at = 1_445_412_480_000L // Wed, 21 Oct 2015 07:28:00 GMT
        assertEquals(5_000L, RetryPolicy.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT", nowMs = at - 5_000))
        assertEquals(0L, RetryPolicy.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT", nowMs = at + 5_000))
    }

    @Test
    fun parsesChecksums() {
        val sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        assertEquals(ExpectedChecksum(ExpectedChecksum.Algorithm.SHA256, sha256), ExpectedChecksum.parse("sha256:$sha256"))
        assertEquals(ExpectedChecksum.Algorithm.SHA256, ExpectedChecksum.parse("SHA-256=${sha256.uppercase()}")?.algorithm)
        assertEquals(sha256, ExpectedChecksum.parse("SHA-256=${sha256.uppercase()}")?.hex)
        assertEquals(ExpectedChecksum.Algorithm.SHA256, ExpectedChecksum.parse(sha256)?.algorithm)
        assertEquals(ExpectedChecksum.Algorithm.MD5, ExpectedChecksum.parse("md5:" + "a".repeat(32))?.algorithm)
        assertEquals(ExpectedChecksum.Algorithm.SHA1, ExpectedChecksum.parse("b".repeat(40))?.algorithm)
        assertEquals(ExpectedChecksum.Algorithm.SHA512, ExpectedChecksum.parse("c".repeat(128))?.algorithm)
        assertNull(ExpectedChecksum.parse("a".repeat(33)))
        assertNull(ExpectedChecksum.parse("sha256:" + "a".repeat(32)))
        assertNull(ExpectedChecksum.parse("crc32:deadbeef"))
        assertNull(ExpectedChecksum.parse("sha256:not-hex"))
        assertNull(ExpectedChecksum.parse(""))
        assertNull(ExpectedChecksum.parse(null))
    }

    @Test
    fun checksumEncodesAndMatches() {
        val c = ExpectedChecksum.parse("md5:" + "ab".repeat(16))!!
        assertEquals("md5:" + "ab".repeat(16), c.encode())
        assertEquals(c, ExpectedChecksum.parse(c.encode()))
        assertTrue(c.matches("AB".repeat(16)))
        assertFalse(c.matches("cd".repeat(16)))
        assertFalse(c.matches(null))
    }
}
