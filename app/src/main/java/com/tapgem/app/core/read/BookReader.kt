package com.tapgem.app.core.read

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.tapgem.app.core.bridge.WebCommandBus
import com.tapgem.app.core.network.GeminiRest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads something aloud, passage after passage, with the words lit on screen as they are said.
 *
 * Why this exists rather than letting the assistant read: the Live model ends a spoken turn after
 * roughly a paragraph — measured twice on the glasses, 336 then 271 characters, with no further
 * tool call however plainly it was asked. So the app does the reading: a speech model voices each
 * passage, and this loop, not the conversation, decides what comes next.
 *
 * The reading surface is a page of the exact words handed to the speech model, which lights each
 * word on a schedule inferred from the audio's length. That is for people who follow text more
 * easily with something holding their place, and the first version taught what that population
 * cannot tolerate: a highlight that skips, text that moves backwards, and a lit word off screen.
 * Each had a specific cause here, called out where it was fixed.
 */
object BookReader {
    private const val TAG = "BookReader"

    /** Big enough to be worth a request, small enough that a stop feels immediate. */
    private const val PASSAGE = 1_000

    /** Passages of speech kept in flight ahead of the voice. */
    private const val AHEAD = 2

    /** A join longer than this stops looking like a pause, so the page is told to hold the place. */
    private const val HOLD_MS = 3_000L

    /**
     * The voice. Pace is a property of the voice, not a parameter — the API rejects every rate
     * field, and an instruction to read slowly made Kore faster. Measured over the same 1000
     * characters: Kore 177 wpm, Charon 186, Aoede 192, Zephyr 142, Rasalgethi 143, Iapetus 148.
     * A rendition varies by about 20% either way (Umbriel read the same text at 169 and 195), so
     * a voice near the edge of the acceptable range will cross it on some passages. A reading
     * specialist's range for this population is 100–170 wpm, ideally 120–150; Zephyr sits in the
     * middle with room to vary in both directions.
     */
    @Volatile var voice: String = "Zephyr"

    /**
     * Audio shorter than this per character means the model did not read the whole passage —
     * seen once as a 1000-character passage rendered in 30 s at 383 wpm. A schedule stretched
     * over that audio would light every word while the voice said half of them. Ask again.
     */
    private const val MIN_SECS_PER_CHAR = 0.040

    /** When the speech model's daily quota returns, and what to say about it meanwhile. */
    @Volatile private var quietUntil = 0L
    @Volatile private var limitMsg: String? = null

    /**
     * Playback speed, pitch preserved. Every voice reads real prose faster than a reading
     * specialist's ceiling for this population (170 wpm): measured on three passages of the book,
     * Zephyr 147–194, Iapetus 164–203, Rasalgethi 174–212. The API has no rate control and an
     * instruction to slow down made it faster, so the stretch happens here, on the device: Android
     * time-stretches the track without changing pitch. At 0.82, 194 becomes 159 and 147 becomes
     * 121 — inside 100–170 across the whole measured spread, and mostly inside the 120–150 ideal.
     */
    private const val SPEED = 0.82f

