/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.cloudsync

import app.mawja.android.data.local.LikedVideoInfo
import app.mawja.android.sync.canonical.CanonicalSetting
import app.mawja.android.sync.canonical.CanonicalWatchHistory
import app.mawja.android.sync.mapping.SettingsMapper
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Shared payload helpers: optional strings are left out when blank or over their cap. */
internal object SyncPayload {
    fun MutableMap<String, Any?>.putText(
        key: String,
        value: String?,
        max: Int,
    ) {
        val v = value?.trim().orEmpty()
        if (v.isNotEmpty()) put(key, v.take(max))
    }

    fun MutableMap<String, Any?>.putUrl(
        key: String,
        value: String?,
        max: Int = SyncLimits.AVATAR_MAX,
    ) {
        val v = value?.trim().orEmpty()
        if (v.isNotEmpty() && v.length <= max) put(key, v)
    }

    fun MutableMap<String, Any?>.putCount(
        key: String,
        value: Long,
    ) {
        if (value > 0) put(key, value)
    }

    fun text(
        data: Map<String, Any?>,
        key: String,
    ): String = (data[key] as? String).orEmpty()

    fun long(
        data: Map<String, Any?>,
        key: String,
    ): Long = (data[key] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L

    fun bool(
        data: Map<String, Any?>,
        key: String,
    ): Boolean = data[key] as? Boolean ?: false
}

// ---- liked videos (c = liked, k = video id) ------------------------------------------------------

/** What applying remote like records means for the local store. */
data class LikeChanges(
    /** Likes to add, each carrying the remote like time (placed by date). */
    val like: List<LikedVideoInfo>,
    /** Video ids liked here that another device unliked later. */
    val unlike: List<String>,
)

/** Liked videos <-> sync records. A like's clock is its like time; an unlike is a tombstone. Pure. */
object LikeSyncMapper {
    const val COLLECTION = "liked"

    fun toRecords(
        liked: List<LikedVideoInfo>,
        tombstones: Map<String, Long>,
        device: String,
    ): Map<String, SyncRecord> {
        val out = LinkedHashMap<String, SyncRecord>()
        tombstones.forEach { (id, at) ->
            if (SyncMerge.isValidKey(id) && at > 0) out[id] = SyncRecord(id, at, deleted = true, device = device)
        }
        liked.take(SyncLimits.MAX_LIKED).forEach { like ->
            if (!SyncMerge.isValidKey(like.videoId)) return@forEach
            val live = SyncRecord(like.videoId, like.likedAt.coerceAtLeast(1L), deleted = false, device = device, data = payload(like))
            val tomb = out[like.videoId]
            if (tomb == null || SyncMerge.wins(live, tomb)) out[like.videoId] = live
        }
        return out
    }

    fun payload(like: LikedVideoInfo): Map<String, Any?> =
        buildMap<String, Any?> {
            with(SyncPayload) {
                put("t", like.title.take(SyncLimits.NAME_MAX))
                putText("ch", like.channelName, SyncLimits.NAME_MAX)
                putUrl("th", like.thumbnail)
                put("mu", like.isMusic)
                val channel = like.channelId
                if (channel != null && SyncMerge.isValidKey(channel)) put("ci", channel)
                putCount("du", like.durationSeconds.toLong())
            }
        }

    fun changes(
        apply: List<SyncRecord>,
        likedIds: Set<String>,
    ): LikeChanges {
        val like = ArrayList<LikedVideoInfo>()
        val unlike = ArrayList<String>()
        apply.forEach { r ->
            when {
                r.deleted && r.key in likedIds -> unlike += r.key
                r.deleted -> Unit
                r.key !in likedIds ->
                    like +=
                        LikedVideoInfo(
                            videoId = r.key,
                            title = SyncPayload.text(r.data, "t"),
                            thumbnail = SyncPayload.text(r.data, "th"),
                            channelName = SyncPayload.text(r.data, "ch"),
                            likedAt = r.modifiedAt,
                            isMusic = SyncPayload.bool(r.data, "mu"),
                            channelId = SyncPayload.text(r.data, "ci").ifBlank { null },
                            durationSeconds = SyncPayload.long(r.data, "du").toInt(),
                        )
            }
        }
        return LikeChanges(like, unlike)
    }
}

// ---- watch history (c = history, k = video id) ---------------------------------------------------

/**
 * Watch history <-> sync records. Only the newest [SyncLimits.MAX_HISTORY] entries are offered
 * (plus any entry the server just sent), the clock is the watch time, and a deletion is only
 * announced for entries the server already holds. Pure.
 */
object HistorySyncMapper {
    const val COLLECTION = "history"

