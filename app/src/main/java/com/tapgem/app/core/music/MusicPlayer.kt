package com.tapgem.app.core.music

import android.content.ContentUris
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Local audio playback, decoded natively.
 *
 * The UI is a web page (a Winamp skin blitted to a canvas) but the audio never touches the WebView:
 * MediaPlayer holds the stream, so playback survives the page being throttled, the window being
 * closed, or eco mode pausing animations — and the glasses' own assistant can duck it through audio
 * focus instead of fighting it. The visualiser still needs a signal, so [fft] lifts the spectrum
 * straight off the output with the platform Visualizer effect and hands it to the page on request.
 */
object MusicPlayer {
    private const val TAG = "MusicPlayer"

    data class Track(
        val id: Long, val title: String, val artist: String, val album: String,
        val durationMs: Long, val trackNo: Int, val uri: Uri, val path: String
    ) {
        val label get() = "$artist — $title"
        fun json(): JSONObject = JSONObject()
            .put("id", id).put("title", title).put("artist", artist).put("album", album)
            .put("duration", durationMs / 1000).put("track", trackNo).put("path", path)
    }

    fun interface Listener { fun onEvent(json: JSONObject) }
    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    private fun emit(o: JSONObject) { for (l in listeners) runCatching { l.onEvent(o) } }

    private lateinit var appContext: Context
    private var player: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var focusRequest: AudioFocusRequest? = null
    private val audio get() = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val queue = ArrayList<Track>()
    private var index = -1
    @Volatile var shuffle = false; private set
    @Volatile var repeat = "off"; private set      // off | all | one
    @Volatile var playing = false; private set
    private var library: List<Track> = emptyList()
    private var libraryAt = 0L
    private var ducked = false

    fun init(context: Context) { appContext = context.applicationContext }

    // ---- library -------------------------------------------------------------------------------

