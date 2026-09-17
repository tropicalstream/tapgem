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

    override fun onCreate() {
        super.onCreate()
        // Night mode process-wide: web pages that have a dark theme use it (prefers-color-scheme).
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        runCatching { com.ffalcon.mercury.android.sdk.MercurySDK.init(this) }
        DesktopBridge.init(this)
        WidgetRefreshEngine.start(this)
        runCatching {
            ContextCompat.registerReceiver(this, keyReceiver, IntentFilter(ApiKeyStore.ACTION_SET_API_KEY),
                SHELL_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
        }
        if (BuildConfig.DEBUG) runCatching {
            ContextCompat.registerReceiver(this, toolReceiver, IntentFilter(ACTION_TOOL),
                SHELL_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    companion object {
        private const val TAG = "TapGemApp"
        const val ACTION_TOOL = "com.tapgem.app.TOOL"
        const val ACTION_VOICE = "com.tapgem.app.VOICE"
        const val ACTION_TRACKPAD = "com.tapgem.app.TRACKPAD"
        /** Held by the adb shell and the system only. */
        const val SHELL_PERMISSION = "android.permission.DUMP"
    }
}
