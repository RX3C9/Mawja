/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.cloudsync

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import app.mawja.android.BuildConfig
import app.mawja.android.account.AccountBackend
import app.mawja.android.account.AccountState
import app.mawja.android.account.EntitlementsRepository
import app.mawja.android.account.FirestoreRest
import app.mawja.android.account.MawjaFeature
import app.mawja.android.data.local.LikedVideosRepository
import app.mawja.android.data.local.SubscriptionRepository
import app.mawja.android.data.localmedia.LocalMediaIds
import app.mawja.android.sync.apply.CollectionLedger
import app.mawja.android.sync.apply.PeerTombstone
import app.mawja.android.sync.apply.SyncChangeLedgerStore
import app.mawja.android.sync.apply.SyncDataAccess
import app.mawja.android.sync.canonical.CanonicalPlaylist
import app.mawja.android.remote.RemoteConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** "Delete my cloud data" progress for the account page. */
enum class CloudDeleteState { IDLE, RUNNING, DONE, FAILED }

/** The account page's view of sync. */
@Immutable
data class CloudSyncStatus(
    val running: Boolean = false,
    val lastSyncAt: Long = 0L,
    val outcome: CloudSyncOutcome = CloudSyncOutcome.NEVER,
    val deleteState: CloudDeleteState = CloudDeleteState.IDLE,
    val deletedCount: Int = 0,
)

/**
 * Cloud sync (PLAN_V2 #61, Plus): pulls the user's changed items, merges them last-writer-wins
 * ([SyncMerge]) into the local stores and pushes what the server lacks. Syncs subscriptions,
 * playlists (meta + one doc per entry with a fractional order key), liked videos, the newest watch
 * history and a whitelisted settings subset. Stores without edit times or deletion traces are
 * diffed against a cloud-only [CollectionLedger]. Schedules the periodic [CloudSyncWorker] while
 * sync is [CloudSyncAvailability.READY].
 */
