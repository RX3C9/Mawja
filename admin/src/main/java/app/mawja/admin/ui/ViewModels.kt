/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.ui

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mawja.admin.BuildConfig
import app.mawja.admin.R
import app.mawja.admin.core.Component
import app.mawja.admin.core.ManualTier
import app.mawja.admin.core.Perm
import app.mawja.admin.core.Permissions
import app.mawja.admin.core.RemoteConfig
import app.mawja.admin.data.AdminServices
import app.mawja.admin.data.Announcement
import app.mawja.admin.data.AuditEntry
import app.mawja.admin.data.CodeBatch
import app.mawja.admin.data.CodeEntry
import app.mawja.admin.data.DashboardData
import app.mawja.admin.data.Device
import app.mawja.admin.data.GeneratedBatch
import app.mawja.admin.data.Notice
import app.mawja.admin.data.Redemption
import app.mawja.admin.data.Report
import app.mawja.admin.data.Session
import app.mawja.admin.data.SignInCancelled
import app.mawja.admin.data.StaffMember
import app.mawja.admin.data.UserRecord
import app.mawja.admin.data.problem
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Date

/** Shared plumbing: a busy flag and errors reported to the snackbar as [Notice]s. */
abstract class BaseViewModel(
    app: Application,
) : AndroidViewModel(app) {
    protected val services = AdminServices.get(app)
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    protected fun launchSafe(
        done: Int? = null,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
                if (done != null) services.notify(Notice(text = done))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignInCancelled) {
                // closed the picker: nothing to say
            } catch (e: Throwable) {
                services.notify(Notice(problem = e.problem(), detail = e.message))
            } finally {
                _busy.value = false
            }
        }
    }

    protected fun can(perm: Perm): Boolean = Permissions.can((services.auth.session.value as? Session.Active)?.staff?.role, perm)
}

// ---- Session ---------------------------------------------------------------------------------

class SessionViewModel(
    app: Application,
) : BaseViewModel(app) {
    val session = services.auth.session
    val theme = services.prefs.theme
    val googleAvailable get() = services.auth.googleAvailable

    /** The device lock was passed this process (kept across rotation and language changes). */
    var unlocked = false

    private val _adminConfig = MutableStateFlow<RemoteConfig?>(null)
    val adminConfig: StateFlow<RemoteConfig?> = _adminConfig.asStateFlow()

    init {
        if (services.firebase.configured) {
            launchSafe { services.auth.refresh() }
            viewModelScope.launch {
                _adminConfig.value = runCatching { services.config.load(Component.ADMIN).config }.getOrNull()
            }
        }
    }

    fun signIn(
        email: String,
        password: String,
    ) = launchSafe { services.auth.signIn(email, password) }

    fun signInWithGoogle(activity: Activity) = launchSafe { services.auth.signInWithGoogle(activity) }

    fun resetPassword(email: String) = launchSafe(R.string.reset_sent) { services.auth.sendPasswordReset(email) }

    fun recheck() = launchSafe { services.auth.refresh() }

    fun signOut() = services.auth.signOut()

    fun setTheme(value: String) = services.prefs.setTheme(value)

    /** The admin app's own kill switch / minimum version (owners are never locked out). */
    fun blocked(config: RemoteConfig?): Boolean = config?.blocks(BuildConfig.VERSION_CODE.toLong()) == true
}

// ---- Dashboard -------------------------------------------------------------------------------

class DashboardViewModel(
    app: Application,
) : BaseViewModel(app) {
    private val _data = MutableStateFlow<DashboardData?>(null)
    val data: StateFlow<DashboardData?> = _data.asStateFlow()

    fun load(force: Boolean = false) {
        if (_data.value != null && !force) return
        launchSafe { _data.value = services.dashboard.load(includeAudit = can(Perm.AUDIT_VIEW)) }
    }
}

// ---- Users -----------------------------------------------------------------------------------

data class UsersState(
    val query: String = "",
    val searched: Boolean = false,
    val results: List<UserRecord> = emptyList(),
    val filter: Pair<String, String>? = null,
    val list: List<UserRecord> = emptyList(),
    val end: Boolean = false,
    val selected: UserRecord? = null,
    val redemptions: List<Redemption> = emptyList(),
    val devices: List<Device> = emptyList(),
)

