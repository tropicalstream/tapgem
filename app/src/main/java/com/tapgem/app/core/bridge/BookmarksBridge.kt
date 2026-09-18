package com.tapgem.app.core.bridge

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Tool → UI plumbing for bookmarks: open/close the strip panel, render one window's thumbnail. */
object BookmarksBridge {
    private val main = Handler(Looper.getMainLooper())

    /** Installed by MainActivity: widget id → small bitmap of that window as it looks now (main thread). */
    @Volatile var thumbnailer: ((String) -> Bitmap?)? = null
    /** Installed by MainActivity: freeze a window's live app state into the model, then call back (main thread). */
    @Volatile var freezer: ((String, () -> Unit) -> Unit)? = null

    /** Blocking (≤ 1.5 s): make sure the widget's stored state is current before it is snapshotted. */
    fun freeze(widgetId: String) {
        val f = freezer ?: return
        val latch = CountDownLatch(1)
        main.post { runCatching { f(widgetId) { latch.countDown() } }.onFailure { latch.countDown() } }
        latch.await(1_500, TimeUnit.MILLISECONDS)
    }

    fun showPanel(show: Boolean) = LibraryBridge.show(LibraryBridge.Drawer.BOOKMARKS, show)

    /** Blocking (≤ 1.5 s) thumbnail for the tool thread; null if the UI isn't up. */
    fun thumbnail(widgetId: String): Bitmap? {
        val t = thumbnailer ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching { t(widgetId) }.getOrNull()
        var out: Bitmap? = null
        val latch = CountDownLatch(1)
        main.post { out = runCatching { t(widgetId) }.getOrNull(); latch.countDown() }
        latch.await(1_500, TimeUnit.MILLISECONDS)
        return out
    }
}
