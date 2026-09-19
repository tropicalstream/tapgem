package com.tapgem.app.core.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.tapgem.app.core.audio.GeminiAudioPlayer
import com.tapgem.app.core.bridge.DesktopBridge
import com.tapgem.app.core.bridge.HudStateBridge
import com.tapgem.app.core.bridge.HudStateBridge.Channel
import com.tapgem.app.core.bridge.HudStateBridge.ConnectionStatus
import com.tapgem.app.core.bridge.HudStateBridge.VoicePhase
import com.tapgem.app.core.bridge.WebCommandBus
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import com.tapgem.app.core.network.GeminiLiveClient
import com.tapgem.app.core.store.ApiKeyStore
import com.tapgem.app.core.tools.ToolDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Gemini Live voice loop: mic in, audio out, tools in between. Barge-in
 * cuts playback on-device the moment the user speaks (raw MIC keeps the
 * capture path open through playback; an output-scaled gate rejects echo);
 * a mutual-silence watchdog ends idle sessions; double-tap ends them on
 * demand. All session state transitions happen under [sessionLock] so a
 * cancel during connect can never strand an open mic or socket. Tool calls
 * execute one at a time, in order, on their own thread; model audio is
 * written on its own thread so the socket reader never blocks.
 */
class GeminiVoicePipeline(context: Context) {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toolDispatcherThread = Executors.newSingleThreadExecutor { Thread(it, "TapGemTools") }.asCoroutineDispatcher()
    private val audioExecutor = Executors.newSingleThreadExecutor { Thread(it, "TapGemPlayback") }
    private val sessionLock = Any()
    private var connectJob: Job? = null
    private var silenceWatchdogJob: Job? = null
    private var screenFeedJob: Job? = null
    @Volatile private var lastFrameSentMs = 0L
    @Volatile private var frameWantedAtMs = 0L

    @Volatile private var liveSession: GeminiLiveClient.LiveSessionHandle? = null
    @Volatile private var liveSessionReady = false
    @Volatile private var localBargeAtMs = 0L
    @Volatile private var interruptedAtMs = 0L
    @Volatile private var lastMicSpeechMs = 0L
    @Volatile private var captureActive = false
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioThread: Thread? = null
    @Volatile private var activeSessionEpoch = 0L
    @Volatile private var lastConversationActivityMs = 0L
    @Volatile private var lastBargeDiagMs = 0L
    @Volatile private var dropLateOutputUntilMs = 0L
    @Volatile private var endAfterReply = false     // a tool asked for the mic: stop once the current reply has been spoken
    private val toolCallsInFlight = AtomicInteger(0)
    private val cancelledToolIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val caption = StringBuilder()
    @Volatile private var captionFresh = true

    /** Fired (any thread) whenever a session ends for any reason. */
    @Volatile var onSessionEnded: (() -> Unit)? = null

    private val audioPlayer: GeminiAudioPlayer by lazy { GeminiAudioPlayer(appContext) }
    private val liveClient: GeminiLiveClient by lazy {
        GeminiLiveClient(
            apiKeyProvider = { ApiKeyStore.resolve(appContext) },
            desktopSummaryProvider = { DesktopBridge.describe() }
        )
    }
    private val toolDispatcher: ToolDispatcher by lazy { ToolDispatcher(appContext) }

    fun isActive(): Boolean = synchronized(sessionLock) { captureActive || liveSession != null || connectJob?.isActive == true }

