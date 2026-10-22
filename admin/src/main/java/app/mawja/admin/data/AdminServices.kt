/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.data

import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** A short message for the snackbar: a string resource, or a [Problem] with optional detail. */
data class Notice(
    val text: Int? = null,
    val problem: Problem? = null,
    val detail: String? = null,
)

/** App-wide singletons (one Firebase app, one session, one repository per area). */
class AdminServices private constructor(
    context: Context,
) {
    val firebase = AdminFirebase(context)
    val auth = AuthRepository(firebase)
    val users = UsersRepository(firebase)
    val codes = CodesRepository(firebase)
    val dashboard = DashboardRepository(firebase)
    val announcements = AnnouncementsRepository(firebase)
    val config = ConfigRepository(firebase)
    val reports = ReportsRepository(firebase)
    val audit = AuditRepository(firebase)
    val staff = StaffRepository(firebase)
    val prefs = AdminPrefs(context)

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    fun notify(notice: Notice) {
        _notices.tryEmit(notice)
    }

    companion object {
        @Volatile private var instance: AdminServices? = null

        fun get(context: Context): AdminServices =
            instance ?: synchronized(this) { instance ?: AdminServices(context.applicationContext).also { instance = it } }
    }
}

/** Theme choice: "system", "light" or "dark". Language is kept by AppCompat (per-app locales). */
class AdminPrefs(
    context: Context,
) {
    private val sp = context.getSharedPreferences("mawja_admin", Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(sp.getString("theme", "system") ?: "system")
    val theme: StateFlow<String> = _theme.asStateFlow()

    fun setTheme(value: String) {
        sp.edit().putString("theme", value).apply()
        _theme.value = value
    }
}
