/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.cloudsync

import app.mawja.android.account.Entitlements
import app.mawja.android.account.MawjaFeature
import app.mawja.android.account.MawjaTier
import app.mawja.android.data.local.ChannelSubscription
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncMergeTest {
    private val now = 1_800_000_000_000L

    private fun live(
        key: String,
        at: Long,
        dev: String = "a",
        name: String = key,
    ) = SyncRecord(key, at, deleted = false, device = dev, data = mapOf("n" to name))

    private fun dead(
        key: String,
        at: Long,
        dev: String = "a",
    ) = SyncRecord(key, at, deleted = true, device = dev)

    // ---- wins ------------------------------------------------------------------------------

    @Test
    fun laterClockWinsThenTombstoneThenDevice() {
        assertThat(SyncMerge.wins(live("k", 2), dead("k", 1))).isTrue()
        assertThat(SyncMerge.wins(dead("k", 1), live("k", 2))).isFalse()
        assertThat(SyncMerge.wins(dead("k", 5), live("k", 5))).isTrue()
        assertThat(SyncMerge.wins(live("k", 5), dead("k", 5))).isFalse()
        assertThat(SyncMerge.wins(live("k", 5, dev = "b"), live("k", 5, dev = "a"))).isTrue()
        assertThat(SyncMerge.wins(live("k", 5, dev = "a"), live("k", 5, dev = "b"))).isFalse()
    }

    // ---- plan ------------------------------------------------------------------------------

    @Test
    fun firstSyncPushesEverythingLocalAndAppliesRemoteOnlyItems() {
        val plan =
            SyncMerge.plan(
                local = mapOf("A" to live("A", 100), "B" to live("B", 200)),
                remote = listOf(live("C", 300, dev = "x")),
                base = emptyMap(),
                now = now,
            )
        assertThat(plan.push.map { it.key }).containsExactly("B", "A").inOrder() // newest first
        assertThat(plan.applyLocally.map { it.key }).containsExactly("C")
        assertThat(plan.base).containsExactly("C", 300L)
        assertThat(plan.deferred).isEqualTo(0)
    }

    @Test
    fun remoteTombstoneNewerThanLocalSubscriptionUnsubscribes() {
        val plan = SyncMerge.plan(mapOf("A" to live("A", 100)), listOf(dead("A", 150, dev = "x")), mapOf("A" to 100L), now)
        assertThat(plan.applyLocally).containsExactly(dead("A", 150, dev = "x"))
        assertThat(plan.push).isEmpty()
        assertThat(plan.base["A"]).isEqualTo(150L)
    }

    @Test
    fun localResubscribeNewerThanRemoteTombstoneIsPushed() {
        val plan = SyncMerge.plan(mapOf("A" to live("A", 200)), listOf(dead("A", 150, dev = "x")), mapOf("A" to 100L), now)
        assertThat(plan.applyLocally).isEmpty()
        assertThat(plan.push).containsExactly(live("A", 200))
        assertThat(plan.base["A"]).isEqualTo(100L) // joins the base only after the commit
    }

    @Test
    fun unchangedItemsCostNothing() {
        val plan =
            SyncMerge.plan(
                local = mapOf("A" to live("A", 100), "B" to dead("B", 90)),
                remote = emptyList(),
                base = mapOf("A" to 100L, "B" to 90L),
                now = now,
            )
        assertThat(plan.push).isEmpty()
        assertThat(plan.applyLocally).isEmpty()
    }

    @Test
    fun ownEchoFromTheOverlapWindowIsRecognised() {
        val plan = SyncMerge.plan(mapOf("A" to live("A", 100)), listOf(live("A", 100)), mapOf("A" to 100L), now)
        assertThat(plan.push).isEmpty()
        assertThat(plan.applyLocally).isEmpty()
    }

    @Test
    fun locallyChangedAfterBaseIsPushedEvenWithoutRemoteChange() {
        val plan = SyncMerge.plan(mapOf("A" to dead("A", 500)), emptyList(), mapOf("A" to 100L), now)
        assertThat(plan.push).containsExactly(dead("A", 500))
    }

    @Test
    fun duplicateRemoteRecordsKeepTheWinner() {
        val plan =
            SyncMerge.plan(
                emptyMap(),
                listOf(live("A", 100, dev = "x"), dead("A", 300, dev = "y"), live("A", 200, dev = "z")),
                emptyMap(),
                now,
            )
        assertThat(plan.applyLocally).containsExactly(dead("A", 300, dev = "y"))
    }

    @Test
    fun oldTombstonesAreNotUploadedOnFirstSyncButFreshOnesAre() {
        val day = 86_400_000L
        val plan =
            SyncMerge.plan(
                local = mapOf("OLD" to dead("OLD", now - 400 * day), "NEW" to dead("NEW", now - day)),
                remote = emptyList(),
                base = emptyMap(),
                now = now,
                tombstoneHorizonMs = 180 * day,
            )
        assertThat(plan.push.map { it.key }).containsExactly("NEW")
    }

    @Test
    fun futureClocksAreClampedAndPushIsCapped() {
        val local = (1..10).associate { "K$it" to live("K$it", it.toLong()) } + ("F" to live("F", now + 99_999))
        val plan = SyncMerge.plan(local, emptyList(), emptyMap(), now, maxPush = 4)
        assertThat(plan.push).hasSize(4)
        assertThat(plan.push.first()).isEqualTo(live("F", now))
        assertThat(plan.deferred).isEqualTo(7)
    }

    @Test
    fun invalidKeysAreIgnored() {
        val plan = SyncMerge.plan(mapOf("bad key" to live("bad key", 1)), listOf(live("a/b", 2)), emptyMap(), now)
        assertThat(plan.push).isEmpty()
        assertThat(plan.applyLocally).isEmpty()
    }

    @Test
    fun twoDevicesConvergeWhicheverOrderTheySync() {
        // Device 1 unsubscribes A at 300; device 2 renames/resubscribes A at 250 and adds B.
        val d1 = mapOf("A" to dead("A", 300, dev = "d1"))
        val d2 = mapOf("A" to live("A", 250, dev = "d2"), "B" to live("B", 260, dev = "d2"))
        val server = HashMap<String, SyncRecord>()

        fun run(local: Map<String, SyncRecord>): Map<String, SyncRecord> {
            val plan = SyncMerge.plan(local, server.values.toList(), emptyMap(), now)
            plan.push.forEach { server[it.key] = it }
            return local + plan.applyLocally.associateBy { it.key }
        }
        val after1 = run(d1)
        val after2 = run(d2)
        val after1Again = run(after1)
        assertThat(after1Again.mapValues { it.value.deleted }).isEqualTo(after2.mapValues { it.value.deleted })
        assertThat(after2.getValue("A").deleted).isTrue()
        assertThat(after2.getValue("B").deleted).isFalse()
    }

    // ---- base codec ------------------------------------------------------------------------

    @Test
    fun baseCodecRoundTripsAndSurvivesDamage() {
        val base = mapOf("UCa" to 1L, "UC_b-c" to 1_700_000_000_000L)
        assertThat(SyncBaseCodec.decode(SyncBaseCodec.encode(base))).isEqualTo(base)
        assertThat(SyncBaseCodec.decode("UCa\t5\ngarbage\n\t7\nUCb\tx\nbad key\t3")).containsExactly("UCa", 5L)
        assertThat(SyncBaseCodec.decode(null)).isEmpty()
    }

    // ---- gate ------------------------------------------------------------------------------

    @Test
    fun gateOrderAndPlusEntitlement() {
        assertThat(CloudSyncGate.availability(false, true, true, true)).isEqualTo(CloudSyncAvailability.UNSUPPORTED)
        assertThat(CloudSyncGate.availability(true, false, true, true)).isEqualTo(CloudSyncAvailability.SIGNED_OUT)
        assertThat(CloudSyncGate.availability(true, true, false, false)).isEqualTo(CloudSyncAvailability.DISABLED)
        assertThat(CloudSyncGate.availability(true, true, true, false)).isEqualTo(CloudSyncAvailability.LOCKED)
        assertThat(CloudSyncGate.availability(true, true, true, true)).isEqualTo(CloudSyncAvailability.READY)
        assertThat(Entitlements.forTier(MawjaTier.FREE).has(MawjaFeature.CLOUD_SYNC)).isFalse()
        assertThat(Entitlements.forTier(MawjaTier.PLUS).has(MawjaFeature.CLOUD_SYNC)).isTrue()
        assertThat(Entitlements.forTier(MawjaTier.LIFETIME).has(MawjaFeature.CLOUD_SYNC)).isTrue()
        assertThat(Entitlements.minimumTier(MawjaFeature.CLOUD_SYNC)).isEqualTo(MawjaTier.PLUS)
        assertThat(CloudSyncGate.shouldRun(manual = false, batterySaverActive = true)).isFalse()
        assertThat(CloudSyncGate.shouldRun(manual = true, batterySaverActive = true)).isTrue()
        assertThat(CloudSyncGate.shouldRun(manual = false, batterySaverActive = false)).isTrue()
    }

    // ---- subscriptions mapper --------------------------------------------------------------

    private fun sub(
        id: String,
        at: Long,
        name: String = "Name $id",
    ) = ChannelSubscription(channelId = id, channelName = name, channelThumbnail = "https://img/$id", subscribedAt = at, isMusic = id == "UCm")

    @Test
    fun localSubscriptionsAndTombstonesBecomeRecords() {
        val records = SubscriptionSyncMapper.toRecords(listOf(sub("UCa", 10), sub("UCm", 20)), mapOf("UCx" to 30L, "UCa" to 5L), "dev")
        assertThat(records.getValue("UCa")).isEqualTo(
            SyncRecord("UCa", 10, false, "dev", mapOf("n" to "Name UCa", "a" to "https://img/UCa", "mu" to false)),
        )
        assertThat(records.getValue("UCm").data["mu"]).isEqualTo(true)
        assertThat(records.getValue("UCx")).isEqualTo(SyncRecord("UCx", 30, true, "dev"))
        val long = SubscriptionSyncMapper.payload(sub("UCl", 1, name = "x".repeat(500)).copy(channelThumbnail = ""))
        assertThat((long["n"] as String).length).isEqualTo(SyncLimits.NAME_MAX)
        assertThat(long).doesNotContainKey("a")
    }

    @Test
    fun appliedRecordsBecomeStoreChanges() {
        val changes =
            SubscriptionSyncMapper.changes(
                apply =
                    listOf(
                        SyncRecord("UCnew", 40, false, "x", mapOf("n" to "New", "a" to "https://img/new", "mu" to true)),
                        SyncRecord("UChave", 41, false, "x", mapOf("n" to "Renamed")),
                        SyncRecord("UChave2", 42, true, "x"),
                        SyncRecord("UCgone", 43, true, "x"),
                    ),
                subscribedIds = setOf("UChave", "UChave2"),
            )
        assertThat(changes.subscribe).containsExactly(
            ChannelSubscription("UCnew", "New", "https://img/new", subscribedAt = 40, isMusic = true),
        )
        assertThat(changes.unsubscribe).containsExactly("UChave2", 42L)
        assertThat(changes.tombstones).containsExactly("UCgone", 43L)
    }

    @Test
    fun storedDocumentsDecodeAndForeignOnesAreSkipped() {
        val (c, r) =
            CloudSyncRemote.decode(
                mapOf("c" to "subscriptions", "k" to "UCa", "m" to 9L, "del" to false, "dev" to "d", "d" to mapOf("n" to "A")),
            )!!
        assertThat(c).isEqualTo("subscriptions")
        assertThat(r).isEqualTo(SyncRecord("UCa", 9, false, "d", mapOf("n" to "A")))
        assertThat(CloudSyncRemote.decode(mapOf("items" to 3L))).isNull()
        assertThat(CloudSyncRemote.decode(mapOf("c" to "subscriptions", "k" to "UCa", "m" to 0L, "del" to false))).isNull()
        val tomb = CloudSyncRemote.decode(mapOf("c" to "subscriptions", "k" to "UCa", "m" to 9L, "del" to true, "d" to mapOf("n" to "x")))!!
        assertThat(tomb.second.data).isEmpty()
    }
}
