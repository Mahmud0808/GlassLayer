package com.drdisagree.glasslayer.ui.settings

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.drdisagree.glasslayer.ui.components.GlassContainer
import com.drdisagree.glasslayer.ui.components.GlassScene
import com.drdisagree.glasslayer.xposed.glass.WaterRippleShader
import com.drdisagree.glasslayer.xposed.glass.WaterSurface

@Composable
internal fun RipplePreview(durationMs: Float, strength: Float, speed: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val water = remember { WaterSurface() }
    val shader = remember { RuntimeShader(WaterRippleShader.SOURCE) }
    val fieldMatrix = remember { Matrix() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var tick by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    val duration by rememberUpdatedState(durationMs.coerceAtLeast(1f))
    val rate by rememberUpdatedState(speed)

    DisposableEffect(water) { onDispose { water.release() } }

    LaunchedEffect(size) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        delay(START_DELAY_MS)
        var start = 0L
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (start == 0L || (now - start) / NANOS_PER_MS > duration + LOOP_PAUSE_MS) {
                    start = now
                    last = now
                    water.reset(size.width, size.height)
                    water.impulse(size.width / 2f, size.height * ORIGIN_Y)
                    water.fieldMatrix(fieldMatrix)
                    water.bitmap?.let { bitmap -> shader.setInputShader(WaterRippleShader.FIELD, fieldShader(bitmap, fieldMatrix)) }
                }
                val elapsedMs = (now - start) / NANOS_PER_MS
                progress = (elapsedMs / duration).coerceIn(0f, 1f)
                water.advance((now - last) / NANOS_PER_S, REFERENCE_MS / duration * rate)
                water.upload()
                last = now
                tick++
            }
        }
    }

    GlassContainer(CardShape, modifier.fillMaxWidth(), frosted = true) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .padding(8.dp)
                .clip(InnerShape)
                .onSizeChanged { size = it }
        ) {
            GlassScene(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (tick < 0 || water.bitmap == null) return@graphicsLayer
                        shader.setFloatUniform(WaterRippleShader.PROGRESS, progress)
                        shader.setFloatUniform(WaterRippleShader.REFRACTION, PREVIEW_REFRACTION_DP * density * strength)
                        shader.setFloatUniform(WaterRippleShader.HAS_BACKDROP, 1f)
                        shader.setFloatUniform(WaterRippleShader.OVERLAY, 0f)
                        shader.setFloatUniform(WaterRippleShader.RESOLUTION, size.width.toFloat(), size.height.toFloat())
                        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, WaterRippleShader.BACKDROP).asComposeRenderEffect()
                    }
            )
        }
    }
}

private fun fieldShader(bitmap: Bitmap, matrix: Matrix): BitmapShader =
    BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        filterMode = BitmapShader.FILTER_MODE_LINEAR
        setLocalMatrix(matrix)
    }

private val PREVIEW_HEIGHT = 196.dp
private val InnerShape = RoundedCornerShape(22.dp)
private const val NANOS_PER_MS = 1_000_000f
private const val NANOS_PER_S = 1_000_000_000f
private const val LOOP_PAUSE_MS = 900f
private const val START_DELAY_MS = 650L
private const val REFERENCE_MS = 800f
private const val ORIGIN_Y = 0.5f
private const val PREVIEW_REFRACTION_DP = 36f
