/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.smartnotify

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.mawja.android.data.local.safePreferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.ZoneId

private val Context.smartNotifyStore by safePreferencesDataStore(name = "mawja_smart_notify")
private val STATE = stringPreferencesKey("state_json")
private val ENABLED = booleanPreferencesKey("enabled")
private val DAILY_LIMIT = intPreferencesKey("daily_limit")
private val QUIET_START = intPreferencesKey("quiet_start")
private val QUIET_END = intPreferencesKey("quiet_end")

/** On-device storage for the adaptive notifications: what was learned and the user's limits. */
class SmartNotifyStore(
    context: Context,
) {
    private val store = context.applicationContext.smartNotifyStore

    val config: Flow<SmartNotifyConfig> =
        store.data.map { prefs ->
            val defaults = SmartNotifyConfig()
            defaults.copy(
                enabled = prefs[ENABLED] ?: defaults.enabled,
                dailyLimit = (prefs[DAILY_LIMIT] ?: defaults.dailyLimit).coerceIn(0, MAX_DAILY_LIMIT),
                quietStartHour = (prefs[QUIET_START] ?: defaults.quietStartHour).coerceIn(0, 23),
                quietEndHour = (prefs[QUIET_END] ?: defaults.quietEndHour).coerceIn(0, 23),
            )
        }

    val enabled: Flow<Boolean> = config.map { it.enabled }

    val dailyLimit: Flow<Int> = config.map { it.dailyLimit }

    suspend fun setEnabled(value: Boolean) {
        store.edit { it[ENABLED] = value }
    }

    suspend fun setDailyLimit(value: Int) {
        store.edit { it[DAILY_LIMIT] = value.coerceIn(1, MAX_DAILY_LIMIT) }
    }

    suspend fun state(): SmartNotifyState = SmartNotifyCodec.decode(store.data.first()[STATE])

    suspend fun update(transform: (SmartNotifyState) -> SmartNotifyState) {
        store.edit { prefs -> prefs[STATE] = SmartNotifyCodec.encode(transform(SmartNotifyCodec.decode(prefs[STATE]))) }
    }

    /** One app session; teaches the engine this person's usual hours. */
    suspend fun recordActivity(now: Long = System.currentTimeMillis()) =
        update { SmartNotifyEngine.recordActivity(it, now, ZoneId.systemDefault()) }

    suspend fun recordOutcome(
        hash: String,
        outcome: SmartOutcome,
    ) = update { SmartNotifyEngine.recordOutcome(it, hash, outcome) }

    /** Forgets everything learned (the limits stay). */
    suspend fun reset() {
        store.edit { it.remove(STATE) }
    }

    companion object {
        const val MAX_DAILY_LIMIT = 4
    }
}
