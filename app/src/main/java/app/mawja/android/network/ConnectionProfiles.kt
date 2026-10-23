/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.network

import android.content.Context
import androidx.core.content.edit
import app.mawja.android.data.local.KeystoreSecretBox
import java.util.UUID

/**
 * The saved connection profiles (#71) in SharedPreferences `mawja_custom_network`, as one JSON value
 * written by [ConnectionProfilesCodec]. Proxy passwords are sealed with KeystoreSecretBox, so a
 * backup of the file never carries them in the clear.
 */
object ConnectionProfiles {
    private const val PREFS = "mawja_custom_network"
    private const val KEY_PROFILES = "profiles"

    fun list(context: Context): List<ConnectionProfile> = ConnectionProfilesCodec.decode(prefs(context).getString(KEY_PROFILES, null))

    /** Saves [proxy] + [dns] under [name]; a profile with the same name is replaced. */
    fun save(
        context: Context,
        name: String,
        proxy: AppProxyConfig,
        dns: DnsSettings,
    ): List<ConnectionProfile> {
        val current = list(context)
        val cleanName = ConnectionProfilesCodec.cleanName(name)
        val id = current.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }?.id ?: UUID.randomUUID().toString()
        val profile = ConnectionProfile.of(id, cleanName, proxy, KeystoreSecretBox.seal(proxy.password), dns)
        return write(context, ConnectionProfilesCodec.upsert(current, profile))
    }

    fun remove(
        context: Context,
        id: String,
    ): List<ConnectionProfile> = write(context, ConnectionProfilesCodec.remove(list(context), id))

    fun password(profile: ConnectionProfile): String = KeystoreSecretBox.open(profile.sealedPassword)

    private fun write(
        context: Context,
        profiles: List<ConnectionProfile>,
    ): List<ConnectionProfile> {
        prefs(context).edit { putString(KEY_PROFILES, ConnectionProfilesCodec.encode(profiles)) }
        return ConnectionProfilesCodec.decode(ConnectionProfilesCodec.encode(profiles))
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
