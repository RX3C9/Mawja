/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.youtubeaccount

import app.mawja.android.data.local.TakeoutLike
import app.mawja.android.data.local.TakeoutPlaylistInfo
import app.mawja.android.data.local.YouTubeTakeoutSubscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/** A playlist read from the signed-in YouTube account, with its videos in order. */
internal data class AccountPlaylist(
    val info: TakeoutPlaylistInfo,
    val videoIds: List<String>,
)

/** Everything "Connect YouTube account" brings over in one read-only pass. */
internal data class YouTubeAccountData(
    val subscriptions: List<YouTubeTakeoutSubscription>,
    val playlists: List<AccountPlaylist>,
    val likes: List<TakeoutLike>,
) {
    val isEmpty get() = subscriptions.isEmpty() && playlists.isEmpty() && likes.isEmpty()
}

internal data class ApiPage<T>(
    val items: List<T>,
    val nextPageToken: String?,
)

/** Pure parsers for the YouTube Data API v3 list responses, kept free of Android for JVM tests. */
internal object YouTubeAccountJson {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonElement?.str(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun page(body: String): Pair<JsonArray, String?> {
        val root = json.parseToJsonElement(body).jsonObject
        val items = (root["items"] as? JsonArray) ?: JsonArray(emptyList())
        return items to root["nextPageToken"].str()?.takeIf { it.isNotBlank() }
    }

    fun subscriptions(body: String): ApiPage<YouTubeTakeoutSubscription> {
        val (items, next) = page(body)
        val rows =
            items.mapNotNull { item ->
                val snippet = (item as? JsonObject)?.obj("snippet") ?: return@mapNotNull null
                val id = snippet.obj("resourceId")?.get("channelId").str() ?: return@mapNotNull null
                YouTubeTakeoutSubscription(channelId = id, channelName = snippet["title"].str().orEmpty())
            }
        return ApiPage(rows, next)
    }

    fun playlists(body: String): ApiPage<TakeoutPlaylistInfo> {
        val (items, next) = page(body)
        val rows =
            items.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val id = o["id"].str() ?: return@mapNotNull null
                val snippet = o.obj("snippet")
                TakeoutPlaylistInfo(
                    id = id,
                    title = snippet?.get("title").str().orEmpty().ifBlank { id },
                    createdAt = snippet?.get("publishedAt").str()?.let(::epochMillis),
                )
            }
        return ApiPage(rows, next)
    }

