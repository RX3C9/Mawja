/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.data

import android.content.Context
import app.mawja.admin.BuildConfig
import app.mawja.codes.MawjaCodes
import com.google.firebase.Timestamp
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.util.Date

data class Staff(
    val uid: String,
    val email: String?,
    val role: String,
)

data class UserCard(
    val uid: String,
    val mawjaId: String?,
    val username: String?,
    val email: String?,
    val displayName: String?,
    val tier: String,
    val status: String,
    val badges: List<String>,
)

data class Report(
    val id: String,
    val type: String,
    val message: String,
    val status: String,
)

data class CodeBatch(
    val id: String,
    val tier: String,
    val days: Int,
    val count: Int,
    val maxUses: Int,
    val expiresAt: Date?,
    val note: String,
    val createdAt: Date?,
    val revoked: Boolean,
)

/** One code as staff see it: only its hash and last group are stored, never the code itself. */
data class CodeEntry(
    val hash: String,
    val hint: String,
    val tier: String,
    val days: Int,
    val used: Int,
    val maxUses: Int,
    val revoked: Boolean,
    val expiresAt: Date?,
)

data class Redemption(
    val id: String,
    val uid: String,
    val tier: String,
    val codeTier: String,
    val days: Int,
    val tierUntil: Date?,
    val createdAt: Date?,
)

/** What a generate run produced. [codes] are shown once; [error] is set when it stopped part-way. */
data class GeneratedBatch(
    val batchId: String,
    val codes: List<String>,
    val requested: Int,
    val error: String? = null,
)

