// SPDX-License-Identifier: GPL-3.0-or-later
package app.mawja.android.player.stream

import android.util.Log
import app.mawja.android.innertube.models.YouTubeClient
import app.mawja.android.innertube.models.response.PlayerResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * Last-resort stream source for when YouTube answers every client with its "Sign in to confirm
 * you're not a bot" wall (datacenter IPs, some VPNs and mobile carriers' CGNAT). Asks public
 * Invidious instances (AGPL, community-run) for the video with `local=true`, so the audio and video
 * bytes are proxied by the instance and YouTube never sees the user's address; only the video id
 * reaches the instance. Results are reshaped into the app's own [PlayerResponse] so the normal
 * player, music player and downloader paths run unchanged.
 *
 * Off switch: [enabled] (Settings → Playback → "Backup servers").
 */
object MirrorFallback {
    private const val TAG = "MirrorFallback"
    private const val DIRECTORY = "https://api.invidious.io/instances.json?sort_by=health"
    private val PINNED = listOf("yt.chocolatemoo53.com", "inv.nadeko.net", "invidious.nerdvpn.de", "invidious.tiekoetter.com")
    private const val CONNECT_MS = 5_000
    private const val READ_MS = 9_000
    private const val DIRECTORY_TTL_MS = 6 * 60 * 60 * 1000L

    @Volatile var enabled: Boolean = true

    @Volatile private var preferred: String? = null

    @Volatile private var directory: List<String> = emptyList()

    @Volatile private var directoryAt = 0L

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Whether the failures look like a wall a mirror can get around (not a removed/private video). */
    fun appliesTo(failureReasons: List<String>): Boolean =
        enabled && failureReasons.isNotEmpty() && failureReasons.any { it.contains("BOT_WALL") || it.contains("LOGIN_REQUIRED") || it.contains("exception=") }

