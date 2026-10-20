/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.smartnotify

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.IsoFields

/** A watch-history row, reduced to what the engine reads. */
data class HistoryItem(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelId: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val watchedAt: Long,
    val isMusic: Boolean,
)

/** A stored subscription-feed upload. */
data class FeedItem(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelId: String,
    val publishedAt: Long,
    val isShort: Boolean,
)

/** Localized notification copy; the Android side fills it from string resources. */
interface SmartTexts {
    fun continueTitle(): String

    fun continueText(videoTitle: String): String

    fun channelTitle(channelName: String): String

    fun recapTitle(): String

    fun recapText(
        videos: Int,
        songs: Int,
        topChannel: String?,
    ): String

    fun djTitle(): String

    fun djText(artist: String): String
}

/** Builds the notifications worth considering from what is already on the device. */
object SmartCandidates {
    private const val DAY = SmartNotifyConfig.DAY

    /** Day the weekly recap goes out: Friday, the start of the weekend in Egypt. */
    val RECAP_DAY: DayOfWeek = DayOfWeek.FRIDAY

    fun build(
        history: List<HistoryItem>,
        feed: List<FeedItem>,
        now: Long,
        zone: ZoneId,
        texts: SmartTexts,
    ): List<SmartCandidate> =
        listOfNotNull(continueWatching(history, now, texts), weeklyRecap(history, now, zone, texts), smartDj(history, now, zone, texts)) +
            favouriteChannels(history, feed, now, texts)

    /** The newest video left between 10% and 90% watched, paused at least two hours and at most a week ago. */
    fun continueWatching(
        history: List<HistoryItem>,
        now: Long,
        texts: SmartTexts,
    ): SmartCandidate? {
        val item =
            history
                .filter { !it.isMusic && it.durationMillis > 0 }
                .filter { val p = it.positionMillis.toDouble() / it.durationMillis; p in 0.10..0.90 }
                .filter { now - it.watchedAt in (2 * SmartNotifyConfig.HOUR)..(7 * DAY) }
                .maxByOrNull { it.watchedAt } ?: return null
        return SmartCandidate(
            kind = SmartKind.CONTINUE_WATCHING,
            contentKey = item.videoId,
            title = texts.continueTitle(),
            text = texts.continueText(item.title),
            videoId = item.videoId,
            priority = 0.8,
        )
    }

    /** Channels by how often they were watched in the last 30 days, most first. */
    fun favouriteChannelIds(
        history: List<HistoryItem>,
        now: Long,
        top: Int = 5,
    ): List<String> =
        history
            .filter { !it.isMusic && it.channelId.isNotBlank() && now - it.watchedAt <= 30 * DAY }
            .groupingBy { it.channelId }
            .eachCount()
            .filterValues { it >= 3 }
            .entries
            .sortedByDescending { it.value }
            .take(top)
            .map { it.key }

    /** Unwatched uploads from the last two days by a favourite channel. */
    fun favouriteChannels(
        history: List<HistoryItem>,
        feed: List<FeedItem>,
        now: Long,
        texts: SmartTexts,
    ): List<SmartCandidate> {
        val favourites = favouriteChannelIds(history, now)
        if (favourites.isEmpty()) return emptyList()
        val watched = history.mapTo(HashSet()) { it.videoId }
        return feed
            .filter { !it.isShort && it.channelId in favourites && it.videoId !in watched && now - it.publishedAt in 0..(2 * DAY) }
            .sortedBy { favourites.indexOf(it.channelId) }
            .take(3)
            .map { upload ->
                val rank = favourites.indexOf(upload.channelId)
                SmartCandidate(
                    kind = SmartKind.FAVOURITE_CHANNEL,
                    contentKey = upload.videoId,
                    title = texts.channelTitle(upload.channelName),
                    text = upload.title,
                    videoId = upload.videoId,
                    priority = 0.9 - rank * 0.08,
                )
            }
    }

    /** On recap day, a summary of the last seven days when there was enough to sum up. */
    fun weeklyRecap(
        history: List<HistoryItem>,
        now: Long,
        zone: ZoneId,
        texts: SmartTexts,
    ): SmartCandidate? {
        val today = SmartNotifyEngine.localDay(now, zone)
        if (today.dayOfWeek != RECAP_DAY) return null
        val week = history.filter { now - it.watchedAt in 0..(7 * DAY) }
        if (week.size < 5) return null
        val videos = week.count { !it.isMusic }
        val songs = week.count { it.isMusic }
        val topChannel =
            week
                .filter { it.channelName.isNotBlank() }
                .groupingBy { it.channelName }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
        val weekKey = "${today.get(IsoFields.WEEK_BASED_YEAR)}-W${today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
        return SmartCandidate(
            kind = SmartKind.WEEKLY_RECAP,
            contentKey = weekKey,
            title = texts.recapTitle(),
            text = texts.recapText(videos, songs, topChannel),
            priority = 0.6,
        )
    }

    /**
     * When this hour of the day is one this person usually plays music in, suggest more of the
     * artist they play most at this hour. One per artist per day.
     */
    fun smartDj(
        history: List<HistoryItem>,
        now: Long,
        zone: ZoneId,
        texts: SmartTexts,
    ): SmartCandidate? {
        val hour = SmartNotifyEngine.localHour(now, zone)
        val music =
            history.filter { it.isMusic && it.channelName.isNotBlank() && now - it.watchedAt <= 30 * DAY }
        val atThisHour =
            music.filter {
                val h = SmartNotifyEngine.localHour(it.watchedAt, zone)
                val distance = minOf((h - hour + 24) % 24, (hour - h + 24) % 24)
                distance <= 1
            }
        if (atThisHour.size < 4) return null
        val (artist, plays) = atThisHour.groupingBy { it.channelName }.eachCount().maxByOrNull { it.value }?.toPair() ?: return null
        if (plays < 2) return null
        val track = atThisHour.filter { it.channelName == artist }.maxByOrNull { it.watchedAt }
        return SmartCandidate(
            kind = SmartKind.SMART_DJ,
            contentKey = "$artist@${SmartNotifyEngine.localDay(now, zone)}",
            title = texts.djTitle(),
            text = texts.djText(artist),
            videoId = track?.videoId,
            priority = 0.5,
        )
    }
}