    fun activate() {
        synchronized(sessionLock) {
            if (captureActive || liveSession != null || connectJob?.isActive == true) {
                Log.d(TAG, "activate(): already active"); return
            }
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission needed."); return
        }
        if (ApiKeyStore.resolve(appContext).isNullOrBlank()) {
            fail("No Gemini API key — push it via adb (see README)."); return
        }
        lastToolKey = null
        ConversationContext.reset()
        Log.i(TAG, "activate(): starting session")
        endAfterReply = false
        com.tapgem.app.core.livex.MicOwner.stopAssistant = { requestEndAfterReply() }
        com.tapgem.app.core.livex.MicOwner.assistantStarting()
        SessionStats.startSession()
        synchronized(caption) { caption.setLength(0); captionFresh = true }
        cancelledToolIds.clear()
        HudStateBridge.update {
            it.copy(phase = VoicePhase.LISTENING, connection = ConnectionStatus.CONNECTING,
                transcript = null, caption = null, notification = "Connecting…", notificationSeq = it.notificationSeq + 1, level = 0f)
        }
        val epoch = beginSessionEpoch()
        val listener = createListener(epoch)
        connectJob = scope.launch {
            val handle = runCatching { liveClient.startLiveAudioSession(listener) }.getOrNull()
            if (handle == null) {
                fail("Could not connect to Gemini Live."); return@launch
            }
            synchronized(sessionLock) {
                if (!isSessionEpochCurrent(epoch)) { runCatching { handle.close() }; return@launch }
                liveSession = handle
            }
        }
    }

    /** Preflight/connect failure: idle phase, sticky ERROR so the wave goes red. */
    private fun fail(reason: String) {
        HudStateBridge.update {
            it.copy(phase = VoicePhase.IDLE, connection = ConnectionStatus.ERROR,
                notification = reason, notificationSeq = it.notificationSeq + 1)
        }
        onSessionEnded?.invoke()
    }

    /**
     * Hand the microphone over (interpreter / tutor) without cutting the assistant off: the session ends
     * after the reply that follows the tool call has been played out, or after a generous fallback.
     */
    fun requestEndAfterReply() {
        if (!isActive()) return
        if (endAfterReply) return
        endAfterReply = true
        val epoch = activeSessionEpoch
        Thread({
            val deadline = SystemClock.uptimeMillis() + END_AFTER_REPLY_MAX_MS
            while (SystemClock.uptimeMillis() < deadline && endAfterReply && isSessionEpochCurrent(epoch)) { try { Thread.sleep(200) } catch (_: InterruptedException) { return@Thread } }
            if (endAfterReply && isSessionEpochCurrent(epoch)) { Log.i(TAG, "endAfterReply: fallback timeout"); shutdown("handing the microphone over") }
        }, "end-after-reply").also { it.isDaemon = true; it.start() }
    }
    private fun endAfterReplyIfQuiet(epoch: Long) {
        if (!endAfterReply) return
        Thread({
            // The turn may complete before the reply's audio has started playing; give speech a moment to
            // begin, then wait for it to go quiet.
            var quietSince = 0L; var spoke = false
            val started = SystemClock.uptimeMillis(); val deadline = started + END_AFTER_REPLY_MAX_MS
            while (SystemClock.uptimeMillis() < deadline && isSessionEpochCurrent(epoch)) {
                val now = SystemClock.uptimeMillis()
                if (audioPlayer.isActivelySpeaking(windowMs = 600L)) { spoke = true; quietSince = 0L } else if (quietSince == 0L) quietSince = now
                if (quietSince != 0L && now - quietSince >= (if (spoke) 500L else 3_000L)) break
                try { Thread.sleep(100) } catch (_: InterruptedException) { return@Thread }
            }
            if (endAfterReply && isSessionEpochCurrent(epoch)) { endAfterReply = false; shutdown("handing the microphone over") }
        }, "end-after-reply-quiet").also { it.isDaemon = true; it.start() }
    }

    fun shutdown(reason: String? = null, error: Boolean = false) {
        synchronized(sessionLock) {
            invalidateSessionEpoch()
            endAfterReply = false
            Log.i(TAG, "shutdown(reason=$reason)")
            silenceWatchdogJob?.cancel(); silenceWatchdogJob = null
            screenFeedJob?.cancel(); screenFeedJob = null
            captureActive = false
            val thread = audioThread; audioThread = null
            runCatching { thread?.interrupt() }
            val rec = audioRecord; audioRecord = null
            runCatching { rec?.stop() }; runCatching { rec?.release() }
            val session = liveSession; liveSession = null
            liveSessionReady = false; localBargeAtMs = 0L; interruptedAtMs = 0L
            com.tapgem.app.core.bridge.NavCueBridge.speaker = null
            runCatching { session?.close() }
            connectJob?.cancel(); connectJob = null
            dropLateOutputUntilMs = 0L
        }
        runCatching { audioPlayer.release() }
        SessionStats.endSession()
        com.tapgem.app.core.livex.MicOwner.assistantStopped()
        HudStateBridge.update {
            it.copy(phase = VoicePhase.IDLE,
                connection = if (error) ConnectionStatus.ERROR else ConnectionStatus.IDLE,
                transcript = null, level = 0f,
                notification = reason ?: it.notification,
                notificationSeq = if (reason != null) it.notificationSeq + 1 else it.notificationSeq)
        }
        onSessionEnded?.invoke()
    }

