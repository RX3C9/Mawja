/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.util.Date

/** A private message staff left for this account, shown once when the app opens. */
@Immutable
data class AdminMessage(
    val id: String,
    val text: String,
    val at: Long,
)

/**
 * What the admin app set on `users/{uid}` (Mawja 2.0 moderation): a ban (permanent, or a
 * suspension that may end at [banUntil]), warnings, a mute, locked features, a private message and
 * "sign out of every device". The Firestore rules enforce the server-side parts; the app shows them.
 */
@Immutable
data class Moderation(
    val status: String = STATUS_ACTIVE,
    val banReason: String? = null,
    val banUntil: Long? = null,
    val warnings: Int = 0,
    val mutedUntil: Long? = null,
    val lockedFeatures: Set<String> = emptySet(),
    val message: AdminMessage? = null,
    val signOutAt: Long? = null,
) {
    /** Banned, or suspended with no end or an end still ahead. */
    fun blocked(now: Long): Boolean = status == STATUS_BANNED || (status == STATUS_SUSPENDED && (banUntil == null || banUntil > now))

    /** When the block ends, or null when it never ends on its own. */
    fun blockedUntil(now: Long): Long? = if (status == STATUS_SUSPENDED && banUntil != null && banUntil > now) banUntil else null

    fun muted(now: Long): Boolean = mutedUntil != null && mutedUntil > now

    fun locked(feature: String): Boolean = feature in lockedFeatures

    fun locked(feature: MawjaFeature): Boolean = locked(feature.name.lowercase())

    /** True when staff asked for a sign-out after this device signed in. */
    fun mustSignOut(signedInAt: Long): Boolean = signOutAt != null && signedInAt > 0 && signOutAt > signedInAt

    /** The private message unless this device already showed it. */
    fun unseenMessage(seenId: String?): AdminMessage? = message?.takeIf { it.id != seenId && it.text.isNotBlank() }

    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_SUSPENDED = "suspended"
        const val STATUS_BANNED = "banned"

        /** Lockable without being a membership feature. */
        const val FEATURE_REPORTS = "reports"
        const val FEATURE_CODES = "codes"

        /** Every id the admin app can lock: the membership features plus reports and codes. */
        val LOCKABLE: List<String> = MawjaFeature.entries.map { it.name.lowercase() } + listOf(FEATURE_REPORTS, FEATURE_CODES)

        /** Reads the moderation fields of a users doc as FirestoreRest or the SDK returns them. */
        fun from(doc: Map<*, *>?): Moderation {
            if (doc == null) return Moderation()
            val msg = doc["adminMessage"] as? Map<*, *>
            return Moderation(
                status = (doc["status"] as? String)?.takeIf { it.isNotBlank() } ?: STATUS_ACTIVE,
                banReason = (doc["banReason"] as? String)?.takeIf { it.isNotBlank() },
                banUntil = millis(doc["banUntil"]),
                warnings = (doc["warnings"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
                mutedUntil = millis(doc["mutedUntil"]),
                lockedFeatures = (doc["lockedFeatures"] as? List<*>).orEmpty().filterIsInstance<String>().toSet(),
                message =
                    msg?.let {
                        val id = it["id"] as? String
                        val text = it["text"] as? String
                        if (id.isNullOrBlank() || text.isNullOrBlank()) null else AdminMessage(id, text, millis(it["at"]) ?: 0L)
                    },
                signOutAt = millis(doc["signOutAt"]),
            )
        }

        private fun millis(v: Any?): Long? =
            when (v) {
                is Instant -> v.toEpochMilli()
                is Date -> v.time
                is Number -> v.toLong()
                else -> null
            }
    }
}

/** Account extras a moderated account keeps: none while blocked, minus each locked feature. */
fun Entitlements.withModeration(
    moderation: Moderation,
    now: Long,
): Entitlements =
    when {
        moderation.blocked(now) -> copy(features = emptySet())
        moderation.lockedFeatures.isEmpty() -> this
        else -> copy(features = features.filterNot { moderation.locked(it) }.toSet())
    }
