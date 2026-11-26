package com.drdisagree.glasslayer.xposed.systemui

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.drdisagree.glasslayer.xposed.glass.ClockBlur

internal object ClockWallpaper {

    private const val SAMPLE_SIZE = 640
    private const val SHARP_SAMPLE_SIZE = 720

    fun load(manager: WallpaperManager, request: ClockWallpaperRequest): Bitmap? {
        val which = if (manager.getWallpaperId(WallpaperManager.FLAG_LOCK) > 0) WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
        if (manager.getWallpaperInfo(which) != null) return null
        val tracked = LockWallpaperTracker.source
        val placement = LockWallpaperTracker.placement
        if (tracked != null && placement != null) return ClockBlur.apply(tracked, request.blurPx, placement.width())
        val target = if (request.blurPx <= 0f) SHARP_SAMPLE_SIZE else SAMPLE_SIZE
        val image = manager.getWallpaperFile(which)?.use { file ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(file.fileDescriptor, null, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / (options.inSampleSize * 2) >= target) options.inSampleSize *= 2
            options.inJustDecodeBounds = false
            BitmapFactory.decodeFileDescriptor(file.fileDescriptor, null, options)
        } ?: return null
        val coverWidth = maxOf(request.width.toFloat(), request.height.toFloat() * image.width / image.height)
        return ClockBlur.apply(image, request.blurPx, coverWidth)
    }
}
