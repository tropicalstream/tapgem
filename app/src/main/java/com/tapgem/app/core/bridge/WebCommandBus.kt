package com.tapgem.app.core.bridge

import android.graphics.Bitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Tool → view plumbing that needs a live View: web-page commands (run JS in a
 * widget's WebView, tap an element natively, drive history) and display
 * capture. The host view / activity install handlers on attach; tools await
 * a result string or bitmap.
 */
object WebCommandBus {

    class Command(val action: String, val args: Map<String, String>) {
        fun arg(vararg keys: String): String? = keys.firstNotNullOfOrNull { args[it]?.trim()?.takeIf { v -> v.isNotBlank() } }
    }

    /** (widgetId, command, done) — must complete `done` exactly once, on any thread. */
    @Volatile var webHandler: ((String, Command, (String) -> Unit) -> Unit)? = null

    /**
     * Captures the real display (video frames and web content included).
     * (hideCursor, done) — screenshots hide the cursor; the model's frames don't
     * bother (no flicker). Completes with null on failure.
     */
    @Volatile var displayCapturer: ((Boolean, (Bitmap?) -> Unit) -> Unit)? = null

    suspend fun execute(widgetId: String, command: Command, timeoutMs: Long = 15_000L): String {
        val h = webHandler ?: return "The desktop view isn't available right now."
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var done = false
                h(widgetId, command) { result -> synchronized(this) { if (!done) { done = true; cont.resume(result) } } }
            }
        } ?: "The page did not respond in time."
    }

    suspend fun capture(timeoutMs: Long = 5_000L, hideCursor: Boolean = true): Bitmap? {
        val c = displayCapturer ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var done = false
                c(hideCursor) { bmp -> synchronized(this) { if (!done) { done = true; cont.resume(bmp) } } }
            }
        }
    }
}