    private val scope = CoroutineScope(Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var readerId: String? = null
    @Volatile private var title: String = "Reading"

    val isReading: Boolean get() = running.get()

    /** Where in the text the reader is, so stopping and starting again carries on. */
    @Volatile var position: Int = 0
        private set

    /**
     * Start reading [text] aloud, lighting words in the window [readerId], from [from].
     * Returns at once; [onDone] runs when the reading ends or is stopped.
     */
    fun start(context: Context, readerId: String?, text: String, from: Int = 0,
              bookTitle: String = "Reading",
              onProgress: ((Int, Int) -> Unit)? = null, onDone: ((String) -> Unit)? = null) {
        stop()
        if (System.currentTimeMillis() >= quietUntil) limitMsg = null   // yesterday's quota is not today's
        val clean = tidy(text)
        if (clean.isBlank()) { onDone?.invoke("Nothing to read."); return }
        running.set(true)
        this.readerId = readerId
        title = bookTitle
        position = snapToSentence(clean, from.coerceIn(0, clean.length))
        val id = readerId
        job = scope.launch {
            var spoken = 0
            try {
                // The window was created a moment ago and may not have loaded its page yet. The
                // first show() used to fall into the page's not-ready guard and vanish — a whole
                // passage played to an empty window. Wait until the page answers.
                awaitPage(id)

                // Text goes up a passage ahead of the voice: the words are known long before the
                // audio is, and a reader following the lit word needs the lines below it to be the
                // next words, not an empty box. Blocks are numbered in the order they are shown.
                val blockOf = HashMap<Int, Int>(); var blocks = 0
                suspend fun show(from: Int, to: Int) {
                    blocks++; blockOf[from] = blocks
                    page(id, "__read.show($blocks, ${js(title)}, ${js(clean.substring(from, to).trim())}, $to, ${clean.length})")
                }

                // Speech is generated AHEAD of playback, two passages deep. One deep is not
                // enough: a passage's audio takes roughly as long to make as a passage takes to
                // say, so a short passage followed by a slow one lets playback catch up and the
                // reading goes silent at the join — run 5 stopped for 30 s between a 35 s passage
                // and one that took 65 s to generate, with the highlight parked on a full stop.
                // Two in flight covers that and stays well under what the endpoint will take.
                // The requests belong to this reading's own scope, so stopping cancels them
                // instead of leaving them to finish into nothing and spend the day's quota.
                var fetchAt = position
                fun request(): Deferred<ByteArray?>? {
                    if (fetchAt >= clean.length) return null
                    val s0 = fetchAt; val e0 = cut(clean, s0); fetchAt = e0
                    return async { voice(context, clean.substring(s0, e0).trim()) }
                }
                val pending = ArrayDeque<Deferred<ByteArray?>>()
                repeat(AHEAD) { request()?.let { pending.addLast(it) } }

                while (isActive && running.get() && position < clean.length) {
                    val start = position
                    val end = cut(clean, start)
                    val passage = clean.substring(start, end).trim()
                    position = end

                    if (blockOf[start] == null) show(start, end)
                    val block = blockOf[start]!!
                    if (end < clean.length && blockOf[end] == null) show(end, cut(clean, end))

                    // A failed request must not end the reading: one more try, in line, before
                    // giving up. (voice() already retries inside itself.)
                    // If the wait runs past a breath, say so on the page: the highlight stops
                    // claiming to be the word being spoken and holds the place instead. A
                    // specialist's threshold — under 3 s reads as a pause between sentences;
                    // beyond it, silence with a lit word reads as "I broke it".
                    val queued = pending.removeFirstOrNull()
                    val audio = if (queued == null) voice(context, passage) else {
                        var held = false
                        val hold = launch { delay(HOLD_MS); held = true; page(id, "__read.waiting(true)") }
                        val got = queued.await()
                        hold.cancelAndJoin()
                        if (held) page(id, "__read.waiting(false)")
                        got
                    } ?: voice(context, passage)
                    request()?.let { pending.addLast(it) }
                    if (audio == null || audio.isEmpty()) {
                        // Say it on the page too. A reading that dies silently leaves the last
                        // word lit and looks exactly like a pause — run 3 sat that way for twelve
                        // minutes with nothing on screen to say the voice had gone.
                        val why = limitMsg ?: "The reading voice stopped working."
                        Log.w(TAG, "no audio for passage at $start; stopping — $why")
                        page(id, "__read.note(${js(why)})")
                        onDone?.invoke(why); break
                    }

                    val secs = audio.size / 2.0 / GeminiRest.SPEECH_RATE_HZ / SPEED
                    page(id, "__read.retime($block, ${wordTimings(passage, secs).joinToString(",", "[", "]")}, ${(secs * 1000).toInt()})")
                    onProgress?.invoke(end, clean.length)
                    play(audio, id, block)
                    spoken++
                }
                val msg = if (position >= clean.length) "Finished reading." else "Stopped after $spoken passage(s)."
                page(id, if (position >= clean.length) "__read.finished(${js(msg)})" else "__read.stop()")
                if (running.get()) onDone?.invoke(msg)
            } catch (t: Throwable) {
                Log.w(TAG, "reader failed: ${t.message}")
                page(id, "__read.stop()")
                onDone?.invoke("Reading stopped: ${t.message}")
            } finally {
                running.set(false)
                releaseTrack()
            }
        }
    }

    fun stop() {
        running.set(false)
        job?.cancel(); job = null
        releaseTrack()
        // The page runs its own clock; left alone it kept lighting words for 42 s after the voice
        // had gone — a highlight moving with nothing to follow.
        readerId?.let { id -> scope.launch { page(id, "__read.stop()") } }
    }

    // ── text ───────────────────────────────────────────────────────

    /**
     * Section-break decoration — a line of asterisks or dashes — is not words. Left in, the voice
     * said something for each one and the page lit twenty asterisks in a row, 130 ms each, eight of
     * them off screen. A separator becomes a paragraph break and nothing more.
     */
    private fun tidy(text: String): String = text
        // A scene break is typeset with NON-BREAKING spaces between the asterisks, which is why
        // this rule used to miss it: twenty "*" tokens were read as words and lit one by one,
        // 20 flickers of nothing in 2.7 s, and they inflated that passage's measured pace from
        // 162 to 177 wpm. The break survives as the blank line it always was.
        .replace(Regex("(?m)^[ \\t\\u00a0]*[*_\\-—–·•~=]+([ \\t\\u00a0]+[*_\\-—–·•~=]+)*[ \\t\\u00a0]*$"), "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    /** A reading that starts in the middle of a word ("gain." for "again.") is wrong from its first
     *  syllable. Move forward to the start of the next sentence, or failing that the next word. */
    private fun snapToSentence(text: String, at: Int): Int {
        if (at <= 0 || at >= text.length) return at.coerceIn(0, text.length)
        if (text[at - 1].isWhitespace()) return at
        val window = text.substring(at, minOf(text.length, at + 400))
        val m = Regex("[.!?\\u201d]\\s+|\\n\\n").find(window)
        if (m != null) return at + m.range.last + 1
        val ws = window.indexOfFirst { it.isWhitespace() }
        return if (ws >= 0) at + ws + 1 else at
    }

    /** End a passage on a sentence, so a pause never lands mid-clause. */
    private fun cut(text: String, start: Int): Int {
        val room = PASSAGE
        var end = (start + room).coerceAtMost(text.length)
        if (end >= text.length) return text.length
        val window = text.substring(start, end)
        val para = window.lastIndexOf("\n\n")
        val stop = if (para > room / 3) para
                   else window.lastIndexOfAny(charArrayOf('.', '!', '?', '”', '"'))
        if (stop > room / 3) end = start + stop + 1
        return end
    }

    /**
     * Give each word a slice of the passage's audio.
     *
     * The speech model returns audio with no timing in it, so the shape is inferred: longer words
     * take longer, and a comma or a full stop buys a pause. The shares are normalised to the audio's
     * actual length, so the schedule ends exactly when the voice does.
     */
    private fun wordTimings(passage: String, seconds: Double): List<Int> {
        val tokens = passage.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val weights = tokens.map { t ->
            var w = t.length.toDouble() + 1.0
            val last = t.lastOrNull()
            if (last != null && last in ",;:") w += 2.5
            if (last != null && last in ".!?”") w += 5.0
            w
        }
        val total = weights.sum().takeIf { it > 0 } ?: return emptyList()
        val ms = seconds * 1000.0
        return weights.map { ((it / total) * ms).toInt().coerceAtLeast(40) }
    }

    // ── voice ──────────────────────────────────────────────────────

    /**
     * Speech for one passage, or null when it cannot be had. The first version gave up on the
     * first failed request, so a single transient error ended a whole reading — run 2 stopped
     * dead after one page for exactly that. Errors are retried with a pause, and a truncated
     * rendering is asked for again.
     *
     * A 429 is not a failure of the same kind. A per-minute rate limit is a pause: wait it out
     * and carry on reading, because ending a book over a twenty-second wait is far worse for the
     * reader than the wait. The daily cap is the opposite — run 3 spent it mid-test and then
     * burned six pointless retries in five seconds — so it is recorded and every later request
     * skipped until the quota returns, with something true to say about why.
     */
    private fun voice(context: Context, passage: String): ByteArray? {
        if (passage.isBlank()) return ByteArray(0)
        if (System.currentTimeMillis() < quietUntil) return null
        var short: ByteArray? = null
        var tries = 0       // requests that failed and are worth making again
        var waits = 0       // pauses for a rate limit, which is not a failure
        while (tries < 3 && waits < 3 && running.get()) {
            val result = GeminiRest.speak(context, passage, voice)
            val limit = result.exceptionOrNull() as? GeminiRest.SpeechLimited
            if (limit != null && limit.perDay) {
                quietUntil = System.currentTimeMillis() + limit.retrySecs * 1_000L
                limitMsg = "Today's reading voice allowance is used up. It comes back in ${humanWait(limit.retrySecs)}."
                Log.w(TAG, "speech daily quota spent; voice returns in ${limit.retrySecs}s")
                return null
            }
            if (limit != null) {
                val secs = limit.retrySecs.coerceIn(5L, 90L)
                Log.i(TAG, "speech rate limited; waiting ${secs}s")
                waits++
                if (!nap(secs * 1_000L)) return null
                continue
            }
            val pcm = result
                .onFailure { Log.w(TAG, "speech request failed (try ${tries + 1}): ${it.message}") }
                .getOrNull()
            if (pcm == null) { tries++; nap(1_500L * tries); continue }
            val secs = pcm.size / 2.0 / GeminiRest.SPEECH_RATE_HZ
            if (secs >= passage.length * MIN_SECS_PER_CHAR) return pcm
            Log.w(TAG, "speech too short (${"%.1f".format(secs)}s for ${passage.length} chars) — asking again")
            short = pcm; tries++
        }
        return short
    }

    /** Wait, but give up the instant the reading is stopped. False if it was. */
    private fun nap(ms: Long): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (!running.get()) return false
            Thread.sleep(200)
        }
        return running.get()
    }

