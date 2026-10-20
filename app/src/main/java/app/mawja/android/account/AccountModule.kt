/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AccountModule {
    /** [AccountBackendFactory] is flavor-specific: Firebase in github builds, guest-only in foss. */
    @Provides
    @Singleton
    fun accountBackend(
        @ApplicationContext context: Context,
    ): AccountBackend = AccountBackendFactory.create(context)
}
