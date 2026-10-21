/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.codes

import app.mawja.codes.CodeRedeemPolicy.CodeProblem
import app.mawja.codes.CodeRedeemPolicy.CodeState
import app.mawja.codes.CodeRedeemPolicy.DAY_MS
import app.mawja.codes.CodeRedeemPolicy.Decision.Grant
import app.mawja.codes.CodeRedeemPolicy.Decision.Refuse
import app.mawja.codes.CodeRedeemPolicy.Membership
import app.mawja.codes.CodeRedeemPolicy.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeRedeemPolicyTest {
    private val now = 1_800_000_000_000L

    @Test
    fun freeUserStartsNow() {
        assertEquals(Grant("plus", now + 30 * DAY_MS), CodeRedeemPolicy.decide(null, "plus", 30, now))
        assertEquals(Grant("pro", now + 365 * DAY_MS), CodeRedeemPolicy.decide(Membership("free", null), "pro", 365, now))
    }

    @Test
    fun expiredMembershipStartsNow() {
        val old = Membership("pro", now - DAY_MS)
        assertEquals(Grant("plus", now + 30 * DAY_MS), CodeRedeemPolicy.decide(old, "plus", 30, now))
    }

    @Test
    fun sameTierStacksOnRemainingTime() {
        val old = Membership("plus", now + 10 * DAY_MS)
        assertEquals(Grant("plus", now + 40 * DAY_MS), CodeRedeemPolicy.decide(old, "plus", 30, now))
    }

    @Test
    fun betterActiveTierKeepsTierAndGainsDays() {
        val old = Membership("pro", now + 10 * DAY_MS)
        assertEquals(Grant("pro", now + 40 * DAY_MS), CodeRedeemPolicy.decide(old, "plus", 30, now))
    }

    @Test
    fun betterCodeTierUpgradesAndStacks() {
        val old = Membership("plus", now + 10 * DAY_MS)
        assertEquals(Grant("pro", now + 40 * DAY_MS), CodeRedeemPolicy.decide(old, "pro", 30, now))
    }

    @Test
    fun lifetime() {
        assertEquals(Grant("lifetime", null), CodeRedeemPolicy.decide(Membership("plus", now + DAY_MS), "lifetime", 0, now))
        assertEquals(Refuse(Refusal.ALREADY_LIFETIME), CodeRedeemPolicy.decide(Membership("lifetime", null), "pro", 30, now))
        assertEquals(Refuse(Refusal.ALREADY_LIFETIME), CodeRedeemPolicy.decide(Membership("lifetime", null), "lifetime", 0, now))
    }

    @Test
    fun openEndedGrant() {
        assertEquals(Refuse(Refusal.ALREADY_COVERED), CodeRedeemPolicy.decide(Membership("pro", null), "plus", 30, now))
        assertEquals(Refuse(Refusal.ALREADY_COVERED), CodeRedeemPolicy.decide(Membership("pro", null), "pro", 30, now))
        assertEquals(Grant("family", now + 30 * DAY_MS), CodeRedeemPolicy.decide(Membership("pro", null), "family", 30, now))
    }

    @Test
    fun codeProblems() {
        val ok = CodeState("plus", 30, 1, 0, false, null)
        assertNull(CodeRedeemPolicy.problem(ok, now))
        assertNull(CodeRedeemPolicy.problem(ok.copy(expiresAtMillis = now + 1), now))
        assertEquals(CodeProblem.EXPIRED, CodeRedeemPolicy.problem(ok.copy(expiresAtMillis = now), now))
        assertEquals(CodeProblem.REVOKED, CodeRedeemPolicy.problem(ok.copy(revoked = true, used = 1), now))
        assertEquals(CodeProblem.USED_UP, CodeRedeemPolicy.problem(ok.copy(used = 1), now))
        assertNull(CodeRedeemPolicy.problem(ok.copy(maxUses = 5, used = 4), now))
        assertEquals(CodeProblem.MALFORMED, CodeRedeemPolicy.problem(ok.copy(tier = "gold"), now))
        assertEquals(CodeProblem.MALFORMED, CodeRedeemPolicy.problem(ok.copy(days = 0), now))
        assertNull(CodeRedeemPolicy.problem(ok.copy(tier = "lifetime", days = 0), now))
    }
}