    /** A wait said the way someone would say it, since this ends up spoken. */
    private fun humanWait(secs: Long): String {
        val mins = Math.round(secs / 60.0)
        return when {
            mins <= 1L -> "a minute"
            mins < 90L -> "about $mins minutes"
            else -> { val h = Math.round(secs / 3600.0); if (h == 1L) "about an hour" else "about $h hours" }
        }
    }

    /** Poll until the page has loaded and can take instructions, up to ten seconds. */
    private suspend fun awaitPage(widgetId: String?) {
        val id = widgetId ?: return
        repeat(50) {
            val r = runCatching {
                WebCommandBus.execute(id, WebCommandBus.Command("eval", mapOf("js" to "typeof window.__read")), 3_000L)
            }.getOrNull().orEmpty()
            if (r.contains("object")) return
            kotlinx.coroutines.delay(200)
        }
        Log.w(TAG, "reader page not ready after 10 s; continuing anyway")
    }

    /**
     * Play the passage and do not return until the last sample has been heard.
     *
     * The first version returned when the last buffer was written, then waited on the play state
     * — but stop() flips that state at once while up to 512 KB (ten seconds) is still queued. So
     * the loop moved to the next passage ten seconds early, every time, and the last 15–32 words of
     * every passage were never lit though the voice said them. Now it waits for the playback head
     * to reach the last frame written.
     */
    private suspend fun play(pcm: ByteArray, widgetId: String?, block: Int) = withContext(Dispatchers.IO) {
        val min = AudioTrack.getMinBufferSize(GeminiRest.SPEECH_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(GeminiRest.SPEECH_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, pcm.size.coerceAtMost(512 * 1024)))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        val totalFrames = pcm.size / 2
        runCatching {
            // Pitch-preserving stretch. If a device refused it the schedule would outrun the voice,
            // so the speed the track actually took is logged and the wall clock is checked below.
            runCatching { t.playbackParams = android.media.PlaybackParams().setSpeed(SPEED).setPitch(1.0f)
                .setAudioFallbackMode(android.media.PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT) }
                .onFailure { Log.w(TAG, "playback speed not accepted: ${it.message}") }
            val began = System.currentTimeMillis()
            t.play()
            Log.i(TAG, "block $block: ${totalFrames} frames, speed ${runCatching { t.playbackParams.speed }.getOrDefault(1f)}, expect ${"%.1f".format(totalFrames / GeminiRest.SPEECH_RATE_HZ.toDouble() / SPEED)}s")
            page(widgetId, "__read.play($block)")
            var off = 0
            while (off < pcm.size && running.get()) {
                val n = t.write(pcm, off, minOf(8192, pcm.size - off))
                if (n <= 0) break
                off += n
            }
            // Drain: the head position counts frames actually rendered.
            var last = -1; var stuck = 0
            while (running.get()) {
                val head = t.playbackHeadPosition
                if (head >= totalFrames) break
                if (head == last) { if (++stuck > 75) break } else { stuck = 0; last = head }   // ~3 s with no movement
                Thread.sleep(40)
            }
            Log.i(TAG, "block $block: played in ${"%.1f".format((System.currentTimeMillis() - began) / 1000.0)}s")
            page(widgetId, "__read.ended($block)")
        }
        releaseTrack()
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        runCatching { if (t.state == AudioTrack.STATE_INITIALIZED) t.pause(); t.flush(); t.stop() }
        runCatching { t.release() }
    }

    // ── page ───────────────────────────────────────────────────────

    private fun js(s: String): String = org.json.JSONObject.quote(s)

    private suspend fun page(widgetId: String?, call: String) {
        val id = widgetId ?: return
        runCatching {
            WebCommandBus.execute(id, WebCommandBus.Command("eval", mapOf("js" to "window.__read && $call")), 6_000L)
        }
    }
}
