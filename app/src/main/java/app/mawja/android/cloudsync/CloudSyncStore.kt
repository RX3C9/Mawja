/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.cloudsync

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/** How the last run ended, for the account page. */
enum class CloudSyncOutcome(
    val id: String,
) {
    NEVER("never"),
    OK("ok"),
    OFFLINE("offline"),
    NOT_ALLOWED("not_allowed"),
    FAILED("failed"),
    ;

    companion object {
        fun of(id: String?): CloudSyncOutcome = entries.firstOrNull { it.id == id } ?: NEVER
    }
}

/**
 * Device-local sync state in SharedPreferences `mawja_cloud_sync` (readable by cold workers):
 * install id, which account it belongs to, pull cursor, per-collection base, last result.
 */
class CloudSyncStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("mawja_cloud_sync", Context.MODE_PRIVATE)

    val deviceId: String
        get() =
            prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { id ->
                prefs.edit { putString(KEY_DEVICE, id) }
            }

    /**
     * Cursor and bases belong to one account. A different user signing in on this device starts
     * from scratch (a full pull, then a push of what is not on their server yet).
     */
    fun bindTo(uid: String) {
        if (prefs.getString(KEY_UID, null) == uid) return
        prefs.edit {
            serverStateKeys().forEach { remove(it) }
            remove(KEY_ENABLED)
            putString(KEY_UID, uid)
        }
    }

    private fun serverStateKeys() =
        prefs.all.keys.filter {
            it.startsWith(PREFIX_BASE) || it.startsWith(PREFIX_ORDER) || it == KEY_CURSOR || it == KEY_PULLED || it == KEY_LAST_GC
        }

    /**
     * After "delete my cloud data": the server holds nothing, so forget what was agreed with it
     * (cursor, bases, order keys, full pulls). Turning sync back on then uploads everything again.
     */
    fun forgetServerState() = prefs.edit { serverStateKeys().forEach { remove(it) } }

    /** The user's own switch on this device (on unless they turned sync off, e.g. by deleting their cloud data). */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** Collections this device has pulled whole at least once (see [CloudSyncCollections.toFullPull]). */
    fun fullyPulled(): Set<String> =
        prefs.getStringSet(KEY_PULLED, null)?.toSet() ?: CloudSyncCollections.legacyFullyPulled(cursor != null)

    fun markFullyPulled(collections: Set<String>) {
        if (collections.isEmpty()) return
        prefs.edit { putStringSet(KEY_PULLED, fullyPulled() + collections) }
    }

    /** Entry key -> fractional order key last used for [collection] (playlist entries). */
    fun orderKeys(collection: String): Map<String, String> = SyncTextMapCodec.decode(prefs.getString(PREFIX_ORDER + collection, null), OrderKeys::isValid)

    fun saveOrderKeys(
        collection: String,
        keys: Map<String, String>,
    ) = prefs.edit { putString(PREFIX_ORDER + collection, SyncTextMapCodec.encode(keys)) }

    var lastGcAt: Long
        get() = prefs.getLong(KEY_LAST_GC, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_GC, value) }

    /** The server time (RFC 3339) of the newest item pulled, or null before the first pull. */
    var cursor: String?
        get() = prefs.getString(KEY_CURSOR, null)
        set(value) = prefs.edit { putString(KEY_CURSOR, value) }

    fun base(collection: String): Map<String, Long> = SyncBaseCodec.decode(prefs.getString(PREFIX_BASE + collection, null))

    fun saveBase(
        collection: String,
        base: Map<String, Long>,
    ) = prefs.edit { putString(PREFIX_BASE + collection, SyncBaseCodec.encode(base)) }

    val lastSyncAt: Long get() = prefs.getLong(KEY_LAST_AT, 0L)

    val lastOutcome: CloudSyncOutcome get() = CloudSyncOutcome.of(prefs.getString(KEY_LAST_OUTCOME, null))

    fun recordRun(
        outcome: CloudSyncOutcome,
        at: Long,
    ) = prefs.edit {
        putString(KEY_LAST_OUTCOME, outcome.id)
        if (outcome == CloudSyncOutcome.OK) putLong(KEY_LAST_AT, at)
    }

    private companion object {
        const val KEY_DEVICE = "device_id"
        const val KEY_UID = "uid"
        const val KEY_CURSOR = "cursor"
        const val PREFIX_BASE = "base_"
        const val PREFIX_ORDER = "order_"
        const val KEY_PULLED = "fully_pulled"
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_GC = "last_gc_at"
        const val KEY_LAST_AT = "last_sync_at"
        const val KEY_LAST_OUTCOME = "last_outcome"
    }
}
