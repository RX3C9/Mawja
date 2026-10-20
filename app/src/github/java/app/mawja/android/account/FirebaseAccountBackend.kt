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
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Mawja accounts on Firebase: email/password (a username signs in by resolving to its email via the
 * `resolveLogin` function), Google through Credential Manager, and the user's own `users/{uid}`
 * document for the Mawja ID, tier and badges, which the backend writes.
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
    private val db = FirebaseFirestore.getInstance(app)
    private val functions = FirebaseFunctions.getInstance(app, BuildConfig.FUNCTIONS_REGION)

    private val _state = MutableStateFlow<AccountState>(if (auth.currentUser != null) AccountState.Loading else AccountState.Guest)
    override val state: StateFlow<AccountState> = _state.asStateFlow()
    override val available = true
    override val googleAvailable = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    private var profileListener: ListenerRegistration? = null

    init {
        auth.addAuthStateListener { watch(it.currentUser) }
    }

    private fun watch(user: FirebaseUser?) {
        profileListener?.remove()
        profileListener = null
        if (user == null) {
            _state.value = AccountState.Guest
            return
        }
        _state.value = AccountState.SignedIn(basicProfile(user, null))
        profileListener =
            db.document("users/${user.uid}").addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Profile listener failed", error)
                    return@addSnapshotListener
                }
                if (auth.currentUser?.uid == user.uid) _state.value = AccountState.SignedIn(basicProfile(user, snapshot))
            }
    }

    private fun basicProfile(
        user: FirebaseUser,
        doc: DocumentSnapshot?,
    ): MawjaProfile {
        @Suppress("UNCHECKED_CAST")
        val badges = (doc?.get("badges") as? List<String>).orEmpty()
        return MawjaProfile(
            uid = user.uid,
            email = user.email ?: doc?.getString("email"),
            displayName = doc?.getString("displayName") ?: user.displayName,
            photoUrl = user.photoUrl?.toString() ?: doc?.getString("photoUrl"),
            mawjaId = doc?.getString("mawjaId"),
            username = doc?.getString("username"),
            tier = MawjaTier.of(doc?.getString("tier")),
            badges = MawjaBadge.of(badges),
            status = doc?.getString("status") ?: "active",
        )
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
            db.document("users/${user.uid}").update("displayName", clean).await()
            AccountResult.Ok
        }

    override suspend fun signOut() {
        // Also forget the Google account choice, so the next sign-in asks again.
        runCatching { CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest()) }
        auth.signOut()
    }

    private suspend fun claim(username: String): AccountResult =
        try {
            functions.getHttpsCallable("claimUsername").call(mapOf("username" to username)).await()
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
    }
}
