/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.smartnotify

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Stores [SmartNotifyState] as JSON. The stored form uses plain nullable fields so an old or damaged
 * value decodes to sensible defaults instead of crashing.
 */
object SmartNotifyCodec {
    private val gson = Gson()

    private data class StoredSent(
        @SerializedName("h") val h: String? = null,
        @SerializedName("k") val k: String? = null,
        @SerializedName("t") val t: Long? = null,
        @SerializedName("o") val o: String? = null,
    )

    private data class StoredState(
        @SerializedName("v") val v: Int? = null,
        @SerializedName("hours") val hours: List<Double>? = null,
        @SerializedName("events") val events: Int? = null,
        @SerializedName("decayAt") val decayAt: Long? = null,
        @SerializedName("kinds") val kinds: Map<String, Double>? = null,
        @SerializedName("sent") val sent: List<StoredSent>? = null,
    )

    fun encode(state: SmartNotifyState): String =
        gson.toJson(
            StoredState(
                v = 1,
                hours = state.hourWeights,
                events = state.activityEvents,
                decayAt = state.lastDecayAt,
                kinds = state.kindWeights.mapKeys { it.key.id },
                sent = state.sent.map { StoredSent(it.hash, it.kind.id, it.sentAt, it.outcome.name) },
            ),
        )

    fun decode(json: String?): SmartNotifyState = runCatching { decodeOrThrow(json) }.getOrElse { SmartNotifyState() }

    private fun decodeOrThrow(json: String?): SmartNotifyState {
        if (json.isNullOrBlank()) return SmartNotifyState()
        val stored = runCatching { gson.fromJson(json, StoredState::class.java) }.getOrNull() ?: return SmartNotifyState()
        val hours = stored.hours?.takeIf { it.size == SmartNotifyState.HOURS_PER_WEEK }?.map { it.takeIf { w -> w.isFinite() && w >= 0 } ?: 0.0 }
        return SmartNotifyState(
            hourWeights = hours ?: List(SmartNotifyState.HOURS_PER_WEEK) { 0.0 },
            activityEvents = stored.events?.coerceAtLeast(0) ?: 0,
            lastDecayAt = stored.decayAt ?: 0L,
            kindWeights =
                stored.kinds
                    .orEmpty()
                    .mapNotNull { (id, w) -> SmartKind.of(id)?.let { it to w.coerceIn(0.0, 1.0) } }
                    .toMap(),
            sent =
                stored.sent.orEmpty().mapNotNull { s ->
                    val hash = s.h ?: return@mapNotNull null
                    val kind = SmartKind.of(s.k) ?: return@mapNotNull null
                    val at = s.t ?: return@mapNotNull null
                    val outcome = SmartOutcome.entries.firstOrNull { it.name == s.o } ?: SmartOutcome.PENDING
                    SentRecord(hash, kind, at, outcome)
                },
        )
    }
}
