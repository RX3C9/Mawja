/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ai

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mawja.android.R
import app.mawja.android.accessibility.MawjaAccessibility
import app.mawja.android.utils.formatDurationMillis

@Composable
fun KeyMomentsToggle(
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(R.string.ai_key_moments)) },
        leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer),
    )
}

/** The key moments as cards under a slowly sweeping aurora border; each slides in, tap to jump. */
@Composable
fun KeyMomentsPanel(
    moments: List<VideoInsights.Moment>,
    cues: List<app.mawja.android.data.transcript.TranscriptCue>,
    onSeekMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val still = MawjaAccessibility.reduceMotionNow()
    var question by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val answers = remember(question, cues) { if (question.isBlank()) null else VideoInsights.ask(question, cues) }
    val shown = answers ?: moments
    val sweep by rememberInfiniteTransition(label = "aurora").animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Reverse),
        label = "auroraX",
    )
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
    val brush = Brush.linearGradient(colors, start = androidx.compose.ui.geometry.Offset(if (still) 0f else sweep, 0f), end = androidx.compose.ui.geometry.Offset((if (still) 0f else sweep) + 600f, 400f))
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item {
            androidx.compose.material3.OutlinedTextField(
                value = question,
                onValueChange = { question = it.take(200) },
                modifier = Modifier.fillMaxWidth().border(BorderStroke(1.dp, brush), RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.ai_ask_video_hint)) },
            )
        }
        item {
            Text(
                stringResource(
                    when {
                        answers == null -> R.string.ai_key_moments_note
                        answers.isEmpty() -> R.string.ai_ask_video_none
                        else -> R.string.ai_ask_video_note
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            val title = stringResource(R.string.ai_study_notes_title)
            val empty = stringResource(R.string.ai_study_notes_empty)
            androidx.compose.material3.TextButton(
                onClick = {
                    val notes = StudyNotes.text(cues, title)
                    if (notes.isEmpty()) {
                        android.widget.Toast.makeText(context, empty, android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        val send =
                            android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, notes)
                        context.startActivity(android.content.Intent.createChooser(send, title))
                    }
                },
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 4.dp))
                Text(stringResource(R.string.ai_study_notes))
            }
        }
        itemsIndexed(shown, key = { _, m -> "${answers != null}-${m.startMs}" }) { index, m ->
            val enter = remember { Animatable(if (still) 1f else 0f) }
            LaunchedEffect(Unit) { if (!still) enter.animateTo(1f, tween(380, delayMillis = 70 * index)) }
            Surface(
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = enter.value
                            translationY = (1f - enter.value) * 40f
                        }.border(BorderStroke(1.5.dp, brush), RoundedCornerShape(18.dp))
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onSeekMs(m.startMs) },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            formatDurationMillis(m.startMs),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(m.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
