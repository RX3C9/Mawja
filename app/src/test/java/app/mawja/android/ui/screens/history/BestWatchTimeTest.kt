package app.mawja.android.ui.screens.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class BestWatchTimeTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(hour: Int, day: Int = 0) = (day * 24L + hour) * 3_600_000L + 60_000L

    @Test fun tooFewSamplesGivesNull() {
        assertNull(BestWatchTime.suggest(List(5) { at(20, it) }, utc))
    }

    @Test fun picksBusiestWindow() {
        val ts = List(6) { at(20, it) } + List(5) { at(21, it) } + List(4) { at(8, it) }
        assertEquals(20, BestWatchTime.suggest(ts, utc))
    }

    @Test fun windowWrapsMidnight() {
        val h = IntArray(24)
        h[23] = 6; h[0] = 6; h[12] = 7
        assertEquals(23, BestWatchTime.peakWindowStart(h))
    }

    @Test fun ignoresInvalidTimestamps() {
        val h = BestWatchTime.hourHistogram(listOf(0L, -5L, at(3)), utc)
        assertEquals(1, h.sum())
        assertEquals(1, h[3])
    }

    @Test fun formatsWindow() {
        assertEquals("23:00–01:00", BestWatchTime.formatWindow(23))
        assertEquals("08:00–10:00", BestWatchTime.formatWindow(8))
    }
}
