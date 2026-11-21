/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndsAtTest {
    @Test fun normalSpeed() = assertEquals(1_600_000L, EndsAt.endMillis(1_000_000L, 0L, 600_000L, 1f))

    @Test fun doubleSpeedHalvesTheWait() = assertEquals(1_300_000L, EndsAt.endMillis(1_000_000L, 0L, 600_000L, 2f))

    @Test fun badSpeedFallsBackToOne() = assertEquals(1_100_000L, EndsAt.endMillis(1_000_000L, 500_000L, 600_000L, 0f))

    @Test fun unknownLength() = assertNull(EndsAt.endMillis(0L, 0L, 0L, 1f))
}
