/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.mawja.android.R

/** The time guardian's gentle card: autoplay already stopped; the viewer decides what is next. */
@Composable
fun BreakPromptHost() {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        MawjaLab.breakPrompts.collect {
            show = true
        }
    }
    if (!show) return
    AlertDialog(
        onDismissRequest = { show = false },
        title = { Text(stringResource(R.string.lab_guardian_title)) },
        text = { Text(stringResource(R.string.lab_guardian_message)) },
        confirmButton = {
            TextButton(onClick = {
                show = false
            }) { Text(stringResource(R.string.lab_guardian_break)) }
        },
        dismissButton = {
            TextButton(onClick = {
                show = false
            }) { Text(stringResource(R.string.lab_guardian_continue)) }
        },
    )
}
