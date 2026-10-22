/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.core

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** What a staff member typed in the user search box. */
sealed interface UserQuery {
    data class Email(
        val email: String,
    ) : UserQuery

    data class MawjaId(
        val id: String,
    ) : UserQuery

    data class Username(
        val name: String,
    ) : UserQuery

    /** Looks like a Firebase UID; also tried as a username when no such user exists. */
    data class Uid(
        val uid: String,
    ) : UserQuery

    companion object {
        private val UID = Regex("^[A-Za-z0-9]{20,40}$")
        private val MAWJA_ID = Regex("^MW-?[0-9A-Z]{4}-?[0-9A-Z]{4}$")
        private val USERNAME = Regex("^[a-z0-9._]{2,30}$")

        fun parse(input: String): UserQuery? {
            val q = input.trim()
            if (q.isEmpty()) return null
            if ('@' in q && !q.startsWith("@")) return Email(q.lowercase(Locale.ROOT))
            val upper = q.uppercase(Locale.ROOT).replace(" ", "")
            if (MAWJA_ID.matches(upper)) {
                val raw = upper.removePrefix("MW").replace("-", "")
                return MawjaId("MW-${raw.take(4)}-${raw.drop(4)}")
            }
            if (UID.matches(q) && q.any(Char::isDigit) && q.any(Char::isUpperCase)) return Uid(q)
            val name = q.removePrefix("@").lowercase(Locale.ROOT)
            if (USERNAME.matches(name)) return Username(name)
            return if (UID.matches(q)) Uid(q) else null
        }
    }
}

/** A manual membership change chosen in the user screen. */
object ManualTier {
    /**
     * The tierUntil to write: free and lifetime never end; [days] > 0 runs from now, or from the
     * current end when [extend] and the member still has time left; [days] == 0 is open-ended.
     */
    fun until(
        tier: String,
        days: Int,
        extend: Boolean,
        currentUntil: Long?,
        now: Long,
    ): Long? {
        if (tier == "free" || tier == "lifetime" || days <= 0) return null
        val base = if (extend && currentUntil != null && currentUntil > now) currentUntil else now
        return base + days * DAY_MS
    }

    /** Effective tier now: paid tiers lapse at tierUntil. */
    fun effective(
        tier: String?,
        until: Long?,
        now: Long,
    ): String = if (tier in Tiers.PAID && (until == null || until > now)) tier!! else "free"

    const val DAY_MS = 86_400_000L
}

/** CSV with RFC 4180 quoting; spreadsheet formulas are neutralised. */
object Csv {
    fun cell(value: Any?): String {
        var s = value?.toString().orEmpty()
        if (s.isNotEmpty() && s[0] in "=+-@\t\r") s = "'$s"
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun table(
        header: List<String>,
        rows: List<List<Any?>>,
    ): String =
        buildString {
            append(header.joinToString(",") { cell(it) }).append("\r\n")
            rows.forEach { append(it.joinToString(",") { v -> cell(v) }).append("\r\n") }
        }
}

/** Counts events per calendar day for the dashboard charts. */
object DailyBuckets {
    /** Start of the day containing [millis] in [zone]. */
    fun dayStart(
        millis: Long,
        zone: TimeZone,
    ): Long =
        Calendar
            .getInstance(zone)
            .apply {
                timeInMillis = millis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

    /**
     * [days] buckets ending today (last = today). Each event adds its weight (1 by default) to its
     * day; events outside the window are ignored.
     */
    fun count(
        events: List<Pair<Long, Int>>,
        days: Int,
        now: Long,
        zone: TimeZone,
    ): List<Int> {
        val out = IntArray(days)
        val starts = LongArray(days)
        val cal = Calendar.getInstance(zone).apply { timeInMillis = dayStart(now, zone) }
        for (i in days - 1 downTo 0) {
            starts[i] = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        events.forEach { (t, w) ->
            val start = dayStart(t, zone)
            val i = starts.indexOf(start)
            if (i >= 0) out[i] += w
        }
        return out.toList()
    }
}

/** Where an announcement stands right now. */
enum class AnnouncementPhase { OFF, SCHEDULED, LIVE, ENDED }

object Announcements {
    val KINDS = listOf("banner", "dialog", "news")
    val LOCALES = listOf("ar", "en")
    const val MAX_TITLE = 120
    const val MAX_BODY = 2000
    const val MAX_CTA_LABEL = 40

    fun phase(
        active: Boolean,
        startAt: Long?,
        endAt: Long?,
        now: Long,
    ): AnnouncementPhase =
        when {
            !active -> AnnouncementPhase.OFF
            endAt != null && endAt <= now -> AnnouncementPhase.ENDED
            startAt != null && startAt > now -> AnnouncementPhase.SCHEDULED
            else -> AnnouncementPhase.LIVE
        }

    /** Problems that stop a save (the rules refuse the same), or empty. */
    fun problems(
        title: String,
        body: String,
        ctaLabel: String,
        ctaUrl: String,
        startAt: Long,
        endAt: Long?,
    ): List<String> =
        buildList {
            if (title.isBlank()) add("title")
            if (title.length > MAX_TITLE) add("title_long")
            if (body.isBlank()) add("body")
            if (body.length > MAX_BODY) add("body_long")
            if (ctaLabel.length > MAX_CTA_LABEL) add("cta_long")
            if (ctaUrl.isNotBlank() && !(ctaUrl.startsWith("https://") || ctaUrl.startsWith("mawja://"))) add("cta_url")
            if (ctaUrl.isNotBlank() && ctaLabel.isBlank()) add("cta_label")
            if (endAt != null && endAt <= startAt) add("end_before_start")
        }
}

object ReportStatus {
    const val OPEN = "open"
    const val IN_PROGRESS = "in_progress"
    const val RESOLVED = "resolved"
    const val DISMISSED = "dismissed"
    val ALL = listOf(OPEN, IN_PROGRESS, RESOLVED, DISMISSED)
}

/** Audit actions grouped for the filter chips. */
object AuditCategories {
    val ALL = listOf("code", "user", "report", "announcement", "config", "staff", "feature")

    fun of(action: String): String = action.substringBefore('.').takeIf { it in ALL } ?: "other"

    /** Client-side filter over a loaded page: category, plus free text in action/target/actor. */
    fun matches(
        action: String,
        target: String,
        actor: String,
        category: String?,
        text: String,
    ): Boolean {
        if (category != null && of(action) != category) return false
        val t = text.trim()
        if (t.isEmpty()) return true
        return action.contains(t, true) || target.contains(t, true) || actor.contains(t, true)
    }
}
