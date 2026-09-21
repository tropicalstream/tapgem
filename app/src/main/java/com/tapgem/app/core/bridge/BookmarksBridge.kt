package com.tapgem.app.core.bridge

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Tool → UI plumbing for bookmarks: open/close the strip panel, render one window's thumbnail. */
object BookmarksBridge {
    private val main = Handler(Looper.getMainLooper())

    /** Installed by MainActivity: widget id → small bitmap of that window as it looks now, delivered
     *  to the callback (started on the main thread; the pixels arrive when the compositor has them). */
    @Volatile var thumbnailer: ((String, (Bitmap?) -> Unit) -> Unit)? = null
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

    /** Blocking (≤ 2 s) thumbnail for the tool thread; null if the UI isn't up. Never call on the main thread. */
    fun thumbnail(widgetId: String): Bitmap? {
        val t = thumbnailer ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        var out: Bitmap? = null
        val latch = CountDownLatch(1)
        main.post { runCatching { t(widgetId) { bmp -> out = bmp; latch.countDown() } }.onFailure { latch.countDown() } }
        latch.await(2_000, TimeUnit.MILLISECONDS)
        return out
    }
}
