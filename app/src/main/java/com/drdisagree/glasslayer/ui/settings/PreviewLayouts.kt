package com.drdisagree.glasslayer.ui.settings

import androidx.compose.ui.graphics.Color
import com.drdisagree.glasslayer.R

internal object PreviewLayouts {

    const val CANVAS_WIDTH = 300f
    const val CANVAS_HEIGHT = 172f

    fun of(page: GlassPage): List<PreviewPiece> = when (page) {
        GlassPage.TILES -> tiles()
        GlassPage.FOOTER -> footer()
        GlassPage.BRIGHTNESS -> brightness()
        GlassPage.MEDIA -> listOf(piece(8, 16, 284, 140, round(28), tint = 0))
        GlassPage.NOTIFICATIONS -> notifications()
        GlassPage.HEADS_UP -> listOf(piece(8, 14, 284, 72, round(24), tint = 0))
        GlassPage.LOCKSCREEN_NOTIFICATIONS -> stack(16, 38, 268, 58)
        GlassPage.LOCKSCREEN_SHORTCUTS -> listOf(
            piece(18, 92, 64, 64, pill(), tint = 0),
            piece(218, 92, 64, 64, pill(), tint = 0)
        )
        GlassPage.FINGERPRINT -> listOf(piece(112, 48, 76, 76, pill(), tint = 0, icon = R.drawable.ic_preview_fingerprint))
        GlassPage.BOUNCER -> bouncer()
        GlassPage.VOLUME -> volume()
        GlassPage.POWER_MENU -> powerMenu()
        GlassPage.SWITCHES -> switches()
        GlassPage.VOLUME_PANEL -> volumePanel()
        GlassPage.DIALOGS -> dialogs()
        GlassPage.BUTTONS -> buttons()
        GlassPage.BACK_GESTURE -> listOf(
            piece(0, 38, 72, 96, corners(20, 48, 48, 20), tint = 0, anchor = ANCHOR_START),
            piece(228, 38, 72, 96, corners(48, 20, 20, 48), tint = 0, anchor = ANCHOR_END)
        )
        GlassPage.TOASTS -> listOf(piece(50, 108, 200, 48, pill(), tint = 0))
        GlassPage.STATUS_BAR_CHIPS -> listOf(
            piece(14, 12, 84, 30, pill(), tint = 0, color = RECORDING_RED),
            piece(228, 12, 58, 30, pill(), tint = 0, color = PRIVACY_GREEN)
        )
        GlassPage.SETTINGS_CARDS -> settingsCards()
        GlassPage.INSTALLER_DIALOGS -> listOf(piece(30, 14, 240, 144, round(28), tint = 0))
        GlassPage.KEYBOARD -> keyboard()
        else -> listOf(piece(40, 38, 220, 96, round(30), tint = 0))
    }

    private fun tiles(): List<PreviewPiece> = listOf(
        piece(6, 16, 140, 64, pill(), tint = 1, accent = true),
        piece(154, 16, 140, 64, pill(), tint = 0),
        piece(6, 92, 140, 64, pill(), tint = 0),
        piece(154, 92, 140, 64, pill(), tint = 1, accent = true)
    )

    private fun footer(): List<PreviewPiece> = listOf(
        piece(8, 58, 132, 56, pill(), tint = 0),
        piece(160, 58, 56, 56, pill(), tint = 0),
        piece(236, 58, 56, 56, pill(), tint = 1, accent = true)
    )

    private fun brightness(): List<PreviewPiece> = listOf(
        piece(10, 58, 280, 56, pill(), tint = 0),
        piece(10, 58, 172, 56, pill(), tint = 1, base = 0, accent = true)
    )

    private fun notifications(): List<PreviewPiece> = listOf(
        piece(8, 8, 284, 56, corners(22, 22, 6, 6), tint = 0),
        piece(8, 68, 284, 56, corners(6, 6, 22, 22), tint = 0),
        piece(8, 134, 36, 36, pill(), tint = 1),
        piece(58, 134, 184, 36, pill(), tint = 1),
        piece(256, 134, 36, 36, pill(), tint = 1)
    )

    private fun stack(x: Int, y: Int, width: Int, height: Int): List<PreviewPiece> = listOf(
        piece(x, y, width, height, corners(22, 22, 6, 6), tint = 0),
        piece(x, y + height + 4, width, height, corners(6, 6, 22, 22), tint = 0)
    )

    private fun bouncer(): List<PreviewPiece> = (0 until PIN_KEYS).map { index ->
        val column = index % PIN_COLUMNS
        val action = index >= PIN_KEYS - PIN_COLUMNS && column != 1
        piece(79 + column * 52, 1 + (index / PIN_COLUMNS) * 44, 38, 38, pill(), tint = if (action) 1 else 0, accent = action)
    }