    /**
     * MediaStore already indexes tags, so artist/album/track resolve without walking the disc.
     * Files dropped over adb are not indexed until something scans them, so anything found loose in
     * the usual folders is folded in too — otherwise a fresh push is invisible until a reboot.
     */
    @Synchronized fun library(refresh: Boolean = false): List<Track> {
        if (!refresh && library.isNotEmpty() && System.currentTimeMillis() - libraryAt < 30_000) return library
        val found = LinkedHashMap<String, Track>()
        // /sdcard is a symlink to /storage/emulated/0, so the same file arrives under two spellings
        // — MediaStore's and the directory walk's. Key on one form or every track appears twice.
        fun key(path: String) = path.replaceFirst(Regex("^/sdcard/"), "/storage/emulated/0/")
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATA
        )
        runCatching {
            appContext.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                "${MediaStore.Audio.Media.IS_MUSIC}!=0 OR ${MediaStore.Audio.Media.IS_PODCAST}!=0", null,
                "${MediaStore.Audio.Media.ARTIST} ASC, ${MediaStore.Audio.Media.ALBUM} ASC, ${MediaStore.Audio.Media.TRACK} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0); val path = c.getString(6) ?: continue
                    found[key(path)] = Track(
                        id, c.getString(1) ?: File(path).nameWithoutExtension, c.getString(2)?.takeIf { it != "<unknown>" } ?: "Unknown artist",
                        c.getString(3) ?: "", c.getLong(4), c.getInt(5) % 1000,
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), path
                    )
                }
            }
        }.onFailure { Log.w(TAG, "MediaStore query failed: ${it.message}") }
        val exts = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "mid")
        for (dir in listOf("Music", "Download", "Podcasts", "Movies")) {
            val d = File("/sdcard/$dir")
            runCatching {
                d.walkTopDown().maxDepth(4).filter { it.isFile && it.extension.lowercase() in exts }.forEach { f ->
                    if (!found.containsKey(key(f.absolutePath))) found[key(f.absolutePath)] = tagged(f, d.name)
                }
            }
        }
        library = found.values.toList(); libraryAt = System.currentTimeMillis()
        return library
    }

    /**
     * A file the indexer has not seen yet still has its tags inside it; read them directly so
     * "play the album" works on something copied over a minute ago, not just after a media scan.
     */
    private fun tagged(f: File, fallbackAlbum: String): Track {
        val id = -abs(f.absolutePath.hashCode().toLong()).let { if (it == 0L) 1L else it }
        var title = f.nameWithoutExtension; var artist = "Unknown artist"; var album = fallbackAlbum
        var dur = 0L; var trackNo = 0
        runCatching {
            android.media.MediaMetadataRetriever().use { mr ->
                mr.setDataSource(f.absolutePath)
                fun m(k: Int) = mr.extractMetadata(k)?.trim()?.takeIf { it.isNotBlank() }
                m(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { title = it }
                (m(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: m(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))?.let { artist = it }
                m(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { album = it }
                m(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { dur = it }
                m(android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.trim()?.toIntOrNull()?.let { trackNo = it }
            }
        }
        return Track(id, title, artist, album, dur, trackNo, Uri.fromFile(f), f.absolutePath)
    }

    /** Loose keyword match across title/artist/album/filename — the model narrows, this just filters. */
    fun search(query: String?, limit: Int = 40): List<Track> {
        val q = query?.trim()?.lowercase().orEmpty()
        fun hits(all: List<Track>): List<Track> {
            if (q.isBlank()) return all.take(limit)
            val terms = q.split(Regex("\\s+")).filter { it.isNotBlank() }
            return all.filter { t ->
                val hay = "${t.title} ${t.artist} ${t.album} ${File(t.path).name}".lowercase()
                terms.all { hay.contains(it) }
            }.take(limit)
        }
        // A miss is usually a file copied onto the glasses seconds ago, so spend one rescan before
        // reporting nothing rather than making the user ask twice.
        return hits(library()).ifEmpty { hits(library(refresh = true)) }
    }

    fun byId(id: Long): Track? = library().firstOrNull { it.id == id }

    // ---- playback ------------------------------------------------------------------------------

    @Synchronized fun play(tracks: List<Track>, startAt: Int = 0): String {
        if (tracks.isEmpty()) return "No matching tracks."
        queue.clear(); queue.addAll(tracks)
        index = startAt.coerceIn(0, queue.size - 1)
        if (shuffle && queue.size > 1) {
            val first = queue.removeAt(index); queue.shuffle(); queue.add(0, first); index = 0
        }
        start()
        return "Playing ${current()?.label ?: ""}${if (queue.size > 1) " (${queue.size} tracks queued)" else ""}."
    }

    private fun start() {
        val t = current() ?: return
        release(keepQueue = true)
        if (!requestFocus()) { emit(ev("error", "text", "Another app holds the audio.")); return }
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(appContext, t.uri)
                setOnCompletionListener { onCompleted() }
                setOnErrorListener { _, w, e -> Log.w(TAG, "player error $w/$e"); emit(ev("error", "text", "Could not play ${t.title}.")); next(); true }
                prepare(); start()
            }
            attachVisualizer()
            playing = true
            emit(state())
        }.onFailure {
            Log.w(TAG, "play failed: ${it.message}")
            emit(ev("error", "text", "Could not open ${t.title}."))
        }
    }

    private fun onCompleted() {
        when {
            repeat == "one" -> start()
            index < queue.size - 1 -> { index++; start() }
            repeat == "all" && queue.isNotEmpty() -> { index = 0; start() }
            else -> { playing = false; release(keepQueue = true); emit(state()) }
        }
    }

    fun current(): Track? = queue.getOrNull(index)
    fun positionMs(): Int = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    fun durationMs(): Int = runCatching { player?.duration ?: 0 }.getOrDefault(0)

    @Synchronized fun pause(): String {
        runCatching { player?.pause() }; playing = false; emit(state()); return "Paused."
    }
    @Synchronized fun resume(): String {
        if (player == null) { if (queue.isNotEmpty()) start() else return "Nothing queued." }
        else { if (!requestFocus()) return "Another app holds the audio."; runCatching { player?.start() }; playing = true; emit(state()) }
        return "Playing ${current()?.label ?: ""}."
    }
    @Synchronized fun toggle(): String = if (playing) pause() else resume()
    @Synchronized fun next(): String {
        if (queue.isEmpty()) return "Nothing queued."
        index = if (index < queue.size - 1) index + 1 else 0
        start(); return "Playing ${current()?.label ?: ""}."
    }
    @Synchronized fun previous(): String {
        if (queue.isEmpty()) return "Nothing queued."
        // Within the first few seconds "back" means the previous track; later it means this one again.
        if (positionMs() > 4000) { start(); return "Restarted ${current()?.title ?: ""}." }
        index = if (index > 0) index - 1 else queue.size - 1
        start(); return "Playing ${current()?.label ?: ""}."
    }
    fun seekTo(ms: Int) { runCatching { player?.seekTo(ms.coerceAtLeast(0)) }; emit(state()) }
    fun seekBy(deltaMs: Int) = seekTo(positionMs() + deltaMs)

    fun setShuffle(on: Boolean): String {
        shuffle = on
        if (on && queue.size > 1) { val c = current(); queue.shuffle(); c?.let { queue.remove(it); queue.add(0, it) }; index = 0 }
        emit(state()); return if (on) "Shuffle on." else "Shuffle off."
    }
    fun setRepeat(mode: String): String {
        repeat = when (mode.lowercase()) { "one", "track", "single" -> "one"; "all", "on", "queue" -> "all"; else -> "off" }
        emit(state()); return "Repeat $repeat."
    }
    fun enqueue(tracks: List<Track>): String {
        if (tracks.isEmpty()) return "No matching tracks."
        queue.addAll(tracks); emit(state())
        return "Queued ${if (tracks.size == 1) tracks[0].label else "${tracks.size} tracks"}."
    }
    fun queueSnapshot(): List<Track> = synchronized(this) { queue.toList() }

    fun volume(pct: Int?): String {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (pct == null) return "Volume ${(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()}%."
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (pct.coerceIn(0, 100) * max / 100f).roundToInt(), 0)
        emit(state()); return "Volume ${pct.coerceIn(0, 100)}%."
    }

    @Synchronized fun stop(): String {
        release(keepQueue = false); playing = false; index = -1; emit(state()); return "Stopped."
    }

    private fun release(keepQueue: Boolean) {
        runCatching { visualizer?.enabled = false }; runCatching { visualizer?.release() }; visualizer = null
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        if (!keepQueue) { queue.clear(); abandonFocus() }
    }

    // ---- audio focus ---------------------------------------------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { pause(); abandonFocus() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playing) { runCatching { player?.pause() }; playing = false; emit(state()) }
            // The assistant and the interpreter speak over the music rather than stopping it.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { ducked = true; runCatching { player?.setVolume(0.18f, 0.18f) } }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (ducked) { ducked = false; runCatching { player?.setVolume(1f, 1f) } }
                else if (!playing && queue.isNotEmpty() && player != null) { runCatching { player?.start() }; playing = true; emit(state()) }
            }
        }
    }

    private fun requestFocus(): Boolean {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val r = if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs).setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusListener).build()
            focusRequest = req; audio.requestAudioFocus(req)
        } else @Suppress("DEPRECATION") audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        return r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") audio.abandonAudioFocus(focusListener)
        focusRequest = null
    }

    // ---- visualiser ----------------------------------------------------------------------------

    /**
     * MilkDrop and the classic analyser both want a spectrum, and the page has no access to the
     * audio because it is decoded out here. The platform effect taps the session directly; the page
     * pulls [fft] when it paints, so a hidden or throttled window costs nothing.
     */
    private fun attachVisualizer() {
        runCatching {
            val session = player?.audioSessionId ?: return
            visualizer = Visualizer(session).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                enabled = true
            }
        }.onFailure { Log.i(TAG, "visualizer unavailable: ${it.message}") }
    }

    /** [bands] magnitudes 0..1, low to high. Empty when nothing is playing. */
    fun fft(bands: Int = 32): FloatArray {
        val v = visualizer ?: return FloatArray(0)
        val raw = ByteArray(v.captureSize)
        if (runCatching { v.getFft(raw) }.getOrDefault(-1) != Visualizer.SUCCESS) return FloatArray(0)
        val bins = raw.size / 2                       // [0]=DC, [1]=Nyquist, then (re,im) per bin
        if (bins < 4) return FloatArray(0)
        val out = FloatArray(bands)
        // Log-spaced edges, forced strictly increasing: computing them with pow() alone collapses the
        // first dozen buckets onto the same bin and leaves the display bunched at the left.
        var prev = 1
        for (b in 0 until bands) {
            var hi = Math.pow(bins.toDouble(), (b + 1.0) / bands).toInt()
            if (hi <= prev) hi = prev + 1
            if (hi > bins) hi = bins
            var peak = 0f
            for (i in prev until hi) {
                val re = raw[i * 2].toFloat(); val im = raw[i * 2 + 1].toFloat()
                val mag = hypot(re, im)
                if (mag > peak) peak = mag
            }
            // Loudness is logarithmic; a linear scale reads as almost-nothing then clipped.
            out[b] = if (peak <= 0f) 0f
                     else ((20.0 * Math.log10((peak / 128.0).coerceAtLeast(1e-4)) + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
            prev = hi
            if (prev >= bins) { for (r in b + 1 until bands) out[r] = 0f; break }
        }
        return out
    }

    // ---- state ---------------------------------------------------------------------------------

    private fun ev(type: String, k: String, v: String) = JSONObject().put("type", type).put(k, v)

    fun state(): JSONObject {
        val t = current()
        val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(15)
        return JSONObject()
            .put("type", "state").put("playing", playing).put("shuffle", shuffle).put("repeat", repeat)
            .put("position", positionMs() / 1000).put("duration", (t?.durationMs?.takeIf { it > 0 }?.toInt() ?: durationMs()) / 1000)
            .put("index", index).put("queueSize", queue.size)
            .put("volume", runCatching { (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt() }.getOrDefault(50))
            .put("track", t?.json() ?: JSONObject())
            .put("queue", JSONArray().also { a -> queue.take(60).forEach { a.put(it.json()) } })
    }
}