class UsersViewModel(
    app: Application,
) : BaseViewModel(app) {
    private val repo = services.users
    private val _state = MutableStateFlow(UsersState())
    val state: StateFlow<UsersState> = _state.asStateFlow()
    private var cursor: DocumentSnapshot? = null

    fun search(q: String) =
        launchSafe {
            val results = repo.search(q)
            _state.update { it.copy(query = q, searched = true, results = results) }
            if (results.size == 1) open(results.first().uid)
        }

    fun clearSearch() = _state.update { it.copy(query = "", searched = false, results = emptyList()) }

    fun browse(filter: Pair<String, String>?) {
        cursor = null
        _state.update { it.copy(filter = filter, list = emptyList(), end = false) }
        more()
    }

    fun more() =
        launchSafe {
            val f = _state.value.filter
            val page = repo.browse(f?.first, f?.second, cursor)
            cursor = page.last
            _state.update { it.copy(list = it.list + page.items, end = page.end) }
        }

    fun open(uid: String) =
        launchSafe {
            val user = repo.byUid(uid) ?: return@launchSafe
            _state.update { it.copy(selected = user, redemptions = emptyList(), devices = emptyList()) }
            val r = runCatching { repo.redemptions(uid) }.getOrDefault(emptyList())
            val d = runCatching { repo.devices(uid) }.getOrDefault(emptyList())
            _state.update { if (it.selected?.uid == uid) it.copy(redemptions = r, devices = d) else it }
        }

    fun close() = _state.update { it.copy(selected = null) }

    private fun reloadSelected() {
        val uid = _state.value.selected?.uid ?: return
        launchSafe {
            val user = repo.byUid(uid) ?: return@launchSafe
            _state.update { s -> s.copy(selected = user, list = s.list.map { if (it.uid == uid) user else it }, results = s.results.map { if (it.uid == uid) user else it }) }
        }
    }

    fun setTier(
        tier: String,
        days: Int,
        extend: Boolean,
    ) {
        val u = _state.value.selected ?: return
        launchSafe(R.string.saved) {
            val until = ManualTier.until(tier, days, extend, u.tierUntil?.time, System.currentTimeMillis())
            repo.setTier(u.uid, tier, until?.let(::Date), u.tier)
            reloadSelected()
        }
    }

    fun setStatus(
        status: String,
        reason: String,
    ) {
        val u = _state.value.selected ?: return
        launchSafe(R.string.saved) {
            repo.setStatus(u.uid, status, reason)
            reloadSelected()
        }
    }

    fun setBadges(
        badges: List<String>,
        verified: Boolean,
    ) {
        val u = _state.value.selected ?: return
        launchSafe(R.string.saved) {
            repo.setBadges(u.uid, badges, verified)
            reloadSelected()
        }
    }

    fun setNote(note: String) {
        val u = _state.value.selected ?: return
        launchSafe(R.string.saved) {
            repo.setNote(u.uid, note)
            reloadSelected()
        }
    }

    fun resetSync() {
        val u = _state.value.selected ?: return
        launchSafe(R.string.sync_reset_done) {
            repo.resetSync(u.uid)
            reloadSelected()
        }
    }
}

// ---- Codes -----------------------------------------------------------------------------------

data class CodesState(
    val batches: List<CodeBatch> = emptyList(),
    val end: Boolean = false,
    val openBatch: String? = null,
    val batchCodes: List<CodeEntry> = emptyList(),
    val redemptions: List<Redemption> = emptyList(),
    /** Codes just generated: shown once, never stored in plain text. */
    val generated: GeneratedBatch? = null,
    val found: CodeEntry? = null,
    val foundRedemptions: List<Redemption> = emptyList(),
    val notFound: Boolean = false,
)

