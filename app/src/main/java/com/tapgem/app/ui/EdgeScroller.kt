package com.tapgem.app.ui

import android.os.Handler
import android.os.SystemClock

/**
 * Edge auto-scroll: park the cursor near a window's edge and the content
 * scrolls that way — the further into the edge band, the faster — until
 * the cursor leaves the band or the content runs out.
 *
 * Rules that keep it from firing by accident:
 *  - only inside a window's scrollable body (never the title bar or the
 *    resize corner, never on passive panels like clocks or photos);
 *  - a dwell of [DWELL_MS] inside the band before anything moves;
 *  - never while a window is being dragged / resized / content-dragged;
 *  - the tick runs only while scrolling; nothing is scheduled when idle.
 */
class EdgeScroller(
    private val host: DesktopHostView,
    private val handler: Handler,
    /** Ask the activity whether some other interaction owns the cursor right now. */
    private val busy: () -> Boolean,
    /** Called when scrolling starts/stops so the cursor can be kept visible. */
    private val onActive: (Boolean) -> Unit = {}
) {
    enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

    private var target: WidgetView? = null
    private var edge: Edge? = null
    private var depth = 0f          // 0 = band's inner boundary … 1 = window edge
    private var enteredMs = 0L
    private var lastTickMs = 0L
    private var scrolling = false
    private var stalledTicks = 0

    private val tick = object : Runnable {
        override fun run() {
            val v = target; val e = edge
            if (v == null || e == null || busy() || !v.isAttachedToWindow) { stop(); return }
            val now = SystemClock.uptimeMillis()
            if (!scrolling) {
                if (now - enteredMs < DWELL_MS) { handler.postDelayed(this, DWELL_MS - (now - enteredMs)); return }
                scrolling = true; lastTickMs = now; stalledTicks = 0
                v.setEdgeGlow(e); onActive(true)
            }
            val dt = ((now - lastTickMs).coerceIn(1L, 100L)) / 1000f
            lastTickMs = now
            val speed = MIN_SPEED + (MAX_SPEED - MIN_SPEED) * depth * depth   // ease-in: gentle at the boundary, brisk at the rim
            val step = (speed * dt).coerceAtLeast(1f).toInt()
            val moved = when (e) {
                Edge.TOP -> v.edgeScrollBy(0, -step)
                Edge.BOTTOM -> v.edgeScrollBy(0, step)
                Edge.LEFT -> v.edgeScrollBy(-step, 0)
                Edge.RIGHT -> v.edgeScrollBy(step, 0)
            }
            // Out of content in that direction: stop quietly (the glow fades) until the cursor moves again.
            if (!moved) { if (++stalledTicks >= 3) { stop(keepTarget = true); return } } else stalledTicks = 0
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Feed every cursor position (host-local coordinates). */
    fun onCursor(x: Float, y: Float) {
        if (busy()) { stop(); return }
        val v = host.widgetViewAt(x, y)
        val e = v?.edgeAt(x - v.left, y - v.top)
        if (v == null || e == null) { stop(); return }
        if (v !== target || e.edge != edge) {
            // New window or new edge: restart the dwell.
            stop()
            target = v; edge = e.edge; enteredMs = SystemClock.uptimeMillis()
            handler.postDelayed(tick, DWELL_MS)
        } else if (stalled) {
            // Same edge after running out of content: only a fresh entry restarts it.
            return
        }
        depth = e.depth
    }

    private var stalled = false

    fun stop(keepTarget: Boolean = false) {
        handler.removeCallbacks(tick)
        if (scrolling) { target?.setEdgeGlow(null); onActive(false) }
        scrolling = false
        stalled = keepTarget
        if (!keepTarget) { target = null; edge = null }
    }

    val isScrolling: Boolean get() = scrolling

    companion object {
        const val DWELL_MS = 220L
        const val TICK_MS = 33L
        const val MIN_SPEED = 90f     // px/s at the band's inner boundary
        const val MAX_SPEED = 380f    // px/s at the very edge
    }
}
