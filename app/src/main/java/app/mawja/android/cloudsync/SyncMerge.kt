/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.cloudsync

/**
 * One synced item: a last-writer-wins register with a tombstone (docs/cloud-sync.md). [modifiedAt]
 * is the client clock of the change (epoch ms), [device] the writer's install id.
 */
data class SyncRecord(
    val key: String,
    val modifiedAt: Long,
    val deleted: Boolean,
    val device: String,
    val data: Map<String, Any?> = emptyMap(),
)

/** What one sync run does: records to apply locally, records to push, and the agreed clocks. */
data class SyncPlan(
    /** Remote records that beat the local state. */
    val applyLocally: List<SyncRecord>,
    /** Local records the server does not have yet (already capped to the run's push limit). */
    val push: List<SyncRecord>,
    /** The base after this run, without [push] (those join it once their commit succeeds). */
    val base: Map<String, Long>,
    /** Local changes left for the next run because of the push limit. */
    val deferred: Int,
)

/** Pure last-writer-wins merge. Same inputs give the same answer on every device. */
object SyncMerge {
    /** Item keys the rules accept (`^[A-Za-z0-9_.:-]{1,128}$`). */
    private val KEY = Regex("^[A-Za-z0-9_.:-]{1,128}$")

    fun isValidKey(key: String): Boolean = KEY.matches(key)

    /** Whether [a] beats [b]: later clock; on a tie a tombstone; then the greater device id. */
    fun wins(
        a: SyncRecord,
        b: SyncRecord,
    ): Boolean {
        if (a.modifiedAt != b.modifiedAt) return a.modifiedAt > b.modifiedAt
        if (a.deleted != b.deleted) return a.deleted
        return a.device > b.device
    }

    /** Same register state (clock and presence); payload differences at an equal clock are ignored. */
    private fun sameState(
        a: SyncRecord,
        b: SyncRecord,
    ) = a.modifiedAt == b.modifiedAt && a.deleted == b.deleted

    /**
     * Plans one run.
     *
     * @param local the device's current records, one per key (live items and remembered tombstones)
     * @param remote server records changed since the pull cursor (several per key are allowed)
     * @param base for each key, the clock last agreed with the server
     * @param now the device clock; local clocks in the future are clamped to it
     * @param maxPush how many records this run may push
     * @param tombstoneHorizonMs local tombstones older than this are not pushed unless the server
     *   already knows the key (keeps a first sync from uploading a year of unsubscribes)
     */
    fun plan(
        local: Map<String, SyncRecord>,
        remote: Collection<SyncRecord>,
        base: Map<String, Long>,
        now: Long,
        maxPush: Int = Int.MAX_VALUE,
        tombstoneHorizonMs: Long = Long.MAX_VALUE,
    ): SyncPlan {
        val remoteByKey = HashMap<String, SyncRecord>()
        remote.filter { isValidKey(it.key) }.forEach { r ->
            val seen = remoteByKey[r.key]
            if (seen == null || wins(r, seen)) remoteByKey[r.key] = r
        }
        val newBase = HashMap(base)
        val apply = ArrayList<SyncRecord>()
        val push = ArrayList<SyncRecord>()

        val keys = (local.keys + remoteByKey.keys).filter(::isValidKey).sorted()
        for (key in keys) {
            val l = local[key]?.let { if (it.modifiedAt > now) it.copy(modifiedAt = now) else it }
            val r = remoteByKey[key]
            when {
                r != null && l == null -> {
                    apply += r
                    newBase[key] = r.modifiedAt
                }

                r != null && l != null ->
                    when {
                        sameState(l, r) -> newBase[key] = r.modifiedAt
                        wins(r, l) -> {
                            apply += r
                            newBase[key] = r.modifiedAt
                        }
                        else -> push += l
                    }

                l != null -> {
                    val agreed = base[key]
                    val changed = agreed == null || l.modifiedAt > agreed
                    val tooOld = agreed == null && l.deleted && now - l.modifiedAt > tombstoneHorizonMs
                    if (changed && !tooOld) push += l
                }
            }
        }
        // Newest changes first, so a capped run ships what the user did most recently.
        val ordered = push.sortedWith(compareByDescending<SyncRecord> { it.modifiedAt }.thenBy { it.key })
        val capped = if (ordered.size > maxPush) ordered.take(maxPush.coerceAtLeast(0)) else ordered
        return SyncPlan(apply, capped, newBase, ordered.size - capped.size)
    }
}

/** The per-collection base map as text: one `key<TAB>clock` line per key. Damage-tolerant. */
object SyncBaseCodec {
    fun encode(base: Map<String, Long>): String =
        base.entries
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}\t${it.value}" }

    fun decode(text: String?): Map<String, Long> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = HashMap<String, Long>()
        text.lineSequence().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            val key = line.substring(0, tab)
            val clock = line.substring(tab + 1).trim().toLongOrNull() ?: return@forEach
            if (SyncMerge.isValidKey(key)) out[key] = clock
        }
        return out
    }
}

/** Where cloud sync stands for this user, most blocking first. */
enum class CloudSyncAvailability {
    /** Build without accounts (foss) or without a Firebase config. */
    UNSUPPORTED,

    /** Guest or session still restoring. */
    SIGNED_OUT,

    /** Switched off remotely (config/app features.cloudSync = false). */
    DISABLED,

    /** Signed in but the membership does not include sync: show the upsell. */
    LOCKED,

    /** Entitled, but turned off on this device (e.g. after "delete my cloud data"). */
    OFF,

    READY,
}

