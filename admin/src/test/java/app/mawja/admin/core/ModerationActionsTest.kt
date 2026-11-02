/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModerationActionsTest {
    @Test
    fun thirdWarningBans() {
        assertEquals(ModerationActions.Warning(1, false), ModerationActions.warn(0))
        assertEquals(ModerationActions.Warning(2, false), ModerationActions.warn(1))
        assertEquals(ModerationActions.Warning(3, true), ModerationActions.warn(2))
        assertEquals(10, ModerationActions.warn(10).warnings)
        assertEquals(1, ModerationActions.warn(-4).warnings)
    }

    @Test
    fun durations() {
        assertEquals(1_000L + 3_600_000L, ModerationActions.until(1_000L, 1))
        assertEquals(1_000L + 3_600_000L, ModerationActions.until(1_000L, 0))
        assertEquals(7, ModerationActions.customDays(" 7 "))
        assertNull(ModerationActions.customDays("0"))
        assertNull(ModerationActions.customDays("abc"))
        assertNull(ModerationActions.customDays("4000"))
    }

    @Test
    fun messages() {
        assertTrue(ModerationActions.messageOk("hi"))
        assertFalse(ModerationActions.messageOk("  "))
        assertFalse(ModerationActions.messageOk("x".repeat(1001)))
        assertEquals("m5", ModerationActions.messageId(5))
    }

    @Test
    fun lockableMatchesTheApp() {
        assertTrue(ModerationActions.LOCKABLE.containsAll(listOf("cloud_sync", "premium_themes", "reports", "codes")))
        assertEquals(ModerationActions.LOCKABLE.size, ModerationActions.LOCKABLE.toSet().size)
    }
}
