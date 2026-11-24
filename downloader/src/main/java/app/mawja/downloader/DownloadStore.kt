/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader

import android.content.Context
import androidx.core.content.edit
import app.mawja.downloader.api.MawjaDownloaderContract.State
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** The queue and library on disk (downloads.json), so pause/resume survives a closed app or a reboot. */
object DownloadStore {
    private const val FILE = "downloads.json"
    private const val MAX_FINISHED = 500

    @Synchronized
    fun save(
        context: Context,
        items: List<DownloadItem>,
    ) {
        val active = items.filter { it.isActive }
        val finished = items.filterNot { it.isActive }.take(MAX_FINISHED)
        val text = encode(active + finished)
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(context.filesDir, FILE))
    }

    @Synchronized
    fun load(context: Context): List<DownloadItem> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return runCatching { decode(file.readText()) }.getOrDefault(emptyList())
    }

    fun encode(items: List<DownloadItem>): String =
        buildJsonArray {
            items.forEach { i ->
                add(
                    buildJsonObject {
                        put("id", i.id)
                        put("title", i.title)
                        put("url", i.url)
                        put("fileName", i.fileName)
                        put("mimeType", i.mimeType)
                        put("subfolder", i.subfolder)
                        put("headers", JsonObject(i.headers.mapValues { JsonPrimitive(it.value) }))
                        i.sourceId?.let { put("sourceId", it) }
                        put("state", i.state.name)
                        put("downloaded", i.downloaded)
                        put("total", i.total)
                        i.contentUri?.let { put("contentUri", it) }
                        i.error?.let { put("error", it) }
                        put("paused", i.paused)
                        put("priority", i.priority)
                        put("wifiOnly", i.wifiOnly)
                        put("notBefore", i.notBefore)
                        put("createdAt", i.createdAt)
                        put("finishedAt", i.finishedAt)
                        put("segments", JsonArray(i.segments.map { s -> JsonArray(listOf(JsonPrimitive(s.start), JsonPrimitive(s.end), JsonPrimitive(s.done))) }))
                        i.sha256?.let { put("sha256", it) }
                        i.verified?.let { put("verified", it) }
                        if (i.mirrors.isNotEmpty()) put("mirrors", JsonArray(i.mirrors.map { JsonPrimitive(it) }))
                        if (i.sources.isNotEmpty()) put("sources", JsonArray(i.sources.map { JsonPrimitive(it) }))
                        i.checksum?.let { put("checksum", it) }
                        i.checksumOk?.let { put("checksumOk", it) }
                        i.etag?.let { put("etag", it) }
                        i.lastModified?.let { put("lastModified", it) }
                        if (i.connections > 0) put("connections", i.connections)
                        if (i.autoName) put("autoName", true)
                        if (i.ignoreRules) put("ignoreRules", true)
                        i.safety?.let { put("safety", it) }
                    },
                )
            }
        }.toString()

    fun decode(text: String): List<DownloadItem> =
        Json.parseToJsonElement(text).jsonArray.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                fun l(k: String) = o[k]?.jsonPrimitive?.longOrNull
                fun b(k: String) = o[k]?.jsonPrimitive?.booleanOrNull
                fun strings(k: String) = (o[k] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                DownloadItem(
                    id = s("id")!!,
                    title = s("title").orEmpty(),
                    url = s("url")!!,
                    fileName = s("fileName").orEmpty(),
                    mimeType = s("mimeType") ?: "application/octet-stream",
                    subfolder = s("subfolder").orEmpty(),
                    headers = o["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                    sourceId = s("sourceId"),
                    state = runCatching { State.valueOf(s("state")!!) }.getOrDefault(State.FAILED),
                    downloaded = l("downloaded") ?: 0,
                    total = l("total") ?: -1,
                    contentUri = s("contentUri"),
                    error = s("error"),
                    paused = b("paused") ?: false,
                    priority = (l("priority") ?: 0).toInt(),
                    wifiOnly = b("wifiOnly") ?: false,
                    notBefore = l("notBefore") ?: 0,
                    createdAt = l("createdAt") ?: 0,
                    finishedAt = l("finishedAt") ?: 0,
                    segments =
                        o["segments"]?.jsonArray?.map { seg ->
                            val a = seg.jsonArray
                            Segment(a[0].jsonPrimitive.longOrNull!!, a[1].jsonPrimitive.longOrNull!!, a[2].jsonPrimitive.longOrNull!!)
                        }.orEmpty(),
                    sha256 = s("sha256"),
                    verified = b("verified"),
                    mirrors = strings("mirrors"),
                    sources = strings("sources"),
                    checksum = s("checksum"),
                    checksumOk = b("checksumOk"),
                    etag = s("etag"),
                    lastModified = s("lastModified"),
                    connections = (l("connections") ?: 0).toInt(),
                    autoName = b("autoName") ?: false,
                    ignoreRules = b("ignoreRules") ?: false,
                    safety = s("safety"),
                )
            }.getOrNull()
        }
}