    suspend fun extract(videoId: String): InnerTubeVideoStreamExtractor.VideoExtractionResult? =
        withContext(Dispatchers.IO) {
            for (host in candidates()) {
                val result = runCatching { fromInstance(host, videoId) }
                    .onFailure { Log.w(TAG, "$host failed for $videoId: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull()
                if (result != null) {
                    preferred = host
                    Log.i(TAG, "MIRROR_OK $videoId via $host (${result.videoFormats.size} video, ${result.audioFormats.size} audio)")
                    return@withContext result
                }
            }
            Log.w(TAG, "MIRROR_FAILED $videoId: no instance served it")
            null
        }

    private fun candidates(): List<String> {
        if (System.currentTimeMillis() - directoryAt > DIRECTORY_TTL_MS) {
            directory = runCatching { fetchDirectory() }.getOrDefault(directory)
            directoryAt = System.currentTimeMillis()
        }
        return (listOfNotNull(preferred) + PINNED + directory).distinct().take(8)
    }

    private fun fetchDirectory(): List<String> =
        json.parseToJsonElement(get(DIRECTORY)).jsonArray.mapNotNull { entry ->
            val pair = entry.jsonArray
            val host = pair[0].jsonPrimitive.content
            val info = pair[1].jsonObject
            val https = info["type"]?.jsonPrimitive?.contentOrNull == "https"
            val api = info["api"]?.jsonPrimitive?.contentOrNull == "true"
            host.takeIf { https && api }
        }

    private fun fromInstance(host: String, videoId: String): InnerTubeVideoStreamExtractor.VideoExtractionResult? {
        val root = json.parseToJsonElement(get("https://$host/api/v1/videos/$videoId?local=true")).jsonObject
        if (root["error"] != null) return null
        val lengthSec = root.int("lengthSeconds") ?: 0
        fun abs(u: String) = if (u.startsWith("/")) "https://$host$u" else u
        fun convert(f: JsonObject, muxed: Boolean): JsonObject? {
            val url = f.str("url")?.let(::abs) ?: return null
            val itag = f.str("itag")?.toIntOrNull() ?: f.int("itag") ?: return null
            val mime = f.str("type") ?: return null
            val isAudio = mime.startsWith("audio/")
            val size = f.str("size")?.split("x")
            return buildJsonObject {
                put("itag", itag)
                put("url", url)
                put("mimeType", mime)
                put("bitrate", f.str("bitrate")?.toIntOrNull() ?: f.int("bitrate") ?: 0)
                put("quality", f.str("quality") ?: if (isAudio) "tiny" else "medium")
                put("approxDurationMs", (lengthSec * 1000L).toString())
                f.str("clen")?.toLongOrNull()?.let { put("contentLength", it) }
                if (!isAudio) {
                    size?.getOrNull(0)?.toIntOrNull()?.let { put("width", it) }
                    size?.getOrNull(1)?.toIntOrNull()?.let { put("height", it) }
                    (f.int("fps"))?.let { put("fps", it) }
                    put("qualityLabel", f.str("qualityLabel") ?: f.str("resolution") ?: "")
                }
                if (isAudio || muxed) {
                    put("audioQuality", f.str("audioQuality") ?: "AUDIO_QUALITY_MEDIUM")
                    (f.str("audioSampleRate")?.toIntOrNull() ?: f.int("audioSampleRate"))?.let { put("audioSampleRate", it) }
                    f.int("audioChannels")?.let { put("audioChannels", it) }
                }
                f.range("init")?.let { put("initRange", it) }
                f.range("index")?.let { put("indexRange", it) }
            }
        }
        val adaptive = root["adaptiveFormats"]?.jsonArray.orEmpty().mapNotNull { convert(it.jsonObject, muxed = false) }
        val muxed = root["formatStreams"]?.jsonArray.orEmpty().mapNotNull { convert(it.jsonObject, muxed = true) }
        if (adaptive.isEmpty() && muxed.isEmpty()) return null
        val response =
            buildJsonObject {
                put("responseContext", buildJsonObject {})
                put("playabilityStatus", buildJsonObject { put("status", "OK") })
                put(
                    "streamingData",
                    buildJsonObject {
                        put("expiresInSeconds", 18_000)
                        put("formats", JsonArray(muxed))
                        put("adaptiveFormats", JsonArray(adaptive))
                    },
                )
                put(
                    "videoDetails",
                    buildJsonObject {
                        put("videoId", videoId)
                        put("title", root.str("title") ?: "")
                        put("author", root.str("author") ?: "")
                        put("channelId", root.str("authorId") ?: "")
                        put("lengthSeconds", lengthSec.toString())
                        put("shortDescription", root.str("description") ?: "")
                        root["viewCount"]?.jsonPrimitive?.contentOrNull?.let { put("viewCount", it) }
                    },
                )
            }
        val player = json.decodeFromJsonElement(PlayerResponse.serializer(), response)
        val formats = player.streamingData?.adaptiveFormats.orEmpty()
        val video = formats.filter { !it.isAudio && it.width != null && it.height != null }
            .ifEmpty { player.streamingData?.formats.orEmpty().filter { it.width != null && it.height != null } }
        val audio = formats.filter { it.isAudio }
        if (video.isEmpty() || audio.isEmpty()) return null
        return InnerTubeVideoStreamExtractor.VideoExtractionResult(
            videoFormats = video,
            audioFormats = audio,
            playerResponse = player,
            usedClient = YouTubeClient.ANDROID_VR_1_65_10,
            sabrInfo = null,
        )
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.range(k: String): JsonObject? {
        val parts = str(k)?.split("-") ?: return null
        if (parts.size != 2) return null
        return buildJsonObject { put("start", parts[0]); put("end", parts[1]) }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = CONNECT_MS
        c.readTimeout = READ_MS
        c.setRequestProperty("User-Agent", "Mawja/2.0 (+https://github.com/RX3C9/Mawja)")
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
