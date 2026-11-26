package com.drdisagree.glasslayer.xposed.systemui

import android.app.ActivityManager
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import com.drdisagree.glasslayer.data.source.PrefKeys
import com.drdisagree.glasslayer.xposed.glass.ClockGlassShader
import com.drdisagree.glasslayer.xposed.hook.Guard
import com.drdisagree.glasslayer.xposed.hook.HookEnv
import com.drdisagree.glasslayer.xposed.hook.HostApplication
import com.drdisagree.glasslayer.xposed.hook.call
import com.drdisagree.glasslayer.xposed.hook.callStatic
import com.drdisagree.glasslayer.xposed.hook.field
import com.drdisagree.glasslayer.xposed.hook.hookAllAfter
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

object LockscreenClockGlass {

    private const val CLOCK_BINDER = "${SystemUiModule.PACKAGE}.keyguard.ui.binder.KeyguardClockViewBinder"
    private const val STYLE_SECTION = "${SystemUiModule.PACKAGE}.keyguard.ui.view.layout.sections.KeyguardClockStyleSection"
    private const val CLOCK_STYLE = "${SystemUiModule.PACKAGE}.clocks.ClockStyle"
    private const val CLOCK_EVENTS = "com.android.keyguard.ClockEventController"
    private const val USER_SWITCHED = "android.intent.action.USER_SWITCHED"
    private const val EXTRA_USER = "android.intent.extra.user_handle"
    private const val MAX_SCALE_RES = "config_wallpaperMaxScale"
    private const val DEFAULT_MAX_ZOOM = 1.1f
    private const val MAX_MASK_FAILURES = 3
    private val FACE_METHODS = listOf("getSmallClock", "getLargeClock")

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val faces = WeakHashMap<View, ClockFace>()
    private val customClocks = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val found = ArrayList<View>()

    private var manager: WallpaperManager? = null
    private var wallpaperUser: Int? = null
    private var userActive = false
    private var settings: ClockGlassSettings? = null
    private var requested: ClockWallpaperRequest? = null
    private var wallpaper: Bitmap? = null
    private var task: Future<*>? = null
    private var generation = 0

    private val maxZoom by lazy {
        val system = Resources.getSystem()
        val id = system.getIdentifier(MAX_SCALE_RES, "dimen", "android")
        if (id == 0) DEFAULT_MAX_ZOOM else runCatching { system.getFloat(id) }.getOrNull()?.takeIf { it > 0f } ?: DEFAULT_MAX_ZOOM
    }

    fun install(): Int {
        var hooks = HookEnv.findClass(CLOCK_BINDER).hookAllAfter("addClockViews") { chain, _ ->
            chain.args.firstOrNull()?.let(::watchClock)
        }
        hooks += HookEnv.findClass(CLOCK_EVENTS).hookAllAfter("setClock") { chain, _ ->
            chain.args.firstOrNull()?.let(::watchClock)
        }
        hooks += HookEnv.findClass(STYLE_SECTION).hookAllAfter("addViews") { chain, _ ->
            val clock = chain.thisObject.field("clockStyleView") ?: return@hookAllAfter
            customClocks.add(clock)
            (clock.field("currentClockView") as? View)?.let(::watchCustom)
        }
        hooks += HookEnv.findClass(CLOCK_STYLE).hookAllAfter("updateClockView") { chain, _ ->
            val clock = chain.thisObject ?: return@hookAllAfter
            if (clock !in customClocks) return@hookAllAfter
            (clock.field("currentClockView") as? View)?.let(::watchCustom)
        }
        return hooks
    }

    fun refresh() {
        mainHandler.post {
            Guard.run(TAG_REFRESH, Unit) {
                val next = readSettings()
                val previous = settings
                settings = next
                if (previous != null && previous.blurPx != next.blurPx) resetWallpaper() else invalidateFaces()
            }
        }
    }

    internal fun attach(face: ClockFace, view: View) {
        Guard.run(TAG_ATTACH, Unit) {
            ensureServices(HostApplication.get() ?: return@run)
            if (face.observer?.isAlive == true) return@run
            face.observer = view.viewTreeObserver.also { it.addOnPreDrawListener(face) }
        }
    }

    internal fun detach(face: ClockFace, view: View) {
        Guard.run(TAG_DETACH, Unit) {
            face.observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(face)
            face.observer = null
            face.clear(view)
        }
    }

    internal fun update(face: ClockFace) {
        Guard.run(TAG_UPDATE, Unit) { apply(face) }
    }

    private fun watchClock(clock: Any) {
        FACE_METHODS.forEach { method ->
            val face = clock.call(method) ?: return@forEach
            (face.call("getView") as? View)?.let(::watch)
            (face.call("getLayout")?.call("getViews") as? Iterable<*>)?.filterIsInstance<View>()?.forEach(::watch)
        }
    }

    private fun watchCustom(root: View) {
        found.clear()
        ClockViews.collect(root, found)
        found.forEach(::watch)
        found.clear()
    }

    private fun watch(view: View) {
        if (faces.containsKey(view)) return
        val face = ClockFace(view)
        faces[view] = face
        view.addOnAttachStateChangeListener(face)
        if (view.isAttachedToWindow) attach(face, view)
    }

