/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.appearance.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import app.mawja.android.ui.theme.ThemeMotif
import kotlin.math.PI
import kotlin.math.sin

private const val MOTIF_ALPHA = 0.22f

/** Draws [motif] in [color] at low opacity, filling the modifier's bounds. Decorative only. */
@Composable
internal fun ThemeMotifCanvas(
    motif: ThemeMotif,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val ink = color.copy(alpha = MOTIF_ALPHA)
    Canvas(modifier = modifier) {
        when (motif) {
            ThemeMotif.DUNES -> drawDunes(ink)
            ThemeMotif.ZELLIGE -> drawZellige(ink)
            ThemeMotif.STARS -> drawStars(ink)
            ThemeMotif.PAPYRUS -> drawPapyrus(ink)
            ThemeMotif.REEF -> drawReef(ink)
            ThemeMotif.RIPPLES -> drawRipples(ink)
        }
    }
}

private fun DrawScope.strokeWidth(): Float = size.minDimension * 0.035f

private fun DrawScope.drawWaves(
    ink: Color,
    rows: Int,
    amplitude: Float,
    cycles: Float,
) {
    val steps = 24
    for (row in 1..rows) {
        val y0 = size.height * row / (rows + 1)
        val path = Path()
        for (i in 0..steps) {
            val x = size.width * i / steps
            val y = y0 + amplitude * sin((i.toFloat() / steps) * cycles * 2f * PI.toFloat() + row)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, ink, style = Stroke(width = strokeWidth()))
    }
}

private fun DrawScope.drawDunes(ink: Color) = drawWaves(ink, rows = 3, amplitude = size.height * 0.07f, cycles = 1f)

private fun DrawScope.drawRipples(ink: Color) = drawWaves(ink, rows = 4, amplitude = size.height * 0.04f, cycles = 2f)

private fun DrawScope.drawZellige(ink: Color) {
    val cell = size.minDimension / 2.2f
    val half = cell * 0.32f
    var y = cell / 2
    while (y < size.height + cell) {
        var x = cell / 2
        while (x < size.width + cell) {
            val pivot = Offset(x, y)
            listOf(0f, 45f).forEach { angle ->
                rotate(angle, pivot) {
                    drawRect(
                        color = ink,
                        topLeft = Offset(pivot.x - half, pivot.y - half),
                        size = Size(half * 2, half * 2),
                        style = Stroke(width = strokeWidth()),
                    )
                }
            }
            x += cell
        }
        y += cell
    }
}

private val StarPoints = listOf(0.12f to 0.2f, 0.4f to 0.12f, 0.78f to 0.18f, 0.25f to 0.62f, 0.6f to 0.5f, 0.88f to 0.72f, 0.48f to 0.86f)

private fun DrawScope.drawStars(ink: Color) {
    StarPoints.forEachIndexed { index, (fx, fy) ->
        val r = size.minDimension * if (index % 3 == 0) 0.05f else 0.03f
        val c = Offset(size.width * fx, size.height * fy)
        drawLine(ink, Offset(c.x - r * 2, c.y), Offset(c.x + r * 2, c.y), strokeWidth() * 0.7f)
        drawLine(ink, Offset(c.x, c.y - r * 2), Offset(c.x, c.y + r * 2), strokeWidth() * 0.7f)
        drawCircle(ink, r, c)
    }
}

private fun DrawScope.drawPapyrus(ink: Color) {
    val stems = 5
    for (i in 0 until stems) {
        val x = size.width * (i + 0.5f) / stems
        val top = size.height * (0.25f + 0.1f * (i % 2))
        drawLine(ink, Offset(x, size.height), Offset(x, top), strokeWidth())
        val fan = size.minDimension * 0.12f
        listOf(-1f, 0f, 1f).forEach { dir ->
            drawLine(ink, Offset(x, top), Offset(x + dir * fan, top - fan), strokeWidth() * 0.8f)
        }
    }
}

private val BubblePoints = listOf(0.15f to 0.75f, 0.32f to 0.4f, 0.55f to 0.7f, 0.7f to 0.25f, 0.88f to 0.55f, 0.45f to 0.15f)

private fun DrawScope.drawReef(ink: Color) {
    BubblePoints.forEachIndexed { index, (fx, fy) ->
        val r = size.minDimension * (0.06f + 0.03f * (index % 3))
        drawCircle(ink, r, Offset(size.width * fx, size.height * fy), style = Stroke(width = strokeWidth()))
    }
}
