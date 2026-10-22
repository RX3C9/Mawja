/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.data

import app.mawja.codes.MawjaCodes
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import java.util.Date

/** Membership codes (docs/codes.md), written straight to Firestore and checked by the rules. */
class CodesRepository(
    private val fb: AdminFirebase,
) {
    private val db get() = fb.db

    /**
     * Generates [count] codes. Only each code's hash (doc id) and last group are stored; the codes
     * themselves exist only in the returned list, so they must be copied, shared or exported now.
     * Writes go in small batches (the rules may read the admins doc for every code).
     */
    suspend fun generate(
        tier: String,
        days: Int,
        count: Int,
        maxUses: Int,
        expiresAt: Date?,
        note: String,
    ): GeneratedBatch {
        val uid = fb.uid
        val codes = MawjaCodes.generateBatch(count)
        val batchRef = db.collection("codeBatches").document()
        val written = mutableListOf<String>()
        val expiry = expiresAt?.let { Timestamp(it) }
        val cleanNote = note.trim().take(200)
        val effectiveDays = if (tier == "lifetime") 0 else days
        val result = GeneratedBatch(batchRef.id, emptyList(), count, tier, effectiveDays, maxUses, expiresAt, cleanNote)
        try {
            codes.chunked(CODES_PER_WRITE).forEachIndexed { index, chunk ->
                val batch = db.batch()
                if (index == 0) {
                    batch.set(
                        batchRef,
                        mapOf(
                            "tier" to tier, "days" to effectiveDays, "count" to count, "maxUses" to maxUses, "expiresAt" to expiry,
                            "note" to cleanNote, "createdBy" to uid, "createdAt" to FieldValue.serverTimestamp(), "revoked" to false,
                        ),
                    )
                    batch.audit(fb, "code.batch.create", batchRef.id, mapOf("tier" to tier, "days" to effectiveDays, "count" to count, "maxUses" to maxUses))
                }
                chunk.forEach { code ->
                    batch.set(
                        db.document("codes/${MawjaCodes.hash(code)}"),
                        mapOf(
                            "tier" to tier, "days" to effectiveDays, "maxUses" to maxUses, "used" to 0, "expiresAt" to expiry, "revoked" to false,
                            "createdBy" to uid, "createdAt" to FieldValue.serverTimestamp(), "batchId" to batchRef.id, "note" to cleanNote,
                            "hint" to MawjaCodes.hint(code),
                        ),
                    )
                }
                batch.commit().await()
                written += chunk
            }
        } catch (e: Exception) {
            return result.copy(codes = written, error = e.message ?: e.javaClass.simpleName)
        }
        return result.copy(codes = written)
    }

    suspend fun batches(after: DocumentSnapshot?): Page<CodeBatch> {
        var q = db.collection("codeBatches").orderBy("createdAt", Query.Direction.DESCENDING)
        if (after != null) q = q.startAfter(after)
        val docs = q.limit(PAGE.toLong()).get().await().documents
        return Page(docs.map(::batch), docs.lastOrNull(), docs.size < PAGE)
    }

    suspend fun codesIn(batchId: String): List<CodeEntry> =
        db
            .collection("codes")
            .whereEqualTo("batchId", batchId)
            .limit(500)
            .get()
            .await()
            .documents
            .map(::entry)
            .sortedBy { it.hint }

    /** Finds a code a customer reads out (the full code is hashed here, never sent). */
    suspend fun find(code: String): CodeEntry? {
        val hash = MawjaCodes.normalize(code)?.let { MawjaCodes.hash(it) } ?: return null
        val doc = db.document("codes/$hash").get().await()
        return if (doc.exists()) entry(doc) else null
    }

    /** Who activated a code (redemptions of that hash). */
    suspend fun redemptionsOf(hash: String): List<Redemption> =
        db.collection("redemptions").whereEqualTo("codeHash", hash).limit(50).get().await().documents.map(::redemption)

    suspend fun revoke(hash: String) {
        val batch = db.batch()
        batch.update(db.document("codes/$hash"), revokeFields())
        batch.audit(fb, "code.revoke", hash)
        batch.commit().await()
    }

    /** Revokes every unrevoked code of a batch, then the batch itself. */
    suspend fun revokeBatch(batchId: String): Int {
        val open = codesIn(batchId).filterNot { it.revoked }
        open.chunked(CODES_PER_WRITE).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.update(db.document("codes/${it.hash}"), revokeFields()) }
            batch.commit().await()
        }
        val batch = db.batch()
        batch.update(db.document("codeBatches/$batchId"), revokeFields())
        batch.audit(fb, "code.batch.revoke", batchId, mapOf("codes" to open.size))
        batch.commit().await()
        return open.size
    }

    suspend fun recentRedemptions(limit: Long = 30): List<Redemption> =
        db
            .collection("redemptions")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .get()
            .await()
            .documents
            .map(::redemption)

    private fun revokeFields() = mapOf("revoked" to true, "revokedAt" to FieldValue.serverTimestamp(), "revokedBy" to fb.uid)

    companion object {
        const val CODES_PER_WRITE = 8
        const val PAGE = 20

        fun batch(it: DocumentSnapshot) =
            CodeBatch(
                id = it.id,
                tier = it.str("tier") ?: "?",
                days = it.int("days"),
                count = it.int("count"),
                maxUses = (it.get("maxUses") as? Number)?.toInt() ?: 1,
                expiresAt = it.date("expiresAt"),
                note = it.str("note").orEmpty(),
                createdAt = it.date("createdAt"),
                revoked = it.getBoolean("revoked") == true,
            )

        fun entry(it: DocumentSnapshot) =
            CodeEntry(
                hash = it.id,
                hint = it.str("hint").orEmpty(),
                tier = it.str("tier") ?: "?",
                days = it.int("days"),
                used = it.int("used"),
                maxUses = (it.get("maxUses") as? Number)?.toInt() ?: 1,
                revoked = it.getBoolean("revoked") == true,
                expiresAt = it.date("expiresAt"),
                batchId = it.str("batchId").orEmpty(),
            )

        fun redemption(it: DocumentSnapshot) =
            Redemption(
                id = it.id,
                uid = it.str("uid").orEmpty(),
                tier = it.str("tier") ?: "?",
                codeTier = it.str("codeTier") ?: "?",
                days = it.int("days"),
                tierUntil = it.date("tierUntil"),
                createdAt = it.date("createdAt"),
            )
    }
}
