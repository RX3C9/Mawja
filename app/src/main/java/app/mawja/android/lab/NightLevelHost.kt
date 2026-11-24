/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.media3.common.util.UnstableApi
import app.mawja.android.player.EnhancedPlayerManager
import kotlinx.coroutines.delay

/**
 * Applies [NightLeveling] to the video player's audio session with the platform's dynamics
 * processor (Android 9 and newer; older phones simply skip it). It holds one effect and retunes it
 * in place; a new audio session or turning leveling off releases it.
 */
class NightLeveler {
    private var effect: AudioEffect? = null
    private var sessionId = 0
    private var applied: NightLeveling.Settings? = null

    fun update(
        session: Int,
        settings: NightLeveling.Settings?,
    ) {
        if (settings == null || session <= 0) {
            release()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (effect != null && session == sessionId && settings == applied) return
        try {
            if (effect == null || session != sessionId) {
                release()
                effect = NightLevelerApi28.create(session)
                sessionId = session
            }
            val current = effect
            if (current != null) {
                NightLevelerApi28.tune(current, settings)
                applied = settings
            }
        } catch (e: Exception) {
            Log.w(TAG, "Night leveling unavailable on this session", e)
            release()
        }
    }

    fun release() {
        try {
            effect?.release()
        } catch (_: Exception) {
        }
        effect = null
        sessionId = 0
        applied = null
    }

    private companion object {
        const val TAG = "NightLeveler"
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private object NightLevelerApi28 {
    fun create(session: Int): AudioEffect {
        val config =
            DynamicsProcessing.Config
                .Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    false,
                    0,
                    false,
                    0,
                    false,
                    0,
                    true,
                ).build()
        val processor = DynamicsProcessing(0, session, config)
        processor.setEnabled(true)
        return processor
    }

    fun tune(
        effect: AudioEffect,
        settings: NightLeveling.Settings,
    ) {
        val processor = effect as? DynamicsProcessing ?: return
        processor.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                true,
                true,
                0,
                settings.attackMs,
                settings.releaseMs,
                settings.ratio,
                settings.thresholdDb,
                settings.postGainDb,
            ),
        )
    }
}

/** Keeps the night leveling in step with the clock while the Lab switch is on. Draws nothing. */
@OptIn(UnstableApi::class)
@Composable
fun NightLevelHost() {
    val on by MawjaLab.nightLeveling.collectAsState()
    val startHour by MawjaLab.bedtimeStartHour.collectAsState()
    val leveler = remember { NightLeveler() }
    DisposableEffect(leveler) {
        onDispose { leveler.release() }
    }
    LaunchedEffect(on, startHour) {
        if (!on) {
            leveler.release()
            return@LaunchedEffect
        }
        while (true) {
            val now = java.util.Calendar.getInstance()
            val minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
            val settings = NightLeveling.settingsFor(NightLeveling.strength(minute, startHour))
            val session = EnhancedPlayerManager.getInstance().getPlayer()?.audioSessionId ?: 0
            leveler.update(session, settings)
            delay(CHECK_EVERY_MS)
        }
    }
}

private const val CHECK_EVERY_MS = 15_000L
