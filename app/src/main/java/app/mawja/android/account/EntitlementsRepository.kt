/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.mawja.android.data.local.safePreferencesDataStore
import app.mawja.android.remote.RemoteConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.entitlementStore by safePreferencesDataStore(name = "mawja_entitlements")
private val LAST_TIER = stringPreferencesKey("last_tier")

/**
 * The user's [Entitlements] as a StateFlow. Free for guests, foss builds, offline and unknown
 * profiles; the last tier seen is cached on the device and used while a session restores. Remote
 * tier gates and the premiumThemes switch from config/app apply on top ([withRemote]).
 */
@Singleton
class EntitlementsRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val account: AccountBackend,
        private val remote: RemoteConfigRepository,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val cachedTier =
            context.entitlementStore.data.map { prefs -> prefs[LAST_TIER]?.let { MawjaTier.of(it) } }

        val entitlements: StateFlow<Entitlements> =
            combine(account.state, cachedTier, remote.settings) { state, cached, settings ->
                Entitlements.forTier(effectiveTier(state, cached)).withRemote(settings)
            }
                .distinctUntilChanged()
                .stateIn(scope, SharingStarted.Eagerly, Entitlements.FREE)

        init {
            scope.launch {
                account.state.collect { state ->
                    val tier = tierToCache(state) ?: return@collect
                    context.entitlementStore.edit { it[LAST_TIER] = tier.id }
                }
            }
        }

        fun has(feature: MawjaFeature): Boolean = entitlements.value.has(feature)

        /** Re-reads the membership from the backend, e.g. right after a code was activated. */
        suspend fun refresh() = account.refreshProfile()
    }
