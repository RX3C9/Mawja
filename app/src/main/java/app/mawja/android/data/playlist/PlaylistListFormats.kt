package app.mawja.android.data.playlist

import app.mawja.android.data.model.Video

/** Plain list formats other players and spreadsheets open (#117): M3U and CSV. */
enum class PlaylistListFormat(
    val extension: String,
    val mimeType: String,
) {
    M3U("m3u", "audio/x-mpegurl"),
    CSV("csv", "text/csv"),
}

object PlaylistListFormats {
    private const val WATCH_URL = "https://www.youtube.com/watch?v="

    fun encode(
        format: PlaylistListFormat,
        name: String,
        videos: List<Video>,
    ): String =
        when (format) {
            PlaylistListFormat.M3U -> m3u(name, videos)
            PlaylistListFormat.CSV -> csv(videos)
        }

    /** The offered file name: the JSON export's safe base name with this format's extension. */
    fun fileName(
        format: PlaylistListFormat,
        name: String,
    ): String = PlaylistFileCodec.fileName(name).substringBeforeLast('.') + "." + format.extension

    fun m3u(
        name: String,
        videos: List<Video>,
    ): String =
        buildString {
            append("#EXTM3U\n")
            oneLine(name).takeIf { it.isNotEmpty() }?.let { append("#PLAYLIST:").append(it).append('\n') }
            videos.forEach { video ->
                val label = listOf(oneLine(video.channelName), oneLine(video.title)).filter { it.isNotEmpty() }.joinToString(" - ")
                val seconds = if (video.duration > 0) video.duration else -1
                append("#EXTINF:").append(seconds).append(',').append(label).append('\n')
                append(WATCH_URL).append(video.id).append('\n')
            }
        }

    fun csv(videos: List<Video>): String =
        buildString {
            append("position,title,channel,duration_seconds,url\r\n")
            videos.forEachIndexed { index, video ->
                listOf(
                    (index + 1).toString(),
                    video.title,
                    video.channelName,
                    video.duration.coerceAtLeast(0).toString(),
                    WATCH_URL + video.id,
                ).joinTo(this, ",") { csvField(it) }
                append("\r\n")
            }
        }

    private fun oneLine(text: String): String = text.replace(Regex("[\\r\\n]+"), " ").trim()

    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
