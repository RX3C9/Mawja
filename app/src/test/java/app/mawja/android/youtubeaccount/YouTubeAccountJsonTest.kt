package app.mawja.android.youtubeaccount

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class YouTubeAccountJsonTest {
    @Test
    fun `subscriptions read channel id and title and the next page`() {
        val page =
            YouTubeAccountJson.subscriptions(
                """{"nextPageToken":"CDIQAA","items":[
                  {"snippet":{"title":"Alpha","resourceId":{"kind":"youtube#channel","channelId":"UC1"}}},
                  {"snippet":{"title":"No id"}},
                  {"snippet":{"title":"Beta","resourceId":{"channelId":"UC2"}}}]}""",
            )
        assertThat(page.items.map { it.channelId }).containsExactly("UC1", "UC2").inOrder()
        assertThat(page.items.first().channelName).isEqualTo("Alpha")
        assertThat(page.nextPageToken).isEqualTo("CDIQAA")
    }

    @Test
    fun `last page has no token`() {
        assertThat(YouTubeAccountJson.subscriptions("""{"items":[]}""").nextPageToken).isNull()
        assertThat(YouTubeAccountJson.subscriptions("""{"nextPageToken":"","items":[]}""").nextPageToken).isNull()
    }

    @Test
    fun `playlists keep id title and creation date`() {
        val page =
            YouTubeAccountJson.playlists(
                """{"items":[{"id":"PL1","snippet":{"title":"Road trip","publishedAt":"2024-05-01T10:00:00Z"}},{"id":"PL2","snippet":{}}]}""",
            )
        assertThat(page.items[0].title).isEqualTo("Road trip")
        assertThat(page.items[0].createdAt).isEqualTo(1714557600000L)
        assertThat(page.items[1].title).isEqualTo("PL2")
        assertThat(page.items[1].createdAt).isNull()
    }

    @Test
    fun `playlist items read video ids from either shape`() {
        val page =
            YouTubeAccountJson.playlistVideoIds(
                """{"items":[{"contentDetails":{"videoId":"v1"}},{"snippet":{"resourceId":{"videoId":"v2"}}},{"contentDetails":{}}]}""",
            )
        assertThat(page.items).containsExactly("v1", "v2").inOrder()
    }

    @Test
    fun `likes mark music and stay newest first`() {
        val page =
            YouTubeAccountJson.likes(
                """{"items":[
                  {"id":"a","snippet":{"title":"Song","channelTitle":"Artist","channelId":"UC9","categoryId":"10"}},
                  {"id":"b","snippet":{"title":"Talk","categoryId":"27"}}]}""",
                now = 1_000_000L,
                offset = 50,
            )
        assertThat(page.items.map { it.isMusic }).containsExactly(true, false).inOrder()
        assertThat(page.items[0].likedAt).isGreaterThan(page.items[1].likedAt)
        assertThat(page.items[0].likedAt).isEqualTo(1_000_000L - 50_000L)
    }

    @Test
    fun `error reason is read from google errors`() {
        assertThat(
            YouTubeAccountJson.errorReason("""{"error":{"code":403,"errors":[{"reason":"quotaExceeded"}],"status":"PERMISSION_DENIED"}}"""),
        ).isEqualTo("quotaExceeded")
        assertThat(YouTubeAccountJson.errorReason("""{"error":{"code":403,"status":"SERVICE_DISABLED"}}""")).isEqualTo("SERVICE_DISABLED")
        assertThat(YouTubeAccountJson.errorReason("not json")).isNull()
    }

    @Test
    fun `duplicates keep the first`() {
        assertThat(YouTubeAccountJson.distinctBy(listOf("a1", "b1", "a2")) { it.take(1) }).containsExactly("a1", "b1").inOrder()
    }
}
