/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.data

import app.mawja.admin.core.Component
import app.mawja.admin.core.DailyBuckets
import app.mawja.admin.core.RemoteConfig
import app.mawja.admin.core.Tiers
import com.google.firebase.Timestamp
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.TimeZone

/** Live counts with count() aggregation (one billed read per 1000 docs) and two 14-day charts. */
class DashboardRepository(
    private val fb: AdminFirebase,
) {
    private val db get() = fb.db

    private suspend fun count(q: Query): Long? = runCatching { q.count().get(AggregateSource.SERVER).await().count }.getOrNull()

    suspend fun load(includeAudit: Boolean): DashboardData =
        coroutineScope {
            val now = System.currentTimeMillis()
            val d7 = Timestamp(Date(now - 7 * DAY))
            val d30 = Timestamp(Date(now - 30 * DAY))
            val d14 = Timestamp(Date(now - 14 * DAY))
            val users = db.collection("users")
            val total = async { count(users) }
            val tiers = Tiers.PAID.associateWith { t -> async { count(users.whereEqualTo("tier", t)) } }
            val timed = async { count(users.whereGreaterThan("tierUntil", Timestamp(Date(now)))) }
            val banned = async { count(users.whereEqualTo("status", "banned")) }
            val suspended = async { count(users.whereEqualTo("status", "suspended")) }
            val codes7 = async { count(db.collection("codes").whereGreaterThanOrEqualTo("createdAt", d7)) }
            val codes30 = async { count(db.collection("codes").whereGreaterThanOrEqualTo("createdAt", d30)) }
            val used7 = async { count(db.collection("redemptions").whereGreaterThanOrEqualTo("createdAt", d7)) }
            val used30 = async { count(db.collection("redemptions").whereGreaterThanOrEqualTo("createdAt", d30)) }
            val open = async { count(db.collection("reports").whereEqualTo("status", "open")) }
            val staff = async { count(db.collection("admins")) }
            val activations =
                async {
                    runCatching {
                        db.collection("redemptions").whereGreaterThanOrEqualTo("createdAt", d14).limit(2000).get().await().documents
                    }.getOrDefault(emptyList())
                }
            val batches =
                async {
                    runCatching {
                        db.collection("codeBatches").whereGreaterThanOrEqualTo("createdAt", d14).limit(500).get().await().documents
                    }.getOrDefault(emptyList())
                }
            val audit =
                async {
                    if (!includeAudit) {
                        emptyList<AuditEntry>()
                    } else {
                        runCatching {
                            db.collection("auditLog").orderBy("at", Query.Direction.DESCENDING).limit(6).get().await().documents.map(AuditRepository::entry)
                        }.getOrDefault(emptyList())
                    }
                }
            val zone = TimeZone.getDefault()
            val act = activations.await()
            DashboardData(
                users = total.await(),
                tierCounts = tiers.mapValues { it.value.await() },
                activeTimed = timed.await(),
                banned = banned.await(),
                suspended = suspended.await(),
                codes7 = codes7.await(),
                codes30 = codes30.await(),
                used7 = used7.await(),
                used30 = used30.await(),
                openReports = open.await(),
                staff = staff.await(),
                activationsByDay = DailyBuckets.count(act.mapNotNull { d -> d.date("createdAt")?.time?.let { it to 1 } }, 14, now, zone),
                codesByDay = DailyBuckets.count(batches.await().mapNotNull { d -> d.date("createdAt")?.time?.let { it to d.int("count") } }, 14, now, zone),
                recentActivations = act.map(CodesRepository::redemption).sortedByDescending { it.createdAt }.take(5),
                recentAudit = audit.await(),
                loadedAt = Date(now),
            )
        }

    private companion object {
        const val DAY = 86_400_000L
    }
}

