/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.cloudsync

import app.mawja.android.data.local.LikedVideoInfo
import app.mawja.android.sync.canonical.CanonicalSetting
import app.mawja.android.sync.canonical.CanonicalWatchHistory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File

class CollectionSyncMappersTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    // ---- liked -------------------------------------------------------------------------------

    private fun like(
        id: String,
        at: Long,
    ) = LikedVideoInfo(videoId = id, title = "T $id", thumbnail = "https://i.ytimg.com/$id.jpg", channelName = "Ch", likedAt = at, channelId = "UC1", durationSeconds = 90)

    @Test
    fun likesBecomeRecordsClockedByLikeTime() {
        val records = LikeSyncMapper.toRecords(listOf(like("v1", 100L), like("v2", 200L)), mapOf("v3" to 300L, "v1" to 50L), "dev")
        assertThat(records.getValue("v1").deleted).isFalse() // a later like beats an older unlike
        assertThat(records.getValue("v1").modifiedAt).isEqualTo(100L)
        assertThat(records.getValue("v3").deleted).isTrue()
        assertThat(records.getValue("v2").data).containsEntry("t", "T v2")
        assertThat(records.getValue("v2").data).containsEntry("ci", "UC1")
        assertThat(records.getValue("v2").data).containsEntry("du", 90L)
    }

    @Test
    fun likeChangesAddMissingAndUnlikeHeld() {
        val apply =
            listOf(
                SyncRecord("new", 500L, deleted = false, device = "B", data = mapOf("t" to "New", "ch" to "C", "mu" to true, "du" to 61L)),
                SyncRecord("held", 600L, deleted = true, device = "B"),
                SyncRecord("absent", 600L, deleted = true, device = "B"),
                SyncRecord("already", 700L, deleted = false, device = "B", data = mapOf("t" to "x")),
            )
        val changes = LikeSyncMapper.changes(apply, setOf("held", "already"))
        assertThat(changes.unlike).containsExactly("held")
        val added = changes.like.single()
        assertThat(added.videoId).isEqualTo("new")
        assertThat(added.likedAt).isEqualTo(500L)
        assertThat(added.isMusic).isTrue()
        assertThat(added.durationSeconds).isEqualTo(61)
    }

    @Test
    fun anUnlikeOnAnotherDeviceWinsOverAnOlderLike() {
        val local = LikeSyncMapper.toRecords(listOf(like("v1", 100L)), emptyMap(), "A")
        val plan = SyncMerge.plan(local, listOf(SyncRecord("v1", 200L, deleted = true, device = "B")), mapOf("v1" to 100L), now)
        assertThat(LikeSyncMapper.changes(plan.applyLocally, setOf("v1")).unlike).containsExactly("v1")
        assertThat(plan.push).isEmpty()
    }

    // ---- history -----------------------------------------------------------------------------

    private fun watched(
        id: String,
        at: Long,
    ) = CanonicalWatchHistory(videoId = id, title = "T", watchedAtMs = at, progress = 0.5, durationSeconds = 300)

    @Test
    fun historyOffersOnlyTheNewestEntriesAndKnownDeletions() {
        val history = (1..10).map { watched("v$it", it * 1_000L) }
        val records =
            HistorySyncMapper.toRecords(
                history,
                tombstones = mapOf("gone" to 50L, "never_pushed" to 60L),
                serverKeys = setOf("gone"),
                remoteKeys = setOf("v1"),
                device = "dev",
                cap = 3,
            )
        // v10, v9, v8 by recency, v1 because the server just sent it, and the known deletion.
        assertThat(records.keys).containsExactly("gone", "v10", "v9", "v8", "v1")
        assertThat(records.getValue("gone").deleted).isTrue()
        assertThat(records.getValue("v10").data).containsEntry("pr", 0.5)
        assertThat(records.getValue("v10").data).containsEntry("du", 300L)
    }

    @Test
    fun historyPushIsCappedAtFiftyNewestFirst() {
        val history = (1..200).map { watched("v$it", it * 1_000L) }
        val local = HistorySyncMapper.toRecords(history, emptyMap(), emptySet(), emptySet(), "dev")
        val plan = SyncMerge.plan(local, emptyList(), emptyMap(), now, SyncLimits.MAX_HISTORY_PUSH)
        assertThat(plan.push).hasSize(50)
        assertThat(plan.push.first().key).isEqualTo("v200")
        assertThat(plan.deferred).isEqualTo(150)
        assertThat(SyncLimits.MAX_HISTORY).isEqualTo(2_000)
    }

    @Test
    fun historyRowsFromRemote() {
        val rows =
            HistorySyncMapper.toCanonical(
                listOf(
                    SyncRecord("v1", 9_000L, deleted = false, device = "B", data = mapOf("t" to "T", "pr" to 2.0, "du" to 100L, "sh" to true)),
                    SyncRecord("v2", 9_000L, deleted = true, device = "B"),
                ),
            )
        assertThat(rows[0].watchedAtMs).isEqualTo(9_000L)
        assertThat(rows[0].progress).isEqualTo(1.0) // clamped
        assertThat(rows[0].isShort).isTrue()
        assertThat(rows[1].deleted).isTrue()
    }

    // ---- settings ----------------------------------------------------------------------------

    @Test
    fun settingsTravelOnlyFromTheWhitelistWithTypedValues() {
        val settings =
            listOf(
                CanonicalSetting("autoplay", JsonPrimitive(true)),
                CanonicalSetting("playback_speed", JsonPrimitive(1.25f)),
                CanonicalSetting("default_quality_wifi", JsonPrimitive("1080p")),
                CanonicalSetting("app_language", JsonPrimitive("ar")), // shown, never applied: not synced
                CanonicalSetting("download_path", JsonPrimitive("/sdcard")),
                CanonicalSetting("equalizer", JsonPrimitive("x".repeat(SettingsSyncMapper.VALUE_MAX + 1))),
            )
        val records = SettingsSyncMapper.toRecords(settings, mapOf("autoplay" to 77L), "dev")
        assertThat(records.keys).containsExactly("autoplay", "playback_speed", "default_quality_wifi")
        assertThat(records.getValue("autoplay").modifiedAt).isEqualTo(77L)
        assertThat(records.getValue("playback_speed").modifiedAt).isEqualTo(1L) // never seen changing
        assertThat(records.getValue("autoplay").data).containsEntry("val", true)
        assertThat(records.getValue("playback_speed").data["val"] as Double).isWithin(1e-6).of(1.25)
        assertThat(records.getValue("default_quality_wifi").data).containsEntry("val", "1080p")
    }

    @Test
    fun settingsChangesRoundTrip() {
        val changes =
            SettingsSyncMapper.changes(
                listOf(
                    SyncRecord("autoplay", 5L, deleted = false, device = "B", data = mapOf("val" to false)),
                    SyncRecord("playback_speed", 5L, deleted = false, device = "B", data = mapOf("val" to 1.5)),
                    SyncRecord("app_language", 5L, deleted = false, device = "B", data = mapOf("val" to "ar")),
                    SyncRecord("subtitles_enabled", 5L, deleted = true, device = "B"),
                ),
            )
        assertThat(changes.map { it.key }).containsExactly("autoplay", "playback_speed")
        assertThat(changes[0].value).isEqualTo(JsonPrimitive(false))
    }

    @Test
    fun settingsWhitelistMatchesTheFirestoreRules() {
        val rules = listOf(File("../backend/firestore.rules"), File("backend/firestore.rules")).firstOrNull { it.exists() } ?: return
        val block = rules.readText().substringAfter("function syncSettingKeys()").substringBefore("];")
        val ruleKeys = Regex("'([a-z_]+)'").findAll(block).map { it.groupValues[1] }.toSet()
        assertThat(ruleKeys).isEqualTo(SettingsSyncMapper.KEYS)
        assertThat(SettingsSyncMapper.KEYS).doesNotContain("app_language")
    }

    // ---- collections, housekeeping, gate -----------------------------------------------------

    @Test
    fun collectionNamesFitTheDocIdScheme() {
        CloudSyncCollections.ALL.forEach { assertThat(it).doesNotContain("_") }
        assertThat(CloudSyncCollections.ALL.indexOf(PlaylistSyncMapper.PLAYLISTS))
            .isLessThan(CloudSyncCollections.ALL.indexOf(PlaylistSyncMapper.ENTRIES))
    }

    @Test
    fun fullPullOnlyForCollectionsNeverReadWhole() {
        assertThat(CloudSyncCollections.toFullPull(emptySet(), cursorKnown = false)).isEmpty()
        val legacy = CloudSyncCollections.legacyFullyPulled(cursorKnown = true)
        assertThat(legacy).containsExactly(SubscriptionSyncMapper.COLLECTION)
        assertThat(CloudSyncCollections.toFullPull(legacy, cursorKnown = true))
            .containsExactly("playlists", "plitems", "liked", "history", "settings")
            .inOrder()
        assertThat(CloudSyncCollections.toFullPull(CloudSyncCollections.ALL.toSet(), cursorKnown = true)).isEmpty()
        assertThat(CloudSyncCollections.legacyFullyPulled(cursorKnown = false)).isEmpty()
    }

    @Test
    fun tombstoneCleanupTakesOnlyOldTombstonesOnceADay() {
        val docs =
            listOf(
                ServerTombstone("a", at = now - 31 * day, modifiedAt = now - 31 * day, deleted = true),
                ServerTombstone("b", at = now - 29 * day, modifiedAt = now - 40 * day, deleted = true), // server time decides
                ServerTombstone("c", at = 0L, modifiedAt = now - 45 * day, deleted = true), // no server time: client clock
                ServerTombstone("d", at = now - 90 * day, modifiedAt = now - 90 * day, deleted = false), // live: never
            )
        assertThat(SyncHousekeeping.expired(docs, now)).containsExactly("a", "c").inOrder()
        assertThat(SyncHousekeeping.expired(docs, now, max = 1)).containsExactly("a")
        assertThat(SyncHousekeeping.gcDue(0L, now)).isTrue()
        assertThat(SyncHousekeeping.gcDue(now - day / 2, now)).isFalse()
        assertThat(SyncHousekeeping.gcDue(now - day, now)).isTrue()
        assertThat(SyncHousekeeping.gcDue(now + day, now)).isTrue() // clock moved back
    }

    @Test
    fun pruneBaseKeepsOnlyWantedKeys() {
        val base = mapOf("a" to 1L, "b" to 2L)
        assertThat(SyncHousekeeping.pruneBase(base, setOf("a", "b", "c"))).isSameInstanceAs(base)
        assertThat(SyncHousekeeping.pruneBase(base, setOf("b"))).containsExactly("b", 2L)
    }

    @Test
    fun offStateAndDeleteOffer() {
        assertThat(CloudSyncGate.availability(true, true, true, true, userEnabled = false)).isEqualTo(CloudSyncAvailability.OFF)
        assertThat(CloudSyncGate.availability(true, true, true, false, userEnabled = false)).isEqualTo(CloudSyncAvailability.LOCKED)
        assertThat(CloudSyncGate.canDeleteCloudData(CloudSyncAvailability.READY, everSynced = false)).isTrue()
        assertThat(CloudSyncGate.canDeleteCloudData(CloudSyncAvailability.OFF, everSynced = false)).isTrue()
        assertThat(CloudSyncGate.canDeleteCloudData(CloudSyncAvailability.LOCKED, everSynced = false)).isFalse()
        assertThat(CloudSyncGate.canDeleteCloudData(CloudSyncAvailability.LOCKED, everSynced = true)).isTrue()
        assertThat(CloudSyncGate.canDeleteCloudData(CloudSyncAvailability.SIGNED_OUT, everSynced = true)).isFalse()
    }
}
