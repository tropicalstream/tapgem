package com.tapgem.app.core.bridge

import java.util.concurrent.ConcurrentHashMap

/**
 * Turn-by-turn cues from the navigation HUD to whoever can voice them. The voice
 * pipeline registers a speaker while a session is open; with nobody listening the
 * cue is only the HUD notice the widget already posted. The page's periodic state
 * summaries are kept per widget so "what's my ETA?" can be answered from them.
 */
object NavCueBridge {
    @Volatile var speaker: ((String) -> Unit)? = null
    private val states = ConcurrentHashMap<String, String>()

    /**
     * With a Live session open the assistant speaks the cue in its own voice. Without one the
     * platform synthesiser does, so navigation is never silent just because nobody is talking.
     */
    fun cue(text: String) {
        val s = speaker
        if (s != null) s.invoke(text) else com.tapgem.app.core.nav.NavAnnouncer.speak(text)
    }
    fun state(widgetId: String, json: String) { states[widgetId] = json }
    fun stateOf(widgetId: String): String? = states[widgetId]
    fun forget(widgetId: String) { states.remove(widgetId) }
}
