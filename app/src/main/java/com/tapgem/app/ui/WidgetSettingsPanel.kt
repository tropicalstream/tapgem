package com.tapgem.app.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tapgem.app.core.model.Theme
import com.tapgem.app.core.model.Widget
import com.tapgem.app.core.model.WidgetType
import com.tapgem.app.core.model.WorldClocks

/**
 * A window's settings sheet (the ⚙ in its title bar, or "open the clock
 * settings"): rows of tappable chips, nothing to type. Clocks get style,
 * 12/24 h, seconds, date and world-clock cities; live cards and tickers their
 * refresh rate; every window stay-on-top, opacity and text size. Each tap is
 * applied at once through [onEdit] and the sheet re-reads the widget.
 */
class WidgetSettingsPanel(context: Context) : FrameLayout(context) {

    /** Apply a change to the widget being edited (id, transform). */
    var onEdit: ((String, (Widget) -> Widget) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    var widgetId: String? = null; private set
    private var accent = 0xFF64D2FF.toInt()
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val header = TextView(context)

    init {
        elevation = 11f
        background = GradientDrawable().apply { cornerRadius = 12f; setColor(0xF00E141B.toInt()); setStroke(1, 0x33FFFFFF) }
        setPadding(12, 8, 12, 10)
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.apply { setTextColor(0xFFE0F4FF.toInt()); textSize = 12.5f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        top.addView(header, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(context).apply {
            text = "✕"; setTextColor(0xFFB7CEDE.toInt()); textSize = 12f; gravity = Gravity.CENTER
            isClickable = true; isFocusable = true; contentDescription = "Close settings"
            setOnClickListener { onClose?.invoke() }
        }, LinearLayout.LayoutParams(26, 26))
        column.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(column)
        visibility = GONE
    }

    /** (Re)build the sheet for [w]. */
    fun show(w: Widget, theme: Theme) {
        widgetId = w.id
        accent = theme.accent
        header.text = "⚙  ${w.title}"
        while (column.childCount > 1) column.removeViewAt(1)
        when (w.type) {
            WidgetType.CLOCK -> clockRows(w)
            WidgetType.MAP -> if (w.state["nav"] == "on") navRows(w)
            WidgetType.LIVE, WidgetType.TICKER -> refreshRow(w)
            else -> {}
        }
        commonRows(w)
        visibility = VISIBLE
    }

    // ── rows ──────────────────────────────────────────────────────

    private fun clockRows(w: Widget) {
        val style = ClockFaceView.parseStyle(w.state["style"]) ?: ClockFaceView.STYLE_DIGITAL
        row("Style", listOf("Digital" to ClockFaceView.STYLE_DIGITAL, "Thin" to ClockFaceView.STYLE_THIN, "LED" to ClockFaceView.STYLE_LED,
            "Analog" to ClockFaceView.STYLE_ANALOG, "Modern" to ClockFaceView.STYLE_MODERN), wrap = true,
            selected = { key -> key == style },
            tap = { key -> edit { it.withState("style" to key) } })
        val h24 = when (w.state["hours"]) { "24" -> true; "12" -> false; else -> android.text.format.DateFormat.is24HourFormat(context) }
        val src = w.source.lowercase()
        val seconds = w.state["seconds"]?.let { it == "true" } ?: src.contains("seconds")
        val date = w.state["date"]?.let { it == "true" } ?: (src.contains("date") || src.isBlank())
        row("Time", listOf("12 h" to "12", "24 h" to "24", "Seconds" to "seconds", "Date" to "date"),
            selected = { key -> when (key) { "12" -> !h24; "24" -> h24; "seconds" -> seconds; else -> date } },
            tap = { key ->
                when (key) {
                    "12", "24" -> edit { it.withState("hours" to key) }
                    "seconds" -> edit { it.withState("seconds" to (!seconds).toString()) }
                    else -> edit { it.withState("date" to (!date).toString()) }
                }
            })
        val zones = (w.state["zones"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("local")).map { if (it.equals("local", true)) "local" else it }
        row("Cities", WorldClocks.POPULAR.map { (name, id) -> name to (if (id.isBlank()) "local" else id) }, wrap = true,
            selected = { key -> key in zones },
            tap = { key ->
                val next = if (key in zones) zones.filter { it != key }.ifEmpty { listOf("local") } else (zones + key).take(6)
                edit { it.withState("zones" to next.joinToString(",")) }
            })
    }

    /** Turn-by-turn windows: the compact HUD or the full map, its theme, and which way the minimap points. */
    private fun navRows(w: Widget) {
        val hud = w.state["view"] == "hud"
        row("View", listOf("Minimap HUD" to "hud", "Full map" to "map"),
            selected = { key -> (key == "hud") == hud },
            tap = { key -> com.tapgem.app.core.tools.WidgetOps.rememberHud(context, key == "hud", null); edit { it.withState("view" to (if (key == "hud") "hud" else "")) } })
        if (!hud) return
        val theme = w.state["theme"].orEmpty()
        row("Theme", listOf("Auto" to "") + com.tapgem.app.core.tools.WidgetOps.HUD_THEMES.map { (k, label) -> label to k }, wrap = true,
            selected = { key -> key == theme },
            tap = { key -> com.tapgem.app.core.tools.WidgetOps.rememberHud(context, null, key); edit { it.withState("theme" to key) } })
        val orient = w.state["orient"].orEmpty()
        row("Map", listOf("Auto" to "", "Heading up" to "heading", "Course up" to "course", "North up" to "north"), wrap = true,
            selected = { key -> key == orient },
            tap = { key -> edit { it.withState("orient" to key) } })
        val metric = w.state["units"]?.let { it == "metric" } ?: !com.tapgem.app.core.network.Router.usUnits()
        val zoom = w.state["mzoom"].orEmpty()
        val zoomLabels = if (metric) listOf("60 m", "120 m", "250 m", "500 m", "1 km") else listOf("200 ft", "400 ft", "0.15 mi", "0.3 mi", "0.6 mi")
        row("Zoom", listOf("Auto" to "") + com.tapgem.app.core.tools.WidgetOps.HUD_ZOOMS.zip(zoomLabels) { k, l -> l to k }, wrap = true,
            selected = { key -> key == zoom },
            tap = { key -> edit { it.withState("mzoom" to key, "mzoomCmd" to "") } })
        val arrow = w.state["arrow"].orEmpty()
        row("Arrow", listOf("Small" to "small", "Normal" to "", "Large" to "large"),
            selected = { key -> key == arrow },
            tap = { key -> edit { it.withState("arrow" to key) } })
        row("Units", listOf("ft / mi" to "us", "m / km" to "metric"),
            selected = { key -> (key == "metric") == metric },
            tap = { key -> edit { it.withState("units" to key) } })
    }

    private fun refreshRow(w: Widget) {
        row("Refresh", listOf("1 min" to "60", "5 min" to "300", "15 min" to "900", "1 h" to "3600", "Off" to "0"),
            selected = { key -> key == w.refreshSec.toString() },
            tap = { key -> edit { it.copy(refreshSec = key.toInt()) } })
    }

    /**
     * The frame itself: keep it, drop it, or let it follow the cursor. Auto is the HUD-friendly one —
     * bare content until you reach for the window, which is when you need the bar and the grip.
     */
    private fun frameRow(w: Widget) {
        val mode = if (w.style.chromeAuto) "auto" else if (w.style.chrome == false) "off" else "on"
        row("Frame", listOf("Show" to "on", "On hover" to "auto", "Hide" to "off"),
            selected = { key -> key == mode },
            tap = { key ->
                edit { it.copy(style = it.style.copy(chrome = key != "off", chromeAuto = key == "auto")) }
            })
    }

    private fun commonRows(w: Widget) {
        frameRow(w)
        val textual = w.type == WidgetType.TEXT || w.type == WidgetType.LIVE || w.type == WidgetType.TICKER || w.type == WidgetType.CLOCK
        val opacityPct = (w.style.opacity * 100).toInt()
        val items = ArrayList<Pair<String, String>>()
        items += "Stay on top" to "ontop"
        items += "Opacity −" to "op-"; items += "$opacityPct%" to "op"; items += "Opacity +" to "op+"
        if (textual) { items += "Text −" to "fs-"; items += "Text +" to "fs+" }
        row("Window", items, wrap = true,
            selected = { key -> key == "ontop" && w.onTop },
            tap = { key ->
                when (key) {
                    "ontop" -> edit { it.copy(onTop = !it.onTop) }
                    "op-" -> edit { it.copy(style = it.style.copy(opacity = (it.style.opacity - 0.1f).coerceIn(0.2f, 1f))) }
                    "op+" -> edit { it.copy(style = it.style.copy(opacity = (it.style.opacity + 0.1f).coerceIn(0.2f, 1f))) }
                    "fs-" -> edit { it.copy(style = it.style.copy(fontSize = ((it.style.fontSize ?: 14f) - 2f).coerceIn(8f, 40f))) }
                    "fs+" -> edit { it.copy(style = it.style.copy(fontSize = ((it.style.fontSize ?: 14f) + 2f).coerceIn(8f, 40f))) }
                }
            })
    }

    private fun edit(t: (Widget) -> Widget) { widgetId?.let { id -> onEdit?.invoke(id, t) } }

    /** A labelled row of chips; [selected] decides the highlight, [tap] handles a chip. */
    private fun row(label: String, items: List<Pair<String, String>>, wrap: Boolean = false, selected: (String) -> Boolean, tap: (String) -> Unit) {
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(TextView(context).apply { text = label; setTextColor(0xFF8FA6B8.toInt()); textSize = 10.5f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) },
            LinearLayout.LayoutParams(52, LinearLayout.LayoutParams.WRAP_CONTENT))
        val chips = if (wrap) FlowRow(context) else LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        items.forEach { (name, key) ->
            val on = selected(key)
            val inert = key == "op"
            val chip = TextView(context).apply {
                text = name; textSize = 10.5f; gravity = Gravity.CENTER; setPadding(9, 4, 9, 4); maxLines = 1
                setTextColor(if (on) 0xFF0B1016.toInt() else 0xFFE0F4FF.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = GradientDrawable().apply { cornerRadius = 10f; if (on) setColor(accent) else { setColor(0xFF1B2530.toInt()); setStroke(1, if (inert) 0x00000000 else 0x55FFFFFF) } }
                if (!inert) { isClickable = true; isFocusable = true; contentDescription = "$label: $name"; setOnClickListener { tap(key) } }
            }
            val wc = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            val lp = if (chips is FlowRow) FlowRow.LayoutParams(wc, wc) else LinearLayout.LayoutParams(wc, wc)
            (lp as android.view.ViewGroup.MarginLayoutParams).setMargins(0, 2, 4, 2)
            chips.addView(chip, lp)
        }
        line.addView(chips, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 5 })
    }

    /** Chips that wrap onto new lines (the city list). */
    class FlowRow(context: Context) : android.view.ViewGroup(context) {
        class LayoutParams(w: Int, h: Int) : MarginLayoutParams(w, h)
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val maxW = MeasureSpec.getSize(widthSpec)
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i); measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), heightSpec)
                val lp = c.layoutParams as MarginLayoutParams
                val cw = c.measuredWidth + lp.leftMargin + lp.rightMargin; val ch = c.measuredHeight + lp.topMargin + lp.bottomMargin
                if (x + cw > maxW && x > 0) { x = 0; y += rowH; rowH = 0 }
                x += cw; rowH = maxOf(rowH, ch)
            }
            setMeasuredDimension(maxW, y + rowH)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxW = r - l
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i); val lp = c.layoutParams as MarginLayoutParams
                val cw = c.measuredWidth + lp.leftMargin + lp.rightMargin; val ch = c.measuredHeight + lp.topMargin + lp.bottomMargin
                if (x + cw > maxW && x > 0) { x = 0; y += rowH; rowH = 0 }
                c.layout(x + lp.leftMargin, y + lp.topMargin, x + lp.leftMargin + c.measuredWidth, y + lp.topMargin + c.measuredHeight)
                x += cw; rowH = maxOf(rowH, ch)
            }
        }
        override fun generateLayoutParams(attrs: android.util.AttributeSet?) = LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        override fun generateDefaultLayoutParams() = LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        override fun checkLayoutParams(p: android.view.ViewGroup.LayoutParams?) = p is MarginLayoutParams
    }
}
