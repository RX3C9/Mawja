/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import java.net.URLEncoder
import kotlin.math.roundToInt

/** How often a plan is paid for. */
enum class PlanPeriod { MONTH, YEAR, ONCE }

/** One purchasable plan. [egp] is whole Egyptian pounds, [usdCents] the international price. Mirrors backend billing/plans.ts. */
data class MembershipPlan(
    val id: String,
    val tier: MawjaTier,
    val period: PlanPeriod,
    val egp: Int,
    val usdCents: Int,
)

/** The membership catalogue shown on the Upgrade page (PLAN_V2 §3). */
object MembershipCatalog {
    val plans: List<MembershipPlan> =
        listOf(
            MembershipPlan("plus_monthly", MawjaTier.PLUS, PlanPeriod.MONTH, 49, 199),
            MembershipPlan("plus_yearly", MawjaTier.PLUS, PlanPeriod.YEAR, 399, 1499),
            MembershipPlan("pro_monthly", MawjaTier.PRO, PlanPeriod.MONTH, 99, 399),
            MembershipPlan("pro_yearly", MawjaTier.PRO, PlanPeriod.YEAR, 799, 2999),
            MembershipPlan("family_monthly", MawjaTier.FAMILY, PlanPeriod.MONTH, 149, 599),
            MembershipPlan("family_yearly", MawjaTier.FAMILY, PlanPeriod.YEAR, 1199, 4499),
            MembershipPlan("lifetime", MawjaTier.LIFETIME, PlanPeriod.ONCE, 1999, 5900),
        )

    /** Tiers in the order the page shows them. */
    val tiers: List<MawjaTier> = listOf(MawjaTier.PLUS, MawjaTier.PRO, MawjaTier.FAMILY, MawjaTier.LIFETIME)

    fun plansFor(tier: MawjaTier): List<MembershipPlan> = plans.filter { it.tier == tier }

    /** Whole-percent saving of the yearly plan over twelve monthly payments, or null without both. */
    fun yearlySavingPercent(tier: MawjaTier): Int? {
        val monthly = plans.firstOrNull { it.tier == tier && it.period == PlanPeriod.MONTH } ?: return null
        val yearly = plans.firstOrNull { it.tier == tier && it.period == PlanPeriod.YEAR } ?: return null
        val full = monthly.egp * 12
        return ((full - yearly.egp) * 100.0 / full).roundToInt()
    }

    /** Whether [tier] is the member's current one (Lifetime and Family count as already above Pro). */
    fun isCurrent(
        tier: MawjaTier,
        current: MawjaTier,
    ): Boolean = tier == current

    /**
     * The web checkout link for [plan], or null when no checkout is configured yet ("coming soon").
     * The page receives the plan id, the member's Mawja ID and the UI language.
     */
    fun checkoutUrl(
        base: String,
        plan: MembershipPlan,
        mawjaId: String?,
        language: String,
    ): String? {
        val root = base.trim()
        if (root.isEmpty() || !(root.startsWith("https://") || root.startsWith("http://"))) return null
        fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
        val params =
            buildList {
                add("plan=${enc(plan.id)}")
                if (!mawjaId.isNullOrBlank()) add("mawja_id=${enc(mawjaId)}")
                if (language.isNotBlank()) add("lang=${enc(language)}")
            }.joinToString("&")
        val separator = if ('?' in root) (if (root.endsWith('?') || root.endsWith('&')) "" else "&") else "?"
        return root + separator + params
    }
}
