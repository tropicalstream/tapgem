package com.tapgem.app.core.bridge

import android.os.Handler
import android.os.Looper

/** Tool → UI: open / close a window's settings sheet. */
object SettingsBridge {
    private val main = Handler(Looper.getMainLooper())
    /** Installed by MainActivity: widget id (null = close). */
    @Volatile var opener: ((String?) -> Unit)? = null
    fun open(widgetId: String) { main.post { opener?.invoke(widgetId) } }
    fun close() { main.post { opener?.invoke(null) } }
}
