package com.drdisagree.glasslayer.xposed.glass

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object ClockBlur {

    private const val BLUR_RATIO = 3f
    private const val SAMPLE_RADIUS = 4
    private const val MIN_WIDTH = 16
    private const val PASSES = 3

    fun apply(image: Bitmap, blurPx: Float, coverWidth: Float): Bitmap {
        val (width, radius) = sample(blurPx / BLUR_RATIO, coverWidth, image.width)
        return if (radius == 0) image else blurred(image, width, radius)
    }

    private fun sample(radius: Float, coverWidth: Float, baseWidth: Int): Pair<Int, Int> {
        if (radius <= 0f || coverWidth <= 0f) return baseWidth to 0
        for (sampleRadius in SAMPLE_RADIUS downTo 1) {
            val sigma = sqrt(sampleRadius * (sampleRadius + 1f))
            val width = (coverWidth * sigma / radius).roundToInt()
            if (width <= baseWidth) return max(width, min(MIN_WIDTH, baseWidth)) to sampleRadius
        }
        return baseWidth to 1
    }

    private fun blurred(base: Bitmap, width: Int, radius: Int): Bitmap {
        var source = base
        while (source.width / 2 >= width) {
            source = Bitmap.createScaledBitmap(source, source.width / 2, max(1, source.height / 2), true)
        }
        val height = max(1, (base.height.toLong() * width / base.width).toInt())
        val scaled = if (width == source.width) source else Bitmap.createScaledBitmap(source, width, height, true)
        val sample = if (scaled.isMutable && scaled !== base) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true)
        return boxBlur(sample, radius)
    }

    private fun boxBlur(bitmap: Bitmap, radius: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        val buffer = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        repeat(PASSES) {
            pass(pixels, buffer, width, height, radius, horizontal = true)
            pass(buffer, pixels, width, height, radius, horizontal = false)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun pass(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val pixel = input[index(line, i.coerceIn(0, length - 1), width, horizontal)]
                r += pixel shr 16 and 0xFF
                g += pixel shr 8 and 0xFF
                b += pixel and 0xFF
            }
            for (i in 0 until length) {
                output[index(line, i, width, horizontal)] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val add = input[index(line, (i + radius + 1).coerceAtMost(length - 1), width, horizontal)]
                val remove = input[index(line, (i - radius).coerceAtLeast(0), width, horizontal)]
                r += (add shr 16 and 0xFF) - (remove shr 16 and 0xFF)
                g += (add shr 8 and 0xFF) - (remove shr 8 and 0xFF)
                b += (add and 0xFF) - (remove and 0xFF)
            }
        }
    }

    private fun index(line: Int, position: Int, width: Int, horizontal: Boolean): Int =
        if (horizontal) line * width + position else position * width + line
}
