package com.drdisagree.glasslayer.ui.settings

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.drdisagree.glasslayer.ui.components.GlassContainer
import com.drdisagree.glasslayer.ui.components.drawGlassScene
import com.drdisagree.glasslayer.xposed.glass.ClockBlur
import com.drdisagree.glasslayer.xposed.glass.ClockGlassShader
import com.drdisagree.glasslayer.xposed.glass.ClockMasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Canvas as ImageCanvas

@Composable
internal fun ClockPreview(
    blurDp: Float,
    refraction: Float,
    reflection: Float,
    rim: Float,
    tint: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, TEXT_WEIGHT, false)
        }
    }
    val shader = remember { RuntimeShader(ClockGlassShader.SOURCE) }
    val scene = remember(size) { sceneBitmap(size, density, direction) }
    val shortSide = size.height * TEXT_RATIO
    val mask = remember(size) {
        if (size.width <= 0 || size.height <= 0) {
            null
        } else {
            ClockMasks.build(size.width, size.height, ClockGlassShader.edge(shortSide, density.density)) {
                drawClock(it, paint, size.width, size.height)
            }
        }
    }
    val blurPx = with(density) { blurDp.dp.toPx() }
    val backdrop by produceState<Bitmap?>(null, scene, blurPx) {
        val base = scene ?: return@produceState
        value = withContext(Dispatchers.Default) { ClockBlur.apply(base, blurPx, base.width.toFloat()) }
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
            scene?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val image = backdrop
                        renderEffect = if (image == null || mask == null) {
                            null
                        } else {
                            ClockGlassShader.effect(
                                shader,
                                wallpaper(image, size),
                                mask,
                                shortSide,
                                density.density,
                                refraction,
                                reflection,
                                rim,
                                tint
                            ).asComposeRenderEffect()
                        }
                    }
                    .drawBehind {
                        drawIntoCanvas { drawClock(it.nativeCanvas, paint, size.width, size.height) }
                    }
            )
        }
    }
}

private fun drawClock(canvas: Canvas, paint: Paint, width: Int, height: Int) {
    paint.textSize = height * TEXT_RATIO
    val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(TIME, width / 2f, baseline, paint)
}

private fun wallpaper(image: Bitmap, size: IntSize): BitmapShader =
    BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        filterMode = BitmapShader.FILTER_MODE_LINEAR
        setLocalMatrix(Matrix().apply { setScale(size.width.toFloat() / image.width, size.height.toFloat() / image.height) })
    }

private fun sceneBitmap(size: IntSize, density: Density, direction: LayoutDirection): Bitmap? {
    if (size.width <= 0 || size.height <= 0) return null
    val image = ImageBitmap(size.width, size.height)
    CanvasDrawScope().draw(density, direction, ImageCanvas(image), Size(size.width.toFloat(), size.height.toFloat())) {
        drawGlassScene(SCENE_PHASE)
    }
    return image.asAndroidBitmap()
}

private val PREVIEW_HEIGHT = 196.dp
private val InnerShape = RoundedCornerShape(22.dp)
private const val TIME = "12:30"
private const val TEXT_RATIO = 0.62f
private const val TEXT_WEIGHT = 700
private const val SCENE_PHASE = 0.12f
