package com.tapgem.app.core.store

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Gemini API key resolution. No login screen — the key arrives over adb:
 *
 *   1. File push (survives reinstalls):
 *      adb push gemini_api_key.txt /sdcard/Android/data/com.tapgem.app/files/gemini_api_key.txt
 *   2. Broadcast (persisted to prefs):
 *      adb shell am broadcast -a com.tapgem.app.SET_API_KEY --es key "AIza..."
 *
 * File wins so a re-push always takes effect immediately.
 */
object ApiKeyStore {

    private const val TAG = "ApiKeyStore"
    const val ACTION_SET_API_KEY = "com.tapgem.app.SET_API_KEY"
    private const val KEY_FILE_NAME = "gemini_api_key.txt"
    private const val PREFS_FILE = "tapgem_config"
    private const val PREF_KEY = "gemini_api_key"
    private const val CACHE_TTL_MS = 10_000L

    @Volatile private var cachedKey: String? = null
    @Volatile private var cachedAtMs: Long = 0L

    fun resolve(context: Context): String? {
        val now = System.currentTimeMillis()
        cachedKey?.let { if (now - cachedAtMs < CACHE_TTL_MS) return it }
        val fromFile = runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching null
            val f = File(dir, KEY_FILE_NAME)
            if (f.exists()) f.readText().trim().takeIf { it.isNotBlank() } else null
        }.getOrNull()
        val key = fromFile ?: context
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(PREF_KEY, null)?.trim()?.takeIf { it.isNotBlank() }
        cachedKey = key
        cachedAtMs = now
        return key
    }

    fun persistFromBroadcast(context: Context, key: String) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, key.trim()).apply()
        cachedKey = key.trim()
        cachedAtMs = System.currentTimeMillis()
        Log.i(TAG, "API key persisted from broadcast (${key.trim().length} chars)")
    }
}

class ApiKeyBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApiKeyStore.ACTION_SET_API_KEY) return
        val key = intent.getStringExtra("key")?.trim().orEmpty()
        if (key.isBlank()) { Log.w("ApiKeyStore", "SET_API_KEY without --es key"); return }
        ApiKeyStore.persistFromBroadcast(context, key)
    }
}
