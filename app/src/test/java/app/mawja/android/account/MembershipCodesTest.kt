/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MembershipCodesTest {
    @Test
    fun localChecksNameTheProblem() {
        assertThat(localCodeError("MW-ABCD-EFGH-JKM4")).isNull()
        assertThat(localCodeError("abcd efgh jkm٤")).isNull()
        assertThat(localCodeError("MW-ABCD-EFGH")).isEqualTo(RedeemError.FORMAT)
        assertThat(localCodeError("MW-ABCD-EFGH-JK0O")).isEqualTo(RedeemError.BAD_CHARACTER)
        assertThat(localCodeError("MW-ABCD-EFGH-JKM5")).isEqualTo(RedeemError.TYPO)
    }

    @Test
    fun expiredCodeMembershipIsFree() {
        val now = 1_800_000_000_000L
        assertThat(effectiveProfileTier(MawjaTier.PLUS, now + 1, now)).isEqualTo(MawjaTier.PLUS)
        assertThat(effectiveProfileTier(MawjaTier.PLUS, now, now)).isEqualTo(MawjaTier.FREE)
        assertThat(effectiveProfileTier(MawjaTier.PRO, null, now)).isEqualTo(MawjaTier.PRO)
        assertThat(effectiveProfileTier(MawjaTier.LIFETIME, now - 1, now)).isEqualTo(MawjaTier.LIFETIME)
    }

    @Test
    fun guestOnlyBackendCannotRedeem() =
        kotlinx.coroutines.test.runTest {
            assertThat(GuestOnlyAccountBackend().redeemCode("MW-ABCD-EFGH-JKM4")).isEqualTo(RedeemResult.Failed(RedeemError.NOT_AVAILABLE))
        }
}
