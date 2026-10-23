package app.mawja.android.player.display

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomFillTest {
    @Test
    fun `a 16 by 9 video on a 20 by 9 phone fills at 1_25`() {
        assertEquals(1.25f, ZoomFill.fillScale(16f / 9f, 2400, 1080), 0.001f)
    }

    @Test
    fun `matching shapes or unknown sizes need no zoom`() {
        assertEquals(1f, ZoomFill.fillScale(16f / 9f, 1920, 1080), 0.001f)
        assertEquals(1f, ZoomFill.fillScale(0f, 1920, 1080), 0.001f)
        assertEquals(1f, ZoomFill.fillScale(1.7f, 0, 1080), 0.001f)
    }

    @Test
    fun `a vertical video on a landscape screen fills by its width`() {
        assertEquals((2400f / 1080f) / (9f / 16f), ZoomFill.fillScale(9f / 16f, 2400, 1080), 0.01f)
    }

    @Test
    fun `a release near the fill scale snaps onto it`() {
        assertEquals(1.25f, ZoomFill.snap(1.2f, 1.25f), 0.0001f)
        assertEquals(1.25f, ZoomFill.snap(1.33f, 1.25f), 0.0001f)
    }

    @Test
    fun `a release far from it stays put, and no snap when fill is 1`() {
        assertEquals(1.6f, ZoomFill.snap(1.6f, 1.25f), 0.0001f)
        assertEquals(1.005f, ZoomFill.snap(1.005f, 1f), 0.0001f)
    }

    @Test
    fun `stored resize mode falls back to fit`() {
        assertEquals(ZoomFill.RESIZE_FIT, ZoomFill.storedResizeMode(null))
        assertEquals(ZoomFill.RESIZE_FIT, ZoomFill.storedResizeMode(7))
        assertEquals(ZoomFill.RESIZE_FILL, ZoomFill.storedResizeMode(1))
        assertEquals(ZoomFill.RESIZE_ZOOM, ZoomFill.storedResizeMode(2))
    }
}
