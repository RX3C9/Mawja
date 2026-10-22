/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader.remote

import android.content.Context
import android.os.Build
import android.util.Log
import app.mawja.downloader.BuildConfig
import app.mawja.remote.DownloadBlock
import app.mawja.remote.RemoteFetcher
import app.mawja.remote.RemoteGate
import app.mawja.remote.RemoteSettings
import app.mawja.remote.TransferAdmission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * config/downloader, read token-free from Firestore when the service starts and when the app is
 * opened (at most every [MAX_AGE_MS] in the background), cached in SharedPreferences so the last
 * known state applies offline and on a cold start. Everything stays open when no Firebase project
 * is configured or nothing was ever fetched.
 */
object DownloaderRemoteConfig {
    private const val TAG = "DownloaderRemote"
    private const val PREFS = "mawja_remote"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val DOC = "downloader"

    /** Background refreshes (service start, transfers) wait this long between reads. */
    val MAX_AGE_MS = TimeUnit.HOURS.toMillis(6)

    /** Opening the app reads again once the cache is older than this. */
    val APP_OPEN_MAX_AGE_MS = TimeUnit.MINUTES.toMillis(15)

    val enabled: Boolean = BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loaded = AtomicBoolean(false)
    private val refreshing = AtomicBoolean(false)
    private val admission = TransferAdmission()

    private val _settings = MutableStateFlow(RemoteSettings.DEFAULT)
    val settings: StateFlow<RemoteSettings> = _settings.asStateFlow()

    @Volatile
    private var fetchedAt = 0L

    /** The installed build's versionCode (CI nightlies are numbered by run, not by the gradle file). */
    @Volatile
    var versionCode: Long = 0L
        private set

    /** Loads the cache once per process. Cheap after the first call. */
    fun init(context: Context) {
        if (!loaded.compareAndSet(false, true)) return
        val app = context.applicationContext
        versionCode =
            runCatching {
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            }.getOrNull()?.takeIf { it > 0 } ?: BuildConfig.VERSION_CODE.toLong()
        if (!enabled) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        RemoteSettings.decode(prefs.getString(KEY_SETTINGS, null))?.let { _settings.value = it }
        fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
    }

    /** Reads config/downloader in the background when the cache is older than [maxAgeMs]. */
    fun refreshIfStale(
        context: Context,
        maxAgeMs: Long = MAX_AGE_MS,
    ) {
        init(context)
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now - fetchedAt in 0 until maxAgeMs) return
        if (!refreshing.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            try {
                val fresh = RemoteFetcher(BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_API_KEY).fetchSettings(DOC)
                if (fresh == null) {
                    Log.i(TAG, "config/downloader unreachable; keeping the cache")
                    return@launch
                }
                _settings.value = fresh
                fetchedAt = System.currentTimeMillis()
                app
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SETTINGS, fresh.encode())
                    .putLong(KEY_FETCHED_AT, fetchedAt)
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "Remote config refresh failed", e)
            } finally {
                refreshing.set(false)
            }
        }
    }

    fun gate(): RemoteGate = if (enabled) _settings.value.gate(versionCode) else RemoteGate.Open

    /** What stops new downloads right now (NONE when open). */
    fun block(): DownloadBlock = if (enabled) DownloadBlock.of(_settings.value, versionCode) else DownloadBlock.NONE

    /** [block] for one request of [jobId]; a job already running before the block may finish. */
    fun admit(jobId: String?): DownloadBlock = admission.admit(jobId, block(), System.currentTimeMillis())

    /** The remote maintenance message in [language], blank when the config has none. */
    fun maintenanceMessage(language: String): String = (gate() as? RemoteGate.Maintenance)?.message(language).orEmpty()
}
