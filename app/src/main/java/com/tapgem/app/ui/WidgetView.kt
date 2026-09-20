package com.tapgem.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tapgem.app.core.bridge.HudStateBridge
import com.tapgem.app.core.bridge.WebCommandBus
import com.tapgem.app.core.media.EpubUnpacker
import com.tapgem.app.core.media.Net
import com.tapgem.app.core.model.ColorUtil
import com.tapgem.app.core.model.DesktopMode
import com.tapgem.app.core.model.Theme
import com.tapgem.app.core.model.Widget
import com.tapgem.app.core.model.WidgetType
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.tapgem.app.core.network.Router
import kotlin.math.roundToInt

/**
 * One window on the desktop. Renders its [Widget] by type — text, clock,
 * live card, image, video (TextureView: SurfaceView can't be dual-drawn),
 * audio card, PDF page, EPUB chapter, web page, vibe-coded app, a three.js
 * 3D viewer, or a street map — with optional chrome (title bar + close) and
 * a resize handle. Content is rebuilt only when type/source change; state
 * changes (page, chapter, play/pause, zoom, reload…) are applied in place.
 *
 * WebViews never show a keyboard ([NoImeWebView]) and are locked down per
 * type: pages get no file access, ebooks get no JavaScript, apps get the
 * tiny window.TapGem bridge and nothing else.
 */
