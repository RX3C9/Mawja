package app.mawja.android.player.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class ScreenshotPlanTest {
    @Test
    fun `the capture is the video's own resolution, not the screen's`() {
        assertEquals(1920 to 1080, ScreenshotPlan.captureSize(1920, 1080, 1f, 1080, 608))
    }

    @Test
    fun `anamorphic pixels are stretched to their display width`() {
        assertEquals(1920 to 1080, ScreenshotPlan.captureSize(1440, 1080, 4f / 3f, 0, 0))
    }

    @Test
    fun `an unknown video size falls back to the surface, and nothing known gives null`() {
        assertEquals(1080 to 608, ScreenshotPlan.captureSize(0, 0, 1f, 1080, 608))
        assertNull(ScreenshotPlan.captureSize(0, 0, 1f, 0, 0))
    }

    @Test
    fun `huge frames are scaled down keeping their shape`() {
        assertEquals(7680 to 4320, ScreenshotPlan.captureSize(15360, 8640, 1f, 0, 0))
    }

    @Test
    fun `file name carries a safe title, the position and the time`() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(
            "Mawja_A_b_c_01-05_19700101-000000.png",
            ScreenshotPlan.fileName("A/b: c?", 65_000L, 0L, utc),
        )
        assertEquals("Mawja_1-01-01_19700101-000001.png", ScreenshotPlan.fileName("  ", 3_661_000L, 1_000L, utc))
        assertEquals("Mawja_أغنية_جميلة_00-00_19700101-000000.png", ScreenshotPlan.fileName("أغنية جميلة", 0L, 0L, utc))
    }
}
