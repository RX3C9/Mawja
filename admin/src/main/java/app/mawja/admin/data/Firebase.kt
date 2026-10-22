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
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.WriteBatch
import java.util.Date

/** The Firebase project the admin app talks to (config from local.properties / CI, never committed JSON). */
class AdminFirebase(
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
                context.applicationContext,
                FirebaseOptions
                    .Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .build(),
            )
        }

    val auth: FirebaseAuth get() = FirebaseAuth.getInstance(requireNotNull(app) { "Firebase is not configured" })
    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance(requireNotNull(app) { "Firebase is not configured" })

    val uid: String get() = requireNotNull(auth.currentUser) { "Signed out" }.uid
}

/**
 * Adds the auditLog entry every staff write needs (backend/firestore.rules `audited`): same batch,
 * same actor, `at` = request time, about [target]. Returns its id for the write's `auditId`.
 */
fun WriteBatch.audit(
    fb: AdminFirebase,
    action: String,
    target: String,
    details: Map<String, Any?> = emptyMap(),
): String {
    val ref = fb.db.collection("auditLog").document()
    set(
        ref,
        mapOf(
            "action" to action,
            "actorUid" to fb.uid,
            "target" to target,
            "details" to details,
            "at" to FieldValue.serverTimestamp(),
            "source" to "admin-android",
        ),
    )
    return ref.id
}

/** One page of a long list; [last] is the cursor for the next page. */
data class Page<T>(
    val items: List<T>,
    val last: DocumentSnapshot?,
    val end: Boolean,
)

internal fun DocumentSnapshot.date(field: String): Date? = runCatching { getTimestamp(field)?.toDate() }.getOrNull()

internal fun DocumentSnapshot.int(field: String): Int = (get(field) as? Number)?.toInt() ?: 0

internal fun DocumentSnapshot.str(field: String): String? = get(field) as? String

internal fun DocumentSnapshot.strings(field: String): List<String> = (get(field) as? List<*>).orEmpty().filterIsInstance<String>()

/** What went wrong, in a form the UI can turn into a translated message. */
enum class Problem { DENIED, OFFLINE, NOT_FOUND, INDEX, INVALID, UNKNOWN }

fun Throwable.problem(): Problem =
    when ((this as? FirebaseFirestoreException)?.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED, FirebaseFirestoreException.Code.UNAUTHENTICATED -> Problem.DENIED
        FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> Problem.OFFLINE
        FirebaseFirestoreException.Code.NOT_FOUND -> Problem.NOT_FOUND
        FirebaseFirestoreException.Code.FAILED_PRECONDITION -> Problem.INDEX
        FirebaseFirestoreException.Code.INVALID_ARGUMENT -> Problem.INVALID
        else -> if (this is IllegalArgumentException) Problem.INVALID else Problem.UNKNOWN
    }
