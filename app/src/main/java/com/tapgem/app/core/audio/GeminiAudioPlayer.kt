package com.tapgem.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

/**
 * Streams Gemini Live PCM chunks straight to an AudioTrack. Proven X3Gemini
 * port: non-blocking sliced writes so stopAndFlush() (barge-in) cuts within
 * ~200 ms, DEAD_OBJECT recovery, and speech that never ducks or pauses for page audio.
 * A chunk that cannot make progress for [STALL_ABORT_MS] (paused track,
 * focus lost) is dropped rather than blocking the caller forever.
 */
class GeminiAudioPlayer(context: Context) {

    companion object {
        private const val TAG = "GeminiAudioPlayer"
        private const val DEFAULT_SAMPLE_RATE = 24_000
        private const val SLICE_BYTES = 16 * 1024
        private const val STALL_ABORT_MS = 1_500L
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()
    private var audioTrack: AudioTrack? = null
    private var trackSampleRate = 0
    private var hasAudioFocus = false
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var lastOutputLevel = 0f
    @Volatile private var lastOutputAtMs = 0L
    @Volatile private var writeGeneration = 0L
    private val tee = if (com.tapgem.app.BuildConfig.DEBUG) VoiceTee(appContext) else null

    /**
     * The assistant's voice never yields to a page: a radio stream (re)starting, a
     * game's sound effect or a video grabbing focus used to duck or pause the
     * reply mid-sentence ("the voice mutes out"). Focus is only tracked so the
     * next chunk takes it back — which is what makes the page duck for us.
     */
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(lock) {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> hasAudioFocus = true
                AudioManager.AUDIOFOCUS_LOSS -> hasAudioFocus = false
                else -> { /* transient loss / duck request: keep speaking at full volume */ }
            }
            runCatching { audioTrack?.setVolume(1f) }
            runCatching { if (audioTrack?.playState == AudioTrack.PLAYSTATE_PAUSED) audioTrack?.play() }
        }
    }

    /** The current cut generation; a chunk queued before a barge-in must be dropped, not played late. */
    val generation: Long get() = writeGeneration

    fun playChunk(mimeType: String, data: ByteArray, queuedAt: Long = writeGeneration) {
        if (data.isEmpty() || queuedAt != writeGeneration) return
        val sampleRate = parseSampleRate(mimeType) ?: DEFAULT_SAMPLE_RATE
        var track: AudioTrack
        val myGeneration: Long
        synchronized(lock) {
            requestAudioFocusLocked()
            track = ensureTrackLocked(sampleRate) ?: return
            runCatching { track.setVolume(1f) }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { track.play() }
            myGeneration = writeGeneration
            // Level is published BEFORE the write so the mic echo gate sees the
            // very first chunk of a reply, not just the tail of it.
            lastOutputLevel = calculatePcm16Level(data)
            lastOutputAtMs = SystemClock.uptimeMillis()
        }
        var offset = 0
        var deadRecoveries = 0
        var stalledSinceMs = 0L
        while (offset < data.size) {
            if (writeGeneration != myGeneration) break
            val toWrite = minOf(data.size - offset, SLICE_BYTES)
            val wrote = runCatching { track.write(data, offset, toWrite, AudioTrack.WRITE_NON_BLOCKING) }.getOrElse { -1 }
            if (wrote == AudioTrack.ERROR_DEAD_OBJECT) {
                if (++deadRecoveries > 3) break
                val replacement = synchronized(lock) {
                    runCatching { audioTrack?.release() }
                    audioTrack = null; trackSampleRate = 0
                    ensureTrackLocked(sampleRate)?.also { if (it.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { it.play() } }
                } ?: break
                track = replacement
                continue
            }
            if (wrote < 0) break
            if (wrote == 0) {
                val now = SystemClock.uptimeMillis()
                if (stalledSinceMs == 0L) stalledSinceMs = now
                else if (now - stalledSinceMs > STALL_ABORT_MS) { Log.w(TAG, "playback stalled — dropping chunk"); break }
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                continue
            }
            stalledSinceMs = 0L
            tee?.wrote(data, offset, wrote, sampleRate)
            offset += wrote
            lastOutputAtMs = SystemClock.uptimeMillis()
        }
    }

    fun currentOutputLevel(): Float = lastOutputLevel

    fun isActivelySpeaking(windowMs: Long = 350L): Boolean =
        SystemClock.uptimeMillis() - lastOutputAtMs <= windowMs && lastOutputLevel > 0.01f

    fun stopAndFlush() {
        writeGeneration++
        synchronized(lock) {
            val track = audioTrack ?: return
            tee?.flushed(runCatching { track.playbackHeadPosition }.getOrDefault(-1))
            runCatching { track.pause() }
            runCatching { track.flush() }
            lastOutputLevel = 0f
            abandonAudioFocusLocked()
        }
    }

    fun release() {
        writeGeneration++
        synchronized(lock) {
            val track = audioTrack
            audioTrack = null; trackSampleRate = 0; lastOutputLevel = 0f
            runCatching { track?.pause() }; runCatching { track?.flush() }; runCatching { track?.release() }
            abandonAudioFocusLocked()
        }
    }

    private fun ensureTrackLocked(sampleRate: Int): AudioTrack? {
        val existing = audioTrack
        if (existing != null && trackSampleRate == sampleRate && existing.state == AudioTrack.STATE_INITIALIZED) return existing
        runCatching { existing?.pause(); existing?.flush(); existing?.release() }
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) { audioTrack = null; trackSampleRate = 0; return null }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(minBuffer * 2, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack init failed for $sampleRate")
            runCatching { track?.release() }
            audioTrack = null; trackSampleRate = 0
            return null
        }
        audioTrack = track; trackSampleRate = sampleRate
        tee?.newTrack(sampleRate)
        return track
    }

    private fun parseSampleRate(mimeType: String): Int? =
        Regex("""rate=(\d+)""").find(mimeType)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun calculatePcm16Level(data: ByteArray): Float {
        if (data.size < 2) return 0f
        var peak = 0
        var i = 0
        while (i + 1 < data.size) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            peak = max(peak, abs(sample)); i += 2
        }
        return (peak / 32767f).coerceIn(0f, 1f)
    }

    private fun requestAudioFocusLocked() {
        if (hasAudioFocus) return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusChangeListener).build().also { focusRequest = it }
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocusLocked() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusChangeListener)
        hasAudioFocus = false
    }
}
