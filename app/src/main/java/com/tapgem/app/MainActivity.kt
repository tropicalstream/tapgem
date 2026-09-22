package com.tapgem.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.view.Gravity
import androidx.core.view.isVisible
import com.tapgem.app.core.bridge.BookmarksBridge
import com.tapgem.app.core.model.Widget
import com.tapgem.app.core.store.Bookmarks
import com.tapgem.app.core.tools.BookmarkTool
import com.tapgem.app.ui.LibraryPanel
import com.tapgem.app.core.bridge.LibraryBridge
import com.tapgem.app.core.library.Library
import com.tapgem.app.core.model.WidgetType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tapgem.app.ui.WidgetSettingsPanel
import com.tapgem.app.core.bridge.SettingsBridge
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tapgem.app.core.bridge.DesktopBridge
import com.tapgem.app.core.bridge.HudStateBridge
import com.tapgem.app.core.bridge.VoiceServiceApi
import com.tapgem.app.core.bridge.WebCommandBus
import com.tapgem.app.core.model.DesktopMode
import com.tapgem.app.core.store.DesktopStore
import com.tapgem.app.ui.DesktopHostView
import com.tapgem.app.ui.SiriWaveView
import com.tapgem.app.ui.SyntheticInput
import com.tapgem.app.ui.WidgetView

/**
 * TapGem — one Activity, one Service. The screen is a 640×480 logical
 * viewport drawn to both eyes; the strip runs along the top, the desktop
 * fills the rest.
 *
 * Controls:
 *   • Right trackpad — moves the cursor. Single tap = click at cursor: a
 *     window's title bar (or body) makes it active and brings it to the
 *     front; buttons inside pages and apps get a real tap; the strip's
 *     thumbnails, camera and wave respond; empty space starts the assistant
 *     when idle. Press-and-hold on a title bar (or a passive widget) and
 *     move = drag the window; on the bottom-right corner = resize. Release
 *     to drop. Double tap anywhere = start / EXIT the assistant.
 *   • Left arm single tap — toggle HUD ⇄ desktop mode.
 */
class MainActivity : AppCompatActivity() {

    private val uiHandler = Handler(Looper.getMainLooper())

    @Volatile private var voiceServiceApi: VoiceServiceApi? = null
    @Volatile private var pendingVoiceActivateUntilMs = 0L
    private var serviceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            voiceServiceApi = service as? VoiceServiceApi
            if (SystemClock.uptimeMillis() < pendingVoiceActivateUntilMs) {
                pendingVoiceActivateUntilMs = 0L
                runCatching { voiceServiceApi?.activateVoice() }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { voiceServiceApi = null }
    }

    // cursor
    private var cursorX = 320f
    private var cursorY = 240f
    private var cursorShown = false
    private var droppedFirstDelta = false
    private var lastTrackpadX = 0f
    private var lastTrackpadY = 0f
    private val cursorGain = 0.45f
    private val hideCursorRunnable = Runnable { setCursorVisible(false) }

    // right-arm taps (key + touch paths, deduped) and press-and-hold drags
    private var rightArmKeyDownMs = 0L
    private var rightArmKeyTracking = false
    private var rightArmKeyLastTapUpMs = 0L
    private var pendingSingleTapClick: Runnable? = null
    private var lastRightArmTapUpAcceptedMs = 0L
    private var rightArmTouchDownMs = 0L
    private var rightArmTouchDownX = 0f
    private var rightArmTouchDownY = 0f
    private var rightArmTouchTracking = false
    private var rightArmTouchMoved = false
    private var holdDragging = false
    private var twoFinger = false
    private var twoFingerLastX = 0f
    private var twoFingerLastY = 0f
    private var lastTouchActivityMs = 0L
    private val longPressRunnable = Runnable { onLongPress() }

    // left arm
    private var leftArmTapDownTimeMs = 0L
    private var leftArmTapDownX = 0f
    private var leftArmTapDownY = 0f
    private var leftArmTapTracking = false
    private var leftArmTapMovedTooFar = false

    private var batteryReceiver: BroadcastReceiver? = null
    private var voiceReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var hudSub: AutoCloseable? = null
    private var catalogSub: AutoCloseable? = null
    private var noticeClearRunnable: Runnable? = null
    private var shownNoticeSeq = -1L
    private var captionHideRunnable: Runnable? = null
    private var captionIdleArmed = false   // the 4 s idle linger has been scheduled for the shown caption
    private var shownCaption: String? = null

    private lateinit var host: DesktopHostView
    private lateinit var wave: SiriWaveView
    private lateinit var edgeScroller: com.tapgem.app.ui.EdgeScroller

    /** 1dp == 1px for the 640×480 viewport. */
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.densityDpi = DisplayMetrics.DENSITY_MEDIUM
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { com.ffalcon.mercury.android.sdk.MercurySDK.init(application) }
        setContentView(R.layout.activity_main)
        host = findViewById(R.id.desktopHost)
        wave = findViewById(R.id.siriWave)
        host.onNotice = { showNotice(it) }
        edgeScroller = com.tapgem.app.ui.EdgeScroller(host, uiHandler,
            busy = { host.interactionActive || holdDragging || twoFinger },
            onActive = { active -> if (active) setCursorVisible(true) else if (cursorShown) setCursorVisible(true) })
        DesktopBridge.thumbnailRenderer = { host.renderThumbnail() }
        WebCommandBus.displayCapturer = { hideCursor, cb -> uiHandler.post { captureDisplay(cb, hideCursor) } }

