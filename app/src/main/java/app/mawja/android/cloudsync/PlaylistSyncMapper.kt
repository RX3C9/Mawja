/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.cloudsync

import app.mawja.android.sync.canonical.CanonicalPlaylist
import app.mawja.android.sync.canonical.CanonicalPlaylistItem
import app.mawja.android.sync.mapping.PlaylistMapper

/** What applying remote playlist records means for the local store. */
data class PlaylistWrites(
    /** Whole playlists to write (meta + every live entry, ranked by order key). */
    val upsert: List<CanonicalPlaylist>,
    /** Sync ids of playlists to delete here. */
    val delete: List<String>,
    /** Entry key -> order key for every entry after the writes. */
    val orderKeys: Map<String, String>,
) {
    val isEmpty: Boolean get() = upsert.isEmpty() && delete.isEmpty()
}

/**
 * Playlists sync per entry (docs/cloud-sync.md §2): `c = playlists` holds one meta doc per playlist
 * (`k` = its sync id; t title, ds description, mu, uc user-created, or origin, y YouTube id, ca
 * created at) and `c = plitems` one doc per entry (`k` = "{playlist}.{video}"; p, v, o fractional
 * order key, t, ch, ci, th, du, mu, aa added at). Each doc is its own LWW register, so two devices
 * adding to the same playlist both keep their adds, and the order is the order of the keys. Pure.
 */
object PlaylistSyncMapper {
    const val PLAYLISTS = "playlists"
    const val ENTRIES = "plitems"
    private const val SEP = '.'
    private const val UNKEYED = "~"
    const val TITLE_MAX = 200
    const val DESCRIPTION_MAX = 1000

    /** Never deleted by sync on either side. */
    val PROTECTED: Set<String> = setOf(CanonicalPlaylist.RESERVED_WATCH_LATER, PlaylistMapper.SAVED_SHORTS_ID)

    /** The playlist's sync key, or null when it cannot travel (the key must also join entry keys). */
    fun playlistKey(p: CanonicalPlaylist): String? = p.syncId.takeIf { SyncMerge.isValidKey(it) && SEP !in it && it.length <= 100 }

    fun entryKey(
        playlistKey: String,
        videoId: String,
    ): String? =
        "$playlistKey$SEP$videoId".takeIf {
            SEP !in playlistKey && SEP !in videoId && videoId.isNotEmpty() && videoId.length <= 64 && SyncMerge.isValidKey(it)
        }

    /** (playlist key, video id) of an entry key. */
    fun splitEntryKey(key: String): Pair<String, String>? {
        val at = key.lastIndexOf(SEP)
        if (at <= 0 || at == key.lastIndex) return null
        return key.substring(0, at) to key.substring(at + 1)
    }

    fun metaHash(p: CanonicalPlaylist): Int = "${p.title}|${p.description}|${p.isMusic}|${p.isUserCreated}|${p.origin}|${p.youtubeId}".hashCode()

    fun metaPayload(p: CanonicalPlaylist): Map<String, Any?> =
        buildMap<String, Any?> {
            with(SyncPayload) {
                put("t", p.title.take(TITLE_MAX))
                putText("ds", p.description, DESCRIPTION_MAX)
                put("mu", p.isMusic)
                put("uc", p.isUserCreated)
                put("or", if (p.origin == CanonicalPlaylist.ORIGIN_YOUTUBE) CanonicalPlaylist.ORIGIN_YOUTUBE else CanonicalPlaylist.ORIGIN_LOCAL)
                val youtubeId = p.youtubeId
                if (youtubeId != null && SyncMerge.isValidKey(youtubeId)) put("y", youtubeId)
                putCount("ca", p.createdAtMs)
            }
        }

    fun entryPayload(
        playlistKey: String,
        item: CanonicalPlaylistItem,
        order: String,
    ): Map<String, Any?> =
        buildMap<String, Any?> {
            with(SyncPayload) {
                put("p", playlistKey)
                put("v", item.videoId)
                put("o", order)
                putText("t", item.title, SyncLimits.NAME_MAX)
                putText("ch", item.channelName, SyncLimits.NAME_MAX)
                if (SyncMerge.isValidKey(item.channelId)) put("ci", item.channelId)
                putUrl("th", item.thumbnailUrl)
                putCount("du", item.durationSeconds)
                put("mu", item.isMusic)
                putCount("aa", item.addedAtMs)
            }
        }

    /** Display order of a playlist's entries (canonical rank, then video id for stability). */
    private fun ordered(p: CanonicalPlaylist): List<CanonicalPlaylistItem> =
        p.items.filter { !it.deleted }.sortedWith(compareBy<CanonicalPlaylistItem> { it.position }.thenBy { it.videoId })

