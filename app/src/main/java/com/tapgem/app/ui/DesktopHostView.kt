package com.tapgem.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.widget.FrameLayout
import android.widget.ImageView
import com.tapgem.app.core.bridge.DesktopBridge
import com.tapgem.app.core.bridge.WebCommandBus
import com.tapgem.app.core.model.Canvas as Logical
import com.tapgem.app.core.model.Desktop
import com.tapgem.app.core.model.DesktopMode
import com.tapgem.app.core.model.WallpaperKind
import com.tapgem.app.core.model.Widget
import kotlin.math.max

/**
 * Renders the current [Desktop]: wallpaper layer (desktop mode only — HUD mode
 * stays pure black, i.e. transparent on the waveguide) plus one [WidgetView]
 * per widget, diffed by id so media never reloads for a mere move. Also owns
 * cursor-driven move/resize interactions, the web-tool command handler and
 * the strip thumbnail render. Always renders the LATEST published desktop,
 * so a stale snapshot from a slow publisher can never win.
 */
class DesktopHostView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object { private const val TAG = "DesktopHost" }

    private enum class Kind { MOVE, RESIZE, CONTENT }
    /** CONTENT = a synthetic finger drag inside a window (scroll a page, pan a map). */
    private data class Interaction(val id: String, val kind: Kind, val grabDx: Int, val grabDy: Int,
                                   val downTime: Long = 0L, var lastX: Float = 0f, var lastY: Float = 0f)

    private val main = Handler(Looper.getMainLooper())
    private val wallpaperView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val views = LinkedHashMap<String, WidgetView>()
    private var subscription: AutoCloseable? = null
    private var wallpaperKey: String? = null
    private var interaction: Interaction? = null
    private var renderQueued = false
    private var ecoMode = false
    var onNotice: ((String) -> Unit)? = null
    /** A window's ⚙ was tapped. */
    var onSettings: ((String) -> Unit)? = null

    /** Battery: hidden WebViews are paused (always) and heavy widgets load one at a time (eco). */
    fun setEcoMode(eco: Boolean) {
        if (ecoMode == eco) return
        ecoMode = eco
        // Painted wallpapers can be bright: a third darker on battery.
        wallpaperView.colorFilter = if (eco) android.graphics.PorterDuffColorFilter(0x55000000, android.graphics.PorterDuff.Mode.SRC_ATOP) else null
        requestRender()
    }

    val interactionActive: Boolean get() = interaction != null

    init {
        setBackgroundColor(Color.BLACK)
        addView(wallpaperView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wallpaperView.visibility = GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        subscription?.runCatching { close() }
        subscription = DesktopBridge.observe { requestRender() }
        WebCommandBus.webHandler = { id, cmd, done ->
            main.post {
                val v = views[id]
                if (v == null) done("That window is no longer open.") else runCatching { v.runWebCommand(cmd, done) }
                    .onFailure { done("Couldn't run that on the page: ${it.message}") }
            }
        }
    }

    override fun onDetachedFromWindow() {
        subscription?.runCatching { close() }; subscription = null
        WebCommandBus.webHandler = null
        views.values.forEach { it.release() }; views.clear()
        super.onDetachedFromWindow()
    }

    /** Coalesce bursts of publishes into one render of the latest desktop. */
    private fun requestRender() {
        synchronized(this) { if (renderQueued) return; renderQueued = true }
        main.post { synchronized(this) { renderQueued = false }; render(DesktopBridge.current()) }
    }

    // ── render ─────────────────────────────────────────────────────

    private fun render(d: Desktop) {
        if (!isAttachedToWindow) return
        renderWallpaper(d)
        renderWidgets(d)
    }

    private fun renderWallpaper(d: Desktop) {
        if (d.mode == DesktopMode.HUD) {
            wallpaperView.visibility = GONE; wallpaperView.setImageDrawable(null); wallpaperKey = null
            setBackgroundColor(Color.BLACK); return
        }
        wallpaperView.visibility = VISIBLE
        val wp = d.wallpaper
        val key = "${wp.kind}|${wp.colors}|${wp.imagePath}"
        if (key == wallpaperKey) return
        wallpaperKey = key
        when (wp.kind) {
            WallpaperKind.NONE -> wallpaperView.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0A0F1C.toInt(), 0xFF02040A.toInt())))
            WallpaperKind.COLOR -> wallpaperView.setImageDrawable(GradientDrawable().apply { setColor(wp.colors.firstOrNull() ?: 0xFF101418.toInt()) })
            WallpaperKind.GRADIENT -> wallpaperView.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR,
                (if (wp.colors.size >= 2) wp.colors else wp.colors + 0xFF000000.toInt()).toIntArray()))
            WallpaperKind.IMAGE -> {
                val path = wp.imagePath ?: return
                wallpaperView.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0A0F1C.toInt(), 0xFF02040A.toInt())))
                Thread({
                    val bmp = runCatching {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(path, o)
                        var s = 1
                        while (o.outWidth / (s * 2) >= Logical.WIDTH && o.outHeight / (s * 2) >= Logical.HEIGHT) s *= 2
                        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
                    }.getOrNull()
                    main.post { if (wallpaperKey == key && bmp != null) wallpaperView.setImageBitmap(bmp) }
                }, "tapgem-wallpaper").start()
            }
        }
    }

    private fun renderWidgets(d: Desktop) {
        val ids = d.widgets.map { it.id }.toSet()
        views.keys.filter { it !in ids }.forEach { id -> views.remove(id)?.let { it.release(); removeView(it) } }
        val active = DesktopBridge.activeWidgetId
        // A cold render (app start / desktop switch) builds heavy content one window at a time
        // so the glasses never take the whole burst at once — the unplugged X3 shuts down on peaks.
        val cold = views.isEmpty() && d.widgets.isNotEmpty()
        var heavyIndex = 0
        // Pinned ("stay on top") windows always come last in the draw order, whatever their z.
        val sorted = d.widgets.sortedWith(compareBy<Widget> { it.onTop }.thenBy { it.z })
        val covered = coveredIds(sorted)
        sorted.forEach { w ->
            val v = views.getOrPut(w.id) {
                WidgetView(context).also { nv ->
                    nv.onClose = { id -> DesktopBridge.mutate { dd -> dd.copy(widgets = dd.widgets.filterNot { it.id == id }) } }
                    nv.onFocus = { id -> focus(id) }
                    nv.onSettings = { id -> focus(id); onSettings?.invoke(id) }
                    nv.onStateChange = { id, st -> DesktopBridge.mutateWidget(id, pushUndo = false, quiet = st.keys.all { it.startsWith("app.__") }) { it.withState(st) } }
                    // The page the user navigated to becomes the widget's source (so a restart
                    // reopens it); titles the app made up from a host ("archive.org") follow
                    // the page, user-given titles stay.
                    nv.onNavigated = { id, url, hostName ->
                        DesktopBridge.mutateWidget(id, pushUndo = false) { f ->
                            if (f.type != com.tapgem.app.core.model.WidgetType.WEB) f else {
                                val auto = f.title.contains('.') && !f.title.contains(' ')
                                f.copy(source = url, title = if (auto && f.title != hostName) hostName.take(32) else f.title)
                            }
                        }
                    }
                    addView(nv, LayoutParams(w.w, w.h))
                }
            }
            val heavy = w.type.isHeavy
            val defer = if (cold && heavy) (heavyIndex++ * (if (ecoMode) 900L else 500L)) else 0L
            v.bind(w, d.theme, d.mode, w.id == active, deferContentMs = defer)
            v.setCovered(w.id in covered)
            v.setEcoMode(ecoMode)
            if (interaction?.id != w.id) place(v, w.x, w.y, w.w, w.h)
            bringChildToFront(v)
        }
    }

    /** Windows entirely hidden behind a higher window: their WebViews get paused. */
    private fun coveredIds(sorted: List<Widget>): Set<String> {
        val out = HashSet<String>()
        for (i in sorted.indices) {
            val w = sorted[i]
            for (j in i + 1 until sorted.size) {
                val o = sorted[j]
                if (o.style.opacity < 0.98f) continue
                if (o.x <= w.x && o.y <= w.y && o.x + o.w >= w.x + w.w && o.y + o.h >= w.y + w.h) { out += w.id; break }
            }
        }
        return out
    }

    private fun place(v: WidgetView, x: Int, y: Int, w: Int, h: Int) {
        val lp = v.layoutParams as LayoutParams
        if (lp.width != w || lp.height != h || lp.leftMargin != x || lp.topMargin != y) {
            lp.width = w; lp.height = h; lp.leftMargin = x; lp.topMargin = y; lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            v.layoutParams = lp
        }
    }

    /** Title-bar / body tap: the window becomes active and comes to the front. */
    fun focus(id: String) {
        val d = DesktopBridge.current()
        val w = d.widget(id) ?: return
        val top = d.widgets.maxOfOrNull { it.z } ?: 0
        if (w.z != top) DesktopBridge.mutate(pushUndo = false) { it.replaceWidget(w.copy(z = top + 1)) }
        if (DesktopBridge.activeWidgetId != id) DesktopBridge.setActive(id)
    }

    val contentDragActive: Boolean get() = interaction?.kind == Kind.CONTENT

    /** The active window is a page/app that can take typed keys. */
    fun activeAcceptsKeys(): Boolean = DesktopBridge.activeWidgetId?.let { views[it]?.acceptsKeys } == true

    /** Typed keys go to the active page/app (scrcpy, a paired keyboard). */
    fun forwardKey(event: android.view.KeyEvent): Boolean {
        val id = DesktopBridge.activeWidgetId ?: return false
        return views[id]?.forwardKey(event) == true
    }

    fun pauseMedia() { views.values.forEach { it.pauseForBackground() } }
    fun resumeMedia() { views.values.forEach { it.resumeFromBackground() } }

    // ── hit-testing & cursor interactions (host-local coordinates) ──

    fun widgetViewAt(x: Float, y: Float): WidgetView? {
        for (i in childCount - 1 downTo 0) {
            val c = getChildAt(i) as? WidgetView ?: continue
            if (x >= c.left && x < c.right && y >= c.top && y < c.bottom) return c
        }
        return null
    }

    /**
     * Auto-hiding frames follow the cursor: the window under it wears its chrome, every other one
     * drops back to bare content. Driven from the same cursor stream as the edge scroller, so this
     * costs one hit-test per cursor move and nothing at all when no window is in auto mode.
     */
    fun updateHover(x: Float, y: Float) {
        val hit = widgetViewAt(x, y)
        for (v in views.values) v.cursorOver = (v === hit)
        applyHoverGrow()
    }

    /**
     * Give a window room while the cursor is on it, and take it back when the cursor leaves.
     *
     * Deliberately a view-level change: the stored widget keeps the size its owner chose, so being
     * hovered is never written to the desktop and a rebind from the model simply snaps it back to
     * resting. Skipped mid-drag so it cannot fight a move or resize already in progress.
     */
    fun refreshHoverGrow() = applyHoverGrow()

    private fun applyHoverGrow() {
        if (interaction != null) return
        for (v in views.values) {
            val want = v.hoverGrow ?: continue
            val m = v.widget
            if (v.cursorOver || v.panelPinned) {
                val w = maxOf(m.w, want.first).coerceAtMost(Logical.WIDTH)
                val h = maxOf(m.h, want.second).coerceAtMost(Logical.HEIGHT - Logical.CONTENT_TOP)
                // Grow down and right from where it sits; pull back on screen only if it must.
                val x = m.x.coerceIn(0, max(0, Logical.WIDTH - w))
                val y = m.y.coerceIn(Logical.CONTENT_TOP, max(Logical.CONTENT_TOP, Logical.HEIGHT - h))
                place(v, x, y, w, h)
            } else place(v, m.x, m.y, m.w, m.h)
        }
    }

    /** The cursor went away (idle timeout, voice took over): every auto frame goes bare again. */
    fun clearHover() { for (v in views.values) v.cursorOver = false; applyHoverGrow() }

    fun beginMove(v: WidgetView, cursorX: Float, cursorY: Float, quiet: Boolean = false) {
        focus(v.widget.id)
        interaction = Interaction(v.widget.id, Kind.MOVE, (cursorX - v.left).toInt(), (cursorY - v.top).toInt())
        v.pinChrome = true
        v.alpha = 0.75f
        if (!quiet) onNotice?.invoke("Moving \"${v.widget.title}\" — tap to place")
    }

    fun beginResize(v: WidgetView, quiet: Boolean = false) {
        focus(v.widget.id)
        interaction = Interaction(v.widget.id, Kind.RESIZE, 0, 0)
        v.pinChrome = true
        v.alpha = 0.75f
        if (!quiet) onNotice?.invoke("Resizing \"${v.widget.title}\" — tap to set")
    }

    /**
     * Start a synthetic finger drag inside the window under the cursor: the
     * page scrolls, a map pans, a slider drags — exactly as a touch would.
     */
    fun beginContentDrag(v: WidgetView, cursorX: Float, cursorY: Float) {
        focus(v.widget.id)
        val lx = cursorX - v.left; val ly = cursorY - v.top
        val t = SyntheticInput.dragStart(v, lx, ly)
        interaction = Interaction(v.widget.id, Kind.CONTENT, 0, 0, downTime = t, lastX = lx, lastY = ly)
    }

    /** Two-finger scroll: drives a content drag on the window under the cursor by a delta. */
    fun scrollContentBy(cursorX: Float, cursorY: Float, dx: Float, dy: Float) {
        val cur = interaction
        if (cur == null) {
            val v = widgetViewAt(cursorX, cursorY) ?: return
            beginContentDrag(v, cursorX, cursorY)
        }
        val it = interaction ?: return
        if (it.kind != Kind.CONTENT) return
        val v = views[it.id] ?: return
        it.lastX += dx; it.lastY += dy
        SyntheticInput.dragMove(v, it.downTime, it.lastX, it.lastY)
    }

    fun updateInteraction(cursorX: Float, cursorY: Float) {
        val it = interaction ?: return
        val v = views[it.id] ?: return
        if (it.kind == Kind.CONTENT) {
            it.lastX = cursorX - v.left; it.lastY = cursorY - v.top
            SyntheticInput.dragMove(v, it.downTime, it.lastX, it.lastY)
            return
        }
        val lp = v.layoutParams as LayoutParams
        when (it.kind) {
            Kind.CONTENT -> {}
            Kind.MOVE -> {
                lp.leftMargin = (cursorX.toInt() - it.grabDx).coerceIn(0, max(0, Logical.WIDTH - lp.width))
                lp.topMargin = (cursorY.toInt() - it.grabDy).coerceIn(Logical.CONTENT_TOP, max(Logical.CONTENT_TOP, Logical.HEIGHT - lp.height))
            }
            Kind.RESIZE -> {
                lp.width = (cursorX.toInt() - v.left).coerceIn(Logical.MIN_W, Logical.WIDTH - v.left)
                lp.height = (cursorY.toInt() - v.top).coerceIn(Logical.MIN_H, Logical.HEIGHT - v.top)
            }
        }
        v.layoutParams = lp
    }

    fun endInteraction(): Boolean {
        val it = interaction ?: return false
        interaction = null
        val v = views[it.id] ?: return true
        if (it.kind == Kind.CONTENT) { SyntheticInput.dragEnd(v, it.downTime, it.lastX, it.lastY); return true }
        v.pinChrome = false
        v.alpha = v.widget.style.opacity
        val lp = v.layoutParams as LayoutParams
        val x = lp.leftMargin; val y = lp.topMargin; val w = lp.width; val h = lp.height
        // A hover-grown window is bigger than its owner asked for while the cursor is on it.
        // Dragging one would otherwise commit that borrowed size as its real one, so a move keeps
        // the stored size and only a deliberate resize changes it.
        val borrowed = v.hoverGrow != null && v.cursorOver && it.kind == Kind.MOVE
        DesktopBridge.mutateWidget(it.id) { f ->
            if (borrowed) f.copy(x = x, y = y) else f.copy(x = x, y = y, w = w, h = h)
        }
        // Report what was actually stored, not the on-screen size — a hover-grown window commits
        // its resting size and logging the borrowed one made a correct move look like a bug.
        val cw = if (borrowed) v.widget.w else w
        val ch = if (borrowed) v.widget.h else h
        Log.d(TAG, "${it.kind} committed ${it.id} → ($x,$y) ${cw}x$ch")
        return true
    }

    /** 160×120 snapshot of the desktop for the strip (video frames included). */
    /** Freeze an app window's live state into the desktop model (no-op for other types); [done] runs after. */
    fun snapshotAppState(id: String, done: () -> Unit) {
        val v = views[id] ?: return done()
        v.snapshotAppState(done)
    }

    /** One window as it looks right now (bookmark tile), letterboxed into [w]×[h]. */
    fun renderWidgetThumbnail(id: String, w: Int = 232, h: Int = 148): Bitmap? {
        val v = views[id] ?: return null
        if (v.width <= 0 || v.height <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF0B1016.toInt())
        val s = minOf(w.toFloat() / v.width, h.toFloat() / v.height)
        c.translate((w - v.width * s) / 2f, (h - v.height * s) / 2f)
        c.scale(s, s)
        runCatching { v.draw(c) }
        runCatching { v.drawLiveFrame(c) }
        return bmp
    }

    fun renderThumbnail(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val bmp = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        c.scale(160f / width, 120f / height)
        runCatching { draw(c) }
        views.values.forEach { v ->
            c.save(); c.translate(v.left.toFloat(), v.top.toFloat())
            c.clipRect(0, 0, v.width, v.height)
            runCatching { v.drawLiveFrame(c) }
            c.restore()
        }
        return bmp
    }

}
