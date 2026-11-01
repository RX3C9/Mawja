/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.components.musicplayer.live

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ShaderBrush

/**
 * AGSL sources for the GPU live backgrounds (Android 13+). Every output is a convex mix of the
 * four palette colours, so [LiveColorDerivation]'s contrast guarantee holds on every pixel.
 * All time multipliers are whole numbers of cycles per [LiveFramePacing.LOOP_SECONDS] loop.
 */
internal object LiveBackgroundShaders {
    private const val HEADER = """
uniform float2 resolution;
uniform float time;
layout(color) uniform half4 ground;
layout(color) uniform half4 primary;
layout(color) uniform half4 secondary;
layout(color) uniform half4 highlight;
const float LOOP = 0.05235988; // 2 * pi / 120 s
"""

    val WAVES =
        HEADER +
            """
float band(float y, float edge) {
    return smoothstep(edge - 0.012, edge + 0.012, y);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float t = time * LOOP;
    float4 g = float4(ground);
    float4 p = float4(primary);
    float4 s = float4(secondary);
    float4 h = float4(highlight);
    float4 c = mix(p, g, smoothstep(0.0, 0.9, uv.y));
    float e1 = 0.50 + 0.05 * sin(uv.x * 5.0 + t * 7.0) + 0.025 * sin(uv.x * 11.0 - t * 11.0);
    float e2 = 0.64 + 0.045 * sin(uv.x * 4.0 - t * 5.0 + 1.3) + 0.02 * sin(uv.x * 9.0 + t * 13.0);
    float e3 = 0.78 + 0.035 * sin(uv.x * 6.5 + t * 9.0 + 2.1);
    c = mix(c, s, 0.45 * band(uv.y, e1));
    c = mix(c, h, 0.30 * band(uv.y, e2) * (1.0 - band(uv.y, e3)));
    c = mix(c, g, 0.70 * band(uv.y, e3));
    return half4(c);
}
"""

    val AURORA =
        HEADER +
            """
float ribbon(float2 uv, float centre, float width) {
    float d = (uv.y - centre) / width;
    return exp(-d * d);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float t = time * LOOP;
    float4 g = float4(ground);
    float4 p = float4(primary);
    float4 s = float4(secondary);
    float4 h = float4(highlight);
    float4 c = mix(p, g, smoothstep(0.0, 0.75, uv.y));
    float c1 = 0.22 + 0.06 * sin(uv.x * 3.0 + t * 4.0) + 0.03 * sin(uv.x * 7.0 - t * 9.0);
    float c2 = 0.34 + 0.05 * sin(uv.x * 2.5 - t * 3.0 + 1.7);
    float c3 = 0.46 + 0.04 * sin(uv.x * 4.5 + t * 6.0 + 0.6);
    float shimmer1 = 0.55 + 0.45 * sin(uv.x * 13.0 + t * 17.0);
    float shimmer2 = 0.55 + 0.45 * sin(uv.x * 9.0 - t * 11.0 + 2.0);
    c = mix(c, s, clamp(0.60 * ribbon(uv, c1, 0.07) * shimmer1, 0.0, 1.0));
    c = mix(c, h, clamp(0.45 * ribbon(uv, c2, 0.05) * shimmer2, 0.0, 1.0));
    c = mix(c, s, clamp(0.35 * ribbon(uv, c3, 0.09), 0.0, 1.0));
    return half4(c);
}
"""
}

/**
 * Draws [source] full-size with the palette uniforms set. Only on Android 13+, where AGSL exists;
 * callers check [Build.VERSION.SDK_INT] and fall back to the canvas layers below that, or when
 * [rememberRuntimeShader] returns null because the shader failed to compile.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun ShaderLiveLayer(
    shader: RuntimeShader,
    colors: LiveColors,
    time: () -> Float,
    modifier: Modifier = Modifier,
) {
    val brush = remember(shader) { ShaderBrush(shader) }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .drawBehind {
                    shader.setFloatUniform("resolution", size.width, size.height)
                    shader.setFloatUniform("time", time())
                    shader.setColorUniform("ground", colors.ground)
                    shader.setColorUniform("primary", colors.primary)
                    shader.setColorUniform("secondary", colors.secondary)
                    shader.setColorUniform("highlight", colors.highlight)
                    drawRect(brush)
                },
    )
}

/** Compiles [source] once; null when the device's AGSL compiler rejects it. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun rememberRuntimeShader(source: String): RuntimeShader? = remember(source) { runCatching { RuntimeShader(source) }.getOrNull() }
