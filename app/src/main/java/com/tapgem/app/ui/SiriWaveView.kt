package com.tapgem.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * The assistant avatar: a tiny bar waveform in the new-Siri palette
 * (indigo → violet → pink → orange → sky). Colour = status: dim grey idle,
 * amber connecting, full rainbow listening (bars ride the mic level), slow
 * rainbow sweep thinking, blue-shifted rainbow speaking (bars ride the model
 * audio), red on error. Tap = talk, double-tap = exit (handled by the Activity).
 */
class SiriWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            restartTicker()
        }

    private var ticking = false

    /** Exactly one tick loop at a time, whatever order attach/mode changes arrive in. */
    private fun restartTicker() {
        removeCallbacks(tick)
        ticking = false
        if (!isAttachedToWindow) return
        ticking = true
        post(tick)
    }

    /** 0..1 input; smoothed internally. */
    var level: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f) }

    /** On battery: no idle animation at all, slower active animation. */
    var ecoMode: Boolean = false
        set(value) { if (field != value) { field = value; restartTicker() } }

    private val siri = intArrayOf(
        0xFF5E5CE6.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF375F.toInt(),
        0xFFFF9F0A.toInt(), 0xFF64D2FF.toInt(), 0xFF5E5CE6.toInt()
    )
    private val speak = intArrayOf(
        0xFF64D2FF.toInt(), 0xFF5E5CE6.toInt(), 0xFFBF5AF2.toInt(),
        0xFF30D158.toInt(), 0xFF64D2FF.toInt()
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private var shaderPalette: IntArray? = null
    private var phase = 0f
    private var shown = 0f
    private val bars = 13

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            val idle = mode == Mode.IDLE
            phase += if (idle) 0.05f else 0.13f
            shown += (level - shown) * 0.35f
            invalidate()
            // Idle is a single static frame: nothing to animate, nothing to burn.
            if (idle) { ticking = false; return }
            postDelayed(this, if (ecoMode) 66L else 33L)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); restartTicker() }
    override fun onDetachedFromWindow() { removeCallbacks(tick); ticking = false; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val barW = 3f
        val gap = (w - bars * barW) / (bars + 1)
        val cy = h / 2f

        when (mode) {
            Mode.IDLE -> { paint.shader = null; paint.color = 0x99FFFFFF.toInt() }
            Mode.ERROR -> { paint.shader = null; paint.color = 0xFFFF6B6B.toInt() }
            Mode.CONNECTING -> { paint.shader = null; paint.color = 0xFFFFB347.toInt() }
            Mode.LISTENING, Mode.THINKING -> applyShader(siri, w)
            Mode.SPEAKING -> applyShader(speak, w)
        }

        for (i in 0 until bars) {
            val t = i / (bars - 1f)
            val env = sin(Math.PI * t).toFloat().coerceAtLeast(0.15f)
            val amp = when (mode) {
                Mode.IDLE -> 0.10f + 0.04f * sin(phase + i * 0.4f)
                Mode.CONNECTING -> 0.22f + 0.14f * sin(phase * 2f + i * 0.5f)
                Mode.LISTENING -> 0.18f + (0.78f * shown + 0.12f) * abs(sin(phase * 1.7f + i * 0.9f))
                Mode.THINKING -> 0.28f + 0.34f * abs(sin(phase * 2.4f + i * 0.6f))
                Mode.SPEAKING -> 0.22f + (0.82f * shown + 0.16f) * abs(sin(phase * 2.2f + i * 1.1f))
                Mode.ERROR -> 0.14f
            }
            val bh = max(barW, (h - 6f) * env * amp.coerceIn(0f, 1f))
            val x = gap + i * (barW + gap)
            rect.set(x, cy - bh / 2f, x + barW, cy + bh / 2f)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
        }
    }

    private fun applyShader(palette: IntArray, w: Float) {
        if (shader == null || shaderPalette !== palette) {
            shader = LinearGradient(0f, 0f, w, 0f, palette, null, Shader.TileMode.REPEAT)
            shaderPalette = palette
        }
        matrix.setTranslate((phase * 22f) % w, 0f)
        shader!!.setLocalMatrix(matrix)
        paint.shader = shader
        paint.color = Color.WHITE
    }
}
