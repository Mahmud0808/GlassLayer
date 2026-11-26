package com.drdisagree.glasslayer.xposed.systemui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.view.View
import android.view.ViewTreeObserver
import com.drdisagree.glasslayer.xposed.glass.ClockMask
import com.drdisagree.glasslayer.xposed.glass.ClockMasks
import java.lang.ref.WeakReference
import kotlin.math.abs

internal class ClockFace(view: View) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {

    val view = WeakReference(view)
    var observer: ViewTreeObserver? = null
    var shader: RuntimeShader? = null
    var backdrop: BitmapShader? = null
    var image: Bitmap? = null
    var applied: ClockGlassSettings? = null
    var appliedDensity = 0f
    var appliedWidth = 0
    var appliedHeight = 0
    val appliedMatrix = Matrix()
    val wallpaperSrc = RectF()
    val wallpaperDst = RectF()
    val localToScreen = Matrix()
    val screenToLocal = Matrix()
    val wallpaperToScreen = Matrix()
    val wallpaperToView = Matrix()
    private val appliedValues = FloatArray(MATRIX_SIZE)
    private val nextValues = FloatArray(MATRIX_SIZE)

    private var mask: ClockMask? = null
    private var maskWidth = 0
    private var maskHeight = 0
    private var maskBlurRadius = -1
    private var maskContent: String? = null
    var failures = 0
    var unsupported = false

    override fun onViewAttachedToWindow(view: View) = LockscreenClockGlass.attach(this, view)

    override fun onViewDetachedFromWindow(view: View) = LockscreenClockGlass.detach(this, view)

    override fun onPreDraw(): Boolean {
        LockscreenClockGlass.update(this)
        return true
    }

    fun needsMask(width: Int, height: Int, edge: Float, content: String): Boolean =
        mask == null || maskWidth != width || maskHeight != height ||
            maskBlurRadius != ClockMasks.blurRadius(edge, ClockMasks.scale(width)) || maskContent != content

    fun ensureMask(view: View, edge: Float, content: String): ClockMask? {
        val existing = mask
        if (existing != null && !needsMask(view.width, view.height, edge, content)) return existing
        val entry = ClockMasks.build(view.width, view.height, edge) { view.draw(it) } ?: return null
        mask = entry
        maskWidth = view.width
        maskHeight = view.height
        maskBlurRadius = ClockMasks.blurRadius(edge, ClockMasks.scale(view.width))
        maskContent = content
        return entry
    }

    fun sameMapping(next: Matrix): Boolean {
        appliedMatrix.getValues(appliedValues)
        next.getValues(nextValues)
        for (index in 0 until MATRIX_SIZE) {
            val tolerance = if (index == Matrix.MTRANS_X || index == Matrix.MTRANS_Y) TRANSLATION_SLOP else SCALE_SLOP
            if (abs(appliedValues[index] - nextValues[index]) > tolerance) return false
        }
        return true
    }

    fun clear(view: View) {
        if (image == null && shader == null) return
        view.setRenderEffect(null)
        image = null
        backdrop = null
        shader = null
        applied = null
        mask = null
    }
}

private const val MATRIX_SIZE = 9
private const val TRANSLATION_SLOP = 1f
private const val SCALE_SLOP = 0.001f
