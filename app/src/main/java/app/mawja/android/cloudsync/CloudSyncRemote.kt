/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.cloudsync

import app.mawja.android.account.FirestoreRest
import java.time.Instant

/**
 * Cloud sync over the Firestore REST API, as the signed-in user (docs/cloud-sync.md):
 * `users/{uid}/syncData/{c}_{k}` with fields c, k, m, dev, del, v, at (server time), d.
 */
internal class CloudSyncRemote(
    private val rest: FirestoreRest,
    private val uid: String,
) {
    /** Changed records grouped by collection, and the newest server time seen (the next cursor). */
    data class Pulled(
        val records: Map<String, List<SyncRecord>>,
        val cursor: Instant?,
        val documents: Int,
    )

    private val parent get() = "users/$uid"

    /** Everything written at or after [since] (all items when null), paged on (at, name). */
    suspend fun pull(since: Instant?): Pulled {
        val byCollection = HashMap<String, MutableList<SyncRecord>>()
        var newest: Instant? = null
        var after: List<Any?>? = null
        var count = 0
        val where = since?.let { rest.fieldAtLeast(FIELD_AT, it) }
        repeat(SyncLimits.MAX_PULL_PAGES) {
            val docs = rest.runQuery(parent, COLLECTION_ID, where, listOf(FIELD_AT, "__name__"), after, SyncLimits.PULL_PAGE)
            count += docs.size
            docs.forEach { doc ->
                val at = doc.fields[FIELD_AT] as? Instant
                if (at != null && newest?.let { at.isAfter(it) } != false) newest = at
                val (collection, record) = decode(doc.fields) ?: return@forEach
                byCollection.getOrPut(collection) { ArrayList() } += record
            }
            if (docs.size < SyncLimits.PULL_PAGE) return Pulled(byCollection, newest, count)
            val last = docs.last()
            after = listOf(last.fields[FIELD_AT], FirestoreRest.Ref(rest.name(last.path)))
        }
        return Pulled(byCollection, newest, count)
    }

    /** Writes [records] of [collection] in commits of [SyncLimits.COMMIT_SIZE]; [onCommitted] after each. */
    suspend fun push(
        collection: String,
        records: List<SyncRecord>,
        onCommitted: (List<SyncRecord>) -> Unit,
    ) {
        records.chunked(SyncLimits.COMMIT_SIZE).forEach { chunk ->
            rest.commit(chunk.map { write(collection, it) })
            onCommitted(chunk)
        }
    }

    private fun write(
        collection: String,
        r: SyncRecord,
    ): FirestoreRest.Update =
        FirestoreRest.Update(
            path = "$parent/$COLLECTION_ID/${collection}_${r.key}",
            fields =
                mapOf(
                    "c" to collection,
                    "k" to r.key,
                    "m" to r.modifiedAt,
                    "dev" to r.device.take(64),
                    "del" to r.deleted,
                    "v" to SyncLimits.SCHEMA_VERSION,
                    "d" to if (r.deleted) emptyMap() else r.data,
                ),
            serverTimeFields = listOf(FIELD_AT),
        )

    companion object {
        const val COLLECTION_ID = "syncData"
        const val FIELD_AT = "at"

        /** A stored item as (collection, record); null for anything malformed or foreign. */
        fun decode(fields: Map<String, Any?>): Pair<String, SyncRecord>? {
            val collection = fields["c"] as? String ?: return null
            val key = fields["k"] as? String ?: return null
            val modified = fields["m"] as? Long ?: return null
            if (!SyncMerge.isValidKey(key) || modified <= 0) return null
            val deleted = fields["del"] as? Boolean ?: return null
            val device = fields["dev"] as? String ?: ""

            @Suppress("UNCHECKED_CAST")
            val data = (fields["d"] as? Map<String, Any?>).orEmpty()
            return collection to SyncRecord(key, modified, deleted, device, if (deleted) emptyMap() else data)
        }
    }
}
