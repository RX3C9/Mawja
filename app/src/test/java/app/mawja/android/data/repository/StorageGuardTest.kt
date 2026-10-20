/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageGuardTest {
    private val mb = 1024L * 1024L
    private val gb = 1024L * mb
    private val usage = MediaCacheUsage(videos = 800 * mb, songs = 600 * mb, artwork = 200 * mb, other = 50 * mb)

    @Test
    fun `threshold is 3 percent clamped to 500 MB to 2 GB`() {
        assertEquals(500 * mb, StorageGuard.lowThreshold(8 * gb))
        assertEquals(64 * gb * 3 / 100, StorageGuard.lowThreshold(64 * gb))
        assertEquals(2048 * mb, StorageGuard.lowThreshold(512 * gb))
    }

    @Test
    fun `nothing happens with room to spare`() {
        assertTrue(StorageGuard.plan(usage, freeBytes = 10 * gb, totalBytes = 64 * gb).isEmpty())
        assertTrue(StorageGuard.plan(usage, freeBytes = 500 * mb, totalBytes = 8 * gb).isEmpty())
    }

    @Test
    fun `clears cheapest caches first until twice the threshold`() {
        // 8 GB phone: low = 500 MB, target = 1000 MB. Free 400 MB + other 50 + artwork 200 + videos 800 >= 1000.
        assertEquals(
            listOf(MediaCacheType.OTHER, MediaCacheType.ARTWORK, MediaCacheType.VIDEOS),
            StorageGuard.plan(usage, freeBytes = 400 * mb, totalBytes = 8 * gb),
        )
        // Free 450 MB with 600 MB of images: other + artwork reach 1100 MB, videos and songs stay.
        assertEquals(
            listOf(MediaCacheType.OTHER, MediaCacheType.ARTWORK),
            StorageGuard.plan(usage.copy(artwork = 600 * mb), freeBytes = 450 * mb, totalBytes = 8 * gb),
        )
    }

    @Test
    fun `skips empty caches and clears everything when still short`() {
        val noImages = usage.copy(artwork = 0, other = 0)
        assertEquals(listOf(MediaCacheType.VIDEOS), StorageGuard.plan(noImages, 300 * mb, 8 * gb))
        val small = MediaCacheUsage(videos = 10 * mb, songs = 10 * mb, artwork = 10 * mb, other = 10 * mb)
        assertEquals(StorageGuard.ORDER, StorageGuard.plan(small, 100 * mb, 8 * gb))
    }
}
