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
import java.util.TimeZone

class AdminLogicTest {
    private val now = 1_800_000_000_000L
    private val day = ManualTier.DAY_MS

    @Test
    fun parsesSearchQueries() {
        assertEquals(UserQuery.Email("omar@example.com"), UserQuery.parse("  Omar@Example.com "))
        assertEquals(UserQuery.MawjaId("MW-AB12-CD34"), UserQuery.parse("mw-ab12-cd34"))
        assertEquals(UserQuery.MawjaId("MW-AB12-CD34"), UserQuery.parse("MWAB12CD34"))
        assertEquals(UserQuery.Username("omar_m"), UserQuery.parse("@Omar_M"))
        assertEquals(UserQuery.Uid("Xk3pQ9vT2mN8rL5wY1zA7bC4dE6f"), UserQuery.parse("Xk3pQ9vT2mN8rL5wY1zA7bC4dE6f"))
        assertNull(UserQuery.parse("   "))
        assertNull(UserQuery.parse("not a valid query!"))
    }

    @Test
    fun manualTierEndDates() {
        assertNull(ManualTier.until("free", 30, false, null, now))
        assertNull(ManualTier.until("lifetime", 30, false, null, now))
        assertNull(ManualTier.until("plus", 0, false, null, now))
        assertEquals(now + 30 * day, ManualTier.until("plus", 30, false, null, now))
        // Extending stacks on the remaining time, but not on an end date already past.
        assertEquals(now + 40 * day, ManualTier.until("pro", 30, true, now + 10 * day, now))
        assertEquals(now + 30 * day, ManualTier.until("pro", 30, true, now - day, now))
        assertEquals(now + 30 * day, ManualTier.until("pro", 30, false, now + 10 * day, now))
    }

    @Test
    fun effectiveTierLapses() {
        assertEquals("plus", ManualTier.effective("plus", now + 1, now))
        assertEquals("free", ManualTier.effective("plus", now - 1, now))
        assertEquals("lifetime", ManualTier.effective("lifetime", null, now))
        assertEquals("free", ManualTier.effective("gold", null, now))
        assertEquals("free", ManualTier.effective(null, null, now))
    }

    @Test
    fun csvQuotesAndNeutralisesFormulas() {
        assertEquals("plain", Csv.cell("plain"))
        assertEquals("\"a,b\"", Csv.cell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.cell("say \"hi\""))
        assertEquals("'=HYPERLINK(1)", Csv.cell("=HYPERLINK(1)"))
        assertEquals("", Csv.cell(null))
        assertEquals("code,tier\r\nMW-AAAA-BBBB-CCCC,plus\r\n", Csv.table(listOf("code", "tier"), listOf(listOf("MW-AAAA-BBBB-CCCC", "plus"))))
    }

    @Test
    fun dailyBucketsCountPerDay() {
        val utc = TimeZone.getTimeZone("UTC")
        val today = DailyBuckets.dayStart(now, utc)
        val events = listOf(now to 1, today to 2, today - 1 to 1, today - 6 * day to 5, today - 7 * day to 9, now + day to 4)
        assertEquals(listOf(5, 0, 0, 0, 0, 1, 3), DailyBuckets.count(events, 7, now, utc))
        val cairo = TimeZone.getTimeZone("Africa/Cairo")
        assertEquals(14, DailyBuckets.count(emptyList(), 14, now, cairo).size)
    }

    @Test
    fun announcementPhasesAndValidation() {
        assertEquals(AnnouncementPhase.OFF, Announcements.phase(false, null, null, now))
        assertEquals(AnnouncementPhase.SCHEDULED, Announcements.phase(true, now + 1, null, now))
        assertEquals(AnnouncementPhase.LIVE, Announcements.phase(true, now - 1, now + 1, now))
        assertEquals(AnnouncementPhase.ENDED, Announcements.phase(true, now - 2, now - 1, now))
        assertTrue(Announcements.problems("Hi", "Body", "", "", now, null).isEmpty())
        assertEquals(listOf("title", "body"), Announcements.problems(" ", "", "", "", now, null))
        assertTrue("cta_url" in Announcements.problems("Hi", "Body", "Go", "http://x", now, null))
        assertTrue("cta_label" in Announcements.problems("Hi", "Body", "", "https://x", now, null))
        assertTrue("end_before_start" in Announcements.problems("Hi", "Body", "", "", now, now))
    }

    @Test
    fun auditFilter() {
        assertEquals("code", AuditCategories.of("code.batch.create"))
        assertEquals("other", AuditCategories.of("weird"))
        assertTrue(AuditCategories.matches("user.tier", "users/abc", "boss", "user", ""))
        assertFalse(AuditCategories.matches("user.tier", "users/abc", "boss", "code", ""))
        assertTrue(AuditCategories.matches("user.tier", "users/abc", "boss", null, "ABC"))
        assertFalse(AuditCategories.matches("user.tier", "users/abc", "boss", null, "zzz"))
    }

    @Test
    fun permissionsMirrorTheRules() {
        assertTrue(Permissions.can(StaffRole.SUPPORT, Perm.REPORTS_HANDLE))
        assertFalse(Permissions.can(StaffRole.SUPPORT, Perm.USERS_STATUS))
        assertTrue(Permissions.can(StaffRole.MODERATOR, Perm.USERS_STATUS))
        assertFalse(Permissions.can(StaffRole.MODERATOR, Perm.USERS_BAN))
        assertFalse(Permissions.can(StaffRole.MODERATOR, Perm.USERS_TIER))
        assertTrue(Permissions.can(StaffRole.ADMIN, Perm.CODES_MANAGE))
        assertFalse(Permissions.can(StaffRole.ADMIN, Perm.STAFF_MANAGE))
        assertTrue(Permissions.can(StaffRole.OWNER, Perm.STAFF_MANAGE))
        assertFalse(Permissions.can(null, Perm.USERS_VIEW))
        assertEquals(Perm.entries.toSet(), Permissions.of(StaffRole.OWNER))
        assertTrue(Permissions.canEditStaff(StaffRole.OWNER, "omar", "sara", "admin"))
        assertFalse(Permissions.canEditStaff(StaffRole.OWNER, "omar", "omar", "owner"))
        assertFalse(Permissions.canEditStaff(StaffRole.OWNER, "omar", "co", "owner"))
        assertFalse(Permissions.canEditStaff(StaffRole.ADMIN, "boss", "sara", "support"))
        assertTrue(Permissions.canSetStatus(StaffRole.MODERATOR, "mo", "alice", UserStatus.SUSPENDED))
        assertFalse(Permissions.canSetStatus(StaffRole.MODERATOR, "mo", "alice", UserStatus.BANNED))
        assertTrue(Permissions.canSetStatus(StaffRole.ADMIN, "boss", "alice", UserStatus.BANNED))
        assertFalse(Permissions.canSetStatus(StaffRole.OWNER, "omar", "omar", UserStatus.SUSPENDED))
        assertEquals(StaffRole.OWNER, StaffRole.of("owner"))
        assertNull(StaffRole.of("removed"))
    }
}