        installKeyboardSuppressor()
        requestRuntimePermissions()
        startHudClockTicker()
        startBatteryReceiver()
        startNetworkObserver()
        startHudStateObserver()
        startCatalogObserver()
        wave.setOnClickListener { if (HudStateBridge.current().phase == HudStateBridge.VoicePhase.IDLE) activateAssistant() }
        setupDrawers()
        setupSettingsSheet()
        bindVoiceService()
        if (BuildConfig.DEBUG) registerVoiceReceiver()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        runCatching { batteryReceiver?.let { unregisterReceiver(it) } }; batteryReceiver = null
        runCatching { voiceReceiver?.let { unregisterReceiver(it) } }; voiceReceiver = null
        runCatching { networkCallback?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } }
        networkCallback = null
        hudSub?.runCatching { close() }; catalogSub?.runCatching { close() }
        DesktopBridge.thumbnailRenderer = null
        WebCommandBus.displayCapturer = null
        LibraryBridge.opener = null; BookmarksBridge.thumbnailer = null; BookmarksBridge.freezer = null
        bookmarkSub?.runCatching { close() }; desktopSub?.runCatching { close() }
        SettingsBridge.opener = null; host.onSettings = null
        DesktopBridge.saveNow()
        if (serviceBound) runCatching { unbindService(serviceConnection) }
        serviceBound = false
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause(); host.pauseMedia(); DesktopBridge.saveNow()
        // Hand the temple hold back to the system the moment we are not the one using it.
        com.tapgem.app.core.system.LongPressGuard.restore(this)
    }
    override fun onResume() {
        super.onResume(); host.resumeMedia(); hideKeyboard()
        com.tapgem.app.core.system.LongPressGuard.raise(this)
    }

    /**
     * Nothing on the glasses ever wants a soft keyboard. WebViews never take
     * input focus (so the system refuses to attach an IME to them); this is
     * the backstop: the moment the window reports the IME visible, hide it.
     */
    private fun installKeyboardSuppressor() {
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val root = findViewById<View>(R.id.mainContainer)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && insets.isVisible(android.view.WindowInsets.Type.ime())) hideKeyboard()
            v.onApplyWindowInsets(insets)
        }
    }

    private fun hideKeyboard() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.insetsController?.hide(android.view.WindowInsets.Type.ime())
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        }
    }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            wanted += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
    }

    private fun bindVoiceService() {
        val intent = Intent().setClassName(packageName, VoiceServiceApi.SERVICE_FQN)
        serviceBound = runCatching { bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
    }

    /**
     * Debug builds: `adb shell am broadcast -a com.tapgem.app.VOICE --es cmd start|stop|toggle`
     * and a trackpad emulator that feeds the real trackpad code path (the
     * shell can't write to the cyttsp5 device node):
     * `am broadcast -a com.tapgem.app.TRACKPAD --es cmd "swipe x0 y0 x1 y1 | holddrag x0 y0 x1 y1 | tap x y | doubletap x y"`.
     */
    private fun registerVoiceReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    TapGemApp.ACTION_VOICE -> when (i.getStringExtra("cmd")?.lowercase()) {
                        "start", "on" -> activateAssistant()
                        "stop", "off" -> exitAssistant()
                        "toggle", null -> toggleAssistant()
                    }
                    TapGemApp.ACTION_TRACKPAD -> emulateTrackpad(i.getStringExtra("cmd").orEmpty())
                }
            }
        }
        runCatching {
            val f = IntentFilter(TapGemApp.ACTION_VOICE).apply { addAction(TapGemApp.ACTION_TRACKPAD) }
            ContextCompat.registerReceiver(this, r, f, TapGemApp.SHELL_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
            voiceReceiver = r
        }
    }

    private fun emulateTrackpad(cmd: String) {
        val p = cmd.trim().split(Regex("\\s+"))
        val n = p.drop(1).mapNotNull { it.toFloatOrNull() }
        fun ev(action: Int, x: Float, y: Float, downTime: Long) {
            val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            try { handleTrackpadCursorTouch(e) } finally { e.recycle() }
        }
        fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, holdMs: Long, steps: Int = 12, stepMs: Long = 25L) {
            val t0 = SystemClock.uptimeMillis()
            ev(MotionEvent.ACTION_DOWN, x0, y0, t0)
            var delay = holdMs
            for (i in 1..steps) {
                val x = x0 + (x1 - x0) * i / steps; val y = y0 + (y1 - y0) * i / steps
                uiHandler.postDelayed({ ev(MotionEvent.ACTION_MOVE, x, y, t0) }, delay)
                delay += stepMs
            }
            uiHandler.postDelayed({ ev(MotionEvent.ACTION_UP, x1, y1, t0) }, delay + 40L)
        }
        when (p.firstOrNull()?.lowercase()) {
            "cursor" -> if (n.size >= 2) { cursorX = n[0]; cursorY = n[1]; setCursorVisible(true); updateCursorView(); host.updateHover(cursorX, cursorY); edgeScroller.onCursor(cursorX, cursorY) }
            "swipe" -> if (n.size >= 4) stroke(n[0], n[1], n[2], n[3], 0L)
            "holddrag" -> if (n.size >= 4) stroke(n[0], n[1], n[2], n[3], LONG_PRESS_MS + 250L)
            "tap" -> if (n.size >= 2) { val t0 = SystemClock.uptimeMillis(); ev(MotionEvent.ACTION_DOWN, n[0], n[1], t0); uiHandler.postDelayed({ ev(MotionEvent.ACTION_UP, n[0], n[1], t0) }, 60L) }
            "twofinger" -> if (n.size >= 4) {
                // Two fingers: DOWN, POINTER_DOWN, moves of both, POINTER_UP, UP — through the real path.
                val t0 = SystemClock.uptimeMillis()
                fun multi(action: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
                    val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
                        MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER })
                    val coords = arrayOf(MotionEvent.PointerCoords().apply { x = x0; y = y0; pressure = 1f; size = 1f },
                        MotionEvent.PointerCoords().apply { x = x1; y = y1; pressure = 1f; size = 1f })
                    val e = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { handleTrackpadCursorTouch(e) } finally { e.recycle() }
                }
                ev(MotionEvent.ACTION_DOWN, n[0], n[1], t0)
                uiHandler.postDelayed({ multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), n[0], n[1], n[0] + 40f, n[1]) }, 30L)
                val steps = 12; var delay = 60L
                for (i in 1..steps) {
                    val x = n[0] + (n[2] - n[0]) * i / steps; val y = n[1] + (n[3] - n[1]) * i / steps
                    uiHandler.postDelayed({ multi(MotionEvent.ACTION_MOVE, x, y, x + 40f, y) }, delay); delay += 25L
                }
                uiHandler.postDelayed({ multi(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), n[2], n[3], n[2] + 40f, n[3]) }, delay + 20L)
                uiHandler.postDelayed({ ev(MotionEvent.ACTION_UP, n[2], n[3], t0) }, delay + 50L)
            }
            "doubletap" -> if (n.size >= 2) {
                val t0 = SystemClock.uptimeMillis(); ev(MotionEvent.ACTION_DOWN, n[0], n[1], t0)
                uiHandler.postDelayed({ ev(MotionEvent.ACTION_UP, n[0], n[1], t0) }, 60L)
                uiHandler.postDelayed({ val t1 = SystemClock.uptimeMillis(); ev(MotionEvent.ACTION_DOWN, n[0], n[1], t1); uiHandler.postDelayed({ ev(MotionEvent.ACTION_UP, n[0], n[1], t1) }, 60L) }, 200L)
            }
        }
    }

    // ── strip ──────────────────────────────────────────────────────

    private fun startHudClockTicker() {
        val timeTv = findViewById<TextView>(R.id.hudTime)
        val dateTv = findViewById<TextView>(R.id.hudDate)
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val dateFmt = java.text.SimpleDateFormat("EEE · MMM d", java.util.Locale.US)
        val ticker = object : Runnable {
            override fun run() {
                val now = java.util.Date()
                timeTv.text = timeFmt.format(now); dateTv.text = dateFmt.format(now)
                uiHandler.postDelayed(this, 30_000L)
            }
        }
        uiHandler.post(ticker)
    }

    private fun startBatteryReceiver() {
        val tv = findViewById<TextView>(R.id.hudBattery)
        val render = { intent: Intent? ->
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                // "Plugged" is the truth for power policy: the X3 keeps reporting STATUS_FULL after unplugging.
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                val charging = plugged || status == BatteryManager.BATTERY_STATUS_CHARGING
                tv.text = if (pct >= 0) "${if (charging) "⚡ " else ""}$pct%" else "—%"
                applyPowerPolicy(plugged, pct)
            }
        }
        runCatching { render(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))) }
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) = render(i) }
        runCatching { registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)); batteryReceiver = receiver }
    }

    // ── power policy ───────────────────────────────────────────────

    private var onBattery = false

    /**
     * Unplugged, the X3 shuts itself down when display + CPU draw peaks (the
     * vendor firmware logs it as a "user requested" shutdown). On battery the
     * window runs at ECO_BRIGHTNESS of the system brightness, the wave stops
     * animating when idle, hidden WebViews are paused and heavy widgets load
     * one at a time. Plugged in, everything is back to normal.
     */
    private var powerPolicyApplied = false
    private var ecoBaseFactor = ECO_BRIGHTNESS
    private var lumaBand = 0
    private val lumaGuard = object : Runnable {
        override fun run() {
            if (!onBattery) return
            captureDisplay({ bmp ->
                if (bmp != null) { val mean = meanLuma(bmp); bmp.recycle(); applyLumaBand(mean) }
                if (onBattery) uiHandler.postDelayed(this, LUMA_PERIOD_MS)
            }, hideCursor = false)
        }
    }

    private fun applyPowerPolicy(plugged: Boolean, pct: Int) {
        val eco = !plugged
        val factor = if (pct in 0..20) ECO_BRIGHTNESS_LOW else ECO_BRIGHTNESS
        if (powerPolicyApplied && eco == onBattery && factor == ecoBaseFactor) return
        powerPolicyApplied = true
        onBattery = eco
        ecoBaseFactor = factor
        lumaBand = 0
        setBrightnessFactor(if (eco) factor else -1f)
        host.setEcoMode(eco)
        wave.ecoMode = eco
        uiHandler.removeCallbacks(lumaGuard)
        if (eco) uiHandler.postDelayed(lumaGuard, 1_500L)
        Log.i(TAG, "power policy: ${if (eco) "eco (battery $pct%)" else "plugged in"} brightness=${window.attributes.screenBrightness}")
    }

    /** -1 = no override (plugged in); otherwise a fraction of the system brightness. */
    private fun setBrightnessFactor(factor: Float) {
        val lp = window.attributes
        lp.screenBrightness = if (factor < 0f) android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else {
            val system = runCatching { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
            ((system / 255f).coerceIn(0.02f, 1f) * factor).coerceIn(0.02f, 1f)
        }
        window.attributes = lp
    }

    /**
     * The real trigger for the battery shutdown is a mostly-white display:
     * every lit pixel is a waveguide LED. On battery the display is sampled
     * every few seconds and the brighter it is on average, the lower the
     * window brightness goes (with hysteresis so it doesn't flicker).
     */
    private fun applyLumaBand(mean: Float) {
        val band = when {
            mean >= 0.55f - (if (lumaBand == 2) 0.06f else 0f) -> 2
            mean >= 0.32f - (if (lumaBand == 1) 0.06f else 0f) -> 1
            else -> 0
        }
        if (band == lumaBand) return
        lumaBand = band
        val factor = when (band) { 2 -> ecoBaseFactor * 0.45f; 1 -> ecoBaseFactor * 0.7f; else -> ecoBaseFactor }
        setBrightnessFactor(factor)
        Log.i(TAG, "luma guard: mean=%.2f band=%d brightness=%.3f".format(mean, band, window.attributes.screenBrightness))
    }

    private fun meanLuma(bmp: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bmp, 32, 24, true)
        val px = IntArray(32 * 24); small.getPixels(px, 0, 32, 0, 0, 32, 24)
        var sum = 0.0
        for (c in px) sum += (0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)) / 255.0
        if (small !== bmp) small.recycle()
        return (sum / px.size).toFloat()
    }

    private fun startNetworkObserver() {
        val tv = findViewById<TextView>(R.id.hudNetwork)
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun render() {
            val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            val (text, color) = when {
                caps == null -> "⊘" to 0xFFFF5252.toInt()
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi" to 0xFF9FE6B0.toInt()
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cell" to 0xFF7FDBFF.toInt()
                else -> "Net" to 0xFF9FE6B0.toInt()
            }
            uiHandler.post { tv.text = text; tv.setTextColor(color) }
        }
        render()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = render()
            override fun onLost(network: Network) = render()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = render()
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); networkCallback = cb }
    }

    /** Saved-desktop thumbnails, newest first, current ringed in accent. */
    private fun startCatalogObserver() {
        catalogSub?.runCatching { close() }
        catalogSub = DesktopBridge.observeCatalog { uiHandler.post { rebuildThumbs() } }
    }

    private fun rebuildThumbs() {
        val row = findViewById<LinearLayout>(R.id.thumbRow)
        row.removeAllViews()
        val current = DesktopBridge.current()
        DesktopStore.list().take(MAX_THUMBS).forEach { meta ->
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = meta.name
                isClickable = true; isFocusable = true
                val ring = GradientDrawable().apply {
                    cornerRadius = 4f; setColor(0xFF10151C.toInt())
                    setStroke(if (meta.id == current.id) 2 else 1, if (meta.id == current.id) current.theme.accent else 0x66FFFFFF)
                }
                background = ring
                setPadding(2, 2, 2, 2)
                meta.thumb?.let { f -> runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()?.let { setImageBitmap(it) } }
                setOnClickListener {
                    if (meta.id == current.id) { showNotice("\"${meta.name}\" is showing"); return@setOnClickListener }
                    Thread({
                        DesktopBridge.saveNow()
                        DesktopStore.load(meta.id)?.let { d -> uiHandler.post { DesktopBridge.replace(d); showNotice("Loaded \"${d.name}\"") } }
                    }, "tapgem-load").start()
                }
            }
            row.addView(iv, LinearLayout.LayoutParams(44, 30).apply { marginStart = 5 })
        }
    }

    // ── the three drawers: apps & widgets · bookmarks · wallpapers & themes ──

    private lateinit var appsPanel: LibraryPanel
    private lateinit var bookmarkPanel: LibraryPanel
    private lateinit var wallpaperPanel: LibraryPanel
    private var bookmarkSub: AutoCloseable? = null
    private val drawerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val drawers: List<LibraryPanel> get() = listOf(appsPanel, bookmarkPanel, wallpaperPanel)

    /**
     * Three buttons on the left of the strip, one drawer each, one look (LibraryPanel):
     * apps & widgets — everything that can be dropped on the desktop; bookmarks — windows
     * saved with their state; wallpapers & themes — every wallpaper on the glasses plus
     * the theme presets. A tap outside any of them closes it. Voice opens them too.
     */
    private fun setupDrawers() {
        val overlay = findViewById<ViewGroup>(R.id.overlay)
        fun panel(): LibraryPanel = LibraryPanel(this).also {
            overlay.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                leftMargin = 6; topMargin = 70   // below the notice line, so a notice never covers the header
            })
            it.onClose = { closeDrawers() }
        }
        appsPanel = panel(); bookmarkPanel = panel(); wallpaperPanel = panel()
        findViewById<ImageView>(R.id.appsBtn).setOnClickListener { toggleDrawer(LibraryBridge.Drawer.APPS) }
        findViewById<ImageView>(R.id.bookmarkBtn).setOnClickListener { toggleDrawer(LibraryBridge.Drawer.BOOKMARKS) }
        findViewById<ImageView>(R.id.wallpaperBtn).setOnClickListener { toggleDrawer(LibraryBridge.Drawer.WALLPAPERS) }

        appsPanel.onTap = { t ->
            closeDrawers()
            drawerScope.launch {
                when {
                    t.key.startsWith("bm:") || t.key.startsWith("file:") -> Library.apps().firstOrNull { it.key == t.key }?.let { e ->
                        val title = withContext(Dispatchers.IO) { Library.openApp(this@MainActivity, e) }; showNotice("Opened \"$title\"")
                    }
                    t.key.startsWith("kind:") -> Library.KINDS.firstOrNull { "kind:" + it.key == t.key }?.let { k ->
                        Library.addKind(this@MainActivity, k).onFailure { showNotice(it.message ?: "Couldn't add ${k.label}") }
                    }
                    t.key.startsWith("site:") -> Library.SITES.firstOrNull { "site:" + it.key == t.key }?.let { site ->
                        Library.openSite(this@MainActivity, site); showNotice(site.label)
                    }
                }
            }
        }
        bookmarkPanel.onTap = { t ->
            if (t.key == "save") activeWidget()?.let { w -> bookmarkWidget(w) }
            else Bookmarks.list().firstOrNull { "bm:" + it.id == t.key }?.let { b -> closeDrawers(); val placed = BookmarkTool.place(b); showNotice("Opened \"${placed.title}\"") }
        }
        bookmarkPanel.onDelete = { t -> Bookmarks.list().firstOrNull { "bm:" + it.id == t.key }?.let { b -> Bookmarks.delete(b.id); showNotice("Forgot \"${b.title}\"") } }
        wallpaperPanel.onTap = { t ->
            when {
                t.key == "keep" -> { val wp = DesktopBridge.current().wallpaper; val b = Bookmarks.saveWallpaper(wp, BookmarkTool.wallpaperThumb(wp)); showNotice(if (b != null) "Kept wallpaper \"${b.title}\"" else "No wallpaper to keep"); refreshDrawer(LibraryBridge.Drawer.WALLPAPERS) }
                t.key == "none" -> { Library.clearWallpaper(); showNotice("Wallpaper cleared"); refreshDrawer(LibraryBridge.Drawer.WALLPAPERS) }
                t.key.startsWith("theme:") -> { Library.applyTheme(t.key.removePrefix("theme:")); showNotice("Theme ${t.label}"); refreshDrawer(LibraryBridge.Drawer.WALLPAPERS) }
                t.key.startsWith("wp:") -> Library.wallpapers().firstOrNull { "wp:" + it.key == t.key }?.let { e -> Library.applyWallpaper(e); showNotice("Wallpaper \"${e.title}\""); refreshDrawer(LibraryBridge.Drawer.WALLPAPERS) }
            }
        }
        wallpaperPanel.onDelete = { t -> Library.wallpapers().firstOrNull { "wp:" + it.key == t.key }?.let { e ->
            showNotice(if (Library.deleteWallpaper(e)) "Removed \"${e.title}\"" else "\"${e.title}\" is in use on ${e.inUseBy.joinToString()}"); refreshDrawer(LibraryBridge.Drawer.WALLPAPERS) } }

        bookmarkSub = Bookmarks.observe { uiHandler.post { if (bookmarkPanel.isVisible) refreshDrawer(LibraryBridge.Drawer.BOOKMARKS); if (appsPanel.isVisible) refreshDrawer(LibraryBridge.Drawer.APPS) } }
        LibraryBridge.opener = { d, show -> if (show) openDrawer(d) else closeDrawers() }
        BookmarksBridge.thumbnailer = { id, cb -> host.captureWidget(id, 232, 148, cb) }
        DesktopBridge.windowShot = { id, w, h -> host.renderWidgetThumbnail(id, w, h) }
        BookmarksBridge.freezer = { id, done -> host.snapshotAppState(id, done) }
    }

    private fun panelFor(d: LibraryBridge.Drawer) = when (d) { LibraryBridge.Drawer.APPS -> appsPanel; LibraryBridge.Drawer.BOOKMARKS -> bookmarkPanel; LibraryBridge.Drawer.WALLPAPERS -> wallpaperPanel }
    private fun buttonFor(d: LibraryBridge.Drawer): ImageView = findViewById(when (d) { LibraryBridge.Drawer.APPS -> R.id.appsBtn; LibraryBridge.Drawer.BOOKMARKS -> R.id.bookmarkBtn; LibraryBridge.Drawer.WALLPAPERS -> R.id.wallpaperBtn })

    private fun toggleDrawer(d: LibraryBridge.Drawer) { if (panelFor(d).isVisible) closeDrawers() else openDrawer(d) }

    private fun openDrawer(d: LibraryBridge.Drawer) {
        closeDrawers(); settingsPanel.visibility = View.GONE
        refreshDrawer(d)
        if (d == LibraryBridge.Drawer.APPS) snapshotOpenApps { if (appsPanel.isVisible) refreshDrawer(d) }
        buttonFor(d).background = GradientDrawable().apply { cornerRadius = 6f; setColor((DesktopBridge.current().theme.accent and 0x00FFFFFF) or 0x33000000) }
    }

    private fun closeDrawers() {
        for (d in LibraryBridge.Drawer.values()) { panelFor(d).visibility = View.GONE; buttonFor(d).background = null }
    }

    /** Every app window on this desktop gets its picture taken for the drawer; [then] runs once all are in. */
    private fun snapshotOpenApps(then: () -> Unit) {
        val open = DesktopBridge.current().widgets.filter { it.type == WidgetType.APP }
        if (open.isEmpty()) return
        var left = open.size
        for (w in open) host.captureWidget(w.id, 232, 148) { bmp ->
            if (bmp != null) Library.saveAppThumb(Library.appBase(java.io.File(w.source)), bmp)
            if (--left == 0) uiHandler.post(then)
        }
    }

    /** Rebuild one drawer's tiles from the live stores. */
    private fun refreshDrawer(d: LibraryBridge.Drawer) {
        val cur = DesktopBridge.current(); val accent = cur.theme.accent
        val panel = panelFor(d)
        when (d) {
            LibraryBridge.Drawer.APPS -> {
                val apps = Library.apps().map { LibraryPanel.Tile(it.key, it.title, "◈", it.thumb) }
                val kinds = Library.KINDS.map { LibraryPanel.Tile("kind:" + it.key, it.label, it.glyph) }
                val sites = Library.SITES.map { LibraryPanel.Tile("site:" + it.key, it.label, "◎") }
                // The drawer scrolls, so every app is shown; a "+4 more — say its name" line
                // hid exactly the ones people went looking for. Widgets and sites stay one row.
                panel.show("Apps & widgets", listOf(
                    LibraryPanel.Section("Apps", apps, tileW = 100, tileH = 84, cols = 5, maxRows = 20),
                    LibraryPanel.Section("Widgets", kinds, tileW = 84, tileH = 52, cols = 6, maxRows = 1),
                    LibraryPanel.Section("Sites", sites, tileW = 62, tileH = 44, cols = 8, maxRows = 1)
                ), accent, if (apps.isEmpty()) "No apps yet — say “make me a …” and it appears here." else "Apps open where you left them. Tap anything to put it on this desktop.")
            }
            LibraryBridge.Drawer.BOOKMARKS -> {
                val active = activeWidget()
                val tiles = ArrayList<LibraryPanel.Tile>()
                if (active != null) tiles += LibraryPanel.Tile("save", if (active.type == WidgetType.APP) "Save “${active.title}” → apps" else "Save “${active.title}”", "+", dashed = true)
                // Apps live in the apps drawer, wallpapers in the wallpapers drawer: this one is pages and windows.
                tiles += Bookmarks.list().filter { !it.isWallpaper && it.type != WidgetType.APP }.map { b ->
                    LibraryPanel.Tile("bm:" + b.id, b.title, glyphFor(b.type), b.thumb?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }, deletable = true,
                        // A book says where you were; anything else says what it is.
                        badge = Bookmarks.placeOf(b.widget) ?: b.type?.name?.lowercase(java.util.Locale.US))
                }
                panel.show("Bookmarks", listOf(LibraryPanel.Section("Saved pages & windows", tiles, maxRows = 3)), accent,
                    if (tiles.size <= 1) "Saved pages and windows appear here — a video, a PDF at its page, a map. Focus one and tap +, or say “bookmark this”. Apps are in the apps drawer."
                    else "A copy lands on this desktop as you left it. Apps are in the apps drawer, wallpapers in the next one.")
            }
            LibraryBridge.Drawer.WALLPAPERS -> {
                val curKey = Bookmarks.wallpaperKey(cur.wallpaper)
                val all = Library.wallpapers()
                val tiles = ArrayList<LibraryPanel.Tile>()
                if (cur.wallpaper.kind != com.tapgem.app.core.model.WallpaperKind.NONE && all.none { it.key == curKey && it.bookmark != null })
                    tiles += LibraryPanel.Tile("keep", "Keep this", "+", Library.thumbFor(cur.wallpaper), dashed = true)
                tiles += all.map { e ->
                    // A wallpaper a desktop is using has no ✕, and without saying so the tile just
                    // looks broken — especially when the desktop holding it is one you have not
                    // opened in weeks. Name it instead: that is both the reason and the fix.
                    val held = e.inUseBy.firstOrNull()?.let { first ->
                        if (e.inUseBy.size > 1) "on $first +${e.inUseBy.size - 1}" else "on $first"
                    }
                    LibraryPanel.Tile("wp:" + e.key, e.title, "▦", e.thumb,
                        e.wallpaper.colors.takeIf { it.isNotEmpty() && e.thumb == null }?.toIntArray(),
                        selected = e.key == curKey, deletable = e.inUseBy.isEmpty(),
                        badge = held ?: if (e.bookmark != null) "kept" else null)
                }
                tiles += LibraryPanel.Tile("none", "None", "∅", dashed = true, selected = cur.wallpaper.kind == com.tapgem.app.core.model.WallpaperKind.NONE)
                val themes = com.tapgem.app.core.model.Themes.ALL.map { t ->
                    LibraryPanel.Tile("theme:" + t.name, t.name.replaceFirstChar { it.uppercase() }, "", swatch = intArrayOf(t.panel or 0xFF000000.toInt(), t.accent), selected = t.name == cur.theme.name)
                }
                panel.show("Wallpapers & themes", listOf(
                    LibraryPanel.Section("Wallpapers", tiles, maxRows = 2),
                    LibraryPanel.Section("Themes", themes, tileW = 58, tileH = 44, cols = 8, maxRows = 1)
                ), accent, "Tap a wallpaper or theme to use it here. ✕ removes a wallpaper no desktop uses.")
            }
        }
        // Keep the drawer inside the canvas.
        panel.measure(View.MeasureSpec.makeMeasureSpec(628, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST))
    }

    private fun glyphFor(t: WidgetType?): String = when (t) {
        WidgetType.APP -> "◈"; WidgetType.WEB -> "◎"; WidgetType.MAP -> "⌖"; WidgetType.VIDEO -> "▶"; WidgetType.AUDIO -> "♪"
        WidgetType.PDF, WidgetType.EPUB -> "❡"; WidgetType.IMAGE -> "▣"; WidgetType.TICKER -> "≋"; WidgetType.LIVE -> "◉"
        WidgetType.CLOCK -> "◷"; WidgetType.TEXT -> "¶"; WidgetType.MODEL3D -> "◆"; null -> "▦"
    }

    // ── per-window settings (the ⚙ in a title bar / "open the clock settings") ──

    private lateinit var settingsPanel: WidgetSettingsPanel
    private var desktopSub: AutoCloseable? = null

    private fun setupSettingsSheet() {
        val overlay = findViewById<ViewGroup>(R.id.overlay)
        settingsPanel = WidgetSettingsPanel(this)
        overlay.addView(settingsPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        settingsPanel.onClose = { showSettings(null) }
        settingsPanel.onEdit = { id, t -> DesktopBridge.mutateWidget(id, transform = t) }
        host.onSettings = { id -> showSettings(id) }
        SettingsBridge.opener = { id -> showSettings(id) }
        // Voice changes the same widget the sheet shows: keep the chips in step.
        desktopSub = DesktopBridge.observe { d -> uiHandler.post {
            val id = settingsPanel.widgetId ?: return@post
            if (!settingsPanel.isVisible) return@post
            val w = d.widget(id)
            if (w == null) showSettings(null) else settingsPanel.show(w, d.theme)
        } }
    }

    private fun showSettings(id: String?) {
        val w = id?.let { DesktopBridge.current().widget(it) }
        if (w == null) { settingsPanel.visibility = View.GONE; return }
        closeDrawers()
        settingsPanel.show(w, DesktopBridge.current().theme)
        // Under the window's title bar, kept inside the canvas.
        settingsPanel.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(430, View.MeasureSpec.AT_MOST))
        val pw = settingsPanel.measuredWidth; val ph = settingsPanel.measuredHeight
        val lp = settingsPanel.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = (w.x + 4).coerceIn(4, (640 - pw - 4).coerceAtLeast(4))
        lp.topMargin = (w.y + com.tapgem.app.ui.WidgetView.TITLE_H + 4).coerceIn(44, (480 - ph - 4).coerceAtLeast(44))
        settingsPanel.layoutParams = lp
    }

    private fun activeWidget(): Widget? = DesktopBridge.activeWidgetId?.let { DesktopBridge.current().widget(it) }
        ?: DesktopBridge.current().widgets.maxByOrNull { it.z }

    private fun bookmarkWidget(w: Widget) {
        host.snapshotAppState(w.id) {
            val fresh = DesktopBridge.current().widget(w.id) ?: w
            val b = Bookmarks.save(fresh, host.renderWidgetThumbnail(w.id))
            if (w.type == WidgetType.APP) { showNotice("Saved \"${b.title}\" to the apps drawer"); refreshDrawer(LibraryBridge.Drawer.APPS) }
            else { showNotice("Bookmarked \"${b.title}\""); refreshDrawer(LibraryBridge.Drawer.BOOKMARKS) }
        }
    }

    // ── display capture (the model's frames, thumbnails) ──────────

    /** Real pixels of the left-eye viewport (video and web content included); cursor optionally hidden. */
    private fun captureDisplay(cb: (Bitmap?) -> Unit, hideCursor: Boolean = true) {
        val container = findViewById<View>(R.id.mainContainer)
        val cursor = findViewById<ImageView>(R.id.cursorView)
        val w = container.width; val h = container.height
        if (w <= 0 || h <= 0 || isFinishing) { cb(null); return }
        val cursorWas = cursor.visibility
        if (hideCursor) cursor.visibility = View.INVISIBLE
        val doCopy = {
            val loc = IntArray(2); container.getLocationInWindow(loc)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val rect = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
            val finish = { ok: Boolean -> if (hideCursor) cursor.visibility = cursorWas; cb(if (ok) bmp else null) }
            try {
                PixelCopy.request(window, rect, bmp, { r -> finish(r == PixelCopy.SUCCESS) }, uiHandler)
            } catch (e: Exception) {
                Log.w(TAG, "PixelCopy failed: ${e.message}")
                val ok = runCatching { container.draw(android.graphics.Canvas(bmp)) }.isSuccess
                finish(ok)
            }
        }
        if (hideCursor) container.postOnAnimation { container.postOnAnimation { doCopy() } } else doCopy()
    }

    // ── assistant state → wave / notice / caption ──────────────────

    private fun startHudStateObserver() {
        hudSub?.runCatching { close() }
        hudSub = HudStateBridge.observe { st -> uiHandler.post { renderWave(st); renderNotice(st); renderCaption(st) } }
    }

    private fun renderWave(st: HudStateBridge.State) {
        wave.mode = when {
            st.connection == HudStateBridge.ConnectionStatus.ERROR -> SiriWaveView.Mode.ERROR
            st.phase == HudStateBridge.VoicePhase.IDLE -> SiriWaveView.Mode.IDLE
            st.connection == HudStateBridge.ConnectionStatus.CONNECTING -> SiriWaveView.Mode.CONNECTING
            st.phase == HudStateBridge.VoicePhase.THINKING -> SiriWaveView.Mode.THINKING
            st.phase == HudStateBridge.VoicePhase.SPEAKING -> SiriWaveView.Mode.SPEAKING
            else -> SiriWaveView.Mode.LISTENING
        }
        wave.level = st.level
    }

    /** The notice timer is armed once per notification, not on every level tick. */
    private fun renderNotice(st: HudStateBridge.State) {
        val tv = findViewById<TextView>(R.id.hudNotice)
        val text = st.notification?.trim().takeUnless { it.isNullOrBlank() }
        if (text == null) {
            noticeClearRunnable?.let { uiHandler.removeCallbacks(it) }; noticeClearRunnable = null
            tv.visibility = View.GONE; tv.text = ""; shownNoticeSeq = st.notificationSeq; return
        }
        if (st.notificationSeq == shownNoticeSeq) return
        shownNoticeSeq = st.notificationSeq
        noticeClearRunnable?.let { uiHandler.removeCallbacks(it) }
        tv.text = text; tv.visibility = View.VISIBLE
        val seq = st.notificationSeq
        val clear = Runnable {
            noticeClearRunnable = null
            if (HudStateBridge.current().notificationSeq == seq) HudStateBridge.update { it.copy(notification = null) } else tv.visibility = View.GONE
        }
        noticeClearRunnable = clear
        uiHandler.postDelayed(clear, NOTICE_DISPLAY_MS)
    }

    private fun renderCaption(st: HudStateBridge.State) {
        val tv = findViewById<TextView>(R.id.caption)
        val text = st.caption?.trim().takeUnless { it.isNullOrBlank() }
        if (text == null) {
            captionHideRunnable?.let { uiHandler.removeCallbacks(it) }; captionHideRunnable = null
            tv.visibility = View.GONE; shownCaption = null; return
        }
        val idle = st.phase == HudStateBridge.VoicePhase.IDLE
        // The same caption again: only the first update after the session goes idle may re-arm the
        // hide (to shorten the linger to 4 s). Every later unrelated state change — a notice, a level
        // tick, a navigation update — must leave the timer alone, or the caption never clears.
        if (text == shownCaption) {
            if (!idle || captionIdleArmed) return
            captionIdleArmed = true
        } else {
            shownCaption = text
            captionIdleArmed = idle
        }
        captionHideRunnable?.let { uiHandler.removeCallbacks(it) }
        tv.text = text; tv.visibility = View.VISIBLE
        val hide = Runnable { captionHideRunnable = null; tv.visibility = View.GONE }
        captionHideRunnable = hide
        uiHandler.postDelayed(hide, if (idle) 4_000L else CAPTION_LINGER_MS)
    }

    private fun showNotice(msg: String) = HudStateBridge.notice(msg)

    // ── assistant control ──────────────────────────────────────────

    private fun activateAssistant() {
        val api = voiceServiceApi
        if (api == null) { pendingVoiceActivateUntilMs = SystemClock.uptimeMillis() + 4000L; bindVoiceService(); return }
        runCatching { api.activateVoice() }
    }

    private fun exitAssistant() {
        runCatching { voiceServiceApi?.shutdownVoice() }
        HudStateBridge.update { it.copy(phase = HudStateBridge.VoicePhase.IDLE, connection = HudStateBridge.ConnectionStatus.IDLE, level = 0f, caption = null) }
    }

    private fun toggleAssistant() {
        if (HudStateBridge.current().phase == HudStateBridge.VoicePhase.IDLE) activateAssistant() else exitAssistant()
    }

    private fun toggleMode() {
        val next = if (DesktopBridge.current().mode == DesktopMode.HUD) DesktopMode.DESKTOP else DesktopMode.HUD
        DesktopBridge.mutate { it.copy(mode = next) }
        showNotice("${next.name.lowercase().replaceFirstChar { it.uppercase() }} mode")
    }

    // ── input ──────────────────────────────────────────────────────

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val deviceName = ev.device?.name ?: InputDevice.getDevice(ev.deviceId)?.name.orEmpty()
        if (deviceName.contains("cyttsp6", ignoreCase = true)) { handleLeftArmTouch(ev); return true }
        if (deviceName.contains("cyttsp5", ignoreCase = true)) { handleTrackpadCursorTouch(ev); return true }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) Log.i(TAG, "unmatched touch device='$deviceName'")
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            // Route trackpad scroll to the widget under the cursor (web/epub/text).
            host.widgetViewAt(cursorX, cursorY)?.let { v ->
                val local = MotionEvent.obtain(event); local.setLocation(cursorX - v.left, cursorY - v.top)
                v.dispatchGenericMotionEvent(local); local.recycle()
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun handleLeftArmTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { leftArmTapDownTimeMs = SystemClock.uptimeMillis(); leftArmTapDownX = ev.x; leftArmTapDownY = ev.y; leftArmTapMovedTooFar = false; leftArmTapTracking = true }
            MotionEvent.ACTION_MOVE -> if (leftArmTapTracking && !leftArmTapMovedTooFar &&
                Math.hypot((ev.x - leftArmTapDownX).toDouble(), (ev.y - leftArmTapDownY).toDouble()) > LEFT_ARM_TAP_MOVE_TOLERANCE_PX) leftArmTapMovedTooFar = true
            MotionEvent.ACTION_UP -> {
                val ok = leftArmTapTracking && !leftArmTapMovedTooFar
                leftArmTapTracking = false
                if (ok && SystemClock.uptimeMillis() - leftArmTapDownTimeMs < TAP_MAX_MS) toggleMode()
            }
            MotionEvent.ACTION_CANCEL -> leftArmTapTracking = false
        }
    }

    /**
     * The right trackpad (cyttsp5) is a touch surface: DOWN / MOVE / UP are
     * authoritative. A RayNeo service also injects a late KEYCODE_BUTTON_A
     * after a tap (and KEYCODE_BACK after a double tap) — those are echoes
     * of gestures the touch stream already reported and are never acted on
     * when a touch preceded them.
     *
     *   one finger slides         → cursor
     *   one finger down, still
     *     for HOLD_MS, then slides → on a title bar / corner: drag / resize the window;
     *                                on a page, map, book, text: drag the CONTENT (scroll, pan)
     *   two fingers slide         → scroll the window under the cursor
     */
    private fun handleTrackpadCursorTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                droppedFirstDelta = false; lastTrackpadX = ev.x; lastTrackpadY = ev.y
                rightArmTouchDownMs = SystemClock.uptimeMillis(); rightArmTouchDownX = ev.x; rightArmTouchDownY = ev.y
                rightArmTouchMoved = false; rightArmTouchTracking = true; twoFinger = false
                lastTouchActivityMs = rightArmTouchDownMs
                setCursorVisible(true)
                armLongPress()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount >= 2) {
                // Second finger: this is a scroll, not a tap or a hold.
                uiHandler.removeCallbacks(longPressRunnable)
                pendingSingleTapClick?.let { uiHandler.removeCallbacks(it) }; pendingSingleTapClick = null
                rightArmKeyLastTapUpMs = 0L
                twoFinger = true; rightArmTouchMoved = true
                twoFingerLastX = avgX(ev); twoFingerLastY = avgY(ev)
                if (holdDragging) finishHoldDrag()
            }
            MotionEvent.ACTION_MOVE -> {
                lastTouchActivityMs = SystemClock.uptimeMillis()
                if (twoFinger && ev.pointerCount >= 2) {
                    val ax = avgX(ev); val ay = avgY(ev)
                    val dx = (ax - twoFingerLastX) * SCROLL_GAIN; val dy = (ay - twoFingerLastY) * SCROLL_GAIN
                    twoFingerLastX = ax; twoFingerLastY = ay
                    // An open drawer sits over the desktop and is what the fingers mean; only when
                    // it does not take the scroll does this fall through to the window underneath.
                    if (dx != 0f || dy != 0f) {
                        val took = dy != 0f && drawers.any { it.visibility == View.VISIBLE && it.scrollByDelta(-dy) }
                        if (!took) host.scrollContentBy(cursorX, cursorY, dx, dy)
                    }
                    lastTrackpadX = ev.x; lastTrackpadY = ev.y
                    return
                }
                val dx = ev.x - lastTrackpadX; val dy = ev.y - lastTrackpadY
                lastTrackpadX = ev.x; lastTrackpadY = ev.y
                if (rightArmTouchTracking && !rightArmTouchMoved &&
                    Math.hypot((ev.x - rightArmTouchDownX).toDouble(), (ev.y - rightArmTouchDownY).toDouble()) > RIGHT_ARM_TAP_MOVE_TOLERANCE_PX) {
                    rightArmTouchMoved = true
                    if (!holdDragging) uiHandler.removeCallbacks(longPressRunnable)
                }
                if (!droppedFirstDelta) { droppedFirstDelta = true; return }
                if (holdDragging && host.contentDragActive) {
                    // Dragging content: the cursor stays on the grab point, the page moves under it.
                    host.scrollContentBy(cursorX, cursorY, dx * SCROLL_GAIN, dy * SCROLL_GAIN)
                    setCursorVisible(true)
                    return
                }
                moveCursorBy(dx * cursorGain, dy * cursorGain)
            }
            MotionEvent.ACTION_POINTER_UP -> if (twoFinger) {
                // One of the two fingers lifted: the scroll is over; whatever is left is not a tap.
                if (host.contentDragActive) host.endInteraction()
                rightArmTouchMoved = true
            }
            MotionEvent.ACTION_UP -> {
                uiHandler.removeCallbacks(longPressRunnable)
                lastTouchActivityMs = SystemClock.uptimeMillis()
                val ok = rightArmTouchTracking && !rightArmTouchMoved && !twoFinger
                rightArmTouchTracking = false
                if (twoFinger) { twoFinger = false; if (host.contentDragActive) host.endInteraction(); return }
                if (holdDragging) { finishHoldDrag(); return }
                if (ok && SystemClock.uptimeMillis() - rightArmTouchDownMs < TAP_MAX_MS) onRightArmTapUp()
            }
            MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(longPressRunnable); rightArmTouchTracking = false; twoFinger = false
                if (holdDragging) finishHoldDrag() else if (host.contentDragActive) host.endInteraction()
            }
        }
    }

    private fun avgX(ev: MotionEvent): Float { var s = 0f; for (i in 0 until ev.pointerCount) s += ev.getX(i); return s / ev.pointerCount }
    private fun avgY(ev: MotionEvent): Float { var s = 0f; for (i in 0 until ev.pointerCount) s += ev.getY(i); return s / ev.pointerCount }

    private fun armLongPress() {
        uiHandler.removeCallbacks(longPressRunnable)
        if (!holdDragging && !host.interactionActive) uiHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
    }

    /**
     * Finger held still: grab the window (title bar / passive panel / corner),
     * or start dragging the content of a page, map, book or text panel.
     */
    private fun onLongPress() {
        edgeScroller.stop()
        if (holdDragging || host.interactionActive || !rightArmTouchTracking) { Log.d(TAG, "hold: ignored (dragging=$holdDragging interaction=${host.interactionActive} tracking=$rightArmTouchTracking)"); return }
        if (findOverlayHit(cursorX, cursorY) != null) return
        val wv = host.widgetViewAt(cursorX, cursorY) ?: run { Log.d(TAG, "hold: no widget under cursor"); return }
        val lx = cursorX - wv.left; val ly = cursorY - wv.top
        val grab = wv.isGrabZone(lx, ly)
        val scroll = !grab && wv.isScrollableBody(lx, ly)
        Log.d(TAG, "hold on \"${wv.widget.title}\" local=($lx,$ly) grab=$grab scroll=$scroll")
        if (!grab && !scroll) return
        holdDragging = true
        pendingSingleTapClick?.let { uiHandler.removeCallbacks(it) }; pendingSingleTapClick = null
        rightArmKeyLastTapUpMs = 0L
        when {
            grab && wv.isOnResizeHandle(lx, ly) -> host.beginResize(wv, quiet = true)
            grab -> host.beginMove(wv, cursorX, cursorY, quiet = true)
            else -> host.beginContentDrag(wv, cursorX, cursorY)
        }
        setCursorVisible(true)
    }

    private fun finishHoldDrag() {
        holdDragging = false
        val wasContent = host.contentDragActive
        if (host.endInteraction() && !wasContent) showNotice("Placed")
    }

    private fun moveCursorBy(dx: Float, dy: Float) {
        val container = findViewById<View>(R.id.mainContainer)
        val maxW = (container.width.takeIf { it > 0 } ?: 640).toFloat()
        val maxH = (container.height.takeIf { it > 0 } ?: 480).toFloat()
        cursorX = (cursorX + dx).coerceIn(0f, maxW - 1f)
        cursorY = (cursorY + dy).coerceIn(0f, maxH - 1f)
        setCursorVisible(true)
        updateCursorView()
        host.updateHover(cursorX, cursorY)
        if (host.interactionActive) host.updateInteraction(cursorX, cursorY)
        else edgeScroller.onCursor(cursorX, cursorY)
    }

    private fun updateCursorView() {
        val cursor = findViewById<ImageView>(R.id.cursorView)
        cursor.translationX = cursorX; cursor.translationY = cursorY
    }

    private fun setCursorVisible(visible: Boolean) {
        val cursor = findViewById<ImageView>(R.id.cursorView)
        if (!visible && ::edgeScroller.isInitialized && edgeScroller.isScrolling) {
            // Parked at an edge to read: keep the cursor (and the scroll) alive.
            uiHandler.removeCallbacks(hideCursorRunnable); uiHandler.postDelayed(hideCursorRunnable, CURSOR_IDLE_HIDE_MS); return
        }
        if (!visible && ::edgeScroller.isInitialized) edgeScroller.stop()
        // The frame belongs to the cursor: when it idles away, auto windows go back to bare content.
        if (!visible) host.clearHover()
        cursorShown = visible
        cursor.visibility = if (visible) View.VISIBLE else View.GONE
        uiHandler.removeCallbacks(hideCursorRunnable)
        if (visible) { updateCursorView(); uiHandler.postDelayed(hideCursorRunnable, CURSOR_IDLE_HIDE_MS) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // The pad's double-tap arrives as an injected BACK: never let it finish the activity.
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) Log.d(TAG, "BACK (pad double-tap echo) swallowed")
            return true
        }
        // A page or app is active: everything a keyboard produces (scrcpy, a paired keyboard) types
        // into it — letters, backspace, arrows, and Enter (which is then a real Enter, not a tap).
        val typingTarget = host.activeAcceptsKeys()
        val isTapKey = code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_DPAD_CENTER || (code == KeyEvent.KEYCODE_ENTER && !typingTarget)
        if (!isTapKey) {
            if (typingTarget && host.forwardKey(event)) return true
            return super.dispatchKeyEvent(event)
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) { rightArmKeyDownMs = SystemClock.uptimeMillis(); rightArmKeyTracking = true }
            KeyEvent.ACTION_UP -> {
                if (!rightArmKeyTracking) return true
                rightArmKeyTracking = false
                val now = SystemClock.uptimeMillis()
                // A key tap that follows touch activity is the system's echo of a gesture we already handled.
                if (now - lastTouchActivityMs < KEY_ECHO_MS || holdDragging || twoFinger) return true
                if (now - rightArmKeyDownMs < TAP_MAX_MS) onRightArmTapUp()
            }
        }
        return true
    }

    private fun onRightArmTapUp() {
        val now = SystemClock.uptimeMillis()
        if (now - lastRightArmTapUpAcceptedMs < RIGHT_ARM_TAP_DEDUPE_MS) return
        lastRightArmTapUpAcceptedMs = now
        val gap = now - rightArmKeyLastTapUpMs
        val isDouble = rightArmKeyLastTapUpMs > 0L && gap in DOUBLE_TAP_MIN_GAP_MS..DOUBLE_TAP_WINDOW_MS
        if (isDouble) {
            rightArmKeyLastTapUpMs = 0L
            pendingSingleTapClick?.let { uiHandler.removeCallbacks(it) }; pendingSingleTapClick = null
            onDoubleTap()
        } else {
            rightArmKeyLastTapUpMs = now
            pendingSingleTapClick?.let { uiHandler.removeCallbacks(it) }
            val click = Runnable { pendingSingleTapClick = null; onSingleTap() }
            pendingSingleTapClick = click
            uiHandler.postDelayed(click, DOUBLE_TAP_WINDOW_MS + 20L)
        }
    }

    /** Single tap: drop an interaction, else click whatever is under the cursor. */
    private fun onSingleTap() {
        edgeScroller.stop()
        if (host.endInteraction()) { showNotice("Placed"); return }
        val overlayHit = findOverlayHit(cursorX, cursorY)
        // A tap anywhere outside an open drawer / settings sheet closes it (and does nothing else) —
        // except on the strip buttons, which switch drawers.
        val openDrawer = drawers.firstOrNull { it.isVisible }
        if (openDrawer != null && (overlayHit == null || !isInside(overlayHit.view, openDrawer)) && overlayHit?.view?.id !in setOf(R.id.appsBtn, R.id.bookmarkBtn, R.id.wallpaperBtn)) {
            closeDrawers(); return
        }
        if (settingsPanel.isVisible && (overlayHit == null || !isInside(overlayHit.view, settingsPanel))) {
            showSettings(null); return
        }
        if (overlayHit != null) {
            if (overlayHit.isInteractive) dispatchSyntheticTap(overlayHit.view)
            return
        }
        val wv = host.widgetViewAt(cursorX, cursorY)
        if (wv != null) {
            host.focus(wv.widget.id)   // title bar or body: active + front
            if (!wv.isOnTitleBar(cursorX - wv.left, cursorY - wv.top)) dispatchSyntheticTap(wv)
            else showNotice("\"${wv.widget.title}\" is active — hold and move to drag")
            return
        }
        if (HudStateBridge.current().phase == HudStateBridge.VoicePhase.IDLE) activateAssistant()
    }

    /** Double tap anywhere: start the assistant, or EXIT it while it's running. */
    private fun onDoubleTap() {
        if (host.endInteraction()) return
        toggleAssistant()
    }

    private fun dispatchSyntheticTap(target: View) {
        val loc = IntArray(2); target.getLocationOnScreen(loc)
        val root = IntArray(2); findViewById<View>(R.id.mainContainer).getLocationOnScreen(root)
        val localX = cursorX + root[0] - loc[0]; val localY = cursorY + root[1] - loc[1]
        SyntheticInput.tap(target, localX, localY)
    }

    private data class OverlayHit(val view: View, val isInteractive: Boolean)

    private fun isInside(v: View, ancestor: View): Boolean {
        var n: View? = v
        while (n != null) { if (n === ancestor) return true; n = n.parent as? View }
        return false
    }

    /** Hit-test the strip/overlay only (container-local coords). Notices and captions never block taps. */
    private fun findOverlayHit(x: Float, y: Float): OverlayHit? {
        val overlay = findViewById<ViewGroup>(R.id.overlay)
        for (i in overlay.childCount - 1 downTo 0) {
            val hit = findHitDescendant(overlay.getChildAt(i), x, y) ?: continue
            return hit
        }
        return null
    }

    private fun findHitDescendant(root: View, x: Float, y: Float): OverlayHit? {
        if (root.visibility != View.VISIBLE || root.tag == "passthrough") return null
        val loc = IntArray(2); root.getLocationOnScreen(loc)
        val base = IntArray(2); findViewById<View>(R.id.mainContainer).getLocationOnScreen(base)
        val left = (loc[0] - base[0]).toFloat(); val top = (loc[1] - base[1]).toFloat()
        val inside = x >= left && x < left + root.width && y >= top && y < top + root.height
        var inert: OverlayHit? = null
        if (root is ViewGroup) for (i in root.childCount - 1 downTo 0) {
            val h = findHitDescendant(root.getChildAt(i), x, y) ?: continue
            if (h.isInteractive) return h
            if (inert == null) inert = h
        }
        if (!inside) return inert
        if (root.isClickable) return OverlayHit(root, true)
        val bg = root.background
        val surface = bg != null && !(bg is android.graphics.drawable.ColorDrawable && Color.alpha(bg.color) == 0)
        return inert ?: if (surface) OverlayHit(root, false) else null
    }

    companion object {
        private const val TAG = "TapGemMain"
        private const val MAX_THUMBS = 5
        private const val TAP_MAX_MS = 400L
        /** Finger still this long = hold (grab a window / drag its content). Slides start sooner → cursor. */
        private const val LONG_PRESS_MS = 220L
        /** An injected key within this window of touch activity is the pad's echo, not a new tap. */
        private const val KEY_ECHO_MS = 900L
        /** Content drags (two fingers, or hold on a page): pad px → content px. A full pad stroke ≈ 300 px. */
        private const val SCROLL_GAIN = 1.6f
        private const val DOUBLE_TAP_MIN_GAP_MS = 40L
        private const val DOUBLE_TAP_WINDOW_MS = 320L
        private const val LEFT_ARM_TAP_MOVE_TOLERANCE_PX = 60f
        private const val RIGHT_ARM_TAP_MOVE_TOLERANCE_PX = 30f
        private const val RIGHT_ARM_TAP_DEDUPE_MS = 90L
        private const val CURSOR_IDLE_HIDE_MS = 6_000L
        private const val NOTICE_DISPLAY_MS = 3_500L
        private const val CAPTION_LINGER_MS = 8_000L
        /** Fraction of the system brightness used on battery (lower again under 20 %). */
        private const val ECO_BRIGHTNESS = 0.6f
        private const val ECO_BRIGHTNESS_LOW = 0.4f
        private const val LUMA_PERIOD_MS = 4_000L
    }
}
