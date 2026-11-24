package com.drdisagree.glasslayer.xposed.systemui

import android.app.ActivityManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Space
import android.widget.TextClock
import android.widget.TextView
import com.drdisagree.glasslayer.data.source.PrefKeys
import com.drdisagree.glasslayer.xposed.hook.HookEnv
import com.drdisagree.glasslayer.xposed.hook.call
import com.drdisagree.glasslayer.xposed.hook.callStatic
import com.drdisagree.glasslayer.xposed.hook.field
import com.drdisagree.glasslayer.xposed.hook.hookAllAfter
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object LockscreenClockGlass {

    private const val DEFAULT_MAX_ZOOM_SCALE = 1.1f
    private const val WALLPAPER_SAMPLE_SIZE = 640
    private const val SHARP_SAMPLE_SIZE = 720
    private const val LIQUID_BLUR_RATIO = 3f
    private const val LIQUID_DISPERSION = 0.06f
    private const val REFRACTION_RATIO = 0.18f
    private const val MAX_LIQUID_EDGE_DP = 9f
    private const val MAX_LIQUID_REFRACTION_DP = 11f
    private const val FROSTED_SAMPLE_RADIUS = 4
    private const val FROSTED_MIN_WIDTH = 16
    private const val MASK_BLUR = 0.25f
    private const val MASK_RAMP = 1.7f
    private const val MASK_PADDING = 8
    private const val MASK_SIZE_STEP = 8
    private const val MAX_MASK_WIDTH = 360
    private const val BLUR_SIGMA_SCALE = 0.57735f
    private const val TIME_SEPARATOR_CHARS = ":：∶•·∙⋅●○◉."

    private val FACE_METHODS = listOf("getSmallClock", "getLargeClock")
    private val QUOTED_TEXT = Regex("'(?:[^']|'')*'")
    private val CLOCK_ID_KEYWORDS = listOf("clock", "hour", "minute", "time", "colon", "dot", "sep", "divider")
    private val IGNORE_ID_KEYWORDS = listOf(
        "weather", "battery", "charging", "alarm", "date", "day", "month", "year",
        "media", "music", "notification", "status", "avatar", "wallpaper",
        "background", "backdrop", "shadow"
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val faces = WeakHashMap<View, Face>()
    private val customClocks = WeakHashMap<View, Unit>()

    private var wallpaperManager: WallpaperManager? = null
    private var requestedWallpaper: WallpaperRequest? = null
    private var wallpaper: Bitmap? = null
    private var wallpaperTask: Future<*>? = null
    private var wallpaperGeneration = 0
    private var wallpaperUserIsActive = false
    private var lastSettings: Settings? = null

    private val maxZoomScale by lazy { systemFloat("config_wallpaperMaxScale") ?: DEFAULT_MAX_ZOOM_SCALE }

    fun install(): Int {
        var hooks = 0

        hooks += HookEnv.findClass("${SystemUiModule.PACKAGE}.keyguard.ui.binder.KeyguardClockViewBinder")
            .hookAllAfter("addClockViews") { chain, _ ->
                val clock = chain.args.firstOrNull() ?: return@hookAllAfter
                for (faceMethod in FACE_METHODS) {
                    val layout = clock.call(faceMethod).call("getLayout")
                    val views = layout.call("getViews") as? Iterable<*> ?: continue
                    views.filterIsInstance<View>().forEach(::watchFace)
                }
            }

        hooks += HookEnv.findClass("${SystemUiModule.PACKAGE}.keyguard.ui.view.layout.sections.KeyguardClockStyleSection")
            .hookAllAfter("addViews") { chain, _ ->
                val clock = chain.thisObject.field("clockStyleView") as? View ?: return@hookAllAfter
                customClocks[clock] = Unit
                (clock.field("currentClockView") as? View)?.let(::watchCustomFace)
            }

        hooks += HookEnv.findClass("${SystemUiModule.PACKAGE}.clocks.ClockStyle")
            .hookAllAfter("updateClockView") { chain, _ ->
                if (chain.thisObject !in customClocks) return@hookAllAfter
                (chain.thisObject.field("currentClockView") as? View)?.let(::watchCustomFace)
            }

        return hooks
    }

    fun refresh() {
        mainHandler.post {
            val next = readSettings()
            val prev = lastSettings
            lastSettings = next
            if (prev != null && (!next.enabled || prev.blurPx != next.blurPx || prev.liquid != next.liquid)) {
                resetWallpaper()
            } else {
                faces.keys.forEach(View::invalidate)
            }
        }
    }

    private fun watchCustomFace(view: View, inNonClockGroup: Boolean = false) {
        if (view is Space) return
        val entryName = view.resourceEntryName()
        if (entryName != null && IGNORE_ID_KEYWORDS.any { it in entryName }) return

        when {
            view is TextClock -> {
                if (view.displaysTime() || view.displaysTimeSeparator() ||
                    (entryName != null && CLOCK_ID_KEYWORDS.any { it in entryName })
                ) {
                    watchFace(view)
                }
            }
            view is TextView -> {
                if (view.text.isTimeSeparatorText() || view.text.isTimeDigitsText() ||
                    (entryName != null && CLOCK_ID_KEYWORDS.any { it in entryName })
                ) {
                    watchFace(view)
                }
            }
            view is ViewGroup -> {
                val childInNonClockGroup = inNonClockGroup || view.hasNonClockTextChild()
                for (i in 0 until view.childCount) {
                    watchCustomFace(view.getChildAt(i), childInNonClockGroup)
                }
            }
            !inNonClockGroup || (entryName != null && CLOCK_ID_KEYWORDS.any { it in entryName }) -> watchFace(view)
        }
    }

    private fun View.resourceEntryName(): String? {
        if (id == View.NO_ID) return null
        return runCatching { resources.getResourceEntryName(id).lowercase() }.getOrNull()
    }

    private fun ViewGroup.hasNonClockTextChild(): Boolean {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is TextView && child !is TextClock &&
                !child.text.isTimeSeparatorText() && !child.text.isTimeDigitsText()
            ) {
                val name = child.resourceEntryName()
                if (name == null || CLOCK_ID_KEYWORDS.none { it in name }) return true
            }
        }
        return false
    }

    private fun TextClock.displaysTime(): Boolean =
        listOfNotNull(format12Hour, format24Hour).any { format ->
            format.replace(QUOTED_TEXT, "").any { it in "hHkKms" }
        }

    private fun TextClock.displaysTimeSeparator(): Boolean =
        text.isTimeSeparatorText() ||
            listOfNotNull(format12Hour, format24Hour).any { format ->
                format.toString().replace("'", "").isTimeSeparatorText()
            }

    private fun CharSequence?.isTimeDigitsText(): Boolean {
        val s = this?.toString()?.trim() ?: return false
        if (s.isEmpty() || s.length > 8) return false
        return s.any { it.isDigit() } &&
            s.all { it.isDigit() || it in TIME_SEPARATOR_CHARS || it.isWhitespace() }
    }

    private fun CharSequence?.isTimeSeparatorText(): Boolean {
        val s = this?.toString()?.trim() ?: return false
        if (s.isEmpty() || s.length > 8) return false
        return s.all { it in TIME_SEPARATOR_CHARS || it.isWhitespace() } &&
            s.any { it in TIME_SEPARATOR_CHARS }
    }

    private fun watchFace(view: View) {
        if (faces.containsKey(view)) return
        val face = Face()
        faces[view] = face
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = watchAttachedFace(view, face)

            override fun onViewDetachedFromWindow(view: View) {
                val observer = face.observer
                val listener = face.preDraw
                if (observer?.isAlive == true && listener != null) observer.removeOnPreDrawListener(listener)
                face.observer = null
                face.preDraw = null
                face.clear(view)
            }
        })
        if (view.isAttachedToWindow) watchAttachedFace(view, face)
    }

    private fun watchAttachedFace(view: View, face: Face) {
        runCatching { attachFace(view, face) }.onFailure { HookEnv.log("LockscreenClockGlass attach failed", it) }
    }

    private fun attachFace(view: View, face: Face) {
        if (face.preDraw != null) return
        if (wallpaperManager == null) {
            val context = view.context.applicationContext
            val wallpaperUser = context.call("getUserId") as? Int
            val activeUser = ActivityManager::class.java.callStatic("getCurrentUser") as? Int
            wallpaperUserIsActive = wallpaperUser != null && wallpaperUser == activeUser
            wallpaperManager = WallpaperManager.getInstance(context).also { manager ->
                manager.addOnColorsChangedListener({ _, _ -> resetWallpaper() }, mainHandler)
            }
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        wallpaperUserIsActive = wallpaperUser == intent.getIntExtra("android.intent.extra.user_handle", -1)
                        resetWallpaper()
                    }
                },
                IntentFilter("android.intent.action.USER_SWITCHED"),
                null,
                mainHandler,
                Context.RECEIVER_NOT_EXPORTED
            )
            context.getSystemService(DisplayManager::class.java)?.registerDisplayListener(
                object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) = Unit
                    override fun onDisplayRemoved(displayId: Int) = Unit
                    override fun onDisplayChanged(displayId: Int) {
                        faces.forEach { (clock, state) ->
                            if (clock.display?.displayId != displayId) return@forEach
                            if (clock.display?.state != Display.STATE_ON) state.clear(clock)
                            clock.invalidate()
                        }
                    }
                },
                mainHandler
            )
        }
        face.preDraw = ViewTreeObserver.OnPreDrawListener {
            if (!face.failed) {
                runCatching { updateFace(view, face) }.onFailure {
                    face.failed = true
                    face.clear(view)
                    HookEnv.log("LockscreenClockGlass update failed", it)
                }
            }
            true
        }
        face.observer = view.viewTreeObserver.also { it.addOnPreDrawListener(face.preDraw) }
    }

    private fun updateFace(view: View, face: Face) {
        val current = readSettings()
        lastSettings = current
        if (!current.enabled || !wallpaperUserIsActive ||
            view.display?.state != Display.STATE_ON ||
            view.display?.displayId != Display.DEFAULT_DISPLAY ||
            !view.isShown || view.width == 0 || view.height == 0 || !view.isHardwareAccelerated
        ) {
            face.clear(view)
            return
        }

        val root = view.rootView
        if (root.width <= 0 || root.height <= 0) return
        val density = view.resources.displayMetrics.density
        requestWallpaper(WallpaperRequest(root.width, root.height, current.blurPx, current.liquid))
        val image = wallpaper ?: return

        face.localToScreen.reset()
        view.transformMatrixToGlobal(face.localToScreen)
        if (!face.localToScreen.invert(face.screenToLocal)) return

        val screenWidth = root.width.toFloat()
        val screenHeight = root.height.toFloat()
        val scale = maxOf(screenWidth / image.width, screenHeight / image.height)
        val drawnWidth = image.width * scale
        val drawnHeight = image.height * scale
        val left = (screenWidth - drawnWidth) / 2f
        val top = (screenHeight - drawnHeight) / 2f
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        val zoomScale = maxZoomScale
        face.wallpaperSrc.set(0f, 0f, image.width.toFloat(), image.height.toFloat())
        face.wallpaperDst.set(
            centerX + (left - centerX) * zoomScale,
            centerY + (top - centerY) * zoomScale,
            centerX + (left + drawnWidth - centerX) * zoomScale,
            centerY + (top + drawnHeight - centerY) * zoomScale
        )
        face.wallpaperToScreen.setRectToRect(face.wallpaperSrc, face.wallpaperDst, Matrix.ScaleToFit.FILL)
        face.wallpaperToView.setConcat(face.screenToLocal, face.wallpaperToScreen)

        val viewText = (view as? TextView)?.text?.toString()
        val fontSignature = (view as? TextView)?.let {
            "${it.textSize}:${it.typeface?.hashCode()}:${it.fontVariationSettings}:${it.letterSpacing}"
        }
        val edge = if (current.liquid) {
            minOf(
                minOf(view.width.toFloat(), view.height.toFloat()) * REFRACTION_RATIO,
                MAX_LIQUID_EDGE_DP * density
            )
        } else 0f
        val maskChanged = current.liquid && face.needsMask(view.width, view.height, edge, viewText, fontSignature)

        if (!maskChanged &&
            face.image === image &&
            face.appliedSettings == current &&
            face.appliedMatrix == face.wallpaperToView &&
            face.appliedDensity == density &&
            face.appliedWidth == view.width &&
            face.appliedHeight == view.height
        ) {
            return
        }

        val backdrop = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        backdrop.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        backdrop.setLocalMatrix(face.wallpaperToView)

        val shader = if (current.liquid) {
            val mask = face.ensureMask(view, edge, viewText, fontSignature)
            val liquidShader = if (face.shaderMode == MODE_LIQUID && face.shader != null) {
                face.shader!!
            } else {
                RuntimeShader(LIQUID_CLOCK_SHADER).also {
                    face.shader = it
                    face.shaderMode = MODE_LIQUID
                }
            }
            val base = minOf(
                minOf(view.width.toFloat(), view.height.toFloat()) * REFRACTION_RATIO,
                MAX_LIQUID_REFRACTION_DP * density
            )
            liquidShader.setInputShader("wallpaper", backdrop)
            liquidShader.setInputShader("mask", mask.shader)
            liquidShader.setFloatUniform("maskOrigin", mask.originX / mask.scale, mask.originY / mask.scale)
            liquidShader.setFloatUniform("maskScale", mask.scale)
            liquidShader.setFloatUniform("maskRange", mask.range / mask.scale)
            liquidShader.setFloatUniform("refraction", base * current.refraction)
            liquidShader.setFloatUniform("edge", max(1f, edge))
            liquidShader.setFloatUniform("dispersion", LIQUID_DISPERSION * current.reflection)
            liquidShader.setFloatUniform("rim", current.rim)
            liquidShader.setFloatUniform("tint", current.tint)
            liquidShader
        } else {
            val frostedShader = if (face.shaderMode == MODE_FROSTED && face.shader != null) {
                face.shader!!
            } else {
                RuntimeShader(FROSTED_CLOCK_SHADER).also {
                    face.shader = it
                    face.shaderMode = MODE_FROSTED
                }
            }
            frostedShader.setInputShader("wallpaper", backdrop)
            frostedShader.setFloatUniform("tint", current.tint)
            frostedShader
        }

        view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "clock"))
        face.image = image
        face.appliedSettings = current
        face.appliedMatrix.set(face.wallpaperToView)
        face.appliedDensity = density
        face.appliedWidth = view.width
        face.appliedHeight = view.height
    }

    private fun requestWallpaper(request: WallpaperRequest) {
        if (requestedWallpaper == request) return
        val manager = wallpaperManager ?: return
        resetWallpaper()
        requestedWallpaper = request
        val generation = wallpaperGeneration
        wallpaperTask = worker.submit {
            val image = runCatching { loadWallpaper(manager, request) }.getOrNull()
            mainHandler.post {
                if (wallpaperGeneration != generation) return@post
                wallpaper = image
                wallpaperTask = null
                faces.keys.forEach(View::invalidate)
            }
        }
    }

    private fun loadWallpaper(manager: WallpaperManager, request: WallpaperRequest): Bitmap? {
        val flags = if (manager.getWallpaperId(WallpaperManager.FLAG_LOCK) > 0) {
            WallpaperManager.FLAG_LOCK
        } else {
            if (manager.wallpaperInfo != null) return null
            WallpaperManager.FLAG_SYSTEM
        }
        val targetSample = if (request.liquid && request.radius <= 0f) SHARP_SAMPLE_SIZE else WALLPAPER_SAMPLE_SIZE
        val drawableBitmap = (manager.call("getDrawable", flags) as? BitmapDrawable)?.bitmap
        val image = if (drawableBitmap != null && drawableBitmap.width > 0 && drawableBitmap.height > 0) {
            val soft = if (drawableBitmap.config == Bitmap.Config.HARDWARE) {
                drawableBitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                drawableBitmap
            }
            val maxDim = maxOf(soft.width, soft.height)
            if (maxDim > targetSample) {
                val ratio = targetSample.toFloat() / maxDim
                Bitmap.createScaledBitmap(
                    soft,
                    max(1, (soft.width * ratio).roundToInt()),
                    max(1, (soft.height * ratio).roundToInt()),
                    true
                ).also { if (soft !== drawableBitmap) soft.recycle() }
            } else {
                soft
            }
        } else {
            manager.getWallpaperFile(flags)?.use { file ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(file.fileDescriptor, null, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) return null
                options.inSampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / (options.inSampleSize * 2) >= targetSample) {
                    options.inSampleSize *= 2
                }
                options.inJustDecodeBounds = false
                BitmapFactory.decodeFileDescriptor(file.fileDescriptor, null, options)
            }
        } ?: return null

        val coverWidth = maxOf(request.width.toFloat(), request.height.toFloat() * image.width / image.height)
        val effectiveRadius = if (request.liquid) request.radius / LIQUID_BLUR_RATIO else request.radius
        val (width, radius) = frostedSample(effectiveRadius, coverWidth, image.width)
        return if (radius == 0) image else sampleBlur(image, width, radius)
    }

    private fun resetWallpaper() {
        wallpaperGeneration++
        wallpaperTask?.cancel(false)
        wallpaperTask = null
        requestedWallpaper = null
        wallpaper = null
        faces.forEach { (view, face) ->
            face.clear(view)
            view.invalidate()
        }
    }

    private fun readSettings(): Settings {
        val surface = GlassConfig.surface(PrefKeys.LOCKSCREEN_CLOCK)
        val style = surface.style
        val liquid = style.refraction > 0.001f || style.rim > 0.001f
        return Settings(
            enabled = GlassConfig.lockscreenClockActive,
            blurPx = surface.blurRadiusPx,
            tint = GlassConfig.lockscreenClockTint,
            refraction = style.refraction,
            reflection = style.reflection,
            rim = style.rim,
            liquid = liquid
        )
    }

    private fun frostedSample(radius: Float, coverWidth: Float, baseWidth: Int): Pair<Int, Int> {
        if (radius <= 0f || coverWidth <= 0f) return baseWidth to 0
        for (sampleRadius in FROSTED_SAMPLE_RADIUS downTo 1) {
            val sigma = sqrt(sampleRadius * (sampleRadius + 1f))
            val width = (coverWidth * sigma / radius).roundToInt()
            if (width <= baseWidth) return max(width, min(FROSTED_MIN_WIDTH, baseWidth)) to sampleRadius
        }
        return baseWidth to 1
    }

    private fun sampleBlur(base: Bitmap, width: Int, radius: Int): Bitmap {
        var source = base
        while (source.width / 2 >= width) {
            source = Bitmap.createScaledBitmap(source, source.width / 2, max(1, source.height / 2), true)
        }
        val sample = if (width == source.width) {
            if (source === base) base.copy(Bitmap.Config.ARGB_8888, true) else source
        } else {
            val height = max(1, (base.height.toLong() * width / base.width).toInt())
            Bitmap.createScaledBitmap(source, width, height, true)
        }.let { if (it.isMutable && it !== base) it else it.copy(Bitmap.Config.ARGB_8888, true) }
        return if (radius > 0) boxBlur(sample, radius) else sample
    }

    private fun boxBlur(bitmap: Bitmap, radius: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        val buffer = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        repeat(3) {
            blurPass(pixels, buffer, width, height, radius, horizontal = true)
            blurPass(buffer, pixels, width, height, radius, horizontal = false)
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun blurPass(
        input: IntArray,
        output: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean
    ) {
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
                output[index(line, i, width, horizontal)] =
                    (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val add = input[index(line, (i + radius + 1).coerceAtMost(length - 1), width, horizontal)]
                val remove = input[index(line, (i - radius).coerceAtLeast(0), width, horizontal)]
                r += (add shr 16 and 0xFF) - (remove shr 16 and 0xFF)
                g += (add shr 8 and 0xFF) - (remove shr 8 and 0xFF)
                b += (add and 0xFF) - (remove and 0xFF)
            }
        }
    }

    private fun index(line: Int, pos: Int, width: Int, horizontal: Boolean): Int =
        if (horizontal) line * width + pos else pos * width + line

    private fun systemFloat(name: String): Float? {
        val system = Resources.getSystem()
        val id = system.getIdentifier(name, "dimen", "android").takeIf { it != 0 } ?: return null
        return runCatching { system.getFloat(id) }.getOrNull()?.takeIf { it > 0f }
    }

    private fun maskBucketWidth(width: Int): Int =
        max(MASK_SIZE_STEP, (width / MASK_SIZE_STEP.toFloat()).roundToInt() * MASK_SIZE_STEP)
            .coerceAtMost(MAX_MASK_WIDTH)

    private fun maskBlurRadius(edge: Float): Int = max(1, (edge * MASK_BLUR).roundToInt())

    private class MaskEntry(
        val shader: BitmapShader,
        val originX: Float,
        val originY: Float,
        val scale: Float,
        val range: Float
    )

    private class Face {
        var observer: ViewTreeObserver? = null
        var preDraw: ViewTreeObserver.OnPreDrawListener? = null
        var failed = false
        var image: Bitmap? = null
        var shader: RuntimeShader? = null
        var shaderMode: Int = 0
        var appliedSettings: Settings? = null
        var appliedDensity = 0f
        var appliedWidth = 0
        var appliedHeight = 0
        var mask: MaskEntry? = null
        var maskWidth = 0
        var maskHeight = 0
        var maskBlurRadius = -1
        var maskText: String? = null
        var maskFontSignature: String? = null
        val wallpaperSrc = RectF()
        val wallpaperDst = RectF()
        val localToScreen = Matrix()
        val screenToLocal = Matrix()
        val wallpaperToScreen = Matrix()
        val wallpaperToView = Matrix()
        val appliedMatrix = Matrix()

        fun needsMask(width: Int, height: Int, edge: Float, text: String?, fontSignature: String?): Boolean {
            if (mask == null) return true
            val bucketWidth = maskBucketWidth(width)
            val scale = bucketWidth.toFloat() / max(1, width)
            val blurRadius = maskBlurRadius(edge * scale)
            return maskWidth != width || maskHeight != height || maskBlurRadius != blurRadius ||
                maskText != text || maskFontSignature != fontSignature
        }

        fun ensureMask(view: View, edge: Float, text: String?, fontSignature: String?): MaskEntry {
            val width = view.width
            val height = view.height
            val bucketWidth = maskBucketWidth(width)
            val scale = bucketWidth.toFloat() / max(1, width)
            val bucketHeight = max(1, (height * scale).roundToInt())
            val blurRadius = maskBlurRadius(edge * scale)
            mask?.let { existing ->
                if (maskWidth == width && maskHeight == height && maskBlurRadius == blurRadius &&
                    maskText == text && maskFontSignature == fontSignature
                ) {
                    return existing
                }
            }

            val padding = MASK_PADDING + blurRadius * 3
            val sharp = Bitmap.createBitmap(bucketWidth + padding * 2, bucketHeight + padding * 2, Bitmap.Config.ALPHA_8)
            Canvas(sharp).apply {
                translate(padding.toFloat(), padding.toFloat())
                scale(scale, scale)
                view.draw(this)
            }
            val sigma = sqrt(blurRadius * (blurRadius + 1f))
            val blurPaint = Paint().apply {
                maskFilter = BlurMaskFilter(max(0.5f, (sigma - 0.5f) / BLUR_SIGMA_SCALE), BlurMaskFilter.Blur.NORMAL)
            }
            val offset = IntArray(2)
            val blurred = sharp.extractAlpha(blurPaint, offset)
            sharp.recycle()
            val entry = MaskEntry(
                BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    .apply { setFilterMode(BitmapShader.FILTER_MODE_LINEAR) },
                offset[0] - padding.toFloat(),
                offset[1] - padding.toFloat(),
                scale,
                blurRadius * MASK_RAMP
            )
            mask = entry
            maskWidth = width
            maskHeight = height
            maskBlurRadius = blurRadius
            maskText = text
            maskFontSignature = fontSignature
            return entry
        }

        fun clear(view: View) {
            if (image == null && shader == null) return
            view.setRenderEffect(null)
            image = null
            shader = null
            shaderMode = 0
            appliedSettings = null
            mask = null
        }
    }

    private data class Settings(
        val enabled: Boolean,
        val blurPx: Float,
        val tint: Float,
        val refraction: Float,
        val reflection: Float,
        val rim: Float,
        val liquid: Boolean
    )

    private data class WallpaperRequest(
        val width: Int,
        val height: Int,
        val radius: Float,
        val liquid: Boolean
    )

    private const val MODE_FROSTED = 1
    private const val MODE_LIQUID = 2

    private const val FROSTED_CLOCK_SHADER = """
        uniform shader clock;
        uniform shader wallpaper;
        uniform float tint;

        half4 main(float2 position) {
            half4 glyph = clock.eval(position);
            half3 glass = wallpaper.eval(position).rgb * glyph.a;
            return half4(mix(glass, glyph.rgb, half(tint)), glyph.a);
        }
    """

    private const val LIQUID_CLOCK_SHADER = """
        uniform shader clock;
        uniform shader wallpaper;
        uniform shader mask;
        uniform float2 maskOrigin;
        uniform float maskScale;
        uniform float maskRange;
        uniform float refraction;
        uniform float edge;
        uniform float dispersion;
        uniform float rim;
        uniform float tint;

        float maskDistance(float2 coord) {
            float m = mask.eval((coord - maskOrigin) * maskScale).a;
            return (0.5 - m) * 2.0 * maskRange;
        }

        half4 main(float2 position) {
            half4 glyph = clock.eval(position);
            if (glyph.a <= 0.001) return half4(0.0);

            float d = maskDistance(position);
            float2 m = float2(2.0, 0.0);
            float2 normal = float2(
                maskDistance(position + m.xy) - maskDistance(position - m.xy),
                maskDistance(position + m.yx) - maskDistance(position - m.yx)
            );
            float width = min(edge, maskRange * 0.9);
            float strength = smoothstep(0.05, 0.4, length(normal));
            normal = normal / max(length(normal), 0.0001);

            float band = clamp(1.0 + d / max(width, 1.0), 0.0, 1.0) * smoothstep(2.0, -0.5, d);
            float bend = band * band * strength;
            float2 offset = -normal * refraction * bend;
            float2 tangent = float2(-normal.y, normal.x);
            float edgeBlur = bend * 3.0;

            half3 color = half3(0.0);
            if (edgeBlur > 0.05) {
                float2 rPos = position + offset * (1.0 + dispersion);
                float2 gPos = position + offset;
                float2 bPos = position + offset * (1.0 - dispersion);
                float2 s1 = tangent * edgeBlur;
                float2 s2 = normal * (edgeBlur * 0.6);

                color.r = (
                    wallpaper.eval(rPos).r +
                    wallpaper.eval(rPos + s1).r +
                    wallpaper.eval(rPos - s1).r +
                    wallpaper.eval(rPos + s2).r +
                    wallpaper.eval(rPos - s2).r
                ) * 0.2;
                color.g = (
                    wallpaper.eval(gPos).g +
                    wallpaper.eval(gPos + s1).g +
                    wallpaper.eval(gPos - s1).g +
                    wallpaper.eval(gPos + s2).g +
                    wallpaper.eval(gPos - s2).g
                ) * 0.2;
                color.b = (
                    wallpaper.eval(bPos).b +
                    wallpaper.eval(bPos + s1).b +
                    wallpaper.eval(bPos - s1).b +
                    wallpaper.eval(bPos + s2).b +
                    wallpaper.eval(bPos - s2).b
                ) * 0.2;
            } else {
                color.r = wallpaper.eval(position + offset * (1.0 + dispersion)).r;
                color.g = wallpaper.eval(position + offset).g;
                color.b = wallpaper.eval(position + offset * (1.0 - dispersion)).b;
            }

            color = mix(color, glyph.rgb / max(glyph.a, half(0.001)), half(tint));

            float rimWidth = max(2.5, width * 0.4);
            float rimBand = smoothstep(-rimWidth, 0.0, d) * smoothstep(2.0, -0.5, d) * strength;
            float2 lightDir = normalize(float2(-0.65, -0.76));
            float topLeft = pow(clamp(dot(normal, lightDir), 0.0, 1.0), 1.5);
            float bottomRight = pow(clamp(dot(normal, -lightDir), 0.0, 1.0), 1.5);
            float specular = topLeft + bottomRight * 0.85;
            color += half3(rim * rimBand * (0.06 + 0.55 * specular));
            color += half3(rim * bend * 0.16 * specular);
            color += half3(0.03);

            return half4(clamp(color, 0.0, 1.0) * glyph.a, glyph.a);
        }
    """
}
