package app.mawja.android.data.playlist

import app.mawja.android.data.model.Video
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaylistListFormatsTest {
    private fun video(
        id: String,
        title: String,
        channel: String = "Channel",
        duration: Int = 245,
    ) = Video(
        id = id,
        title = title,
        channelName = channel,
        channelId = "UC123",
        thumbnailUrl = "",
        duration = duration,
        viewCount = 0L,
        uploadDate = "",
    )

    @Test
    fun `m3u lists every video with its length, label and watch link in order`() {
        val text = PlaylistListFormats.m3u("Road\ntrip", listOf(video("aaaaaaaaaaa", "One"), video("bbbbbbbbbbb", "Two", duration = 0)))
        assertThat(text.lines()).containsExactly(
            "#EXTM3U",
            "#PLAYLIST:Road trip",
            "#EXTINF:245,Channel - One",
            "https://www.youtube.com/watch?v=aaaaaaaaaaa",
            "#EXTINF:-1,Channel - Two",
            "https://www.youtube.com/watch?v=bbbbbbbbbbb",
            "",
        ).inOrder()
    }

    @Test
    fun `csv quotes fields holding commas or quotes`() {
        val text = PlaylistListFormats.csv(listOf(video("aaaaaaaaaaa", "Hello, \"world\"", channel = "A")))
        assertThat(text).isEqualTo(
            "position,title,channel,duration_seconds,url\r\n" +
                "1,\"Hello, \"\"world\"\"\",A,245,https://www.youtube.com/watch?v=aaaaaaaaaaa\r\n",
        )
    }

    @Test
    fun `file name keeps the safe base and swaps the extension`() {
        assertThat(PlaylistListFormats.fileName(PlaylistListFormat.M3U, "My/Mix")).isEqualTo("My_Mix.m3u")
        assertThat(PlaylistListFormats.fileName(PlaylistListFormat.CSV, "My/Mix")).isEqualTo("My_Mix.csv")
    }
}
