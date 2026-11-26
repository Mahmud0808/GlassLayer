package com.drdisagree.glasslayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.drdisagree.glasslayer.ui.components.AppIcon
import com.drdisagree.glasslayer.ui.components.BackdropScreen
import com.drdisagree.glasslayer.ui.components.GlassContainer
import com.drdisagree.glasslayer.ui.components.GlassScene
import com.drdisagree.glasslayer.ui.theme.Accent
import com.drdisagree.glasslayer.ui.theme.GlassLayerTheme
import com.drdisagree.glasslayer.ui.theme.LocalPalette
import com.drdisagree.glasslayer.ui.theme.OnInk
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

@Composable
internal fun GlassPreview(
    blurDp: Float,
    refraction: Float,
    reflection: Float,
    rim: Float,
    shadow: Float,
    tints: List<Float>,
    pieces: List<PreviewPiece>,
    modifier: Modifier = Modifier,
    sceneDim: Float = 0f,
    sceneBlurDp: Float = 0f,
    showGlass: Boolean = true
) {
    val scene = rememberLayerBackdrop()
    val surface = if (LocalPalette.current.isDark) DARK_SURFACE else LIGHT_SURFACE
    GlassContainer(CardShape, modifier.fillMaxWidth(), frosted = true) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .padding(8.dp)
                .clip(InnerShape),
            contentAlignment = Alignment.Center
        ) {
            GlassScene(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(scene)
                    .blur(sceneBlurDp.dp),
                dim = sceneDim
            )
            if (showGlass) {
                val scale = minOf(
                    (maxWidth - CANVAS_MARGIN * 2) / PreviewLayouts.CANVAS_WIDTH.dp,
                    (maxHeight - CANVAS_MARGIN * 2) / PreviewLayouts.CANVAS_HEIGHT.dp
                )
                val areaWidth = maxWidth.value / scale
                val areaHeight = maxHeight.value / scale
                val centered = pieces.filter { it.anchor == ANCHOR_NONE }.ifEmpty { pieces }
                val left = centered.minOfOrNull { it.x } ?: 0f
                val right = centered.maxOfOrNull { it.x + it.width } ?: 0f
                val top = pieces.minOfOrNull { it.y } ?: 0f
                val bottom = pieces.maxOfOrNull { it.y + it.height } ?: 0f
                val shiftX = (areaWidth - (right - left)) / 2f - left
                val shiftY = (areaHeight - (bottom - top)) / 2f - top
                Box(Modifier.fillMaxSize()) {
                    pieces.filter { it.visible }.forEach { piece ->
                        val x = when (piece.anchor) {
                            ANCHOR_START -> EDGE_GAP
                            ANCHOR_END -> areaWidth - piece.width - EDGE_GAP
                            else -> piece.x + shiftX
                        }
                        GlassPiece(piece.copy(x = x, y = piece.y + shiftY), scale, scene, blurDp, refraction, reflection, rim, shadow, tints, surface)
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassPiece(
    piece: PreviewPiece,
    scale: Float,
    scene: Backdrop,
    blurDp: Float,
    refraction: Float,
    reflection: Float,
    rim: Float,
    shadow: Float,
    tints: List<Float>,
    surface: Color
) {
    val shape = pieceShape(piece, scale)
    val accent = Accent
    val baseTint = tints.getOrNull(piece.base) ?: 0f
    val ownTint = tints.getOrNull(piece.tint) ?: 0f
    val lensLimit = minOf(piece.width, piece.height) * scale * LENS_LIMIT
    Box(
        Modifier
            .offset((piece.x * scale).dp, (piece.y * scale).dp)
            .size((piece.width * scale).dp, (piece.height * scale).dp)
            .drawBackdrop(
                backdrop = scene,
                shape = { shape },
                effects = {
                    blur(blurDp.dp.toPx())
                    val lensHeight = minOf(LENS_HEIGHT * scale * refraction, lensLimit).dp.toPx()
                    lens(lensHeight, lensHeight * 2f, depthEffect = true)
                },
                highlight = { Highlight.Default.copy(alpha = rim.coerceIn(0f, 2f) * RIM_ALPHA) },
                shadow = { Shadow(radius = (SHADOW_RADIUS * scale).dp, alpha = (shadow * SHADOW_ALPHA).coerceIn(0f, 1f)) },
                onDrawSurface = {
                    val fill = piece.fill
                    if (fill != null) {
                        drawRect(fill)
                    } else {
                        if (baseTint > 0f) drawRect(surface.copy(alpha = baseTint.coerceIn(0f, 1f)))
                        drawRect((piece.color ?: if (piece.accent) accent else surface).copy(alpha = ownTint.coerceIn(0f, 1f)))
                    }
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = (reflection * SHEEN_ALPHA).coerceIn(0f, 1f)), Color.Transparent),
                            0f,
                            size.height * SHEEN_DEPTH
                        )
                    )
                }
            )
    ) {
        piece.icon?.let { icon ->
            AppIcon(icon, Modifier.align(Alignment.Center), tint = OnInk, size = (minOf(piece.width, piece.height) * scale * ICON_FRACTION).dp)
        }
    }
}

