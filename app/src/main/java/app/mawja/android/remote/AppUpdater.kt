/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Where the in-app update stands. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data class Downloading(
        val done: Long,
        val total: Long,
    ) : UpdateState {
        val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    }

    /** Verified (hash, package, signer, newer) and waiting for the system installer. */
    data class Ready(
        val file: File,
    ) : UpdateState

    data class Failed(
        val reason: UpdateFailure,
    ) : UpdateState
}

enum class UpdateFailure { NETWORK, HASH, NOT_MAWJA, SIGNER, NOT_NEWER, STORAGE }

/**
 * Mawja 2.0 in-app update (P7): downloads the APK named in config/app, refuses it unless its
 * SHA-256 matches the published one, its package is this app, it is signed by the same key and it
 * is newer, then hands it to the system installer. Android never lets an app outside a store
 * install silently, so a forced update keeps the app locked until the user taps Install.
 */
@Singleton
class AppUpdater
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
        val state: StateFlow<UpdateState> = _state.asStateFlow()
        private var job: Job? = null

        private val client by lazy {
            OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        }

        private val dir get() = File(context.cacheDir, "updates")

        fun start(
            url: String,
            sha256: String,
            currentVersion: Long,
        ) {
            if (job?.isActive == true || !url.startsWith("https://") || sha256.length != 64) return
            job =
                scope.launch {
                    _state.value = UpdateState.Downloading(0, -1)
                    val file = File(dir.apply { mkdirs() }, "mawja-update.apk")
                    val part = File(dir, "mawja-update.apk.part")
                    val digest = MessageDigest.getInstance("SHA-256")
                    val ok =
                        runCatching {
                            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                                if (!resp.isSuccessful) error("http ${resp.code}")
                                val body = resp.body ?: error("empty")
                                val total = body.contentLength()
                                var done = 0L
                                var lastEmit = 0L
                                part.outputStream().use { out ->
                                    body.byteStream().use { input ->
                                        val buf = ByteArray(64 * 1024)
                                        while (isActive) {
                                            val n = input.read(buf)
                                            if (n < 0) break
                                            out.write(buf, 0, n)
                                            digest.update(buf, 0, n)
                                            done += n
                                            if (done - lastEmit > 256 * 1024) {
                                                lastEmit = done
                                                _state.value = UpdateState.Downloading(done, total)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    if (ok.isFailure) {
                        part.delete()
                        _state.value = UpdateState.Failed(if (ok.exceptionOrNull() is java.io.IOException && dir.usableSpace < 50L shl 20) UpdateFailure.STORAGE else UpdateFailure.NETWORK)
                        return@launch
                    }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    val failure =
                        when {
                            !UpdateChecks.hashMatches(hex, sha256) -> UpdateFailure.HASH
                            else -> verify(part, currentVersion)
                        }
                    if (failure != null) {
                        part.delete()
                        _state.value = UpdateState.Failed(failure)
                        return@launch
                    }
                    file.delete()
                    part.renameTo(file)
                    _state.value = UpdateState.Ready(file)
                }
        }

        fun reset() {
            job?.cancel()
            _state.value = UpdateState.Idle
        }

        /** Package, signer and version checks on the downloaded archive; null when it passes. */
        private fun verify(
            apk: File,
            currentVersion: Long,
        ): UpdateFailure? {
            val pm = context.packageManager
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return UpdateFailure.NOT_MAWJA
            if (archive.packageName != context.packageName) return UpdateFailure.NOT_MAWJA
            val mine = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull() ?: return UpdateFailure.SIGNER
            if (!UpdateChecks.sameSigners(signers(mine), signers(archive))) return UpdateFailure.SIGNER
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
            return if (v > currentVersion) null else UpdateFailure.NOT_NEWER
        }

        private fun signers(info: PackageInfo): Set<String> {
            val sigs =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
                } else {
                    @Suppress("DEPRECATION") info.signatures
                }
            val md = MessageDigest.getInstance("SHA-256")
            return sigs.orEmpty().filterNotNull().map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        }

        /** Whether the system lets this app open the installer (Android 8+ asks once per app). */
        fun canInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

        fun openInstallPermission(activityContext: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            runCatching {
                activityContext.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                )
            }
        }

        fun install(
            activityContext: Context,
            file: File,
        ): Boolean {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { activityContext.startActivity(intent) }.isSuccess
        }
    }

/** Pure checks of the in-app update, JVM-tested. */
object UpdateChecks {
    fun hashMatches(
        actualHex: String,
        expectedHex: String,
    ): Boolean = expectedHex.length == 64 && actualHex.equals(expectedHex.trim(), ignoreCase = true)

    /**
     * The new APK must carry every signer the installed app has (and at least one): Android would
     * refuse a different key anyway, but checking first gives a clear message instead.
     */
    fun sameSigners(
        installed: Set<String>,
        archive: Set<String>,
    ): Boolean = installed.isNotEmpty() && archive.isNotEmpty() && installed.any { it in archive }
}
