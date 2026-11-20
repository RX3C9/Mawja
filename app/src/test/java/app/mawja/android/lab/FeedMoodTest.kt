package app.mawja.android.lab

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeedMoodTest {
    private data class V(val id: String, val score: Double, val topics: List<String>)

    private val feed = listOf(
        V("prank", 1.0, listOf("prank")),
        V("lecture", 0.9, listOf("Education", "science")),
        V("plain", 0.95, listOf("music")),
    )

    @Test
    fun `focus lifts learning and drops pranks`() {
        val out = MoodRanker.rerank(feed, FeedMood.FOCUS, { it.score }, { it.topics }).map { it.id }
        assertThat(out.first()).isEqualTo("lecture")
        assertThat(out.last()).isEqualTo("prank")
    }

    @Test
    fun `off keeps the order and nothing is removed`() {
        assertThat(MoodRanker.rerank(feed, FeedMood.OFF, { it.score }, { it.topics })).isEqualTo(feed)
        assertThat(MoodRanker.rerank(feed, FeedMood.CALM, { it.score }, { it.topics })).containsExactlyElementsIn(feed)
    }
}
