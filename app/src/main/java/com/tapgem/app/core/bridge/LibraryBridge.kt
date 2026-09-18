package com.tapgem.app.core.bridge

import android.os.Handler
import android.os.Looper

/** Tool → UI: open one of the strip drawers by voice ("show my apps", "show wallpapers"). */
object LibraryBridge {
    enum class Drawer { APPS, BOOKMARKS, WALLPAPERS }
    private val main = Handler(Looper.getMainLooper())
    /** Installed by MainActivity: (drawer, show). */
    @Volatile var opener: ((Drawer, Boolean) -> Unit)? = null
    fun show(d: Drawer, show: Boolean = true) { main.post { opener?.invoke(d, show) } }
}
