package com.tapgem.app.core.read

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.tapgem.app.core.bridge.DesktopBridge
import com.tapgem.app.core.bridge.WebCommandBus
import com.tapgem.app.core.network.GeminiRest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads something aloud, passage after passage, turning the page as it goes.
 *
 * Why this exists rather than letting the assistant read: the Live model ends a spoken turn after
 * roughly a paragraph. Measured twice on the glasses — handed several pages it spoke 336
 * characters and stopped; handed a short passage and told explicitly to continue, it spoke 271 and
 * never called back. No prompt fixes that, because the limit is the model's.
 *
 * So the app does the reading. A separate speech model voices each passage, and the loop here —
 * not the conversation — decides what comes next. That also puts page-turning somewhere it can
 * actually work: the page moves when a passage starts, so the words on screen are the words being
 * spoken.
 *
 * Generation runs ahead of playback (about 26s of work for 65s of speech), so the next passage is
 * prepared while the current one plays and the gap between them is silence, not waiting.
 */
object BookReader {
    private const val TAG = "BookReader"

    /** Big enough to be worth a request, small enough that a stop feels immediate. */
    private const val PASSAGE = 1_000

    private val scope = CoroutineScope(Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    @Volatile private var track: AudioTrack? = null

    val isReading: Boolean get() = running.get()

    /** Where in [text] the reader is, so stopping and starting again carries on. */
    @Volatile var position: Int = 0
        private set

    /**
     * Start reading [text] aloud for the window [widgetId], from [from].
     * Returns at once; [onDone] runs when the reading ends or is stopped.
     */
    /**
     * Where each chapter begins in the text handed over, so the page can be turned to the chapter
     * being read. Without it the view stays on whichever chapter it started on and the scroll
     * fraction means nothing: three percent of a book is three percent of the title page.
     */
    @Volatile private var chapterStarts: List<Int> = emptyList()
    @Volatile private var shownChapter: Int = -1

    fun start(context: Context, widgetId: String?, text: String, from: Int = 0,
              chapters: List<Int> = emptyList(),
              onProgress: ((Int, Int) -> Unit)? = null, onDone: ((String) -> Unit)? = null) {
        stop()
        if (text.isBlank()) { onDone?.invoke("Nothing to read."); return }
        running.set(true)
        chapterStarts = chapters
        shownChapter = -1
        position = from.coerceIn(0, text.length)
        job = scope.launch {
            var spoken = 0
            try {
                var next: Deferred<ByteArray?>? = null
                while (isActive && running.get() && position < text.length) {
                    val start = position
                    val end = cut(text, start)
                    val passage = text.substring(start, end).trim()
                    position = end

                    // The audio for this passage was usually started last time round.
                    val audio = (next ?: scope.async { voice(context, passage) }).await()
                    // Prepare the following one while this plays.
                    val after = if (position < text.length) {
                        val s2 = position; val e2 = cut(text, s2)
                        scope.async { voice(context, text.substring(s2, e2).trim()) }
                    } else null

                    if (audio == null || audio.isEmpty()) {
                        Log.w(TAG, "no audio for passage at $start; stopping")
                        onDone?.invoke("The reading voice stopped working."); break
                    }
                    // Move the page under the words before they are spoken: the right chapter
                    // first, then the right place within it.
                    widgetId?.let { id -> turnTo(id, start, end, text.length) }
                    onProgress?.invoke(end, text.length)
                    play(audio)
                    spoken++
                    next = after
                }
                if (running.get()) onDone?.invoke(
                    if (position >= text.length) "Finished reading." else "Stopped after $spoken passage(s).")
            } catch (t: Throwable) {
                Log.w(TAG, "reader failed: ${t.message}")
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
    }

    /**
     * Put the page where the words are. With chapter offsets, that means opening the chapter this
     * passage falls in and scrolling to its place inside that chapter; without them, a fraction of
     * the whole is the best that can be done.
     */
    private suspend fun turnTo(widgetId: String, start: Int, end: Int, total: Int) {
        val starts = chapterStarts
        val fraction: Double
        if (starts.isEmpty()) {
            fraction = end.toDouble() / total
        } else {
            var ch = starts.indexOfLast { it <= start }
            if (ch < 0) ch = 0
            val chStart = starts[ch]
            val chEnd = starts.getOrNull(ch + 1) ?: total
            fraction = if (chEnd > chStart) (end - chStart).toDouble() / (chEnd - chStart) else 0.0
            if (ch != shownChapter) {
                shownChapter = ch
                DesktopBridge.mutateWidget(widgetId) { it.withState("chapter" to ch.toString()) }
                // The chapter has to render before it can be scrolled inside.
                kotlinx.coroutines.delay(500)
            }
        }
        runCatching {
            WebCommandBus.execute(widgetId, WebCommandBus.Command("scroll",
                mapOf("fraction" to fraction.coerceIn(0.0, 1.0).toString())), 4_000L)
        }
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

    private fun voice(context: Context, passage: String): ByteArray? {
        if (passage.isBlank()) return ByteArray(0)
        // Asking it to read rather than react: without this the model answers the passage.
        return GeminiRest.speak(context, "Read this aloud, exactly as written:\n\n$passage")
            .onFailure { Log.w(TAG, "speech failed: ${it.message}") }
            .getOrNull()
    }

    private suspend fun play(pcm: ByteArray) = withContext(Dispatchers.IO) {
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
        runCatching {
            t.play()
            var off = 0
            while (off < pcm.size && running.get()) {
                val n = t.write(pcm, off, minOf(8192, pcm.size - off))
                if (n <= 0) break
                off += n
            }
            // Let the tail drain rather than cutting the last syllable.
            if (running.get()) {
                t.stop()
                while (running.get() && t.playState == AudioTrack.PLAYSTATE_PLAYING) Thread.sleep(40)
            }
        }
        releaseTrack()
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        runCatching { if (t.state == AudioTrack.STATE_INITIALIZED) t.pause(); t.flush(); t.stop() }
        runCatching { t.release() }
    }
}
