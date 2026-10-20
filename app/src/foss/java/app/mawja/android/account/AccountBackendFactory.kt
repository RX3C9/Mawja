/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import android.content.Context

/** FOSS builds carry no Google services, so they run in guest mode only. */
object AccountBackendFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): AccountBackend = GuestOnlyAccountBackend()
}
