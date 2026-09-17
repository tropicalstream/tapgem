package com.tapgem.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View

/**
 * A one-line news/stock/weather crawl: items separated by a diamond scroll
 * right-to-left forever. Drawn with a single Paint at a fixed frame period
 * (30 fps, 20 fps on battery) — only this strip repaints, nothing else.
 */
class TickerView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private var text = ""
    private var textWidth = 0f
    private var offset = 0f
    private var lastFrameMs = 0L
    private var running = false
    /** px per second */
    var speed = 48f
    var frameMs = 33L
    private val gap = 90f

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrameMs == 0L) frameMs else (now - lastFrameMs).coerceIn(1L, 200L)
            lastFrameMs = now
            offset -= speed * dt / 1000f
            if (textWidth > 0 && offset < -(textWidth + gap)) offset += textWidth + gap
            invalidate()
            postDelayed(this, frameMs)
        }
    }

    fun setStyle(color: Int, sizePx: Float) {
        paint.color = color; paint.textSize = sizePx
        textWidth = paint.measureText(text)
        invalidate()
    }

    fun setItems(items: List<String>) {
        val t = items.map { it.trim() }.filter { it.isNotBlank() }.joinToString("   ◆   ")
        if (t == text) return
        text = t
        textWidth = paint.measureText(text)
        offset = width.toFloat().coerceAtLeast(1f)
        invalidate()
    }

    fun setEco(eco: Boolean) { frameMs = if (eco) 50L else 33L; speed = if (eco) 40f else 48f }

    private fun start() { if (running || text.isBlank()) return; running = true; lastFrameMs = 0L; post(tick) }
    private fun stop() { running = false; removeCallbacks(tick) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) start() else stop()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (offset > w) offset = w.toFloat()
        start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (text.isBlank()) return
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2f
        var x = offset
        // Draw copies until the strip is covered so the loop is seamless.
        while (x < width) {
            canvas.drawText(text, x, y, paint)
            x += textWidth + gap
            if (textWidth <= 0f) break
        }
    }
}
