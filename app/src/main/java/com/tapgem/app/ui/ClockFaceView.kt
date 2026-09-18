package com.tapgem.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.tapgem.app.core.model.Widget
import com.tapgem.app.core.model.WorldClocks
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The clock widget's face: five styles, 12/24 h, optional seconds and date,
 * and one or several time zones (a world clock). Everything is drawn on a
 * Canvas so a style change is instant and a repaint costs nothing on battery.
 *
 * Styles: digital (bold), thin (light hairline digits), led (seven-segment),
 * analog (classic dial with numerals), modern (minimal dial). With several
 * zones the digital styles list city rows; the analog styles draw a dial per
 * city.
 */
class ClockFaceView(context: Context) : View(context) {

    class Config(
        val style: String = STYLE_DIGITAL,
        val hours24: Boolean = false,
        val seconds: Boolean = false,
        val date: Boolean = true,
        val zones: List<String> = listOf(""),   // "" = local
        val textColor: Int = Color.WHITE,
        val accent: Int = 0xFF64D2FF.toInt(),
        val fontScale: Float = 1f
    ) {
        val analog: Boolean get() = style == STYLE_ANALOG || style == STYLE_MODERN
    }

    var config = Config(); private set
    var ecoMode = false
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val cal = Calendar.getInstance()

    fun setConfig(c: Config) { config = c; invalidate() }

    /** Repaint interval this configuration needs (1 s with seconds/second hand, else once a minute, aligned). */
    fun tickMs(): Long = if (config.seconds && !ecoMode) 1_000L else 60_000L - (System.currentTimeMillis() % 60_000L) + 50L

    override fun onDraw(canvas: Canvas) {
        val c = config
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val now = System.currentTimeMillis()
        if (c.analog) drawDials(canvas, w, h, now) else drawDigital(canvas, w, h, now)
    }

    // ── digital ───────────────────────────────────────────────────