    fun playlistVideoIds(body: String): ApiPage<String> {
        val (items, next) = page(body)
        val ids =
            items.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                o.obj("contentDetails")?.get("videoId").str()
                    ?: o.obj("snippet")?.obj("resourceId")?.get("videoId").str()
            }
        return ApiPage(ids, next)
    }

    /** Liked videos, newest first as YouTube lists them; [now] stamps them in that order. */
    fun likes(
        body: String,
        now: Long,
        offset: Int = 0,
    ): ApiPage<TakeoutLike> {
        val (items, next) = page(body)
        val rows =
            items.mapIndexedNotNull { index, item ->
                val o = item as? JsonObject ?: return@mapIndexedNotNull null
                val id = o["id"].str() ?: return@mapIndexedNotNull null
                val snippet = o.obj("snippet")
                TakeoutLike(
                    videoId = id,
                    title = snippet?.get("title").str().orEmpty(),
                    channelName = snippet?.get("channelTitle").str().orEmpty(),
                    channelId = snippet?.get("channelId").str().orEmpty(),
                    likedAt = now - (offset + index) * 1000L,
                    isMusic = snippet?.get("categoryId").str() == MUSIC_CATEGORY,
                )
            }
        return ApiPage(rows, next)
    }

    /** The reason Google gave for a refused call ("quotaExceeded", "accessNotConfigured", …). */
    fun errorReason(body: String): String? =
        runCatching {
            val error = json.parseToJsonElement(body).jsonObject.obj("error") ?: return null
            (error["errors"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("reason").str()
                ?: error["status"].str()
        }.getOrNull()

    private fun epochMillis(text: String): Long? = runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()

    /** De-duplicates by id while keeping the first (newest) occurrence. */
    fun <T> distinctBy(
        rows: List<T>,
        key: (T) -> String,
    ): List<T> = rows.distinctBy(key)

    const val MUSIC_CATEGORY = "10"
}

/** Why the account read stopped; the UI turns each into a clear message. */
internal class YouTubeAccountException(
    val reason: String,
) : Exception(reason)

/**
 * Reads the account with the user's own read-only OAuth token (scope youtube.readonly). Nothing is
 * written to YouTube. Watch history and Watch Later are not offered by the API, so Takeout stays
 * the way to bring those.
 */
internal class YouTubeAccountClient(
    private val accessToken: String,
    private val http: OkHttpClient = defaultClient,
    private val base: String = "https://www.googleapis.com/youtube/v3/",
) {
    suspend fun readAll(onProgress: (step: Step, count: Int) -> Unit = { _, _ -> }): YouTubeAccountData =
        withContext(Dispatchers.IO) {
            val subs = mutableListOf<YouTubeTakeoutSubscription>()
            paged("subscriptions", mapOf("mine" to "true", "part" to "snippet", "order" to "alphabetical"), MAX_PAGES) { body ->
                YouTubeAccountJson.subscriptions(body).also { subs += it.items; onProgress(Step.SUBSCRIPTIONS, subs.size) }.nextPageToken
            }
            val infos = mutableListOf<TakeoutPlaylistInfo>()
            paged("playlists", mapOf("mine" to "true", "part" to "snippet"), MAX_PAGES) { body ->
                YouTubeAccountJson.playlists(body).also { infos += it.items }.nextPageToken
            }
            val playlists =
                infos.take(MAX_PLAYLISTS).mapIndexed { i, info ->
                    val ids = mutableListOf<String>()
                    paged("playlistItems", mapOf("playlistId" to info.id, "part" to "contentDetails"), MAX_ITEM_PAGES) { body ->
                        YouTubeAccountJson.playlistVideoIds(body).also { ids += it.items }.nextPageToken
                    }
                    onProgress(Step.PLAYLISTS, i + 1)
                    AccountPlaylist(info, ids.distinct())
                }.filter { it.videoIds.isNotEmpty() }
            val likes = mutableListOf<TakeoutLike>()
            val now = System.currentTimeMillis()
            runCatching {
                paged("videos", mapOf("myRating" to "like", "part" to "snippet"), MAX_PAGES) { body ->
                    YouTubeAccountJson.likes(body, now, likes.size).also { likes += it.items; onProgress(Step.LIKES, likes.size) }.nextPageToken
                }
            }.onFailure { if (it is YouTubeAccountException && it.reason != "forbidden") throw it }
            YouTubeAccountData(
                subscriptions = YouTubeAccountJson.distinctBy(subs) { it.channelId },
                playlists = playlists,
                likes = YouTubeAccountJson.distinctBy(likes) { it.videoId },
            )
        }

    private fun paged(
        path: String,
        params: Map<String, String>,
        maxPages: Int,
        handle: (String) -> String?,
    ) {
        var token: String? = null
        var pages = 0
        do {
            val url =
                (base + path).toHttpUrl().newBuilder().apply {
                    params.forEach { (k, v) -> addQueryParameter(k, v) }
                    addQueryParameter("maxResults", "50")
                    token?.let { addQueryParameter("pageToken", it) }
                }.build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").build()
            val body =
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) {
                        val reason = YouTubeAccountJson.errorReason(text)
                        throw YouTubeAccountException(
                            when {
                                response.code == 401 -> "unauthorized"
                                reason == "quotaExceeded" || reason == "dailyLimitExceeded" -> "quota"
                                reason == "accessNotConfigured" || reason == "SERVICE_DISABLED" -> "api_disabled"
                                reason == "channelNotFound" || reason == "youtubeSignupRequired" -> "no_channel"
                                response.code == 403 -> "forbidden"
                                else -> "http_${response.code}"
                            },
                        )
                    }
                    text
                }
            token = handle(body)
            pages++
        } while (token != null && pages < maxPages)
    }

    enum class Step { SUBSCRIPTIONS, PLAYLISTS, LIKES }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
        private const val MAX_PAGES = 40 // 2,000 rows
        private const val MAX_PLAYLISTS = 200
        private const val MAX_ITEM_PAGES = 20 // 1,000 videos a playlist
        private val defaultClient by lazy {
            OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        }
    }
}