    /** Order keys for every entry of [playlists], keeping each [prior] key whose relative order still holds. */
    fun orderKeys(
        playlists: List<CanonicalPlaylist>,
        prior: Map<String, String>,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        playlists.forEach { p ->
            val pk = playlistKey(p) ?: return@forEach
            val keys = ordered(p).take(SyncLimits.MAX_ENTRIES_PER_PLAYLIST).mapNotNull { entryKey(pk, it.videoId) }
            keys.zip(OrderKeys.assign(keys, prior)).forEach { (k, o) -> out[k] = o }
        }
        return out
    }

    /** Meta records: live playlists clocked by [stamp] (falls back to creation time), plus tombstones. */
    fun metaRecords(
        playlists: List<CanonicalPlaylist>,
        stamp: (String) -> Long,
        tombstones: Map<String, Long>,
        device: String,
    ): Map<String, SyncRecord> {
        val out = LinkedHashMap<String, SyncRecord>()
        tombstones.forEach { (k, at) ->
            if (at > 0 && k !in PROTECTED && SyncMerge.isValidKey(k)) out[k] = SyncRecord(k, at, deleted = true, device = device)
        }
        playlists.take(SyncLimits.MAX_PLAYLISTS).forEach { p ->
            val pk = playlistKey(p) ?: return@forEach
            val clock = stamp(pk).takeIf { it > 0 } ?: p.createdAtMs
            val live = SyncRecord(pk, clock.coerceAtLeast(1L), deleted = false, device = device, data = metaPayload(p))
            val tomb = out[pk]
            if (tomb == null || SyncMerge.wins(live, tomb)) out[pk] = live
        }
        return out
    }

    /** Entry records: live entries clocked by [stamp] (falls back to the add time), plus tombstones. */
    fun entryRecords(
        playlists: List<CanonicalPlaylist>,
        orderKeys: Map<String, String>,
        stamp: (String) -> Long,
        tombstones: Map<String, Long>,
        device: String,
    ): Map<String, SyncRecord> {
        val out = LinkedHashMap<String, SyncRecord>()
        tombstones.forEach { (k, at) ->
            if (at > 0 && SyncMerge.isValidKey(k) && splitEntryKey(k) != null) out[k] = SyncRecord(k, at, deleted = true, device = device)
        }
        playlists.take(SyncLimits.MAX_PLAYLISTS).forEach { p ->
            val pk = playlistKey(p) ?: return@forEach
            ordered(p).take(SyncLimits.MAX_ENTRIES_PER_PLAYLIST).forEach { item ->
                val ek = entryKey(pk, item.videoId) ?: return@forEach
                val order = orderKeys[ek] ?: return@forEach
                val clock = stamp(ek).takeIf { it > 0 } ?: item.addedAtMs
                val live = SyncRecord(ek, clock.coerceAtLeast(1L), deleted = false, device = device, data = entryPayload(pk, item, order))
                val tomb = out[ek]
                if (tomb == null || SyncMerge.wins(live, tomb)) out[ek] = live
            }
        }
        return out
    }

    /** A playlist made from a remote meta record (no entries yet). */
    fun fromMeta(
        key: String,
        data: Map<String, Any?>,
    ): CanonicalPlaylist {
        val origin = if (SyncPayload.text(data, "or") == CanonicalPlaylist.ORIGIN_YOUTUBE) CanonicalPlaylist.ORIGIN_YOUTUBE else CanonicalPlaylist.ORIGIN_LOCAL
        return CanonicalPlaylist(
            syncId = key,
            origin = origin,
            youtubeId = SyncPayload.text(data, "y").ifBlank { null }?.takeIf { origin == CanonicalPlaylist.ORIGIN_YOUTUBE },
            title = SyncPayload.text(data, "t"),
            description = SyncPayload.text(data, "ds"),
            isMusic = SyncPayload.bool(data, "mu"),
            isUserCreated = data["uc"] as? Boolean ?: true,
            isProtected = key == CanonicalPlaylist.RESERVED_WATCH_LATER,
            createdAtMs = SyncPayload.long(data, "ca"),
        )
    }

    private fun itemFrom(data: Map<String, Any?>): CanonicalPlaylistItem =
        CanonicalPlaylistItem(
            videoId = SyncPayload.text(data, "v"),
            addedAtMs = SyncPayload.long(data, "aa"),
            title = SyncPayload.text(data, "t"),
            channelName = SyncPayload.text(data, "ch"),
            channelId = SyncPayload.text(data, "ci"),
            thumbnailUrl = SyncPayload.text(data, "th"),
            durationSeconds = SyncPayload.long(data, "du"),
            isMusic = SyncPayload.bool(data, "mu"),
        )

