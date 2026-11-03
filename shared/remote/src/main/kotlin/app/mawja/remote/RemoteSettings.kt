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
        /** Why, in the user's language when staff wrote it (may be blank). */
        val reasonAr: String = "",
        val reasonEn: String = "",
        /** SHA-256 (hex) the downloaded APK must match before it is offered for install. */
        val sha256: String = "",
    ) : RemoteGate {
        fun reason(language: String): String {
            val ar = language.lowercase().startsWith("ar")
            return (if (ar) reasonAr else reasonEn).ifBlank { if (ar) reasonEn else reasonAr }
        }
    }
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
    /** Mawja 2.0 updates (P7): why to update, in Arabic and English. */
    val updateReason: String = "",
    val updateReasonAr: String = "",
    /** SHA-256 (64 hex) of the APK at [updateUrl]; the in-app installer refuses any other file. */
    val updateSha256: String = "",
    /** After this moment (epoch ms; 0 = never) the latest version stops being optional. */
    val requiredAfter: Long = 0,
    /** Staged rollout of [latestVersion]: share of devices (0..100) that are offered it. */
    val rolloutPercent: Int = 100,
    /** Builds withdrawn for a serious problem: they must update to [latestVersion] right away. */
    val pulledVersions: List<Long> = emptyList(),
) {
    /**
     * Kill switch first, then maintenance (updating would not help), then the update policy. A
     * [versionCode] <= 0 (unknown build) is never forced to update. [bucket] (0..99, stable per
     * device) decides the staged rollout; [now] decides a grace period that has run out.
     */
    fun gate(
        versionCode: Long,
        bucket: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): RemoteGate =
        when {
            killSwitch -> RemoteGate.Killed
            maintenance -> RemoteGate.Maintenance(maintenanceMessageAr, maintenanceMessage)
            update(versionCode, bucket, now) == UpdateLevel.FORCED ->
                RemoteGate.ForceUpdate(safeUrl(updateUrl), updateReasonAr, updateReason, sha256OrEmpty)
            else -> RemoteGate.Open
        }

    /** How strongly this build is asked to update: the three levels of the plan plus none. */
    fun update(
        versionCode: Long,
        bucket: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): UpdateLevel {
        if (versionCode <= 0) return UpdateLevel.NONE
        if (minVersion > 0 && versionCode < minVersion) return UpdateLevel.FORCED
        val newer = latestVersion > versionCode
        if (!newer) return UpdateLevel.NONE
        if (versionCode in pulledVersions) return UpdateLevel.FORCED
        if (!inRollout(bucket)) return UpdateLevel.NONE
        return when {
            requiredAfter <= 0 -> UpdateLevel.SUGGESTED
            now >= requiredAfter -> UpdateLevel.FORCED
            else -> UpdateLevel.REQUIRED_SOON
        }
    }

    /** Whether a device in [bucket] (0..99) is offered [latestVersion] yet. */
    fun inRollout(bucket: Int): Boolean = bucket.coerceIn(0, 99) < rolloutPercent.coerceIn(0, 100)

    /** A newer build is offered but this one may still run (suggested, or required after a deadline). */
    fun softUpdate(
        versionCode: Long,
        bucket: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): Boolean =
        gate(versionCode, bucket, now) == RemoteGate.Open &&
            update(versionCode, bucket, now).let { it == UpdateLevel.SUGGESTED || it == UpdateLevel.REQUIRED_SOON }

    /** The reason for [language], falling back to the other language; blank if none. */
    fun updateReason(language: String): String {
        val ar = language.lowercase().startsWith("ar")
        return (if (ar) updateReasonAr else updateReason).ifBlank { if (ar) updateReason else updateReasonAr }
    }

    /** [updateSha256] lower-cased when it is a real SHA-256, else blank (no in-app install). */
    val sha256OrEmpty: String get() = updateSha256.trim().lowercase().takeIf { SHA.matches(it) }.orEmpty()

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
            put("updateReason", updateReason)
            put("updateReasonAr", updateReasonAr)
            put("updateSha256", updateSha256)
            put("requiredAfter", requiredAfter)
            put("rolloutPercent", rolloutPercent)
            put("pulledVersions", kotlinx.serialization.json.JsonArray(pulledVersions.map { JsonPrimitive(it) }))
        }.toString()

    companion object {
        val DEFAULT = RemoteSettings()
        private const val CACHE_VERSION = 1
        private val KEY = Regex("^[A-Za-z][A-Za-z0-9_]{0,47}$")
        private const val MAX_MESSAGE = 500
        private val SHA = Regex("^[0-9a-f]{64}$")

        /** Stable 0..99 rollout bucket for a device id (same id → same bucket on every run). */
        fun bucketOf(deviceId: String): Int = Math.floorMod(deviceId.hashCode(), 100)

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
                updateReason = FirestoreJson.string(f["updateReason"]).orEmpty().take(MAX_MESSAGE),
                updateReasonAr = FirestoreJson.string(f["updateReasonAr"]).orEmpty().take(MAX_MESSAGE),
                updateSha256 = FirestoreJson.string(f["updateSha256"]).orEmpty(),
                requiredAfter = (FirestoreJson.millis(f["requiredAfter"]) ?: 0).coerceAtLeast(0),
                rolloutPercent = (FirestoreJson.long(f["rolloutPercent"]) ?: 100).coerceIn(0, 100).toInt(),
                pulledVersions = FirestoreJson.longList(f["pulledVersions"]).filter { it > 0 }.take(50),
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
                updateReason = str("updateReason"),
                updateReasonAr = str("updateReasonAr"),
                updateSha256 = str("updateSha256"),
                requiredAfter = num("requiredAfter"),
                rolloutPercent = ((o["rolloutPercent"] as? JsonPrimitive)?.longOrNull ?: 100L).coerceIn(0, 100).toInt(),
                pulledVersions = (o["pulledVersions"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull },
            )
        }
    }
}

/** The update levels of Mawja 2.0: nothing, a suggestion, required after a deadline, or now. */
enum class UpdateLevel { NONE, SUGGESTED, REQUIRED_SOON, FORCED }

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
