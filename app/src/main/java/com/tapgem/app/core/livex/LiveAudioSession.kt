package com.tapgem.app.core.livex

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.tapgem.app.core.audio.GeminiAudioPlayer
import com.tapgem.app.core.store.ApiKeyStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The glasses' microphone, opened once and fanned out to whoever is listening (an interpreter
 * running two directions at once shares one capture). 16 kHz mono PCM in 100 ms chunks, which
 * is what every Live model wants. Publishes a smoothed level for the pages' meters.
 */
object MicSource {
    private const val TAG = "MicSource"
    const val RATE = 16_000
    fun interface Sink { fun onChunk(pcm: ByteArray, size: Int) }
    private val sinks = CopyOnWriteArrayList<Sink>()
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile var level = 0f; private set
    private var diag = 0

    @Synchronized fun add(s: Sink) { sinks += s; if (rec == null) start() }
    @Synchronized fun remove(s: Sink) { sinks -= s; if (sinks.isEmpty()) stop() }
    @Synchronized fun stop() {
        val r = rec; rec = null; thread?.interrupt(); thread = null
        runCatching { r?.stop() }; runCatching { r?.release() }; level = 0f
    }
    private fun start() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_COMMUNICATION first: that capture path carries the platform's acoustic echo canceller, so
        // the mic keeps hearing the person while the glasses' own speaker output is subtracted — a live
        // interpreter must not go deaf while it talks.
        val r = listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC).firstNotNullOfOrNull { src ->
            runCatching { AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 4096)) }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } ?: run { Log.w(TAG, "microphone could not be opened"); return }
        runCatching {
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(r.audioSessionId)?.let { it.enabled = true; Log.i(TAG, "AEC on (session ${r.audioSessionId})") }
            else Log.i(TAG, "AEC not available on this device")
        }
        rec = r; runCatching { r.startRecording() }
        thread = Thread({
            val buf = ByteArray(3200)   // 100 ms
            while (rec === r && !Thread.currentThread().isInterrupted) {
                val n = runCatching { r.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n <= 0) { try { Thread.sleep(10) } catch (_: InterruptedException) { break }; continue }
                var peak = 0; var i = 0
                while (i + 1 < n) { val v = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8); peak = maxOf(peak, abs(v.toShort().toInt())); i += 2 }
                level = level * 0.6f + (peak / 32767f) * 0.4f
                if (com.tapgem.app.BuildConfig.DEBUG && (++diag % 50) == 0) Log.d(TAG, "mic peak %.3f (%d bytes/chunk, %d sinks)".format(peak / 32767f, n, sinks.size))
                for (s in sinks) runCatching { s.onChunk(buf, n) }
            }
        }, "MicSource").also { it.isDaemon = true; it.start() }
    }
}

/**
 * One Gemini Live audio session with any model and setup: feeds it the microphone, plays what
 * comes back, and reports transcripts, text, tool calls and turn events as JSON to a listener.
 * Used by the interpreter (translate model, continuous) and the tutor (agent model, turns).
 */
/**
 * [halfDuplex]: gate the microphone while any live session is playing real audio, so the glasses do not
 * hear — and translate — their own voice. The speaker and the mic sit centimetres apart on the frame and
 * the translate model has no echo cancellation; without this, a translated sentence comes straight back
 * in as new speech and loops.
 */