    /**
     * Folds the merge winners into the local playlists. A winning meta tombstone deletes the
     * playlist (never a protected one); a winning live meta creates or renames it; entry winners add
     * or remove single entries. Entries of a playlist this device neither has nor just received are
     * dropped (their playlist was deleted here, and that deletion won).
     */
    fun applyRemote(
        local: List<CanonicalPlaylist>,
        orderKeys: Map<String, String>,
        metaApply: List<SyncRecord>,
        entryApply: List<SyncRecord>,
    ): PlaylistWrites {
        val localByKey = local.mapNotNull { p -> playlistKey(p)?.let { it to p } }.toMap()
        val deleted = metaApply.filter { it.deleted && it.key !in PROTECTED }.mapTo(LinkedHashSet()) { it.key }
        val metaLive = metaApply.filter { !it.deleted }.associateBy { it.key }
        val entriesByPlaylist = LinkedHashMap<String, MutableList<Pair<String, SyncRecord>>>()
        entryApply.forEach { r ->
            val (pk, video) = splitEntryKey(r.key) ?: return@forEach
            entriesByPlaylist.getOrPut(pk) { ArrayList() } += video to r
        }

        val keys = HashMap(orderKeys)
        val upsert = ArrayList<CanonicalPlaylist>()
        val affected = (metaLive.keys + entriesByPlaylist.keys).filter { it !in deleted }.distinct()
        for (pk in affected) {
            val existing = localByKey[pk]
            val remoteMeta = metaLive[pk]
            val base = existing ?: remoteMeta?.let { fromMeta(pk, it.data) } ?: continue
            val meta =
                if (remoteMeta != null && existing != null) {
                    val m = fromMeta(pk, remoteMeta.data)
                    existing.copy(
                        title = m.title,
                        description = m.description,
                        isMusic = m.isMusic,
                        isUserCreated = m.isUserCreated,
                        createdAtMs = existing.createdAtMs.takeIf { it > 0 } ?: m.createdAtMs,
                    )
                } else {
                    base
                }
            // video id -> (order key, item)
            val members = LinkedHashMap<String, Pair<String, CanonicalPlaylistItem>>()
            existing?.let { p ->
                ordered(p).forEach { item ->
                    val ek = entryKey(pk, item.videoId)
                    // Entries past the per-playlist cap have no key: keep them after the rest.
                    members[item.videoId] = (ek?.let { keys[it] } ?: UNKEYED) to item
                }
            }
            entriesByPlaylist[pk].orEmpty().forEach { (video, r) ->
                val ek = r.key
                if (r.deleted) {
                    members.remove(video)
                    keys.remove(ek)
                } else {
                    val order = (r.data["o"] as? String)?.takeIf(OrderKeys::isValid) ?: return@forEach
                    val previous = members[video]?.second
                    val incoming = itemFrom(r.data).copy(videoId = video)
                    members[video] = order to (previous?.let { incoming.withDetailsOf(it) } ?: incoming)
                    keys[ek] = order
                }
            }
            val items =
                members.entries
                    .sortedWith(compareBy<Map.Entry<String, Pair<String, CanonicalPlaylistItem>>> { it.value.first }.thenBy { it.key })
                    .mapIndexed { index, e -> e.value.second.copy(position = index.toLong(), deleted = false) }
            upsert += meta.copy(deleted = false, items = items)
        }
        val delete = deleted.filter { it in localByKey }
        if (deleted.isNotEmpty()) {
            val prefixes = deleted.map { "$it$SEP" }
            keys.keys.removeAll { k -> prefixes.any { k.startsWith(it) } }
        }
        return PlaylistWrites(upsert, delete, keys)
    }

    /** Remote entry details, filling blanks from what this device already knew. */
    private fun CanonicalPlaylistItem.withDetailsOf(local: CanonicalPlaylistItem): CanonicalPlaylistItem =
        copy(
            title = title.ifBlank { local.title },
            channelName = channelName.ifBlank { local.channelName },
            channelId = channelId.ifBlank { local.channelId },
            thumbnailUrl = thumbnailUrl.ifBlank { local.thumbnailUrl },
            durationSeconds = durationSeconds.takeIf { it > 0 } ?: local.durationSeconds,
            addedAtMs = addedAtMs.takeIf { it > 0 } ?: local.addedAtMs,
        )
}
