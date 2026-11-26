package com.drdisagree.glasslayer.xposed.systemui

import android.view.View
import android.view.ViewGroup
import android.widget.Space
import android.widget.TextClock
import android.widget.TextView

internal object ClockViews {

    private const val MAX_TIME_TEXT = 8
    private const val TIME_SEPARATORS = ":：∶•·∙⋅●○◉."
    private const val TIME_FORMAT_CHARS = "hHkKms"
    private const val QUOTE = "'"
    private const val SEPARATOR = '|'
    private const val END = ';'
    private val QUOTED_TEXT = Regex("'(?:[^']|'')*'")
    private val CLOCK_ID_KEYWORDS = listOf("clock", "hour", "minute", "time", "colon", "dot", "sep", "divider")
    private val IGNORE_ID_KEYWORDS = listOf(
        "weather", "battery", "charging", "alarm", "date", "day", "month", "year",
        "media", "music", "notification", "status", "avatar", "wallpaper",
        "background", "backdrop", "shadow"
    )

    fun signature(view: View): String = StringBuilder().also { append(view, it) }.toString()

    private fun append(view: View, out: StringBuilder) {
        out.append(view.width).append(SEPARATOR).append(view.height).append(SEPARATOR)
        if (view is TextView) {
            val paint = view.paint
            out.append(view.text).append(SEPARATOR).append(paint.textSize).append(SEPARATOR)
                .append(paint.typeface?.hashCode()).append(SEPARATOR).append(paint.fontVariationSettings).append(SEPARATOR)
                .append(paint.letterSpacing).append(SEPARATOR).append(paint.textScaleX).append(SEPARATOR)
                .append(view.currentTextColor)
        }
        out.append(END)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) append(view.getChildAt(index), out)
        }
    }

    fun collect(view: View, out: MutableList<View>, inNonClockGroup: Boolean = false) {
        if (view is Space) return
        val name = entryName(view)
        if (name != null && IGNORE_ID_KEYWORDS.any { it in name }) return
        val named = name != null && CLOCK_ID_KEYWORDS.any { it in name }
        when (view) {
            is TextClock -> if (showsTime(view) || showsSeparator(view) || named) out.add(view)
            is TextView -> if (isSeparator(view.text) || isDigits(view.text) || named) out.add(view)
            is ViewGroup -> {
                val childNonClock = inNonClockGroup || hasNonClockText(view)
                for (index in 0 until view.childCount) collect(view.getChildAt(index), out, childNonClock)
            }
            else -> if (!inNonClockGroup || named) out.add(view)
        }
    }

    private fun entryName(view: View): String? {
        val id = view.id
        if (id == View.NO_ID || id ushr 24 == 0) return null
        return runCatching { view.resources.getResourceEntryName(id).lowercase() }.getOrNull()
    }

    private fun hasNonClockText(group: ViewGroup): Boolean {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child is TextView && child !is TextClock && !isSeparator(child.text) && !isDigits(child.text)) {
                val name = entryName(child)
                if (name == null || CLOCK_ID_KEYWORDS.none { it in name }) return true
            }
        }
        return false
    }

    private fun showsTime(view: TextClock): Boolean =
        listOfNotNull(view.format12Hour, view.format24Hour).any { format ->
            format.replace(QUOTED_TEXT, "").any { it in TIME_FORMAT_CHARS }
        }

    private fun showsSeparator(view: TextClock): Boolean =
        isSeparator(view.text) || listOfNotNull(view.format12Hour, view.format24Hour).any { isSeparator(it.toString().replace(QUOTE, "")) }

    private fun isDigits(text: CharSequence?): Boolean {
        val value = text?.toString()?.trim() ?: return false
        if (value.isEmpty() || value.length > MAX_TIME_TEXT) return false
        return value.any { it.isDigit() } && value.all { it.isDigit() || it in TIME_SEPARATORS || it.isWhitespace() }
    }

    private fun isSeparator(text: CharSequence?): Boolean {
        val value = text?.toString()?.trim() ?: return false
        if (value.isEmpty() || value.length > MAX_TIME_TEXT) return false
        return value.all { it in TIME_SEPARATORS || it.isWhitespace() } && value.any { it in TIME_SEPARATORS }
    }
}
