package com.drdisagree.glasslayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos

@Composable
fun rememberLoopPhase(loopMs: Int, fps: Int = SCENE_FPS): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(loopMs, fps) {
        val loop = loopMs * NANOS_PER_MS
        val step = NANOS_PER_SECOND / fps.coerceAtLeast(1)
        val start = withFrameNanos { it } - (phase.floatValue * loop).toLong()
        var last = -1L
        while (true) withFrameNanos { now ->
            val index = (now - start) / step
            if (index != last) {
                last = index
                phase.floatValue = (index * step % loop).toFloat() / loop
            }
        }
    }
    return phase
}

private const val SCENE_FPS = 30
private const val NANOS_PER_MS = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000L
