/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.data

import java.util.Date

data class UserRecord(
    val uid: String,
    val exists: Boolean,
    val email: String? = null,
    val displayName: String? = null,
    val username: String? = null,
    val mawjaId: String? = null,
    val photoUrl: String? = null,
    val locale: String? = null,
    val tier: String = "free",
    val tierUntil: Date? = null,
    val tierSource: String? = null,
    val status: String = "active",
    val banReason: String? = null,
    val badges: List<String> = emptyList(),
    val verified: Boolean = false,
    val staffNote: String = "",
    val createdAt: Date? = null,
    val lastSeenAt: Date? = null,
)

data class Device(
    val id: String,
    val fields: Map<String, String>,
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
    val batchId: String = "",
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
    val tier: String,
    val days: Int,
    val maxUses: Int,
    val expiresAt: Date?,
    val note: String,
    val error: String? = null,
)

data class Announcement(
    val id: String,
    val title: String,
    val body: String,
    val targetTier: String?,
    val locale: String?,
    val kind: String,
    val ctaLabel: String,
    val ctaUrl: String,
    val active: Boolean,
    val startAt: Date?,
    val endAt: Date?,
    val createdBy: String?,
)

data class Report(
    val id: String,
    val uid: String?,
    val type: String,
    val message: String,
    val status: String,
    val assignee: String?,
    val note: String,
    val reply: String,
    val createdAt: Date?,
    val updatedAt: Date?,
    /** Any other fields the app sent (device, version...), as text. */
    val extra: Map<String, String>,
)

data class AuditEntry(
    val id: String,
    val action: String,
    val actorUid: String,
    val target: String,
    val details: Map<String, String>,
    val at: Date?,
    val source: String,
)

data class StaffMember(
    val uid: String,
    val role: String,
    val email: String?,
    val note: String,
    val updatedAt: Date?,
)

data class DashboardData(
    val users: Long? = null,
    val tierCounts: Map<String, Long?> = emptyMap(),
    val activeTimed: Long? = null,
    val banned: Long? = null,
    val suspended: Long? = null,
    val codes7: Long? = null,
    val codes30: Long? = null,
    val used7: Long? = null,
    val used30: Long? = null,
    val openReports: Long? = null,
    val staff: Long? = null,
    /** Last 14 days, oldest first. */
    val activationsByDay: List<Int> = emptyList(),
    val codesByDay: List<Int> = emptyList(),
    val recentActivations: List<Redemption> = emptyList(),
    val recentAudit: List<AuditEntry> = emptyList(),
    val loadedAt: Date? = null,
)
