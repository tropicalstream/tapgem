package com.tapgem.app.core.nav

import android.content.Context
import android.util.Log
import com.tapgem.app.core.livex.LiveAudioSession
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turn-by-turn speech when no Live conversation is running.
 *
 * The Live pipeline owns the voice while a session is open; the rest of the time the glasses used
 * to navigate in silence, because the cue had nobody to speak it. Neither obvious way of filling
 * that gap is available here:
 *
 *  - Gemini's TTS models (`*-tts-*`) answer 404 on this key, in both v1beta and v1alpha.
 *  - The X3 Pro ships no speech engine at all: `TextToSpeech` initialises with ERROR and the
 *    device lists zero `TTS_SERVICE` providers, so `android.speech.tts` is a dead end.
 *
 * What does work is a Gemini audio model with the microphone never attached — text in, speech out,
 * no conversation and no claim on the mic. One session is held open for the trip so a cue costs a
 * text frame rather than a handshake, and it is closed the moment navigation ends.
 *
 * This is a network path with no offline fallback, because the hardware offers none: with no
 * connection the HUD still shows every turn, it just cannot say it.
 */
object NavAnnouncer {
    private const val TAG = "NavAnnouncer"
    /** A small audio-out model; the assistant's own model is left alone for the conversation. */
    private const val MODEL = "models/gemini-2.5-flash-native-audio-latest"
    private const val REPEAT_MS = 12_000L

    private lateinit var appContext: Context
    private var session: LiveAudioSession? = null
    @Volatile private var ready = false
    @Volatile private var lastSpoken = ""
    @Volatile private var lastAt = 0L
    @Volatile private var pending: String? = null
    @Volatile var enabled = true

    fun init(context: Context) { appContext = context.applicationContext }

    private fun setup(): JSONObject = JSONObject()
        .put("model", MODEL)
        .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
            "You are the navigation voice of a pair of AR glasses. Speak each line you are given " +
            "aloud, once, exactly as written — it is already phrased for a driver. Do not greet, " +
            "acknowledge, summarise, add pleasantries or ask anything. Say only the instruction."))))

    @Synchronized private fun ensureSession() {
        if (session?.closed == false) return
        if (!::appContext.isInitialized) return
        ready = false
        session = LiveAudioSession(appContext, "nav", setup(), halfDuplex = false, useMic = false) { ev ->
            when (ev.optString("type")) {
                "ready" -> { ready = true; pending?.let { p -> pending = null; session?.sendText(p) } }
                "closed" -> { ready = false; session = null }
                "error" -> Log.w(TAG, "nav voice: ${ev.optString("text")}")
            }
        }.also { it.open() }
    }

    /**
     * Speak a cue. Repeats of the same sentence inside [REPEAT_MS] are dropped: the HUD re-posts a
     * cue as the distance counts down, and hearing one turn three times is worse than silence.
     */
    fun speak(text: String, interrupt: Boolean = false) {
        val t = text.trim()
        if (!enabled || t.isBlank() || !::appContext.isInitialized) return
        val now = System.currentTimeMillis()
        if (t == lastSpoken && now - lastAt < REPEAT_MS) return
        lastSpoken = t; lastAt = now
        ensureSession()
        if (ready) session?.sendText(t) else pending = t   // spoken as soon as the socket is up
    }

    /** Navigation finished: drop the socket rather than holding it open across the day. */
    @Synchronized fun stop() {
        pending = null; lastSpoken = ""
        runCatching { session?.close() }; session = null; ready = false
    }
}
