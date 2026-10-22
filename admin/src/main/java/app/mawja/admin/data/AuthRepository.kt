/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.data

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import app.mawja.admin.BuildConfig
import app.mawja.admin.core.StaffRole
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

data class Staff(
    val uid: String,
    val email: String?,
    val name: String?,
    val role: StaffRole,
    /** Where the role comes from: the ID token claim or the admins/{uid} doc. */
    val viaClaim: Boolean,
)

sealed interface Session {
    data object Loading : Session

    data object NotConfigured : Session

    data object SignedOut : Session

    /** Signed in, but no staff role: shown with the UID so the owner can be set up in the console. */
    data class NotStaff(
        val uid: String,
        val email: String?,
    ) : Session

    data class Active(
        val staff: Staff,
    ) : Session
}

/** Thrown when the user closed the Google account picker. */
class SignInCancelled : Exception("cancelled")

class AuthRepository(
    private val fb: AdminFirebase,
) {
    private val _session = MutableStateFlow<Session>(if (fb.configured) Session.Loading else Session.NotConfigured)
    val session: StateFlow<Session> = _session.asStateFlow()

    val googleAvailable: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    /** Re-reads the role: ID token claim (forced refresh) first, else the admins/{uid} doc. */
    suspend fun refresh(): Session {
        if (!fb.configured) return Session.NotConfigured.also { _session.value = it }
        val user = fb.auth.currentUser ?: return Session.SignedOut.also { _session.value = it }
        val claim = runCatching { user.getIdToken(true).await().claims["role"] as? String }.getOrNull()
        val fromClaim = StaffRole.of(claim)
        val role =
            fromClaim ?: runCatching { StaffRole.of(fb.db.document("admins/${user.uid}").get().await().getString("role")) }.getOrNull()
        val next =
            if (role == null) {
                Session.NotStaff(user.uid, user.email)
            } else {
                Session.Active(Staff(user.uid, user.email, user.displayName, role, viaClaim = fromClaim != null))
            }
        _session.value = next
        return next
    }

    suspend fun signIn(
        email: String,
        password: String,
    ): Session {
        fb.auth.signInWithEmailAndPassword(email.trim(), password).await()
        return refresh()
    }

    suspend fun signInWithGoogle(activity: Activity): Session {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential =
            try {
                CredentialManager.create(activity).getCredential(activity, request).credential
            } catch (e: GetCredentialCancellationException) {
                throw SignInCancelled()
            }
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "Unexpected credential" }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        fb.auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        return refresh()
    }

    suspend fun sendPasswordReset(email: String) {
        fb.auth.sendPasswordResetEmail(email.trim()).await()
    }

    fun signOut() {
        if (fb.configured) fb.auth.signOut()
        _session.value = if (fb.configured) Session.SignedOut else Session.NotConfigured
    }
}
