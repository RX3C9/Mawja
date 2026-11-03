/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.report

import android.content.Context
import android.os.Build
import app.mawja.android.BuildConfig
import app.mawja.android.account.AccountBackend
import app.mawja.android.account.AccountState
import app.mawja.android.account.FirestoreRest
import app.mawja.android.account.Moderation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What a report is about (P9): the four kinds of the Mawja 2.0 plan. */
enum class ReportType(
    val id: String,
) {
    VIDEO_NOT_WORKING("video"),
    BUG("bug"),
    CONTENT("content"),
    SUGGESTION("suggestion"),
}

/** A report before it is sent, with the checks the rules also make. */
data class ReportDraft(
    val type: ReportType,
    val message: String,
    val attachLog: Boolean = false,
    val videoId: String? = null,
) {
    val trimmed: String get() = message.trim()

    /** Ready to send: something written (at least 5 characters) and under the rules' limit. */
    val valid: Boolean get() = trimmed.length in MIN..MAX

    companion object {
        const val MIN = 5
        const val MAX = 4000
        const val LOG_MAX = 20_000

        /** Keeps the newest end of a log (the crash is usually last) within [LOG_MAX]. */
        fun clipLog(log: String): String = if (log.length <= LOG_MAX) log else log.takeLast(LOG_MAX)
    }
}

enum class ReportOutcome { SENT, SIGNED_OUT, RESTRICTED, FAILED }

/**
 * Sends a report to Firestore `reports/{id}` with the account's ID token (REST, no Firestore SDK in
 * :app). Attaches device, Android and app version, and this app's own recent log only when the
 * user ticked it. Staff handle reports in the admin app; an AI triage loop reads them later (P9).
 */
@Singleton
class ReportRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val account: AccountBackend,
    ) {
        val available: Boolean get() = account.available && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

        fun signedIn(): Boolean = account.state.value is AccountState.SignedIn

        suspend fun send(draft: ReportDraft): ReportOutcome {
            val state = account.state.value as? AccountState.SignedIn ?: return ReportOutcome.SIGNED_OUT
            val mod = state.profile.moderation
            val now = System.currentTimeMillis()
            if (mod.blocked(now) || mod.muted(now) || mod.locked(Moderation.FEATURE_REPORTS)) return ReportOutcome.RESTRICTED
            if (!draft.valid) return ReportOutcome.FAILED
            val fields =
                buildMap<String, Any?> {
                    put("uid", state.profile.uid)
                    put("type", draft.type.id)
                    put("message", draft.trimmed)
                    put("status", "open")
                    put("appVersion", BuildConfig.VERSION_NAME)
                    put("flavor", BuildConfig.FLAVOR)
                    put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    put("locale", Locale.getDefault().toLanguageTag())
                    draft.videoId?.takeIf { it.isNotBlank() }?.let { put("videoId", it) }
                    if (draft.attachLog) ownLog()?.let { put("log", ReportDraft.clipLog(it)) }
                }
            return runCatching {
                val rest = FirestoreRest(BuildConfig.FIREBASE_PROJECT_ID) { account.idToken() }
                rest.commit(
                    listOf(FirestoreRest.Update("reports/${UUID.randomUUID()}", fields, serverTimeFields = listOf("createdAt"), mustExist = false)),
                )
                ReportOutcome.SENT
            }.getOrElse { e -> if ((e as? FirestoreRest.HttpError)?.code == 403) ReportOutcome.RESTRICTED else ReportOutcome.FAILED }
        }

        /** This process's recent log lines (Android only shows an app its own). */
        private suspend fun ownLog(): String? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val p = ProcessBuilder("logcat", "-d", "-t", "400", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
                    p.inputStream.bufferedReader().use { it.readText() }.also { p.destroy() }
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
    }