object CloudSyncGate {
    /** config/app features key that switches cloud sync on or off for everyone. */
    const val REMOTE_SWITCH = "cloudSync"

    fun availability(
        supported: Boolean,
        signedIn: Boolean,
        remoteEnabled: Boolean,
        entitled: Boolean,
        userEnabled: Boolean = true,
    ): CloudSyncAvailability =
        when {
            !supported -> CloudSyncAvailability.UNSUPPORTED
            !signedIn -> CloudSyncAvailability.SIGNED_OUT
            !remoteEnabled -> CloudSyncAvailability.DISABLED
            !entitled -> CloudSyncAvailability.LOCKED
            !userEnabled -> CloudSyncAvailability.OFF
            else -> CloudSyncAvailability.READY
        }

    /** "Delete my cloud data" is offered to any signed-in account that may have synced before. */
    fun canDeleteCloudData(
        availability: CloudSyncAvailability,
        everSynced: Boolean,
    ): Boolean =
        when (availability) {
            CloudSyncAvailability.READY, CloudSyncAvailability.OFF -> true
            CloudSyncAvailability.LOCKED, CloudSyncAvailability.DISABLED -> everSynced
            else -> false
        }

    /** The periodic job skips its run while the deep battery saver is on; "Sync now" still runs. */
    fun shouldRun(
        manual: Boolean,
        batterySaverActive: Boolean,
    ): Boolean = manual || !batterySaverActive
}

/** Limits from docs/cloud-sync.md. */
object SyncLimits {
    const val COMMIT_SIZE = 400
    const val MAX_PUSH_PER_RUN = 2_000
    const val PULL_PAGE = 300
    const val MAX_PULL_PAGES = 20
    const val PULL_OVERLAP_MS = 2 * 60 * 1000L
    const val TOMBSTONE_HORIZON_MS = 180L * 24 * 60 * 60 * 1000
    const val MAX_SUBSCRIPTIONS = 5_000
    const val PERIOD_HOURS = 6L
    const val SCHEMA_VERSION = 1L
    const val NAME_MAX = 300
    const val AVATAR_MAX = 1000

    // Part 2 collections: per-run push caps and item caps.
    const val MAX_PLAYLISTS = 500
    const val MAX_ENTRIES_PER_PLAYLIST = 1_000
    const val MAX_PLAYLIST_PUSH = 200
    const val MAX_ENTRY_PUSH = 1_000
    const val MAX_LIKED = 5_000
    const val MAX_LIKED_PUSH = 1_000
    const val MAX_HISTORY = 2_000
    const val MAX_HISTORY_PUSH = 50
    const val MAX_SETTINGS_PUSH = 100

    /** A first pull of one collection reads at most this many pages before trying again next run. */
    const val MAX_FULL_PULL_PAGES = 40

    /** Own tombstones older than this (server time) are deleted, at most once a day. */
    const val TOMBSTONE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    const val GC_INTERVAL_MS = 24L * 60 * 60 * 1000
    const val GC_MAX_DELETES = 300

    /** "Delete my cloud data": pages of this many docs, each deleted in one commit. */
    const val DELETE_PAGE = 300
    const val DELETE_MAX_PAGES = 200
}

/** Which collections this build syncs, and the one-off full pulls a newly enabled one needs. */
object CloudSyncCollections {
    /** In push order: a playlist's meta before its entries. */
    val ALL: List<String> =
        listOf(
            SubscriptionSyncMapper.COLLECTION,
            PlaylistSyncMapper.PLAYLISTS,
            PlaylistSyncMapper.ENTRIES,
            LikeSyncMapper.COLLECTION,
            HistorySyncMapper.COLLECTION,
            SettingsSyncMapper.COLLECTION,
        )

    /**
     * Collections to pull in full this run. The first sync (no cursor yet) reads everything through
     * the cursor pull; afterwards a collection this device has never pulled whole (added by an app
     * update, while the cursor already moved past older docs of it) gets one full pull.
     */
    fun toFullPull(
        fullyPulled: Set<String>,
        cursorKnown: Boolean,
    ): List<String> = if (!cursorKnown) emptyList() else ALL.filter { it !in fullyPulled }

    /** Before part 2 only subscriptions synced, so a device with a cursor already holds those. */
    fun legacyFullyPulled(cursorKnown: Boolean): Set<String> = if (cursorKnown) setOf(SubscriptionSyncMapper.COLLECTION) else emptySet()
}

/** A tombstone doc found on the server, for garbage collection. */
data class ServerTombstone(
    val path: String,
    /** Server write time (epoch ms), or 0 when unknown. */
    val at: Long,
    val modifiedAt: Long,
    val deleted: Boolean,
)

object SyncHousekeeping {
    fun gcDue(
        lastGcAt: Long,
        now: Long,
    ): Boolean = lastGcAt <= 0L || now - lastGcAt >= SyncLimits.GC_INTERVAL_MS || lastGcAt > now

    /** Tombstones older than [SyncLimits.TOMBSTONE_TTL_MS] by server time (client clock if unknown). */
    fun expired(
        docs: List<ServerTombstone>,
        now: Long,
        max: Int = SyncLimits.GC_MAX_DELETES,
    ): List<String> =
        docs
            .filter { it.deleted && now - (if (it.at > 0) it.at else it.modifiedAt) > SyncLimits.TOMBSTONE_TTL_MS }
            .take(max)
            .map { it.path }

    /** Prunes an agreed base to the keys still worth remembering. */
    fun pruneBase(
        base: Map<String, Long>,
        keep: Set<String>,
    ): Map<String, Long> = if (base.keys.all { it in keep }) base else base.filterKeys { it in keep }
}
