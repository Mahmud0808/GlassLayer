package com.drdisagree.glasslayer.xposed.glass

import android.view.WindowManager

internal object WindowLayers {

    private const val TYPE_PRIVATE_PRESENTATION = 2030
    private const val TYPE_DOCK_DIVIDER = 2034
    private const val TYPE_QS_DIALOG = 2035
    private const val TYPE_VOICE_INTERACTION_STARTING = 2033
    private const val TYPE_VOICE_INTERACTION = 2031
    private const val TYPE_INPUT_CONSUMER = 2022
    private const val TYPE_STATUS_BAR_ADDITIONAL = 2041
    private const val TYPE_NOTIFICATION_SHADE = 2040
    private const val TYPE_NAVIGATION_BAR_PANEL = 2024
    private const val TYPE_DISPLAY_OVERLAY = 2026
    private const val TYPE_MAGNIFICATION_OVERLAY = 2027
    private const val TYPE_SECURE_SYSTEM_OVERLAY = 2015
    private const val TYPE_BOOT_PROGRESS = 2021
    private const val TYPE_POINTER = 2018
    private const val TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY = 2039
    private const val TYPE_VOLUME_OVERLAY = 2020
    private const val TYPE_PRESENTATION = 2037
    private const val TYPE_STATUS_BAR_SUB_PANEL = 2017
    private const val TYPE_NAVIGATION_BAR = 2019
    private const val TYPE_SCREENSHOT = 2036
    private const val TYPE_DRAG = 2016
    private const val APPLICATION_LAYER = 2

    @Suppress("DEPRECATION")
    fun of(type: Int): Int = when (type) {
        WindowManager.LayoutParams.TYPE_WALLPAPER -> 1
        TYPE_PRESENTATION,
        TYPE_PRIVATE_PRESENTATION,
        TYPE_DOCK_DIVIDER,
        TYPE_QS_DIALOG,
        WindowManager.LayoutParams.TYPE_PHONE -> 3
        WindowManager.LayoutParams.TYPE_SEARCH_BAR -> 4
        TYPE_INPUT_CONSUMER -> 5
        WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG -> 6
        WindowManager.LayoutParams.TYPE_TOAST -> 7
        WindowManager.LayoutParams.TYPE_PRIORITY_PHONE -> 8
        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT -> 12
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY -> 11
        WindowManager.LayoutParams.TYPE_INPUT_METHOD -> 13
        WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG -> 14
        WindowManager.LayoutParams.TYPE_STATUS_BAR -> 15
        TYPE_STATUS_BAR_ADDITIONAL -> 16
        TYPE_NOTIFICATION_SHADE -> 17
        TYPE_STATUS_BAR_SUB_PANEL -> 18
        WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG -> 19
        TYPE_VOICE_INTERACTION_STARTING -> 20
        TYPE_VOICE_INTERACTION -> 21
        TYPE_VOLUME_OVERLAY -> 22
        WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY -> 23
        TYPE_NAVIGATION_BAR -> 24
        TYPE_NAVIGATION_BAR_PANEL -> 25
        TYPE_SCREENSHOT -> 26
        WindowManager.LayoutParams.TYPE_SYSTEM_ERROR -> 27
        TYPE_MAGNIFICATION_OVERLAY -> 28
        TYPE_DISPLAY_OVERLAY -> 29
        TYPE_DRAG -> 30
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY -> 31
        TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY -> 32
        TYPE_SECURE_SYSTEM_OVERLAY -> 33
        TYPE_BOOT_PROGRESS -> 34
        TYPE_POINTER -> 35
        else -> APPLICATION_LAYER
    }
}
