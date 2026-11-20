/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mawja.android.R
import app.mawja.android.data.model.Video

/** Home's "I have N minutes" action: picks the feed videos that fit the time and plays them as a mix. */
@Composable
fun TimeBudgetButton(
    feed: () -> List<Video>,
    onPlay: (videos: List<Video>, title: String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.Timer, contentDescription = stringResource(R.string.lab_budget_title))
    }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.lab_budget_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.lab_budget_message))
                TimeBudgetFeed.MINUTE_CHOICES.forEach { minutes ->
                    OutlinedButton(
                        onClick = {
                            open = false
                            val picked = TimeBudgetFeed.plan(feed(), minutes)
                            if (picked.isEmpty()) {
                                Toast.makeText(context, R.string.lab_budget_empty, Toast.LENGTH_SHORT).show()
                            } else {
                                onPlay(picked, context.getString(R.string.lab_budget_mix_title, minutes))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.lab_budget_minutes, minutes)) }
                }
                TextButton(
                    onClick = {
                        open = false
                        val picked = TimeBudgetFeed.surprise(feed())
                        if (picked.isEmpty()) {
                            Toast.makeText(context, R.string.lab_budget_empty, Toast.LENGTH_SHORT).show()
                        } else {
                            onPlay(picked, context.getString(R.string.lab_surprise_title))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.lab_surprise_title)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}
