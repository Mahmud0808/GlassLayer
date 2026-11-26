package com.drdisagree.glasslayer.xposed.systemui

internal data class ClockWallpaperRequest(
    val width: Int,
    val height: Int,
    val blurPx: Float,
    val generation: Int
)
