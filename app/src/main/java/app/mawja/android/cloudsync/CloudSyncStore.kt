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
            prefs.all.keys.filter { it.startsWith(PREFIX_BASE) || it == KEY_CURSOR }.forEach { remove(it) }
            putString(KEY_UID, uid)
        }
    }

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
        const val KEY_LAST_AT = "last_sync_at"
        const val KEY_LAST_OUTCOME = "last_outcome"
    }
}
