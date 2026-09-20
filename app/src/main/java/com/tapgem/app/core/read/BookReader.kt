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
    fun start(context: Context, readerId: String?, text: String, from: Int = 0,
              bookTitle: String = "Reading",
              onProgress: ((Int, Int) -> Unit)? = null, onDone: ((String) -> Unit)? = null) {
        stop()
        if (text.isBlank()) { onDone?.invoke("Nothing to read."); return }
        running.set(true)
        title = bookTitle
        position = from.coerceIn(0, text.length)
        val readerId = readerId
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
                    // Show the exact words about to be spoken, with a duration for each, then
                    // start the page's clock at the moment the audio does. Highlighting a separate
                    // rendering could only ever approximate this; here the lit word is the word.
                    val secs = audio.size / 2.0 / GeminiRest.SPEECH_RATE_HZ
                    showPassage(readerId, passage, secs, end, text.length)
                    onProgress?.invoke(end, text.length)
                    play(audio, readerId)
                    spoken++
                    next = after
                }
                val msg = if (position >= text.length) "Finished reading." else "Stopped after $spoken passage(s)."
                readerId?.let { id -> runCatching { WebCommandBus.execute(id,
                    WebCommandBus.Command("eval", mapOf("js" to "window.__read && __read.finished(${jsStr(msg)})")), 4_000L) } }
                if (running.get()) onDone?.invoke(msg)
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
     * Give each word a slice of the passage's audio.
     *
     * The speech model returns audio with no timing in it, so the shape has to be inferred: longer
     * words take longer to say, and a comma or a full stop buys a pause. Proportional weighting is
     * not perfect, but it drifts by a word at most across a passage and that is close enough to
     * keep someone's place — which is the whole point of highlighting.
     */
    private fun wordTimings(passage: String, seconds: Double): List<Int> {
        val tokens = passage.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val weights = tokens.map { t ->
            var w = t.length.toDouble() + 1.0
            val last = t.lastOrNull()
            if (last != null && last in ",;:") w += 2.5
            if (last != null && last in ".!?\u201d") w += 5.0
            w
        }
        val total = weights.sum().takeIf { it > 0 } ?: return emptyList()
        val ms = seconds * 1000.0
        return weights.map { ((it / total) * ms).toInt().coerceAtLeast(40) }
    }

    private suspend fun showPassage(widgetId: String?, passage: String, seconds: Double,
                                    done: Int, total: Int) {
        val id = widgetId ?: return
        val times = wordTimings(passage, seconds)
        runCatching {
            WebCommandBus.execute(id, WebCommandBus.Command("eval", mapOf("js" to
                "window.__read && __read.show(${jsStr(title)}, ${jsStr(passage)}, " +
                "${times.joinToString(",", "[", "]")}, $done, $total)")), 6_000L)
        }
    }

    @Volatile private var title: String = "Reading"

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

    private fun jsStr(s: String): String = org.json.JSONObject.quote(s)

    private fun voice(context: Context, passage: String): ByteArray? {
        if (passage.isBlank()) return ByteArray(0)
        // Asking it to read rather than react: without this the model answers the passage.
        return GeminiRest.speak(context, "Read this aloud, exactly as written:\n\n$passage")
            .onFailure { Log.w(TAG, "speech failed: ${it.message}") }
            .getOrNull()
    }

    private suspend fun play(pcm: ByteArray, widgetId: String? = null) = withContext(Dispatchers.IO) {
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
            widgetId?.let { id ->
                runCatching { WebCommandBus.execute(id,
                    WebCommandBus.Command("eval", mapOf("js" to "window.__read && __read.play()")), 4_000L) }
            }
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