class LiveAudioSession(private val context: Context, val tag: String, private val setup: JSONObject, private val halfDuplex: Boolean = false,
                      /** Speech only: never attach the microphone, so the session is one-way and
                       *  cannot collide with whoever owns the mic. Used for spoken navigation. */
                      private val useMic: Boolean = true,
                      private val listener: (JSONObject) -> Unit) {
    companion object {
        private const val TAG = "LiveAudioSession"
        private const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        private val live = CopyOnWriteArrayList<LiveAudioSession>()
        /** True while any live session's speaker is producing audible output (silence padding does not count). */
        fun anySpeaking() = live.any { it.player.isActivelySpeaking(windowMs = 450L) }
    }
    private var ws: WebSocket? = null
    private val player = GeminiAudioPlayer(context)
    @Volatile var ready = false; private set
    @Volatile var closed = false; private set
    @Volatile var muted = false
    @Volatile var speaking = false; private set
    private var lastAudioMs = 0L
    private var sent = 0
    private var zeros = ByteArray(0)
    private val sink = MicSource.Sink { pcm, n ->
        if (ready && !muted) {
            // While the glasses are talking, send silence of the same cadence instead of the mic: the
            // stream stays continuous for the model but it never hears its own translation.
            val gated = halfDuplex && anySpeaking()
            val src = if (gated) { if (zeros.size < n) zeros = ByteArray(n); zeros } else pcm
            val ok = ws?.send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", Base64.encodeToString(src, 0, n, Base64.NO_WRAP)))).toString()) ?: false
            if (com.tapgem.app.BuildConfig.DEBUG && (++sent % 30) == 0) Log.d(TAG, "$tag → sent $sent chunks, last ok=$ok queued=${ws?.queueSize()}")
        }
    }

    fun open(): Boolean {
        val key = ApiKeyStore.resolve(context)?.trim()?.takeIf { it.isNotBlank() } ?: run { emit(JSONObject().put("type", "error").put("text", "No Gemini API key.")); return false }
        live += this
        ws = http.newWebSocket(Request.Builder().url("$URL?key=$key").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { webSocket.send(JSONObject().put("setup", setup).toString()) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (com.tapgem.app.BuildConfig.DEBUG && !text.contains("inlineData") && text.length > 3) Log.d(TAG, "$tag ← ${text.take(220).replace(Regex("\\s+"), " ")}")
                runCatching { handle(JSONObject(text)) }.onFailure { Log.w(TAG, "$tag: ${it.message}") }
            }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { onMessage(webSocket, bytes.utf8()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { finish("closed: $reason ($code)") }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) { finish("failed: ${t.message ?: t.javaClass.simpleName}" + (response?.let { " HTTP ${it.code}" } ?: "")) }
        })
        return true
    }

    private fun handle(o: JSONObject) {
        if (o.has("setupComplete")) { ready = true; if (useMic) MicSource.add(sink); emit(JSONObject().put("type", "ready")); return }
        val sc = o.optJSONObject("serverContent")
        if (sc != null) {
            sc.optJSONObject("inputTranscription")?.let { emit(JSONObject().put("type", "in").put("text", it.optString("text")).put("lang", it.optString("languageCode")).put("final", it.optBoolean("finished"))) }
            sc.optJSONObject("outputTranscription")?.let { emit(JSONObject().put("type", "out").put("text", it.optString("text")).put("lang", it.optString("languageCode")).put("final", it.optBoolean("finished"))) }
            if (sc.optBoolean("interrupted")) { player.stopAndFlush(); emit(JSONObject().put("type", "interrupted")) }
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    p.optJSONObject("inlineData")?.let { d ->
                        val bytes = Base64.decode(d.optString("data"), Base64.DEFAULT)
                        // The translate model streams silence between phrases; only real audio counts as "speaking".
                        var peak = 0; var j = 0; while (j + 1 < bytes.size) { val v = ((bytes[j].toInt() and 0xff) or (bytes[j + 1].toInt() shl 8)).toShort().toInt(); if (abs(v) > peak) peak = abs(v); j += 64 }
                        if (peak > 400) { speaking = true; lastAudioMs = System.currentTimeMillis() } else if (System.currentTimeMillis() - lastAudioMs > 600) speaking = false
                        player.playChunk(d.optString("mimeType", "audio/pcm;rate=24000"), bytes)
                    }
                    p.optString("text").takeIf { it.isNotBlank() }?.let { emit(JSONObject().put("type", "text").put("text", it)) }
                }
            }
            if (sc.optBoolean("turnComplete")) { speaking = false; emit(JSONObject().put("type", "turn")) }
            if (sc.optBoolean("generationComplete")) emit(JSONObject().put("type", "generated"))
        }
        o.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            val responses = JSONArray()
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                emit(JSONObject().put("type", "tool").put("name", c.optString("name")).put("args", c.optJSONObject("args") ?: JSONObject()))
                responses.put(JSONObject().put("id", c.optString("id")).put("name", c.optString("name")).put("response", JSONObject().put("result", "ok")))
            }
            send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)))
        }
        o.optJSONObject("goAway")?.let { emit(JSONObject().put("type", "error").put("text", "Session is ending soon — reconnecting.")) }
    }

    fun sendText(text: String) = send(JSONObject().put("clientContent", JSONObject().put("turns", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))).put("turnComplete", true)))
    private fun send(o: JSONObject) { ws?.send(o.toString()) }
    fun isPlaying() = player.isActivelySpeaking()
    private fun emit(o: JSONObject) { listener(o.put("session", tag)) }
    private fun finish(why: String) { if (closed) return; closed = true; ready = false; live -= this; if (useMic) MicSource.remove(sink); runCatching { player.release() }; emit(JSONObject().put("type", "closed").put("text", why)) }
    fun close() { if (closed) return; closed = true; ready = false; live -= this; if (useMic) MicSource.remove(sink); runCatching { ws?.close(1000, "bye") }; ws = null; runCatching { player.release() }; emit(JSONObject().put("type", "closed").put("text", "stopped")) }
}

/**
 * Who has the microphone. The assistant, the interpreter and the tutor never share it: starting
 * one stops the others, and a request made *from inside* an assistant turn ("start the
 * interpreter") is carried out the moment that turn's session ends.
 */
object MicOwner {
    @Volatile var assistantActive = false
    @Volatile var stopAssistant: (() -> Unit)? = null           // set by the voice pipeline
    private var pending: (() -> Unit)? = null
    private val stoppers = CopyOnWriteArrayList<() -> Unit>()     // interpreter / tutor stop functions

    fun registerApp(stop: () -> Unit) { stoppers += stop }
    /** Called by the assistant when it takes the mic. */
    fun assistantStarting() { assistantActive = true; for (s in stoppers) runCatching { s() } }
    /** Called by the assistant when it lets go; runs whatever a tool asked for. */
    fun assistantStopped() { assistantActive = false; val p = pending; pending = null; p?.let { Thread { Thread.sleep(400); it() }.start() } }
    /** From a tool: run [block] now if the mic is free, else end the assistant's turn and run it after. */
    fun whenMicFree(block: () -> Unit): Boolean {
        if (!assistantActive) { block(); return true }
        pending = block
        stopAssistant?.invoke()   // the pipeline ends the session once its reply has been spoken
        return false
    }
}
