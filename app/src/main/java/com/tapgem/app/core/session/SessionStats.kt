package com.tapgem.app.core.session

import android.os.SystemClock
import com.tapgem.app.core.network.GeminiLiveClient
import com.tapgem.app.core.network.GeminiRest

/**
 * What the current (or last) Live session has consumed, from the server's
 * own usageMetadata frames. Remaining API *quota* is not something the
 * Gemini API exposes to clients — only Google AI Studio / Cloud Console
 * show it — so this reports what can be known: models, context-window use,
 * turns, tool calls, session age and any announced disconnect.
 */
object SessionStats {
    @Volatile var sessionStartMs = 0L
    @Volatile var sessionActive = false
    @Volatile var promptTokens = 0
    @Volatile var responseTokens = 0
    @Volatile var totalTokens = 0
    @Volatile var byModality: Map<String, Int> = emptyMap()
    @Volatile var turns = 0
    @Volatile var toolCalls = 0
    @Volatile var framesSent = 0
    @Volatile var goAwayTimeLeft: String? = null
    @Volatile var goAwayAtMs = 0L

    fun startSession() {
        sessionStartMs = SystemClock.uptimeMillis(); sessionActive = true
        promptTokens = 0; responseTokens = 0; totalTokens = 0; byModality = emptyMap(); turns = 0; toolCalls = 0; framesSent = 0
        goAwayTimeLeft = null; goAwayAtMs = 0L
    }

    fun endSession() { sessionActive = false }

    fun report(): String {
        val sb = StringBuilder()
        sb.append("Models: voice ${GeminiLiveClient.LIVE_MODEL} (context ${GeminiLiveClient.LIVE_CONTEXT_TOKENS / 1024}k tokens); ")
        sb.append("cards, tickers and apps ${GeminiRest.TEXT_MODEL}; wallpapers ${GeminiRest.IMAGE_MODEL}. ")
        if (sessionStartMs == 0L) { sb.append("No voice session has run yet."); return sb.toString() }
        val ageS = (SystemClock.uptimeMillis() - sessionStartMs) / 1000
        sb.append(if (sessionActive) "This session: " else "Last session: ")
        sb.append("${ageS / 60} min ${ageS % 60} s, $turns turns, $toolCalls tool calls, $framesSent screen frames. ")
        if (totalTokens > 0) {
            val pct = promptTokens * 100 / GeminiLiveClient.LIVE_CONTEXT_TOKENS
            sb.append("Context in use: ${fmt(promptTokens)} of ${fmt(GeminiLiveClient.LIVE_CONTEXT_TOKENS)} tokens ($pct%)")
            if (byModality.isNotEmpty()) sb.append(" — " + byModality.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key.lowercase()} ${fmt(it.value)}" })
            sb.append("; ${fmt(responseTokens)} tokens spoken back. Older context is compressed automatically, so the session does not run out; ")
        } else sb.append("No token report from the server yet. ")
        sb.append(goAwayTimeLeft?.let { "the server announced it will disconnect in $it. " } ?: "no disconnect has been announced. ")
        sb.append("Remaining API quota is not exposed by the API — check Google AI Studio's usage page.")
        return sb.toString()
    }

    private fun fmt(n: Int): String = if (n >= 1000) "%,d".format(n) else n.toString()
}
