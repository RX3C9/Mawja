package app.mawja.android.data.transcript

import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TranscriptRepository"
private const val CACHE_SIZE = 4

/**
 * Reads a caption track as a transcript.
 *
 * The track URL comes from what the player already resolved for subtitles, so opening a transcript
 * costs one request for a few KB of text and nothing at all the second time.
 */
@Singleton
class TranscriptRepository
    @Inject
    constructor(
        private val httpClient: OkHttpClient,
    ) {
        private val cache = LruCache<String, List<TranscriptCue>>(CACHE_SIZE)

        private val captionCache = LruCache<String, List<TranscriptCue>>(CACHE_SIZE)

        /** The track's timed lines as captions (not joined into transcript sentences), for dual subtitles. */
        suspend fun captionCues(trackUrl: String): List<TranscriptCue> =
            withContext(Dispatchers.IO) {
                if (trackUrl.isBlank()) return@withContext emptyList()
                captionCache.get(trackUrl)?.let { return@withContext it }
                val body = fetch(trackUrl) ?: return@withContext emptyList()
                VideoTranscript.parseCaptions(body).also { cues ->
                    if (cues.isNotEmpty()) captionCache.put(trackUrl, cues)
                }
            }

        private fun fetch(trackUrl: String): String? =
            try {
                httpClient.newCall(Request.Builder().url(transcriptFormatUrl(trackUrl)).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Caption track returned HTTP ${response.code}")
                        null
                    } else {
                        response.body?.string().orEmpty()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Caption track fetch failed: ${e.message}")
                null
            }

        suspend fun cues(trackUrl: String): List<TranscriptCue> =
            withContext(Dispatchers.IO) {
                if (trackUrl.isBlank()) return@withContext emptyList()
                cache.get(trackUrl)?.let { return@withContext it }
                val requestUrl = transcriptFormatUrl(trackUrl)
                val body =
                    try {
                        httpClient.newCall(Request.Builder().url(requestUrl).build()).execute().use { response ->
                            if (!response.isSuccessful) {
                                Log.w(TAG, "Caption track returned HTTP ${response.code}")
                                return@withContext emptyList()
                            }
                            response.body?.string().orEmpty()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Caption track fetch failed: ${e.message}")
                        return@withContext emptyList()
                    }
                VideoTranscript.parse(body).also { cues ->
                    if (cues.isNotEmpty()) cache.put(trackUrl, cues)
                }
            }
    }

/**
 * The same caption track asked for as json3.
 *
 * The `fmt` already on the URL is replaced rather than appended: YouTube honours the first one it
 * sees, so a second parameter would hand back the old format under the new one's name.
 */
internal fun transcriptFormatUrl(trackUrl: String): String {
    val separator = trackUrl.indexOf('?')
    if (separator < 0) return "$trackUrl?fmt=json3"
    val path = trackUrl.substring(0, separator)
    val query =
        trackUrl
            .substring(separator + 1)
            .split('&')
            .filter { it.isNotEmpty() && !it.startsWith("fmt=") }
    return (listOf(path) + listOf((query + "fmt=json3").joinToString("&"))).joinToString("?")
}

/** Reaches [TranscriptRepository] from the subtitle overlay composable, which Hilt does not construct. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TranscriptEntryPoint {
    fun transcriptRepository(): TranscriptRepository
}
