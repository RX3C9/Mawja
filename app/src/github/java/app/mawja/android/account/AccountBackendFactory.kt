/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import android.content.Context
import app.mawja.android.BuildConfig

/** Firebase accounts when this build carries a Firebase config, guest-only otherwise. */
object AccountBackendFactory {
    fun create(context: Context): AccountBackend {
        val configured =
            BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() &&
                BuildConfig.FIREBASE_APP_ID.isNotBlank() &&
                BuildConfig.FIREBASE_API_KEY.isNotBlank()
        if (!configured) return GuestOnlyAccountBackend()
        return runCatching { FirebaseAccountBackend(context.applicationContext) }
            .getOrElse { GuestOnlyAccountBackend() }
    }
}