class CodesViewModel(
    app: Application,
) : BaseViewModel(app) {
    private val repo = services.codes
    private val _state = MutableStateFlow(CodesState())
    val state: StateFlow<CodesState> = _state.asStateFlow()
    private var cursor: DocumentSnapshot? = null
    private var loaded = false

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        cursor = null
        launchSafe {
            val page = repo.batches(null)
            cursor = page.last
            val redemptions = runCatching { repo.recentRedemptions() }.getOrDefault(emptyList())
            _state.update { it.copy(batches = page.items, end = page.end, redemptions = redemptions) }
        }
    }

    fun more() =
        launchSafe {
            val page = repo.batches(cursor)
            cursor = page.last
            _state.update { it.copy(batches = it.batches + page.items, end = page.end) }
        }

    fun generate(
        tier: String,
        days: Int,
        count: Int,
        maxUses: Int,
        validForDays: Int,
        note: String,
    ) = launchSafe {
        val expires = if (validForDays > 0) Date(System.currentTimeMillis() + validForDays * 86_400_000L) else null
        val result = repo.generate(tier, days, count, maxUses, expires, note)
        _state.update { it.copy(generated = result) }
        if (result.error != null) services.notify(Notice(detail = result.error))
        load(force = true)
    }

    fun dismissGenerated() = _state.update { it.copy(generated = null) }

    fun toggleBatch(id: String) {
        if (_state.value.openBatch == id) {
            _state.update { it.copy(openBatch = null, batchCodes = emptyList()) }
            return
        }
        launchSafe {
            val codes = repo.codesIn(id)
            _state.update { it.copy(openBatch = id, batchCodes = codes) }
        }
    }

    fun find(code: String) =
        launchSafe {
            val found = repo.find(code)
            val who = found?.let { runCatching { repo.redemptionsOf(it.hash) }.getOrDefault(emptyList()) }.orEmpty()
            _state.update { it.copy(found = found, foundRedemptions = who, notFound = found == null) }
        }

    fun revoke(hash: String) =
        launchSafe(R.string.codes_revoked_done) {
            repo.revoke(hash)
            val open = _state.value.openBatch
            val codes = open?.let { repo.codesIn(it) } ?: _state.value.batchCodes
            _state.update { s -> s.copy(batchCodes = codes, found = s.found?.let { if (it.hash == hash) it.copy(revoked = true) else it }) }
        }

    fun revokeBatch(id: String) =
        launchSafe(R.string.codes_revoked_done) {
            repo.revokeBatch(id)
            val codes = if (_state.value.openBatch == id) repo.codesIn(id) else _state.value.batchCodes
            _state.update { s -> s.copy(batches = s.batches.map { if (it.id == id) it.copy(revoked = true) else it }, batchCodes = codes) }
        }
}

// ---- Announcements ---------------------------------------------------------------------------

class AnnouncementsViewModel(
    app: Application,
) : BaseViewModel(app) {
    private val repo = services.announcements
    private val _items = MutableStateFlow<List<Announcement>>(emptyList())
    val items: StateFlow<List<Announcement>> = _items.asStateFlow()
    private val _end = MutableStateFlow(false)
    val end: StateFlow<Boolean> = _end.asStateFlow()

    /** The announcement being edited (id blank = new), or null. */
    private val _editing = MutableStateFlow<Announcement?>(null)
    val editing: StateFlow<Announcement?> = _editing.asStateFlow()
    private var cursor: DocumentSnapshot? = null
    private var loaded = false

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        cursor = null
        launchSafe {
            val page = repo.list(null)
            cursor = page.last
            _items.value = page.items
            _end.value = page.end
        }
    }

    fun more() =
        launchSafe {
            val page = repo.list(cursor)
            cursor = page.last
            _items.value = _items.value + page.items
            _end.value = page.end
        }

    fun edit(a: Announcement?) {
        _editing.value = a
    }

    fun newDraft() {
        _editing.value = Announcement("", "", "", null, null, "banner", "", "", true, Date(), null, null)
    }

    fun save(a: Announcement) =
        launchSafe(R.string.saved) {
            repo.save(a)
            _editing.value = null
            load(force = true)
        }

    fun setActive(
        a: Announcement,
        active: Boolean,
    ) = launchSafe(R.string.saved) {
        repo.save(a.copy(active = active))
        _items.value = _items.value.map { if (it.id == a.id) it.copy(active = active) else it }
    }
}

// ---- Remote config ---------------------------------------------------------------------------

data class ConfigState(
    val component: Component = Component.APP,
    val original: RemoteConfig? = null,
    val draft: RemoteConfig = RemoteConfig(),
    val updatedAt: Date? = null,
    val updatedBy: String? = null,
)