/** Thin layer over Firebase. Every write goes through the role-checked `adminAction` function. */
class AdminRepository(
    context: Context,
) {
    val configured: Boolean =
        BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() && BuildConfig.FIREBASE_APP_ID.isNotBlank() &&
            BuildConfig.FIREBASE_API_KEY.isNotBlank()

    private val app: FirebaseApp? =
        if (!configured) {
            null
        } else {
            FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions
                    .Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .build(),
            )
        }

    private val auth get() = FirebaseAuth.getInstance(requireNotNull(app))
    private val db get() = FirebaseFirestore.getInstance(requireNotNull(app))
    private val functions get() = FirebaseFunctions.getInstance(requireNotNull(app), BuildConfig.FUNCTIONS_REGION)

    /**
     * The signed-in staff member: the ID token's role claim (forced refresh), else an admins/{uid}
     * doc with a role (works on the Spark plan, where no function can set claims).
     */
    suspend fun currentStaff(): Staff? {
        if (app == null) return null
        val user = auth.currentUser ?: return null
        val claim = user.getIdToken(true).await().claims["role"] as? String
        val role =
            claim?.takeIf { it in STAFF_ROLES }
                ?: runCatching { db.document("admins/${user.uid}").get().await().getString("role") }.getOrNull()?.takeIf { it in STAFF_ROLES }
                ?: return null
        return Staff(user.uid, user.email, role)
    }

    suspend fun signIn(
        email: String,
        password: String,
    ): Staff? {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
        return currentStaff()
    }

    fun signOut() {
        if (app != null) auth.signOut()
    }

    /** Finds a user by Mawja ID (MW-XXXX-XXXX) or username. */
    suspend fun lookup(query: String): UserCard? {
        val q = query.trim()
        val uid =
            if (q.uppercase().startsWith("MW")) {
                val id = q.uppercase().replace(" ", "")
                db.document("mawjaIds/$id").get().await().getString("uid")
            } else {
                db.document("usernames/${q.lowercase().removePrefix("@")}").get().await().getString("uid")
            } ?: return null
        val doc = db.document("users/$uid").get().await()
        if (!doc.exists()) return null
        @Suppress("UNCHECKED_CAST")
        return UserCard(
            uid = uid,
            mawjaId = doc.getString("mawjaId"),
            username = doc.getString("username"),
            email = doc.getString("email"),
            displayName = doc.getString("displayName"),
            tier = doc.getString("tier") ?: "free",
            status = doc.getString("status") ?: "active",
            badges = (doc.get("badges") as? List<String>).orEmpty(),
        )
    }

    suspend fun openReports(): List<Report> =
        db
            .collection("reports")
            .whereEqualTo("status", "open")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .await()
            .documents
            .map { Report(it.id, it.getString("type") ?: "feedback", it.getString("message").orEmpty(), it.getString("status") ?: "open") }

    suspend fun features(): Map<String, Boolean> {
        val data = db.document("config/features").get().await().data.orEmpty()
        return data.mapValues { (_, v) -> ((v as? Map<*, *>)?.get("enabled") as? Boolean) ?: false }.toSortedMap()
    }

    suspend fun action(payload: Map<String, Any?>) {
        functions.getHttpsCallable("adminAction").call(payload).await()
    }

    // ---- Membership codes (docs/codes.md). Written straight to Firestore, checked by the rules. ----

    /**
     * Generates [count] codes. Only each code's hash (doc id) and last group are stored; the codes
     * themselves exist only in the returned list, so they must be copied or shared right away.
     * Writes go in small batches (the rules may read the admins doc for every code).
     */
    suspend fun generateCodes(
        tier: String,
        days: Int,
        count: Int,
        maxUses: Int,
        expiresAt: Date?,
        note: String,
    ): GeneratedBatch {
        val uid = requireNotNull(auth.currentUser).uid
        val codes = MawjaCodes.generateBatch(count)
        val batchRef = db.collection("codeBatches").document()
        val written = mutableListOf<String>()
        val expiry = expiresAt?.let { Timestamp(it) }
        val cleanNote = note.trim().take(200)
        val effectiveDays = if (tier == "lifetime") 0 else days
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
                    batch.set(db.collection("auditLog").document(), audit(uid, "code.batch.create", batchRef.id, mapOf("tier" to tier, "days" to effectiveDays, "count" to count, "maxUses" to maxUses)))
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
            return GeneratedBatch(batchRef.id, written, count, e.message ?: e.javaClass.simpleName)
        }
        return GeneratedBatch(batchRef.id, written, count)
    }

    suspend fun codeBatches(): List<CodeBatch> =
        db
            .collection("codeBatches")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .await()
            .documents
            .map {
                CodeBatch(
                    id = it.id,
                    tier = it.getString("tier") ?: "?",
                    days = it.getLong("days")?.toInt() ?: 0,
                    count = it.getLong("count")?.toInt() ?: 0,
                    maxUses = it.getLong("maxUses")?.toInt() ?: 1,
                    expiresAt = it.getTimestamp("expiresAt")?.toDate(),
                    note = it.getString("note").orEmpty(),
                    createdAt = it.getTimestamp("createdAt")?.toDate(),
                    revoked = it.getBoolean("revoked") == true,
                )
            }

    suspend fun codesIn(batchId: String): List<CodeEntry> =
        db
            .collection("codes")
            .whereEqualTo("batchId", batchId)
            .limit(500)
            .get()
            .await()
            .documents
            .map {
                CodeEntry(
                    hash = it.id,
                    hint = it.getString("hint").orEmpty(),
                    tier = it.getString("tier") ?: "?",
                    days = it.getLong("days")?.toInt() ?: 0,
                    used = it.getLong("used")?.toInt() ?: 0,
                    maxUses = it.getLong("maxUses")?.toInt() ?: 1,
                    revoked = it.getBoolean("revoked") == true,
                    expiresAt = it.getTimestamp("expiresAt")?.toDate(),
                )
            }.sortedBy { it.hint }

    /** Finds a code a customer reads out (the full code is hashed here, never sent). */
    suspend fun findCode(code: String): CodeEntry? {
        val hash = MawjaCodes.normalize(code)?.let { MawjaCodes.hash(it) } ?: return null
        val doc = db.document("codes/$hash").get().await()
        if (!doc.exists()) return null
        return CodeEntry(
            hash = doc.id,
            hint = doc.getString("hint").orEmpty(),
            tier = doc.getString("tier") ?: "?",
            days = doc.getLong("days")?.toInt() ?: 0,
            used = doc.getLong("used")?.toInt() ?: 0,
            maxUses = doc.getLong("maxUses")?.toInt() ?: 1,
            revoked = doc.getBoolean("revoked") == true,
            expiresAt = doc.getTimestamp("expiresAt")?.toDate(),
        )
    }

    suspend fun revokeCode(hash: String) {
        val uid = requireNotNull(auth.currentUser).uid
        val batch = db.batch()
        batch.update(db.document("codes/$hash"), revokeFields(uid))
        batch.set(db.collection("auditLog").document(), audit(uid, "code.revoke", hash, emptyMap()))
        batch.commit().await()
    }

    /** Revokes every unrevoked code of a batch, then the batch itself. */
    suspend fun revokeBatch(batchId: String) {
        val uid = requireNotNull(auth.currentUser).uid
        val open = codesIn(batchId).filterNot { it.revoked }
        open.chunked(CODES_PER_WRITE).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.update(db.document("codes/${it.hash}"), revokeFields(uid)) }
            batch.commit().await()
        }
        val batch = db.batch()
        batch.update(db.document("codeBatches/$batchId"), revokeFields(uid))
        batch.set(db.collection("auditLog").document(), audit(uid, "code.batch.revoke", batchId, mapOf("codes" to open.size)))
        batch.commit().await()
    }

    suspend fun recentRedemptions(): List<Redemption> =
        db
            .collection("redemptions")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(30)
            .get()
            .await()
            .documents
            .map {
                Redemption(
                    id = it.id,
                    uid = it.getString("uid").orEmpty(),
                    tier = it.getString("tier") ?: "?",
                    codeTier = it.getString("codeTier") ?: "?",
                    days = it.getLong("days")?.toInt() ?: 0,
                    tierUntil = it.getTimestamp("tierUntil")?.toDate(),
                    createdAt = it.getTimestamp("createdAt")?.toDate(),
                )
            }

    private fun revokeFields(uid: String) = mapOf("revoked" to true, "revokedAt" to FieldValue.serverTimestamp(), "revokedBy" to uid)

    private fun audit(
        uid: String,
        action: String,
        target: String,
        details: Map<String, Any?>,
    ) = mapOf("action" to action, "actorUid" to uid, "target" to target, "details" to details, "at" to FieldValue.serverTimestamp(), "source" to "admin-android")

    private companion object {
        val STAFF_ROLES = setOf("owner", "admin", "moderator", "support")
        const val CODES_PER_WRITE = 8
    }
}
