/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AnnouncementFilterTest {
    private val now = Instant.parse("2026-10-22T12:00:00Z").toEpochMilli()
    private val hour = 3_600_000L

    private val query =
        """
        [
          {"document":{"name":"projects/p/databases/(default)/documents/announcements/a1","fields":{
            "title":{"stringValue":"Hello"},"body":{"stringValue":"World"},"kind":{"stringValue":"dialog"},
            "targetTier":{"nullValue":null},"locale":{"stringValue":"ar"},
            "ctaLabel":{"stringValue":"Open"},"ctaUrl":{"stringValue":"mawja://upgrade"},
            "active":{"booleanValue":true},"startAt":{"timestampValue":"2026-10-22T10:00:00.123Z"},
            "endAt":{"timestampValue":"2026-10-23T00:00:00Z"}}},"readTime":"2026-10-22T12:00:00Z"},
          {"document":{"name":"projects/p/databases/(default)/documents/announcements/a2","fields":{
            "title":{"stringValue":"T"},"body":{"stringValue":"B"},"kind":{"stringValue":"popup"},
            "ctaLabel":{"stringValue":"Click"},"ctaUrl":{"stringValue":"javascript:x"},
            "active":{"booleanValue":true}}}},
          {"document":{"name":"projects/p/databases/(default)/documents/announcements/a3","fields":{
            "title":{"stringValue":""},"body":{"stringValue":"no title"},"active":{"booleanValue":true}}}},
          {"readTime":"2026-10-22T12:00:00Z"}
        ]
        """.trimIndent()

    private fun a(
        id: String,
        active: Boolean = true,
        start: Long? = null,
        end: Long? = null,
        tier: String? = null,
        locale: String? = null,
    ) = Announcement(id, "t$id", "b$id", active = active, startAt = start, endAt = end, targetTier = tier, locale = locale)

    @Test
    fun decodesRunQueryResponse() {
        val list = Announcement.listFromQuery(query)
        assertEquals(listOf("a1", "a2"), list.map { it.id })
        val a1 = list[0]
        assertEquals("dialog", a1.kind)
        assertNull(a1.targetTier)
        assertEquals("ar", a1.locale)
        assertEquals(Instant.parse("2026-10-22T10:00:00.123Z").toEpochMilli(), a1.startAt)
        assertTrue(a1.hasCta)
        val a2 = list[1]
        assertEquals(Announcement.KIND_BANNER, a2.kind)
        assertEquals("", a2.ctaUrl)
        assertFalse(a2.hasCta)
        assertNull(a2.startAt)
    }

    @Test
    fun garbageQueryIsEmpty() {
        assertTrue(Announcement.listFromQuery("nope").isEmpty())
        assertTrue(Announcement.listFromQuery("{}").isEmpty())
        assertTrue(Announcement.listFromQuery("[]").isEmpty())
    }

    @Test
    fun scheduleWindow() {
        val all =
            listOf(
                a("live", start = now - hour, end = now + hour),
                a("future", start = now + hour),
                a("ended", start = now - 2 * hour, end = now),
                a("open"),
                a("off", active = false),
            )
        val ids = AnnouncementFilter.visible(all, now, "free", "en", emptySet()).map { it.id }
        assertEquals(listOf("live", "open"), ids)
    }

    @Test
    fun tierLocaleAndDismissed() {
        val all =
            listOf(
                a("everyone"),
                a("plusOnly", tier = "plus"),
                a("freeOnly", tier = "free"),
                a("arabic", locale = "ar"),
                a("english", locale = "en"),
                a("gone"),
            )
        val freeAr = AnnouncementFilter.visible(all, now, "free", "ar-EG", setOf("gone")).map { it.id }.toSet()
        assertEquals(setOf("everyone", "freeOnly", "arabic"), freeAr)
        val plusEn = AnnouncementFilter.visible(all, now, "PLUS", "en_US", emptySet()).map { it.id }.toSet()
        assertEquals(setOf("everyone", "plusOnly", "english", "gone"), plusEn)
    }

    @Test
    fun newestFirstAndDistinct() {
        val all = listOf(a("old", start = now - 3 * hour), a("new", start = now - hour), a("new", start = now - hour), a("none"))
        assertEquals(listOf("new", "old", "none"), AnnouncementFilter.visible(all, now, "free", "en", emptySet()).map { it.id })
    }

    @Test
    fun cacheRoundTrip() {
        val list = Announcement.listFromQuery(query) + a("x", tier = "pro", end = now)
        assertEquals(list, Announcement.decodeList(Announcement.encodeList(list)))
        assertTrue(Announcement.decodeList(null).isEmpty())
        assertTrue(Announcement.decodeList("{bad").isEmpty())
        assertTrue(Announcement.encodeList(emptyList()).let { Announcement.decodeList(it) }.isEmpty())
    }
}
