/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What the remote config lets this build do right now. */
sealed interface RemoteGate {
    data object Open : RemoteGate

    data object Killed : RemoteGate

    data class Maintenance(
        val messageAr: String,
        val messageEn: String,
    ) : RemoteGate {
        /** The message for [language] ("ar"/"en"), falling back to the other one; blank if neither. */
        fun message(language: String): String {
            val ar = language.lowercase().startsWith("ar")
            return (if (ar) messageAr else messageEn).ifBlank { if (ar) messageEn else messageAr }
        }
    }

    data class ForceUpdate(
        val updateUrl: String,
    ) : RemoteGate
}

/**
 * config/{app|downloader} as read by the clients. Mirrors admin's RemoteConfig and the rules'
 * configOk. A missing or unreadable doc is [RemoteSettings.DEFAULT]: everything open and enabled.
 */
data class RemoteSettings(
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
    /**
     * Kill switch first, then maintenance (updating would not help), then minVersion. A
     * [versionCode] <= 0 (unknown build) is never forced to update.
     */
    fun gate(versionCode: Long): RemoteGate =
        when {
            killSwitch -> RemoteGate.Killed
            maintenance -> RemoteGate.Maintenance(maintenanceMessageAr, maintenanceMessage)
            versionCode > 0 && minVersion > 0 && versionCode < minVersion -> RemoteGate.ForceUpdate(safeUrl(updateUrl))
            else -> RemoteGate.Open
        }

    /** A newer build is published but this one may still run. */
    fun softUpdate(versionCode: Long): Boolean = gate(versionCode) == RemoteGate.Open && versionCode > 0 && latestVersion > versionCode

    /** A switch is on unless the config explicitly turns it off. */
    fun isEnabled(key: String): Boolean = features[key] ?: true

    /** The https checkout URL, or null to use the build's own. */
    val checkoutUrlOrNull: String? get() = safeUrl(checkoutUrl).ifBlank { null }

    /** The lowest tier id required for [feature] by a remote gate, or null when not gated (or unknown tier). */
    fun requiredTier(feature: String): String? = tierGates[feature]?.takeIf { TierRank.isKnown(it) }

    /** Whether [tierId] satisfies the remote gate on [feature]; null when the feature has no gate. */
    fun tierAllows(
        feature: String,
        tierId: String,
    ): Boolean? = requiredTier(feature)?.let { TierRank.meets(tierId, it) }

    /** Cache form (plain JSON, not Firestore-typed). */
    fun encode(): String =
        buildJsonObject {
            put("v", CACHE_VERSION)
            put("killSwitch", killSwitch)
            put("maintenance", maintenance)
            put("maintenanceMessage", maintenanceMessage)
            put("maintenanceMessageAr", maintenanceMessageAr)
            put("minVersion", minVersion)
            put("latestVersion", latestVersion)
            put("updateUrl", updateUrl)
            put("checkoutUrl", checkoutUrl)
            put("features", JsonObject(features.toSortedMap().mapValues { JsonPrimitive(it.value) }))
            put("tierGates", JsonObject(tierGates.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        }.toString()

    companion object {
        val DEFAULT = RemoteSettings()
        private const val CACHE_VERSION = 1
        private val KEY = Regex("^[A-Za-z][A-Za-z0-9_]{0,47}$")
        private const val MAX_MESSAGE = 500

        /** Only https:// URLs leave this class. */
        internal fun safeUrl(url: String): String = url.trim().takeIf { it.startsWith("https://") }.orEmpty()

        /** Parses a Firestore REST document (GET .../documents/config/{doc}); null/garbage → DEFAULT. */
        fun fromFirestore(document: JsonElement?): RemoteSettings {
            val f = FirestoreJson.fields(document)
            if (f.isEmpty()) return DEFAULT
            return RemoteSettings(
                killSwitch = FirestoreJson.bool(f["killSwitch"]) ?: false,
                maintenance = FirestoreJson.bool(f["maintenance"]) ?: false,
                maintenanceMessage = FirestoreJson.string(f["maintenanceMessage"]).orEmpty().take(MAX_MESSAGE),
                maintenanceMessageAr = FirestoreJson.string(f["maintenanceMessageAr"]).orEmpty().take(MAX_MESSAGE),
                minVersion = (FirestoreJson.long(f["minVersion"]) ?: 0).coerceAtLeast(0),
                latestVersion = (FirestoreJson.long(f["latestVersion"]) ?: 0).coerceAtLeast(0),
                updateUrl = FirestoreJson.string(f["updateUrl"]).orEmpty(),
                checkoutUrl = FirestoreJson.string(f["checkoutUrl"]).orEmpty(),
                features =
                    FirestoreJson.map(f["features"]).mapNotNull { (k, v) -> FirestoreJson.bool(v)?.let { k to it } }.filter { KEY.matches(it.first) }.toMap(),
                tierGates =
                    FirestoreJson.map(f["tierGates"]).mapNotNull { (k, v) -> FirestoreJson.string(v)?.let { k to it } }.filter { KEY.matches(it.first) }.toMap(),
            )
        }

        fun fromFirestore(json: String): RemoteSettings = fromFirestore(runCatching { Json.parseToJsonElement(json) }.getOrNull())

        /** Reads [encode] output; null for a blank, corrupt or foreign cache. */
        fun decode(cached: String?): RemoteSettings? {
            if (cached.isNullOrBlank()) return null
            val o = runCatching { Json.parseToJsonElement(cached) as? JsonObject }.getOrNull() ?: return null
            if ((o["v"] as? JsonPrimitive)?.longOrNull != CACHE_VERSION.toLong()) return null

            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

            fun bool(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false

            fun num(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
            return RemoteSettings(
                killSwitch = bool("killSwitch"),
                maintenance = bool("maintenance"),
                maintenanceMessage = str("maintenanceMessage"),
                maintenanceMessageAr = str("maintenanceMessageAr"),
                minVersion = num("minVersion"),
                latestVersion = num("latestVersion"),
                updateUrl = str("updateUrl"),
                checkoutUrl = str("checkoutUrl"),
                features = (o["features"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.booleanOrNull?.let { k to it } }.toMap(),
                tierGates =
                    (o["tierGates"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { k to it } }.toMap(),
            )
        }
    }
}

/**
 * Tier order for remote gates: free < plus < pro = lifetime < family (family has everything pro has
 * plus family sharing, matching the app's Entitlements table).
 */
object TierRank {
    private val RANK = mapOf("free" to 0, "plus" to 1, "pro" to 2, "lifetime" to 2, "family" to 3)

    fun isKnown(tierId: String): Boolean = tierId.lowercase() in RANK

    /** Unknown tiers rank as free. */
    fun rank(tierId: String?): Int = RANK[tierId?.lowercase()] ?: 0

    fun meets(
        tierId: String?,
        required: String,
    ): Boolean = rank(tierId) >= rank(required)
}