class WidgetView(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "WidgetView"
        const val APP_SNAPSHOT_KEY = "app.__snapshot"
        const val APP_SNAPSHOT_SRC = "app.__snapshotSrc"
        private const val APP_SNAPSHOT_MS = 5_000L
        private const val APP_SNAPSHOT_MAX = 256 * 1024
        const val HANDLE = 18
        const val TITLE_H = 18
        /** Edge auto-scroll bands (px inside the content area). */
        const val EDGE_BAND_V = 30f
        const val EDGE_BAND_H = 26f
        /** Sites that may read the glasses' location (for "your location" / directions from here). */
        private val GEO_ORIGINS = setOf("google.com", "maps.google.com", "radio.garden", "openstreetmap.org")
    }

    var widget: Widget = Widget(type = WidgetType.TEXT, title = "", x = 0, y = 0, w = 100, h = 60)
        private set
    private var theme: Theme = com.tapgem.app.core.model.Themes.DEFAULT
    private var mode: DesktopMode = DesktopMode.HUD
    private var active = false

    var onClose: ((String) -> Unit)? = null
    var onFocus: ((String) -> Unit)? = null
    /** The ⚙ in the title bar: open this window's settings sheet. */
    var onSettings: ((String) -> Unit)? = null
    /** Persist small runtime state (playback position, page counts) without undo. */
    var onStateChange: ((String, Map<String, String>) -> Unit)? = null
    /** A web widget navigated: (id, url, host) — the host view records the page and refreshes an auto title. */
    var onNavigated: ((String, String, String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val bg = GradientDrawable()
    private val titleBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val titleText = TextView(context).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTypeface(Typeface.DEFAULT_BOLD) }
    private val closeBtn = TextView(context).apply { text = "✕"; gravity = Gravity.CENTER; isClickable = true; isFocusable = true; contentDescription = "Close" }
    private val settingsBtn = TextView(context).apply { text = "⚙"; gravity = Gravity.CENTER; isClickable = true; isFocusable = true; contentDescription = "Settings" }
    private val content = FrameLayout(context)
    private val errorLabel = TextView(context).apply {
        visibility = GONE; gravity = Gravity.CENTER; setTextColor(0xFFFFB4B4.toInt()); setBackgroundColor(0xB3000000.toInt())
        setPadding(10, 6, 10, 6); setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f)
    }
    private val handle = View(context)
    /** Battery: photos and video are dimmed a third — bright media is most of what lights the display. */
    private val dimOverlay = View(context).apply { setBackgroundColor(0x59000000); visibility = GONE }
    /** Accent line along the edge that is auto-scrolling. */
    private val edgeGlow = View(context).apply { visibility = GONE }
    private var contentKind: String? = null
    private var styleKey: String? = null
    private var contentGen = 0

    private var mediaPlayer: MediaPlayer? = null
    private var videoSurface: Surface? = null
    private var textureView: TextureView? = null
    private var webView: WebView? = null
    private var fullscreenView: View? = null                                  // a page's fullscreen element, shown inside the window
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pdfFd: ParcelFileDescriptor? = null
    private var clockRunnable: Runnable? = null
    private var epubChapters: List<File> = emptyList()
    private var audioProgress: Runnable? = null
    private var mediaPrepared = false
    private var resumeOnForeground = false
    private var lastNavigatedUrl: String? = null
    private var pageLoading = false
    private var lastHttpStatus = 0
    private var navSeq = 0
    private val loadWaiters = ArrayList<() -> Unit>()

    init {
        clipChildren = true; clipToPadding = true
        isClickable = true; isFocusable = true
        background = bg
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        titleBar.addView(titleText, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 })
        titleBar.addView(settingsBtn, LinearLayout.LayoutParams(22, TITLE_H))
        titleBar.addView(closeBtn, LinearLayout.LayoutParams(22, TITLE_H))
        addView(titleBar, LayoutParams(LayoutParams.MATCH_PARENT, TITLE_H, Gravity.TOP))
        addView(dimOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(edgeGlow, LayoutParams(LayoutParams.MATCH_PARENT, 3, Gravity.BOTTOM))
        addView(errorLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(handle, LayoutParams(HANDLE, HANDLE, Gravity.BOTTOM or Gravity.END))
        closeBtn.setOnClickListener { onClose?.invoke(widget.id) }
        settingsBtn.setOnClickListener { onSettings?.invoke(widget.id) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onFocus?.invoke(widget.id)
        val handled = super.dispatchTouchEvent(ev)
        if (com.tapgem.app.BuildConfig.DEBUG && ev.actionMasked != MotionEvent.ACTION_MOVE)
            Log.d(TAG, "touch ${MotionEvent.actionToString(ev.actionMasked)} (${ev.x.toInt()},${ev.y.toInt()}) handled=$handled")
        return handled
    }

    /**
     * Hardware/remote keyboards (scrcpy, a paired keyboard) type straight into
     * the page: the WebView never holds Android focus (that is what keeps the
     * soft keyboard away), so the activity hands key events to it directly.
     * Chromium delivers them to the page's focused field as real key presses.
     */
    fun forwardKey(event: android.view.KeyEvent): Boolean {
        val wv = webView ?: return false
        if (!wv.settings.javaScriptEnabled && widget.type != WidgetType.EPUB) return false
        return runCatching { keyTarget(wv).dispatchKeyEvent(event) }.getOrDefault(false)
    }

    val acceptsKeys: Boolean get() = webView != null && widget.type.isWebLike

    fun isOnResizeHandle(localX: Float, localY: Float): Boolean =
        localX >= width - HANDLE - 6 && localY >= height - HANDLE - 6

    fun isOnTitleBar(localX: Float, localY: Float): Boolean =
        chromeVisible() && localY < TITLE_H && localX < width - 46   // ⚙ and ✕ live in the last 46 px

    /**
     * Where a press-and-hold grabs the window: the title bar when shown;
     * otherwise anywhere on passive widgets and a top band on interactive ones.
     */
    fun isGrabZone(localX: Float, localY: Float): Boolean {
        if (isOnResizeHandle(localX, localY)) return true
        if (chromeVisible()) return isOnTitleBar(localX, localY)
        return when (widget.type) {
            WidgetType.CLOCK, WidgetType.IMAGE, WidgetType.AUDIO -> true
            else -> localY < TITLE_H + 4   // a top band; the rest of the body scrolls the content
        }
    }

    private var covered = false
    private var pendingBuild: Runnable? = null
    private var pendingSeq = 0
    private var eco = false
    private var ecoCssApplied = false

    /**
     * Battery: a generated app that is not the active window gets its CSS
     * animations frozen (a "glow" loop alone kept the GPU at 60 fps). The
     * active app, and everything when plugged in, animates normally.
     */
    fun setEcoMode(on: Boolean) {
        if (eco == on) return
        eco = on
        applyEcoCss()
        applyDim()
        tickerView?.setEco(on)
        clockFace?.let { it.ecoMode = on; clockRunnable?.let { r -> main.removeCallbacks(r); main.post(r) } }
        // On battery, pages may not start media on their own (YouTube autoplay after a reload
        // is a full decode + network load nobody asked for); a tap — ours or the user's — still counts.
        if (widget.type == WidgetType.WEB) runCatching { webView?.settings?.mediaPlaybackRequiresUserGesture = on }
        if (isHud) pushHudEnv()
    }

    private fun applyDim() {
        val dim = eco && (widget.type == WidgetType.IMAGE || widget.type == WidgetType.VIDEO)
        dimOverlay.visibility = if (dim) VISIBLE else GONE
        if (dim) bringChildToFront(dimOverlay).also { bringChildToFront(titleBar); bringChildToFront(handle); bringChildToFront(errorLabel) }
    }

    private fun applyEcoCss() {
        val wv = webView ?: return
        if (widget.type != WidgetType.APP || !wv.settings.javaScriptEnabled) return
        val want = eco && !active
        if (want == ecoCssApplied) return
        ecoCssApplied = want
        val js = if (want)
            "(function(){if(document.getElementById('tapgem-eco'))return;var s=document.createElement('style');s.id='tapgem-eco';s.textContent='*,*::before,*::after{animation-play-state:paused!important}';(document.head||document.documentElement).appendChild(s);})()"
        else "(function(){var s=document.getElementById('tapgem-eco');if(s)s.remove();})()"
        wv.evaluateJavascript(js, null)
    }

    /**
     * A window entirely hidden behind another: its WebView is paused (no
     * compositor work, no animations) until it is uncovered.
     */
    fun setCovered(isCovered: Boolean) {
        if (covered == isCovered) return
        covered = isCovered
        runCatching { if (isCovered) webView?.onPause() else webView?.onResume() }
    }

    fun bind(w: Widget, t: Theme, m: DesktopMode, isActive: Boolean, deferContentMs: Long = 0L) {
        val oldState = widget.state
        val oldWidget = widget
        val kind = "${w.type}|${w.source}"
        val sameContent = kind == contentKind
        val sk = "$t|${w.style}|$m"
        val restyle = sk != styleKey
        widget = w; theme = t; mode = m; active = isActive
        styleKey = sk
        applyChrome(); applyStyle(); applyEcoCss(); applyDim()
        if (!sameContent && deferContentMs > 0L && contentKind == null) {
            // Cold start: build this heavy panel a beat later than the previous one.
            contentKind = kind
            pendingBuild?.let { main.removeCallbacks(it) }
            val seq = ++pendingSeq
            val r = Runnable { pendingBuild = null; if (seq == pendingSeq && isAttachedToWindow && contentKind == kind) buildContent() }
            pendingBuild = r
            main.postDelayed(r, deferContentMs)
            return
        }
        if (pendingBuild != null && !sameContent) { pendingBuild?.let { main.removeCallbacks(it) }; pendingBuild = null; pendingSeq++ }
        if (!sameContent && w.type == WidgetType.WEB && webView != null && oldWidget.type == WidgetType.WEB) {
            // Source changed on a live page: navigate in place (a reload would drop history),
            // and never reload a page the user just navigated to inside the widget.
            contentKind = kind
            val wv = webView!!
            if (w.source != lastNavigatedUrl && w.source != wv.url) wv.loadUrl(w.source)
        } else if (!sameContent) {
            unbindContent(); contentKind = kind; buildContent()
        } else {
            val textual = w.type == WidgetType.TEXT || w.type == WidgetType.LIVE || w.type == WidgetType.CLOCK || w.type == WidgetType.TICKER
            if (restyle && textual) { unbindContent(); buildContent() }
            else if (restyle && w.type == WidgetType.EPUB) loadEpubChapter()
            else if (oldState != w.state || w.content != oldWidget.content || w.updatedAt != oldWidget.updatedAt) applyState(oldState, w.state)
        }
    }

    fun release() { pendingBuild?.let { main.removeCallbacks(it) }; pendingBuild = null; pendingSeq++; unbindContent(); contentKind = null }

    // ── edge auto-scroll ───────────────────────────────────────────

    class EdgeHit(val edge: EdgeScroller.Edge, val depth: Float)

    /**
     * Which edge band (if any) a body point is in. Bands are [EDGE_BAND_V] /
     * [EDGE_BAND_H] px inside the content area; the title bar and the resize
     * corner are excluded, as are widgets with nothing to scroll.
     */
    fun edgeAt(localX: Float, localY: Float): EdgeHit? {
        if (!widget.type.isScrollable || widget.type == WidgetType.PDF) return null
        if (isOnResizeHandle(localX, localY) || (chromeVisible() && localY < TITLE_H)) return null
        val top = contentTop()
        val h = height - top; val w = width
        if (h < EDGE_BAND_V * 3 || w < EDGE_BAND_H * 3) return null
        val ly = localY - top
        val dTop = ly; val dBottom = h - ly; val dLeft = localX; val dRight = w - localX
        val v = when { dTop < EDGE_BAND_V -> EdgeHit(EdgeScroller.Edge.TOP, 1f - dTop / EDGE_BAND_V); dBottom < EDGE_BAND_V -> EdgeHit(EdgeScroller.Edge.BOTTOM, 1f - dBottom / EDGE_BAND_V); else -> null }
        val hz = if (widget.type == WidgetType.MAP || widget.type.isWebLike) when {
            dLeft < EDGE_BAND_H -> EdgeHit(EdgeScroller.Edge.LEFT, 1f - dLeft / EDGE_BAND_H)
            dRight < EDGE_BAND_H -> EdgeHit(EdgeScroller.Edge.RIGHT, 1f - dRight / EDGE_BAND_H)
            else -> null } else null
        // In a corner the deeper band wins.
        return if (v != null && hz != null) (if (v.depth >= hz.depth) v else hz) else v ?: hz
    }

    private var jsScrollAccX = 0; private var jsScrollAccY = 0; private var jsScrollFlushMs = 0L

    /** Scroll the content by a small step; false when there is nothing more in that direction. */
    fun edgeScrollBy(dx: Int, dy: Int): Boolean {
        when (widget.type) {
            WidgetType.WEB, WidgetType.APP, WidgetType.EPUB -> {
                val wv = webView ?: return false
                val canV = dy != 0 && wv.canScrollVertically(if (dy > 0) 1 else -1)
                val canH = dx != 0 && wv.canScrollHorizontally(if (dx > 0) 1 else -1)
                if (canV || canH) { wv.scrollBy(if (canH) dx else 0, if (canV) dy else 0); return true }
                // The document doesn't scroll — an inner scroller might (web apps); ask the page, throttled.
                if (!wv.settings.javaScriptEnabled) return false
                return jsScroll(wv, dx, dy)
            }
            WidgetType.MAP -> { val wv = webView ?: return false; return jsScroll(wv, dx, dy) }
            WidgetType.TEXT, WidgetType.LIVE -> {
                val sv = findScrollView(content) ?: return false
                val can = dy != 0 && sv.canScrollVertically(if (dy > 0) 1 else -1)
                if (can) sv.scrollBy(0, dy)
                return can
            }
            else -> return false
        }
    }

    private fun jsScroll(wv: WebView, dx: Int, dy: Int): Boolean {
        jsScrollAccX += dx; jsScrollAccY += dy
        val now = android.os.SystemClock.uptimeMillis()
        if (now - jsScrollFlushMs < 100L) return true
        jsScrollFlushMs = now
        val ax = jsScrollAccX; val ay = jsScrollAccY; jsScrollAccX = 0; jsScrollAccY = 0
        if (widget.type == WidgetType.MAP) {
            val dir = when { kotlin.math.abs(ay) >= kotlin.math.abs(ax) -> if (ay > 0) "south" else "north"; else -> if (ax > 0) "east" else "west" }
            wv.evaluateJavascript("window.panBy && panBy(${jsStr(dir)}, ${maxOf(kotlin.math.abs(ax), kotlin.math.abs(ay))})", null)
        } else {
            wv.evaluateJavascript(HELPER_JS, null)
            val dir = when { kotlin.math.abs(ay) >= kotlin.math.abs(ax) -> if (ay > 0) "down" else "up"; else -> if (ax > 0) "right" else "left" }
            wv.evaluateJavascript("window.__tg && __tg.scroll(${jsStr(dir)}, ${maxOf(kotlin.math.abs(ax), kotlin.math.abs(ay))})", null)
        }
        return true
    }

    private fun findScrollView(v: View): ScrollView? {
        if (v is ScrollView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findScrollView(v.getChildAt(i))?.let { return it }
        return null
    }

    /** A thin accent line along the edge that is currently auto-scrolling. */
    fun setEdgeGlow(edge: EdgeScroller.Edge?) {
        if (edge == null) { edgeGlow.visibility = GONE; return }
        val top = contentTop()
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        when (edge) {
            EdgeScroller.Edge.TOP -> { lp.height = 3; lp.gravity = Gravity.TOP; lp.topMargin = top }
            EdgeScroller.Edge.BOTTOM -> { lp.height = 3; lp.gravity = Gravity.BOTTOM }
            EdgeScroller.Edge.LEFT -> { lp.width = 3; lp.gravity = Gravity.START }
            EdgeScroller.Edge.RIGHT -> { lp.width = 3; lp.gravity = Gravity.END }
        }
        edgeGlow.layoutParams = lp
        edgeGlow.setBackgroundColor(ColorUtil.withAlpha(theme.accent, 0.9f))
        edgeGlow.visibility = VISIBLE
        bringChildToFront(edgeGlow)
    }

    /** True when a press-and-hold on the body should scroll/pan the content rather than grab the window. */
    fun isScrollableBody(localX: Float, localY: Float): Boolean =
        widget.type.isScrollable && !isOnResizeHandle(localX, localY) && !(chromeVisible() && localY < TITLE_H)

    // ── chrome & style ─────────────────────────────────────────────

    /**
     * Title bar + ✕ + corner handle are how windows are dragged, closed and resized: always on
     * unless the user hides them, or — in auto mode — until the cursor comes over the window.
     */
    private fun chromeVisible(): Boolean =
        if (widget.style.chromeAuto) cursorOver || pinChrome else widget.style.chrome ?: true

    /** Where the body starts: a fixed frame reserves its strip, an auto frame floats over it. */
    private fun contentTop(): Int = if (chromeVisible() && !widget.style.chromeAuto) TITLE_H else 0

    /**
     * How big this window has to be before its hover-revealed controls have anywhere to appear,
     * or null if it never grows. The music player is the case: left at skin height there is no
     * room for the section switch, so reaching for it grows the window and leaving puts it back.
     * This is a floor, never a shrink — a player already larger keeps the size it has.
     */
    val hoverGrow: Pair<Int, Int>?
        get() = if (widget.type == WidgetType.APP &&
                    widget.source.endsWith(com.tapgem.app.core.tools.LiveApps.MUSIC)) 430 to 330 else null

    /**
     * A section the owner has switched on stays on screen: the window keeps the room it grew to
     * even after the cursor leaves, and only goes back to bare artwork when that section is
     * switched off again. The page owns that state and reports it through the music bridge.
     */
    var panelPinned: Boolean = false
        set(v) { if (field != v) { field = v; (parent as? DesktopHostView)?.refreshHoverGrow() } }

    /**
     * The cursor is over this window. Auto-mode frames use it, and a page can opt in by defining
     * `window.__tgHover` — the music player reveals its section switch and panel that way, so the
     * window is just the skin until you reach for it. The software cursor never reaches the page
     * as a real mouse event, so this is the only way a page can know.
     */
    var cursorOver: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            if (widget.style.chromeAuto) applyChrome()
            runCatching { webView?.evaluateJavascript("window.__tgHover && __tgHover($v)", null) }
        }

    /** Held on through a move/resize so an auto frame cannot vanish mid-drag if the cursor runs past the edge. */
    var pinChrome: Boolean = false
        set(v) { if (field != v) { field = v; if (widget.style.chromeAuto) applyChrome() } }

    /**
     * Auto mode must reveal the frame without moving the content. Shifting the body down by
     * [TITLE_H] on hover would relayout the window, and for a page that means a full reflow — twice
     * per frame, because the binocular layout draws every view once per eye. So an auto frame is
     * floated over the top of the content and only its visibility changes; a fixed frame still
     * reserves its strip exactly as before.
     */
    private fun applyChrome() {
        val auto = widget.style.chromeAuto
        val show = chromeVisible()
        titleBar.visibility = if (show) VISIBLE else GONE
        titleText.text = if (widget.onTop) "⬆ ${widget.title}" else widget.title
        titleText.setTextColor(ColorUtil.withAlpha(widget.style.textColor ?: theme.text, if (active) 1f else 0.85f))
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * theme.fontScale)
        closeBtn.setTextColor(theme.accent)
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f)
        settingsBtn.setTextColor(ColorUtil.withAlpha(theme.accent, 0.85f))
        settingsBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f)
        // Floating over the content, the bar needs to read as a layer above it, not part of it.
        titleBar.setBackgroundColor(ColorUtil.withAlpha(theme.accent,
            if (auto) 0.55f else if (active) 0.42f else if (mode == DesktopMode.HUD) 0.16f else 0.22f))
        val topM = if (show && !auto) TITLE_H else 0
        val lp = content.layoutParams as LayoutParams
        if (lp.topMargin != topM) { lp.topMargin = topM; content.requestLayout() }
        if (auto && show) { bringChildToFront(titleBar); bringChildToFront(handle) }
        // Nothing but the content when the cursor is elsewhere. Hit-testing still finds the corner,
        // so a window is never unreachable — coming near it is what brings the grip back.
        handle.visibility = if (auto && !show) GONE else VISIBLE
        handle.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(ColorUtil.withAlpha(theme.accent, if (show) 0.8f else 0.45f))
            cornerRadius = 3f
        }
    }

    private var texturedBg: TexturedPanelDrawable? = null
    private var texturedName: String? = null

    private fun applyStyle() {
        val base = widget.style.bgColor ?: theme.panel
        val corner = (widget.style.cornerRadius ?: theme.corner).toFloat()
        val strokeW = if (active) 2f else 1f
        val strokeC = ColorUtil.withAlpha(theme.accent, if (active) 1f else 0.55f)
        val tile = if (widget.style.bgColor == null) PanelTextures.tile(theme.texture) else null
        if (tile != null) {
            val d = texturedBg?.takeIf { texturedName == theme.texture } ?: TexturedPanelDrawable(tile).also { texturedBg = it; texturedName = theme.texture }
            d.cornerRadius = corner
            d.tintColor = 0                          // the grain is the colour; a custom bg_color switches back to flat
            d.setStroke(strokeW, strokeC)
            if (background !== d) background = d
        } else {
            bg.shape = GradientDrawable.RECTANGLE
            bg.cornerRadius = corner
            bg.setColor(base)
            bg.setStroke(strokeW.toInt(), strokeC)
            if (background !== bg) background = bg
        }
        alpha = widget.style.opacity
    }

    private fun textSize(base: Float) = (widget.style.fontSize ?: (base * theme.fontScale))
    private fun textColor() = widget.style.textColor ?: theme.text

    private fun showError(msg: String?) {
        errorLabel.text = msg.orEmpty()
        errorLabel.visibility = if (msg.isNullOrBlank()) GONE else VISIBLE
        if (!msg.isNullOrBlank()) bringChildToFront(errorLabel)
    }

    // ── content ────────────────────────────────────────────────────

    private fun buildContent() {
        content.removeAllViews()
        showError(null)
        contentGen++
        when (widget.type) {
            WidgetType.TEXT -> buildText()
            WidgetType.CLOCK -> buildClock()
            WidgetType.LIVE -> buildLive()
            WidgetType.IMAGE -> buildImage()
            WidgetType.VIDEO -> buildVideo()
            WidgetType.AUDIO -> buildAudio()
            WidgetType.PDF -> buildPdf()
            WidgetType.EPUB -> buildEpub()
            WidgetType.WEB -> buildWeb()
            WidgetType.APP -> buildApp()
            WidgetType.MODEL3D -> buildModel3d()
            WidgetType.MAP -> buildMap()
            WidgetType.TICKER -> buildTicker()
        }
    }

    private var tickerView: TickerView? = null

    private fun tickerItems(): List<String> {
        val raw = widget.content.ifBlank { "Fetching ${widget.source}…" }
        return raw.split('|', '\n', '•', '◆').map { it.trim().trimEnd('.', ';') }.filter { it.isNotBlank() }
    }

    private fun buildTicker() {
        val tv = TickerView(context)
        tickerView = tv
        tv.setStyle(textColor(), textSize(16f))
        tv.setItems(tickerItems())
        tv.setEco(eco)
        content.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun unbindContent() {
        contentGen++
        appSnapshotRunnable?.let { main.removeCallbacks(it) }; appSnapshotRunnable = null; lastAppSnapshot = null
        clockRunnable?.let { main.removeCallbacks(it) }; clockRunnable = null; clockFace = null
        tickerView = null
        audioProgress?.let { main.removeCallbacks(it) }; audioProgress = null
        runCatching { mediaPlayer?.stop() }; runCatching { mediaPlayer?.release() }; mediaPlayer = null; mediaPrepared = false
        runCatching { videoSurface?.release() }; videoSurface = null; textureView = null
        exitFullscreen(); stopHeading(); roadsCell = null; com.tapgem.app.core.bridge.NavCueBridge.forget(widget.id)
        ircListener?.let { com.tapgem.app.core.irc.IrcClient.removeListener(it) }; ircListener = null
        discordListener?.let { com.tapgem.app.core.irc.DiscordClient.removeListener(it) }; discordListener = null
        interpListener?.let { com.tapgem.app.core.livex.Interpreter.removeListener(it) }; interpListener = null
        tutorListener?.let { com.tapgem.app.core.livex.Tutor.removeListener(it) }; tutorListener = null
        musicListener?.let { com.tapgem.app.core.music.MusicPlayer.removeListener(it) }; musicListener = null
        musicUiListener?.let { com.tapgem.app.core.music.MusicBridgeEvents.removeListener(it) }; musicUiListener = null
        runCatching { webView?.stopLoading(); webView?.loadUrl("about:blank"); webView?.destroy() }; webView = null
        synchronized(this) { runCatching { pdfRenderer?.close() }; pdfRenderer = null }
        runCatching { pdfFd?.close() }; pdfFd = null
        epubChapters = emptyList()
        content.removeAllViews()
    }

    /**
     * Where keys go while a page is full screen: Chromium moves input handling from the
     * WebView (which then drops every event) into the fullscreen view it handed us — a
     * FrameLayout around the real view, and only that child takes key events.
     */
    private fun keyTarget(wv: WebView): View = (fullscreenView as? ViewGroup)?.getChildAt(0) ?: fullscreenView ?: wv

    /** The page left fullscreen on its own: take its view down. */
    private fun dropFullscreenView() {
        fullscreenView?.let { v -> runCatching { content.removeView(v) } }
        fullscreenView = null; fullscreenCallback = null
    }

    /** We are ending fullscreen (window closing, content rebuilt): tell Chromium, then take the view down. */
    private fun exitFullscreen() {
        val cb = fullscreenCallback ?: run { dropFullscreenView(); return }
        dropFullscreenView()
        runCatching { cb.onCustomViewHidden() }
    }

    private fun textView(size: Float): TextView = TextView(context).apply {
        setTextColor(textColor()); setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        setPadding(8, 6, 8, 6); setLineSpacing(2f, 1f)
    }

    private fun textBody(): String = when {
        widget.source.startsWith("prompt:") -> widget.content.ifBlank { "…" }
        widget.source.startsWith("file:") -> widget.content.ifBlank { "(empty file)" }
        else -> widget.source
    }

    private fun buildText() {
        val tv = textView(textSize(14f)).apply { text = textBody() }
        content.addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(tv) }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun buildLive() {
        val body = textView(textSize(14f)).apply { text = widget.content.ifBlank { "Fetching…" } }
        val foot = textView(9f).apply { setTextColor(ColorUtil.withAlpha(textColor(), 0.6f)); gravity = Gravity.END; setPadding(8, 0, 8, 3) }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(foot, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        updateLiveFooter(foot)
        content.tag = foot
    }

    private fun updateLiveFooter(foot: TextView) {
        foot.text = if (widget.updatedAt > 0) "updated " + SimpleDateFormat("h:mm a", Locale.US).format(Date(widget.updatedAt)) else "…"
    }

    private var clockFace: ClockFaceView? = null

    private fun clockConfig(): ClockFaceView.Config = ClockFaceView.configOf(
        widget, textColor = widget.style.textColor ?: theme.text, accent = theme.accent, fontScale = widget.style.fontSize?.let { it / 14f } ?: theme.fontScale,
        default24 = android.text.format.DateFormat.is24HourFormat(context))

    private fun buildClock() {
        val face = ClockFaceView(context).apply { setConfig(clockConfig()); ecoMode = eco }
        clockFace = face
        content.addView(face, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { setMargins(4, 2, 4, 2) })
        val r = object : Runnable {
            override fun run() { face.invalidate(); main.postDelayed(this, face.tickMs()) }
        }
        clockRunnable = r; main.post(r)
    }

    /** Style / hours / zones changed: reconfigure in place and re-time the ticks. */
    private fun applyClockState() {
        val face = clockFace ?: return
        face.setConfig(clockConfig())
        clockRunnable?.let { main.removeCallbacks(it); main.post(it) }
    }

    private fun buildImage() {
        val iv = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        content.addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        loadImageInto(iv, widget.source)
    }

    private fun loadImageInto(iv: ImageView, source: String) {
        val targetW = widget.w.coerceAtLeast(64); val targetH = widget.h.coerceAtLeast(64)
        val gen = contentGen
        Thread({
            var err: String? = null
            val bmp: Bitmap? = runCatching {
                val bytes: ByteArray = if (source.startsWith("http")) {
                    Net.http.newCall(Request.Builder().url(source).build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        r.body?.bytes() ?: ByteArray(0)
                    }
                } else File(source).readBytes()
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                while (opts.outWidth / (sample * 2) >= targetW * 2 && opts.outHeight / (sample * 2) >= targetH * 2) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: error("not an image")
            }.onFailure { Log.w(TAG, "image load failed: ${it.message}"); err = it.message }.getOrNull()
            main.post {
                if (gen != contentGen || !iv.isAttachedToWindow) return@post
                if (bmp != null) { iv.setImageBitmap(bmp); showError(null) }
                else { iv.setImageDrawable(null); showError("Couldn't load image${err?.let { ": $it" } ?: ""}") }
            }
        }, "tapgem-img").start()
    }

    private fun buildVideo() {
        val tex = TextureView(context)
        textureView = tex
        content.addView(tex, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val mp = MediaPlayer()
        mediaPlayer = mp
        tex.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (mediaPlayer !== mp) return
                runCatching {
                    val s = Surface(st); videoSurface = s
                    mp.setSurface(s)
                    mp.setDataSource(widget.source)
                    mp.setOnPreparedListener { mediaPrepared = true; applyPlayback(force = true) }
                    mp.setOnCompletionListener { if (widget.state["loop"] != "true") onStateChange?.invoke(widget.id, mapOf("playing" to "false")) }
                    mp.setOnErrorListener { _, what, extra -> Log.w(TAG, "video error $what/$extra"); main.post { showError("Can't play this video ($what)") }; true }
                    mp.prepareAsync()
                }.onFailure { Log.w(TAG, "video setup failed: ${it.message}"); showError("Can't open video") }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                runCatching { mp.setSurface(null) }
                runCatching { videoSurface?.release() }; videoSurface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        tex.setOnClickListener { togglePlaying() }
    }

    private fun buildAudio() {
        val col = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8, 4, 8, 4) }
        val btn = textView(20f).apply { text = "▶"; isClickable = true; gravity = Gravity.CENTER; setTextColor(theme.accent); setPadding(6, 0, 10, 0); contentDescription = "Play" }
        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val name = textView(textSize(13f)).apply { text = widget.title; maxLines = 1; setPadding(0, 0, 0, 0) }
        val time = textView(10f).apply { text = "0:00"; setPadding(0, 0, 0, 0); setTextColor(ColorUtil.withAlpha(textColor(), 0.7f)) }
        info.addView(name); info.addView(time)
        col.addView(btn); col.addView(info, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        content.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val mp = MediaPlayer()
        mediaPlayer = mp
        runCatching {
            mp.setDataSource(widget.source)
            mp.setOnPreparedListener { mediaPrepared = true; applyPlayback(force = true); btn.text = if (mp.isPlaying) "⏸" else "▶" }
            mp.setOnCompletionListener { btn.text = "▶"; if (widget.state["loop"] != "true") onStateChange?.invoke(widget.id, mapOf("playing" to "false")) }
            mp.setOnErrorListener { _, what, _ -> main.post { showError("Can't play this audio ($what)") }; true }
            mp.prepareAsync()
        }.onFailure { Log.w(TAG, "audio setup failed: ${it.message}"); showError("Can't open audio") }
        btn.setOnClickListener { togglePlaying(); btn.text = if (mp.isPlaying) "⏸" else "▶" }
        val prog = object : Runnable {
            override fun run() {
                runCatching { if (mediaPrepared) { val p = mp.currentPosition / 1000; val d = mp.duration / 1000; time.text = "%d:%02d / %d:%02d".format(p / 60, p % 60, d / 60, d % 60); btn.text = if (mp.isPlaying) "⏸" else "▶" } }
                main.postDelayed(this, 1_000L)
            }
        }
        audioProgress = prog; main.post(prog)
    }

    private fun togglePlaying() {
        val mp = mediaPlayer ?: return
        if (!mediaPrepared) return
        val playing = runCatching { if (mp.isPlaying) { mp.pause(); false } else { mp.start(); true } }.getOrDefault(false)
        onStateChange?.invoke(widget.id, mapOf("playing" to playing.toString()))
    }

    /** Apply playing / muted / loop / seek from state to the MediaPlayer. A seek fires once per nonce. */
    private fun applyPlayback(force: Boolean, old: Map<String, String> = emptyMap()) {
        val mp = mediaPlayer ?: return
        if (!mediaPrepared) return
        val st = widget.state
        runCatching {
            mp.isLooping = st["loop"] == "true"
            val v = if (st["muted"] == "true") 0f else 1f
            mp.setVolume(v, v)
            if (!force && old["seekNonce"] != st["seekNonce"] && st["seekNonce"] != null) {
                st["seekMs"]?.toLongOrNull()?.let { mp.seekTo(it.toInt().coerceIn(0, mp.duration)) }
                st["seekDelta"]?.toLongOrNull()?.let { mp.seekTo((mp.currentPosition + it).toInt().coerceIn(0, mp.duration)) }
            }
            val wantPlaying = st["playing"] != "false"
            if (wantPlaying && !mp.isPlaying) mp.start() else if (!wantPlaying && mp.isPlaying) mp.pause()
        }.onFailure { Log.w(TAG, "playback apply: ${it.message}") }
    }

    /** Activity went to the background: stop sound, remember to resume. */
    fun pauseForBackground() {
        stopHeading()
        val mp = mediaPlayer ?: return
        resumeOnForeground = runCatching { mediaPrepared && mp.isPlaying }.getOrDefault(false)
        if (resumeOnForeground) runCatching { mp.pause() }
        runCatching { webView?.onPause() }
    }

    fun resumeFromBackground() {
        runCatching { webView?.onResume() }
        if (isHud && webView != null) startHeading()
        if (!resumeOnForeground) return
        resumeOnForeground = false
        runCatching { if (mediaPrepared && widget.state["playing"] != "false") mediaPlayer?.start() }
    }

    private fun buildPdf() {
        // Dark paper: pages render inverted (white → black, ink → light) so a full-page PDF never
        // lights the whole waveguide.
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK)
            colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
                -0.88f, 0f, 0f, 0f, 235f,
                0f, -0.88f, 0f, 0f, 235f,
                0f, 0f, -0.88f, 0f, 235f,
                0f, 0f, 0f, 1f, 0f))
        }
        val foot = textView(10f).apply { gravity = Gravity.CENTER; setPadding(4, 1, 4, 1); setBackgroundColor(0x99000000.toInt()); setTextColor(Color.WHITE) }
        content.addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addView(foot, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        content.tag = foot
        runCatching {
            val fd = ParcelFileDescriptor.open(File(widget.source), ParcelFileDescriptor.MODE_READ_ONLY)
            pdfFd = fd
            val r = PdfRenderer(fd)
            if (r.pageCount <= 0) { r.close(); error("no pages") }
            pdfRenderer = r
            if (widget.state["pages"] != r.pageCount.toString()) onStateChange?.invoke(widget.id, mapOf("pages" to r.pageCount.toString()))
        }.onFailure { Log.w(TAG, "pdf open failed: ${it.message}"); showError("Couldn't open PDF"); return }
        renderPdfPage(iv, foot)
    }

    private fun renderPdfPage(iv: ImageView, foot: TextView) {
        val r = pdfRenderer ?: return
        val idx = (widget.state["page"]?.toIntOrNull() ?: 0).coerceIn(0, r.pageCount - 1)
        val w = widget.w.coerceAtLeast(64); val h = (widget.h - contentTop()).coerceAtLeast(64)
        val gen = contentGen
        Thread({
            val bmp = runCatching {
                synchronized(this) {
                    if (pdfRenderer !== r) return@runCatching null
                    r.openPage(idx).use { page ->
                        val scale = minOf(w.toFloat() / page.width, h.toFloat() / page.height) * 2f
                        val b = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); b
                    }
                }
            }.onFailure { Log.w(TAG, "pdf render: ${it.message}") }.getOrNull()
            main.post { if (gen == contentGen && iv.isAttachedToWindow && bmp != null) { iv.setImageBitmap(bmp); foot.text = "${idx + 1} / ${r.pageCount}" } }
        }, "tapgem-pdf").start()
    }

    private fun buildEpub() {
        val wv = newWebView(Kind.EPUB)
        content.addView(wv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val gen = contentGen
        val src = File(widget.source)
        Thread({
            val ch = EpubUnpacker.chapters(context, src)
            main.post {
                if (gen != contentGen || !isAttachedToWindow) return@post
                epubChapters = ch
                if (ch.isEmpty()) showError("No readable chapters")
                else if (widget.state["chapters"] != ch.size.toString()) onStateChange?.invoke(widget.id, mapOf("chapters" to ch.size.toString()))
                loadEpubChapter()
            }
        }, "tapgem-epub").start()
    }

    private fun readerCss(): String {
        val fg = ColorUtil.hex(textColor()); val accent = ColorUtil.hex(theme.accent)
        val px = (widget.style.fontSize ?: (15f * theme.fontScale)).roundToInt()
        return "<style id=\"tapgem-reader\">html,body{background:#000!important;color:$fg!important;font-size:${px}px!important;line-height:1.45!important;padding:8px!important;margin:0!important;max-width:100%!important}" +
            "*{color:inherit!important;background:transparent!important;max-width:100%!important}a{color:$accent!important}img,svg{max-width:100%!important;height:auto!important}</style>"
    }

    private fun loadEpubChapter() {
        val wv = webView ?: return
        if (epubChapters.isEmpty()) { wv.loadData("<body style='background:#000;color:#ccc'>&nbsp;</body>", "text/html", "utf-8"); return }
        val idx = (widget.state["chapter"]?.toIntOrNull() ?: 0).coerceIn(0, epubChapters.size - 1)
        val f = epubChapters[idx]
        val gen = contentGen
        Thread({
            val html = runCatching {
                val raw = f.readText()
                val i = raw.indexOf("</head>", ignoreCase = true)
                if (i >= 0) raw.substring(0, i) + readerCss() + raw.substring(i)
                else readerCss() + raw
            }.getOrElse { "<body style='background:#000;color:#ccc'>Couldn't read chapter</body>" }
            main.post {
                if (gen != contentGen || webView !== wv) return@post
                wv.loadDataWithBaseURL("file://" + (f.parentFile?.absolutePath ?: "/") + "/", html, "text/html", "utf-8", null)
            }
        }, "tapgem-chapter").start()
    }

    private fun buildWeb() {
        val wv = newWebView(Kind.WEB)
        content.addView(wv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wv.loadUrl(widget.source)
    }

    private fun buildApp() {
        val wv = newWebView(Kind.APP)
        content.addView(wv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        loadApp(wv)
    }

    /** (Re)read the app file and serve it through the instrumenter so its state can be frozen and thawed (see AppInstrumenter). */
    private fun loadApp(wv: WebView) {
        val f = java.io.File(widget.source)
        val html = runCatching { f.readText() }.getOrNull()
        if (html == null) { wv.loadUrl("file://" + widget.source); return }
        appHtmlHash = html.hashCode().toString(16) + ":" + html.length   // a snapshot only ever thaws into the same code
        wv.loadDataWithBaseURL("file://" + widget.source, com.tapgem.app.core.apps.AppInstrumenter.instrument(html), "text/html", "utf-8", null)
    }

    private fun buildModel3d() {
        val src = "file://" + widget.source
        val wv = newWebView(Kind.MODEL)
        content.addView(wv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wv.loadUrl("file:///android_asset/viewer3d.html?src=" + URLEncoder.encode(src, "UTF-8"))
    }

    private fun mapUrl(): String {
        val m = Regex("geo:([-0-9.]+),([-0-9.]+)(?:\\?q=(.*))?").find(widget.source)
        val lat = m?.groupValues?.get(1) ?: "0"; val lon = m?.groupValues?.get(2) ?: "0"
        val label = m?.groupValues?.getOrNull(3).orEmpty()
        val zoom = widget.state["zoom"] ?: "13"
        if (isHud) return "file:///android_asset/navhud.html?theme=${widget.state["theme"] ?: "auto"}&orient=${widget.state["orient"] ?: "auto"}" +
            "&mode=${widget.state["mode"] ?: "walking"}&units=${if (Router.usUnits()) "us" else "metric"}"
        return "file:///android_asset/map.html?lat=$lat&lon=$lon&zoom=$zoom&label=$label&style=dark"
    }

    /** The compact turn-by-turn HUD (arrow · info · heading-up minimap) instead of the slippy map. */
    private val isHud: Boolean get() = widget.type == WidgetType.MAP && widget.state["view"] == "hud" && widget.state["nav"] == "on"

    private var lastRouteJson: String? = null

    /** Push the stored route (if navigating) into the map page and show the current step. */
    private fun applyMapRoute(force: Boolean) {
        val wv = webView ?: return
        if (widget.state["nav"] == "on" && widget.content.isNotBlank()) {
            if (force || lastRouteJson != widget.content) {
                lastRouteJson = widget.content
                wv.evaluateJavascript("window.setRoute && setRoute(${JSONObject.quote(widget.content)})", null)
                wv.evaluateJavascript("window.setStep && setStep(${widget.state["step"]?.toIntOrNull() ?: 0})", null)
                // The user's chosen zoom outlives a reload / restart.
                if (isHud) applyHudSettings() else wv.evaluateJavascript("window.setZoom && setZoom(${widget.state["zoom"]?.toIntOrNull() ?: 17})", null)
                applyMapPosition()
                applyMapFlags()
            }
            if (isHud) startHeading() 
        } else {
            stopHeading()
            if (lastRouteJson != null) { lastRouteJson = null; wv.loadUrl(mapUrl()) }
        }
    }

    private fun applyMapPosition() {
        val wv = webView ?: return
        val p = widget.state["pos"]?.split(',') ?: return
        if (p.size < 2) return
        val acc = p.getOrNull(2)?.toIntOrNull() ?: 0
        if (isHud) {
            val vel = widget.state["vel"]?.split(',').orEmpty()
            val speed = vel.getOrNull(0)?.toDoubleOrNull()?.let { "%.1f".format(Locale.US, it) } ?: "null"
            val course = vel.getOrNull(1)?.toDoubleOrNull()?.let { "%.0f".format(Locale.US, it) } ?: "null"
            wv.evaluateJavascript("window.setPosition && setPosition(${p[0]}, ${p[1]}, $acc, ${jsStr(widget.state["posSrc"] ?: "")}, $speed, $course)", null)
            p[0].toDoubleOrNull()?.let { lat -> p[1].toDoubleOrNull()?.let { lon -> fetchRoads(lat, lon) } }
        } else wv.evaluateJavascript("window.setPosition && setPosition(${p[0]}, ${p[1]}, $acc, false)", null)
    }

    /** Off-route / rerouted / arrived, for the HUD's status line. */
    private fun applyMapFlags() {
        if (!isHud) return
        val wv = webView ?: return
        val steps = Router.Route.fromJson(widget.content)?.steps?.size ?: 0
        val step = widget.state["step"]?.toIntOrNull() ?: 0
        val flags = JSONObject().put("offRoute", (widget.state["offRoute"]?.toIntOrNull() ?: 0) > 0)
            .put("rerouted", widget.state["rerouted"]?.toLongOrNull()?.let { System.currentTimeMillis() - it < 20_000L } ?: false)
            .put("rerouting", widget.state["rerouting"] == "1")
            .put("arrived", widget.state["arrived"] == "1")
        wv.evaluateJavascript("window.setFlags && setFlags(${flags})", null)
    }

    private fun applyHudSettings() {
        val wv = webView ?: return
        pushHudEnv()
        wv.evaluateJavascript("window.setUnits && setUnits(${jsStr(widget.state["units"] ?: (if (Router.usUnits()) "us" else "metric"))})", null)
        wv.evaluateJavascript("window.setTheme && setTheme(${jsStr(widget.state["theme"]?.ifBlank { null } ?: "auto")})", null)
        wv.evaluateJavascript("window.setOrientation && setOrientation(${jsStr(widget.state["orient"]?.ifBlank { null } ?: "auto")})", null)
        wv.evaluateJavascript("window.setZoom && setZoom(${jsStr(widget.state["mzoom"]?.ifBlank { null } ?: "auto")})", null)
        wv.evaluateJavascript("window.setArrowSize && setArrowSize(${jsStr(widget.state["arrow"]?.ifBlank { null } ?: "normal")})", null)
    }

    private fun pushHudEnv() {
        val wv = webView ?: return
        val env = JSONObject().put("eco", eco).put("hour12", !android.text.format.DateFormat.is24HourFormat(context))
            .put("active", isAttachedToWindow)
        Router.Route.fromJson(widget.content)?.drivingSide?.let { env.put("drivingSide", it) }
        wv.evaluateJavascript("window.setEnv && setEnv($env)", null)
    }

    // ── HUD: compass heading and the streets around the wearer ─────
    private var headingOn = false
    private val headingListener = com.tapgem.app.core.location.HeadingSource.Listener { h ->
        val wv = webView ?: return@Listener
        if (!isHud || !isAttachedToWindow) return@Listener
        wv.evaluateJavascript("window.setHeading && setHeading(${"%.1f".format(Locale.US, h.deg)}, ${h.reliable})", null)
    }
    private fun startHeading() {
        if (headingOn) return
        headingOn = true
        com.tapgem.app.core.location.HeadingSource.acquire(context, headingListener)
    }
    private fun stopHeading() {
        if (!headingOn) return
        headingOn = false
        com.tapgem.app.core.location.HeadingSource.release(headingListener)
    }

    private var roadsCell: String? = null
    /** Fetch the surrounding streets once per ~250 m grid cell; cached cells are pushed at once. */
    private fun fetchRoads(lat: Double, lon: Double) {
        val cell = com.tapgem.app.core.network.RoadsSource.cellOf(lat, lon)
        if (cell == roadsCell) return
        com.tapgem.app.core.network.RoadsSource.cached(lat, lon)?.let { roadsCell = cell; pushRoads(it); return }
        val gen = contentGen
        Thread({
            val json = com.tapgem.app.core.network.RoadsSource.around(lat, lon) ?: return@Thread
            main.post { if (gen == contentGen && isHud && isAttachedToWindow) { roadsCell = cell; pushRoads(json) } }
        }, "tapgem-roads").start()
    }
    private fun pushRoads(json: String) { webView?.evaluateJavascript("window.setRoads && setRoads(${JSONObject.quote(json)})", null) }

    private fun buildMap() {
        val wv = newWebView(Kind.MAP)
        content.addView(wv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wv.loadUrl(mapUrl())
    }

    private enum class Kind { WEB, APP, EPUB, MODEL, MAP }

    /**
     * A WebView that never summons the soft keyboard. The IME only ever
     * shows for the view that holds input focus, so this view simply never
     * takes it: Chromium's showSoftInput() is rejected by the framework.
     * Keys and taps are still delivered directly (dispatchKeyEvent /
     * dispatchTouchEvent), and JS value-setting never needed an IME.
     */
    private class NoImeWebView(context: Context) : WebView(context) {
        init { isFocusable = false; isFocusableInTouchMode = false }
        override fun requestFocus(direction: Int, previouslyFocusedRect: android.graphics.Rect?): Boolean = false
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            outAttrs.inputType = InputType.TYPE_NULL
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            return null
        }
        override fun onCheckIsTextEditor(): Boolean = false
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(kind: Kind): WebView {
        val wv = NoImeWebView(context)
        webView = wv
        with(wv.settings) {
            javaScriptEnabled = kind != Kind.EPUB
            domStorageEnabled = kind == Kind.WEB || kind == Kind.APP
            allowFileAccess = kind == Kind.APP || kind == Kind.EPUB || kind == Kind.MODEL
            allowContentAccess = false
            @Suppress("DEPRECATION") run {
                allowFileAccessFromFileURLs = kind == Kind.MODEL   // viewer3d.html fetches the model over file://
                allowUniversalAccessFromFileURLs = false
            }
            mediaPlaybackRequiresUserGesture = eco && kind == Kind.WEB
            loadWithOverviewMode = kind == Kind.WEB; useWideViewPort = kind == Kind.WEB
            builtInZoomControls = false; displayZoomControls = false
            setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            setGeolocationEnabled(kind == Kind.WEB)   // granted per origin below (Google Maps, Radio Garden)
            textZoom = if (kind == Kind.WEB) (100 * theme.fontScale).roundToInt().coerceIn(60, 200) else 100
            // Dark, always: a full-screen white page is the one thing that reboots the
            // unplugged X3 (the waveguide LEDs at full white). Sites with their own dark theme
            // use it (the app runs in night mode); the rest get Chromium's darkening.
            if (kind == Kind.WEB || kind == Kind.APP) {
                if (android.os.Build.VERSION.SDK_INT >= 33) isAlgorithmicDarkeningAllowed = true
                else @Suppress("DEPRECATION") { forceDark = WebSettings.FORCE_DARK_ON }
            }
        }
        wv.setBackgroundColor(Color.TRANSPARENT)
        // The binocular layout draws every view twice per frame. Drawn directly, Chromium's
        // draw functor sees two different transforms each frame, treats that as damage and
        // requests another frame — a 60 fps loop for a static page. A hardware layer is
        // rasterised once per content change and simply blitted twice.
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.isVerticalScrollBarEnabled = false; wv.isHorizontalScrollBarEnabled = false
        wv.webChromeClient = object : WebChromeClient() {
            /**
             * A page's fullscreen request (YouTube's ⛶, a video's own control) fills the
             * window, not the display: Chromium hands us the fullscreen view and we lay it
             * over the page. Only web pages get it — apps never go fullscreen.
             */
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (kind != Kind.WEB || view == null || webView !== wv) { callback?.onCustomViewHidden(); return }
                exitFullscreen()
                fullscreenView = view; fullscreenCallback = callback
                view.setBackgroundColor(Color.BLACK)
                view.setLayerType(View.LAYER_TYPE_HARDWARE, null)   // drawn twice per frame like the WebView (see above)
                content.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            override fun onHideCustomView() { dropFullscreenView() }
            /** "Leave this page?" — a HUD has nobody to ask; navigation always proceeds. */
            override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean { result?.confirm(); return true }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
                val host = runCatching { java.net.URL(origin ?: "").host.lowercase(Locale.US) }.getOrDefault("")
                val allow = GEO_ORIGINS.any { host == it || host.endsWith(".$it") }
                callback?.invoke(origin, allow, false)
            }
            /**
             * DRM (EME) needs the protected-media-id permission or every key system is
             * refused. Grant just that for https pages; never camera or microphone. The
             * X3 Pro ships only the ClearKey plugin — Widevine sites (Spotify full tracks,
             * Netflix) still fail, but this stops us being the reason.
             */
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                val https = request.origin?.scheme?.equals("https", ignoreCase = true) == true
                val drm = request.resources.filter { it == android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (kind == Kind.WEB && https && drm.isNotEmpty()) request.grant(drm.toTypedArray()) else request.deny()
            }
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                HudStateBridge.notice(message?.take(80) ?: ""); result?.confirm(); return true
            }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean { result?.confirm(); return true }
            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean { result?.cancel(); return true }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme?.lowercase(Locale.US)
                return when (kind) {
                    Kind.WEB -> scheme != "http" && scheme != "https"   // intent://, market://… stay inside the widget
                    Kind.APP -> scheme != "file"                          // apps never navigate away
                    Kind.EPUB, Kind.MODEL, Kind.MAP -> true
                }
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                pageLoading = true; lastHttpStatus = 0
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                if (request.isForMainFrame) lastHttpStatus = errorResponse.statusCode
            }
            override fun onPageFinished(view: WebView, url: String?) {
                pageLoading = false; navSeq++
                if (kind == Kind.APP) { ecoCssApplied = false; applyEcoCss(); onAppLoaded(view) }
                // Google Maps: force-dark leaves the (blob-image) tiles white; invert them into a dark map.
                if (kind == Kind.WEB && url != null && url.contains("google.") && url.contains("/maps")) {
                    view.evaluateJavascript(GOOGLE_MAPS_DARK_JS, null)
                }
                // YouTube Music: its phone layout leaves the video 10px tall in a window this short.
                if (kind == Kind.WEB && url != null && url.contains("://music.youtube.com")) {
                    view.evaluateJavascript(YT_MUSIC_LAYOUT_JS, null)
                }
                if (kind == Kind.MAP) applyMapRoute(force = true)
                val waiters = ArrayList(loadWaiters); loadWaiters.clear()
                waiters.forEach { runCatching { it() } }
                if (kind == Kind.WEB && !url.isNullOrBlank() && url != "about:blank") {
                    showError(null)
                    lastNavigatedUrl = url
                    runCatching { java.net.URL(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
                        ?.let { onNavigated?.invoke(widget.id, url, it.removePrefix("www.").removePrefix("m.")) }
                }
                runCatching { hideIme() }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError("Couldn't load ${request.url.host ?: "page"}: ${error.description}")
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                if (kind != Kind.WEB || request.isForMainFrame) return null
                return com.tapgem.app.core.network.WebAdBlocker.intercept(request.url?.toString())
            }
        }
        if (kind == Kind.APP) wv.addJavascriptInterface(JsBridge(), "TapGem")
        if (kind == Kind.APP && widget.source.endsWith(com.tapgem.app.core.tools.LiveApps.INTERPRETER)) {
            wv.addJavascriptInterface(InterpreterBridge(), "TapGemInterp")
            interpListener?.let { com.tapgem.app.core.livex.Interpreter.removeListener(it) }
            val l = com.tapgem.app.core.livex.Interpreter.Listener { o -> val js = "window.__lxEvent && __lxEvent(${JSONObject.quote(o.toString())})"; main.post { if (webView === wv) wv.evaluateJavascript(js, null) } }
            interpListener = l; com.tapgem.app.core.livex.Interpreter.addListener(l)
        }
        if (kind == Kind.APP && widget.source.endsWith(com.tapgem.app.core.tools.LiveApps.TUTOR)) {
            wv.addJavascriptInterface(TutorBridge(), "TapGemTutor")
            tutorListener?.let { com.tapgem.app.core.livex.Tutor.removeListener(it) }
            val l = com.tapgem.app.core.livex.Tutor.Listener { o -> val js = "window.__lxEvent && __lxEvent(${JSONObject.quote(o.toString())})"; main.post { if (webView === wv) wv.evaluateJavascript(js, null) } }
            tutorListener = l; com.tapgem.app.core.livex.Tutor.addListener(l)
        }
        if (kind == Kind.APP && widget.source.endsWith(com.tapgem.app.core.tools.DiscordTool.APP_FILE)) {
            wv.addJavascriptInterface(DiscordBridge(), "TapGemDiscord")
            discordListener?.let { com.tapgem.app.core.irc.DiscordClient.removeListener(it) }
            val l = com.tapgem.app.core.irc.DiscordClient.Listener { o ->
                val js = "window.__dcEvent && __dcEvent(${JSONObject.quote(o.toString())})"
                main.post { if (webView === wv) wv.evaluateJavascript(js, null) }
            }
            discordListener = l; com.tapgem.app.core.irc.DiscordClient.addListener(l)
        }
        if (kind == Kind.APP && widget.source.endsWith(com.tapgem.app.core.tools.IrcTool.APP_FILE)) {
            wv.addJavascriptInterface(IrcBridge(), "TapGemIrc")
            ircListener?.let { com.tapgem.app.core.irc.IrcClient.removeListener(it) }
            val l = com.tapgem.app.core.irc.IrcClient.Listener { o ->
                val js = "window.__ircEvent && __ircEvent(${JSONObject.quote(o.toString())})"
                main.post { if (webView === wv) wv.evaluateJavascript(js, null) }
            }
            ircListener = l; com.tapgem.app.core.irc.IrcClient.addListener(l)
        }
        if (kind == Kind.APP && (widget.source.endsWith(com.tapgem.app.core.tools.LiveApps.MUSIC) ||
                widget.source.endsWith(com.tapgem.app.core.tools.LiveApps.MUSIC_SKINS))) {
            wv.addJavascriptInterface(MusicBridge(), "TapGemMusic")
            musicListener?.let { com.tapgem.app.core.music.MusicPlayer.removeListener(it) }
            val ml = com.tapgem.app.core.music.MusicPlayer.Listener { o ->
                val js = "window.__muEvent && __muEvent(${JSONObject.quote(o.toString())})"
                main.post { if (webView === wv) wv.evaluateJavascript(js, null) }
            }
            musicListener = ml; com.tapgem.app.core.music.MusicPlayer.addListener(ml)
            musicUiListener?.let { com.tapgem.app.core.music.MusicBridgeEvents.removeListener(it) }
            val ul = com.tapgem.app.core.music.MusicBridgeEvents.Listener { o ->
                val js = "window.__muEvent && __muEvent(${JSONObject.quote(o.toString())})"
                main.post { if (webView === wv) wv.evaluateJavascript(js, null) }
            }
            musicUiListener = ul; com.tapgem.app.core.music.MusicBridgeEvents.addListener(ul)
        }
        if (kind == Kind.MAP) wv.addJavascriptInterface(NavBridge(), "TapGemNav")
        return wv
    }

    // ── app state freezer ──────────────────────────────────────────
    //
    // Vibe-coded apps keep their state in top-level `let`/`const` variables and
    // redraw through zero-argument render functions. Nothing forces them to
    // persist anything, so TapGem does it for them: the page is scanned for
    // those declarations, their values are snapshotted into the widget's state
    // every few seconds (and right before a bookmark), and after a reload the
    // values are put back and the renderers called — the checkers board comes
    // back mid-game whether or not the app's author thought of it.

    private var appSnapshotRunnable: Runnable? = null
    private var lastAppSnapshot: String? = null
    private var appHtmlHash: String = ""

    private fun onAppLoaded(wv: WebView) {
        val gen = contentGen
        val saved = widget.state[APP_SNAPSHOT_KEY]?.takeIf { widget.state[APP_SNAPSHOT_SRC] == appHtmlHash }
        if (!saved.isNullOrBlank()) {
            // Let the app's own init finish first, then overwrite it with where the user left off.
            main.postDelayed({
                if (gen != contentGen || webView !== wv) return@postDelayed
                wv.evaluateJavascript("window.__tgState && __tgState.restore(${JSONObject.quote(saved)})") { r -> Log.i(TAG, "app state restored: ${r?.take(120)}") }
                lastAppSnapshot = saved
            }, 350L)
        }
        appSnapshotRunnable?.let { main.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                if (gen != contentGen || webView !== wv) return
                if (!covered) snapshotAppState(null)
                main.postDelayed(this, APP_SNAPSHOT_MS)
            }
        }
        appSnapshotRunnable = tick
        main.postDelayed(tick, APP_SNAPSHOT_MS)
    }

    /** Snapshot now (bookmark about to be taken, or the periodic tick); [done] runs once the state is stored. */
    fun snapshotAppState(done: (() -> Unit)?) {
        val wv = webView
        if (widget.type != WidgetType.APP || wv == null) { done?.invoke(); return }
        wv.evaluateJavascript("(function(){try{return window.__tgState?__tgState.snapshot():null}catch(e){return null}})()") { raw ->
            val json = (decodeJs(raw) as? String)?.takeIf { it.isNotBlank() && it != "null" && it.length < APP_SNAPSHOT_MAX }
            if (json != null && json != lastAppSnapshot) {
                lastAppSnapshot = json
                onStateChange?.invoke(widget.id, mapOf(APP_SNAPSHOT_KEY to json, APP_SNAPSHOT_SRC to appHtmlHash))
            }
            done?.invoke()
        }
    }

    private fun hideIme() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    /** Tiny bridge exposed to vibe-coded apps as window.TapGem. */
    private var ircListener: com.tapgem.app.core.irc.IrcClient.Listener? = null
    private var discordListener: com.tapgem.app.core.irc.DiscordClient.Listener? = null
    private var interpListener: com.tapgem.app.core.livex.Interpreter.Listener? = null
    private var tutorListener: com.tapgem.app.core.livex.Tutor.Listener? = null
    private var musicListener: com.tapgem.app.core.music.MusicPlayer.Listener? = null
    private var musicUiListener: com.tapgem.app.core.music.MusicBridgeEvents.Listener? = null

    inner class InterpreterBridge {
        @JavascriptInterface fun snapshot(since: String): String = com.tapgem.app.core.livex.Interpreter.snapshot(since.toLongOrNull() ?: 0L).toString()
        @JavascriptInterface fun cmd(json: String): String {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return "bad json"; val i = com.tapgem.app.core.livex.Interpreter
            return when (o.optString("op")) {
                "start" -> { i.configure(o.optString("mode").ifBlank { null }, o.optString("mine").ifBlank { null }, o.optString("theirs").ifBlank { null }); if (com.tapgem.app.core.livex.MicOwner.whenMicFree { i.start() }) "ok" else "after assistant" }
                "stop" -> { i.stop(); "ok" }
                "set" -> { i.configure(o.optString("mode").ifBlank { null }, o.optString("mine").ifBlank { null }, o.optString("theirs").ifBlank { null }); "ok" }
                "clear" -> { i.clear(); "ok" }
                else -> "unknown op"
            }
        }
    }
    /**
     * The page draws the skin and the spectrum; the audio itself lives in [MusicPlayer], so this
     * carries commands one way and pulls state/FFT on demand. Nothing is pushed per frame: a
     * throttled or hidden window simply stops asking.
     */
    inner class MusicBridge {
        @JavascriptInterface fun state(): String = com.tapgem.app.core.music.MusicPlayer.state().toString()
        @JavascriptInterface fun library(query: String): String =
            org.json.JSONArray().also { a -> com.tapgem.app.core.music.MusicPlayer.search(query.ifBlank { null }).forEach { a.put(it.json()) } }.toString()
        /** Spectrum for the visualiser, as comma-separated magnitudes — cheap to parse, no JSON churn. */
        @JavascriptInterface fun fft(bands: Int): String =
            com.tapgem.app.core.music.MusicPlayer.fft(bands.coerceIn(8, 128)).joinToString(",") { "%.3f".format(it) }
        @JavascriptInterface fun defaultSkin(): String = com.tapgem.app.core.music.SkinStore.lastWorn()
        @JavascriptInterface fun cmd(json: String): String {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return "bad json"
            val m = com.tapgem.app.core.music.MusicPlayer
            return when (o.optString("op")) {
                "play" -> {
                    val id = o.optLong("id", -1L)
                    if (id > 0) m.byId(id)?.let { m.play(listOf(it)) } ?: "no such track"
                    else m.play(m.search(o.optString("query").ifBlank { null }))
                }
                "playAll" -> m.play(m.library())
                // The page telling us a section is switched on, so the window holds its size.
                "panel" -> o.optBoolean("open").let { on -> main.post { panelPinned = on }; "ok" }
                // Tapping a row in the Queue tab means "go to this one", not "throw the rest away".
                // The plain play op replaces the queue with a single track, which would empty the
                // very list being tapped, so jumping keeps the queue and moves the index.
                "jump" -> m.queueSnapshot().let { q ->
                    val i = o.optInt("index", -1)
                    if (i in q.indices) m.play(q, i) else "not in the queue"
                }
                "toggle" -> m.toggle()
                "pause" -> m.pause()
                "resume" -> m.resume()
                "next" -> m.next()
                "previous" -> m.previous()
                "stop" -> m.stop()
                "seek" -> { m.seekTo(o.optInt("ms")); "ok" }
                "volume" -> m.volume(o.optInt("level", 50))
                "shuffle" -> m.setShuffle(o.optBoolean("value", !m.shuffle))
                "repeat" -> m.setRepeat(o.optString("value", "all"))
                "skins" -> com.tapgem.app.core.music.SkinStore.searchJson(o.optString("query").ifBlank { null }).toString()
                // Tell every open page, not just the caller: a skin tapped in the gallery has to reach
                // the player window too, which is the whole point of tapping it.
                "skin" -> com.tapgem.app.core.music.SkinStore.installedJson(o.optString("id"))
                    .also { if (it.optBoolean("ok")) com.tapgem.app.core.music.MusicBridgeEvents.emitSkin(it) }
                    .toString()
                else -> "unknown op"
            }
        }
    }
    inner class TutorBridge {
        @JavascriptInterface fun snapshot(since: String): String = com.tapgem.app.core.livex.Tutor.snapshot(since.toLongOrNull() ?: 0L).toString()
        @JavascriptInterface fun cmd(json: String): String {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return "bad json"; val t = com.tapgem.app.core.livex.Tutor
            return when (o.optString("op")) {
                "start" -> { t.configure(o.optString("language").ifBlank { null }, o.optString("native").ifBlank { null }, o.optString("level").ifBlank { null }, o.optString("scenario").ifBlank { null }); if (com.tapgem.app.core.livex.MicOwner.whenMicFree { t.start() }) "ok" else "after assistant" }
                "stop" -> { t.stop(); "ok" }
                "set" -> { t.configure(o.optString("language").ifBlank { null }, o.optString("native").ifBlank { null }, o.optString("level").ifBlank { null }, o.optString("scenario").ifBlank { null }); "ok" }
                "say" -> { t.say(o.optString("text")); "ok" }
                else -> "unknown op"
            }
        }
    }

    /** The Discord page's window onto the shared session; credentials pass straight through, never logged. */
    inner class DiscordBridge {
        @JavascriptInterface fun snapshot(since: String): String = com.tapgem.app.core.irc.DiscordClient.snapshot(since.toLongOrNull() ?: 0L).toString()
        @JavascriptInterface fun cmd(json: String): String {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return "bad json"
            val c = com.tapgem.app.core.irc.DiscordClient
            return when (o.optString("op")) {
                "token" -> { c.useToken(o.optString("token")); "ok" }
                "connect" -> { c.connect(); "ok" }
                "disconnect" -> { c.disconnect(); "ok" }
                "logout" -> { c.logout(); "ok" }
                "current" -> { val id = o.optString("channel"); c.setCurrent(id); Thread { c.history(id) }.start(); "ok" }
                "say" -> { Thread { c.say(o.optString("channel"), o.optString("text")) }.start(); "ok" }
                "confirm" -> { val p = c.pending ?: return "nothing"; c.pending = null; Thread { c.say(p.first, p.second) }.start(); "ok" }
                "cancel" -> { c.pending = null; "ok" }
                else -> "unknown op"
            }
        }
    }

    /** The IRC page's window onto the shared connection (see IrcClient / IrcTool). */
    inner class IrcBridge {
        @JavascriptInterface fun snapshot(since: String): String = com.tapgem.app.core.irc.IrcClient.snapshot(since.toLongOrNull() ?: 0L).toString()
        @JavascriptInterface fun cmd(json: String): String {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return "bad json"
            val c = com.tapgem.app.core.irc.IrcClient
            return when (o.optString("op")) {
                "connect" -> { val srv = c.serverFor(o.optString("server")) ?: return "unknown server"; c.connect(srv, o.optString("nick").ifBlank { null }); "ok" }
                "disconnect" -> { c.disconnect(); "ok" }
                "join" -> { c.join(o.optString("channel")); "ok" }
                "part" -> { c.part(o.optString("channel")); "ok" }
                "nick" -> { c.changeNick(o.optString("nick")); "ok" }
                "say" -> if (c.say(o.optString("target"), o.optString("text"))) "ok" else "not connected"
                "current" -> { c.setCurrent(o.optString("target")); "ok" }
                "confirm" -> { val p = c.pending ?: return "nothing"; c.pending = null; if (c.say(p.first, p.second)) "ok" else "not connected" }
                "cancel" -> { c.pending = null; "ok" }
                "members" -> JSONArray(c.membersOf(o.optString("target"))).toString()
                "raw" -> { c.raw(o.optString("line")); "ok" }
                else -> "unknown op"
            }
        }
    }

    inner class JsBridge {
        @JavascriptInterface fun notify(msg: String) { HudStateBridge.notice(msg.take(80)) }
        @JavascriptInterface fun setTitle(t: String) { main.post { titleText.text = t.take(32) } }
        @JavascriptInterface fun save(key: String, value: String) {
            val k = "app." + key.take(32).replace(Regex("[^A-Za-z0-9_.-]"), "_")
            main.post { onStateChange?.invoke(widget.id, mapOf(k to value.take(4000))) }
        }
        /** True on battery: apps should animate slower (or not at all). */
        @JavascriptInterface fun eco(): Boolean = eco
        @JavascriptInterface fun load(key: String): String {
            val k = "app." + key.take(32).replace(Regex("[^A-Za-z0-9_.-]"), "_")
            return widget.state[k].orEmpty()
        }
    }

    /**
     * Events from the navigation HUD page (navhud.html): approach phases become a HUD
     * notice and, while a voice session is open, a spoken cue; the minimap's zoom
     * choice is persisted so it survives a reload; a tap on the arrow re-reads the step.
     */
    inner class NavBridge {
        @JavascriptInterface fun event(json: String) {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return
            when (o.optString("t")) {
                "phase" -> {
                    val text = o.optString("text").take(120)
                    val cue = when (o.optString("phase")) {
                        "near" -> if (text.isNotBlank()) "In ${o.optString("distText").ifBlank { Router.distance(o.optDouble("distM", 0.0)) }}, $text" else ""
                        "now" -> text.ifBlank { "Turn now" }
                        "arrived" -> text.ifBlank { "You've arrived" }
                        else -> ""
                    }
                    if (cue.isNotBlank()) { HudStateBridge.notice(cue.take(80)); com.tapgem.app.core.bridge.NavCueBridge.cue(cue) }
                }
                "zoom" -> main.post {
                    val auto = o.optBoolean("auto"); val preset = if (auto) "" else o.optString("preset")
                    val pending = !widget.state["mzoomCmd"].isNullOrBlank()
                    if ((pending || !auto) && (pending || widget.state["mzoom"].orEmpty() != preset)) onStateChange?.invoke(widget.id, mapOf("mzoom" to preset, "mzoomCmd" to ""))
                }
                "tap" -> if (o.optString("on") == "arrow") {
                    val text = o.optString("text").ifBlank { Router.Route.fromJson(widget.content)?.steps?.getOrNull(widget.state["step"]?.toIntOrNull() ?: 0)?.text.orEmpty() }
                    val dist = o.optString("distText")
                    if (text.isNotBlank()) { val cue = if (dist.isNotBlank()) "In $dist, $text" else text; HudStateBridge.notice(cue.take(80)); com.tapgem.app.core.bridge.NavCueBridge.cue(cue) }
                }
                "state" -> com.tapgem.app.core.bridge.NavCueBridge.state(widget.id, (o.optJSONObject("s") ?: o).toString())
            }
        }
    }

    // ── state application (no rebuild) ─────────────────────────────

    private fun applyState(old: Map<String, String>, new: Map<String, String>) {
        when (widget.type) {
            WidgetType.TEXT -> ((content.getChildAt(0) as? ScrollView)?.getChildAt(0) as? TextView)?.text = textBody()
            WidgetType.LIVE -> {
                val col = content.getChildAt(0) as? LinearLayout
                ((col?.getChildAt(0) as? ScrollView)?.getChildAt(0) as? TextView)?.text = widget.content.ifBlank { "Fetching…" }
                (content.tag as? TextView)?.let { updateLiveFooter(it) }
            }
            WidgetType.VIDEO, WidgetType.AUDIO -> applyPlayback(force = false, old = old)
            WidgetType.PDF -> if (old["page"] != new["page"]) {
                (content.getChildAt(0) as? ImageView)?.let { iv -> (content.tag as? TextView)?.let { renderPdfPage(iv, it) } }
            }
            WidgetType.EPUB -> if (old["chapter"] != new["chapter"]) loadEpubChapter()
            WidgetType.WEB, WidgetType.MODEL3D -> if (old["reload"] != new["reload"]) webView?.reload()
            // An app reload re-reads its file (the page was served as data, so WebView.reload() would replay the old bytes).
            WidgetType.APP -> if (old["reload"] != new["reload"]) webView?.let { loadApp(it) }
            WidgetType.MAP -> {
                val wv = webView ?: return
                if (old["reload"] != new["reload"] || old["view"] != new["view"]) { lastRouteJson = null; roadsCell = null; stopHeading(); wv.loadUrl(mapUrl()); return }
                if (old["nav"] != new["nav"] || (new["nav"] == "on" && lastRouteJson != widget.content)) { applyMapRoute(force = true); return }
                if (old["step"] != new["step"] && new["nav"] == "on") { wv.evaluateJavascript("window.setStep && setStep(${new["step"]?.toIntOrNull() ?: 0})", null); applyMapFlags() }
                if (old["pos"] != new["pos"] || old["vel"] != new["vel"]) applyMapPosition()
                if (old["offRoute"] != new["offRoute"] || old["rerouted"] != new["rerouted"] || old["rerouting"] != new["rerouting"] || old["arrived"] != new["arrived"]) applyMapFlags()
                if (isHud) {
                    if (old["theme"] != new["theme"] || old["orient"] != new["orient"] || old["units"] != new["units"] || old["arrow"] != new["arrow"]) applyHudSettings()
                    if (old["mzoom"] != new["mzoom"] && new["mzoomCmd"].isNullOrBlank()) wv.evaluateJavascript("window.setZoom && setZoom(${jsStr(new["mzoom"]?.ifBlank { null } ?: "auto")})", null)
                    // "zoom in / out / auto": a one-shot command; the page answers with a zoom event that persists the preset.
                    if (old["mzoomCmd"] != new["mzoomCmd"] && !new["mzoomCmd"].isNullOrBlank()) wv.evaluateJavascript("window.setZoom && setZoom(${jsStr(new["mzoomCmd"]!!.substringBefore(':'))})", null)
                }
                if (old["zoom"] != new["zoom"]) wv.evaluateJavascript("window.setZoom && setZoom(${new["zoom"]?.toIntOrNull() ?: 13})", null)
                if (old["panNonce"] != new["panNonce"]) wv.evaluateJavascript("window.panBy && panBy(${jsStr(new["pan"] ?: "center")})", null)
            }
            WidgetType.IMAGE -> if (old["reload"] != new["reload"]) (content.getChildAt(0) as? ImageView)?.let { loadImageInto(it, widget.source) }
            WidgetType.TICKER -> tickerView?.setItems(tickerItems())
            WidgetType.CLOCK -> applyClockState()
        }
    }

    // ── web tool commands ──────────────────────────────────────────

    /** Draw the live video frame into a software canvas (thumbnails). */
    fun drawLiveFrame(canvas: Canvas) {
        val tex = textureView ?: return
        val bmp = runCatching { tex.getBitmap(tex.width.coerceAtLeast(1), tex.height.coerceAtLeast(1)) }.getOrNull() ?: return
        canvas.drawBitmap(bmp, (tex.left + content.left).toFloat(), (tex.top + content.top).toFloat(), null)
        bmp.recycle()
    }

    /** Runs on the main thread; completes [done] exactly once. */
    fun runWebCommand(cmd: WebCommandBus.Command, done: (String) -> Unit) {
        val wv = webView
        if (wv == null) { done("\"${widget.title}\" has no page to operate."); return }
        val jsOn = wv.settings.javaScriptEnabled
        val title = widget.title
        when (cmd.action) {
            "back" -> if (fullscreenView != null) { exitFullscreen(); main.postDelayed({ finish(wv, "Left full screen.", done) }, 400L) }
                else if (wv.canGoBack()) { pageLoading = true; wv.goBack(); awaitLoad(10_000L) { main.postDelayed({ finish(wv, "Went back.", done) }, 400L) } } else done("There's no earlier page in \"$title\".")
            "forward" -> if (wv.canGoForward()) { pageLoading = true; wv.goForward(); awaitLoad(10_000L) { main.postDelayed({ finish(wv, "Went forward.", done) }, 400L) } } else done("There's no later page in \"$title\".")
            "reload" -> {
                pageLoading = true
                if (widget.type == WidgetType.APP) loadApp(wv) else wv.reload()   // apps re-read their file
                awaitLoad(12_000L) { main.postDelayed({ finish(wv, "Reloaded.", done) }, 400L) }
            }
            "url" -> {
                val u = cmd.arg("url") ?: return done("No URL given.")
                pageLoading = true; lastHttpStatus = 0
                wv.loadUrl(u)
                awaitLoad(12_000L) { main.postDelayed({ finish(wv, "Opened ${runCatching { java.net.URL(u).host }.getOrDefault(u)}.${httpHint()}", done) }, 400L) }
            }
            "press" -> {
                val key = cmd.arg("key", "text", "value") ?: "enter"
                if (!SyntheticInput.key(keyTarget(wv), key)) return done("I can't press \"$key\" — try enter, escape, space, tab, arrow keys, backspace, page_down.")
                val seq0 = navSeq
                main.postDelayed({ awaitLoad(10_000L) { main.postDelayed({ finish(wv, "Pressed $key.", done, changed = navSeq != seq0) }, 300L) } }, 500L)
            }
            "zoom" -> {
                val dir = (cmd.arg("direction", "value") ?: "in").lowercase(Locale.US)
                val out = dir.startsWith("out") || dir.startsWith("far") || dir == "-" || dir == "minus"
                val levels = cmd.arg("amount", "levels")?.toDoubleOrNull()?.toInt()?.coerceIn(1, 6) ?: 2   // one spoken "zoom" = a visible 4× change
                val cur = wv.url.orEmpty()
                val gm = Regex("(@-?\\d+\\.\\d+,-?\\d+\\.\\d+,)(\\d+(?:\\.\\d+)?)z").find(cur)
                if (gm != null) {
                    // Google Maps mobile has no zoom buttons on place pages: the zoom lives in the URL.
                    val z = (gm.groupValues[2].toDouble() + if (out) -levels else levels).coerceIn(2.0, 21.0)
                    val next = cur.replaceRange(gm.range, gm.groupValues[1] + "%.2f".format(Locale.US, z).trimEnd('0').trimEnd('.') + "z")
                    pageLoading = true; lastHttpStatus = 0
                    wv.loadUrl(next)
                    awaitLoad(12_000L) { main.postDelayed({ finish(wv, "Zoomed ${if (out) "out" else "in"} on the map to level ${"%.0f".format(Locale.US, z)} of 21.", done) }, 400L) }
                } else {
                    // Other pages: scale the page itself, like a pinch.
                    val f = if (out) Math.pow(0.8, levels.toDouble()) else Math.pow(1.25, levels.toDouble())
                    wv.settings.setSupportZoom(true)
                    wv.zoomBy(f.toFloat().coerceIn(0.01f, 100f))
                    main.postDelayed({ finish(wv, "Zoomed ${if (out) "out" else "in"} on the page.", done) }, 400L)
                }
            }
            "scroll" -> {
                val dir = (cmd.arg("direction", "value") ?: "down").lowercase(Locale.US)
                val amount = cmd.arg("amount")?.toDoubleOrNull()?.toInt() ?: 300
                if (!jsOn) {
                    when (dir) {
                        "up" -> wv.scrollBy(0, -amount); "down" -> wv.scrollBy(0, amount)
                        "top" -> wv.scrollTo(0, 0); "bottom" -> wv.pageDown(true)
                        "left" -> wv.scrollBy(-amount, 0); "right" -> wv.scrollBy(amount, 0)
                    }
                    done("Scrolled $dir in \"$title\".")
                } else js(wv, "__tg.scroll(${jsStr(dir)}, $amount)") { r -> main.postDelayed({ finish(wv, r ?: "Scrolled $dir.", done) }, 250L) }
            }
            "eval" -> if (!com.tapgem.app.BuildConfig.DEBUG || !jsOn) done("Not available.") else js(wv, "(function(){ return ${cmd.arg("js") ?: "null"}; })()") { done(it ?: "null") }
            "inspect" -> if (!jsOn) done("\"$title\" is an ebook — use chapter navigation.") else js(wv, "__tg.inspect()") { done((it ?: "Nothing to inspect.") + "\n" + soundLine()) }
            "read" -> if (!jsOn) done("\"$title\" is an ebook — use chapter navigation.")
                else js(wv, "__tg.read(${cmd.arg("cap")?.toIntOrNull()?.coerceIn(500, 400_000) ?: 2500})") { done(it ?: "Nothing to read.") }
            "click" -> {
                val target = cmd.arg("target_text", "text", "label", "target") ?: ""
                val index = cmd.arg("index")?.toDoubleOrNull()?.toInt() ?: 0
                if (!jsOn) return done("\"$title\" has nothing to click.")
                runStep(wv, "__tg.click(${jsStr(target)}, $index)", 3, "", done)
            }
            "type", "search" -> {
                val text = cmd.arg("text", "value", "query") ?: return done("Nothing to type.")
                val field = cmd.arg("field_text", "field", "target_text") ?: ""
                val submit = cmd.arg("submit")?.lowercase(Locale.US) in setOf("true", "yes", "1", "on")
                if (!jsOn) return done("\"$title\" has no fields to type into.")
                val typeJs = if (cmd.action == "search") "__tg.search(${jsStr(text)})" else "__tg.type(${jsStr(field)}, ${jsStr(text)}, $submit)"
                runStep(wv, typeJs, 4, "", done)
            }
            "play", "pause" -> {
                if (!jsOn) return done("\"$title\" has no media.")
                val wantPlay = cmd.action == "play"
                jsObj(wv, "__tg.media($wantPlay)") { o ->
                    if (o?.optBoolean("none") == true) {
                        val on = soundActive()
                        return@jsObj done(if (wantPlay) (if (on) "Nothing to press — sound is already playing." else o.optString("msg") + " Sound: none.")
                            else (if (on) o.optString("msg") + " Sound is still playing — click its stop or pause control by text." else "Nothing is playing."))
                    }
                    completeWithTap(wv, o, digest = false, done = { msg ->
                        if (!wantPlay) { main.postDelayed({ done("$msg ${soundLine()}") }, 600L); return@completeWithTap }
                        // Verify the page really started: page media state or sound on the glasses,
                        // polled for a few seconds (streams buffer), with one nudge via the media API.
                        fun verify(left: Int) {
                            jsObj(wv, "__tg.isPlaying()") { st ->
                                val has = st?.optBoolean("has") == true
                                val playing = st?.optBoolean("playing") == true || soundActive()
                                when {
                                    playing -> done("$msg Sound: playing.")
                                    left == 0 -> done("$msg Sound: none — the player hasn't started; click its own play control by text or index.")
                                    else -> {
                                        if (has && left == 3) jsObj(wv, "({ok:__tg.forcePlay()})") {}
                                        main.postDelayed({ verify(left - 1) }, 900L)
                                    }
                                }
                            }
                        }
                        main.postDelayed({ verify(5) }, 900L)
                    })
                }
            }
            else -> done("Unknown web action ${cmd.action}.")
        }
    }

    /** Run [then] once the current page load finishes (or right away / after [timeoutMs]). */
    private fun awaitLoad(timeoutMs: Long, then: () -> Unit) {
        if (!pageLoading) { then(); return }
        var fired = false
        val once = { if (!fired) { fired = true; then() } }
        loadWaiters += once
        main.postDelayed({ if (!fired) { loadWaiters.remove(once); once() } }, timeoutMs)
    }

    /**
     * Runs a page step (click / type / search) whose JS may first need a preparatory
     * tap — dismissing a dialog that covers the target, or opening the control that
     * reveals a hidden search field (Spotify: Search tab, then the search bar;
     * archive.org: the search icon). Such results carry retry=true: tap, wait, re-run
     * the same step, at most [left] times. async=true means the site answered
     * through its own API and the text arrives in __tg.asyncOut.
     */
    private fun runStep(wv: WebView, stepJs: String, left: Int, prefix: String, done: (String) -> Unit) {
        // Steps issued right after add/url arrive while the page is still loading: wait for it.
        awaitLoad(10_000L) { main.postDelayed({ runStepNow(wv, stepJs, left, prefix, done) }, if (prefix.isEmpty()) 300L else 0L) }
    }

    private fun runStepNow(wv: WebView, stepJs: String, left: Int, prefix: String, done: (String) -> Unit) {
        js(wv, "__tg.mark()") { sig0 ->
            jsObj(wv, stepJs) { o ->
                runCatching { hideIme() }
                when {
                    o?.optBoolean("async") == true -> main.postDelayed({ awaitAsync(wv, 15, done) }, 600L)
                    o?.optBoolean("retry") != true -> completeWithTap(wv, o, done, prefix = prefix, sig0 = sig0)
                    left == 0 -> finish(wv, prefix + o.optString("msg") + " I couldn't get past that.", done)
                    else -> completeWithTap(wv, o, { first -> main.postDelayed({ runStep(wv, stepJs, left - 1, "$prefix$first ", done) }, 700L) }, digest = false)
                }
            }
        }
    }

    private fun awaitAsync(wv: WebView, left: Int, done: (String) -> Unit) {
        js(wv, "__tg.asyncOut") { r ->
            if (r != null) done("$r ${soundLine()}")
            else if (left == 0) done("The site's search didn't answer in time.")
            else main.postDelayed({ awaitAsync(wv, left - 1, done) }, 400L)
        }
    }

    /** An HTTP error hint the model can act on (empty when the page loaded fine). */
    private fun httpHint(): String = when {
        lastHttpStatus == 404 -> " That address doesn't exist (404) — search the site instead of guessing links."
        lastHttpStatus >= 400 -> " The page returned HTTP $lastHttpStatus."
        else -> ""
    }

    /**
     * Every screen-changing web action ends here: the action's own message, then
     * where the page is now and what is on it (so the next step needs no inspect),
     * then whether the glasses are actually making sound.
     */
    private fun finish(wv: WebView, msg: String, done: (String) -> Unit, changed: Boolean = true, sig0: String? = null) {
        if (!wv.settings.javaScriptEnabled || !changed) { done(msg); return }
        val seq0 = navSeq
        // Single-page sites render results a beat after the URL settles: wait until the DOM stops changing.
        fun settled(left: Int, prev: String?) {
            js(wv, "__tg.sig()") { sig ->
                if (left == 0 || (sig != null && sig == prev)) {
                    js(wv, "__tg.digest(14)") { d ->
                        val digest = d?.takeIf { it.isNotBlank() && !it.startsWith("Page error") }
                        // Same DOM signature as before the step and no navigation: the page ignored it — say so
                        // rather than let a plausible-looking digest read as success.
                        val unchanged = sig0 != null && sig == sig0 && navSeq == seq0
                        done(listOfNotNull(msg, if (unchanged) "Nothing on the page changed." else null, digest, soundLine()).joinToString(" "))
                    }
                } else main.postDelayed({ settled(left - 1, sig) }, 450L)
            }
        }
        settled(7, null)
    }

    /** Anything on the glasses' media stream except our own speech (the page's audio, video, radio). */
    private fun soundActive(): Boolean = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.activePlaybackConfigurations.any { it.audioAttributes.contentType != android.media.AudioAttributes.CONTENT_TYPE_SPEECH }
    }.getOrDefault(false)

    private fun soundLine(): String = if (soundActive()) "Sound: playing." else "Sound: none."

    /** JS results of the form {tap:[x,y], msg, innerWidth} get a real native tap at that CSS point. */
    private fun completeWithTap(wv: WebView, o: JSONObject?, done: (String) -> Unit, digest: Boolean = true, prefix: String = "", sig0: String? = null) {
        if (o == null) { done("The page didn't respond."); return }
        val msg = prefix + o.optString("msg").ifBlank { "Done." }
        val tap = o.optJSONArray("tap")
        val end: (String) -> Unit = { m -> if (digest) finish(wv, m + httpHint(), done, sig0 = sig0) else done(m) }
        if (tap == null || tap.length() < 2) {
            // e.g. type+submit: the page may be navigating — report where it landed.
            main.postDelayed({ awaitLoad(10_000L) { main.postDelayed({ end(msg) }, 300L) } }, 600L)
            return
        }
        val innerW = o.optDouble("innerWidth", 0.0)
        val scale = if (innerW > 1.0) wv.width / innerW else 1.0
        onFocus?.invoke(widget.id)
        // Smooth-scrolling pages move the target after scrollIntoView: re-measure once settled.
        main.postDelayed({
            wv.evaluateJavascript("(function(){try{return JSON.stringify(__tg.pendingPoint())}catch(e){return null}})()") { raw ->
                val fresh = (decodeJs(raw) as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: (decodeJs(raw) as? JSONObject)
                val pt = fresh?.optJSONArray("tap")?.takeIf { it.length() >= 2 } ?: tap
                val x = (pt.getDouble(0) * scale).toFloat().coerceIn(1f, (wv.width - 2).toFloat())
                val y = (pt.getDouble(1) * scale).toFloat().coerceIn(1f, (wv.height - 2).toFloat())
                // While the page is full screen Chromium's fullscreen view sits over the WebView
                // (same size, same origin) and is the one that takes input.
                SyntheticInput.tap(fullscreenView ?: wv, x, y) {
                    runCatching { hideIme() }
                    // Give the page a beat to react; if the tap started a navigation, wait for it.
                    main.postDelayed({ awaitLoad(10_000L) { main.postDelayed({ end(msg) }, 300L) } }, 600L)
                }
            }
        }, 450L)
    }

    private fun js(wv: WebView, expr: String, cb: (String?) -> Unit) {
        wv.evaluateJavascript(HELPER_JS, null)
        wv.evaluateJavascript("(function(){try{var r=$expr;return (typeof r==='string')?r:JSON.stringify(r);}catch(e){return 'Page error: '+e.message}})()") { raw ->
            cb(decodeJs(raw) as? String ?: raw?.takeIf { it != "null" })
        }
    }

    private fun jsObj(wv: WebView, expr: String, cb: (JSONObject?) -> Unit) {
        wv.evaluateJavascript(HELPER_JS, null)
        wv.evaluateJavascript("(function(){try{var r=$expr;if(typeof r==='string')r={msg:r};r.innerWidth=window.innerWidth;return JSON.stringify(r);}catch(e){return JSON.stringify({msg:'Page error: '+e.message})}})()") { raw ->
            val v = decodeJs(raw)
            cb(when (v) { is JSONObject -> v; is String -> runCatching { JSONObject(v) }.getOrNull(); else -> null })
        }
    }

    private fun decodeJs(raw: String?): Any? = runCatching { JSONTokener(raw ?: return null).nextValue() }.getOrNull()
        ?.let { if (it == JSONObject.NULL) null else it }

    private fun jsStr(s: String): String = JSONObject.quote(s)

    private val GOOGLE_MAPS_DARK_JS = """
(function(){ if(document.getElementById('tg-mapdark')) return; var s=document.createElement('style'); s.id='tg-mapdark';
 s.textContent='img[src^="blob:"]{filter:invert(1) hue-rotate(180deg) brightness(0.8) contrast(1.1)!important}';
 (document.head||document.documentElement).appendChild(s); })();
""".trimIndent()

    /**
     * music.youtube.com's player page is laid out for a phone held upright: 408px of
     * controls and tab strip are reserved under the media, so in a 640×418 window the
     * video (or album art) gets 10px. This reflows the player page for short viewports —
     * art above a compact control block, a video filling the width with the controls
     * floating over its foot, the Song/Video switch over the top — and nudges YouTube's
     * player, which only re-measures its <video> on a window resize, whenever the box changes.
     */
    private val YT_MUSIC_CSS = """
@media (max-height: 700px) {
  /* Every rule stands down in full screen ([player-fullscreened]): YouTube's own scrim, centred buttons and exit control take over there. */
  ytmusic-player-page[is-mweb-modernization-enabled] { --tg-ctl: 124px; --tg-tabs: 68px; }   /* compact controls; the Up next/Lyrics tab strip peeking at the foot */
  /* Media box: album art sits above the controls; a video takes the whole height and the controls float over its foot. */
  ytmusic-player-page[is-mweb-modernization-enabled]:not([player-fullscreened]) #music-player-wrapper.ytmusic-player-page {
    top: 0 !important; height: calc(var(--ytmusic-player-page-inner-height) - var(--tg-tabs) - var(--tg-ctl)) !important; justify-content: center; }
  ytmusic-player-page[is-mweb-modernization-enabled][video-mode]:not([player-fullscreened]) #music-player-wrapper.ytmusic-player-page {
    height: calc(var(--ytmusic-player-page-inner-height) - var(--tg-tabs)) !important; }
  /* The player's size is width-driven (its inline margins centre it): a square for art, 16:9 for a video. */
  ytmusic-player-page[is-mweb-modernization-enabled]:not([player-fullscreened]) #player.ytmusic-player-page {
    --tg-art: min(100vw - 64px, var(--ytmusic-player-page-inner-height) - var(--tg-tabs) - var(--tg-ctl));
    max-height: var(--tg-art) !important; width: var(--tg-art) !important; margin: auto !important; }
  ytmusic-player-page[is-mweb-modernization-enabled][video-mode]:not([player-fullscreened]) #player.ytmusic-player-page {
    --tg-vid: min(100vw, (var(--ytmusic-player-page-inner-height) - var(--tg-tabs)) * 1.7778);
    max-height: calc(var(--tg-vid) * 0.5625) !important; width: var(--tg-vid) !important; }
  /* Song/Video switch, the ▾ that closes the player page and the ⋮ menu float over the top edge. */
  ytmusic-player-page[is-mweb-modernization-enabled]:not([player-fullscreened]) #av-id.ytmusic-player-page { position: absolute; top: 4px; left: 0; right: 0; z-index: 3; height: 32px; pointer-events: none; }
  ytmusic-player-page[is-mweb-modernization-enabled]:not([player-fullscreened]) .collapse-button.ytmusic-player-page,
  ytmusic-player-page[is-mweb-modernization-enabled]:not([player-fullscreened]) .context-menu-button.ytmusic-player-page { z-index: 4; }
  ytmusic-player-page[is-mweb-modernization-enabled]:not([player-fullscreened]) #av-id.ytmusic-player-page > * { pointer-events: auto; }
  /* Controls: one line of title · artist, the seek bar, the buttons — anchored just above the tab strip. */
  ytmusic-player-page[is-mweb-modernization-enabled]:not([player-fullscreened]) #player-controls.ytmusic-player-page {
    top: auto !important; bottom: var(--tg-tabs) !important; height: auto !important; padding: 6px 0 0 !important; z-index: 3; }
  ytmusic-player-page[is-mweb-modernization-enabled][video-mode]:not([player-fullscreened]) #player-controls.ytmusic-player-page {
    width: 100% !important; margin: 0 !important; padding: 6px 32px 0 !important; box-sizing: border-box;
    background: linear-gradient(rgba(0,0,0,0), rgba(0,0,0,.65) 30%, rgba(0,0,0,.85)) !important; }
  ytmusic-player-page[is-mweb-modernization-enabled]:not([player-fullscreened]) #player-controls.ytmusic-player-page .content-info-wrapper {
    display: flex !important; align-items: baseline; gap: 10px; min-width: 0; height: 22px; }
  ytmusic-player-page[is-mweb-modernization-enabled]:not([player-fullscreened]) #player-controls.ytmusic-player-page .title {
    font-size: 16px !important; line-height: 22px !important; white-space: nowrap !important; overflow: hidden; text-overflow: ellipsis;
    flex: 0 1 auto; min-width: 0; display: block !important; -webkit-line-clamp: 1 !important; }
  ytmusic-player-page[is-mweb-modernization-enabled]:not([player-fullscreened]) #player-controls.ytmusic-player-page .byline-wrapper { padding: 0 !important; flex: 1 1 auto; min-width: 0; }
  ytmusic-player-page[is-mweb-modernization-enabled]:not([player-fullscreened]) #player-controls.ytmusic-player-page .byline {
    font-size: 13px !important; line-height: 22px !important; white-space: nowrap !important; overflow: hidden; text-overflow: ellipsis; }
  ytmusic-player-page[is-mweb-modernization-enabled]:not([player-fullscreened]) #player-controls.ytmusic-player-page .progress-bar-container { height: 40px !important; }
  ytmusic-player-page[is-mweb-modernization-enabled]:not([player-fullscreened]) #player-controls.ytmusic-player-page #progress-bar { margin: 0 !important; }
  ytmusic-player-page[is-mweb-modernization-enabled]:not([player-fullscreened]) #player-controls.ytmusic-player-page .controls { height: 56px !important; }
  ytmusic-player-page[is-mweb-modernization-enabled]:not([player-fullscreened]) #player-controls.ytmusic-player-page .play-pause-button-wrapper { width: 56px !important; height: 56px !important; }
}
""".trimIndent()

    private val YT_MUSIC_LAYOUT_JS = """
(function(){ if(document.getElementById('tg-ytm')) return; var s=document.createElement('style'); s.id='tg-ytm';
 s.textContent=${org.json.JSONObject.quote(YT_MUSIC_CSS)};
 (document.head||document.documentElement).appendChild(s);
 var t=0; function kick(){ clearTimeout(t); t=setTimeout(function(){ window.dispatchEvent(new Event('resize')); }, 60); }
 function watch(){ var p=document.querySelector('ytmusic-player'); if(!p) return false; try{ new ResizeObserver(kick).observe(p); }catch(e){} return true; }
 if(!watch()){ var mo=new MutationObserver(function(){ if(watch()) mo.disconnect(); }); mo.observe(document.documentElement,{childList:true,subtree:true}); }
 /* The Up next / Lyrics sheet only closes with a finger dragged down its header. With a cursor that is a
    hold-and-drag nobody finds, so a tap on the sheet's own selected tab, or on the media peeking
    above it (not the toggle, ▾ or ⋮ floating over it), plays that drag for them. Touch events, not click: the header swallows taps before they click. */
 function page(){ return document.querySelector('ytmusic-player-page'); }
 function collapseTabs(){ var pp=page(); var h=pp&&pp.querySelector('.tab-header-container'); if(!h) return; var r=h.getBoundingClientRect(); var x=r.x+r.width/2;
   function t(type,y){ var touch=new Touch({identifier:1,target:h,clientX:x,clientY:y,pageX:x,pageY:y}); var end=type==='touchend';
     h.dispatchEvent(new TouchEvent(type,{touches:end?[]:[touch],targetTouches:end?[]:[touch],changedTouches:[touch],bubbles:true,cancelable:true})); }
   var y0=r.y+20; t('touchstart',y0); [40,90,150,220].forEach(function(dy){ t('touchmove',y0+dy); }); t('touchend',y0+220); }
 var down=null;
 document.addEventListener('touchstart', function(e){ if(!e.isTrusted) return; var t=e.touches[0]; down=t?{x:t.clientX,y:t.clientY,path:e.composedPath?e.composedPath():[]}:null; }, true);
 document.addEventListener('touchend', function(e){ if(!e.isTrusted||!down) return; var d=down; down=null; var pp=page(); if(!pp||pp.getAttribute('player-page-ui-state')!=='TABS_VIEW') return;
   var t=e.changedTouches[0]; if(!t||Math.abs(t.clientX-d.x)>12||Math.abs(t.clientY-d.y)>12) return;
   var media=pp.querySelector('#music-player-wrapper'), header=pp.querySelector('.tab-header-container'), hit=false;
   if(media&&d.path.indexOf(media)>=0) hit=true;
   else if(header&&d.path.indexOf(header)>=0){ for(var i=0;i<d.path.length;i++){ var n=d.path[i]; if(n.classList&&n.classList.contains('iron-selected')){ hit=true; break; } } }
   if(!hit) return; e.stopPropagation(); e.preventDefault(); setTimeout(collapseTabs,0);
 }, true); })();
""".trimIndent()

    private val HELPER_JS = """
(function(){
 if (window.__tg) return;
 var T = {};
 function laidOut(el){ try{ var r=el.getBoundingClientRect(); if(r.width<2||r.height<2) return false; var s=getComputedStyle(el); if(s.visibility==='hidden'||s.display==='none'||s.opacity==='0') return false; var n=el; for(var k=0;k<12&&n&&n.nodeType===1;k++){ var cs=getComputedStyle(n); if(cs.display==='none'||cs.visibility==='hidden'||cs.opacity==='0') return false; n=n.parentNode&&n.parentNode.nodeType===1?n.parentNode:(n.parentNode&&n.parentNode.host)||null; } return true; }catch(e){ return false; } }
 function vis(el){ try{ if(!laidOut(el)) return false; var r=el.getBoundingClientRect(); return r.bottom>0&&r.right>0&&r.top<innerHeight&&r.left<innerWidth; }catch(e){ return false; } }
 function norm(s){ return String(s||'').toLowerCase().replace(/\s+/g,' ').trim(); }
 function attr(el,n){ return (el.getAttribute&&el.getAttribute(n))||''; }
 function labelFor(el){ var t=''; try{ if(el.id){ var root=el.getRootNode?el.getRootNode():document; var l=root.querySelector('label[for="'+el.id+'"]'); if(l) t=l.innerText; } if(!t&&el.closest){ var p=el.closest('label'); if(p) t=p.innerText; } }catch(e){} return t||''; }
 function chain(el){ var out=[]; var n=el; while(n){ out.push(n); n=n.parentNode||n.host||null; if(n&&n.nodeType===11&&n.host){ n=n.host; } } return out; }
 function clean(s){ return String(s||'').replace(/[\u0000-\u001F\u007F-\u009F\u200B-\u200F\uE000-\uF8FF\uFE00-\uFE0F\uFFFD]/g,'').replace(/\s+/g,' ').trim(); }
 function txt(el){ var t=clean(el.innerText||el.textContent||''); if(!t){ var a=attr(el,'aria-label')||attr(el,'title')||attr(el,'placeholder')||attr(el,'alt')||labelFor(el); if(!a&&el.querySelector){ var i=el.querySelector('img[alt],[aria-label],svg title'); if(i) a=attr(i,'alt')||attr(i,'aria-label')||i.textContent; } t=clean(a); } return String(t).slice(0,80); }
 var SEL='a[href],button,input,select,textarea,[role=button],[role=link],[role=tab],[role=menuitem],[role=option],[role=checkbox],[role=switch],[role=textbox],[role=searchbox],[role=combobox],[onclick],[contenteditable=true],video,audio,summary';
 function parentEl(n){ return n.parentNode&&n.parentNode.nodeType===1?n.parentNode:(n.parentNode&&n.parentNode.host)||null; }
 function pointer(el){ try{ return getComputedStyle(el).cursor==='pointer'; }catch(e){ return false; } }
 /* Clickable rows/cards that are plain divs with cursor:pointer (Spotify results, station cards): the outermost such element, not one already inside a link/button. */
 function clickableRow(el){ if(el===document.body||el.children.length>40||!pointer(el)) return false; var p=parentEl(el); if(p&&p!==document.body&&pointer(p)) return false; try{ if(el.closest&&el.closest(SEL)!==el&&el.closest(SEL)) return false; }catch(e){} return true; }
 function walk(root,out,depth){ if(depth>25) return; var els; try{ els=root.querySelectorAll('*'); }catch(e){ return; } for(var i=0;i<els.length&&out.length<4000;i++){ var el=els[i]; try{ if(el.matches(SEL)) out.push(el); else if(clickableRow(el)) out.push(el); }catch(e){} if(el.shadowRoot) walk(el.shadowRoot,out,depth+1); } }
 function all(){ var out=[]; walk(document,out,0); return out; }
 function inView(el){ var r=el.getBoundingClientRect(); return r.bottom>0&&r.right>0&&r.top<innerHeight&&r.left<innerWidth; }
 /* Everything rendered on the page (not just the viewport): click scrolls to it, so the model need not scroll-and-inspect to find results below the fold. */
 T.collect=function(){ var els=all(); var out=[]; els.forEach(function(el){ if(!laidOut(el)) return; if(el.type==='hidden') return; var kind=el.tagName.toLowerCase(); if(el.type&&kind==='input') kind+=':'+el.type; var label=txt(el); if(!label&&kind!=='video'&&kind!=='audio') return; var r=el.getBoundingClientRect(); out.push({el:el,kind:kind,label:label.slice(0,60),where:r.bottom<=0?'above':(r.top>=innerHeight?'below':'')}); }); return out; };
 T.sig=function(){ var n=0; try{ n=all().length; }catch(e){} return n+'|'+((document.body&&document.body.innerText)||'').length; };
 /* Called before a step: remember what was on the page so the digest can point out what appeared (a popover, a menu, an error). */
 T.mark=function(){ try{ var b={}; T.collect().forEach(function(x){ b[x.kind+'|'+x.label]=1; }); T.before=b; }catch(e){ T.before=null; } return T.sig(); };
 function fresh(o){ var b=T.before; T.before=null; if(!b) return []; return o.filter(function(x){ return !b[x.kind+'|'+x.label]; }); }
 T.mediaLine=function(){ var st=T.isPlaying(); return st.has?('Media: '+(st.playing?'playing':'paused — not started yet, use action=play')):''; };
 /* Site chrome (header, nav, footer — also inside custom elements like archive.org's ia-topnav) ranks after content so a short list shows results, not menus. */
 function inNav(el){ var n=el; for(var k=0;k<30&&n;k++){ if(n.nodeType===1){ var tg=n.tagName; var role=attr(n,'role'); if(tg==='HEADER'||tg==='NAV'||tg==='FOOTER'||role==='banner'||role==='navigation'||role==='contentinfo'||/NAV|HEADER|FOOTER|BAR|MENU/.test(tg)) return true; } n=n.parentNode&&n.parentNode.nodeType===1?n.parentNode:(n.parentNode&&n.parentNode.host)||null; } return false; }
 /* Links and rows (results) before fields before toolbar buttons; on-screen before below; site chrome last. */
 function rank(x){ if(x.dlg) return -1; var k=x.kind; var kp=(k==='a'||k==='div'||k==='li'||k==='video'||k==='audio')?0:(k.indexOf('input')===0?1:2); if(k==='a'&&x.label.length<16) kp=1; return (x.nav?8:0)+kp*2+(x.where===''?0:(x.where==='below'?1:5)); }
 function lines(o,max,prioritize){ var d=T.dialog(); o.forEach(function(x){ x.nav=inNav(x.el); x.dlg=!!(d&&chain(x.el).indexOf(d)>=0); }); if(prioritize||o.length>max){ var seen={}; o=o.slice().sort(function(a,b){ return rank(a)-rank(b); }).filter(function(x){ if(x.kind==='a'||x.kind==='div'||x.kind==='li') return true; var key=x.kind+'|'+x.label; if(seen[key]) return false; seen[key]=1; return true; }).slice(0,max); } T.last=o.map(function(x){return x.el;}); T.virtual=null; return o.map(function(x,i){ return (i+1)+'. ['+x.kind+'] '+x.label+(x.where?' ('+x.where+')':''); }); }
 T.inspect=function(){ var o=T.collect(); var ls=lines(o,45,false); var head='Page: '+document.title+' ('+location.host+')'; var dl=T.dialogLine(); if(dl) head+='\n'+dl; var m=T.mediaLine(); if(m) head+='\n'+m; return head+'\n'+(o.length?('Elements ('+o.length+'):\n'+ls.join('\n')):'No interactive elements found; the page may still be loading.'); };
 /* Short "what's here now" appended to action results so the model can go straight to the next step. */
 T.digest=function(n){ var o=T.collect(); var nw=fresh(o); var ls=lines(o,n||12,true); var s='Now on: '+(document.title||location.host)+'.'; var dl=T.dialogLine(); if(dl) s+=' '+dl; else if(nw.length&&nw.length<=6&&nw.length<o.length/2) s+=' New: '+nw.map(function(x){ return x.label; }).join(' | ')+'.'; var m=T.mediaLine(); if(m) s+=' '+m+'.'; if(ls.length) s+=' Items: '+ls.join(' | ')+(o.length>ls.length?(' | +'+(o.length-ls.length)+' more (inspect)'):''); return s; };
 function deepText(node,acc,depth){ if(acc.n>6000||depth>40) return; if(node.nodeType===3){ var s=node.nodeValue.replace(/\s+/g,' ').trim(); if(s){ acc.parts.push(s); acc.n+=s.length; } return; } if(node.nodeType!==1&&node.nodeType!==11&&node.nodeType!==9) return; if(node.nodeType===1){ var tg=node.tagName; if(tg==='SCRIPT'||tg==='STYLE'||tg==='NOSCRIPT'||tg==='TEMPLATE') return; try{ var cs=getComputedStyle(node); if(cs.display==='none'||cs.visibility==='hidden') return; }catch(e){} if(node.shadowRoot) deepText(node.shadowRoot,acc,depth+1); } var c=node.childNodes; for(var i=0;i<c.length;i++) deepText(c[i],acc,depth+1); }
 /* cap: 2500 is what goes straight to the conversation; a bigger slice is for the reader model,
    which has room for it and hands back something short. */
 T.read=function(cap){ var m=document.querySelector('main,article,[role=main]')||document.body; var t=(m&&m.innerText||'').replace(/\n{3,}/g,'\n\n').trim(); if(t.length<200){ var acc={parts:[],n:0}; deepText(document.body,acc,0); t=acc.parts.join(' · '); } cap=cap||2500; return 'Title: '+document.title+'\nURL: '+location.href+'\n\n'+t.slice(0,cap)+(t.length>cap?'…':''); };
 T.tapPoint=function(el){ var r=el.getBoundingClientRect(); var cx=r.left+r.width/2, cy=r.top+r.height/2; var cands=[[cx,cy],[cx,r.top+r.height*0.25],[cx,r.top+r.height*0.75],[r.left+r.width*0.25,cy],[r.left+r.width*0.75,cy],[r.left+r.width*0.25,r.top+r.height*0.25],[r.left+r.width*0.75,r.top+r.height*0.25]]; var best=null; for(var i=0;i<cands.length;i++){ var p=[Math.round(cands[i][0]),Math.round(cands[i][1])]; if(p[0]<1||p[1]<1||p[0]>=innerWidth-1||p[1]>=innerHeight-1) continue; if(!best) best=p; if(!T.covered(el,p)) return p; } return best||[Math.round(cx),Math.round(cy)]; };
 T.center=function(el){ T.pending=el; try{ el.scrollIntoView({block:'center',inline:'center',behavior:'instant'}); }catch(e){ el.scrollIntoView({block:'center',inline:'center'}); } return T.tapPoint(el); };
 T.pendingPoint=function(){ var el=T.pending; if(!el) return null; var p=T.tapPoint(el); return {tap:p, settled:!T.covered(el,p)}; };
 T.covered=function(el,pt){ var h=document.elementFromPoint(pt[0],pt[1]); if(!h) return true; while(h&&h.shadowRoot&&h.shadowRoot.elementFromPoint){ var inner=h.shadowRoot.elementFromPoint(pt[0],pt[1]); if(!inner||inner===h) break; h=inner; } return !(h===el||chain(h).indexOf(el)>=0||chain(el).indexOf(h)>=0); };
 T.findByText=function(q,pool){ q=norm(q); if(!q) return null; var c=pool||all().filter(laidOut); var qw=q.split(' ').filter(function(w){return w.length>1;}); var best=null,bs=0; c.forEach(function(el){ var l=norm(txt(el)); if(!l) return; var s=0; if(l===q) s=100; else if(l.indexOf(q)===0) s=90; else if(l.indexOf(q)>=0) s=80; else { var hit=qw.filter(function(w){return l.indexOf(w)>=0;}).length; if(qw.length&&hit===qw.length) s=70; else if(qw.length>=3&&hit>=qw.length-1) s=40; if(q.indexOf(l)>=0&&l.length>2) s=Math.max(s,Math.round(50*l.length/q.length)); } if(s<60){ var h=norm(attr(el,'aria-label')+' '+attr(el,'title')+' '+attr(el,'data-testid')); if(h&&h.indexOf(q)>=0) s=60; } if(s&&inView(el)) s+=5; if(s>bs){bs=s;best=el;} }); return bs>=25?best:null; };
 function walkAll(root,out,depth){ if(depth>25||out.length>6000) return; var els; try{ els=root.querySelectorAll('*'); }catch(e){ return; } for(var i=0;i<els.length&&out.length<6000;i++){ out.push(els[i]); if(els[i].shadowRoot) walkAll(els[i].shadowRoot,out,depth+1); } }
 T.findAnyText=function(q){ q=norm(q); if(!q) return null; var out=[]; walkAll(document,out,0); var best=null,ba=1e12; out.forEach(function(el){ if(el.children&&el.children.length>6) return; if(!vis(el)) return; var t=norm(el.innerText||el.textContent); if(!t||t.length>200||t.indexOf(q)<0) return; var r=el.getBoundingClientRect(); var a=r.width*r.height; if(a<ba){ba=a;best=el;} }); if(!best) return null; var n=best; for(var k=0;k<8&&n&&n!==document.body;k++){ try{ if(n.matches&&n.matches('a[href],button,[role=button],[role=link],[onclick],[tabindex]')) return n; if(getComputedStyle(n).cursor==='pointer') return n; }catch(e){} n=n.parentNode&&n.parentNode.nodeType===1?n.parentNode:(n.parentNode&&n.parentNode.host)||null; } return best; };
 /* A dialog / consent sheet / app-nag covering the page: role=dialog, aria-modal, or a fixed box over most of the viewport at its centre. */
 T.dialog=function(){ if(document.fullscreenElement) return null; var c=null; try{ c=Array.prototype.filter.call(document.querySelectorAll('[role=dialog],[aria-modal="true"],dialog[open]'),laidOut).pop()||null; }catch(e){} if(c) return c; try{ var els=document.elementsFromPoint(innerWidth/2,innerHeight/2); for(var i=0;i<els.length;i++){ var n=els[i]; for(var k=0;k<8&&n&&n!==document.body;k++){ var cs=getComputedStyle(n); if(cs.position==='fixed'||cs.position==='absolute'){ var r=n.getBoundingClientRect(); if(r.width*r.height>=0.45*innerWidth*innerHeight&&(parseInt(cs.zIndex,10)||0)>0&&n!==T.scroller()&&(n.innerText||'').trim().length>=20&&!sheetLike(n)) return n; } n=n.parentElement; } } }catch(e){} return null; };
 /* Bottom sheets and side panels (Google Maps results, players) are content, not dialogs: they scroll or hold many controls. */
 function sheetLike(n){ try{ if(n.scrollHeight>n.clientHeight+50) return true; var inner=n.querySelector('[style*="overflow"],[class*="scroll"]'); var ctl=n.querySelectorAll('a[href],button,[role=button],input').length; if(ctl>8) return true; var sc=Array.prototype.slice.call(n.querySelectorAll('div')).some(function(e){ var s=getComputedStyle(e); return /(auto|scroll)/.test(s.overflowY)&&e.scrollHeight>e.clientHeight+50; }); return sc; }catch(e){ return false; } }
 T.dialogButtons=function(d){ var out=[]; walk(d,out,0); return out.filter(function(el){ return laidOut(el)&&txt(el)&&!/^(input|textarea|select)$/i.test(el.tagName); }); };
 T.dismisser=function(d){ var re=/^(not now|no thanks|no, thanks|maybe later|later|skip|dismiss|close|cancel|got it|ok|okay|continue|i agree|agree|accept|accept all|allow all|reject all|x|×|✕)$/i; var bs=T.dialogButtons(d); if(bs.length===1) return bs[0]; /* a single-button gate: 'Press play to start', 'Enter' */ return bs.filter(function(b){ return re.test(norm(txt(b))); })[0]||bs.filter(function(b){ return /close|dismiss/i.test(attr(b,'aria-label')+' '+attr(b,'title')+' '+(attr(b,'data-testid'))); })[0]||null; };
 T.dialogLine=function(){ var fs=document.fullscreenElement; if(fs) return 'The page is full screen ('+(fs.querySelector&&fs.querySelector('video')||fs.tagName==='VIDEO'?'video':fs.tagName.toLowerCase())+') — web action=back leaves it.'; var d=T.dialog(); if(!d) return ''; var t=(d.innerText||'').replace(/\s+/g,' ').trim().slice(0,70); var bs=T.dialogButtons(d).slice(0,5).map(function(b){ return txt(b); }); return 'A dialog covers the page: "'+t+'"'+(bs.length?(' — buttons: '+bs.join(' | ')):'')+'.'; };
 /* Before tapping something a dialog covers: tap the dialog's dismiss button and ask for a retry; if there is none, say what the dialog offers. */
 T.unblock=function(el,pt,label){ if(!T.covered(el,pt)) return null; var d=T.dialog(); if(!d||chain(el).indexOf(d)>=0) return null; var b=T.dismisser(d); if(b){ var bp=T.center(b); return {tap:bp,retry:true,msg:'Closed the "'+(txt(b)||'dialog')+'" dialog.'}; } return {msg:'"'+label+'" is covered. '+T.dialogLine()+' Click one of its buttons first.'}; };
 T.click=function(q,idx){ if(T.virtual){ var it=null; if(idx&&T.virtual[idx-1]) it=T.virtual[idx-1]; else if(q){ var nq=norm(q); it=T.virtual.filter(function(v){ var l=norm(v.label); return l.indexOf(nq)>=0||nq.indexOf(l.split(' (')[0])>=0; })[0]; } if(it){ T.virtual=null; location.assign(it.url); return {msg:'Opening '+it.label+'.'}; } } var el=null; if(idx&&T.last&&T.last[idx-1]) el=T.last[idx-1]; if(!el&&q) el=T.findByText(q); if(!el&&q) el=T.findAnyText(q); if(!el) return {msg:'I couldn\'t find anything to click matching "'+q+'". Try inspect to see what\'s on the page.'}; var label=txt(el)||el.tagName.toLowerCase(); var pt=T.center(el); var onScreen=pt[0]>0&&pt[1]>0&&pt[0]<innerWidth&&pt[1]<innerHeight; if(!onScreen){ try{ el.click(); }catch(e){} return {msg:'Clicked "'+label+'".'}; } var u=T.unblock(el,pt,label); if(u) return u; return {tap:pt,msg:'Clicked "'+label+'".'}; };
 T.fields=function(){ return all().filter(function(el){ var k=el.tagName.toLowerCase(); if(k==='input') return vis(el)&&!/^(hidden|submit|button|checkbox|radio|file|image|range|color)$/.test(el.type||'text'); return vis(el)&&(k==='textarea'||el.isContentEditable||/^(textbox|searchbox|combobox)$/.test(attr(el,'role'))); }); };
 T.findField=function(q){ var f=T.fields(); if(!f.length) return null; if(!q){ var a=document.activeElement; if(a&&f.indexOf(a)>=0) return a; return f[0]; } q=norm(q); var best=null,bs=0; f.forEach(function(el){ var l=norm([attr(el,'placeholder'),attr(el,'aria-label'),attr(el,'name'),attr(el,'id'),attr(el,'title'),labelFor(el),el.value].join(' ')); var s=0; if(l===q) s=5; else if(l.indexOf(q)>=0) s=3; else { var qw=q.split(' '); var hit=qw.filter(function(w){return w.length>1&&l.indexOf(w)>=0;}).length; if(hit) s=hit; } if(s>bs){bs=s;best=el;} }); if(!best){ var unl=f.filter(function(el){ return !norm([attr(el,'placeholder'),attr(el,'aria-label'),attr(el,'name'),labelFor(el)].join('')); }); if(unl.length===1) best=unl[0]; } return best; };
 T.setValue=function(el,text){ if(el.isContentEditable){ el.focus(); el.textContent=text; el.dispatchEvent(new Event('input',{bubbles:true})); return; } var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype; var d=Object.getOwnPropertyDescriptor(proto,'value'); el.focus(); if(d&&d.set) d.set.call(el,text); else el.value=text; el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); };
 T.enter=function(el){ var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}; var kd=new KeyboardEvent('keydown',o); var prevented=!el.dispatchEvent(kd); el.dispatchEvent(new KeyboardEvent('keypress',o)); el.dispatchEvent(new KeyboardEvent('keyup',o)); if(!prevented&&el.form){ try{ if(el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit(); }catch(e){} } };
 function hint(el){ return norm([attr(el,'type'),attr(el,'role'),attr(el,'placeholder'),attr(el,'aria-label'),attr(el,'name'),attr(el,'id'),attr(el,'data-testid'),labelFor(el),typeof el.className==='string'?el.className:''].join(' ')); }
 T.searchField=function(){ var f=T.fields(); var best=null,bs=0; f.forEach(function(el){ var h=hint(el); var s=0; if(attr(el,'type')==='search'||attr(el,'role')==='searchbox') s=10; if(/search|find/.test(h)) s+=5; if(/what do you want|looking for|type here/.test(norm(attr(el,'placeholder')))) s+=3; if(/url|address|wayback/.test(h)) s-=8; if(s>bs){bs=s;best=el;} }); if(!best&&f.length===1&&!/url|address|wayback/.test(hint(f[0]))) best=f[0]; return best; };
 T.searchOpener=function(){ var c=all().filter(laidOut); var best=null,bs=0; c.forEach(function(el){ var k=el.tagName.toLowerCase(); if(k==='input'||k==='textarea'||k==='select') return; var l=norm(txt(el)); var h=norm([attr(el,'aria-label'),attr(el,'title'),attr(el,'data-testid'),el.id||''].join(' ')); var s=0; if(l==='search') s=9; else if(/(^|\s)search(\s|$)/.test(l)) s=8; else if(/search/.test(h)) s=6; if(/what do you want|looking for/.test(l)) s=Math.max(s,7); if(!s) return; if(k==='button'||attr(el,'role')==='button') s+=4; if(inView(el)) s+=1; if(s>bs){bs=s;best=el;} }); return best; };
 T.virtual=null; T.asyncOut=null;
 /* Sites whose layout at this width hides search but expose a same-origin API: results become numbered items the model clicks like any other. */
 T.siteSearch=function(text){ if(/(^|\.)radio\.garden$/.test(location.host)){ T.asyncOut=null; fetch('/api/search?q='+encodeURIComponent(text)).then(function(r){ return r.json(); }).then(function(j){ var hits=(j.hits&&j.hits.hits)||[]; var items=[]; hits.forEach(function(h){ var p=h._source&&h._source.page; if(!p||!p.url) return; items.push({label:p.title+(p.subtitle?' – '+p.subtitle:'')+(p.type==='channel'?' (station)':' (place)'),url:p.url}); }); T.virtual=items.slice(0,12); T.asyncOut=items.length?('Radio Garden found: '+T.virtual.map(function(it,i){ return (i+1)+'. '+it.label; }).join(' | ')+'. Click one by index or name, then play.'):('Nothing on Radio Garden matches "'+text+'".'); }).catch(function(e){ T.asyncOut='Radio Garden search failed: '+e; }); return {async:true,msg:'Searching Radio Garden…'}; } return null; };
 T.search=function(text){ var v=T.siteSearch(text); if(v) return v; var el=T.searchField(); if(el){ var u=T.unblock(el,T.center(el),'the search box'); if(u) return u; T.setValue(el,text); T.enter(el); return {msg:'Searched for "'+text+'".'}; } var b=T.searchOpener(); if(b){ var pt=T.center(b); var u=T.unblock(b,pt,'search'); if(u) return u; return {tap:pt,retry:true,msg:'Opened search.'}; } return {msg:'I can\'t find a search box on this page — try clicking a Search link or menu first.'}; };
 T.type=function(field,text,submit){ var el=T.findField(field); if(!el&&field){ var b=T.findByText(field); if(b&&b!==document.body){ var pt=T.center(b); var u=T.unblock(b,pt,field); if(u) return u; return {tap:pt,retry:true,msg:'Opened "'+(txt(b)||'the control')+'".'}; } } if(!el) return {msg:field?('I couldn\'t find a field matching "'+field+'".'):'There\'s no text field on this page.'}; var label=attr(el,'placeholder')||attr(el,'aria-label')||attr(el,'name')||labelFor(el)||'the field'; var u=T.unblock(el,T.center(el),label); if(u) return u; T.setValue(el,text); if(submit){ T.enter(el); return {msg:'Typed "'+text+'" into '+label+' and pressed enter.'}; } return {msg:'Typed "'+text+'" into '+label+'.'}; };
 T.media=function(play){ var m=Array.prototype.slice.call(document.querySelectorAll('video,audio')); m.sort(function(a,b){ var ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect(); return rb.width*rb.height-ra.width*ra.height; }); var el=m[0]; if(el){ try{ if(play){ var p=el.play(); if(p&&p.catch) p.catch(function(){}); el.muted=false; } else el.pause(); return {msg:(play?'Playing ':'Paused ')+(document.title||el.tagName.toLowerCase())+'.'}; }catch(e){} } var re=play?/(^|\s)(play|resume|listen now)(\s|$|\b)/i:/(^|\s)(pause|stop)(\s|$|\b)/i; var pool=all().filter(laidOut); var cand=pool.filter(function(el){ var k=el.tagName.toLowerCase(); if(k==='input'||k==='textarea'||k==='select') return false; return re.test(txt(el)); }); var visc=cand.filter(vis); if(visc.length) cand=visc; if(!cand.length&&play) cand=pool.filter(vis).filter(function(el){ var k=el.tagName.toLowerCase(); if(k==='input'||k==='textarea') return false; return /(^|[\s_-])play([\s_-]|$)/i.test(attr(el,'aria-label')+' '+attr(el,'title')+' '+(typeof el.className==='string'?el.className:'')); }); cand.sort(function(a,b){ var ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect(); return rb.width*rb.height-ra.width*ra.height; }); var btn=cand[0]||null; if(!btn) return {none:true,msg:play?'I couldn\'t find anything to play here — try inspect or click a specific item.':'I couldn\'t find a pause control.'}; var pt=T.center(btn); return {tap:pt,msg:(play?'Pressed play':'Pressed pause')+' on "'+(txt(btn)||document.title)+'".'}; };
 T.isPlaying=function(){ var m=Array.prototype.slice.call(document.querySelectorAll('video,audio')); if(!m.length){ var out=[]; walkAll(document,out,0); m=out.filter(function(e){ return e.tagName==='VIDEO'||e.tagName==='AUDIO'; }); } if(!m.length) return {has:false}; var p=m.some(function(e){ return !e.paused&&!e.ended&&e.readyState>0||(!e.paused&&e.currentTime>0); }); var playing=m.some(function(e){ return !e.paused; }); return {has:true,playing:playing,ready:p}; };
 T.forcePlay=function(){ var m=Array.prototype.slice.call(document.querySelectorAll('video,audio')); if(!m.length){ var out=[]; walkAll(document,out,0); m=out.filter(function(e){ return e.tagName==='VIDEO'||e.tagName==='AUDIO'; }); } var ok=false; m.forEach(function(e){ try{ var pr=e.play(); if(pr&&pr.catch) pr.catch(function(){}); ok=true; }catch(err){} }); return ok; };
 T.scroller=function(){ var d=document.scrollingElement||document.documentElement; if(d.scrollHeight>d.clientHeight+10) return d; var best=d,ba=0; try{ Array.prototype.forEach.call(document.querySelectorAll('div,main,section,ul,ol'),function(el){ var s=getComputedStyle(el); if(!/(auto|scroll)/.test(s.overflowY+' '+s.overflowX)) return; if(el.scrollHeight<=el.clientHeight+10&&el.scrollWidth<=el.clientWidth+10) return; var r=el.getBoundingClientRect(); var a=Math.min(r.width,innerWidth)*Math.min(r.height,innerHeight); if(a>ba){ba=a;best=el;} }); }catch(e){} return best; };
 T.scroll=function(dir,amount){ if(window.__tgScroll) return window.__tgScroll(dir,amount); var a=amount||300; var se=T.scroller(); var bx=se.scrollLeft,by=se.scrollTop; if(dir==='up') se.scrollBy(0,-a); else if(dir==='down') se.scrollBy(0,a); else if(dir==='left') se.scrollBy(-a,0); else if(dir==='right') se.scrollBy(a,0); else if(dir==='top') se.scrollTo(0,0); else if(dir==='bottom') se.scrollTo(0,se.scrollHeight); else return 'Unknown direction '+dir+'.'; var moved=(se.scrollLeft!==bx)||(se.scrollTop!==by); return moved?('Scrolled '+dir+'.'):('Can\'t scroll '+dir+' any further.'); };
 window.__tg=T;
})();
""".trimIndent()
}
