package app.mawja.android.ui.components.videoplayer.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TouchRippleMathTest {
    @Test
    fun `wave reaches toward the farthest corner`() {
        assertThat(TouchRippleMath.maxRadius(0f, 0f, 3f, 4f)).isWithin(0.001f).of(5f)
        assertThat(TouchRippleMath.maxRadius(3f, 4f, 3f, 4f)).isWithin(0.001f).of(5f)
        assertThat(TouchRippleMath.maxRadius(50f, 50f, 100f, 100f)).isWithin(0.01f).of(70.71f)
    }

    @Test
    fun `an empty box has no wave`() {
        assertThat(TouchRippleMath.maxRadius(10f, 10f, 0f, 100f)).isEqualTo(0f)
    }

    @Test
    fun `wave starts under the fingertip and grows to its reach`() {
        assertThat(TouchRippleMath.radiusAt(0f, 100f)).isEqualTo(TouchRippleMath.MIN_RADIUS_PX)
        assertThat(TouchRippleMath.radiusAt(1f, 100f)).isWithin(0.001f).of(85f)
        assertThat(TouchRippleMath.radiusAt(0.5f, 100f)).isWithin(0.001f).of(66.75f)
    }

    @Test
    fun `wave never shrinks below the fingertip`() {
        assertThat(TouchRippleMath.radiusAt(1f, 0f)).isEqualTo(TouchRippleMath.MIN_RADIUS_PX)
    }

    @Test
    fun `wave fades out as it spreads`() {
        assertThat(TouchRippleMath.alphaAt(0f)).isEqualTo(TouchRippleMath.START_ALPHA)
        assertThat(TouchRippleMath.alphaAt(1f)).isEqualTo(0f)
        assertThat(TouchRippleMath.alphaAt(2f)).isEqualTo(0f)
    }
}
