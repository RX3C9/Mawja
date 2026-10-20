/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipPlansTest {
    private val plus = MembershipCatalog.plans.first { it.id == "plus_monthly" }

    @Test
    fun `catalogue matches the published prices`() {
        val egp = MembershipCatalog.plans.associate { it.id to it.egp }
        assertEquals(
            mapOf(
                "plus_monthly" to 49, "plus_yearly" to 399, "pro_monthly" to 99, "pro_yearly" to 799,
                "family_monthly" to 149, "family_yearly" to 1199, "lifetime" to 1999,
            ),
            egp,
        )
        assertEquals(PlanPeriod.ONCE, MembershipCatalog.plansFor(MawjaTier.LIFETIME).single().period)
        assertTrue(MembershipCatalog.tiers.all { MembershipCatalog.plansFor(it).isNotEmpty() })
    }

    @Test
    fun `yearly savings`() {
        assertEquals(32, MembershipCatalog.yearlySavingPercent(MawjaTier.PLUS))
        assertEquals(33, MembershipCatalog.yearlySavingPercent(MawjaTier.PRO))
        assertEquals(33, MembershipCatalog.yearlySavingPercent(MawjaTier.FAMILY))
        assertNull(MembershipCatalog.yearlySavingPercent(MawjaTier.LIFETIME))
        assertNull(MembershipCatalog.yearlySavingPercent(MawjaTier.FREE))
    }

    @Test
    fun `blank or unsafe checkout base means coming soon`() {
        assertNull(MembershipCatalog.checkoutUrl("", plus, "MW-1", "en"))
        assertNull(MembershipCatalog.checkoutUrl("   ", plus, "MW-1", "en"))
        assertNull(MembershipCatalog.checkoutUrl("javascript:alert(1)", plus, "MW-1", "en"))
    }

    @Test
    fun `checkout url carries plan, id and language`() {
        assertEquals(
            "https://mawja.app/checkout?plan=plus_monthly&mawja_id=MW-AB12-CD34&lang=ar",
            MembershipCatalog.checkoutUrl("https://mawja.app/checkout", plus, "MW-AB12-CD34", "ar"),
        )
        assertEquals(
            "https://x.test/c?src=app&plan=plus_monthly&lang=en",
            MembershipCatalog.checkoutUrl(" https://x.test/c?src=app ", plus, null, "en"),
        )
        assertEquals(
            "https://x.test/c?plan=plus_monthly",
            MembershipCatalog.checkoutUrl("https://x.test/c?", plus, "", ""),
        )
    }
}
