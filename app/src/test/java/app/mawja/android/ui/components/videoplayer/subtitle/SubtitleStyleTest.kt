package app.mawja.android.ui.components.videoplayer.subtitle

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleStyleTest {
    private val style = SubtitleStyle(bottomPadding = 40f, fullscreenBottomPadding = 120f, verticalFullscreenBottomPadding = 260f)

    @Test
    fun `each layout has its own position`() {
        assertThat(style.bottomPaddingFor(isFullscreen = false, isVertical = true)).isEqualTo(40f)
        assertThat(style.bottomPaddingFor(isFullscreen = true, isVertical = false)).isEqualTo(120f)
        assertThat(style.bottomPaddingFor(isFullscreen = true, isVertical = true)).isEqualTo(260f)
    }

    @Test
    fun `captions start in the system font`() {
        assertThat(SubtitleStyle().font).isEqualTo(SubtitleFont.DEFAULT)
        assertThat(SubtitleFont.DEFAULT.familyName).isNull()
    }

    @Test
    fun `a saved font comes back and an unknown one falls back to the default`() {
        SubtitleFont.entries.forEach { assertThat(SubtitleFont.fromStored(it.name)).isEqualTo(it) }
        assertThat(SubtitleFont.fromStored(null)).isEqualTo(SubtitleFont.DEFAULT)
        assertThat(SubtitleFont.fromStored("COMIC")).isEqualTo(SubtitleFont.DEFAULT)
    }

    @Test
    fun `every other font names a distinct Android family`() {
        val families = SubtitleFont.entries.filter { it != SubtitleFont.DEFAULT }.map { it.familyName }
        assertThat(families).containsNoDuplicates()
        assertThat(families).doesNotContain(null)
    }
}
