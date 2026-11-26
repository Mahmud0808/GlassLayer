package com.drdisagree.glasslayer.xposed.glass

import android.graphics.BitmapShader

internal class ClockMask(
    val shader: BitmapShader,
    val originX: Float,
    val originY: Float,
    val scale: Float,
    val range: Float
)
