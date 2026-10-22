/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.cloudsync

import app.mawja.android.sync.canonical.CanonicalPlaylist
import app.mawja.android.sync.canonical.CanonicalPlaylistItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaylistSyncMapperTest {
    private val pl = "0123456789abcdef0123456789abcdef"

    private fun item(
        id: String,
        position: Long,
    ) = CanonicalPlaylistItem(videoId = id, position = position, title = "T $id", channelName = "Ch", addedAtMs = 1_000L + position)

    private fun playlist(
        vararg ids: String,
        key: String = pl,
        title: String = "Mix",
    ) = CanonicalPlaylist(
        syncId = key,
        title = title,
        createdAtMs = 500L,
        items = ids.mapIndexed { i, id -> item(id, i.toLong()) },
    )

    @Test
    fun keysJoinAndSplit() {
        assertThat(PlaylistSyncMapper.entryKey(pl, "dQw4w9WgXcQ")).isEqualTo("$pl.dQw4w9WgXcQ")
        assertThat(PlaylistSyncMapper.splitEntryKey("reserved:watch-later.abc_-1")).isEqualTo("reserved:watch-later" to "abc_-1")
        assertThat(PlaylistSyncMapper.entryKey("bad.key", "v")).isNull()
        assertThat(PlaylistSyncMapper.entryKey(pl, "")).isNull()
        assertThat(PlaylistSyncMapper.splitEntryKey("noseparator")).isNull()
        assertThat(PlaylistSyncMapper.playlistKey(playlist(key = "has space"))).isNull()
        assertThat(PlaylistSyncMapper.playlistKey(playlist(key = CanonicalPlaylist.RESERVED_WATCH_LATER))).isEqualTo("reserved:watch-later")
    }

    @Test
    fun recordsCarryMetaAndOrderedEntries() {
        val p = playlist("a", "b", "c")
        val orders = PlaylistSyncMapper.orderKeys(listOf(p), emptyMap())
        assertThat(orders.keys).containsExactly("$pl.a", "$pl.b", "$pl.c").inOrder()
        assertThat(orders.values.toList()).isInStrictOrder()

        val meta = PlaylistSyncMapper.metaRecords(listOf(p), { 0L }, emptyMap(), "dev")
        assertThat(meta.getValue(pl).modifiedAt).isEqualTo(500L) // falls back to creation time
        assertThat(meta.getValue(pl).data).containsEntry("t", "Mix")
        assertThat(meta.getValue(pl).data).containsEntry("or", "local")

        val entries = PlaylistSyncMapper.entryRecords(listOf(p), orders, { if (it == "$pl.b") 9_000L else 0L }, emptyMap(), "dev")
        assertThat(entries.getValue("$pl.a").modifiedAt).isEqualTo(1_000L) // add time
        assertThat(entries.getValue("$pl.b").modifiedAt).isEqualTo(9_000L) // ledger stamp
        assertThat(entries.getValue("$pl.b").data).containsEntry("p", pl)
        assertThat(entries.getValue("$pl.b").data).containsEntry("v", "b")
        assertThat(entries.getValue("$pl.b").data).containsEntry("o", orders.getValue("$pl.b"))
    }

    @Test
    fun tombstonesNeverTouchProtectedPlaylists() {
        val meta =
            PlaylistSyncMapper.metaRecords(
                emptyList(),
                { 0L },
                mapOf(CanonicalPlaylist.RESERVED_WATCH_LATER to 5L, "saved_shorts" to 5L, pl to 7L),
                "dev",
            )
        assertThat(meta.keys).containsExactly(pl)
        assertThat(meta.getValue(pl).deleted).isTrue()

        val writes =
            PlaylistSyncMapper.applyRemote(
                local = listOf(playlist("a", key = CanonicalPlaylist.RESERVED_WATCH_LATER)),
                orderKeys = emptyMap(),
                metaApply = listOf(SyncRecord(CanonicalPlaylist.RESERVED_WATCH_LATER, 10L, deleted = true, device = "x")),
                entryApply = emptyList(),
            )
        assertThat(writes.delete).isEmpty()
    }

    @Test
    fun concurrentAddsOnTwoDevicesBothSurviveInKeyOrder() {
        // Device A and B both start from [a, b] with the same keys.
        val base = playlist("a", "b")
        val keys = PlaylistSyncMapper.orderKeys(listOf(base), emptyMap())
        val ka = keys.getValue("$pl.a")
        val kb = keys.getValue("$pl.b")
        // A adds "x" between a and b locally; B added "y" at the end and pushed it.
        val local = playlist("a", "x", "b")
        val localKeys = PlaylistSyncMapper.orderKeys(listOf(local), keys)
        assertThat(localKeys.getValue("$pl.a")).isEqualTo(ka)
        assertThat(localKeys.getValue("$pl.b")).isEqualTo(kb)
        val yKey = OrderKeys.between(kb, null)
        val remoteY =
            SyncRecord(
                "$pl.y",
                2_000L,
                deleted = false,
                device = "B",
                data = mapOf("p" to pl, "v" to "y", "o" to yKey, "t" to "Song Y", "du" to 30L),
            )
        val writes = PlaylistSyncMapper.applyRemote(listOf(local), localKeys, emptyList(), listOf(remoteY))
        val written = writes.upsert.single()
        assertThat(written.items.map { it.videoId }).containsExactly("a", "x", "b", "y").inOrder()
        assertThat(written.items.map { it.position }).containsExactly(0L, 1L, 2L, 3L).inOrder()
        assertThat(written.items.last().title).isEqualTo("Song Y")
        assertThat(written.items.last().durationSeconds).isEqualTo(30L)
        assertThat(writes.orderKeys).containsEntry("$pl.y", yKey)
        // The settled order keeps every key, so the next run sees no change.
        assertThat(PlaylistSyncMapper.orderKeys(writes.upsert, writes.orderKeys)).isEqualTo(writes.orderKeys)
    }

    @Test
    fun remoteRemovalAndMoveApply() {
        val local = playlist("a", "b", "c")
        val keys = PlaylistSyncMapper.orderKeys(listOf(local), emptyMap())
        val moved = OrderKeys.between(null, keys.getValue("$pl.a"))
        val writes =
            PlaylistSyncMapper.applyRemote(
                listOf(local),
                keys,
                emptyList(),
                listOf(
                    SyncRecord("$pl.b", 3_000L, deleted = true, device = "B"),
                    SyncRecord("$pl.c", 3_000L, deleted = false, device = "B", data = mapOf("p" to pl, "v" to "c", "o" to moved)),
                ),
            )
        assertThat(writes.upsert.single().items.map { it.videoId }).containsExactly("c", "a").inOrder()
        assertThat(writes.upsert.single().items.first().title).isEqualTo("T c") // details kept from local
        assertThat(writes.orderKeys).doesNotContainKey("$pl.b")
    }

    @Test
    fun remoteMetaCreatesRenamesAndDeletes() {
        val other = "fedcba9876543210fedcba9876543210"
        val local = listOf(playlist("a"), playlist("z", key = other, title = "Old"))
        val keys = PlaylistSyncMapper.orderKeys(local, emptyMap())
        val newKey = "11111111111111111111111111111111"
        val writes =
            PlaylistSyncMapper.applyRemote(
                local,
                keys,
                metaApply =
                    listOf(
                        SyncRecord(pl, 5_000L, deleted = true, device = "B"),
                        SyncRecord(other, 5_000L, deleted = false, device = "B", data = mapOf("t" to "Renamed", "mu" to true)),
                        SyncRecord(newKey, 5_000L, deleted = false, device = "B", data = mapOf("t" to "New", "uc" to true)),
                    ),
                entryApply =
                    listOf(
                        SyncRecord("$newKey.n1", 5_000L, deleted = false, device = "B", data = mapOf("p" to newKey, "v" to "n1", "o" to "V")),
                        // An entry of the playlist deleted here is dropped.
                        SyncRecord("$pl.q", 5_000L, deleted = false, device = "B", data = mapOf("p" to pl, "v" to "q", "o" to "V")),
                        // An entry of a playlist nobody knows is dropped.
                        SyncRecord("unknown.q", 5_000L, deleted = false, device = "B", data = mapOf("p" to "unknown", "v" to "q", "o" to "V")),
                    ),
            )
        assertThat(writes.delete).containsExactly(pl)
        val byKey = writes.upsert.associateBy { it.syncId }
        assertThat(byKey.keys).containsExactly(other, newKey)
        assertThat(byKey.getValue(other).title).isEqualTo("Renamed")
        assertThat(byKey.getValue(other).isMusic).isTrue()
        assertThat(byKey.getValue(other).items.map { it.videoId }).containsExactly("z")
        assertThat(byKey.getValue(newKey).title).isEqualTo("New")
        assertThat(byKey.getValue(newKey).items.map { it.videoId }).containsExactly("n1")
        assertThat(writes.orderKeys.keys.none { it.startsWith("$pl.") }).isTrue()
    }

    @Test
    fun metaPayloadRoundTrips() {
        val p =
            CanonicalPlaylist(
                syncId = "PLabc",
                origin = CanonicalPlaylist.ORIGIN_YOUTUBE,
                youtubeId = "PLabc",
                title = "x".repeat(250),
                description = "d",
                isMusic = true,
                isUserCreated = false,
                createdAtMs = 42L,
            )
        val data = PlaylistSyncMapper.metaPayload(p)
        assertThat((data["t"] as String).length).isEqualTo(PlaylistSyncMapper.TITLE_MAX)
        val back = PlaylistSyncMapper.fromMeta("PLabc", data)
        assertThat(back.origin).isEqualTo(CanonicalPlaylist.ORIGIN_YOUTUBE)
        assertThat(back.youtubeId).isEqualTo("PLabc")
        assertThat(back.isUserCreated).isFalse()
        assertThat(back.isMusic).isTrue()
        assertThat(back.createdAtMs).isEqualTo(42L)
        assertThat(PlaylistSyncMapper.metaHash(p)).isNotEqualTo(PlaylistSyncMapper.metaHash(p.copy(title = "other")))
    }
}
