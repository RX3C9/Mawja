/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Token-free Firestore REST reads of the world-readable config/{doc} and announcements collections
 * (rules: allow read: if true). Blocking: call from a background dispatcher. Returns null on any
 * network/HTTP/parse failure so callers keep their cache; a missing config doc is [RemoteSettings.DEFAULT].
 */
class RemoteFetcher(
    private val projectId: String,
    private val apiKey: String = "",
    private val client: OkHttpClient = defaultClient(),
) {
    private val base = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"

    val isConfigured: Boolean get() = projectId.isNotBlank()

    private fun withKey(url: String) = if (apiKey.isBlank()) url else "$url?key=" + URLEncoder.encode(apiKey, "UTF-8")

    /** config/{doc} ("app" or "downloader"). */
    fun fetchSettings(doc: String): RemoteSettings? {
        if (!isConfigured) return null
        return try {
            val request = Request.Builder().url(withKey("$base/config/$doc")).get().build()
            client.newCall(request).execute().use { r ->
                when {
                    r.code == 404 -> RemoteSettings.DEFAULT
                    r.isSuccessful -> RemoteSettings.fromFirestore(r.body.string())
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** announcements where active == true (single-field equality: no composite index needed). */
    fun fetchAnnouncements(limit: Int = 50): List<Announcement>? {
        if (!isConfigured) return null
        val body =
            """{"structuredQuery":{"from":[{"collectionId":"announcements"}],""" +
                """"where":{"fieldFilter":{"field":{"fieldPath":"active"},"op":"EQUAL","value":{"booleanValue":true}}},""" +
                """"limit":${limit.coerceIn(1, 200)}}}"""
        return try {
            val request =
                Request
                    .Builder()
                    .url(withKey("$base:runQuery"))
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            client.newCall(request).execute().use { r ->
                if (r.isSuccessful) Announcement.listFromQuery(r.body.string()) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .build()
    }
}
