package app.mawja.android.ai

import app.mawja.android.data.transcript.TranscriptCue
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StudyNotesTest {
    private fun topic(
        from: Long,
        minutes: Int,
        line: String,
    ) = (0 until minutes * 6).map { TranscriptCue(from + it * 10_000L, "$line ${it % 5}", 9_000) }

    @Test
    fun `stamp formats minutes and hours`() {
        assertThat(StudyNotes.stamp(0)).isEqualTo("00:00")
        assertThat(StudyNotes.stamp(65_000)).isEqualTo("01:05")
        assertThat(StudyNotes.stamp(3_723_000)).isEqualTo("1:02:03")
        assertThat(StudyNotes.stamp(-5)).isEqualTo("00:00")
    }

    @Test
    fun `each chapter keeps its own points only`() {
        val cues =
            topic(0, 4, "نتكلم عن الطبخ والمطبخ والوصفة") + topic(240_000, 4, "دلوقتي الكرة والملعب والمباراة")
        val chapters = listOf(VideoInsights.Chapter(0, "الطبخ"), VideoInsights.Chapter(240_000, "الكرة"))
        val sections = StudyNotes.sections(cues, chapters)
        assertThat(sections).hasSize(2)
        assertThat(sections[0].points.all { it.startMs < 240_000 }).isTrue()
        assertThat(sections[1].points.all { it.startMs >= 240_000 }).isTrue()
        val text = StudyNotes.text(cues, "ملاحظات", chapters)
        assertThat(text).startsWith("ملاحظات")
        assertThat(text).contains("▸ 00:00  الطبخ")
        assertThat(text).contains("▸ 04:00  الكرة")
    }

    @Test
    fun `a short video gets one untitled list and an empty one gets nothing`() {
        val text = StudyNotes.text(topic(0, 1, "كلام قصير عن حاجة واحدة"))
        assertThat(text).contains("•")
        assertThat(text).doesNotContain("▸")
        assertThat(StudyNotes.text(emptyList())).isEmpty()
    }
}
