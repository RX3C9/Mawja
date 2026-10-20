/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.smartnotify

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId

class SmartNotifyCodecTest {
    @Test
    fun roundTrips() {
        var state = SmartNotifyEngine.recordActivity(SmartNotifyState(), 1_760_000_000_000, ZoneId.of("Africa/Cairo"))
        val c = SmartCandidate(SmartKind.WEEKLY_RECAP, "2026-W43", "t", "x")
        state = SmartNotifyEngine.recordSent(state, c, 1_760_000_100_000)
        state = SmartNotifyEngine.recordOutcome(state, SmartNotifyEngine.contentHash(c.kind, c.contentKey), SmartOutcome.OPENED)
        assertThat(SmartNotifyCodec.decode(SmartNotifyCodec.encode(state))).isEqualTo(state)
    }

    @Test
    fun damagedOrMissingInputDecodesToDefaults() {
        assertThat(SmartNotifyCodec.decode(null)).isEqualTo(SmartNotifyState())
        assertThat(SmartNotifyCodec.decode("not json")).isEqualTo(SmartNotifyState())
        assertThat(SmartNotifyCodec.decode("""{"hours":[1,2],"kinds":{"unknown":0.5},"sent":[{"h":"x"}]}""")).isEqualTo(SmartNotifyState())
    }
}
