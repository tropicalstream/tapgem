package com.tapgem.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.sin

/**
 * Procedural panel textures for themes that want more than a flat colour.
 * Generated once per process into a small tile and drawn with a repeating
 * shader — no image assets, no per-frame cost. All textures are dark: a
 * bright panel on the waveguide is what drains (and reboots) the glasses.
 */
object PanelTextures {

    private val cache = HashMap<String, Bitmap>()

    /** A tile for [name] ("wood"), or null for flat themes. */
    @Synchronized
    fun tile(name: String?): Bitmap? {
        if (name.isNullOrBlank()) return null
        cache[name]?.let { return it }
        val bmp = when (name) { "wood" -> wood(); else -> null } ?: return null
        cache[name] = bmp
        return bmp
    }

    /**
     * Dark walnut: long-grain streaks along x with slow wandering, a few
     * tighter rings, and fine noise. 256×256, tileable in both directions.
     */
    private fun wood(): Bitmap {
        val n = 256
        val px = IntArray(n * n)
        val base = floatArrayOf(0x2B / 255f, 0x1A / 255f, 0x10 / 255f)     // #2B1A10
        val light = floatArrayOf(0x4A / 255f, 0x2E / 255f, 0x1A / 255f)    // #4A2E1A
        val dark = floatArrayOf(0x17 / 255f, 0x0D / 255f, 0x07 / 255f)     // #170D07
        var seed = 0x9E3779B9.toInt()
        fun rnd(): Float { seed = seed * 1664525 + 1013904223; return ((seed ushr 8) and 0xFFFF) / 65535f }
        val phase = FloatArray(n) { rnd() * 6.283f }
        for (y in 0 until n) {
            val ty = y * 6.283f / n
            for (x in 0 until n) {
                val tx = x * 6.283f / n
                // Grain runs left→right: bands vary slowly with y and wander with x (both periodic → seamless tile).
                val wander = 0.6f * sin(tx) + 0.3f * sin(2f * tx + phase[y] * 0.05f)
                var g = sin(ty * 9f + wander) * 0.5f + sin(ty * 23f + wander * 1.7f) * 0.25f + sin(ty * 47f + 0.5f * sin(tx * 3f)) * 0.12f
                g += (rnd() - 0.5f) * 0.10f                                // fine noise
                val knot = 1f - (abs(sin(ty * 2f + sin(tx * 1.5f) * 2f)) * 0.35f)
                g = (g * knot).coerceIn(-1f, 1f)
                val t = (g + 1f) / 2f
                val r = if (t < 0.5f) lerp(dark[0], base[0], t * 2f) else lerp(base[0], light[0], (t - 0.5f) * 2f)
                val gg = if (t < 0.5f) lerp(dark[1], base[1], t * 2f) else lerp(base[1], light[1], (t - 0.5f) * 2f)
                val b = if (t < 0.5f) lerp(dark[2], base[2], t * 2f) else lerp(base[2], light[2], (t - 0.5f) * 2f)
                px[y * n + x] = Color.rgb((r * 255).toInt(), (gg * 255).toInt(), (b * 255).toInt())
            }
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
}

/** Rounded panel filled with a repeating texture, tinted, with an accent stroke. */
class TexturedPanelDrawable(tile: Bitmap) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    var cornerRadius = 8f
    var tintColor = 0
        set(v) { field = v; tint.color = v; invalidateSelf() }

    fun setStroke(widthPx: Float, color: Int) { stroke.strokeWidth = widthPx; stroke.color = color; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fill)
        if (Color.alpha(tintColor) > 0) canvas.drawRoundRect(rect, cornerRadius, cornerRadius, tint)
        if (stroke.strokeWidth > 0f) { rect.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2); canvas.drawRoundRect(rect, cornerRadius, cornerRadius, stroke) }
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
