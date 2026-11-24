package app.mawja.android.ui.components.videoplayer.gesture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HoldSpeedDragTest {
    private fun speed(
        dx: Float,
        dy: Float = 0f,
        boost: Float = 2.0f,
    ) = HoldSpeedDrag.speedFor(boostSpeed = boost, dx = dx, dy = dy, stepTravelPx = 22f)

    @Test
    fun `holding still keeps the boost`() {
        assertThat(speed(dx = 0f)).isEqualTo(2.0f)
        assertThat(speed(dx = 21f)).isEqualTo(2.0f)
    }

    @Test
    fun `sliding right speeds up in quarter steps`() {
        assertThat(speed(dx = 44f)).isEqualTo(2.5f)
    }

    @Test
    fun `sliding left slows down`() {
        assertThat(speed(dx = -66f)).isEqualTo(1.25f)
    }

    @Test
    fun `sliding up speeds up and down slows down`() {
        assertThat(speed(dx = 0f, dy = -44f)).isEqualTo(2.5f)
        assertThat(speed(dx = 0f, dy = 44f)).isEqualTo(1.5f)
    }

    @Test
    fun `the longer axis decides a diagonal slide`() {
        assertThat(HoldSpeedDrag.travelOf(dx = 30f, dy = -40f)).isEqualTo(40f)
        assertThat(HoldSpeedDrag.travelOf(dx = -50f, dy = 10f)).isEqualTo(-50f)
        assertThat(speed(dx = 30f, dy = -40f)).isEqualTo(2.25f)
    }

    @Test
    fun `speed stays between half and four times`() {
        assertThat(speed(dx = 10_000f)).isEqualTo(HoldSpeedDrag.MAX_SPEED)
        assertThat(speed(dx = -10_000f)).isEqualTo(HoldSpeedDrag.MIN_SPEED)
    }

    @Test
    fun `a boost outside the range starts from its nearest end`() {
        assertThat(speed(dx = 0f, boost = 6f)).isEqualTo(4.0f)
        assertThat(speed(dx = -22f, boost = 6f)).isEqualTo(3.75f)
    }
}
