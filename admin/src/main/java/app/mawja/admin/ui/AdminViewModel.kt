/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mawja.admin.data.AdminRepository
import app.mawja.admin.data.CodeBatch
import app.mawja.admin.data.CodeEntry
import app.mawja.admin.data.GeneratedBatch
import app.mawja.admin.data.Redemption
import app.mawja.admin.data.Report
import app.mawja.admin.data.Staff
import app.mawja.admin.data.UserCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Date

data class AdminState(
    val configured: Boolean = true,
    val loading: Boolean = false,
    val staff: Staff? = null,
    val notStaff: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val user: UserCard? = null,
    val userNotFound: Boolean = false,
    val reports: List<Report> = emptyList(),
    val features: Map<String, Boolean> = emptyMap(),
    val batches: List<CodeBatch> = emptyList(),
    val openBatch: String? = null,
    val batchCodes: List<CodeEntry> = emptyList(),
    val redemptions: List<Redemption> = emptyList(),
    /** Codes just generated: shown once, never stored in plain text. */
    val generated: GeneratedBatch? = null,
    val foundCode: CodeEntry? = null,
    val codeNotFound: Boolean = false,
)

/** Who may generate and revoke codes; the rules enforce the same. */
fun canManageCodes(role: String?): Boolean = role == "owner" || role == "admin"


class AdminViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val repo = AdminRepository(app)
    private val _state = MutableStateFlow(AdminState(configured = repo.configured))
    val state: StateFlow<AdminState> = _state.asStateFlow()

    init {
        if (repo.configured) launchSafe {
            val staff = repo.currentStaff()
            _state.update { it.copy(staff = staff) }
        }
    }

    fun signIn(
        email: String,
        password: String,
    ) = launchSafe {
        val staff = repo.signIn(email, password)
        if (staff == null) repo.signOut()
        _state.update { it.copy(staff = staff, notStaff = staff == null) }
    }

    fun signOut() {
        repo.signOut()
        _state.value = AdminState(configured = repo.configured)
    }

    fun lookup(query: String) =
        launchSafe {
            val user = repo.lookup(query)
            _state.update { it.copy(user = user, userNotFound = user == null) }
        }

    fun userAction(payload: Map<String, Any?>) =
        launchSafe {
            repo.action(payload)
            val uid = payload["uid"] as? String
            val refreshed = state.value.user?.let { u -> if (u.uid == uid) repo.lookup(u.mawjaId ?: u.username.orEmpty()) else u }
            _state.update { it.copy(user = refreshed ?: it.user, message = "ok") }
        }

    fun loadReports() =
        launchSafe {
            val reports = repo.openReports()
            _state.update { it.copy(reports = reports) }
        }

    fun resolveReport(id: String) =
        launchSafe {
            repo.action(mapOf("action" to "report.resolve", "reportId" to id))
            _state.update { s -> s.copy(reports = s.reports.filterNot { it.id == id }, message = "ok") }
        }

    fun loadFeatures() =
        launchSafe {
            val features = repo.features()
            _state.update { it.copy(features = features) }
        }

    fun toggleFeature(
        key: String,
        enabled: Boolean,
    ) = launchSafe {
        repo.action(mapOf("action" to "feature.toggle", "key" to key, "enabled" to enabled))
        _state.update { it.copy(features = (it.features + (key to enabled)).toSortedMap(), message = "ok") }
    }

    fun announce(
        title: String,
        body: String,
        targetTier: String?,
    ) = launchSafe {
        repo.action(mapOf("action" to "announcement.send", "title" to title, "body" to body, "targetTier" to targetTier))
        _state.update { it.copy(message = "ok") }
    }

    fun loadCodes() =
        launchSafe {
            val batches = repo.codeBatches()
            val redemptions = runCatching { repo.recentRedemptions() }.getOrDefault(emptyList())
            _state.update { it.copy(batches = batches, redemptions = redemptions) }
        }

    fun generateCodes(
        tier: String,
        days: Int,
        count: Int,
        maxUses: Int,
        validForDays: Int,
        note: String,
    ) = launchSafe {
        val expires = if (validForDays > 0) Date(System.currentTimeMillis() + validForDays * 86_400_000L) else null
        val result = repo.generateCodes(tier, days, count, maxUses, expires, note)
        _state.update { it.copy(generated = result, error = result.error) }
        val batches = repo.codeBatches()
        _state.update { it.copy(batches = batches) }
    }

    fun dismissGenerated() = _state.update { it.copy(generated = null) }

    fun openBatch(id: String?) =
        launchSafe {
            if (id == null) {
                _state.update { it.copy(openBatch = null, batchCodes = emptyList()) }
                return@launchSafe
            }
            val codes = repo.codesIn(id)
            _state.update { it.copy(openBatch = id, batchCodes = codes) }
        }

    fun findCode(code: String) =
        launchSafe {
            val found = repo.findCode(code)
            _state.update { it.copy(foundCode = found, codeNotFound = found == null) }
        }

    fun revokeCode(hash: String) =
        launchSafe {
            repo.revokeCode(hash)
            val open = state.value.openBatch
            val codes = open?.let { repo.codesIn(it) } ?: state.value.batchCodes
            val found = state.value.foundCode?.takeIf { it.hash == hash }?.copy(revoked = true) ?: state.value.foundCode
            _state.update { it.copy(batchCodes = codes, foundCode = found, message = "ok") }
        }

    fun revokeBatch(id: String) =
        launchSafe {
            repo.revokeBatch(id)
            val batches = repo.codeBatches()
            val codes = if (state.value.openBatch == id) repo.codesIn(id) else state.value.batchCodes
            _state.update { it.copy(batches = batches, batchCodes = codes, message = "ok") }
        }

    fun consumeMessage() = _state.update { it.copy(message = null, error = null) }

    private fun launchSafe(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                block()
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: t.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }
}