/** In-app messages: announcements/{id}. Never deleted; switched off with `active`. */
class AnnouncementsRepository(
    private val fb: AdminFirebase,
) {
    private val db get() = fb.db

    suspend fun list(after: DocumentSnapshot?): Page<Announcement> {
        var q = db.collection("announcements").orderBy("startAt", Query.Direction.DESCENDING)
        if (after != null) q = q.startAfter(after)
        val docs = q.limit(PAGE.toLong()).get().await().documents
        return Page(docs.map(::announcement), docs.lastOrNull(), docs.size < PAGE)
    }

    /** Creates ([a].id blank) or updates an announcement, with its audit entry. Returns the id. */
    suspend fun save(a: Announcement): String {
        val ref = if (a.id.isBlank()) db.collection("announcements").document() else db.document("announcements/${a.id}")
        val creating = a.id.isBlank()
        val batch = db.batch()
        val auditId =
            batch.audit(
                fb,
                if (creating) "announcement.create" else "announcement.update",
                "announcements/${ref.id}",
                mapOf("title" to a.title.take(60), "active" to a.active, "targetTier" to a.targetTier, "locale" to a.locale),
            )
        val fields =
            mutableMapOf<String, Any?>(
                "title" to a.title.trim(),
                "body" to a.body.trim(),
                "targetTier" to a.targetTier,
                "locale" to a.locale,
                "kind" to a.kind,
                "ctaLabel" to a.ctaLabel.trim(),
                "ctaUrl" to a.ctaUrl.trim(),
                "active" to a.active,
                "startAt" to Timestamp(a.startAt ?: Date()),
                "endAt" to a.endAt?.let { Timestamp(it) },
                "updatedBy" to fb.uid,
                "updatedAt" to FieldValue.serverTimestamp(),
                "auditId" to auditId,
            )
        if (creating) {
            fields["createdBy"] = fb.uid
            fields["createdAt"] = FieldValue.serverTimestamp()
            batch.set(ref, fields)
        } else {
            batch.update(ref, fields)
        }
        batch.commit().await()
        return ref.id
    }

    companion object {
        const val PAGE = 20

        fun announcement(d: DocumentSnapshot) =
            Announcement(
                id = d.id,
                title = d.str("title").orEmpty(),
                body = d.str("body").orEmpty(),
                targetTier = d.str("targetTier"),
                locale = d.str("locale"),
                kind = d.str("kind") ?: "banner",
                ctaLabel = d.str("ctaLabel").orEmpty(),
                ctaUrl = d.str("ctaUrl").orEmpty(),
                active = d.getBoolean("active") ?: true,
                startAt = d.date("startAt"),
                endAt = d.date("endAt"),
                createdBy = d.str("createdBy"),
            )
    }
}

/** Remote config and feature flags: config/{app|downloader|admin}. */
class ConfigRepository(
    private val fb: AdminFirebase,
) {
    private val db get() = fb.db

    data class Loaded(
        val config: RemoteConfig,
        val updatedAt: Date?,
        val updatedBy: String?,
    )

    suspend fun load(component: Component): Loaded {
        val doc = db.document("config/${component.id}").get().await()
        return Loaded(RemoteConfig.from(doc.data), doc.date("updatedAt"), doc.str("updatedBy"))
    }

    /** Writes only what changed (maps whole, so removed flags disappear). */
    suspend fun save(
        component: Component,
        old: RemoteConfig,
        new: RemoteConfig,
    ) {
        val changes = new.changesFrom(old)
        if (changes.isEmpty()) return
        val batch = db.batch()
        val auditId = batch.audit(fb, "config.edit", "config/${component.id}", mapOf("keys" to changes.keys.sorted()))
        batch.set(
            db.document("config/${component.id}"),
            changes + mapOf("updatedAt" to FieldValue.serverTimestamp(), "updatedBy" to fb.uid, "auditId" to auditId),
            SetOptions.mergeFields(changes.keys.toList() + listOf("updatedAt", "updatedBy", "auditId")),
        )
        batch.commit().await()
    }
}

