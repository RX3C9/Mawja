/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mawja.admin.data.AdminRepository
import app.mawja.admin.data.Report
import app.mawja.admin.data.Staff
import app.mawja.admin.data.UserCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
)

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
