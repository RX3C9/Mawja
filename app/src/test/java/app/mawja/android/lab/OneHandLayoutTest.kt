/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneHandLayoutTest {
    @Test fun centredWhenOff() = assertEquals(0f, OneHandLayout.transportBias(false, true), 0f)

    @Test fun centredOutsidePortraitFullscreen() = assertEquals(0f, OneHandLayout.transportBias(true, false), 0f)

    @Test fun loweredInPortraitFullscreen() =
        assertEquals(OneHandLayout.TRANSPORT_BIAS, OneHandLayout.transportBias(true, true), 0f)

    @Test fun loweredButtonsSitInTheBottomThird() {
        val fraction = OneHandLayout.heightFraction(OneHandLayout.transportBias(true, true))
        assertTrue(fraction > 2f / 3f)
        assertTrue(fraction < 1f)
    }

    @Test fun centreIsHalfway() = assertEquals(0.5f, OneHandLayout.heightFraction(0f), 0f)
}