/** Feedback and abuse reports inbox. */
class ReportsRepository(
    private val fb: AdminFirebase,
) {
    private val db get() = fb.db

    /** One status (no composite index needed: sorted here) or all, newest first. */
    suspend fun list(
        status: String?,
        after: DocumentSnapshot?,
    ): Page<Report> {
        var q: Query =
            if (status != null) {
                db.collection("reports").whereEqualTo("status", status)
            } else {
                db.collection("reports").orderBy("createdAt", Query.Direction.DESCENDING)
            }
        if (after != null) q = q.startAfter(after)
        val docs = q.limit(PAGE.toLong()).get().await().documents
        val items = docs.map(::report).let { if (status != null) it.sortedByDescending { r -> r.createdAt } else it }
        return Page(items, docs.lastOrNull(), docs.size < PAGE)
    }

    suspend fun update(
        id: String,
        status: String,
        assignee: String?,
        note: String,
        reply: String,
    ) {
        val batch = db.batch()
        val auditId = batch.audit(fb, "report.update", "reports/$id", mapOf("status" to status, "assignee" to assignee))
        val fields =
            mutableMapOf<String, Any?>(
                "status" to status,
                "assignee" to assignee,
                "note" to note.take(2000),
                "reply" to reply.take(2000),
                "updatedAt" to FieldValue.serverTimestamp(),
                "updatedBy" to fb.uid,
                "auditId" to auditId,
            )
        if (status == "resolved" || status == "dismissed") fields["resolvedBy"] = fb.uid
        batch.update(db.document("reports/$id"), fields)
        batch.commit().await()
    }

    companion object {
        const val PAGE = 25
        private val KNOWN = setOf("uid", "type", "message", "status", "assignee", "note", "reply", "createdAt", "updatedAt", "updatedBy", "resolvedBy", "auditId")

        fun report(d: DocumentSnapshot) =
            Report(
                id = d.id,
                uid = d.str("uid"),
                type = d.str("type") ?: "feedback",
                message = d.str("message").orEmpty(),
                status = d.str("status") ?: "open",
                assignee = d.str("assignee"),
                note = d.str("note").orEmpty(),
                reply = d.str("reply").orEmpty(),
                createdAt = d.date("createdAt"),
                updatedAt = d.date("updatedAt"),
                extra = d.data.orEmpty().filterKeys { it !in KNOWN }.mapValues { (_, v) -> UsersRepository.display(v) },
            )
    }
}

/** auditLog, newest first (filters run on the loaded pages: no composite indexes needed). */
class AuditRepository(
    private val fb: AdminFirebase,
) {
    suspend fun list(after: DocumentSnapshot?): Page<AuditEntry> {
        var q = fb.db.collection("auditLog").orderBy("at", Query.Direction.DESCENDING)
        if (after != null) q = q.startAfter(after)
        val docs = q.limit(PAGE.toLong()).get().await().documents
        return Page(docs.map(::entry), docs.lastOrNull(), docs.size < PAGE)
    }

    companion object {
        const val PAGE = 50

        fun entry(d: DocumentSnapshot) =
            AuditEntry(
                id = d.id,
                action = d.str("action") ?: "?",
                actorUid = d.str("actorUid") ?: d.str("adminUid").orEmpty(),
                target = d.str("target").orEmpty(),
                details =
                    ((d.get("details") as? Map<*, *>) ?: (d.get("after") as? Map<*, *>)).orEmpty()
                        .entries.associate { (k, v) -> k.toString() to UsersRepository.display(v) },
                at = d.date("at"),
                source = d.str("source") ?: "functions",
            )
    }
}

/** Staff roles in admins/{uid}; the owner edits them, owners themselves are made in the console. */
class StaffRepository(
    private val fb: AdminFirebase,
) {
    suspend fun list(): List<StaffMember> =
        fb.db.collection("admins").limit(200).get().await().documents.map { d ->
            StaffMember(d.id, d.str("role") ?: "?", d.str("email"), d.str("note").orEmpty(), d.date("updatedAt"))
        }.sortedWith(compareBy<StaffMember>({ ROLE_ORDER.indexOf(it.role).let { i -> if (i < 0) 99 else i } }, { it.email ?: it.uid }))

    suspend fun setRole(
        uid: String,
        role: String,
        email: String?,
        note: String,
    ) {
        val batch = fb.db.batch()
        val auditId = batch.audit(fb, "staff.set", "admins/$uid", mapOf("role" to role))
        val fields =
            mutableMapOf<String, Any?>("role" to role, "note" to note.take(500), "updatedAt" to FieldValue.serverTimestamp(), "updatedBy" to fb.uid, "auditId" to auditId)
        if (!email.isNullOrBlank()) fields["email"] = email.trim().take(200)
        batch.set(fb.db.document("admins/$uid"), fields, SetOptions.merge())
        batch.commit().await()
    }

    private companion object {
        val ROLE_ORDER = listOf("owner", "admin", "moderator", "support", "removed")
    }
}