    fun release() { shutdown(null); runCatching { audioPlayer.release() }; audioExecutor.shutdownNow(); toolDispatcherThread.close() }

    // ── internals ─────────────────────────────────────────────────────

    @Synchronized private fun beginSessionEpoch(): Long { activeSessionEpoch += 1L; return activeSessionEpoch }
    @Synchronized private fun invalidateSessionEpoch() { activeSessionEpoch += 1L }
    private fun isSessionEpochCurrent(epoch: Long) = activeSessionEpoch == epoch
    private fun noteConversationActivity() { lastConversationActivityMs = SystemClock.uptimeMillis() }

    private fun onLocalBargeIn(level: Float, gate: Float) {
        localBargeAtMs = SystemClock.uptimeMillis(); interruptedAtMs = localBargeAtMs
        Log.i(TAG, "Local barge-in: mic=%.2f over gate=%.2f".format(level, gate))
        noteConversationActivity()
        runCatching { audioPlayer.stopAndFlush() }
        HudStateBridge.update { it.copy(phase = VoicePhase.LISTENING, level = 0f, channel = Channel.USER) }
    }

    private fun inBargeHold(): Boolean =
        localBargeAtMs != 0L && SystemClock.uptimeMillis() - localBargeAtMs < LOCAL_BARGE_HOLD_MS

    /**
     * After an interruption the server keeps streaming the tail of the turn it just cut; those
     * chunks arrive while the user is still talking and would play as stutter between flushes.
     * Drop audio until the mic has been quiet for a moment (the reply to what was said can only
     * start after that), bounded so a noisy room cannot mute the assistant.
     */
    private fun inInterruptHold(): Boolean {
        val at = interruptedAtMs
        if (at == 0L) return false
        val now = SystemClock.uptimeMillis()
        if (now - at > INTERRUPT_HOLD_MAX_MS) { interruptedAtMs = 0L; return false }
        if (now - lastMicSpeechMs > INTERRUPT_MIC_QUIET_MS) { interruptedAtMs = 0L; return false }
        return true
    }

    /**
     * Turn-by-turn cues from the navigation HUD: voiced only while a session is open and the
     * assistant is quietly listening (never over its own speech, a tool run or the user's turn);
     * otherwise the HUD notice the widget posted is all there is.
     */
    private fun speakNavCue(text: String) {
        val session = liveSession ?: return
        if (!liveSessionReady || toolCallsInFlight.get() > 0) return
        if (HudStateBridge.current().phase != VoicePhase.LISTENING) return
        val now = SystemClock.uptimeMillis()
        if (now - lastNavCueMs < 4_000L) return
        lastNavCueMs = now
        runCatching { session.sendClientText("[Navigation cue — tell the user, in one short sentence and nothing else: \"$text\"]") }
    }
    private var lastNavCueMs = 0L

