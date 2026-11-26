package com.drdisagree.glasslayer.xposed.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object ClockMasks {

    private const val BLUR = 0.25f
    private const val RAMP = 1.7f
    private const val PADDING = 8
    private const val SIZE_STEP = 8
    private const val MAX_WIDTH = 360
    private const val SIGMA_SCALE = 0.57735f

    fun scale(width: Int): Float = bucketWidth(width).toFloat() / max(1, width)

    fun blurRadius(edge: Float, scale: Float): Int = max(1, (edge * scale * BLUR).roundToInt())

    fun build(width: Int, height: Int, edge: Float, draw: (Canvas) -> Unit): ClockMask? {
        val bucket = bucketWidth(width)
        val scale = scale(width)
        val bucketHeight = max(1, (height * scale).roundToInt())
        val radius = blurRadius(edge, scale)
        val padding = PADDING + radius * 3
        val canvasWidth = bucket + padding * 2
        val canvasHeight = bucketHeight + padding * 2
        val place: (Canvas) -> Unit = { canvas ->
            canvas.translate(padding.toFloat(), padding.toFloat())
            canvas.scale(scale, scale)
            draw(canvas)
        }
        val sharp = software(canvasWidth, canvasHeight, place) ?: ClockMaskRenderer.render(canvasWidth, canvasHeight, place) ?: return null
        val sigma = sqrt(radius * (radius + 1f))
        val blurPaint = Paint().apply {
            maskFilter = BlurMaskFilter(max(0.5f, (sigma - 0.5f) / SIGMA_SCALE), BlurMaskFilter.Blur.NORMAL)
        }
        val offset = IntArray(2)
        val blurred = sharp.extractAlpha(blurPaint, offset)
        sharp.recycle()
        return ClockMask(
            BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR },
            offset[0] - padding.toFloat(),
            offset[1] - padding.toFloat(),
            scale,
            radius * RAMP
        )
    }

    private fun software(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap? {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        return try {
            draw(Canvas(bitmap))
            bitmap
        } catch (_: RuntimeException) {
            bitmap.recycle()
            null
        }
    }

    private fun bucketWidth(width: Int): Int =
        max(SIZE_STEP, (width / SIZE_STEP.toFloat()).roundToInt() * SIZE_STEP).coerceAtMost(MAX_WIDTH)
}
