package com.tapgem.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.tapgem.app.core.bridge.DesktopBridge
import com.tapgem.app.core.bridge.HudStateBridge
import com.tapgem.app.core.location.LocationSource
import com.tapgem.app.core.live.WidgetRefreshEngine
import com.tapgem.app.core.store.ApiKeyStore
import com.tapgem.app.core.tools.ToolDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Process bootstrap: vendor SDK, the desktop store/bridge, the widget refresh
 * engine, and the adb-facing receivers — the key push (any build) and a
 * debug-only tool hook that runs any assistant tool without voice (see
 * README "Bench testing"). Both are guarded by android.permission.DUMP so
 * only the adb shell / system can send them.
 */
class TapGemApp : Application() {

    private val toolDispatcher by lazy { ToolDispatcher(this) }

    private val keyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ApiKeyStore.ACTION_SET_API_KEY) return
            val key = intent.getStringExtra("key")?.trim().orEmpty()
            if (key.isBlank()) { Log.w(TAG, "SET_API_KEY without --es key"); return }
            ApiKeyStore.persistFromBroadcast(context, key)
            HudStateBridge.notice("API key saved")
        }
    }

    /** adb shell am broadcast -a com.tapgem.app.TOOL --es name widget --es args '{"action":"add","type":"clock"}' */
    private val toolReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_TOOL) return
            val name = intent.getStringExtra("name")?.trim().orEmpty()
            val args = intent.getStringExtra("args")?.trim().orEmpty()
            if (name.isBlank()) return
            CoroutineScope(Dispatchers.IO).launch {
                val r = toolDispatcher.dispatch(name, args)
                val text = r.getOrElse { "ERROR: ${it.message}" }
                Log.i(TAG, "TOOL $name → $text")
                HudStateBridge.notice(text.lineSequence().first().take(90))
            }
        }
    }

    /**
     * Debug builds only: a simulated position for bench-testing navigation indoors.
     * adb shell am broadcast -a com.tapgem.app.LOCATION --es fix "lat,lon[,accuracyM[,speedMps[,bearingDeg]]]"   (or --es fix clear)
     */
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_LOCATION) return
            val raw = intent.getStringExtra("fix")?.trim().orEmpty()
            val fix = if (raw.equals("clear", ignoreCase = true)) null else {
                val n = raw.split(',').map { it.trim().toDoubleOrNull() }
                if (n.size < 2 || n[0] == null || n[1] == null) { Log.w(TAG, "LOCATION: bad fix '$raw'"); return }
                LocationSource.Fix(n[0]!!, n[1]!!, (n.getOrNull(2) ?: 8.0).toFloat(), "simulated", android.os.SystemClock.elapsedRealtime(),
                    n.getOrNull(3)?.toFloat(), n.getOrNull(4)?.toFloat())
            }
            LocationSource.simulated = fix
            Log.i(TAG, "LOCATION → ${fix?.latLon() ?: "cleared"}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Night mode process-wide: web pages that have a dark theme use it (prefers-color-scheme).
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        runCatching { com.ffalcon.mercury.android.sdk.MercurySDK.init(this) }
        DesktopBridge.init(this)
        com.tapgem.app.core.irc.DiscordClient.init(this)
        com.tapgem.app.core.livex.Interpreter.init(this)
        com.tapgem.app.core.livex.Tutor.init(this)
        com.tapgem.app.core.media.ModelStore.init(this)
        // If the last session was killed mid-drag the whole device is left with a slow
        // long-press; put it back before anything else runs.
        com.tapgem.app.core.system.LongPressGuard.restoreIfLeftRaised(this)
        com.tapgem.app.core.music.MusicPlayer.init(this)
        com.tapgem.app.core.music.SkinStore.init(this)
        // A restored window opens the copy of the page already written to appsDir, and nothing
        // rewrote it on launch — so after an app update the old page came back until some tool
        // action happened to reinstall it, which reads as "the fix didn't take". install() only
        // writes when the bundled asset actually differs, so doing it here before any desktop is
        // built is cheap and means a window always opens on the page this build ships.
        runCatching {
            com.tapgem.app.core.tools.LiveApps.install(this, "winamp.html", com.tapgem.app.core.tools.LiveApps.MUSIC)
            com.tapgem.app.core.tools.LiveApps.install(this, "skins.html", com.tapgem.app.core.tools.LiveApps.MUSIC_SKINS)
            com.tapgem.app.core.tools.LiveApps.install(this, "weather.html", com.tapgem.app.core.tools.LiveApps.WEATHER)
        }
        WidgetRefreshEngine.start(this)
        runCatching {
            ContextCompat.registerReceiver(this, keyReceiver, IntentFilter(ApiKeyStore.ACTION_SET_API_KEY),
                SHELL_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
        }
        if (BuildConfig.DEBUG) runCatching {
            ContextCompat.registerReceiver(this, toolReceiver, IntentFilter(ACTION_TOOL),
                SHELL_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
            ContextCompat.registerReceiver(this, locationReceiver, IntentFilter(ACTION_LOCATION),
                SHELL_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    companion object {
        private const val TAG = "TapGemApp"
        const val ACTION_TOOL = "com.tapgem.app.TOOL"
        const val ACTION_VOICE = "com.tapgem.app.VOICE"
        const val ACTION_TRACKPAD = "com.tapgem.app.TRACKPAD"
        const val ACTION_LOCATION = "com.tapgem.app.LOCATION"
        /** Held by the adb shell and the system only. */
        const val SHELL_PERMISSION = "android.permission.DUMP"
    }
}
