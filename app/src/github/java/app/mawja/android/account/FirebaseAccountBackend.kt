/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import app.mawja.android.BuildConfig
import app.mawja.codes.CodeRedeemPolicy
import app.mawja.codes.MawjaCodes
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseOptions
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.io.IOException
import java.time.Instant

/**
 * Mawja accounts on Firebase: email/password (a username signs in by resolving to its email via the
 * `resolveLogin` function), Google through Credential Manager, and the `myProfile` function for
 * the Mawja ID, tier and badges the backend keeps in `users/{uid}`. (No Firestore SDK in the app:
 * its protolite classes clash with the app's protobuf-javalite.) `users/{uid}` is also read over the
 * Firestore REST API ([FirestoreRest]), which is how code memberships work without Cloud Functions.
 */
class FirebaseAccountBackend(
    context: Context,
) : AccountBackend {
    private val appContext: Context = context.applicationContext
    private val app: FirebaseApp =
        FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME } ?: FirebaseApp.initializeApp(
            context,
            FirebaseOptions
                .Builder()
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .build(),
            APP_NAME,
        )
    private val auth = FirebaseAuth.getInstance(app)
    private val functions = FirebaseFunctions.getInstance(app, BuildConfig.FUNCTIONS_REGION)
    private val firestore = FirestoreRest(BuildConfig.FIREBASE_PROJECT_ID) { auth.currentUser?.getIdToken(false)?.await()?.token }

    private val _state = MutableStateFlow<AccountState>(if (auth.currentUser != null) AccountState.Loading else AccountState.Guest)
    override val state: StateFlow<AccountState> = _state.asStateFlow()
    override val available = true
    override val googleAvailable = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null

    init {
        auth.addAuthStateListener { watch(it.currentUser) }
    }

    private fun watch(user: FirebaseUser?) {
        refreshJob?.cancel()
        if (user == null) {
            _state.value = AccountState.Guest
            return
        }
        _state.value = AccountState.SignedIn(basicProfile(user, null))
        refreshJob = scope.launch { loadProfile(user) }
    }

    /** Reads the account fields; right after sign-up the backend may still be allocating the Mawja ID, so it retries. */
    private suspend fun loadProfile(user: FirebaseUser) {
        repeat(PROFILE_ATTEMPTS) { attempt ->
            val result = fetchFields(user)
            if (auth.currentUser?.uid != user.uid) return
            if (result != null) {
                _state.value = AccountState.SignedIn(basicProfile(user, result.fields))
                // Without the functions (Spark plan) there is no Mawja ID to wait for.
                if (result.fields["mawjaId"] != null || !result.fromFunction) return
            }
            delay(PROFILE_RETRY_MS * (attempt + 1))
        }
    }

    private class Fields(
        val fields: Map<*, *>,
        val fromFunction: Boolean,
    )

    /**
     * The `myProfile` function's answer merged with the users doc read over REST. The doc wins for
     * the membership (tier, tierUntil): code redemptions write it directly.
     */
    private suspend fun fetchFields(user: FirebaseUser): Fields? {
        val fromFunction =
            try {
                (functions.getHttpsCallable("myProfile").call().await().data as? Map<*, *>)?.takeIf { it["ready"] == true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "myProfile failed", e)
                null
            }
        val doc =
            try {
                val path = "users/${user.uid}"
                firestore.batchGet(listOf(path)).docs[path]?.fields ?: emptyMap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "users doc read failed", e)
                null
            }
        if (fromFunction == null && doc == null) return null
        val merged = LinkedHashMap<Any?, Any?>()
        doc?.let { merged.putAll(it) }
        fromFunction?.let { merged.putAll(it) }
        if (doc != null && (doc["tierSource"] == "code" || doc.containsKey("tierUntil"))) {
            merged["tier"] = doc["tier"]
            merged["tierUntil"] = doc["tierUntil"]
        }
        return Fields(merged, fromFunction != null)
    }

    private fun basicProfile(
        user: FirebaseUser,
        doc: Map<*, *>?,
    ): MawjaProfile {
        fun str(key: String) = doc?.get(key) as? String
        val badges = (doc?.get("badges") as? List<*>).orEmpty().filterIsInstance<String>()
        val tierUntil = (doc?.get("tierUntil") as? Instant)?.toEpochMilli()
        return MawjaProfile(
            uid = user.uid,
            email = user.email ?: str("email"),
            displayName = str("displayName") ?: user.displayName,
            photoUrl = user.photoUrl?.toString() ?: str("photoUrl"),
            mawjaId = str("mawjaId"),
            username = str("username"),
            tier = effectiveProfileTier(MawjaTier.of(str("tier")), tierUntil, System.currentTimeMillis()),
            badges = MawjaBadge.of(badges),
            status = str("status") ?: "active",
            tierUntil = tierUntil,
        )
    }

    private fun reload() {
        auth.currentUser?.let { user ->
            refreshJob?.cancel()
            refreshJob = scope.launch { loadProfile(user) }
        }
    }

    override suspend fun signIn(
        login: String,
        password: String,
    ): AccountResult =
        guarded {
            val email =
                if (UsernameRules.looksLikeEmail(login)) {
                    login.trim()
                } else {
                    if (!UsernameRules.isValid(login)) return@guarded AccountResult.Failed(AccountError.UNKNOWN_USERNAME)
                    resolveUsername(UsernameRules.normalize(login)) ?: return@guarded AccountResult.Failed(AccountError.UNKNOWN_USERNAME)
                }
            auth.signInWithEmailAndPassword(email, password).await()
            AccountResult.Ok
        }

    override suspend fun signUp(
        displayName: String,
        username: String,
        email: String,
        password: String,
    ): AccountResult =
        guarded {
            if (!UsernameRules.isValid(username)) return@guarded AccountResult.Failed(AccountError.USERNAME_INVALID)
            if (!UsernameRules.looksLikeEmail(email)) return@guarded AccountResult.Failed(AccountError.EMAIL_INVALID)
            val user = auth.createUserWithEmailAndPassword(email.trim(), password).await().user ?: error("no user")
            if (displayName.isNotBlank()) {
                user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(displayName.trim()).build()).await()
            }
            runCatching { user.sendEmailVerification().await() }
            // The account exists now; a taken username is reported so the user can pick another on the account page.
            claim(UsernameRules.normalize(username))
        }

    override suspend fun signInWithGoogle(activity: Activity): AccountResult =
        guarded {
            if (!googleAvailable) return@guarded AccountResult.Failed(AccountError.NOT_CONFIGURED)
            val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val credential =
                try {
                    CredentialManager.create(activity).getCredential(activity, request).credential
                } catch (e: GetCredentialCancellationException) {
                    return@guarded AccountResult.Failed(AccountError.CANCELLED)
                } catch (e: GetCredentialException) {
                    return@guarded AccountResult.Failed(AccountError.UNKNOWN, e.message)
                }
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return@guarded AccountResult.Failed(AccountError.UNKNOWN, "unexpected credential")
            }
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
            AccountResult.Ok
        }

    override suspend fun claimUsername(username: String): AccountResult =
        guarded {
            if (!UsernameRules.isValid(username)) return@guarded AccountResult.Failed(AccountError.USERNAME_INVALID)
            claim(UsernameRules.normalize(username))
        }

    override suspend fun sendPasswordReset(login: String): AccountResult =
        guarded {
            val email =
                if (UsernameRules.looksLikeEmail(login)) {
                    login.trim()
                } else {
                    resolveUsername(UsernameRules.normalize(login)) ?: return@guarded AccountResult.Failed(AccountError.UNKNOWN_USERNAME)
                }
            auth.sendPasswordResetEmail(email).await()
            AccountResult.Ok
        }

    override suspend fun updateDisplayName(name: String): AccountResult =
        guarded {
            val user = auth.currentUser ?: return@guarded AccountResult.Failed(AccountError.INVALID_LOGIN)
            val clean = name.trim().take(MAX_NAME)
            user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(clean).build()).await()
            functions.getHttpsCallable("updateMyProfile").call(mapOf("displayName" to clean)).await()
            reload()
            AccountResult.Ok
        }

    override suspend fun signOut() {
        // Also forget the Google account choice, so the next sign-in asks again.
        runCatching { CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest()) }
        auth.signOut()
    }

    override suspend fun refreshProfile() {
        val user = auth.currentUser ?: return
        val result = fetchFields(user) ?: return
        if (auth.currentUser?.uid == user.uid) _state.value = AccountState.SignedIn(basicProfile(user, result.fields))
    }

    /**
     * Activates a membership code (docs/codes.md) without Cloud Functions:
     * 1. announce the code hash in users/{uid}/meta/redeem (the rules throttle this: 2 s apart,
     *    12 per hour) — reading codes/{hash} is only allowed right after;
     * 2. in one transaction read the code, the users doc and any earlier redemption, decide with
     *    [CodeRedeemPolicy] (the rules enforce the same math), then write used+1, the redemption and
     *    the new tier together.
     */
    override suspend fun redeemCode(code: String): RedeemResult {
        localCodeError(code)?.let { return RedeemResult.Failed(it) }
        val user = auth.currentUser ?: return RedeemResult.Failed(RedeemError.NOT_SIGNED_IN)
        val hash = MawjaCodes.hash(code)
        val uid = user.uid
        return try {
            if (!announceAttempt(uid, hash)) return RedeemResult.Failed(RedeemError.TOO_MANY_ATTEMPTS)
            var result: RedeemResult? = null
            var rounds = 0
            while (result == null && rounds < TX_ATTEMPTS) {
                rounds++
                result = redeemTransaction(uid, hash)
            }
            val activated = result as? RedeemResult.Activated
            if (activated != null) {
                val signed = _state.value as? AccountState.SignedIn
                if (signed != null && signed.profile.uid == uid) {
                    _state.value = AccountState.SignedIn(signed.profile.copy(tier = activated.tier, tierUntil = activated.until))
                }
                reload()
            }
            result ?: RedeemResult.Failed(RedeemError.UNKNOWN)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirestoreRest.HttpError) {
            Log.w(TAG, "redeem failed", e)
            RedeemResult.Failed(if (e.code == 401) RedeemError.NOT_SIGNED_IN else RedeemError.UNKNOWN)
        } catch (e: FirebaseNetworkException) {
            RedeemResult.Failed(RedeemError.NETWORK)
        } catch (e: IOException) {
            RedeemResult.Failed(RedeemError.NETWORK)
        } catch (e: Exception) {
            Log.w(TAG, "redeem failed", e)
            RedeemResult.Failed(RedeemError.UNKNOWN)
        }
    }

    /** Step 1. False when the throttle says no. */
    private suspend fun announceAttempt(
        uid: String,
        hash: String,
    ): Boolean {
        val path = "users/$uid/meta/redeem"
        val snapshot = firestore.batchGet(listOf(path))
        val meta = snapshot.docs[path]?.fields
        val now = snapshot.readTime.toEpochMilli()
        val windowStart = meta?.get("windowStart") as? Instant
        val count = meta?.get("count") as? Long ?: 0L
        val fresh = windowStart == null || windowStart.toEpochMilli() < now - HOUR_MS
        if (!fresh && count >= MAX_ATTEMPTS_PER_HOUR) return false
        (meta?.get("lastAttempt") as? Instant)?.let { last ->
            val wait = ATTEMPT_GAP_MS - (now - last.toEpochMilli())
            if (wait > 0) delay(wait)
        }
        val write =
            if (fresh) {
                FirestoreRest.Update(path, mapOf("hash" to hash, "count" to 1L), serverTimeFields = listOf("lastAttempt", "windowStart"))
            } else {
                FirestoreRest.Update(path, mapOf("hash" to hash, "count" to count + 1, "windowStart" to windowStart), serverTimeFields = listOf("lastAttempt"))
            }
        return try {
            firestore.commit(listOf(write))
            true
        } catch (e: FirestoreRest.HttpError) {
            if (e.code == 403) false else throw e
        }
    }

    /** Step 2. Null when the transaction was aborted by a concurrent write and should run again. */
    private suspend fun redeemTransaction(
        uid: String,
        hash: String,
    ): RedeemResult? {
        val codePath = "codes/$hash"
        val userPath = "users/$uid"
        val redemptionPath = "redemptions/${hash}_$uid"
        val tx = firestore.beginTransaction()
        var committed = false
        try {
            val snapshot =
                try {
                    firestore.batchGet(listOf(codePath, userPath, redemptionPath), tx)
                } catch (e: FirestoreRest.HttpError) {
                    // The code read needs a fresh attempt; a 403 here means the throttle window passed.
                    if (e.code == 403) return RedeemResult.Failed(RedeemError.TOO_MANY_ATTEMPTS)
                    throw e
                }
            val now = snapshot.readTime.toEpochMilli()
            val c = snapshot.docs[codePath]?.fields ?: return RedeemResult.Failed(RedeemError.INVALID)
            if (snapshot.docs[redemptionPath] != null) return RedeemResult.Failed(RedeemError.ALREADY_YOURS)
            val state = codeState(c)
            CodeRedeemPolicy.problem(state, now)?.let { return RedeemResult.Failed(it.toError()) }
            val u = snapshot.docs[userPath]?.fields.orEmpty()
            if ((u["status"] as? String ?: "active") != "active") return RedeemResult.Failed(RedeemError.ACCOUNT_RESTRICTED)
            val current = CodeRedeemPolicy.Membership(u["tier"] as? String ?: "free", (u["tierUntil"] as? Instant)?.toEpochMilli())
            val grant =
                when (val decision = CodeRedeemPolicy.decide(current, state.tier, state.days, now)) {
                    is CodeRedeemPolicy.Decision.Refuse ->
                        return RedeemResult.Failed(
                            if (decision.reason == CodeRedeemPolicy.Refusal.ALREADY_LIFETIME) RedeemError.ALREADY_LIFETIME else RedeemError.ALREADY_COVERED,
                        )
                    is CodeRedeemPolicy.Decision.Grant -> decision
                }
            val until = grant.untilMillis?.let { Instant.ofEpochMilli(it) }
            val previous = if (current.isActive(now)) current.tier else "free"
            val writes =
                listOf(
                    FirestoreRest.Update(codePath, mapOf("used" to state.used + 1L), mustExist = true),
                    FirestoreRest.Update(
                        redemptionPath,
                        mapOf(
                            "uid" to uid,
                            "codeHash" to hash,
                            "codeTier" to state.tier,
                            "days" to state.days.toLong(),
                            "tier" to grant.tier,
                            "tierUntil" to until,
                            "batchId" to (c["batchId"] as? String ?: ""),
                            "previousTier" to previous,
                        ),
                        serverTimeFields = listOf("createdAt"),
                        mustExist = false,
                    ),
                    FirestoreRest.Update(
                        userPath,
                        mapOf("tier" to grant.tier, "tierUntil" to until, "tierSource" to "code", "tierCodeHash" to hash),
                        serverTimeFields = listOf("tierUpdatedAt"),
                    ),
                )
            try {
                firestore.commit(writes, tx)
                committed = true
            } catch (e: FirestoreRest.HttpError) {
                committed = true // a failed commit ends the transaction
                if (e.code == 409 || e.status == "ABORTED") return null
                if (e.code == 403) return RedeemResult.Failed(recheck(codePath))
                throw e
            }
            return RedeemResult.Activated(MawjaTier.of(grant.tier), grant.untilMillis)
        } finally {
            if (!committed) firestore.rollback(tx)
        }
    }

    /** After a refused commit: the code most likely changed in between (used up or revoked). */
    private suspend fun recheck(codePath: String): RedeemError =
        runCatching {
            val snapshot = firestore.batchGet(listOf(codePath))
            val c = snapshot.docs[codePath]?.fields ?: return@runCatching RedeemError.INVALID
            CodeRedeemPolicy.problem(codeState(c), snapshot.readTime.toEpochMilli())?.toError() ?: RedeemError.UNKNOWN
        }.getOrDefault(RedeemError.UNKNOWN)

    private fun codeState(c: Map<String, Any?>) =
        CodeRedeemPolicy.CodeState(
            tier = c["tier"] as? String ?: "",
            days = (c["days"] as? Long)?.toInt() ?: -1,
            maxUses = (c["maxUses"] as? Long)?.toInt() ?: 0,
            used = (c["used"] as? Long)?.toInt() ?: Int.MAX_VALUE,
            revoked = c["revoked"] == true,
            expiresAtMillis = (c["expiresAt"] as? Instant)?.toEpochMilli(),
        )

    private fun CodeRedeemPolicy.CodeProblem.toError(): RedeemError =
        when (this) {
            CodeRedeemPolicy.CodeProblem.REVOKED -> RedeemError.REVOKED
            CodeRedeemPolicy.CodeProblem.EXPIRED -> RedeemError.EXPIRED
            CodeRedeemPolicy.CodeProblem.USED_UP -> RedeemError.USED
            CodeRedeemPolicy.CodeProblem.MALFORMED -> RedeemError.INVALID
        }

    private suspend fun claim(username: String): AccountResult =
        try {
            functions.getHttpsCallable("claimUsername").call(mapOf("username" to username)).await()
            reload()
            AccountResult.Ok
        } catch (e: FirebaseFunctionsException) {
            when (e.code) {
                FirebaseFunctionsException.Code.ALREADY_EXISTS -> AccountResult.Failed(AccountError.USERNAME_TAKEN)
                FirebaseFunctionsException.Code.INVALID_ARGUMENT -> AccountResult.Failed(AccountError.USERNAME_INVALID)
                else -> throw e
            }
        }

    private suspend fun resolveUsername(username: String): String? =
        try {
            val data = functions.getHttpsCallable("resolveLogin").call(mapOf("username" to username)).await().data
            (data as? Map<*, *>)?.get("email") as? String
        } catch (e: FirebaseFunctionsException) {
            if (e.code == FirebaseFunctionsException.Code.NOT_FOUND) null else throw e
        }

    private suspend fun guarded(block: suspend () -> AccountResult): AccountResult =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseAuthWeakPasswordException) {
            AccountResult.Failed(AccountError.WEAK_PASSWORD)
        } catch (e: FirebaseAuthUserCollisionException) {
            AccountResult.Failed(AccountError.EMAIL_IN_USE)
        } catch (e: FirebaseAuthInvalidUserException) {
            if (e.errorCode == "ERROR_USER_DISABLED") {
                AccountResult.Failed(AccountError.ACCOUNT_DISABLED)
            } else {
                AccountResult.Failed(AccountError.INVALID_LOGIN)
            }
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            if (e.errorCode == "ERROR_INVALID_EMAIL") {
                AccountResult.Failed(AccountError.EMAIL_INVALID)
            } else {
                AccountResult.Failed(AccountError.INVALID_LOGIN)
            }
        } catch (e: FirebaseTooManyRequestsException) {
            AccountResult.Failed(AccountError.TOO_MANY_ATTEMPTS)
        } catch (e: FirebaseNetworkException) {
            AccountResult.Failed(AccountError.NETWORK)
        } catch (e: Exception) {
            Log.w(TAG, "Account call failed", e)
            AccountResult.Failed(AccountError.UNKNOWN, e.message)
        }

    private companion object {
        const val TAG = "MawjaAccount"
        const val APP_NAME = "mawja"
        const val MAX_NAME = 60
        const val PROFILE_ATTEMPTS = 6
        const val PROFILE_RETRY_MS = 2_000L
        const val TX_ATTEMPTS = 3
        const val HOUR_MS = 3_600_000L
        const val ATTEMPT_GAP_MS = 2_500L
        const val MAX_ATTEMPTS_PER_HOUR = 12L
    }
}