@Singleton
class CloudSyncRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val account: AccountBackend,
        entitlements: EntitlementsRepository,
        remote: RemoteConfigRepository,
        private val data: SyncDataAccess,
        private val ledgers: SyncChangeLedgerStore,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val store = CloudSyncStore(context)
        private val mutex = Mutex()
        private val userEnabled = MutableStateFlow(store.enabled)

        private val supported: Boolean =
            account.available && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

        val availability: StateFlow<CloudSyncAvailability> =
            combine(account.state, remote.settings, entitlements.entitlements, userEnabled) { state, settings, ent, on ->
                CloudSyncGate.availability(
                    supported = supported,
                    signedIn = state is AccountState.SignedIn,
                    remoteEnabled = settings.isEnabled(CloudSyncGate.REMOTE_SWITCH),
                    entitled = ent.has(MawjaFeature.CLOUD_SYNC),
                    userEnabled = on,
                )
            }.distinctUntilChanged()
                .stateIn(scope, SharingStarted.Eagerly, if (supported) CloudSyncAvailability.SIGNED_OUT else CloudSyncAvailability.UNSUPPORTED)

        private val _status = MutableStateFlow(CloudSyncStatus(lastSyncAt = store.lastSyncAt, outcome = store.lastOutcome))
        val status: StateFlow<CloudSyncStatus> = _status.asStateFlow()

        init {
            if (supported) followSchedule()
        }

        /**
         * Keeps the periodic job in step with [availability]. Decisions wait until the account has
         * restored and the state held for a few seconds, so a cold start (profile tier still
         * loading) neither cancels nor re-enqueues the job.
         */
        @OptIn(FlowPreview::class)
        private fun followSchedule() {
            scope.launch {
                combine(availability, account.state) { a, s -> a to s }
                    .filter { (_, s) -> s !is AccountState.Loading }
                    .debounce(SCHEDULE_SETTLE_MS)
                    .distinctUntilChanged { old, new -> old.first == new.first }
                    .collect { (a, _) ->
                        if (a == CloudSyncAvailability.READY) CloudSyncWorker.schedule(context) else CloudSyncWorker.cancel(context)
                    }
            }
        }

        /** For a cold worker: waits briefly for the session and membership to restore. */
        suspend fun awaitReady(): Boolean =
            withTimeoutOrNull(READY_WAIT_MS) { availability.first { it == CloudSyncAvailability.READY } } != null

        /** "Sync now" on the account page. */
        fun syncNow() {
            if (availability.value != CloudSyncAvailability.READY) return
            scope.launch { sync(manual = true) }
        }

        /** Turns sync back on for this device (after it was turned off) and runs it once. */
        fun turnOn() {
            store.enabled = true
            userEnabled.value = true
            _status.update { it.copy(deleteState = CloudDeleteState.IDLE) }
            scope.launch {
                if (awaitReady()) sync(manual = true)
            }
        }

        /**
         * "Delete my cloud data": deletes every sync doc of this account, a page at a time, then turns
         * sync off on this device and forgets what was agreed with the server. Local data stays.
         */
        fun deleteCloudData() {
            val uid = (account.state.value as? AccountState.SignedIn)?.profile?.uid ?: return
            if (!supported || _status.value.deleteState == CloudDeleteState.RUNNING) return
            _status.update { it.copy(deleteState = CloudDeleteState.RUNNING, deletedCount = 0) }
            scope.launch {
                mutex.withLock {
                    // Off first, so the periodic job cannot re-upload while this runs.
                    store.enabled = false
                    userEnabled.value = false
                    CloudSyncWorker.cancel(context)
                    val result =
                        try {
                            val rest = FirestoreRest(BuildConfig.FIREBASE_PROJECT_ID) { account.idToken() }
                            val count =
                                CloudSyncRemote(rest, uid).deleteAll { n -> _status.update { it.copy(deletedCount = n) } }
                            store.bindTo(uid)
                            store.forgetServerState()
                            Log.i(TAG, "cloud data deleted: $count docs")
                            CloudDeleteState.DONE to count
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "cloud data delete failed", e)
                            CloudDeleteState.FAILED to _status.value.deletedCount
                        }
                    _status.update { it.copy(deleteState = result.first, deletedCount = result.second) }
                }
            }
        }

        suspend fun sync(manual: Boolean): CloudSyncOutcome =
            mutex.withLock {
                val uid = (account.state.value as? AccountState.SignedIn)?.profile?.uid
                if (uid == null || !supported || !store.enabled) return@withLock CloudSyncOutcome.NOT_ALLOWED
                _status.update { it.copy(running = true) }
                val outcome =
                    try {
                        withContext(Dispatchers.IO) { runAll(uid) }
                        CloudSyncOutcome.OK
                    } catch (e: CancellationException) {
                        _status.update { it.copy(running = false) }
                        throw e
                    } catch (e: FirestoreRest.HttpError) {
                        Log.w(TAG, "sync refused: ${e.code} ${e.status}")
                        if (e.code == 401 || e.code == 403) CloudSyncOutcome.NOT_ALLOWED else CloudSyncOutcome.FAILED
                    } catch (e: IOException) {
                        Log.i(TAG, "sync offline: ${e.message}")
                        CloudSyncOutcome.OFFLINE
                    } catch (e: Exception) {
                        Log.w(TAG, "sync failed", e)
                        CloudSyncOutcome.FAILED
                    }
                val now = System.currentTimeMillis()
                store.recordRun(outcome, now)
                _status.update { it.copy(running = false, lastSyncAt = store.lastSyncAt, outcome = outcome) }
                Log.i(TAG, "sync ${if (manual) "manual" else "periodic"} -> $outcome")
                outcome
            }

        /** Local winners of one collection waiting to be pushed, and how to record each commit. */
        private class Pending(
            val collection: String,
            val records: List<SyncRecord>,
            val onCommitted: (List<SyncRecord>) -> Unit,
        )

        private suspend fun runAll(uid: String) {
            store.bindTo(uid)
            val rest = FirestoreRest(BuildConfig.FIREBASE_PROJECT_ID) { account.idToken() }
            val remote = CloudSyncRemote(rest, uid)
            val hadCursor = store.cursor != null
            val since = store.cursor?.let { runCatching { Instant.parse(it) }.getOrNull() }?.minusMillis(SyncLimits.PULL_OVERLAP_MS)
            val pulled = remote.pull(since)
            val records = HashMap<String, MutableList<SyncRecord>>()
            pulled.records.forEach { (c, list) -> records.getOrPut(c) { ArrayList() } += list }

            // One-off full pull of collections this device has never read whole.
            val completed = HashSet<String>()
            if (!hadCursor) completed += CloudSyncCollections.ALL
            for (c in CloudSyncCollections.toFullPull(store.fullyPulled(), hadCursor)) {
                val full = remote.pullCollection(c)
                records.getOrPut(c) { ArrayList() } += full.records
                if (full.complete) completed += c
                Log.i(TAG, "full pull $c: ${full.records.size} (complete=${full.complete})")
            }

            val now = System.currentTimeMillis()
            val device = store.deviceId
            val pending = ArrayList<Pending>()
            pending += subscriptions(records[SubscriptionSyncMapper.COLLECTION].orEmpty(), device, now)
            pending +=
                playlists(
                    records[PlaylistSyncMapper.PLAYLISTS].orEmpty(),
                    records[PlaylistSyncMapper.ENTRIES].orEmpty(),
                    device,
                    now,
                )
            pending += liked(records[LikeSyncMapper.COLLECTION].orEmpty(), device, now)
            pending += history(records[HistorySyncMapper.COLLECTION].orEmpty(), device, now)
            pending += settings(records[SettingsSyncMapper.COLLECTION].orEmpty(), device, now)

            // Everything pulled is applied: move the cursor before pushing.
            pulled.cursor?.let { store.cursor = it.toString() }
            store.markFullyPulled(completed)

            for (p in pending) {
                if (p.records.isNotEmpty()) remote.push(p.collection, p.records, p.onCommitted)
            }
            Log.i(TAG, "pulled ${pulled.documents}, pushed " + pending.joinToString { "${it.collection}=${it.records.size}" })
            collectGarbage(remote, now)
        }

        /** Saves the plan's base and returns the push, whose commits extend the base as they land. */
        private fun pendingOf(
            collection: String,
            plan: SyncPlan,
            keep: Set<String>? = null,
        ): Pending {
            var base = keep?.let { SyncHousekeeping.pruneBase(plan.base, it) } ?: plan.base
            store.saveBase(collection, base)
            if (plan.deferred > 0) Log.i(TAG, "$collection: ${plan.deferred} changes deferred to the next run")
            return Pending(collection, plan.push) { committed ->
                base = base + committed.associate { it.key to it.modifiedAt }
                store.saveBase(collection, base)
            }
        }

        private fun peerTombstones(apply: List<SyncRecord>): Map<String, PeerTombstone> =
            apply.filter { it.deleted }.associate { it.key to PeerTombstone(it.modifiedAt, "") }

        private fun liveStamps(apply: List<SyncRecord>): Map<String, Long> = apply.filter { !it.deleted }.associate { it.key to it.modifiedAt }

        private fun CollectionLedger.tombstoneClocks(): Map<String, Long> = tombstones().mapValues { it.value.atMs }

        // ---- subscriptions ----------------------------------------------------------------------

        private suspend fun subscriptions(
            remoteRecords: List<SyncRecord>,
            device: String,
            now: Long,
        ): Pending {
            val subscriptions = SubscriptionRepository.getInstance(context)
            val current = subscriptions.getAllSubscriptions().first()
            val local = SubscriptionSyncMapper.toRecords(current, subscriptions.unsubscribedTombstones(), device)
            val collection = SubscriptionSyncMapper.COLLECTION
            val plan =
                SyncMerge.plan(
                    local = local,
                    remote = remoteRecords,
                    base = store.base(collection),
                    now = now,
                    maxPush = SyncLimits.MAX_PUSH_PER_RUN,
                    tombstoneHorizonMs = SyncLimits.TOMBSTONE_HORIZON_MS,
                )
            val changes = SubscriptionSyncMapper.changes(plan.applyLocally, current.map { it.channelId }.toSet())
            if (changes.subscribe.isNotEmpty()) subscriptions.subscribeAll(changes.subscribe)
            changes.unsubscribe.forEach { (id, at) -> subscriptions.unsubscribe(id, at) }
            if (changes.tombstones.isNotEmpty()) subscriptions.recordUnsubscribedAt(changes.tombstones)
            return pendingOf(collection, plan)
        }

        // ---- playlists (meta + entries) ---------------------------------------------------------

        private suspend fun playlists(
            remoteMeta: List<SyncRecord>,
            remoteEntries: List<SyncRecord>,
            device: String,
            now: Long,
        ): List<Pending> {
            val metaC = PlaylistSyncMapper.PLAYLISTS
            val entryC = PlaylistSyncMapper.ENTRIES
            val live = data.readPlaylists(hlc = "").filter { PlaylistSyncMapper.playlistKey(it) != null }
            val orders = PlaylistSyncMapper.orderKeys(live, store.orderKeys(entryC))
            val metaLedger =
                ledgers.update(SyncChangeLedgerStore.CLOUD_PLAYLISTS) { l ->
                    l.observe(live.associate { PlaylistSyncMapper.playlistKey(it)!! to PlaylistSyncMapper.metaHash(it) }, now)
                }
            val entryLedger =
                ledgers.update(SyncChangeLedgerStore.CLOUD_PLAYLIST_ITEMS) { l -> l.observe(orders.mapValues { it.value.hashCode() }, now) }

            val metaLocal = PlaylistSyncMapper.metaRecords(live, metaLedger::stampOf, metaLedger.tombstoneClocks(), device)
            val entryLocal = PlaylistSyncMapper.entryRecords(live, orders, entryLedger::stampOf, entryLedger.tombstoneClocks(), device)
            val metaPlan =
                SyncMerge.plan(metaLocal, remoteMeta, store.base(metaC), now, SyncLimits.MAX_PLAYLIST_PUSH, SyncLimits.TOMBSTONE_HORIZON_MS)
            val entryPlan =
                SyncMerge.plan(entryLocal, remoteEntries, store.base(entryC), now, SyncLimits.MAX_ENTRY_PUSH, SyncLimits.TOMBSTONE_HORIZON_MS)

            val writes = PlaylistSyncMapper.applyRemote(live, orders, metaPlan.applyLocally, entryPlan.applyLocally)
            if (!writes.isEmpty) {
                data.writePlaylists(writes.upsert + writes.delete.map { CanonicalPlaylist(syncId = it, deleted = true) })
            }
            val after = if (writes.isEmpty) live else data.readPlaylists(hlc = "").filter { PlaylistSyncMapper.playlistKey(it) != null }
            val settledOrders = PlaylistSyncMapper.orderKeys(after, writes.orderKeys)
            store.saveOrderKeys(entryC, settledOrders)
            ledgers.update(SyncChangeLedgerStore.CLOUD_PLAYLISTS) { l ->
                l.settle(
                    after.associate { PlaylistSyncMapper.playlistKey(it)!! to PlaylistSyncMapper.metaHash(it) },
                    liveStamps(metaPlan.applyLocally),
                    peerTombstones(metaPlan.applyLocally),
                )
            }
            ledgers.update(SyncChangeLedgerStore.CLOUD_PLAYLIST_ITEMS) { l ->
                l.settle(settledOrders.mapValues { it.value.hashCode() }, liveStamps(entryPlan.applyLocally), peerTombstones(entryPlan.applyLocally))
            }
            if (!writes.isEmpty) Log.i(TAG, "playlists: wrote ${writes.upsert.size}, deleted ${writes.delete.size}")
            return listOf(pendingOf(metaC, metaPlan), pendingOf(entryC, entryPlan))
        }

        // ---- liked videos -----------------------------------------------------------------------

        private suspend fun liked(
            remoteRecords: List<SyncRecord>,
            device: String,
            now: Long,
        ): Pending {
            val collection = LikeSyncMapper.COLLECTION
            val repo = LikedVideosRepository.getInstance(context)
            val liked = repo.getAllLikedVideos().first().filterNot { LocalMediaIds.isLocal(it.videoId) }
            val ledger = ledgers.update(SyncChangeLedgerStore.CLOUD_LIKES) { it.observe(liked.associate { l -> l.videoId to PRESENT }, now) }
            val local = LikeSyncMapper.toRecords(liked, ledger.tombstoneClocks(), device)
            val plan =
                SyncMerge.plan(local, remoteRecords, store.base(collection), now, SyncLimits.MAX_LIKED_PUSH, SyncLimits.TOMBSTONE_HORIZON_MS)
            val changes = LikeSyncMapper.changes(plan.applyLocally, liked.mapTo(HashSet()) { it.videoId })
            if (changes.like.isNotEmpty()) repo.restoreLikes(changes.like)
            changes.unlike.forEach { repo.removeLikeState(it) }
            val after =
                if (changes.like.isEmpty() && changes.unlike.isEmpty()) liked else repo.getAllLikedVideos().first().filterNot { LocalMediaIds.isLocal(it.videoId) }
            ledgers.update(SyncChangeLedgerStore.CLOUD_LIKES) {
                it.settle(after.associate { l -> l.videoId to PRESENT }, emptyMap(), peerTombstones(plan.applyLocally))
            }
            return pendingOf(collection, plan)
        }

        // ---- watch history (newest 2 000, at most 50 writes a run) ------------------------------

        private suspend fun history(
            remoteRecords: List<SyncRecord>,
            device: String,
            now: Long,
        ): Pending {
            val collection = HistorySyncMapper.COLLECTION
            val all = data.readWatchHistory(node = "")
            val ledger = ledgers.update(SyncChangeLedgerStore.CLOUD_HISTORY) { it.observe(all.associate { h -> h.videoId to PRESENT }, now) }
            val base = store.base(collection)
            val remoteKeys = remoteRecords.mapTo(HashSet()) { it.key }
            val tombstones = ledger.tombstoneClocks()
            val local = HistorySyncMapper.toRecords(all, tombstones, base.keys, remoteKeys, device)
            val plan = SyncMerge.plan(local, remoteRecords, base, now, SyncLimits.MAX_HISTORY_PUSH, SyncLimits.TOMBSTONE_HORIZON_MS)
            val rows = HistorySyncMapper.toCanonical(plan.applyLocally)
            if (rows.isNotEmpty()) data.writeWatchHistory(rows)
            val afterKeys =
                if (rows.isEmpty()) all.mapTo(HashSet()) { it.videoId } else data.readWatchHistory(node = "").mapTo(HashSet()) { it.videoId }
            ledgers.update(SyncChangeLedgerStore.CLOUD_HISTORY) {
                it.settle(afterKeys.associateWith { PRESENT }, emptyMap(), peerTombstones(plan.applyLocally))
            }
            // The base only needs keys this device still has (or still tombstones).
            return pendingOf(collection, plan, keep = afterKeys + tombstones.keys + remoteKeys)
        }

        // ---- settings (whitelisted subset) ------------------------------------------------------

        private suspend fun settings(
            remoteRecords: List<SyncRecord>,
            device: String,
            now: Long,
        ): Pending {
            val collection = SettingsSyncMapper.COLLECTION
            val live = data.readSettings(hlc = "").filter { it.key in SettingsSyncMapper.KEYS }
            val ledger =
                ledgers.update(SyncChangeLedgerStore.CLOUD_SETTINGS) { it.observe(live.associate { s -> s.key to SettingsSyncMapper.hash(s) }, now) }
            val local = SettingsSyncMapper.toRecords(live, live.associate { it.key to ledger.stampOf(it.key) }, device)
            val plan = SyncMerge.plan(local, remoteRecords, store.base(collection), now, SyncLimits.MAX_SETTINGS_PUSH)
            val changes = SettingsSyncMapper.changes(plan.applyLocally)
            if (changes.isNotEmpty()) data.writeSettings(changes)
            val after = if (changes.isEmpty()) live else data.readSettings(hlc = "").filter { it.key in SettingsSyncMapper.KEYS }
            ledgers.update(SyncChangeLedgerStore.CLOUD_SETTINGS) {
                it.settle(after.associate { s -> s.key to SettingsSyncMapper.hash(s) }, liveStamps(plan.applyLocally), emptyMap())
            }
            return pendingOf(collection, plan)
        }

        // ---- tombstone garbage collection -------------------------------------------------------

        /** Once a day: deletes this account's own tombstones older than 30 days. Never fails the run. */
        private suspend fun collectGarbage(
            remote: CloudSyncRemote,
            now: Long,
        ) {
            if (!SyncHousekeeping.gcDue(store.lastGcAt, now)) return
            try {
                val expired = SyncHousekeeping.expired(remote.tombstones(SyncLimits.GC_MAX_DELETES), now)
                if (expired.isNotEmpty()) remote.delete(expired)
                store.lastGcAt = now
                if (expired.isNotEmpty()) Log.i(TAG, "deleted ${expired.size} old tombstones")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "tombstone cleanup skipped: ${e.message}")
            }
        }

        private companion object {
            const val TAG = "MawjaCloudSync"
            const val SCHEDULE_SETTLE_MS = 5_000L
            const val READY_WAIT_MS = 15_000L
            const val PRESENT = 1
        }
    }
