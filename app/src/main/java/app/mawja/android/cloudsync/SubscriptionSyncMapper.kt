/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.cloudsync

import app.mawja.android.data.local.ChannelSubscription

/** What applying remote subscription records means for the local store. */
data class SubscriptionChanges(
    val subscribe: List<ChannelSubscription>,
    /** channel id -> unsubscribe time, for channels currently subscribed here. */
    val unsubscribe: Map<String, Long>,
    /** channel id -> unsubscribe time, for channels this device does not hold (kept as tombstones). */
    val tombstones: Map<String, Long>,
)

/** Local subscriptions <-> sync records (`c = subscriptions`, payload n/a/mu). Pure. */
object SubscriptionSyncMapper {
    const val COLLECTION = "subscriptions"

    fun toRecords(
        subscriptions: List<ChannelSubscription>,
        unsubscribedAt: Map<String, Long>,
        device: String,
    ): Map<String, SyncRecord> {
        val out = LinkedHashMap<String, SyncRecord>()
        unsubscribedAt.forEach { (id, at) ->
            if (SyncMerge.isValidKey(id) && at > 0) out[id] = SyncRecord(id, at, deleted = true, device = device)
        }
        subscriptions.take(SyncLimits.MAX_SUBSCRIPTIONS).forEach { sub ->
            if (!SyncMerge.isValidKey(sub.channelId)) return@forEach
            val live =
                SyncRecord(
                    key = sub.channelId,
                    modifiedAt = sub.subscribedAt.coerceAtLeast(1L),
                    deleted = false,
                    device = device,
                    data = payload(sub),
                )
            // Both present only after a damaged store; the newer state wins, as on the server.
            val tomb = out[sub.channelId]
            if (tomb == null || SyncMerge.wins(live, tomb)) out[sub.channelId] = live
        }
        return out
    }

    fun payload(sub: ChannelSubscription): Map<String, Any?> =
        buildMap {
            put("n", sub.channelName.take(SyncLimits.NAME_MAX))
            val avatar = sub.channelThumbnail.trim()
            if (avatar.isNotEmpty() && avatar.length <= SyncLimits.AVATAR_MAX) put("a", avatar)
            put("mu", sub.isMusic)
        }

    /**
     * Turns the winners of a merge into store operations. A live record for a channel already
     * subscribed here changes nothing: the local row keeps its bell and feed state.
     */
    fun changes(
        apply: List<SyncRecord>,
        subscribedIds: Set<String>,
    ): SubscriptionChanges {
        val subscribe = ArrayList<ChannelSubscription>()
        val unsubscribe = LinkedHashMap<String, Long>()
        val tombstones = LinkedHashMap<String, Long>()
        apply.forEach { r ->
            when {
                r.deleted && r.key in subscribedIds -> unsubscribe[r.key] = r.modifiedAt
                r.deleted -> tombstones[r.key] = r.modifiedAt
                r.key !in subscribedIds ->
                    subscribe +=
                        ChannelSubscription(
                            channelId = r.key,
                            channelName = (r.data["n"] as? String).orEmpty().ifBlank { r.key },
                            channelThumbnail = (r.data["a"] as? String).orEmpty(),
                            subscribedAt = r.modifiedAt,
                            isMusic = r.data["mu"] as? Boolean ?: false,
                        )
            }
        }
        return SubscriptionChanges(subscribe, unsubscribe, tombstones)
    }
}
