/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.network

import android.content.Context
import androidx.core.content.edit

/** What kind of connection the device is on, as far as data cost and speed go. */
enum class NetworkProfile {
    OFFLINE,
    /** Unmetered wifi or ethernet with usable bandwidth. */
    FAST_UNMETERED,
    /** Wifi that is metered (a phone hotspot, a capped plan) or the system data saver is on. */
    METERED_WIFI,
    /** Wifi or ethernet slower than [SmartNetwork.SLOW_KBPS]. */
    SLOW_WIFI,
    CELLULAR,
    /** Cellular slower than [SmartNetwork.SLOW_KBPS]. */
    SLOW_CELLULAR,
}

/** One reading of the active network, decoupled from Android so the rules are unit-testable. */
data class NetworkSnapshot(
    val online: Boolean,
    val wifi: Boolean,
    val ethernet: Boolean,
    val metered: Boolean,
    /** Estimated downstream bandwidth in kbps; 0 when the platform gives no estimate. */
    val downKbps: Int,
    val dataSaver: Boolean,
)

/**
 * Mawja smart network manager (PLAN_V2 #55). The app's quality, prefetch, wifi-only auto-download
 * and animated-artwork decisions all ask "is this wifi?". With smart network on, the answer becomes
 * "is this a fast, unmetered connection?": ethernet counts (TV boxes), while a metered hotspot, a
 * slow wifi or the system data saver are treated like mobile data so the data-saving settings apply.
 */
object SmartNetwork {
    /** Below this estimated bandwidth a connection is treated as slow (≈ smooth 480p). */
    const val SLOW_KBPS = 1_500

    fun classify(s: NetworkSnapshot): NetworkProfile {
        if (!s.online) return NetworkProfile.OFFLINE
        val slow = s.downKbps in 1 until SLOW_KBPS
        return if (s.wifi || s.ethernet) {
            when {
                slow -> NetworkProfile.SLOW_WIFI
                s.metered || s.dataSaver -> NetworkProfile.METERED_WIFI
                else -> NetworkProfile.FAST_UNMETERED
            }
        } else {
            if (slow) NetworkProfile.SLOW_CELLULAR else NetworkProfile.CELLULAR
        }
    }

    /**
     * Whether the app should behave as on wifi. Smart off keeps the original rule (the wifi transport,
     * whatever its state); smart on asks for a fast unmetered connection, wifi or ethernet.
     */
    fun treatAsWifi(
        s: NetworkSnapshot,
        smart: Boolean,
    ): Boolean = if (!smart) s.wifi else classify(s) == NetworkProfile.FAST_UNMETERED

    private const val PREFS = "mawja_smart_network"
    private const val KEY_ENABLED = "enabled"

    /** Read synchronously on every check, so it lives in plain SharedPreferences. On by default. */
    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, enabled) }
}
