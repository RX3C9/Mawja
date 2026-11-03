/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportDraftTest {
    @Test
    fun validity() {
        assertFalse(ReportDraft(ReportType.BUG, "  hi  ").valid)
        assertTrue(ReportDraft(ReportType.BUG, "  crashes on open ").valid)
        assertFalse(ReportDraft(ReportType.BUG, "x".repeat(ReportDraft.MAX + 1)).valid)
        assertEquals("crashes", ReportDraft(ReportType.BUG, " crashes \n").trimmed)
    }

    @Test
    fun typesMatchTheAdminInbox() {
        assertEquals(listOf("video", "bug", "content", "suggestion"), ReportType.entries.map { it.id })
    }

    @Test
    fun logKeepsTheNewestEnd() {
        val log = "a".repeat(ReportDraft.LOG_MAX) + "CRASH"
        val clipped = ReportDraft.clipLog(log)
        assertEquals(ReportDraft.LOG_MAX, clipped.length)
        assertTrue(clipped.endsWith("CRASH"))
        assertEquals("short", ReportDraft.clipLog("short"))
    }
}