    private fun createListener(epoch: Long) = object : GeminiLiveClient.LiveSessionListener {
        override fun onSessionReady() {
            if (!isSessionEpochCurrent(epoch)) return
            liveSessionReady = true
            com.tapgem.app.core.bridge.NavCueBridge.speaker = { text -> speakNavCue(text) }
            noteConversationActivity()
            HudStateBridge.update { it.copy(connection = ConnectionStatus.CONNECTED, notification = null) }
            startAudioStreaming(epoch)
            startSilenceWatchdog(epoch)
            startScreenFeed(epoch)
        }

        override fun onInputTranscription(text: String) {
            if (!isSessionEpochCurrent(epoch) || text.isBlank()) return
            noteConversationActivity()
            ConversationContext.noteUser(text)
            HudStateBridge.update { it.copy(transcript = text) }
        }

        override fun onOutputTranscription(text: String) {
            if (!isSessionEpochCurrent(epoch) || text.isBlank()) return
            noteConversationActivity()
            if (SystemClock.uptimeMillis() < dropLateOutputUntilMs || inBargeHold()) return
            val snapshot: String
            synchronized(caption) {
                if (captionFresh) { caption.setLength(0); captionFresh = false }
                // Transcription chunks arrive without separators ("paused" + "the") — add one.
                if (caption.isNotEmpty() && !caption.last().isWhitespace() && !text.first().isWhitespace() &&
                    text.first().isLetterOrDigit() && caption.last().isLetterOrDigit()) caption.append(' ')
                caption.append(text)
                if (caption.length > 400) caption.delete(0, caption.length - 400)
                snapshot = caption.toString()
            }
            HudStateBridge.update { it.copy(phase = VoicePhase.SPEAKING, caption = snapshot) }
            Log.i(TAG, "said: ${text.trim()}")
        }

        override fun onModelText(text: String) { if (isSessionEpochCurrent(epoch) && text.isNotBlank()) noteConversationActivity() }

        override fun onModelAudio(mimeType: String, data: ByteArray) {
            if (!isSessionEpochCurrent(epoch) || !liveSessionReady || data.isEmpty()) return
            if (inBargeHold() || inInterruptHold()) return
            noteConversationActivity()
            val norm = (calculatePcm16Peak(data, data.size) / 32_767f).coerceIn(0f, 1f)
            HudStateBridge.update { it.copy(phase = VoicePhase.SPEAKING, level = norm, channel = Channel.MODEL) }
            val gen = audioPlayer.generation
            runCatching { audioExecutor.execute { if (isSessionEpochCurrent(epoch)) runCatching { audioPlayer.playChunk(mimeType, data, gen) } } }
        }

        override fun onInterrupted() {
            if (!isSessionEpochCurrent(epoch)) return
            localBargeAtMs = 0L
            interruptedAtMs = SystemClock.uptimeMillis()
            noteConversationActivity()
            runCatching { audioPlayer.stopAndFlush() }
            HudStateBridge.update { it.copy(phase = VoicePhase.LISTENING, level = 0f) }
        }

        override fun onToolCall(callId: String, name: String, args: String) {
            if (!isSessionEpochCurrent(epoch)) return
            noteConversationActivity()
            Log.i(TAG, "onToolCall id=$callId name=$name args=${args.take(200)}")
            HudStateBridge.update { it.copy(phase = VoicePhase.THINKING) }
            dispatchNativeTool(callId, name, args, epoch)
        }

        override fun onToolCallCancellation(ids: List<String>) {
            if (!isSessionEpochCurrent(epoch)) return
            Log.i(TAG, "tool calls cancelled by server: $ids")
            cancelledToolIds.addAll(ids)
        }

        override fun onGoAway(timeLeft: String?) {
            if (!isSessionEpochCurrent(epoch)) return
            SessionStats.goAwayTimeLeft = timeLeft ?: "a moment"; SessionStats.goAwayAtMs = SystemClock.uptimeMillis()
            HudStateBridge.notice("Session ending soon${timeLeft?.let { " ($it)" }.orEmpty()} — tap the wave to start a new one")
        }

        override fun onUsage(promptTokens: Int, responseTokens: Int, totalTokens: Int, byModality: Map<String, Int>) {
            if (!isSessionEpochCurrent(epoch)) return
            if (totalTokens > 0) { SessionStats.promptTokens = promptTokens; SessionStats.responseTokens = responseTokens; SessionStats.totalTokens = totalTokens; SessionStats.byModality = byModality }
        }

        override fun onTurnComplete(finishReason: String?) {
            if (!isSessionEpochCurrent(epoch)) return
            SessionStats.turns++
            noteConversationActivity()
            localBargeAtMs = 0L
            dropLateOutputUntilMs = SystemClock.uptimeMillis() + LATE_OUTPUT_DROP_MS
            synchronized(caption) { captionFresh = true }
            if (liveSessionReady) HudStateBridge.update { it.copy(phase = VoicePhase.LISTENING) }
            endAfterReplyIfQuiet(epoch)
        }

        override fun onError(message: String) {
            if (!isSessionEpochCurrent(epoch)) return
            Log.w(TAG, "onError: $message")
            shutdown(reason = "Voice error: $message", error = true)
        }

        override fun onClosed(code: Int, reason: String) {
            if (!isSessionEpochCurrent(epoch)) return
            if (code == 1000) shutdown(reason = null)
            else shutdown(reason = "Voice session closed ($code${if (reason.isNotBlank()) ": $reason" else ""})", error = true)
        }
    }

