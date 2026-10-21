/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import android.app.Activity
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Membership tiers, as the backend stores them. */
enum class MawjaTier(
    val id: String,
) {
    FREE("free"),
    PLUS("plus"),
    PRO("pro"),
    LIFETIME("lifetime"),
    FAMILY("family"),
    ;

    companion object {
        fun of(id: String?): MawjaTier = entries.firstOrNull { it.id == id } ?: FREE
    }
}

/** Badges the admin team (or an automatic rule) grants. */
enum class MawjaBadge(
    val id: String,
) {
    VERIFIED("verified"),
    CREATOR("creator"),
    FOUNDER("founder"),
    PRO("pro"),
    TEAM("team"),
    ;

    companion object {
        fun of(ids: List<String>): List<MawjaBadge> = ids.mapNotNull { id -> entries.firstOrNull { it.id == id } }
    }
}

@Immutable
data class MawjaProfile(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    /** MW-XXXX-XXXX; null for the few seconds the backend takes to allocate it after sign-up. */
    val mawjaId: String?,
    val username: String?,
    val tier: MawjaTier,
    val badges: List<MawjaBadge>,
    /** suspended or banned accounts keep guest features but lose account extras. */
    val status: String,
    /** When a code-activated membership ends (epoch ms); null for Free, Lifetime and open-ended grants. */
    val tierUntil: Long? = null,
)

/** Who is using the app. Guest is the default and keeps every original feature. */
sealed interface AccountState {
    data object Guest : AccountState

    data object Loading : AccountState

    data class SignedIn(
        val profile: MawjaProfile,
    ) : AccountState
}

enum class AccountError {
    NOT_CONFIGURED,
    INVALID_LOGIN,
    UNKNOWN_USERNAME,
    USERNAME_TAKEN,
    USERNAME_INVALID,
    EMAIL_INVALID,
    EMAIL_IN_USE,
    WEAK_PASSWORD,
    TOO_MANY_ATTEMPTS,
    NETWORK,
    CANCELLED,
    ACCOUNT_DISABLED,
    UNKNOWN,
}

sealed interface AccountResult {
    data object Ok : AccountResult

    data class Failed(
        val error: AccountError,
        val detail: String? = null,
    ) : AccountResult
}

/**
 * The Mawja account system. The github flavor talks to Firebase; foss builds and builds without a
 * Firebase config use [GuestOnlyAccountBackend], so the app always works without an account.
 */
interface AccountBackend {
    /** False when this build has no account service; the UI then explains guest mode only. */
    val available: Boolean

    /** Whether "Continue with Google" can be offered. */
    val googleAvailable: Boolean

    val state: StateFlow<AccountState>

    /** [login] is an email or a Mawja username. */
    suspend fun signIn(
        login: String,
        password: String,
    ): AccountResult

    suspend fun signUp(
        displayName: String,
        username: String,
        email: String,
        password: String,
    ): AccountResult

    suspend fun signInWithGoogle(activity: Activity): AccountResult

    suspend fun claimUsername(username: String): AccountResult

    suspend fun sendPasswordReset(login: String): AccountResult

    suspend fun updateDisplayName(name: String): AccountResult

    suspend fun signOut()

    /** Activates a membership code (MW-XXXX-XXXX-XXXX) on the signed-in account. */
    suspend fun redeemCode(code: String): RedeemResult

    /** Re-reads the account (tier, Mawja ID) from the backend. */
    suspend fun refreshProfile()
}

/** Accounts switched off: everything stays local, exactly like the original app. */
class GuestOnlyAccountBackend : AccountBackend {
    private val guest = MutableStateFlow<AccountState>(AccountState.Guest)
    override val available = false
    override val googleAvailable = false
    override val state: StateFlow<AccountState> = guest.asStateFlow()

    private val off = AccountResult.Failed(AccountError.NOT_CONFIGURED)

    override suspend fun signIn(
        login: String,
        password: String,
    ) = off

    override suspend fun signUp(
        displayName: String,
        username: String,
        email: String,
        password: String,
    ) = off

    override suspend fun signInWithGoogle(activity: Activity) = off

    override suspend fun claimUsername(username: String) = off

    override suspend fun sendPasswordReset(login: String) = off

    override suspend fun updateDisplayName(name: String) = off

    override suspend fun signOut() = Unit

    override suspend fun redeemCode(code: String): RedeemResult = RedeemResult.Failed(RedeemError.NOT_AVAILABLE)

    override suspend fun refreshProfile() = Unit
}

/** Client-side copy of the backend's username rules (backend/functions/src/username.ts). */
object UsernameRules {
    private val RESERVED =
        setOf(
            "admin", "administrator", "mawja", "support", "help", "root", "system", "moderator", "official",
            "team", "staff", "security", "null", "undefined", "api", "www",
        )
    private val ALLOWED = Regex("^[a-z0-9_.]+$")

    fun normalize(input: String): String = input.trim().lowercase().removePrefix("@")

    fun isValid(input: String): Boolean {
        val value = normalize(input)
        if (value.length !in 3..20) return false
        if (!ALLOWED.matches(value)) return false
        if (value.startsWith(".") || value.endsWith(".") || value.contains("..")) return false
        return value !in RESERVED && !value.startsWith("mawja")
    }

    fun looksLikeEmail(input: String): Boolean = input.trim().let { it.contains('@') && !it.startsWith("@") && it.substringAfter('@').contains('.') }
}
