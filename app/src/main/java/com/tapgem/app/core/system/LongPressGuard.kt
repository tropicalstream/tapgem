package com.tapgem.app.core.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Keeps the launcher's system menu out of the way while TapGem is on screen.
 *
 * The problem: the launcher registers a global input monitor (`ffalconxr_inputmonitor`) whose
 * GestureDetector fires `onLongPress` on a temple hold and throws up the dock — right in the
 * middle of the hold-then-slide gesture TapGem uses to move and resize windows. It monitors input
 * ahead of app windows and calls `pilferPointers`, so it can take the gesture away mid-drag and no
 * amount of consuming events in this process outranks it.
 *
 * The one thing that does move it: that GestureDetector reads
 * `Settings.Secure.long_press_timeout` (default 700 ms on the X3), and reads it **live** — no
 * launcher restart. Measured on device: at 2500 ms a 1.5 s hold produced no menu and a 4 s hold
 * still did. So this raises the bar rather than disabling anything. The menu stays reachable with
 * a deliberate long hold; it just stops firing under a window drag.
 *
 * Scope is deliberately the foreground, not the individual drag. Raising it per-gesture would race
 * the system's own 700 ms deadline — TapGem only decides a touch is a hold at 220 ms, and the
 * settings write has to propagate to the launcher process before 700 ms. Foreground scope has no
 * race, costs two writes per session, and restores itself the moment TapGem is not in front.
 *
 * Needs `WRITE_SECURE_SETTINGS`, which is `signature|privileged|development` — so it cannot be
 * granted by the app, only handed over once from a machine:
 *
 *     adb shell pm grant com.tapgem.app android.permission.WRITE_SECURE_SETTINGS
 *
 * Ungranted, every call here is a no-op and TapGem behaves exactly as before. Revoking the
 * permission is therefore also the off switch.
 */
object LongPressGuard {
    private const val TAG = "LongPressGuard"
    private const val PREFS = "longpress_guard"

    /** What the original value gets parked in, so a crash mid-session is recoverable. */
    private const val KEY_SAVED = "saved_original_ms"

    /** Long enough to finish any drag or resize; short enough that the menu is still reachable. */
    const val RAISED_MS = 4_000

    /** The framework's own fallback if the setting has never been written. */
    private const val ANDROID_DEFAULT_MS = 400

    /** `Settings.Secure.LONG_PRESS_TIMEOUT` is @hide, so the key is spelled out. */
    private const val KEY_TIMEOUT = "long_press_timeout"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun granted(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun current(c: Context): Int? = runCatching {
        Settings.Secure.getInt(c.contentResolver, KEY_TIMEOUT, ANDROID_DEFAULT_MS)
    }.getOrNull()

    private fun write(c: Context, ms: Int): Boolean = runCatching {
        Settings.Secure.putInt(c.contentResolver, KEY_TIMEOUT, ms)
    }.onFailure { Log.w(TAG, "could not write long_press_timeout: ${it.message}") }.getOrDefault(false)

    /** TapGem came to the front: give a temple hold room to be a window drag. */
    fun raise(c: Context) {
        if (!granted(c)) return
        val now = current(c) ?: return
        if (now == RAISED_MS) return                      // already ours; don't overwrite the saved original
        if (!prefs(c).contains(KEY_SAVED)) prefs(c).edit().putInt(KEY_SAVED, now).apply()
        if (write(c, RAISED_MS)) Log.i(TAG, "long-press bar ${now}ms → ${RAISED_MS}ms (system menu held off)")
    }

    /** TapGem went away (or is shutting down): hand the hold back to the system. */
    fun restore(c: Context) {
        if (!granted(c)) return
        val saved = prefs(c).takeIf { it.contains(KEY_SAVED) }?.getInt(KEY_SAVED, ANDROID_DEFAULT_MS) ?: return
        if (write(c, saved)) Log.i(TAG, "long-press bar restored to ${saved}ms")
        prefs(c).edit().remove(KEY_SAVED).apply()
    }

    /**
     * Startup repair. If the process was killed while raised, nothing ran [restore] and the whole
     * device is left with a sluggish long-press. A saved original still sitting in prefs at launch
     * means exactly that, so put it back before doing anything else.
     */
    fun restoreIfLeftRaised(c: Context) {
        if (!prefs(c).contains(KEY_SAVED)) return
        Log.w(TAG, "previous session ended while the long-press bar was raised — restoring")
        restore(c)
    }

    /** One line for the setup/debug surface: is this actually doing anything? */
    fun status(c: Context): String = when {
        !granted(c) -> "System-menu guard off — needs: adb shell pm grant com.tapgem.app " +
            "android.permission.WRITE_SECURE_SETTINGS"
        else -> "System-menu guard on — long-press is ${current(c) ?: "?"}ms" +
            (prefs(c).takeIf { it.contains(KEY_SAVED) }?.let { " (restores to ${it.getInt(KEY_SAVED, 0)}ms)" } ?: "")
    }
}
