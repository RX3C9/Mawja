/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.core

/** The three Mawja components with a config/{id} doc. */
enum class Component(
    val id: String,
) {
    APP("app"),
    DOWNLOADER("downloader"),
    ADMIN("admin"),
}

/**
 * Remote config for one component (config/{app|downloader|admin}). Read by everyone, edited by
 * owner/admin. The fields mirror backend/firestore.rules `configOk`.
 */
data class RemoteConfig(
    val killSwitch: Boolean = false,
    val maintenance: Boolean = false,
    val maintenanceMessage: String = "",
    val maintenanceMessageAr: String = "",
    val minVersion: Long = 0,
    val latestVersion: Long = 0,
    val updateUrl: String = "",
    val checkoutUrl: String = "",
    val features: Map<String, Boolean> = emptyMap(),
    val tierGates: Map<String, String> = emptyMap(),
) {
    /** Firestore fields that differ from [old], ready for a merge write (maps are written whole). */
    fun changesFrom(old: RemoteConfig): Map<String, Any> =
        buildMap {
            if (killSwitch != old.killSwitch) put("killSwitch", killSwitch)
            if (maintenance != old.maintenance) put("maintenance", maintenance)
            if (maintenanceMessage != old.maintenanceMessage) put("maintenanceMessage", maintenanceMessage)
            if (maintenanceMessageAr != old.maintenanceMessageAr) put("maintenanceMessageAr", maintenanceMessageAr)
            if (minVersion != old.minVersion) put("minVersion", minVersion)
            if (latestVersion != old.latestVersion) put("latestVersion", latestVersion)
            if (updateUrl != old.updateUrl) put("updateUrl", updateUrl)
            if (checkoutUrl != old.checkoutUrl) put("checkoutUrl", checkoutUrl)
            if (features != old.features) put("features", features.toSortedMap())
            if (tierGates != old.tierGates) put("tierGates", tierGates.toSortedMap())
        }

    /** Problems that stop a save, or empty. */
    fun problems(): List<String> =
        buildList {
            if (maintenanceMessage.length > MAX_MESSAGE || maintenanceMessageAr.length > MAX_MESSAGE) add("message_long")
            if (minVersion < 0 || latestVersion < 0) add("version_negative")
            if (latestVersion in 1 until minVersion) add("latest_below_min")
            if (updateUrl.isNotBlank() && !updateUrl.startsWith("https://")) add("update_url")
            if (checkoutUrl.isNotBlank() && !checkoutUrl.startsWith("https://")) add("checkout_url")
            if (features.size + tierGates.size > 200 || features.size > 100 || tierGates.size > 100) add("too_many")
            if (features.keys.any { !isFlagKey(it) } || tierGates.keys.any { !isFlagKey(it) }) add("bad_key")
            if (tierGates.values.any { it !in Tiers.ALL }) add("bad_tier")
        }

    /** Whether a build with [versionCode] may run under this config. */
    fun blocks(versionCode: Long): Boolean = killSwitch || (minVersion > 0 && versionCode < minVersion)

    companion object {
        const val MAX_MESSAGE = 500
        private val KEY = Regex("^[A-Za-z][A-Za-z0-9_]{0,47}$")

        fun isFlagKey(key: String): Boolean = KEY.matches(key)

        /** Reads a Firestore doc's data leniently (numbers may arrive as Long or Double). */
        fun from(data: Map<String, Any?>?): RemoteConfig {
            if (data == null) return RemoteConfig()
            fun str(k: String) = data[k] as? String ?: ""
            fun bool(k: String) = data[k] as? Boolean ?: false
            fun num(k: String) = (data[k] as? Number)?.toLong() ?: 0L
            return RemoteConfig(
                killSwitch = bool("killSwitch"),
                maintenance = bool("maintenance"),
                maintenanceMessage = str("maintenanceMessage"),
                maintenanceMessageAr = str("maintenanceMessageAr"),
                minVersion = num("minVersion"),
                latestVersion = num("latestVersion"),
                updateUrl = str("updateUrl"),
                checkoutUrl = str("checkoutUrl"),
                features = (data["features"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? Boolean)?.let { key to it } } }.toMap(),
                tierGates = (data["tierGates"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? String)?.let { key to it } } }.toMap(),
            )
        }

        /** Known switches per component, shown even before they are first saved. */
        fun catalogue(component: Component): List<String> =
            when (component) {
                Component.APP ->
                    listOf(
                        "accounts", "codeRedeem", "announcements", "smartNotifications", "smartNetwork", "storageGuard",
                        "premiumThemes", "sync", "lastfm", "downloads",
                    )
                Component.DOWNLOADER -> listOf("downloads", "playlistDownloads", "audioOnly", "videoDownloads", "retag", "autoUpdateExtractor")
                Component.ADMIN -> listOf("codesGenerate", "googleSignIn", "csvExport")
            }

        /** Features that can be gated by tier (app MawjaFeature names). */
        val GATEABLE = listOf("PREMIUM_THEMES", "PREMIUM_ICONS", "UI_SOUNDS", "VISUALIZER", "FAMILY_SHARING")
    }
}