class ConfigViewModel(
    app: Application,
) : BaseViewModel(app) {
    private val _state = MutableStateFlow(ConfigState())
    val state: StateFlow<ConfigState> = _state.asStateFlow()

    fun select(component: Component) {
        if (_state.value.component == component && _state.value.original != null) return
        _state.value = ConfigState(component = component)
        launchSafe {
            val loaded = services.config.load(component)
            _state.update { if (it.component == component) it.copy(original = loaded.config, draft = loaded.config, updatedAt = loaded.updatedAt, updatedBy = loaded.updatedBy) else it }
        }
    }

    fun edit(change: (RemoteConfig) -> RemoteConfig) = _state.update { it.copy(draft = change(it.draft)) }

    fun discard() = _state.update { it.copy(draft = it.original ?: RemoteConfig()) }

    fun save() {
        val s = _state.value
        val old = s.original ?: return
        launchSafe(R.string.saved) {
            services.config.save(s.component, old, s.draft)
            val loaded = services.config.load(s.component)
            _state.update { it.copy(original = loaded.config, draft = loaded.config, updatedAt = loaded.updatedAt, updatedBy = loaded.updatedBy) }
        }
    }
}

// ---- Reports ---------------------------------------------------------------------------------

class ReportsViewModel(
    app: Application,
) : BaseViewModel(app) {
    private val repo = services.reports
    private val _status = MutableStateFlow<String?>("open")
    val status: StateFlow<String?> = _status.asStateFlow()
    private val _items = MutableStateFlow<List<Report>>(emptyList())
    val items: StateFlow<List<Report>> = _items.asStateFlow()
    private val _end = MutableStateFlow(false)
    val end: StateFlow<Boolean> = _end.asStateFlow()
    private val _selected = MutableStateFlow<Report?>(null)
    val selected: StateFlow<Report?> = _selected.asStateFlow()
    private var cursor: DocumentSnapshot? = null
    private var loaded = false

    val myUid: String? get() = (services.auth.session.value as? Session.Active)?.staff?.uid

    fun load(
        status: String? = _status.value,
        force: Boolean = false,
    ) {
        if (loaded && !force && status == _status.value) return
        loaded = true
        _status.value = status
        cursor = null
        launchSafe {
            val page = repo.list(status, null)
            cursor = page.last
            _items.value = page.items
            _end.value = page.end
        }
    }

    fun more() =
        launchSafe {
            val page = repo.list(_status.value, cursor)
            cursor = page.last
            _items.value = _items.value + page.items
            _end.value = page.end
        }

    fun open(r: Report?) {
        _selected.value = r
    }

    fun save(
        r: Report,
        status: String,
        assignee: String?,
        note: String,
        reply: String,
    ) = launchSafe(R.string.saved) {
        repo.update(r.id, status, assignee, note, reply)
        val updated = r.copy(status = status, assignee = assignee, note = note, reply = reply, updatedAt = Date())
        _selected.value = null
        _items.value = _items.value.mapNotNull { if (it.id == r.id) updated.takeIf { _status.value == null || _status.value == status } else it }
    }
}

// ---- Audit -----------------------------------------------------------------------------------

class AuditViewModel(
    app: Application,
) : BaseViewModel(app) {
    private val _items = MutableStateFlow<List<AuditEntry>>(emptyList())
    val items: StateFlow<List<AuditEntry>> = _items.asStateFlow()
    private val _end = MutableStateFlow(false)
    val end: StateFlow<Boolean> = _end.asStateFlow()
    private var cursor: DocumentSnapshot? = null
    private var loaded = false

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        cursor = null
        launchSafe {
            val page = services.audit.list(null)
            cursor = page.last
            _items.value = page.items
            _end.value = page.end
        }
    }

    fun more() =
        launchSafe {
            val page = services.audit.list(cursor)
            cursor = page.last
            _items.value = _items.value + page.items
            _end.value = page.end
        }
}

// ---- Staff -----------------------------------------------------------------------------------

class StaffViewModel(
    app: Application,
) : BaseViewModel(app) {
    private val _items = MutableStateFlow<List<StaffMember>>(emptyList())
    val items: StateFlow<List<StaffMember>> = _items.asStateFlow()

    fun load() = launchSafe { _items.value = services.staff.list() }

    fun setRole(
        uid: String,
        role: String,
        email: String?,
        note: String,
    ) = launchSafe(R.string.saved) {
        services.staff.setRole(uid.trim(), role, email, note)
        _items.value = services.staff.list()
    }

    /** Finds a UID for an email in the users directory (null when nobody has that email). */
    fun addByEmail(
        email: String,
        role: String,
        note: String,
    ) = launchSafe(R.string.saved) {
        val user = services.users.search(email).firstOrNull() ?: throw IllegalArgumentException(email)
        services.staff.setRole(user.uid, role, user.email ?: email, note)
        _items.value = services.staff.list()
    }
}
