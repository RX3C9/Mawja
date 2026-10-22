/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.codes

/**
 * What redeeming a code does to a membership. The Firestore rules (backend/firestore.rules,
 * `tierMath`) enforce exactly this, so the app computes the same result before it writes.
 *
 * - An active Lifetime membership takes nothing more: the code is refused and stays unused.
 * - A Lifetime code makes the membership Lifetime (no end date).
 * - An open-ended membership (granted by staff, no end date) of an equal or better tier: refused,
 *   the code would add nothing. A better code tier starts now for the code's days.
 * - Otherwise the days are added on top of the remaining time of an active membership (or from
 *   now), and the better of the current and the code's tier is kept, so nobody loses paid time.
 */
object CodeRedeemPolicy {
    val TIERS = listOf("plus", "pro", "lifetime", "family")
    private val RANK = mapOf("free" to 0, "plus" to 1, "pro" to 2, "family" to 3, "lifetime" to 4)
    const val DAY_MS = 86_400_000L

    fun rank(tier: String?): Int = RANK[tier] ?: 0

    /** The membership on the users doc: [untilMillis] null means no end date. */
    data class Membership(
        val tier: String,
        val untilMillis: Long?,
    ) {
        fun isActive(now: Long): Boolean = tier in TIERS && (untilMillis == null || untilMillis > now)
    }

    /** The code doc's fields that matter for redeeming it. */
    data class CodeState(
        val tier: String,
        val days: Int,
        val maxUses: Int,
        val used: Int,
        val revoked: Boolean,
        val expiresAtMillis: Long?,
    )

    enum class CodeProblem { REVOKED, EXPIRED, USED_UP, MALFORMED }

    enum class Refusal { ALREADY_LIFETIME, ALREADY_COVERED }

    sealed interface Decision {
        data class Grant(
            val tier: String,
            val untilMillis: Long?,
        ) : Decision

        data class Refuse(
            val reason: Refusal,
        ) : Decision
    }

    /** Why a code cannot be redeemed right now, or null when it can. */
    fun problem(
        code: CodeState,
        now: Long,
    ): CodeProblem? =
        when {
            code.tier !in TIERS || code.days < 0 || (code.tier != "lifetime" && code.days < 1) -> CodeProblem.MALFORMED
            code.revoked -> CodeProblem.REVOKED
            code.expiresAtMillis != null && code.expiresAtMillis <= now -> CodeProblem.EXPIRED
            code.used >= code.maxUses -> CodeProblem.USED_UP
            else -> null
        }

    fun decide(
        current: Membership?,
        codeTier: String,
        codeDays: Int,
        now: Long,
    ): Decision {
        val active = current?.isActive(now) == true
        val cur = if (active) current!!.tier else "free"
        if (cur == "lifetime") return Decision.Refuse(Refusal.ALREADY_LIFETIME)
        if (codeTier == "lifetime") return Decision.Grant("lifetime", null)
        val add = codeDays * DAY_MS
        if (active && current!!.untilMillis == null) {
            return if (rank(cur) >= rank(codeTier)) Decision.Refuse(Refusal.ALREADY_COVERED) else Decision.Grant(codeTier, now + add)
        }
        val base = if (active) current!!.untilMillis!! else now
        val tier = if (rank(cur) > rank(codeTier)) cur else codeTier
        return Decision.Grant(tier, base + add)
    }
}