    fun toRecords(
        history: List<CanonicalWatchHistory>,
        tombstones: Map<String, Long>,
        /** Keys the server knows (agreed base): the only ones a deletion is worth sending for. */
        serverKeys: Set<String>,
        /** Keys in this pull: offered even when older than the cap, so the merge compares them. */
        remoteKeys: Set<String>,
        device: String,
        cap: Int = SyncLimits.MAX_HISTORY,
    ): Map<String, SyncRecord> {
        val out = LinkedHashMap<String, SyncRecord>()
        tombstones.forEach { (id, at) ->
            if (at > 0 && id in serverKeys && SyncMerge.isValidKey(id)) out[id] = SyncRecord(id, at, deleted = true, device = device)
        }
        val live = history.filter { !it.deleted && SyncMerge.isValidKey(it.videoId) }.sortedByDescending { it.watchedAtMs }
        val newest = live.take(cap).mapTo(HashSet()) { it.videoId }
        live.forEach { h ->
            if (h.videoId !in newest && h.videoId !in remoteKeys) return@forEach
            val record = SyncRecord(h.videoId, h.watchedAtMs.coerceAtLeast(1L), deleted = false, device = device, data = payload(h))
            val tomb = out[h.videoId]
            if (tomb == null || SyncMerge.wins(record, tomb)) out[h.videoId] = record
        }
        return out
    }

    fun payload(h: CanonicalWatchHistory): Map<String, Any?> =
        buildMap<String, Any?> {
            with(SyncPayload) {
                putText("t", h.title, SyncLimits.NAME_MAX)
                putText("ch", h.channelName, SyncLimits.NAME_MAX)
                if (SyncMerge.isValidKey(h.channelId)) put("ci", h.channelId)
                putUrl("th", h.thumbnailUrl)
                put("pr", h.progress.coerceIn(0.0, 1.0))
                putCount("du", h.durationSeconds)
                put("mu", h.isMusic)
                put("sh", h.isShort)
            }
        }

    /** Remote winners as rows to write: live ones upserted (watch time = clock), tombstones deleted. */
    fun toCanonical(apply: List<SyncRecord>): List<CanonicalWatchHistory> =
        apply.map { r ->
            if (r.deleted) {
                CanonicalWatchHistory(videoId = r.key, deleted = true)
            } else {
                CanonicalWatchHistory(
                    videoId = r.key,
                    title = SyncPayload.text(r.data, "t"),
                    channelName = SyncPayload.text(r.data, "ch"),
                    channelId = SyncPayload.text(r.data, "ci"),
                    thumbnailUrl = SyncPayload.text(r.data, "th"),
                    watchedAtMs = r.modifiedAt,
                    progress = ((r.data["pr"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0),
                    durationSeconds = SyncPayload.long(r.data, "du"),
                    isMusic = SyncPayload.bool(r.data, "mu"),
                    isShort = SyncPayload.bool(r.data, "sh"),
                )
            }
        }
}

// ---- settings (c = settings, k = canonical setting key) ------------------------------------------

/**
 * The whitelisted settings subset <-> sync records: `d = {val}` with a bool, number or string.
 * Only keys of [SettingsMapper.WHITELIST] that are applied on other devices travel (never the UI
 * language, paths or tokens). The clock is when the value last changed here. Pure.
 */
object SettingsSyncMapper {
    const val COLLECTION = "settings"
    const val VALUE_MAX = 4_000

    /** Must match `syncSettingKeys()` in backend/firestore.rules (SettingsSyncMapperTest checks). */
    val KEYS: Set<String> = SettingsMapper.WHITELIST.filter { it.applied }.mapTo(LinkedHashSet()) { it.canonical }

    fun hash(setting: CanonicalSetting): Int = setting.value.toString().hashCode()

    fun toRecords(
        settings: List<CanonicalSetting>,
        stamps: Map<String, Long>,
        device: String,
    ): Map<String, SyncRecord> {
        val out = LinkedHashMap<String, SyncRecord>()
        settings.forEach { s ->
            if (s.key !in KEYS || !SyncMerge.isValidKey(s.key)) return@forEach
            val value = valueOf(s) ?: return@forEach
            out[s.key] = SyncRecord(s.key, (stamps[s.key] ?: 0L).coerceAtLeast(1L), deleted = false, device = device, data = mapOf("val" to value))
        }
        return out
    }

    /** The stored form of a setting: Boolean, Long, Double or String (null when unusable). */
    fun valueOf(setting: CanonicalSetting): Any? {
        val p = setting.value as? JsonPrimitive ?: return null
        return when {
            p.isString -> p.content.takeIf { it.length <= VALUE_MAX }
            p.booleanOrNull != null -> p.booleanOrNull
            p.longOrNull != null -> p.longOrNull
            else -> p.doubleOrNull
        }
    }

    /** Remote winners as settings to apply (tombstones and unknown keys ignored). */
    fun changes(apply: List<SyncRecord>): List<CanonicalSetting> =
        apply.mapNotNull { r ->
            if (r.deleted || r.key !in KEYS) return@mapNotNull null
            val value =
                when (val v = r.data["val"]) {
                    is Boolean -> JsonPrimitive(v)
                    is Long -> JsonPrimitive(v)
                    is Int -> JsonPrimitive(v)
                    is Double -> JsonPrimitive(v)
                    is String -> JsonPrimitive(v)
                    else -> return@mapNotNull null
                }
            CanonicalSetting(r.key, value)
        }
}