    private fun volume(): List<PreviewPiece> = listOf(
        piece(226, 4, 64, 164, pill(), tint = 0),
        piece(238, 14, 40, 104, pill(), tint = 1, base = 0),
        piece(238, 66, 40, 52, pill(), tint = 2, base = 0, accent = true),
        piece(238, 124, 40, 40, pill(), tint = 1, base = 0)
    )

    private fun powerMenu(): List<PreviewPiece> = listOf(
        piece(55, 8, 190, 156, round(32), tint = 0),
        piece(75, 22, 60, 60, pill(), tint = 2, base = 0, accent = true),
        piece(165, 22, 60, 60, pill(), tint = 1, base = 0),
        piece(75, 94, 60, 60, pill(), tint = 1, base = 0),
        piece(165, 94, 60, 60, pill(), tint = 1, base = 0)
    )

    private fun switches(): List<PreviewPiece> = listOf(
        piece(90, 26, 120, 52, pill(), tint = 0),
        piece(96, 32, 56, 40, pill(), fill = Color.White),
        piece(90, 94, 120, 52, pill(), tint = 0, accent = true),
        piece(148, 100, 56, 40, pill(), fill = Color.White)
    )

    private fun volumePanel(): List<PreviewPiece> = listOf(
        piece(0, 22, 300, 150, corners(32, 32, 0, 0), tint = 0),
        piece(16, 40, 268, 40, pill(), tint = 3, base = 0),
        piece(16, 40, 170, 40, pill(), tint = 4, base = 0, accent = true),
        piece(16, 88, 268, 40, pill(), tint = 3, base = 0),
        piece(16, 88, 110, 40, pill(), tint = 4, base = 0, accent = true),
        piece(16, 136, 128, 32, pill(), tint = 1, base = 0),
        piece(156, 136, 128, 32, pill(), tint = 2, base = 0, accent = true)
    )

    private fun dialogs(): List<PreviewPiece> = listOf(
        piece(20, 8, 260, 156, round(28), tint = 0),
        piece(36, 24, 228, 48, round(18), tint = 3, base = 0, accent = true),
        piece(36, 120, 104, 32, pill(), tint = 1, base = 0),
        piece(160, 120, 104, 32, pill(), tint = 2, base = 0, accent = true)
    )

    private fun buttons(): List<PreviewPiece> = listOf(
        piece(16, 44, 128, 48, pill(), tint = 0, accent = true),
        piece(156, 44, 128, 48, pill(), tint = 0),
        piece(100, 106, 44, 44, pill(), tint = 0, accent = true),
        piece(156, 106, 44, 44, pill(), tint = 0)
    )

    private fun settingsCards(): List<PreviewPiece> = listOf(
        piece(16, 8, 268, 44, pill(), tint = 1, accent = true),
        piece(16, 60, 268, 50, corners(24, 24, 6, 6), tint = 0),
        piece(16, 113, 268, 50, corners(6, 6, 24, 24), tint = 0),
        piece(30, 70, 30, 30, pill(), tint = 2, base = 0),
        piece(30, 123, 30, 30, pill(), tint = 2, base = 0)
    )

    private fun keyboard(): List<PreviewPiece> {
        val keys = (0 until KEY_ROWS * KEYS_PER_ROW).map { index ->
            piece(13 + (index % KEYS_PER_ROW) * 40, 52 + (index / KEYS_PER_ROW) * 36, 34, 30, round(8), tint = 1, base = 0)
        }
        return listOf(piece(0, 40, 300, 132, corners(24, 24, 0, 0), tint = 0)) + keys + listOf(
            piece(13, 124, 52, 30, round(8), tint = 2, base = 0, accent = true),
            piece(71, 124, 158, 30, round(8), tint = 1, base = 0),
            piece(235, 124, 52, 30, round(8), tint = 2, base = 0, accent = true)
        )
    }

    private fun piece(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        radii: List<Float>,
        tint: Int = NO_TINT,
        base: Int = NO_TINT,
        fill: Color? = null,
        accent: Boolean = false,
        color: Color? = null,
        anchor: Int = ANCHOR_NONE,
        icon: Int? = null
    ): PreviewPiece = PreviewPiece(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), radii, tint, base, fill, accent, color, anchor, icon = icon)

    private fun pill(): List<Float> = listOf(PILL, PILL, PILL, PILL)

    private fun round(radius: Int): List<Float> = List(CORNERS) { radius.toFloat() }

    private fun corners(topStart: Int, topEnd: Int, bottomEnd: Int, bottomStart: Int): List<Float> =
        listOf(topStart.toFloat(), topEnd.toFloat(), bottomEnd.toFloat(), bottomStart.toFloat())

    private const val CORNERS = 4
    private val RECORDING_RED = Color(0xFFE5483D)
    private val PRIVACY_GREEN = Color(0xFF34A853)
    private const val PIN_KEYS = 12
    private const val PIN_COLUMNS = 3
    private const val KEY_ROWS = 2
    private const val KEYS_PER_ROW = 7
}
