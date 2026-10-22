/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.account

import android.app.Activity
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mawja.android.account.AccountBackend
import app.mawja.android.account.AccountError
import app.mawja.android.account.AccountResult
import app.mawja.android.account.AccountState
import app.mawja.android.account.EntitlementsRepository
import app.mawja.android.account.RedeemError
import app.mawja.android.account.RedeemResult
import app.mawja.android.account.localCodeError
import app.mawja.android.cloudsync.CloudSyncAvailability
import app.mawja.android.cloudsync.CloudSyncRepository
import app.mawja.android.cloudsync.CloudSyncStatus
import app.mawja.android.remote.RemoteConfigRepository
import app.mawja.remote.RemoteSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AccountMode { WELCOME, SIGN_IN, SIGN_UP }

/** The account page's transient UI: which form is open, whether a call runs, the last error or notice. */
@Immutable
data class AccountUiState(
    val mode: AccountMode = AccountMode.WELCOME,
    val busy: Boolean = false,
    val error: AccountError? = null,
    val notice: AccountNotice? = null,
)

enum class AccountNotice { RESET_SENT, USERNAME_SAVED, NAME_SAVED, SIGNED_OUT, VERIFY_EMAIL }

/** The Upgrade page's code field: idle, checking, or the last result. */
@Immutable
data class RedeemUiState(
    val busy: Boolean = false,
    val error: RedeemError? = null,
    val activated: RedeemResult.Activated? = null,
)

@HiltViewModel
class AccountViewModel
    @Inject
    constructor(
        private val backend: AccountBackend,
        private val entitlements: EntitlementsRepository,
        remote: RemoteConfigRepository,
        private val cloudSync: CloudSyncRepository,
    ) : ViewModel() {
        /** Cloud sync card: whether it is usable, locked (upsell) or off, and the last run. */
        val syncAvailability: StateFlow<CloudSyncAvailability> = cloudSync.availability
        val syncStatus: StateFlow<CloudSyncStatus> = cloudSync.status

        fun syncNow() = cloudSync.syncNow()

        /** config/app: codeRedeem switch and the online checkout URL. */
        val remoteSettings: StateFlow<RemoteSettings> = remote.settings

        val available: Boolean get() = backend.available
        val googleAvailable: Boolean get() = backend.googleAvailable
        val account: StateFlow<AccountState> = backend.state

        private val _ui = MutableStateFlow(AccountUiState())
        val ui: StateFlow<AccountUiState> = _ui.asStateFlow()

        private val _redeem = MutableStateFlow(RedeemUiState())
        val redeem: StateFlow<RedeemUiState> = _redeem.asStateFlow()

        /** Checks the code locally (format and check symbol) first, then activates it on the account. */
        fun redeemCode(code: String) {
            if (_redeem.value.busy) return
            localCodeError(code)?.let { error ->
                _redeem.value = RedeemUiState(error = error)
                return
            }
            _redeem.value = RedeemUiState(busy = true)
            viewModelScope.launch {
                when (val result = backend.redeemCode(code)) {
                    is RedeemResult.Activated -> {
                        _redeem.value = RedeemUiState(activated = result)
                        runCatching { entitlements.refresh() }
                    }
                    is RedeemResult.Failed -> _redeem.value = RedeemUiState(error = result.error)
                }
            }
        }

        fun clearRedeemError() = _redeem.update { if (it.busy) it else it.copy(error = null) }

        fun show(mode: AccountMode) = _ui.update { it.copy(mode = mode, error = null, notice = null) }

        fun dismissMessage() = _ui.update { it.copy(error = null, notice = null) }

        fun signIn(
            login: String,
            password: String,
        ) = launchCall { backend.signIn(login, password) }

        fun signUp(
            displayName: String,
            username: String,
            email: String,
            password: String,
        ) = launchCall(onOk = AccountNotice.VERIFY_EMAIL) { backend.signUp(displayName, username, email, password) }

        fun signInWithGoogle(activity: Activity) = launchCall { backend.signInWithGoogle(activity) }

        fun sendPasswordReset(login: String) = launchCall(onOk = AccountNotice.RESET_SENT) { backend.sendPasswordReset(login) }

        fun claimUsername(username: String) = launchCall(onOk = AccountNotice.USERNAME_SAVED) { backend.claimUsername(username) }

        fun updateDisplayName(name: String) = launchCall(onOk = AccountNotice.NAME_SAVED) { backend.updateDisplayName(name) }

        fun signOut() {
            viewModelScope.launch {
                backend.signOut()
                _ui.value = AccountUiState(notice = AccountNotice.SIGNED_OUT)
            }
        }

        private fun launchCall(
            onOk: AccountNotice? = null,
            call: suspend () -> AccountResult,
        ) {
            if (_ui.value.busy) return
            _ui.update { it.copy(busy = true, error = null, notice = null) }
            viewModelScope.launch {
                when (val result = call()) {
                    AccountResult.Ok -> _ui.update { it.copy(busy = false, notice = onOk, mode = AccountMode.WELCOME) }

                    is AccountResult.Failed ->
                        _ui.update {
                            it.copy(busy = false, error = result.error.takeUnless { e -> e == AccountError.CANCELLED })
                        }
                }
            }
        }
    }
