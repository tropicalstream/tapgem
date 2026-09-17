package com.tapgem.app.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Cursor taps and tool-driven key presses are delivered as real-looking
 * input: finger tool type, touchscreen source, DOWN then UP a beat later —
 * the shape Chromium's gesture detector and Android widgets expect.
 */
object SyntheticInput {

    private val main = Handler(Looper.getMainLooper())
    private const val TAP_UP_DELAY_MS = 60L

    fun tap(target: View, localX: Float, localY: Float, onDone: (() -> Unit)? = null) {
        val downTime = SystemClock.uptimeMillis()
        val down = touch(downTime, downTime, MotionEvent.ACTION_DOWN, localX, localY)
        try { target.dispatchTouchEvent(down) } finally { down.recycle() }
        main.postDelayed({
            val up = touch(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, localX, localY)
            try { target.dispatchTouchEvent(up) } finally { up.recycle() }
            onDone?.invoke()
        }, TAP_UP_DELAY_MS)
    }

    /** A finger drag delivered as real touch: start → moves → end. Scrolls pages, pans maps, drags sliders. */
    fun dragStart(target: View, x: Float, y: Float): Long {
        val t = SystemClock.uptimeMillis()
        val down = touch(t, t, MotionEvent.ACTION_DOWN, x, y)
        try { target.dispatchTouchEvent(down) } finally { down.recycle() }
        return t
    }

    fun dragMove(target: View, downTime: Long, x: Float, y: Float) {
        val mv = touch(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y)
        try { target.dispatchTouchEvent(mv) } finally { mv.recycle() }
    }

    fun dragEnd(target: View, downTime: Long, x: Float, y: Float, cancel: Boolean = false) {
        val up = touch(downTime, SystemClock.uptimeMillis(), if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, x, y)
        try { target.dispatchTouchEvent(up) } finally { up.recycle() }
    }

    private fun touch(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        return MotionEvent.obtain(downTime, eventTime, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    /** Named key ("enter", "arrow_down") or a single character. Returns false if unknown. */
    fun key(target: View, name: String): Boolean {
        val k = name.trim().lowercase().replace(' ', '_').replace('-', '_')
        val code = when (k) {
            "enter", "return", "go", "submit" -> KeyEvent.KEYCODE_ENTER
            "escape", "esc" -> KeyEvent.KEYCODE_ESCAPE
            "space", "spacebar" -> KeyEvent.KEYCODE_SPACE
            "tab" -> KeyEvent.KEYCODE_TAB
            "backspace", "delete", "del" -> KeyEvent.KEYCODE_DEL
            "arrow_down", "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "arrow_up", "up" -> KeyEvent.KEYCODE_DPAD_UP
            "arrow_left", "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "arrow_right", "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "page_down", "pagedown" -> KeyEvent.KEYCODE_PAGE_DOWN
            "page_up", "pageup" -> KeyEvent.KEYCODE_PAGE_UP
            "home" -> KeyEvent.KEYCODE_MOVE_HOME
            "end" -> KeyEvent.KEYCODE_MOVE_END
            "f", "fullscreen" -> KeyEvent.KEYCODE_F
            "k" -> KeyEvent.KEYCODE_K
            "m" -> KeyEvent.KEYCODE_M
            else -> -1
        }
        if (code >= 0) {
            val t = SystemClock.uptimeMillis()
            target.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            target.dispatchKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
            return true
        }
        if (name.length == 1) {
            val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(name.toCharArray()) ?: return false
            events.forEach { target.dispatchKeyEvent(it) }
            return true
        }
        return false
    }
}