    private fun ensureServices(context: Context) {
        if (manager != null) return
        wallpaperUser = context.call("getUserId") as? Int
        userActive = wallpaperUser != null && wallpaperUser == ActivityManager::class.java.callStatic("getCurrentUser") as? Int
        manager = WallpaperManager.getInstance(context).also { wallpapers ->
            wallpapers.addOnColorsChangedListener({ _, _ -> Guard.run(TAG_WALLPAPER, Unit) { resetWallpaper() } }, mainHandler)
        }
        context.registerReceiver(
            ClockUserReceiver { intent -> Guard.run(TAG_USER, Unit) { onUserSwitched(intent) } },
            IntentFilter(USER_SWITCHED),
            null,
            mainHandler,
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun onUserSwitched(intent: Intent) {
        userActive = wallpaperUser == intent.getIntExtra(EXTRA_USER, -1)
        resetWallpaper()
    }

    private fun apply(face: ClockFace) {
        val view = face.view.get() ?: return
        if (face.unsupported) return
        val current = settings ?: readSettings().also { settings = it }
        val display = view.display
        if (!GlassConfig.lockscreenClockActive || !userActive || display == null ||
            display.state != Display.STATE_ON || display.displayId != Display.DEFAULT_DISPLAY ||
            !view.isShown || view.width == 0 || view.height == 0 || !view.isHardwareAccelerated
        ) {
            face.clear(view)
            return
        }
        val root = view.rootView
        if (root.width <= 0 || root.height <= 0) return
        requestWallpaper(ClockWallpaperRequest(root.width, root.height, current.blurPx))
        val image = wallpaper ?: return

        face.localToScreen.reset()
        view.transformMatrixToGlobal(face.localToScreen)
        if (!face.localToScreen.invert(face.screenToLocal)) return
        mapWallpaper(face, image, root.width.toFloat(), root.height.toFloat())

        val density = view.resources.displayMetrics.density
        val content = ClockViews.signature(view)
        val short = minOf(view.width, view.height).toFloat()
        val edge = ClockGlassShader.edge(short, density)
        val maskChanged = face.needsMask(view.width, view.height, edge, content)
        if (!maskChanged && face.image === image && face.applied == current && face.appliedMatrix == face.wallpaperToView &&
            face.appliedDensity == density && face.appliedWidth == view.width && face.appliedHeight == view.height
        ) {
            return
        }

        val backdrop = face.backdrop?.takeIf { face.image === image }
            ?: BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
        backdrop.setLocalMatrix(face.wallpaperToView)
        val mask = try {
            face.ensureMask(view, edge, content)
        } catch (_: RuntimeException) {
            null
        }
        if (mask == null) {
            if (++face.failures >= MAX_MASK_FAILURES) {
                face.unsupported = true
                HookEnv.log("LockscreenClockGlass: ${view.javaClass.name} cannot be drawn into a mask, left stock")
            }
            face.clear(view)
            return
        }
        face.failures = 0
        val shader = face.shader ?: RuntimeShader(ClockGlassShader.SOURCE)
        view.setRenderEffect(
            ClockGlassShader.effect(shader, backdrop, mask, short, density, current.refraction, current.reflection, current.rim, current.tint)
        )

        face.shader = shader
        face.backdrop = backdrop
        face.image = image
        face.applied = current
        face.appliedMatrix.set(face.wallpaperToView)
        face.appliedDensity = density
        face.appliedWidth = view.width
        face.appliedHeight = view.height
    }

    private fun mapWallpaper(face: ClockFace, image: Bitmap, screenWidth: Float, screenHeight: Float) {
        val scale = maxOf(screenWidth / image.width, screenHeight / image.height)
        val drawnWidth = image.width * scale
        val drawnHeight = image.height * scale
        val left = (screenWidth - drawnWidth) / 2f
        val top = (screenHeight - drawnHeight) / 2f
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        val zoom = maxZoom
        face.wallpaperSrc.set(0f, 0f, image.width.toFloat(), image.height.toFloat())
        face.wallpaperDst.set(
            centerX + (left - centerX) * zoom,
            centerY + (top - centerY) * zoom,
            centerX + (left + drawnWidth - centerX) * zoom,
            centerY + (top + drawnHeight - centerY) * zoom
        )
        face.wallpaperToScreen.setRectToRect(face.wallpaperSrc, face.wallpaperDst, Matrix.ScaleToFit.FILL)
        face.wallpaperToView.setConcat(face.screenToLocal, face.wallpaperToScreen)
    }

    private fun requestWallpaper(request: ClockWallpaperRequest) {
        if (requested == request) return
        val wallpapers = manager ?: return
        resetWallpaper()
        requested = request
        val current = generation
        task = worker.submit {
            val image = Guard.run(TAG_LOAD, null) { ClockWallpaper.load(wallpapers, request) }
            mainHandler.post {
                Guard.run(TAG_WALLPAPER, Unit) {
                    if (generation != current) return@run
                    wallpaper = image
                    task = null
                    invalidateFaces()
                }
            }
        }
    }

    private fun resetWallpaper() {
        generation++
        task?.cancel(false)
        task = null
        requested = null
        wallpaper = null
        faces.forEach { (view, face) ->
            face.clear(view)
            view.invalidate()
        }
    }

    private fun invalidateFaces() {
        faces.keys.forEach(View::invalidate)
    }

    private fun readSettings(): ClockGlassSettings {
        val surface = GlassConfig.surface(PrefKeys.LOCKSCREEN_CLOCK)
        return ClockGlassSettings(
            blurPx = surface.blurRadiusPx,
            tint = GlassConfig.lockscreenClockTint,
            refraction = surface.style.refraction,
            reflection = surface.style.reflection,
            rim = surface.style.rim
        )
    }
}

private const val TAG_REFRESH = "LockscreenClockGlass.refresh"
private const val TAG_ATTACH = "LockscreenClockGlass.attach"
private const val TAG_DETACH = "LockscreenClockGlass.detach"
private const val TAG_UPDATE = "LockscreenClockGlass.update"
private const val TAG_WALLPAPER = "LockscreenClockGlass.wallpaper"
private const val TAG_LOAD = "LockscreenClockGlass.load"
private const val TAG_USER = "LockscreenClockGlass.user"
