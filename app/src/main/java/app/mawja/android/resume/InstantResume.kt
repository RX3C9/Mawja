/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.resume

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Instant Resume (#75): after the process was killed, a launcher start reopens the page that was
 * open and puts its list back where it was. The players already come back paused on their own
 * (continue-watching mini player and the saved music queue, see Playback settings); this adds the
 * page and the scroll position.
 *
 * Cheap by design: one SharedPreferences line per page change (only when it changed) and one per
 * list after it has been still for [InstantResumePolicy.SCROLL_SETTLE_MS], all with apply().
 */
object InstantResume {
    private const val PREFS = "mawja_instant_resume"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ROUTE = "route"
    private const val KEY_SCROLL_PREFIX = "scroll:"

    private var prefs: SharedPreferences? = null
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile private var lastRoute: String? = null

    /** The page reopened by this launch; list positions are put back only on it. */
    @Volatile var restoredRoute: String? = null
        private set

    private val restoredScrollKeys = HashSet<String>()

    /** Called once from Application.onCreate. */
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = p.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(on: Boolean) {
        val p = prefs ?: return
        p.edit {
            putBoolean(KEY_ENABLED, on)
            if (!on) {
                remove(KEY_ROUTE)
                p.all.keys.filter { it.startsWith(KEY_SCROLL_PREFIX) }.forEach { remove(it) }
            }
        }
        _enabled.value = on
    }

    /** The page now on screen, from its navigation pattern and arguments. Pass-through pages are skipped. */
    fun recordPage(
        pattern: String?,
        args: Map<String, String?>,
    ) {
        if (!_enabled.value) return
        val route = InstantResumePolicy.concreteRoute(pattern, args) ?: return
        if (route == lastRoute) return
        lastRoute = route
        prefs?.edit { putString(KEY_ROUTE, InstantResumePolicy.encodeRoute(SavedRoute(route, System.currentTimeMillis()))) }
    }

    /** On a cold launcher start: the page to reopen, once. */
    fun takeColdStartRoute(): String? {
        val p = prefs ?: return null
        val route =
            InstantResumePolicy.routeToRestore(
                InstantResumePolicy.decodeRoute(p.getString(KEY_ROUTE, null)),
                System.currentTimeMillis(),
                _enabled.value,
            )
        restoredRoute = route
        lastRoute = route
        return route
    }

    /** Where list [key] was on the reopened page, at most once per process. */
    fun takeScroll(key: String): SavedScroll? {
        val p = prefs ?: return null
        synchronized(restoredScrollKeys) { if (!restoredScrollKeys.add(key)) return null }
        return InstantResumePolicy.scrollToRestore(
            InstantResumePolicy.decodeScroll(p.getString(KEY_SCROLL_PREFIX + key, null)),
            restoredRoute,
            System.currentTimeMillis(),
        )
    }

    fun recordScroll(
        key: String,
        index: Int,
        offset: Int,
    ) {
        if (!_enabled.value) return
        val route = lastRoute ?: return
        prefs?.edit {
            putString(KEY_SCROLL_PREFIX + key, InstantResumePolicy.encodeScroll(SavedScroll(route, index, offset, System.currentTimeMillis())))
        }
    }
}
