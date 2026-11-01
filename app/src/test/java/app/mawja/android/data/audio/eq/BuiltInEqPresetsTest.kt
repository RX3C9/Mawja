package app.mawja.android.data.audio.eq

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BuiltInEqPresetsTest {
    @Test
    fun `ids, legacy names and string resources are unique`() {
        val all = BuiltInEqPresets.all
        assertThat(all.map { it.id }.toSet()).hasSize(all.size)
        assertThat(all.map { it.legacyName }.toSet()).hasSize(all.size)
        assertThat(all.map { it.nameRes }.toSet()).hasSize(all.size)
    }

    @Test
    fun `flat stays first and the Arabic presets follow it`() {
        val ids = BuiltInEqPresets.all.map { it.id }
        assertThat(ids.first()).isEqualTo(BuiltInEqPresets.FLAT_ID)
        assertThat(ids.subList(1, 1 + BuiltInEqPresets.ARABIC_IDS.size)).isEqualTo(BuiltInEqPresets.ARABIC_IDS)
        assertThat(ids).contains("builtin:bass_boost")
    }

    @Test
    fun `every built-in curve is already within limits`() {
        BuiltInEqPresets.all.forEach { preset ->
            assertThat(preset.curve.sanitized()).isEqualTo(preset.curve)
        }
    }

    @Test
    fun `auto preamp keeps every built-in at or below 0 dB`() {
        BuiltInEqPresets.all.forEach { preset ->
            val spec = EqState().selectPreset(preset.id).processingSpec(bypass = false)
            val peakWithPreamp = EqFilterMath.peakDb(spec.bands) + spec.preampDb
            assertThat(peakWithPreamp).isAtMost(0.01)
        }
    }

    @Test
    fun `quran preset cuts rumble and lifts speech presence`() {
        val bands = BuiltInEqPresets.byId("builtin:quran")!!.curve.bands
        val at40 = EqFilterMath.magnitudeDb(bands, 40.0)
        val at3k = EqFilterMath.magnitudeDb(bands, 3_000.0)
        assertThat(at40).isLessThan(-3.0)
        assertThat(at3k).isGreaterThan(3.0)
    }

    @Test
    fun `mahraganat is bass heavy and tarab lifts the voice`() {
        val mahraganat = BuiltInEqPresets.byId("builtin:mahraganat")!!.curve.bands
        assertThat(EqFilterMath.magnitudeDb(mahraganat, 30.0)).isGreaterThan(5.0)
        assertThat(EqFilterMath.magnitudeDb(mahraganat, 450.0)).isLessThan(0.0)

        val tarab = BuiltInEqPresets.byId("builtin:tarab")!!.curve.bands
        assertThat(EqFilterMath.magnitudeDb(tarab, 1_800.0)).isGreaterThan(1.5)
    }

    @Test
    fun `arabic presets play in graphic mode too`() {
        BuiltInEqPresets.ARABIC_IDS.forEach { id ->
            val graphic = EqState(mode = EqMode.GRAPHIC).selectPreset(id)
            assertThat(graphic.active.presetId).isEqualTo(id)
            assertThat(graphic.active.curve.bands).hasSize(GraphicEq.FREQUENCIES.size)
            assertThat(graphic.isEdited).isFalse()
        }
    }
}
