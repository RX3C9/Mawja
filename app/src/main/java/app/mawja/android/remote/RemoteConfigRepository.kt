/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.remote

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.mawja.android.BuildConfig
import app.mawja.android.data.local.safePreferencesDataStore
import app.mawja.remote.Announcement
import app.mawja.remote.RemoteFetcher
import app.mawja.remote.RemoteGate
import app.mawja.remote.RemoteSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.remoteStore by safePreferencesDataStore(name = "mawja_remote")
private val SETTINGS = stringPreferencesKey("settings")
private val ANNOUNCEMENTS = stringPreferencesKey("announcements")
private val DISMISSED = stringSetPreferencesKey("dismissed_announcements")

/**
 * config/app and the active announcements, read token-free from Firestore on start and about every
 * six hours, cached in DataStore so the last known state applies offline and on cold start. A no-op
 * (everything open, no announcements) in foss builds and when no Firebase project is configured.
 */
@Singleton
class RemoteConfigRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        val enabled: Boolean = BuildConfig.FLAVOR != "foss" && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** The installed build's versionCode (CI nightlies are numbered by run, so not BuildConfig's). */
        val versionCode: Long by lazy {
            runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            }.getOrNull()?.takeIf { it > 0 } ?: BuildConfig.VERSION_CODE.toLong()
        }

        val settings: StateFlow<RemoteSettings> =
            if (enabled) {
                context.remoteStore.data
                    .map { RemoteSettings.decode(it[SETTINGS]) ?: RemoteSettings.DEFAULT }
                    .stateIn(scope, SharingStarted.Eagerly, RemoteSettings.DEFAULT)
            } else {
                MutableStateFlow(RemoteSettings.DEFAULT)
            }

        val announcements: StateFlow<List<Announcement>> =
            if (enabled) {
                context.remoteStore.data
                    .map { Announcement.decodeList(it[ANNOUNCEMENTS]) }
                    .stateIn(scope, SharingStarted.Eagerly, emptyList())
            } else {
                MutableStateFlow(emptyList())
            }

        val dismissed: StateFlow<Set<String>> =
            if (enabled) {
                context.remoteStore.data
                    .map { it[DISMISSED].orEmpty() }
                    .stateIn(scope, SharingStarted.Eagerly, emptySet())
            } else {
                MutableStateFlow(emptySet())
            }

        val gate: StateFlow<RemoteGate> =
            if (enabled) {
                settings.map { it.gate(versionCode) }.stateIn(scope, SharingStarted.Eagerly, RemoteGate.Open)
            } else {
                MutableStateFlow(RemoteGate.Open)
            }

        init {
            if (enabled) {
                scope.launch {
                    while (true) {
                        refresh()
                        delay(REFRESH_EVERY_MS)
                    }
                }
            }
        }

        /** The cached settings, read from disk (for workers and services that start cold). */
        suspend fun current(): RemoteSettings =
            if (!enabled) {
                RemoteSettings.DEFAULT
            } else {
                runCatching { RemoteSettings.decode(context.remoteStore.data.first()[SETTINGS]) }.getOrNull() ?: RemoteSettings.DEFAULT
            }

        suspend fun isEnabled(key: String): Boolean = current().isEnabled(key)

        /** Fetches config/app and the active announcements; a failed read keeps the cache. */
        suspend fun refresh() {
            if (!enabled) return
            withContext(Dispatchers.IO) {
                val fetcher = RemoteFetcher(BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_API_KEY)
                val fresh = fetcher.fetchSettings("app")
                val news = fetcher.fetchAnnouncements()
                if (fresh == null && news == null) {
                    Log.i(TAG, "Remote config unreachable; keeping the cache")
                    return@withContext
                }
                runCatching {
                    context.remoteStore.edit { prefs ->
                        fresh?.let { prefs[SETTINGS] = it.encode() }
                        news?.let { list ->
                            prefs[ANNOUNCEMENTS] = Announcement.encodeList(list)
                            // Forget dismissals of announcements that no longer exist.
                            val ids = list.map { it.id }.toSet()
                            prefs[DISMISSED] = prefs[DISMISSED].orEmpty().filterTo(mutableSetOf()) { it in ids }
                        }
                    }
                }.onFailure { Log.w(TAG, "Could not cache remote config", it) }
            }
        }

        fun dismiss(id: String) {
            if (!enabled) return
            scope.launch {
                runCatching { context.remoteStore.edit { it[DISMISSED] = it[DISMISSED].orEmpty() + id } }
            }
        }

        private companion object {
            const val TAG = "RemoteConfig"
            val REFRESH_EVERY_MS = TimeUnit.HOURS.toMillis(6)
        }
    }
