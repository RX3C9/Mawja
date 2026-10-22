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
import app.mawja.android.data.local.SubscriptionRepository
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

/** The account page's view of sync. */
@Immutable
data class CloudSyncStatus(
    val running: Boolean = false,
    val lastSyncAt: Long = 0L,
    val outcome: CloudSyncOutcome = CloudSyncOutcome.NEVER,
)

/**
 * Cloud sync (PLAN_V2 #61, Plus): pulls the user's changed items, merges them last-writer-wins
 * ([SyncMerge]) into the local stores and pushes what the server lacks. Part 1 syncs
 * subscriptions. Schedules the periodic [CloudSyncWorker] while sync is [CloudSyncAvailability.READY].
 */
@Singleton
class CloudSyncRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val account: AccountBackend,
        entitlements: EntitlementsRepository,
        remote: RemoteConfigRepository,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val store = CloudSyncStore(context)
        private val mutex = Mutex()

        private val supported: Boolean =
            account.available && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

        val availability: StateFlow<CloudSyncAvailability> =
            combine(account.state, remote.settings, entitlements.entitlements) { state, settings, ent ->
                CloudSyncGate.availability(
                    supported = supported,
                    signedIn = state is AccountState.SignedIn,
                    remoteEnabled = settings.isEnabled(CloudSyncGate.REMOTE_SWITCH),
                    entitled = ent.has(MawjaFeature.CLOUD_SYNC),
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

        suspend fun sync(manual: Boolean): CloudSyncOutcome =
            mutex.withLock {
                val uid = (account.state.value as? AccountState.SignedIn)?.profile?.uid
                if (uid == null || !supported) return@withLock CloudSyncOutcome.NOT_ALLOWED
                _status.update { it.copy(running = true) }
                val outcome =
                    try {
                        withContext(Dispatchers.IO) { runSubscriptions(uid) }
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
                _status.value = CloudSyncStatus(running = false, lastSyncAt = store.lastSyncAt, outcome = outcome)
                Log.i(TAG, "sync ${if (manual) "manual" else "periodic"} -> $outcome")
                outcome
            }

        private suspend fun runSubscriptions(uid: String) {
            store.bindTo(uid)
            val rest = FirestoreRest(BuildConfig.FIREBASE_PROJECT_ID) { account.idToken() }
            val remote = CloudSyncRemote(rest, uid)
            val since = store.cursor?.let { runCatching { Instant.parse(it) }.getOrNull() }?.minusMillis(SyncLimits.PULL_OVERLAP_MS)
            val pulled = remote.pull(since)

            val subscriptions = SubscriptionRepository.getInstance(context)
            val device = store.deviceId
            val current = subscriptions.getAllSubscriptions().first()
            val local = SubscriptionSyncMapper.toRecords(current, subscriptions.unsubscribedTombstones(), device)
            val collection = SubscriptionSyncMapper.COLLECTION
            val plan =
                SyncMerge.plan(
                    local = local,
                    remote = pulled.records[collection].orEmpty(),
                    base = store.base(collection),
                    now = System.currentTimeMillis(),
                    maxPush = SyncLimits.MAX_PUSH_PER_RUN,
                    tombstoneHorizonMs = SyncLimits.TOMBSTONE_HORIZON_MS,
                )

            val changes = SubscriptionSyncMapper.changes(plan.applyLocally, current.map { it.channelId }.toSet())
            if (changes.subscribe.isNotEmpty()) subscriptions.subscribeAll(changes.subscribe)
            changes.unsubscribe.forEach { (id, at) -> subscriptions.unsubscribe(id, at) }
            if (changes.tombstones.isNotEmpty()) subscriptions.recordUnsubscribedAt(changes.tombstones)
            var base = plan.base
            store.saveBase(collection, base)
            pulled.cursor?.let { store.cursor = it.toString() }

            remote.push(collection, plan.push) { committed ->
                base = base + committed.associate { it.key to it.modifiedAt }
                store.saveBase(collection, base)
            }
            Log.i(
                TAG,
                "subscriptions: pulled ${pulled.documents}, applied +${changes.subscribe.size}/-${changes.unsubscribe.size}, " +
                    "pushed ${plan.push.size}, deferred ${plan.deferred}",
            )
        }

        private companion object {
            const val TAG = "MawjaCloudSync"
            const val SCHEDULE_SETTLE_MS = 5_000L
            const val READY_WAIT_MS = 15_000L
        }
    }