    /** Tools run strictly one at a time, in arrival order, off the socket thread. */
    private fun dispatchNativeTool(callId: String, name: String, args: String, epoch: Long) {
        val toolName = name.trim()
        if (toolName.isBlank()) return
        if (!toolDispatcher.isSupported(toolName)) {
            runCatching { liveSession?.sendToolResponse(callId, toolName, "Unknown tool: $toolName") }
            return
        }
        toolCallsInFlight.incrementAndGet()
        SessionStats.toolCalls++
        scope.launch(toolDispatcherThread) {
            try {
                if (!isSessionEpochCurrent(epoch)) return@launch
                if (cancelledToolIds.remove(callId)) { Log.i(TAG, "skipping cancelled tool $callId"); return@launch }
                val result = toolDispatcher.dispatch(toolName, args)
                var resultText = result.getOrElse { err ->
                    Log.w(TAG, "tool failed $toolName: ${err.message}")
                    err.message?.trim().takeUnless { it.isNullOrBlank() } ?: "Tool $toolName is unavailable right now."
                }
                // The model re-issuing the very same call is the "second-guessing" loop: name it, so it moves on.
                val key = toolName + normalizeArgs(args)
                val readOnly = Regex("\"action\":\"(inspect|read|describe|list|usage|locate|phone_gps)\"").containsMatchIn(key)
                if (key == lastToolKey && resultText == lastToolResult && !readOnly) {
                    resultText = "You already made this exact call and the result is the same — do something different or tell the user what is blocking. $resultText"
                }
                lastToolKey = key; lastToolResult = resultText
                Log.i(TAG, "tool result $toolName: '${resultText.take(200)}'")
                if (!isSessionEpochCurrent(epoch)) return@launch
                if (cancelledToolIds.remove(callId)) { Log.i(TAG, "result for cancelled tool $callId dropped"); return@launch }
                // Evidence first: let the window/page settle, ship a fresh screen frame, THEN the
                // tool result — so the model verifies inside the same turn instead of waiting
                // (and going silent) for a frame that can never wake it.
                val settle = settleMsFor(toolName, args)
                if (settle > 0) {
                    delay(settle)
                    if (!isSessionEpochCurrent(epoch)) return@launch
                    val jpeg = runCatching { captureFrameJpeg() }.getOrNull()
                    if (jpeg != null && isSessionEpochCurrent(epoch)) {
                        runCatching { liveSession?.sendImageFrame(jpeg) }
                        lastFrameSentMs = SystemClock.uptimeMillis(); SessionStats.framesSent++
                    }
                }
                if (!isSessionEpochCurrent(epoch)) return@launch
                runCatching { liveSession?.sendToolResponse(callId, toolName, resultText) }
            } finally {
                toolCallsInFlight.decrementAndGet()
                noteConversationActivity()
            }
        }
    }

    private var lastToolKey: String? = null
    private var lastToolResult: String? = null