    private fun timeParts(zone: String, now: Long): Triple<String, String, String> {
        cal.timeZone = WorldClocks.timeZone(zone); cal.timeInMillis = now
        val h = cal.get(Calendar.HOUR_OF_DAY); val m = cal.get(Calendar.MINUTE); val s = cal.get(Calendar.SECOND)
        val hh = if (config.hours24) h else ((h + 11) % 12 + 1)
        val t = (if (config.hours24) hh.toString().padStart(2, '0') else hh.toString()) + ":" + m.toString().padStart(2, '0') + if (config.seconds) ":" + s.toString().padStart(2, '0') else ""
        val ampm = if (config.hours24) "" else if (h < 12) "AM" else "PM"
        val date = String.format(Locale.US, "%s, %s %d", DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1], MONTHS[cal.get(Calendar.MONTH)], cal.get(Calendar.DAY_OF_MONTH))
        return Triple(t, ampm, date)
    }

    private fun drawDigital(canvas: Canvas, w: Float, h: Float, now: Long) {
        val c = config
        if (c.zones.size <= 1) {
            val (t, ampm, date) = timeParts(c.zones.firstOrNull() ?: "", now)
            val dateH = if (c.date) 0.22f else 0f
            val timeBoxH = h * (1f - dateH)
            when (c.style) {
                STYLE_LED -> drawSegments(canvas, t, w / 2f, timeBoxH / 2f + 2f, min(w * 0.9f, timeBoxH * 1.9f), c.accent, ampm)
                else -> {
                    text.color = c.textColor
                    text.typeface = if (c.style == STYLE_THIN) Typeface.create("sans-serif-thin", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.BOLD)
                    val size = fitText(t + if (ampm.isNotEmpty()) "  " else "", w * 0.92f, timeBoxH * 0.8f) * c.fontScale.coerceIn(0.6f, 1.3f)
                    text.textSize = size
                    val baseline = timeBoxH / 2f + size * 0.36f
                    val tw = text.measureText(t)
                    val ampmW = if (ampm.isNotEmpty()) size * 0.42f else 0f
                    val timeCx = (w - ampmW) / 2f
                    canvas.drawText(t, timeCx, baseline, text)
                    if (ampm.isNotEmpty()) {
                        text.textSize = size * 0.32f; text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); text.color = c.accent
                        text.textAlign = Paint.Align.LEFT
                        canvas.drawText(ampm, timeCx + tw / 2f + size * 0.08f, baseline, text)
                        text.textAlign = Paint.Align.CENTER
                    }
                }
            }
            if (c.date) {
                text.textSize = (h * dateH * 0.55f).coerceIn(9f, 16f); text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                text.color = if (c.style == STYLE_LED) c.accent else dim(c.textColor, 0.7f)
                val label = if (c.zones.firstOrNull().isNullOrBlank()) date else WorldClocks.label(c.zones.first()) + " · " + date
                canvas.drawText(label, w / 2f, h - h * dateH * 0.35f, text)
            }
            return
        }
        // World clock: one row per city — name left, time right, offset small.
        val rows = c.zones.take(6)
        val rowH = h / rows.size
        val timeSize = (rowH * 0.55f).coerceIn(12f, 34f)
        rows.forEachIndexed { i, zone ->
            val (t, ampm, _) = timeParts(zone, now)
            val cy = rowH * i + rowH / 2f
            text.textAlign = Paint.Align.LEFT; text.color = c.textColor
            text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); text.textSize = (rowH * 0.36f).coerceIn(11f, 18f)
            canvas.drawText(WorldClocks.label(zone), 10f, cy - rowH * 0.02f, text)
            text.textSize = (rowH * 0.24f).coerceIn(8f, 12f); text.color = dim(c.textColor, 0.55f)
            canvas.drawText(WorldClocks.offsetLabel(zone, now), 10f, cy + rowH * 0.3f, text)
            text.textAlign = Paint.Align.RIGHT
            if (c.style == STYLE_LED) {
                drawSegments(canvas, t, w - 10f - segWidth(t, timeSize * 0.9f) / 2f, cy, segWidth(t, timeSize * 0.9f), c.accent, ampm)
            } else {
                text.typeface = if (c.style == STYLE_THIN) Typeface.create("sans-serif-thin", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.BOLD)
                text.textSize = timeSize; text.color = c.textColor
                val ampmW = if (ampm.isNotEmpty()) timeSize * 0.6f else 0f
                canvas.drawText(t, w - 10f - ampmW, cy + timeSize * 0.36f, text)
                if (ampm.isNotEmpty()) { text.textSize = timeSize * 0.4f; text.color = c.accent; canvas.drawText(ampm, w - 10f, cy + timeSize * 0.36f, text) }
            }
            text.textAlign = Paint.Align.CENTER
            if (i < rows.size - 1) { paint.color = dim(c.textColor, 0.12f); paint.strokeWidth = 1f; canvas.drawLine(8f, rowH * (i + 1), w - 8f, rowH * (i + 1), paint) }
        }
    }

    private fun fitText(s: String, maxW: Float, maxH: Float): Float {
        text.textSize = 100f
        val wAt100 = text.measureText(s)
        return min(100f * maxW / wAt100, maxH)
    }

    // ── seven-segment ─────────────────────────────────────────────

    private fun segWidth(s: String, digitH: Float): Float {
        val dw = digitH * 0.55f; val colon = digitH * 0.25f; val gap = digitH * 0.14f
        return s.sumOf { ch -> (if (ch == ':') colon else dw + gap).toDouble() }.toFloat()
    }

    /** Draws [s] (digits and colons) centred at (cx, cy) with total width ≈ [totalW], LED style with dim "off" segments. */
    private fun drawSegments(canvas: Canvas, s: String, cx: Float, cy: Float, totalW: Float, color: Int, ampm: String) {
        val nDigits = s.count { it != ':' }; val nColons = s.count { it == ':' }
        // digit width = 0.55 H, gap 0.14 H, colon 0.25 H → solve H from total width
        val unitsW = nDigits * 0.69f + nColons * 0.25f
        var digitH = totalW / unitsW
        digitH = min(digitH, height * 0.62f)
        val dw = digitH * 0.55f; val gap = digitH * 0.14f; val colonW = digitH * 0.25f; val th = digitH * 0.11f
        var x = cx - (nDigits * (dw + gap) + nColons * colonW - gap) / 2f
        val top = cy - digitH / 2f
        val on = color; val off = (color and 0x00FFFFFF) or 0x22000000
        for (ch in s) {
            if (ch == ':') {
                paint.color = on
                canvas.drawCircle(x + colonW / 2f, top + digitH * 0.3f, th * 0.55f, paint)
                canvas.drawCircle(x + colonW / 2f, top + digitH * 0.7f, th * 0.55f, paint)
                x += colonW; continue
            }
            val mask = SEG[ch - '0']
            for (i in 0 until 7) {
                paint.color = if (mask and (1 shl i) != 0) on else off
                segPath(i, x, top, dw, digitH, th)?.let { canvas.drawPath(it, paint) }
            }
            x += dw + gap
        }
        if (ampm.isNotEmpty()) {
            text.textAlign = Paint.Align.LEFT; text.color = on; text.textSize = digitH * 0.22f; text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            canvas.drawText(ampm, x - gap + digitH * 0.08f, top + digitH, text); text.textAlign = Paint.Align.CENTER
        }
    }

    private val segPath = Path()
    /** Segment i of a digit box (a b c d e f g = top, top-right, bottom-right, bottom, bottom-left, top-left, middle). */
    private fun segPath(i: Int, x: Float, y: Float, w: Float, h: Float, t: Float): Path? {
        val p = segPath; p.reset()
        val half = t / 2f; val mid = y + h / 2f
        fun hSeg(cy: Float) { p.moveTo(x + t, cy - half); p.lineTo(x + w - t, cy - half); p.lineTo(x + w - t + half, cy); p.lineTo(x + w - t, cy + half); p.lineTo(x + t, cy + half); p.lineTo(x + t - half, cy); p.close() }
        fun vSeg(cx: Float, y0: Float, y1: Float) { p.moveTo(cx - half, y0 + t); p.lineTo(cx, y0 + t - half); p.lineTo(cx + half, y0 + t); p.lineTo(cx + half, y1 - t); p.lineTo(cx, y1 - t + half); p.lineTo(cx - half, y1 - t); p.close() }
        when (i) {
            0 -> hSeg(y + half)                       // a
            1 -> vSeg(x + w - half, y, mid)           // b
            2 -> vSeg(x + w - half, mid, y + h)       // c
            3 -> hSeg(y + h - half)                   // d
            4 -> vSeg(x + half, mid, y + h)           // e
            5 -> vSeg(x + half, y, mid)               // f
            6 -> hSeg(mid)                            // g
            else -> return null
        }
        return p
    }

    // ── analog ────────────────────────────────────────────────────

    private fun drawDials(canvas: Canvas, w: Float, h: Float, now: Long) {
        val c = config
        val zones = c.zones.take(4)
        val n = zones.size
        val cols = if (n <= 1) 1 else if (n == 2) 2 else if (w >= h * 1.6f) n else 2
        val rowsN = (n + cols - 1) / cols
        val cellW = w / cols; val cellH = h / rowsN
        zones.forEachIndexed { i, zone ->
            val cx = cellW * (i % cols) + cellW / 2f; val cy = cellH * (i / cols) + cellH / 2f
            val labelH = if (n > 1 || c.date) min(cellH * 0.16f, 16f) else 0f
            val r = min(cellW, cellH - labelH) / 2f - 6f
            drawDial(canvas, cx, cy - labelH / 2f, r, zone, now, n > 1)
            if (labelH > 0f) {
                text.textSize = (labelH * 0.8f).coerceIn(9f, 14f); text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); text.color = dim(c.textColor, 0.75f)
                val (_, _, date) = timeParts(zone, now)
                val label = if (n > 1) WorldClocks.label(zone) + (if (c.date && n <= 2 && cellW >= 150f) " · $date" else "") else date
                canvas.drawText(label, cx, cy + r + labelH * 0.3f + (if (n > 1) 2f else 4f), text)
            }
        }
    }

    private fun drawDial(canvas: Canvas, cx: Float, cy: Float, r: Float, zone: String, now: Long, small: Boolean) {
        val c = config
        cal.timeZone = WorldClocks.timeZone(zone); cal.timeInMillis = now
        val hr = cal.get(Calendar.HOUR); val mn = cal.get(Calendar.MINUTE); val sc = cal.get(Calendar.SECOND)
        val modern = c.style == STYLE_MODERN
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        // Face
        paint.color = dim(c.textColor, if (modern) 0.35f else 0.8f); paint.strokeWidth = if (modern) 1.5f else 2.5f
        canvas.drawCircle(cx, cy, r, paint)
        if (!modern) { paint.style = Paint.Style.FILL; paint.color = dim(c.textColor, 0.07f); canvas.drawCircle(cx, cy, r - 1f, paint); paint.style = Paint.Style.STROKE }
        // Ticks / numerals
        for (i in 0 until 60) {
            val major = i % 5 == 0
            if (modern && !major) continue
            if (modern && i % 15 != 0) { paint.style = Paint.Style.FILL; paint.color = dim(c.textColor, 0.5f); val a = Math.toRadians(i * 6.0 - 90); canvas.drawCircle(cx + (r * 0.86f) * cos(a).toFloat(), cy + (r * 0.86f) * sin(a).toFloat(), r * 0.025f, paint); paint.style = Paint.Style.STROKE; continue }
            val a = Math.toRadians(i * 6.0 - 90)
            val len = if (major) r * (if (modern) 0.14f else 0.12f) else r * 0.05f
            paint.color = if (major) dim(c.textColor, 0.9f) else dim(c.textColor, 0.4f)
            paint.strokeWidth = if (major) (if (modern) 2.5f else 2f) else 1f
            canvas.drawLine(cx + (r - len) * cos(a).toFloat(), cy + (r - len) * sin(a).toFloat(), cx + (r - 2f) * cos(a).toFloat(), cy + (r - 2f) * sin(a).toFloat(), paint)
        }
        if (!modern && !small) {
            text.color = dim(c.textColor, 0.9f); text.textSize = (r * 0.22f).coerceIn(9f, 20f); text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            for ((num, ang) in listOf(12 to -90.0, 3 to 0.0, 6 to 90.0, 9 to 180.0)) {
                val a = Math.toRadians(ang); canvas.drawText(num.toString(), cx + r * 0.72f * cos(a).toFloat(), cy + r * 0.72f * sin(a).toFloat() + text.textSize * 0.35f, text)
            }
        }
        // Hands
        val hourA = Math.toRadians((hr % 12 + mn / 60.0) * 30.0 - 90)
        val minA = Math.toRadians((mn + sc / 60.0) * 6.0 - 90)
        paint.style = Paint.Style.STROKE
        paint.color = c.textColor; paint.strokeWidth = if (modern) r * 0.05f else r * 0.07f
        canvas.drawLine(cx, cy, cx + r * 0.52f * cos(hourA).toFloat(), cy + r * 0.52f * sin(hourA).toFloat(), paint)
        paint.strokeWidth = if (modern) r * 0.035f else r * 0.05f
        canvas.drawLine(cx, cy, cx + r * 0.78f * cos(minA).toFloat(), cy + r * 0.78f * sin(minA).toFloat(), paint)
        if (c.seconds && !ecoMode) {
            val secA = Math.toRadians(sc * 6.0 - 90)
            paint.color = c.accent; paint.strokeWidth = r * 0.02f
            canvas.drawLine(cx - r * 0.15f * cos(secA).toFloat(), cy - r * 0.15f * sin(secA).toFloat(), cx + r * 0.85f * cos(secA).toFloat(), cy + r * 0.85f * sin(secA).toFloat(), paint)
        }
        paint.style = Paint.Style.FILL; paint.color = c.accent
        canvas.drawCircle(cx, cy, if (modern) r * 0.04f else r * 0.06f, paint)
    }

    private fun dim(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    companion object {
        const val STYLE_DIGITAL = "digital"
        const val STYLE_THIN = "thin"
        const val STYLE_LED = "led"
        const val STYLE_ANALOG = "analog"
        const val STYLE_MODERN = "modern"
        val STYLES = listOf(STYLE_DIGITAL, STYLE_THIN, STYLE_LED, STYLE_ANALOG, STYLE_MODERN)
        /** Spoken / typed names → style. */
        fun parseStyle(s: String?): String? = when (s?.trim()?.lowercase(Locale.US)?.replace(Regex("[^a-z0-9]"), "")) {
            null, "" -> null
            "digital", "bold", "classic", "default", "big", "standard" -> STYLE_DIGITAL
            "thin", "light", "minimal", "minimalist", "hairline", "elegant", "slim" -> STYLE_THIN
            "led", "segment", "sevensegment", "7segment", "retro", "alarm", "alarmclock", "flip", "nixie" -> STYLE_LED
            "analog", "analogue", "round", "dial", "classicanalog", "traditional", "wall", "wallclock" -> STYLE_ANALOG
            "modern", "modernanalog", "minimalanalog", "swiss", "bauhaus", "clean" -> STYLE_MODERN
            else -> null
        }
        private val SEG = intArrayOf(0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F)
        private val DAYS = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        /** Read a clock widget's configuration from its state (falling back to the legacy `source` format string). */
        fun configOf(w: Widget, textColor: Int, accent: Int, fontScale: Float, default24: Boolean): Config {
            val st = w.state
            val src = w.source.lowercase(Locale.US)
            val raw = st["zones"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val zones = raw.map { if (it.equals("local", ignoreCase = true)) "" else it }.ifEmpty { listOf("") }
            return Config(
                style = parseStyle(st["style"]) ?: STYLE_DIGITAL,
                hours24 = when (st["hours"]) { "24" -> true; "12" -> false; else -> default24 },
                seconds = st["seconds"]?.let { it == "true" } ?: src.contains("seconds"),
                date = st["date"]?.let { it == "true" } ?: (src.contains("date") || src.isBlank()),
                zones = zones.distinct(),
                textColor = textColor, accent = accent, fontScale = fontScale
            )
        }
    }
}
