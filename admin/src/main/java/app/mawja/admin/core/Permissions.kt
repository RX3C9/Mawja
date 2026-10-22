/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.core

/** Staff roles, highest first. Owners are made in the Firebase console only. */
enum class StaffRole(
    val id: String,
) {
    OWNER("owner"),
    ADMIN("admin"),
    MODERATOR("moderator"),
    SUPPORT("support"),
    ;

    companion object {
        fun of(id: String?): StaffRole? = entries.firstOrNull { it.id == id }

        /** Roles the owner can hand out from the app ("removed" switches a role off). */
        val ASSIGNABLE = listOf(ADMIN, MODERATOR, SUPPORT)
        const val REMOVED = "removed"
    }
}

/** What the admin app lets each role do. Mirrors backend/firestore.rules, which enforce it. */
enum class Perm {
    USERS_VIEW,
    USERS_NOTE,
    USERS_STATUS,
    USERS_BAN,
    USERS_BADGES,
    USERS_TIER,
    USERS_SYNC_RESET,
    CODES_VIEW,
    CODES_MANAGE,
    ANNOUNCE_VIEW,
    ANNOUNCE_MANAGE,
    CONFIG_VIEW,
    CONFIG_EDIT,
    REPORTS_HANDLE,
    AUDIT_VIEW,
    STAFF_VIEW,
    STAFF_MANAGE,
}

object Permissions {
    private val support =
        setOf(Perm.USERS_VIEW, Perm.USERS_NOTE, Perm.CODES_VIEW, Perm.ANNOUNCE_VIEW, Perm.CONFIG_VIEW, Perm.REPORTS_HANDLE, Perm.STAFF_VIEW)
    private val moderator = support + setOf(Perm.USERS_STATUS, Perm.USERS_BADGES)
    private val admin =
        moderator +
            setOf(Perm.USERS_BAN, Perm.USERS_TIER, Perm.USERS_SYNC_RESET, Perm.CODES_MANAGE, Perm.ANNOUNCE_MANAGE, Perm.CONFIG_EDIT, Perm.AUDIT_VIEW)
    private val owner = admin + Perm.STAFF_MANAGE

    fun of(role: StaffRole?): Set<Perm> =
        when (role) {
            StaffRole.OWNER -> owner
            StaffRole.ADMIN -> admin
            StaffRole.MODERATOR -> moderator
            StaffRole.SUPPORT -> support
            null -> emptySet()
        }

    fun can(
        role: StaffRole?,
        perm: Perm,
    ): Boolean = perm in of(role)

    /**
     * Whether [actor] may change [targetUid]'s staff role from [currentRole]: owners only, never
     * themselves, never another owner (the rules refuse the same).
     */
    fun canEditStaff(
        actor: StaffRole?,
        actorUid: String,
        targetUid: String,
        currentRole: String?,
    ): Boolean = actor == StaffRole.OWNER && actorUid != targetUid && currentRole != StaffRole.OWNER.id

    /** Status changes: moderators suspend/restore, owner/admin also ban, nobody changes their own. */
    fun canSetStatus(
        actor: StaffRole?,
        actorUid: String,
        targetUid: String,
        status: String,
    ): Boolean =
        actorUid != targetUid && status in UserStatus.ALL &&
            (if (status == UserStatus.BANNED) can(actor, Perm.USERS_BAN) else can(actor, Perm.USERS_STATUS))
}

object UserStatus {
    const val ACTIVE = "active"
    const val SUSPENDED = "suspended"
    const val BANNED = "banned"
    val ALL = listOf(ACTIVE, SUSPENDED, BANNED)
}

object Tiers {
    val ALL = listOf("free", "plus", "pro", "family", "lifetime")
    val PAID = listOf("plus", "pro", "family", "lifetime")
    val BADGES = listOf("verified", "creator", "founder", "pro", "team", "staff", "early")
}
