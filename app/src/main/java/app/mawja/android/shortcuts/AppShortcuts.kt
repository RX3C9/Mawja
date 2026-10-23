/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.shortcuts

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.mawja.android.MainActivity
import app.mawja.android.R
import java.util.Locale

/**
 * Dynamic launcher shortcuts (#79): long-pressing the icon offers the last playlist, Search,
 * Downloads and Smart DJ. They are pushed through ShortcutManagerCompat only when something on them
 * changed (a new last playlist, another language), from the foreground, so launcher rate limits are
 * never hit. Each opens a mawja:// link that MainActivity reads with [ShortcutPlan.parse].
 */
object AppShortcuts {
    const val EXTRA_SHORTCUT_ID = "app.mawja.android.shortcut_id"

    private const val TAG = "AppShortcuts"
    private const val PREFS = "mawja_shortcuts"
    private const val KEY_SIGNATURE = "signature"
    private const val KEY_PLAYLIST_ID = "playlist_id"
    private const val KEY_PLAYLIST_TITLE = "playlist_title"
    private const val KEY_PLAYLIST_MUSIC = "playlist_music"

    /** The playlist page just opened becomes the first shortcut. */
    fun recordPlaylist(
        context: Context,
        id: String,
        title: String,
        isMusic: Boolean,
    ) {
        if (id.isBlank() || title.isBlank()) return
        val p = prefs(context)
        if (p.getString(KEY_PLAYLIST_ID, null) == id && p.getString(KEY_PLAYLIST_TITLE, null) == title &&
            p.getBoolean(KEY_PLAYLIST_MUSIC, false) == isMusic
        ) {
            return
        }
        p.edit {
            putString(KEY_PLAYLIST_ID, id)
            putString(KEY_PLAYLIST_TITLE, title)
            putBoolean(KEY_PLAYLIST_MUSIC, isMusic)
        }
        refresh(context)
    }

    /** Pushes the shortcuts when they differ from what the launcher already has. Cheap otherwise. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        runCatching {
            val p = prefs(app)
            val last =
                p.getString(KEY_PLAYLIST_ID, null)?.let { id ->
                    ShortcutPlan.LastPlaylist(id, p.getString(KEY_PLAYLIST_TITLE, null).orEmpty(), p.getBoolean(KEY_PLAYLIST_MUSIC, false))
                }
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(app).takeIf { it > 0 } ?: 4
            val plan = ShortcutPlan.plan(last, hasMusic = true, maxCount = minOf(max, 4))
            val language = context.resources.configuration.locales[0]?.toLanguageTag() ?: Locale.getDefault().toLanguageTag()
            val signature = ShortcutPlan.signature(plan, language)
            if (p.getString(KEY_SIGNATURE, null) == signature) return
            val infos = plan.map { toInfo(context, it) }
            if (ShortcutManagerCompat.setDynamicShortcuts(app, infos)) {
                p.edit { putString(KEY_SIGNATURE, signature) }
            }
        }.onFailure { Log.w(TAG, "Could not update launcher shortcuts", it) }
    }

    /** Lets the launcher rank the shortcut that was just used. */
    fun reportUsed(
        context: Context,
        id: String,
    ) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context.applicationContext, id) }
    }

    private fun toInfo(
        context: Context,
        shortcut: ShortcutPlan.Shortcut,
    ): ShortcutInfoCompat {
        val (short, long, icon) =
            when (shortcut.kind) {
                ShortcutPlan.Kind.PLAYLIST -> {
                    Triple(shortcut.label.orEmpty(), shortcut.label.orEmpty(), R.drawable.ic_shortcut_playlist)
                }

                ShortcutPlan.Kind.SEARCH -> {
                    Triple(context.getString(R.string.shortcut_search_short), context.getString(R.string.shortcut_search_long), R.drawable.ic_shortcut_search)
                }

                ShortcutPlan.Kind.DOWNLOADS -> {
                    Triple(
                        context.getString(R.string.shortcut_downloads_short),
                        context.getString(R.string.shortcut_downloads_long),
                        R.drawable.ic_shortcut_downloads,
                    )
                }

                ShortcutPlan.Kind.DJ -> {
                    Triple(context.getString(R.string.shortcut_dj_short), context.getString(R.string.shortcut_dj_long), R.drawable.ic_shortcut_dj)
                }
            }
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(shortcut.link))
                .setClass(context.applicationContext, MainActivity::class.java)
                .putExtra(EXTRA_SHORTCUT_ID, shortcut.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ShortcutInfoCompat
            .Builder(context.applicationContext, shortcut.id)
            .setShortLabel(short.take(25).ifBlank { context.getString(R.string.app_name) })
            .setLongLabel(long.ifBlank { short })
            .setIcon(IconCompat.createWithResource(context.applicationContext, icon))
            .setIntent(intent)
            .setRank(shortcut.rank)
            .build()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
