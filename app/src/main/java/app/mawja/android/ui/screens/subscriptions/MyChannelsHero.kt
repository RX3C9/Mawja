/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.subscriptions

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mawja.android.R
import app.mawja.android.accessibility.MawjaAccessibility
import app.mawja.android.data.model.Channel
import app.mawja.android.ui.components.shared.ChannelAvatarStack
import kotlin.math.PI
import kotlin.math.sin

/**
 * P2 "My channels": a layered hero over the manage list — the count rolls up, two translucent waves
 * drift behind it (still under Reduce motion), and the newest avatars sit stacked on the right.
 */
@Composable
internal fun MyChannelsHero(
    shown: List<Channel>,
    total: Int,
    musicCount: Int,
    modifier: Modifier = Modifier,
) {
    val still = MawjaAccessibility.reduceMotionNow()
    val count = remember { Animatable(if (still) shown.size.toFloat() else 0f) }
    LaunchedEffect(shown.size) {
        if (still) count.snapTo(shown.size.toFloat()) else count.animateTo(shown.size.toFloat(), tween(900, easing = FastOutSlowInEasing))
    }
    val phase by rememberInfiniteTransition(label = "heroWave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart),
        label = "heroPhase",
    )
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .shadow(14.dp, shape, ambientColor = primary, spotColor = primary)
            .clip(shape)
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer))),
    ) {
        Canvas(Modifier.matchParentSize()) {
            listOf(0.55f to 0.22f, 0.7f to 0.14f).forEachIndexed { layer, (level, alpha) ->
                val p = Path()
                val base = size.height * level
                val amp = size.height * 0.08f
                p.moveTo(0f, size.height)
                var x = 0f
                while (x <= size.width) {
                    val t = if (still) 0f else phase
                    p.lineTo(x, base + amp * sin(x / size.width * 2f * PI.toFloat() * (1 + layer) + t * (if (layer == 0) 1 else -1)))
                    x += 8f
                }
                p.lineTo(size.width, size.height)
                p.close()
                drawPath(p, Brush.verticalGradient(listOf(if (layer == 0) primary else tertiary, Color.Transparent), startY = base - amp, endY = size.height), alpha = alpha, style = Fill)
            }
            drawCircle(Color.White.copy(alpha = 0.10f), radius = size.height * 0.6f, center = Offset(size.width * 0.92f, -size.height * 0.1f))
        }
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.my_channels_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    count.value.toInt().toString(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    pluralStringResource(R.plurals.my_channels_breakdown, total, total, total - musicCount, musicCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            if (shown.isNotEmpty()) {
                ChannelAvatarStack(
                    urls = shown.map { it.thumbnailUrl },
                    contentDescription = null,
                    avatarSize = 52.dp,
                    ringColor = MaterialTheme.colorScheme.primaryContainer,
                )
            }
        }
    }
}