    /** JSON args with keys sorted, so the same call in a different key order compares equal. */
    private fun normalizeArgs(args: String): String = runCatching {
        val o = org.json.JSONObject(args)
        o.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> "\"$k\":\"${o.opt(k)}\"" }
    }.getOrDefault(args.trim())

    /** Read-only tools need no evidence; anything that changes the screen gets a settle + frame. */
    private fun settleMsFor(tool: String, args: String): Long {
        val action = Regex("\"action\"\\s*:\\s*\"([a-z_]+)\"").find(args)?.groupValues?.get(1) ?: ""
        return when (tool) {
            "web" -> if (action in setOf("inspect", "read", "eval")) 0L else 800L   // web actions already wait for the page themselves
            "desktop" -> if (action in setOf("describe", "list")) 0L else 500L
            "media" -> if (action == "find") 0L else 900L
            "theme", "wallpaper" -> 600L
            "app_builder" -> 1_200L
            else -> 700L
        }
    }

    /**
     * The model's eyes: a downscaled JPEG of the left-eye display goes to the
     * session every [FRAME_PERIOD_MS] and ~1 s after each tool finishes, so it
     * can verify results (is the video really playing?) instead of trusting
     * tool text. Frames are skipped while the model is mid-sentence to keep
     * the socket lean.
     */
    private fun startScreenFeed(epoch: Long) {
        screenFeedJob?.cancel()
        screenFeedJob = scope.launch {
            delay(1_500L)
            while (isActive && isSessionEpochCurrent(epoch)) {
                val now = SystemClock.uptimeMillis()
                val wanted = frameWantedAtMs
                val due = (wanted != 0L && now >= wanted) || now - lastFrameSentMs >= FRAME_PERIOD_MS
                if (due && liveSessionReady && liveSession != null) {
                    frameWantedAtMs = 0L
                    val jpeg = runCatching { captureFrameJpeg() }.getOrNull()
                    if (jpeg != null && isSessionEpochCurrent(epoch)) {
                        val ok = runCatching { liveSession?.sendImageFrame(jpeg) }.getOrDefault(false) == true
                        if (ok) { lastFrameSentMs = SystemClock.uptimeMillis(); SessionStats.framesSent++ }
                        Log.d(TAG, "screen frame ${jpeg.size / 1024} KB sent=$ok")
                    }
                }
                delay(250L)
            }
        }
    }

    private suspend fun captureFrameJpeg(): ByteArray? {
        val bmp = WebCommandBus.capture(timeoutMs = 2_500L, hideCursor = false) ?: return null
        val w = FRAME_WIDTH; val h = (bmp.height.toLong() * w / bmp.width).toInt().coerceAtLeast(1)
        val scaled = if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
        val out = ByteArrayOutputStream(64 * 1024)
        scaled.compress(Bitmap.CompressFormat.JPEG, FRAME_JPEG_QUALITY, out)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        return out.toByteArray()
    }

    private fun startSilenceWatchdog(epoch: Long) {
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = scope.launch {
            while (isActive && isSessionEpochCurrent(epoch)) {
                delay(SILENCE_WATCHDOG_TICK_MS)
                if (!liveSessionReady || !isSessionEpochCurrent(epoch)) continue
                val now = SystemClock.uptimeMillis()
                if (toolCallsInFlight.get() > 0 || audioPlayer.isActivelySpeaking(windowMs = 600L)) {
                    lastConversationActivityMs = now; continue
                }
                if (now - lastConversationActivityMs >= SILENCE_END_MS) {
                    Log.i(TAG, "Silence watchdog: ending session")
                    shutdown(reason = null)
                    break
                }
            }
        }
    }

    private fun startAudioStreaming(epoch: Long) {
        synchronized(sessionLock) { if (captureActive || !isSessionEpochCurrent(epoch)) return }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) { shutdown("Microphone buffer could not be created.", error = true); return }
        val recorder = createAudioRecord(maxOf(minBuffer * 2, 4096))
            ?: run { shutdown("Microphone could not be opened.", error = true); return }
        val thread = Thread({ captureLoop(recorder, epoch) }, "TapGemAudioThread").apply { isDaemon = true }
        synchronized(sessionLock) {
            // A cancel may have landed while the mic was opening — never keep a
            // recorder the session no longer owns.
            if (captureActive || !isSessionEpochCurrent(epoch)) { runCatching { recorder.release() }; return }
            audioRecord = recorder
            captureActive = true
            runCatching { recorder.startRecording() }
            audioThread = thread
            thread.start()
        }
    }

    private fun captureLoop(recorder: AudioRecord, epoch: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val chunk = ByteArray(2048)
        val silence = ByteArray(2048)
        var bargeFrames = 0
        var userSpeakingUntilMs = 0L
        var readErrors = 0
        try {
            while (captureActive && isSessionEpochCurrent(epoch)) {
                val read = try { recorder.read(chunk, 0, chunk.size) } catch (e: Throwable) { -1 }
                if (read < 0) {
                    if (++readErrors > 20) { shutdown("Microphone stopped delivering audio.", error = true); return }
                    try { Thread.sleep(20) } catch (_: InterruptedException) { return }
                    continue
                }
                if (read == 0) continue
                readErrors = 0
                val norm = (calculatePcm16Peak(chunk, read) / 32_767f).coerceIn(0f, 1f)
                if (norm >= USER_SPEECH_LEVEL) { lastConversationActivityMs = SystemClock.uptimeMillis(); lastMicSpeechMs = lastConversationActivityMs }
                var suppressToServer = false
                if (audioPlayer.isActivelySpeaking()) {
                    val gate = BARGE_BASE_LEVEL + BARGE_ECHO_REJECT * audioPlayer.currentOutputLevel()
                    val nowMs = SystemClock.uptimeMillis()
                    if (nowMs - lastBargeDiagMs >= 500L) {
                        lastBargeDiagMs = nowMs
                        Log.d(TAG, "barge-watch mic=%.3f gate=%.3f".format(norm, gate))
                    }
                    if (norm >= gate) {
                        userSpeakingUntilMs = nowMs + BARGE_HANGOVER_MS
                        if (++bargeFrames >= BARGE_FRAMES) { bargeFrames = 0; onLocalBargeIn(norm, gate) }
                    } else bargeFrames = 0
                    suppressToServer = nowMs >= userSpeakingUntilMs
                } else { bargeFrames = 0; userSpeakingUntilMs = 0L }
                if (HudStateBridge.current().phase == VoicePhase.LISTENING && (norm > 0.04f || System.currentTimeMillis() % 8L == 0L)) {
                    HudStateBridge.update { it.copy(level = norm, channel = Channel.USER) }
                }
                if (isSessionEpochCurrent(epoch)) {
                    runCatching { liveSession?.sendAudioChunkPcm16(if (suppressToServer) silence else chunk, read, SAMPLE_RATE_HZ) }
                }
            }
        } finally {
            // If this thread outlived the session's ownership of the recorder,
            // it is the last one holding it — release it here.
            synchronized(sessionLock) {
                if (audioRecord === recorder) { audioRecord = null; captureActive = false }
            }
            runCatching { recorder.stop() }; runCatching { recorder.release() }
        }
    }

    /** MIC first: VOICE_COMMUNICATION is half-duplex on the X3 (mic mutes during playback). */
    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        for (source in intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION)) {
            val rec = runCatching {
                AudioRecord(source, SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
            runCatching { rec.release() }
        }
        return null
    }

    private fun calculatePcm16Peak(data: ByteArray, size: Int): Int {
        var peak = 0; var i = 0
        while (i < size - 1) {
            val s = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            i += 2
        }
        return peak
    }

    companion object {
        private const val TAG = "TapGemVoice"
        private const val SAMPLE_RATE_HZ = 16_000
        /** Mutual silence that ends a session (double-tap ends it sooner). */
        private const val SILENCE_END_MS = 20_000L
        private const val SILENCE_WATCHDOG_TICK_MS = 250L
        private const val LATE_OUTPUT_DROP_MS = 500L
        private const val END_AFTER_REPLY_MAX_MS = 15_000L
        private const val USER_SPEECH_LEVEL = 0.12f
        private const val BARGE_BASE_LEVEL = 0.13f
        private const val BARGE_ECHO_REJECT = 0.20f
        private const val BARGE_FRAMES = 3
        private const val BARGE_HANGOVER_MS = 900L
        private const val LOCAL_BARGE_HOLD_MS = 1_200L
        private const val INTERRUPT_HOLD_MAX_MS = 2_500L
        private const val INTERRUPT_MIC_QUIET_MS = 350L
        /** Screen frames for the model: cadence and size. */
        private const val FRAME_PERIOD_MS = 3_000L
        private const val FRAME_WIDTH = 512
        private const val FRAME_JPEG_QUALITY = 55
    }
}
