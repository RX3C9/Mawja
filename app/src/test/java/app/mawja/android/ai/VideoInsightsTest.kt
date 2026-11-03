package app.mawja.android.ai

import app.mawja.android.data.transcript.TranscriptCue
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VideoInsightsTest {
    @Test
    fun `arabic search forgives hamza, ta marbuta, alef maqsura and tashkeel`() {
        assertThat(VideoInsights.matches("إنّ المدرسةَ الكُبرى", "ان المدرسه الكبري")).isTrue()
        assertThat(VideoInsights.matches("أحمد ذهب إلى السوق", "احمد السوق")).isTrue()
        assertThat(VideoInsights.matches("Hello World", "world")).isTrue()
        assertThat(VideoInsights.matches("أحمد ذهب", "محمد")).isFalse()
        assertThat(VideoInsights.matches("anything", "  ")).isTrue()
    }

    private fun topic(
        from: Long,
        minutes: Int,
        line: String,
    ) = (0 until minutes * 6).map { TranscriptCue(from + it * 10_000L, line, 9_000) }

    @Test
    fun `chapters split where the topic changes and are titled by its words`() {
        val cues =
            topic(0, 4, "نتكلم عن الطبخ والمطبخ والوصفة والطبخ") +
                topic(240_000, 4, "دلوقتي الكرة والملعب والمباراة والكرة") +
                topic(480_000, 4, "أخيرا البرمجة والكود والتطبيق والبرمجة")
        val chapters = VideoInsights.autoChapters(cues)
        assertThat(chapters.map { it.startMs }).containsExactly(0L, 230_000L, 500_000L).inOrder()
        assertThat(chapters[0].title).contains("الطبخ")
        assertThat(chapters[1].title).contains("الكرة")
        assertThat(chapters[2].title).contains("البرمجة")
    }

    @Test
    fun `short videos get no invented chapters`() {
        assertThat(VideoInsights.autoChapters(topic(0, 2, "كلام قصير عن حاجة واحدة"))).isEmpty()
        assertThat(VideoInsights.autoChapters(emptyList())).isEmpty()
    }

    @Test
    fun `key moments are on topic, not duplicated, and in time order`() {
        val cues =
            listOf(
                TranscriptCue(0, "أهلا بيكم يا جماعة", 2000),
                TranscriptCue(5_000, "النهارده هنشرح طريقة تحضير البيتزا في البيت", 3000),
                TranscriptCue(10_000, "طريقة تحضير البيتزا في البيت سهلة جدا", 3000),
                TranscriptCue(15_000, "العجينة محتاجة دقيق وخميرة وماية دافية", 3000),
                TranscriptCue(20_000, "الصوص بيتعمل من طماطم وثوم وريحان", 3000),
                TranscriptCue(25_000, "الفرن لازم يكون سخن جدا قبل البيتزا", 3000),
                TranscriptCue(30_000, "اشتركوا في القناة", 2000),
            )
        val moments = VideoInsights.keyMoments(cues, count = 3)
        assertThat(moments).hasSize(3)
        assertThat(moments.map { it.startMs }).isInOrder()
        assertThat(moments.none { it.text.startsWith("أهلا") }).isTrue()
    }
}
