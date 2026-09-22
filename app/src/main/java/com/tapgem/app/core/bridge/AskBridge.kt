package com.tapgem.app.core.bridge

/**
 * Put a turn into the open assistant session as though the user had spoken it.
 *
 * Debug builds only, and only from the VOICE broadcast: it exists so the model's own routing —
 * which request opens what — can be tested repeatedly without playing audio at a microphone,
 * where ambient noise and transcription decide half the outcome. The model sees an ordinary
 * user turn, with the same tools and the same instructions.
 */
object AskBridge {
    @Volatile var asker: ((String) -> Boolean)? = null
    fun ask(text: String): Boolean = asker?.invoke(text) == true
}
