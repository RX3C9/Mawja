/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.data

import android.content.Context
import app.mawja.admin.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

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

    /** The signed-in staff member, read from the ID token's role claim (forced refresh). */
    suspend fun currentStaff(): Staff? {
        if (app == null) return null
        val user = auth.currentUser ?: return null
        val role = user.getIdToken(true).await().claims["role"] as? String ?: return null
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
}
