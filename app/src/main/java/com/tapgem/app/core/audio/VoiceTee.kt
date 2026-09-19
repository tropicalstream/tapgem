package com.tapgem.app.core.audio

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Debug builds only: while `files/voice_tee.on` exists, every PCM slice handed to the
 * AudioTrack is appended to `files/voice_tee/<epoch>.pcm` (16-bit mono, rate in the log)
 * with a `.log` of wall-clock write offsets and barge-in flush points, so a screen
 * recording can be married to the assistant's real voice afterwards.
 */
internal class VoiceTee(private val context: Context) {
    private var out: FileOutputStream? = null
    private var log: FileOutputStream? = null
    private var bytes = 0L
    private var rate = 0
    private val flag get() = File(context.filesDir, "voice_tee.on")

    @Synchronized fun newTrack(sampleRate: Int) {
        close()
        if (!flag.exists()) return
        runCatching {
            val dir = File(context.filesDir, "voice_tee").apply { mkdirs() }
            val base = System.currentTimeMillis().toString()
            out = FileOutputStream(File(dir, "$base.pcm")); log = FileOutputStream(File(dir, "$base.log"))
            bytes = 0; rate = sampleRate
            line("start rate=$sampleRate")
        }.onFailure { Log.w("VoiceTee", "open failed: ${it.message}") }
    }

    @Synchronized fun wrote(data: ByteArray, offset: Int, n: Int, sampleRate: Int) {
        val o = out ?: return
        if (sampleRate != rate) return
        runCatching { o.write(data, offset, n); bytes += n; line("w $bytes") }
    }

    /** [headFrames] = frames the track actually played since its last flush; the rest of the buffer was cut off. */
    @Synchronized fun flushed(headFrames: Int) { if (out != null) line("flush head=$headFrames bytes=$bytes") }

    private fun line(s: String) { log?.write("${System.currentTimeMillis()} ${SystemClock.uptimeMillis()} $s\n".toByteArray()) }

    @Synchronized fun close() {
        runCatching { out?.close() }; runCatching { log?.close() }; out = null; log = null
    }
}
