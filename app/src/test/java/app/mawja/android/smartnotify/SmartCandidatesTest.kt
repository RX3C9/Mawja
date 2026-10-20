/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.smartnotify

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SmartCandidatesTest {
    private val zone: ZoneId = ZoneId.of("Africa/Cairo")
    private val hour = SmartNotifyConfig.HOUR
    private val day = SmartNotifyConfig.DAY

    /** 2026-10-23 is a Friday. */
    private fun at(
        d: Int,
        h: Int,
    ): Long = LocalDateTime.of(2026, 10, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private val texts =
        object : SmartTexts {
            override fun continueTitle() = "continue"

            override fun continueText(videoTitle: String) = "continue $videoTitle"

            override fun channelTitle(channelName: String) = "new $channelName"

            override fun recapTitle() = "recap"

            override fun recapText(
                videos: Int,
                songs: Int,
                topChannel: String?,
            ) = "$videos/$songs/$topChannel"

            override fun djTitle() = "dj"

            override fun djText(artist: String) = "dj $artist"
        }

    private fun item(
        id: String,
        at: Long,
        position: Long = 0,
        duration: Long = 600_000,
        channel: String = "c1",
        music: Boolean = false,
    ) = HistoryItem(id, "title $id", "name $channel", channel, position, duration, at, music)

    @Test
    fun continueWatchingPicksNewestHalfWatched() {
        val now = at(22, 20)
        val history =
            listOf(
                item("done", now - 3 * hour, position = 590_000),
                item("half-old", now - 2 * day, position = 300_000),
                item("half-new", now - 4 * hour, position = 200_000),
                item("just-now", now - 30 * 60_000, position = 300_000),
                item("song", now - 5 * hour, position = 100_000, music = true),
            )
        val c = SmartCandidates.continueWatching(history, now, texts)
        assertThat(c?.videoId).isEqualTo("half-new")
        assertThat(c?.text).isEqualTo("continue title half-new")
    }

    @Test
    fun favouriteChannelNeedsRepeatedWatchingAndFreshUnwatchedUploads() {
        val now = at(22, 20)
        val history = (1..4).map { item("w$it", now - it * day, channel = "fav") } + item("x", now - day, channel = "once")
        val feed =
            listOf(
                FeedItem("new1", "Fresh", "Fav", "fav", now - 3 * hour, isShort = false),
                FeedItem("w1", "Seen", "Fav", "fav", now - 3 * hour, isShort = false),
                FeedItem("old", "Old", "Fav", "fav", now - 5 * day, isShort = false),
                FeedItem("short", "Short", "Fav", "fav", now - hour, isShort = true),
                FeedItem("other", "Other", "Once", "once", now - hour, isShort = false),
            )
        val result = SmartCandidates.favouriteChannels(history, feed, now, texts)
        assertThat(result.map { it.contentKey }).containsExactly("new1")
        assertThat(result.single().title).isEqualTo("new Fav")
    }

    @Test
    fun weeklyRecapOnlyOnFridayWithEnoughHistory() {
        val friday = at(23, 20)
        val history = (1..6).map { item("v$it", friday - it * hour, channel = if (it < 4) "top" else "c2") } + item("s", friday - hour, music = true)
        val recap = SmartCandidates.weeklyRecap(history, friday, zone, texts)
        assertThat(recap?.text).isEqualTo("6/1/name top")
        assertThat(SmartCandidates.weeklyRecap(history, at(22, 20), zone, texts)).isNull()
        assertThat(SmartCandidates.weeklyRecap(history.take(3), friday, zone, texts)).isNull()
    }

    @Test
    fun smartDjFollowsMusicHabitsAtThisHour() {
        val now = at(22, 21)
        val evenings = (1..5).map { item("m$it", at(22 - it, 21), music = true, channel = "artist") }
        val dj = SmartCandidates.smartDj(evenings, now, zone, texts)
        assertThat(dj?.text).isEqualTo("dj name artist")
        assertThat(dj?.videoId).isEqualTo("m1")
        assertThat(SmartCandidates.smartDj(evenings, at(22, 9), zone, texts)).isNull()
    }
}
