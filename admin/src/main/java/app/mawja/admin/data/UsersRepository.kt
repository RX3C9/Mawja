/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.data

import app.mawja.admin.core.UserQuery
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.util.Date

/** Users: search, browse, and audited staff edits written straight to users/{uid}. */
class UsersRepository(
    private val fb: AdminFirebase,
) {
    private val db get() = fb.db

    /** All matches for a search (an email can belong to more than one doc in odd cases). */
    suspend fun search(input: String): List<UserRecord> {
        val q = UserQuery.parse(input) ?: return emptyList()
        return when (q) {
            is UserQuery.Email -> {
                val exact = db.collection("users").whereEqualTo("email", q.email).limit(10).get().await().documents
                val typed = if (exact.isEmpty() && input.trim() != q.email) {
                    db.collection("users").whereEqualTo("email", input.trim()).limit(10).get().await().documents
                } else {
                    emptyList()
                }
                (exact + typed).map(::record)
            }
            is UserQuery.MawjaId -> listOfNotNull(viaIndex("mawjaIds/${q.id}"))
            is UserQuery.Username -> listOfNotNull(viaIndex("usernames/${q.name}") ?: byUid(input.trim())?.takeIf { it.exists })
            is UserQuery.Uid -> listOfNotNull(byUid(q.uid)?.takeIf { it.exists } ?: viaIndex("usernames/${q.uid.lowercase()}") ?: byUid(q.uid))
        }
    }

    private suspend fun viaIndex(path: String): UserRecord? {
        val uid = db.document(path).get().await().getString("uid") ?: return null
        return byUid(uid)
    }

    /** The user's doc; a record with exists = false when there is none yet (still editable). */
    suspend fun byUid(uid: String): UserRecord? {
        if (uid.isBlank() || '/' in uid) return null
        val doc = db.document("users/$uid").get().await()
        return if (doc.exists()) record(doc) else UserRecord(uid = uid, exists = false)
    }

    /** Newest users first, or one tier / status (filtered lists page by document id). */
    suspend fun browse(
        filterField: String?,
        filterValue: String?,
        after: DocumentSnapshot?,
    ): Page<UserRecord> {
        var query: Query =
            if (filterField != null && filterValue != null) {
                db.collection("users").whereEqualTo(filterField, filterValue)
            } else {
                db.collection("users").orderBy("createdAt", Query.Direction.DESCENDING)
            }
        if (after != null) query = query.startAfter(after)
        val docs = query.limit(PAGE.toLong()).get().await().documents
        return Page(docs.map(::record), docs.lastOrNull(), docs.size < PAGE)
    }

    suspend fun redemptions(uid: String): List<Redemption> =
        db
            .collection("redemptions")
            .whereEqualTo("uid", uid)
            .limit(50)
            .get()
            .await()
            .documents
            .map(CodesRepository::redemption)
            .sortedByDescending { it.createdAt }

    suspend fun devices(uid: String): List<Device> =
        db.collection("users/$uid/devices").limit(20).get().await().documents.map { d ->
            Device(d.id, d.data.orEmpty().mapValues { (_, v) -> display(v) })
        }

    suspend fun setTier(
        uid: String,
        tier: String,
        until: Date?,
        previous: String,
    ) = write(uid, "user.tier", mapOf("tier" to tier, "until" to until?.time, "previous" to previous)) {
        mapOf(
            "tier" to tier,
            "tierUntil" to until?.let { Timestamp(it) },
            "tierSource" to "admin",
            "tierUpdatedAt" to FieldValue.serverTimestamp(),
            "tierUpdatedBy" to fb.uid,
        )
    }

    suspend fun setStatus(
        uid: String,
        status: String,
        reason: String,
    ) = write(uid, "user.status", mapOf("status" to status, "reason" to reason)) {
        mapOf("status" to status, "banReason" to reason.take(500), "statusUpdatedAt" to FieldValue.serverTimestamp(), "statusBy" to fb.uid)
    }

    suspend fun setBadges(
        uid: String,
        badges: List<String>,
        verified: Boolean,
    ) = write(uid, "user.badges", mapOf("badges" to badges, "verified" to verified)) {
        mapOf("badges" to badges, "verified" to verified)
    }

    suspend fun setNote(
        uid: String,
        note: String,
    ) = write(uid, "user.note", mapOf("length" to note.length)) { mapOf("staffNote" to note.take(1000)) }

    /** Deletes users/{uid}/syncData/* in the batch that stamps syncResetAt. Returns how many docs. */
    suspend fun resetSync(uid: String): Int {
        val docs = db.collection("users/$uid/syncData").limit(400).get().await().documents
        val batch = db.batch()
        val auditId = batch.audit(fb, "user.sync_reset", "users/$uid", mapOf("docs" to docs.size))
        batch.set(db.document("users/$uid"), mapOf("syncResetAt" to FieldValue.serverTimestamp(), "auditId" to auditId), SetOptions.merge())
        docs.forEach { batch.delete(it.reference) }
        batch.commit().await()
        return docs.size
    }

    private suspend fun write(
        uid: String,
        action: String,
        details: Map<String, Any?>,
        fields: () -> Map<String, Any?>,
    ) {
        val batch = db.batch()
        val auditId = batch.audit(fb, action, "users/$uid", details)
        batch.set(db.document("users/$uid"), fields() + ("auditId" to auditId), SetOptions.merge())
        batch.commit().await()
    }

    companion object {
        const val PAGE = 25

        fun record(d: DocumentSnapshot) =
            UserRecord(
                uid = d.id,
                exists = d.exists(),
                email = d.str("email"),
                displayName = d.str("displayName"),
                username = d.str("username"),
                mawjaId = d.str("mawjaId"),
                photoUrl = d.str("photoUrl"),
                locale = d.str("locale"),
                tier = d.str("tier") ?: "free",
                tierUntil = d.date("tierUntil") ?: (d.get("tierExpiresAt") as? Number)?.let { Date(it.toLong()) },
                tierSource = d.str("tierSource"),
                status = d.str("status") ?: "active",
                banReason = d.str("banReason"),
                badges = d.strings("badges"),
                verified = d.getBoolean("verified") == true,
                staffNote = d.str("staffNote").orEmpty(),
                createdAt = d.date("createdAt"),
                lastSeenAt = d.date("lastSeenAt"),
            )

        fun display(v: Any?): String =
            when (v) {
                null -> "—"
                is Timestamp -> v.toDate().toString()
                is Map<*, *> -> v.entries.joinToString(", ") { "${it.key}: ${display(it.value)}" }
                is List<*> -> v.joinToString(", ") { display(it) }
                else -> v.toString()
            }
    }
}
