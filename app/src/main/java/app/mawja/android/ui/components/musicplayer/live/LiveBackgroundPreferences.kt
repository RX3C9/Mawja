/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.components.musicplayer.live

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mawja.android.account.Entitlements
import app.mawja.android.account.EntitlementsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The live background choice, in its own small prefs file (`mawja_visual_player`) so it never
 * touches Flow's player preferences. The stored value is the user's pick; [LiveBackgroundGate]
 * decides what actually shows.
 */
object LiveBackgroundPreferences {
    private const val PREFS = "mawja_visual_player"
    private const val KEY_STYLE = "live_background"

    private val _style = MutableStateFlow(LiveBackgroundStyle.OFF)

    @Volatile
    private var loaded = false

    fun style(context: Context): StateFlow<LiveBackgroundStyle> {
        ensureLoaded(context)
        return _style.asStateFlow()
    }

    fun setStyle(
        context: Context,
        style: LiveBackgroundStyle,
    ) {
        ensureLoaded(context)
        prefs(context).edit().putString(KEY_STYLE, style.id).apply()
        _style.value = style
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _style.value = LiveBackgroundStyle.of(prefs(context).getString(KEY_STYLE, null))
            loaded = true
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Live background state for the full player and its setting row. */
@HiltViewModel
class LiveBackgroundViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        entitlementsRepository: EntitlementsRepository,
    ) : ViewModel() {
        val entitlements: StateFlow<Entitlements> = entitlementsRepository.entitlements

        /** What the user picked, shown in Settings even while it is locked. */
        val selected: StateFlow<LiveBackgroundStyle> = LiveBackgroundPreferences.style(context)

        /** What the player draws: the pick, or Off when the membership does not cover it. */
        val effective: StateFlow<LiveBackgroundStyle> =
            combine(selected, entitlements) { style, ent -> LiveBackgroundGate.effective(style, ent) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, LiveBackgroundGate.effective(selected.value, entitlements.value))

        fun isLocked(style: LiveBackgroundStyle): Boolean = LiveBackgroundGate.isLocked(style, entitlements.value)

        fun setStyle(style: LiveBackgroundStyle) {
            LiveBackgroundPreferences.setStyle(context, style)
        }
    }
