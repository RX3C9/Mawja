/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import app.mawja.codes.MawjaCodes

/** Why a membership code could not be activated. Each has its own message (ar + en). */
enum class RedeemError {
    /** Not 12 symbols. */
    FORMAT,

    /** Contains 0, O, 1, I, L or other characters codes never use. */
    BAD_CHARACTER,

    /** The check symbol does not match: a typo. */
    TYPO,

    /** No such code. */
    INVALID,
    USED,

    /** This account already activated this code. */
    ALREADY_YOURS,
    EXPIRED,
    REVOKED,
    NOT_SIGNED_IN,
    NETWORK,
    TOO_MANY_ATTEMPTS,

    /** The account already has Lifetime. */
    ALREADY_LIFETIME,

    /** An open-ended membership already covers what the code gives. */
    ALREADY_COVERED,

    /** The account is suspended or banned. */
    ACCOUNT_RESTRICTED,

    /** This build has no Mawja accounts (foss / no Firebase config). */
    NOT_AVAILABLE,
    UNKNOWN,
}

sealed interface RedeemResult {
    /** [until] is epoch ms, null for Lifetime. */
    data class Activated(
        val tier: MawjaTier,
        val until: Long?,
    ) : RedeemResult

    data class Failed(
        val error: RedeemError,
    ) : RedeemResult
}

/** Local checks before anything goes to the network; null when the code is well formed. */
fun localCodeError(input: String): RedeemError? =
    when (MawjaCodes.parse(input)) {
        is MawjaCodes.Parsed.Valid -> null
        MawjaCodes.Parsed.WrongLength -> RedeemError.FORMAT
        MawjaCodes.Parsed.BadCharacter -> RedeemError.BAD_CHARACTER
        MawjaCodes.Parsed.BadCheck -> RedeemError.TYPO
    }

/** The tier to show for a profile: a code membership past its end date is Free again. */
fun effectiveProfileTier(
    tier: MawjaTier,
    tierUntil: Long?,
    now: Long,
): MawjaTier = if (tier != MawjaTier.FREE && tier != MawjaTier.LIFETIME && tierUntil != null && tierUntil <= now) MawjaTier.FREE else tier
