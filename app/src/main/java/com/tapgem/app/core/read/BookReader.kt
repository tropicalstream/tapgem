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
import kotlinx.coroutines.coroutineScope
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
    private const val AHEAD = 3

    /** A join longer than this stops looking like a pause, so the page is told to hold the place. */
    private const val HOLD_MS = 3_000L

    /** How far into a file a Project Gutenberg header can be and still be a header. */
    private const val HEADER_LIMIT = 20_000

    /** Measured: a passage takes 22-29s to voice. What the page tells the reader to expect. */
    private const val EXPECT_SECS = 30

    /**
     * Test hook (debug builds): hold up one join by this many milliseconds, to see what a reader
     * actually sees when a request runs long. The path that holds someone's place is the one that
     * must not be trusted untested, and real joins are under 1.5s so it never runs by itself.
     */
    @Volatile var stall = 0L
    private const val STALL_AT = 2

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
     * What a rendering's pace must be to be believed, measured on the audio before it is slowed.
     * Pace is the honest test, not seconds per character: a per-character bound of 0.150 s let a
     * passage through at 73 wpm because it happened to be 985 characters long, and the schedule
     * then crawled two and a half times slower than the voice. Real renderings of real prose sit
     * between 130 and 210 wpm; outside this band the audio is not the passage read once.
     */
    /** How far a passage may be halved, and the length below which halving is pointless. */
    private const val SPLIT_DEPTH = 2
    private const val SPLIT_MIN = 200

    /** Short lines closer together than this are one heading, not two — and at most this many. */
    private const val HEADING_RUN = 80
    private const val HEADING_LINES = 3

    private const val MIN_WPM = 100.0
    private const val MAX_WPM = 260.0

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
                fun request(): Deferred<Speech?>? {
                    if (fetchAt >= clean.length) return null
                    val s0 = fetchAt; val e0 = cut(clean, s0); fetchAt = e0
                    return async { voiceOrSplit(context, clean.substring(s0, e0).trim()) }
                }
                val pending = ArrayDeque<Deferred<Speech?>>()
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
                    val audio = if (queued == null) voiceOrSplit(context, passage) else {
                        // The start of a reading is acknowledged straight away, not after the
                        // join threshold: a specialist's ruling, and the reason is that silence
                        // after a command is read by this population as "I did it wrong" rather
                        // than "it is working". Mid-reading the threshold still applies, because
                        // a short join is just a breath.
                        val first = spoken == 0
                        var held = false
                        val hold = launch {
                            delay(if (first) 100L else HOLD_MS)
                            held = true
                            page(id, if (first) "__read.waiting(true, $EXPECT_SECS)" else "__read.waiting(true)")
                        }
                        if (stall > 0 && spoken == STALL_AT) { delay(stall); stall = 0 }
                        waitingOn = queued
                        val got = runCatching { queued.await() }.getOrNull()
                        waitingOn = null
                        hold.cancelAndJoin()
                        if (held) page(id, "__read.waiting(false)")
                        got
                    } ?: voiceOrSplit(context, passage)
                    request()?.let { pending.addLast(it) }
                    if (skipped.getAndSet(false)) {
                        page(id, "__read.skipped($block)")
                        continue                        // the next passage is already on its way
                    }
                    if (audio == null || audio.pcm.isEmpty()) {
                        // Say it on the page too. A reading that dies silently leaves the last
                        // word lit and looks exactly like a pause — run 3 sat that way for twelve
                        // minutes with nothing on screen to say the voice had gone.
                        val why = limitMsg ?: "The reading voice stopped working."
                        Log.w(TAG, "no audio for passage at $start; stopping — $why")
                        page(id, "__read.note(${js(why)})")
                        onDone?.invoke(why); break
                    }

                    // One schedule per rendering. A passage read in two halves has two paces, and
                    // spreading one average over both puts the highlight ahead in the first half
                    // and behind in the second.
                    var at = 0
                    val times = ArrayList<Int>()
                    for ((chunk, bytes) in audio.parts) {
                        val cs = bytes / 2.0 / GeminiRest.SPEECH_RATE_HZ / SPEED
                        times += wordTimings(chunk, cs)
                        at += bytes
                    }
                    val secs = audio.pcm.size / 2.0 / GeminiRest.SPEECH_RATE_HZ / SPEED
                    page(id, "__read.retime($block, ${times.joinToString(",", "[", "]")}, ${(secs * 1000).toInt()})")
                    onProgress?.invoke(end, clean.length)
                    play(audio.pcm, id, block)
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
    private fun tidy(text: String): String = unwrapGutenberg(text)
        // Structural whitespace is not silence to be performed. An epub's markup leaves lines of
        // spaces and runs of indentation around headings, and the speech model reads them as
        // pauses: measured on one Frankenstein passage, 170 words came back as 234.9s of audio
        // (43 wpm) exactly as the file gives them, and 62.0s (164 wpm) with the same words and the
        // whitespace collapsed. A schedule spread over the first is hopelessly behind the voice
        // within a sentence — the highlight and the reading come apart completely.
        .replace(Regex("(?m)^[ \\t\\u00a0]+$"), "")
        .replace(Regex("[ \\t\\u00a0]{2,}"), " ")
        // A scene break is typeset with NON-BREAKING spaces between the asterisks, which is why
        // this rule used to miss it: twenty "*" tokens were read as words and lit one by one,
        // 20 flickers of nothing in 2.7 s, and they inflated that passage's measured pace from
        // 162 to 177 wpm. The break survives as the blank line it always was.
        .replace(Regex("(?m)^[ \\t\\u00a0]*[*_\\-—–·•~=]+([ \\t\\u00a0]+[*_\\-—–·•~=]+)*[ \\t\\u00a0]*$"), "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    /**
     * Take off Project Gutenberg's wrapper: a licence header before the book and the whole licence
     * after it. "Read it from the beginning" should begin at the book, not at several hundred
     * words about redistribution terms.
     *
     * Bounded on purpose. An epub whose spine cannot be read is concatenated in filename order,
     * which can leave the header sitting in the middle of the text — and an unbounded "drop
     * everything before START" would then throw the book away and keep the front matter. So the
     * header is only removed where a header belongs, and the licence only from the back half.
     */
    private fun unwrapGutenberg(text: String): String {
        var t = text
        Regex("(?is)\\*\\*\\*\\s*START OF (?:THE|THIS) PROJECT GUTENBERG E-?BOOK.*?\\*\\*\\*")
            .find(t)?.takeIf { it.range.last < HEADER_LIMIT }
            ?.let { t = t.substring(it.range.last + 1) }
        Regex("(?is)\\*\\*\\*\\s*END OF (?:THE|THIS) PROJECT GUTENBERG E-?BOOK")
            .findAll(t).lastOrNull()?.takeIf { it.range.first > t.length / 2 }
            ?.let { t = t.substring(0, it.range.first) }
        return t
    }

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

    /**
     * End a passage on a sentence, so a pause never lands mid-clause — and in preference to that,
     * end it just before a heading.
     *
     * A heading is a real pause: the voice takes one, and rightly. But a pause INSIDE a passage is
     * spread across that passage's words by the schedule, so the highlight drifts around it. Both
     * renderings this reader has had to throw away spanned a letter ending, a heading, and the
     * start of the next letter — the same shape. Let a heading begin a passage and its pause falls
     * at a join, where a pause belongs.
     */
    private fun cut(text: String, start: Int): Int {
        val room = PASSAGE
        var end = (start + room).coerceAtMost(text.length)
        if (end >= text.length) return text.length
        val window = text.substring(start, end)
        headingStart(window, room / 3)?.let { return start + it }
        val para = window.lastIndexOf("\n\n")
        val stop = if (para > room / 3) para
                   else window.lastIndexOfAny(charArrayOf('.', '!', '?', '”', '"'))
        if (stop > room / 3) end = start + stop + 1
        return end
    }

    /**
     * Where the last heading in the window begins — "Letter 4", "CHAPTER II" — past [least].
     *
     * A heading is often several short lines: a title, an addressee, a date. Taking the last short
     * line cut BETWEEN them, so one passage ended "Letter 4 To Mrs. Saville, England." and the
     * next opened "August 5th, 17—." A run of short lines is one heading, and the passage ends
     * before the whole of it.
     */
    private fun headingStart(window: String, least: Int): Int? {
        val starts = Regex("\\n\\n(?=[^\\n]{1,60}\\n)").findAll(window)
            .map { it.range.first + 2 }.filter { it < window.length }.toList()
        if (starts.isEmpty()) return null
        var i = starts.lastIndex
        var run = 0
        while (i > 0 && starts[i] - starts[i - 1] <= HEADING_RUN && run < HEADING_LINES) { i--; run++ }
        val at = starts[i]
        // Short is not the same as a heading. A page of dialogue is nothing but short lines, and
        // cutting the passage at every one of them would chop a conversation into fragments. A
        // heading does not end like a sentence, or it says what it is.
        if (!looksLikeHeading(window.substring(at).substringBefore('\n'))) return null
        return at.takeIf { it > least }
    }

    private fun looksLikeHeading(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        if (Regex("^(letter|chapter|part|book|volume|canto|act|scene)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        if (Regex("^[IVXLC]+\\.?$").matches(t) || Regex("^\\d+\\.?$").matches(t)) return true
        return t.last() !in ".!?\u201d\"" && t.last() != ','
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
        var tries = 0       // requests that failed and are worth making again
        var waits = 0       // pauses for a rate limit, which is not a failure
        while (tries < 3 && waits < 3 && running.get()) {
            // The slowest rendering that could still be this passage read once. Anything past it
            // is abandoned while it downloads instead of being waited out and then rejected.
            val cap = passage.split(Regex("\\s+")).count { it.isNotBlank() } / MIN_WPM * 60.0 * 1.2
            val result = GeminiRest.speak(context, passage, voice, cap)
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
            val words = passage.split(Regex("\\s+")).count { it.isNotBlank() }
            val wpm = if (secs > 0) words / (secs / 60.0) else 0.0
            if (wpm >= MIN_WPM && wpm <= MAX_WPM) return pcm
            // Asking for the same passage again cost 65.8 seconds of silence once: the bad
            // rendering took 156s to make (654s of audio for 160 words) and the replacement
            // another 32s. A passage the model paces this badly tends to do it again, so give up
            // on the whole passage at once and let the caller read it in halves instead — they
            // render in parallel, and shorter chunks are what the model gets right.
            Log.w(TAG, "speech pace not believable (${"%.0f".format(wpm)} wpm: $words words in ${"%.1f".format(secs)}s)")
            return null
        }
        // Deliberately nothing rather than the bad audio. A schedule stretched over a rendering
        // that is 1.5s long for 803 characters would tear through a passage at a rate no one can
        // follow — the run-1 desync in its worst form. A wait the reader can see beats a
        // highlight the reader cannot trust.
        return null
    }

    /**
     * A rendering of one passage: the audio, and the pieces it was rendered in. Usually one piece;
     * a passage the model keeps getting wrong is read in halves instead, and each half's words are
     * scheduled against its own audio.
     */
    class Speech(val pcm: ByteArray, val parts: List<Pair<String, Int>>)

    /**
     * Speech for a passage, halving it if the model will not render it plausibly.
     *
     * Three rejected renderings used to end the reading, which for a passage that reliably comes
     * back wrong means the book stops at the same place every time. Shorter chunks render
     * reliably — the failures seen here were long passages that the model paced strangely — so a
     * failure becomes a slightly different rhythm rather than a dead end.
     */
    private suspend fun voiceOrSplit(context: Context, passage: String, depth: Int = 0): Speech? {
        if (passage.isBlank()) return Speech(ByteArray(0), emptyList())
        withContext(Dispatchers.IO) { voice(context, passage) }
            ?.let { return Speech(it, listOf(passage to it.size)) }
        if (depth >= SPLIT_DEPTH || passage.length < SPLIT_MIN) return null
        val at = splitPoint(passage)
        if (at <= 0 || at >= passage.length) return null
        Log.i(TAG, "splitting a passage the voice would not render (${passage.length} chars)")
        return coroutineScope {
            val a = async { voiceOrSplit(context, passage.substring(0, at).trim(), depth + 1) }
            val b = async { voiceOrSplit(context, passage.substring(at).trim(), depth + 1) }
            val ra = a.await(); val rb = b.await()
            if (ra == null || rb == null) null else Speech(ra.pcm + rb.pcm, ra.parts + rb.parts)
        }
    }

    /** Halfway, but on a sentence — a split mid-clause would be heard as a stumble. */
    private fun splitPoint(text: String): Int {
        val mid = text.length / 2
        val ends = Regex("[.!?\u201d]\\s+|\\n\\n").findAll(text).map { it.range.last + 1 }.toList()
        return ends.minByOrNull { kotlin.math.abs(it - mid) } ?: mid
    }

    /**
     * Skip the passage the reader is waiting on. Only ever on request — a reading that skips by
     * itself puts a discontinuity in front of someone who will read it as their own attention
     * having wandered, and they lose text without knowing they lost it. The page marks the gap so
     * it stays legible.
     */
    fun skip() {
        val w = waitingOn ?: return
        Log.i(TAG, "skipping the passage the reader is waiting on")
        skipped.set(true)
        w.cancel()
    }

    @Volatile private var waitingOn: Deferred<Speech?>? = null
    private val skipped = AtomicBoolean(false)

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
