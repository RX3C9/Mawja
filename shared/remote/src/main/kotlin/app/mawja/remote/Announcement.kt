/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** announcements/{id} as the clients see it. Times are epoch millis. */
data class Announcement(
    val id: String,
    val title: String,
    val body: String,
    val kind: String = KIND_BANNER,
    val targetTier: String? = null,
    val locale: String? = null,
    val ctaLabel: String = "",
    val ctaUrl: String = "",
    val active: Boolean = true,
    val startAt: Long? = null,
    val endAt: Long? = null,
) {
    /** A call to action only when both label and a safe URL (https:// or mawja://) are present. */
    val hasCta: Boolean get() = ctaLabel.isNotBlank() && isSafeCta(ctaUrl)

    companion object {
        const val KIND_BANNER = "banner"
        const val KIND_DIALOG = "dialog"
        const val KIND_NEWS = "news"
        val KINDS = listOf(KIND_BANNER, KIND_DIALOG, KIND_NEWS)

        fun isSafeCta(url: String): Boolean = url.startsWith("https://") || url.startsWith("mawja://")

        /** One Firestore REST document; null when it has no id, title or body. */
        fun fromFirestore(document: JsonElement?): Announcement? {
            val id = FirestoreJson.docId(document) ?: return null
            val f = FirestoreJson.fields(document)
            val title = FirestoreJson.string(f["title"])?.trim().orEmpty()
            val body = FirestoreJson.string(f["body"])?.trim().orEmpty()
            if (title.isEmpty() || body.isEmpty()) return null
            val ctaUrl = FirestoreJson.string(f["ctaUrl"])?.trim().orEmpty()
            return Announcement(
                id = id,
                title = title,
                body = body,
                kind = FirestoreJson.string(f["kind"])?.takeIf { it in KINDS } ?: KIND_BANNER,
                targetTier = FirestoreJson.string(f["targetTier"])?.ifBlank { null },
                locale = FirestoreJson.string(f["locale"])?.ifBlank { null },
                ctaLabel = FirestoreJson.string(f["ctaLabel"])?.trim().orEmpty(),
                ctaUrl = if (isSafeCta(ctaUrl)) ctaUrl else "",
                active = FirestoreJson.bool(f["active"]) ?: false,
                startAt = FirestoreJson.millis(f["startAt"]),
                endAt = FirestoreJson.millis(f["endAt"]),
            )
        }

        /** A :runQuery response (array of {document?, readTime}). Bad entries are skipped. */
        fun listFromQuery(response: JsonElement?): List<Announcement> = FirestoreJson.queryDocuments(response).mapNotNull { fromFirestore(it) }

        fun listFromQuery(json: String): List<Announcement> = listFromQuery(runCatching { Json.parseToJsonElement(json) }.getOrNull())

        /** Cache form (plain JSON array). */
        fun encodeList(list: List<Announcement>): String =
            buildJsonArray {
                list.forEach { a ->
                    add(
                        buildJsonObject {
                            put("id", a.id)
                            put("title", a.title)
                            put("body", a.body)
                            put("kind", a.kind)
                            put("targetTier", a.targetTier)
                            put("locale", a.locale)
                            put("ctaLabel", a.ctaLabel)
                            put("ctaUrl", a.ctaUrl)
                            put("active", a.active)
                            put("startAt", a.startAt)
                            put("endAt", a.endAt)
                        },
                    )
                }
            }.toString()

        /** Reads [encodeList] output; empty for blank or corrupt input. */
        fun decodeList(cached: String?): List<Announcement> {
            if (cached.isNullOrBlank()) return emptyList()
            val arr = runCatching { Json.parseToJsonElement(cached) as? JsonArray }.getOrNull() ?: return emptyList()
            return arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null

                fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull

                fun num(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
                val id = str("id") ?: return@mapNotNull null
                Announcement(
                    id = id,
                    title = str("title").orEmpty(),
                    body = str("body").orEmpty(),
                    kind = str("kind") ?: KIND_BANNER,
                    targetTier = str("targetTier"),
                    locale = str("locale"),
                    ctaLabel = str("ctaLabel").orEmpty(),
                    ctaUrl = str("ctaUrl").orEmpty(),
                    active = (o["active"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    startAt = num("startAt"),
                    endAt = num("endAt"),
                )
            }
        }
    }
}

/** Which announcements this user should see now. */
object AnnouncementFilter {
    /**
     * Active, inside [startAt, endAt), aimed at everyone or exactly [tierId], in everyone's or the
     * user's [language] ("ar"/"en", region ignored), and not dismissed. Newest first.
     */
    fun visible(
        all: List<Announcement>,
        now: Long,
        tierId: String,
        language: String,
        dismissed: Set<String>,
    ): List<Announcement> {
        val lang = language.lowercase().substringBefore('-').substringBefore('_')
        return all
            .asSequence()
            .filter { it.active }
            .filter { it.startAt == null || it.startAt <= now }
            .filter { it.endAt == null || it.endAt > now }
            .filter { it.targetTier == null || it.targetTier.equals(tierId, ignoreCase = true) }
            .filter { it.locale == null || it.locale.equals(lang, ignoreCase = true) }
            .filter { it.id !in dismissed }
            .distinctBy { it.id }
            .sortedByDescending { it.startAt ?: 0L }
            .toList()
    }
}