private fun pieceShape(piece: PreviewPiece, scale: Float): Shape {
    if (piece.radii.all { it == PILL }) return RoundedCornerShape(PILL_PERCENT)
    val (topStart, topEnd, bottomEnd, bottomStart) = piece.radii.map { (it * scale).dp }
    return RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)
}

@Preview(widthDp = 411, heightDp = 860)
@Composable
private fun QuickSettingsPreviews() {
    PreviewSheet(listOf(GlassPage.TILES, GlassPage.FOOTER, GlassPage.BRIGHTNESS, GlassPage.NOTIFICATIONS))
}

@Preview(widthDp = 411, heightDp = 860)
@Composable
private fun PanelPreviews() {
    PreviewSheet(listOf(GlassPage.BOUNCER, GlassPage.VOLUME, GlassPage.POWER_MENU, GlassPage.VOLUME_PANEL))
}

@Preview(widthDp = 411, heightDp = 860)
@Composable
private fun ControlPreviews() {
    PreviewSheet(listOf(GlassPage.SWITCHES, GlassPage.DIALOGS, GlassPage.SETTINGS_CARDS, GlassPage.KEYBOARD))
}

@Preview(widthDp = 411, heightDp = 860)
@Composable
private fun EdgePreviews() {
    PreviewSheet(listOf(GlassPage.LOCKSCREEN_SHORTCUTS, GlassPage.BACK_GESTURE, GlassPage.STATUS_BAR_CHIPS, GlassPage.TRANSITIONS))
}

@Composable
private fun PreviewSheet(pages: List<GlassPage>) {
    GlassLayerTheme(dark = true) {
        BackdropScreen(
            content = {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pages.forEach { page ->
                        GlassPreview(
                            blurDp = 6f,
                            refraction = 1f,
                            reflection = 1f,
                            rim = 1f,
                            shadow = 1f,
                            tints = List(page.levels.size) { index -> if (index == 0) 0.3f else 0.6f },
                            pieces = rememberPreviewPieces(page)
                        )
                    }
                }
            },
            overlay = {}
        )
    }
}

private val PREVIEW_HEIGHT = 196.dp
private val CANVAS_MARGIN = 4.dp
private const val EDGE_GAP = 16f
private val InnerShape = RoundedCornerShape(22.dp)
private const val PILL_PERCENT = 50
private const val ICON_FRACTION = 0.62f
private const val LENS_HEIGHT = 14f
private const val LENS_LIMIT = 0.25f
private const val SHADOW_RADIUS = 16f
private const val RIM_ALPHA = 0.5f
private const val SHADOW_ALPHA = 0.25f
private const val SHEEN_ALPHA = 0.22f
private const val SHEEN_DEPTH = 0.55f
private val LIGHT_SURFACE = Color(0xFFF4F4F6)
private val DARK_SURFACE = Color(0xFF2A2A2E)
