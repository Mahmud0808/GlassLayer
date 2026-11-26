package com.drdisagree.glasslayer.xposed.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader

internal object ClockMaskRenderer {

    private const val NODE_NAME = "GlassLayerClockMask"

    fun render(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap? {
        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val node = RenderNode(NODE_NAME).apply { setPosition(0, 0, width, height) }
        val renderer = HardwareRenderer().apply {
            setSurface(reader.surface)
            setContentRoot(node)
            isOpaque = false
        }
        return try {
            val recording = node.beginRecording(width, height)
            draw(recording)
            node.endRecording()
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireLatestImage() ?: return null
            image.use {
                val buffer = it.hardwareBuffer ?: return null
                buffer.use { hardware ->
                    Bitmap.wrapHardwareBuffer(hardware, ColorSpace.get(ColorSpace.Named.SRGB))?.let { wrapped ->
                        wrapped.copy(Bitmap.Config.ARGB_8888, false).also { wrapped.recycle() }
                    }
                }
            }
        } finally {
            renderer.destroy()
            node.discardDisplayList()
            reader.close()
        }
    }
}
