package com.drdisagree.glasslayer.xposed.systemui

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.WindowManager
import com.drdisagree.glasslayer.xposed.hook.Guard
import com.drdisagree.glasslayer.xposed.hook.HookEnv
import com.drdisagree.glasslayer.xposed.hook.call
import com.drdisagree.glasslayer.xposed.hook.field
import com.drdisagree.glasslayer.xposed.hook.hookAllAfter
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.roundToInt

internal object LockWallpaperTracker {

    private const val CANVAS_ENGINE = "${SystemUiModule.PACKAGE}.wallpapers.ImageWallpaper\$CanvasEngine"
    private const val BASE_ENGINE = "android.service.wallpaper.WallpaperService\$Engine"
    private const val MAX_SCALE_RES = "config_wallpaperMaxScale"
    private const val MAX_SANE_SCALE = 2f
    private const val CENTER = 0.5f
    private const val SAMPLE_SIZE = 720

    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: WeakReference<Any>? = null
    private var listener: (() -> Unit)? = null

    @Volatile
    var source: Bitmap? = null
        private set

    @Volatile
    var placement: RectF? = null
        private set

    @Volatile
    var scale = 1f
        private set

    @Volatile
    var generation = 0
        private set

    fun install(onChanged: () -> Unit): Int {
        listener = onChanged
        var hooks = HookEnv.findClass(CANVAS_ENGINE).hookAllAfter("drawFrameOnCanvas") { chain, _ ->
            val current = chain.thisObject ?: return@hookAllAfter
            val bitmap = chain.args.firstOrNull() as? Bitmap ?: return@hookAllAfter
            if (bitmap.isRecycled || !isLock(current)) return@hookAllAfter
            engine = WeakReference(current)
            capture(current, bitmap)
        }
        hooks += HookEnv.findClass(BASE_ENGINE).hookAllAfter("setZoom") { chain, _ ->
            val current = chain.thisObject ?: return@hookAllAfter
            if (engine?.get() !== current) return@hookAllAfter
            updateScale(current)
        }
        return hooks
    }

    private fun capture(engine: Any, bitmap: Bitmap) {
        val context = engine.call("getDisplayContext") as? Context ?: return
        val display = context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds ?: return
        if (display.isEmpty) return
        val next = placementOf(engine, context, display, bitmap.width, bitmap.height) ?: return
        val ratio = SAMPLE_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val copy = if (ratio < 1f) {
            Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * ratio).roundToInt()), max(1, (bitmap.height * ratio).roundToInt()), true)
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } ?: return
        source = copy
        placement = next
        generation++
        updateScale(engine)
        mainHandler.post { Guard.run(TAG_CHANGED, Unit) { listener?.invoke() } }
    }

    private fun updateScale(engine: Any) {
        val zoom = ((engine.call("getZoom") as? Float) ?: 0f).coerceIn(0f, 1f)
        val zoomsOut = engine.call("shouldZoomOutWallpaper") as? Boolean ?: true
        val next = if (zoomsOut) 1f + (maxScale() - 1f) * (1f - zoom) else 1f
        if (next == scale) return
        scale = next
        mainHandler.post { Guard.run(TAG_CHANGED, Unit) { listener?.invoke() } }
    }

    private fun isLock(engine: Any): Boolean {
        val flags = engine.call("getWallpaperFlags") as? Int ?: return true
        return flags and WallpaperManager.FLAG_LOCK != 0
    }

    private fun placementOf(engine: Any, context: Context, display: Rect, width: Int, height: Int): RectF? {
        if (width <= 0 || height <= 0) return null
        val surface = (engine.field("mRelayoutResult")?.field("frames")?.field("frame") as? Rect)?.takeUnless { it.isEmpty }
            ?: (engine.field("mWinFrames")?.field("frame") as? Rect)?.takeUnless { it.isEmpty }
            ?: (engine.call("getSurfaceHolder") as? SurfaceHolder)?.surfaceFrame?.takeUnless { it.isEmpty }
            ?: return null
        val crop = cropOf(engine, context, display)
        val xOffset = ((engine.field("mPendingXOffset") as? Float) ?: CENTER).coerceIn(0f, 1f)
        val yOffset = ((engine.field("mPendingYOffset") as? Float) ?: CENTER).coerceIn(0f, 1f)
        val scaleX = surface.width().toFloat() / width
        val scaleY = surface.height().toFloat() / height
        val cropLeft = (crop?.left ?: 0) * scaleX
        val cropTop = (crop?.top ?: 0) * scaleY
        val cropWidth = crop?.let { it.width() * scaleX } ?: surface.width().toFloat()
        val cropHeight = crop?.let { it.height() * scaleY } ?: surface.height().toFloat()
        val spareX = cropWidth - display.width()
        val spareY = cropHeight - display.height()
        val left = surface.left - (cropLeft + (if (spareX > 0f) spareX * xOffset else 0f)).roundToInt()
        val top = surface.top - (cropTop + (if (spareY > 0f) spareY * yOffset else 0f)).roundToInt()
        return RectF(left.toFloat(), top.toFloat(), (left + surface.width()).toFloat(), (top + surface.height()).toFloat())
    }

    private fun cropOf(engine: Any, context: Context, display: Rect): Rect? {
        val flags = engine.call("getWallpaperFlags") as? Int ?: 0
        val which = if (flags and WallpaperManager.FLAG_SYSTEM != 0) WallpaperManager.FLAG_SYSTEM else WallpaperManager.FLAG_LOCK
        val manager = WallpaperManager.getInstance(context)
        val crops = runCatching {
            manager.call("getBitmapCrops", listOf(Point(display.width(), display.height())), which, false) as? List<*>
        }.getOrNull()
        return (crops?.firstOrNull() as? Rect)?.takeUnless { it.isEmpty }
    }

    private fun maxScale(): Float {
        val resources = Resources.getSystem()
        val id = resources.getIdentifier(MAX_SCALE_RES, "dimen", "android")
        if (id == 0) return 1f
        return runCatching { resources.getFloat(id) }.getOrNull()?.takeIf { it in 1f..MAX_SANE_SCALE } ?: 1f
    }
}

private const val TAG_CHANGED = "LockWallpaperTracker.changed"