/** Downloader settings: speed limit, Wi-Fi only, clipboard watch. */
object DownloaderPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("downloader", Context.MODE_PRIVATE)

    fun speedLimit(context: Context): Long = prefs(context).getLong("speed_limit", 0)

    fun setSpeedLimit(
        context: Context,
        bps: Long,
    ) = prefs(context).edit { putLong("speed_limit", bps.coerceAtLeast(0)) }

    fun wifiOnly(context: Context): Boolean = prefs(context).getBoolean("wifi_only", false)

    fun setWifiOnly(
        context: Context,
        on: Boolean,
    ) = prefs(context).edit { putBoolean("wifi_only", on) }

    fun watchClipboard(context: Context): Boolean = prefs(context).getBoolean("watch_clipboard", true)

    fun setWatchClipboard(
        context: Context,
        on: Boolean,
    ) = prefs(context).edit { putBoolean("watch_clipboard", on) }

    /** The last clipboard link offered, so the same one is not offered twice. */
    fun lastClip(context: Context): String? = prefs(context).getString("last_clip", null)

    fun setLastClip(
        context: Context,
        url: String,
    ) = prefs(context).edit { putString("last_clip", url) }

    /** Connections per download (0 = default); capped by the tier in ConnectionPolicy. */
    fun connections(context: Context): Int = prefs(context).getInt("connections", ConnectionPolicy.DEFAULT_CONNECTIONS)

    fun setConnections(
        context: Context,
        value: Int,
    ) = prefs(context).edit { putInt("connections", value) }

    /** Downloads at the same time. */
    fun parallel(context: Context): Int = prefs(context).getInt("parallel", ConnectionPolicy.FREE_PARALLEL)

    fun setParallel(
        context: Context,
        value: Int,
    ) = prefs(context).edit { putInt("parallel", value) }

    /**
     * Royal tier (32 connections, more at once). The Downloader has no account link yet, so this
     * stays false until the Mawja account/entitlement hand-over lands (plan §6 "same account").
     */
    fun royal(context: Context): Boolean = prefs(context).getBoolean("royal", false)

    fun autoSort(context: Context): Boolean = prefs(context).getBoolean("auto_sort", true)

    fun setAutoSort(
        context: Context,
        on: Boolean,
    ) = prefs(context).edit { putBoolean("auto_sort", on) }

    /** The smart scheduling rules, Wi-Fi only included. */
    fun rules(context: Context): ScheduleRules.Rules {
        val p = prefs(context)
        return ScheduleRules.Rules(
            wifiOnly = p.getBoolean("wifi_only", false),
            chargingOnly = p.getBoolean("charging_only", false),
            minBatteryPercent = if (p.getBoolean("battery_floor", false)) ScheduleRules.BATTERY_FLOOR else 0,
            nightOnly = p.getBoolean("night_only", false),
            windowStart = p.getInt("window_start", 60),
            windowEnd = p.getInt("window_end", 7 * 60),
            dailyCapBytes = p.getLong("daily_cap", 0),
        )
    }

    fun setChargingOnly(
        context: Context,
        on: Boolean,
    ) = prefs(context).edit { putBoolean("charging_only", on) }

    fun setBatteryFloor(
        context: Context,
        on: Boolean,
    ) = prefs(context).edit { putBoolean("battery_floor", on) }

    fun setNightOnly(
        context: Context,
        on: Boolean,
    ) = prefs(context).edit { putBoolean("night_only", on) }

    fun setDailyCap(
        context: Context,
        bytes: Long,
    ) = prefs(context).edit { putLong("daily_cap", bytes.coerceAtLeast(0)) }

    fun ledger(context: Context): ScheduleRules.Ledger =
        ScheduleRules.Ledger(prefs(context).getInt("ledger_day", 0), prefs(context).getLong("ledger_bytes", 0))

    fun setLedger(
        context: Context,
        ledger: ScheduleRules.Ledger,
    ) = prefs(context).edit {
        putInt("ledger_day", ledger.day)
        putLong("ledger_bytes", ledger.bytes)
    }

    fun hourlySpeed(context: Context): String? = prefs(context).getString("hourly_speed", null)

    fun setHourlySpeed(
        context: Context,
        encoded: String,
    ) = prefs(context).edit { putString("hourly_speed", encoded) }
}
