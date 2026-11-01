/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.network

/** Why the app has no connection, as far as the user needs to know (v1 #125). */
enum class OfflineReason { NONE, AIRPLANE_MODE, NO_CONNECTION }

/** Pure rules for offline / airplane mode: which screens still work, and what to tell the user. */
object OfflineMode {
    private val offlineRoutes = setOf("downloads", "settings", "notes")
    private val offlinePrefixes = listOf("player", "musicPlayer", "downloads", "settings")

    fun reason(
        connected: Boolean,
        airplaneMode: Boolean,
    ): OfflineReason =
        when {
            connected -> OfflineReason.NONE
            airplaneMode -> OfflineReason.AIRPLANE_MODE
            else -> OfflineReason.NO_CONNECTION
        }

    /** True when the screen works from local data (downloads, players, settings, notes). */
    fun worksOffline(route: String): Boolean = route in offlineRoutes || offlinePrefixes.any { route.startsWith(it) }

    /** Whether to show the "you are offline" notice on [route] right now. */
    fun shouldNotify(
        reason: OfflineReason,
        route: String,
    ): Boolean = reason != OfflineReason.NONE && !worksOffline(route)
}
