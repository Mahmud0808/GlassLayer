package com.drdisagree.glasslayer.xposed.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal class ClockUserReceiver(private val onSwitched: (Intent) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = onSwitched(intent)
}
