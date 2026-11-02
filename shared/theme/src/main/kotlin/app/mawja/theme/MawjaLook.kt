/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The shared [MawjaThemeStore] of this process. */
@Composable
fun rememberMawjaThemeStore(): MawjaThemeStore {
    val context = LocalContext.current
    return remember { MawjaThemeStore.get(context) }
}

/** Applies the chosen Mawja palette, following the system's light or dark setting. */
@Composable
fun MawjaLookTheme(content: @Composable () -> Unit) {
    val store = rememberMawjaThemeStore()
    val palette by store.palette.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = mawjaColorScheme(palette, isSystemInDarkTheme()), content = content)
}

/** "Look & sound": palette swatches, sound pack, volume and haptics, as a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MawjaLookSheet(onDismiss: () -> Unit) {
    val store = rememberMawjaThemeStore()
    val palette by store.palette.collectAsStateWithLifecycle()
    val pack by store.pack.collectAsStateWithLifecycle()
    val sounds by store.sounds.collectAsStateWithLifecycle()
    val volume by store.volume.collectAsStateWithLifecycle()
    val haptics by store.haptics.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.mawja_look_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.mawja_look_palette), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MawjaPalette.entries.forEach { p ->
                    val seed = if (dark) p.dark else p.light
                    val name = stringResource(paletteName(p))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(seed.primary, seed.secondary)))
                                .border(
                                    width = if (p == palette) 3.dp else 1.dp,
                                    color = if (p == palette) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                ).semantics {
                                    contentDescription = name
                                    selected = p == palette
                                }.clickable(role = Role.RadioButton) {
                                    store.setPalette(p)
                                    store.play(MawjaSound.SUCCESS)
                                },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
            SwitchRow(stringResource(R.string.mawja_look_sounds), sounds) {
                store.setSounds(it)
                if (it) store.play(MawjaSound.TOGGLE)
            }
            if (sounds) {
                Text(stringResource(R.string.mawja_look_pack), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MawjaSoundPack.entries.forEach { p ->
                        FilterChip(
                            selected = p == pack,
                            onClick = {
                                store.setPack(p)
                                store.play(MawjaSound.TAP)
                            },
                            label = { Text(stringResource(packName(p))) },
                        )
                    }
                }
                Text(stringResource(R.string.mawja_look_volume), style = MaterialTheme.typography.titleSmall)
                Slider(value = volume, onValueChange = store::setVolume, onValueChangeFinished = { store.play(MawjaSound.TAP) })
            }
            SwitchRow(stringResource(R.string.mawja_look_haptics), haptics) {
                store.setHaptics(it)
                if (it) store.play(MawjaSound.TAP)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun paletteName(p: MawjaPalette): Int =
    when (p) {
        MawjaPalette.NILE -> R.string.mawja_palette_nile
        MawjaPalette.SAHARA -> R.string.mawja_palette_sahara
        MawjaPalette.ANDALUS -> R.string.mawja_palette_andalus
        MawjaPalette.MIDNIGHT_OUD -> R.string.mawja_palette_midnight_oud
        MawjaPalette.DELTA -> R.string.mawja_palette_delta
        MawjaPalette.RED_SEA_REEF -> R.string.mawja_palette_red_sea_reef
        MawjaPalette.CAIRO_NIGHTS -> R.string.mawja_palette_cairo_nights
        MawjaPalette.SIWA_OASIS -> R.string.mawja_palette_siwa_oasis
        MawjaPalette.ASWAN_SUNSET -> R.string.mawja_palette_aswan_sunset
        MawjaPalette.LOTUS -> R.string.mawja_palette_lotus
        MawjaPalette.ALEXANDRIA -> R.string.mawja_palette_alexandria
    }

private fun packName(p: MawjaSoundPack): Int =
    when (p) {
        MawjaSoundPack.WAVE -> R.string.mawja_pack_wave
        MawjaSoundPack.DROP -> R.string.mawja_pack_drop
        MawjaSoundPack.CRYSTAL -> R.string.mawja_pack_crystal
        MawjaSoundPack.SOFT -> R.string.mawja_pack_soft
        MawjaSoundPack.RETRO -> R.string.mawja_pack_retro
    }
