package app.mawja.android.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusModeTest {
    @Test
    fun `turning on hides all four and remembers what was there`() {
        val before = FocusModeSettings(homeFeed = true, shorts = false, comments = true, relatedVideos = true)
        val change = FocusMode.change(enable = true, current = before, snapshot = null)
        assertEquals(FocusModeSettings.FOCUSED, change.apply)
        assertEquals("1011", change.snapshot)
    }

    @Test
    fun `turning off restores the snapshot and clears it`() {
        val change = FocusMode.change(enable = false, current = FocusModeSettings.FOCUSED, snapshot = "1011")
        assertEquals(FocusModeSettings(homeFeed = true, shorts = false, comments = true, relatedVideos = true), change.apply)
        assertNull(change.snapshot)
    }

    @Test
    fun `a second turn on keeps the first snapshot`() {
        val change = FocusMode.change(enable = true, current = FocusModeSettings.FOCUSED, snapshot = "0110")
        assertEquals("0110", change.snapshot)
    }

    @Test
    fun `turning off without a usable snapshot brings everything back`() {
        for (raw in listOf(null, "", "10", "1x11", "11111")) {
            assertEquals(FocusModeSettings(), FocusMode.change(enable = false, current = FocusModeSettings.FOCUSED, snapshot = raw).apply)
        }
    }

    @Test
    fun `encode and decode round trip`() {
        val settings = FocusModeSettings(homeFeed = false, shorts = true, comments = false, relatedVideos = true)
        assertEquals(settings, FocusModeSettings.decode(settings.encode()))
    }
}
