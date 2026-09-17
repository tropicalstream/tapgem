package com.tapgem.app.core.media

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.tapgem.app.core.model.WidgetType
import com.tapgem.app.core.store.DesktopStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Shared HTTP client for media/image downloads. */
object Net {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    }
}

/**
 * Finds media on the glasses by loose name ("vacation", "tolkien") and type.
 * Sources, in order: the adb media-drop folder, MediaStore (images/video/
 * audio/files), and the public Download/Documents/Movies/Music/Pictures/DCIM
 * folders when readable (All-files access). Also turns URLs into local files.
 */
object MediaScanner {

    private const val TAG = "MediaScanner"

    data class Hit(val path: String, val name: String, val type: WidgetType, val sizeBytes: Long, val modified: Long)

    private val STOP = setOf("my", "the", "a", "an", "of", "in", "file", "video", "image", "picture", "photo",
        "song", "book", "ebook", "pdf", "document", "movie", "music", "audio", "track", "model", "3d", "please", "open", "show",
        "play", "clip", "recording", "text", "note", "notes", "that", "this", "me", "some", "any")

    /** Query words worth matching; if every word is a stop word, keep them all ("the notes"). */
    fun terms(query: String?): List<String> {
        val all = query?.lowercase(Locale.US)?.split(Regex("[^a-z0-9]+"))?.filter { it.length > 1 }.orEmpty()
        return all.filter { it !in STOP }.ifEmpty { all }
    }

    private const val CACHE_MS = 30_000L
    private var cachedAt = 0L
    private var cached: List<File> = emptyList()

    /** One scan feeds every lookup for 30 s — a voice turn often searches several times. */
    @Synchronized
    private fun candidates(context: Context): List<File> {
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_MS) return cached
        val out = LinkedHashMap<String, File>()
        fun add(f: File) { if (f.isFile && f.length() > 0) out.putIfAbsent(f.absolutePath, f) }
        DesktopStore.mediaDropDir?.let { scanDir(it, 3, ::add) }
        context.getExternalFilesDir(null)?.let { scanDir(it, 2, ::add) }
        queryMediaStore(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ::add)
        queryMediaStore(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ::add)
        queryMediaStore(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ::add)
        queryMediaStore(context, MediaStore.Files.getContentUri("external"), ::add)
        listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_PODCASTS).forEach { d ->
            runCatching { scanDir(Environment.getExternalStoragePublicDirectory(d), 2, ::add) }
        }
        cached = out.values.toList(); cachedAt = now
        return cached
    }

    fun invalidate() { synchronized(this) { cachedAt = 0L } }

    fun find(context: Context, query: String?, type: WidgetType?, limit: Int = 8): List<Hit> {
        val t = terms(query)
        val candidates = candidates(context)

        val hits = candidates.mapNotNull { f ->
            val ft = WidgetType.forExtension(f.extension)?.takeIf { it != WidgetType.APP } ?: return@mapNotNull null
            if (type != null && type != ft) return@mapNotNull null
            val name = f.nameWithoutExtension.lowercase(Locale.US)
            val score = if (t.isEmpty()) 1 else t.map { term ->
                when {
                    name == term -> 6
                    name.split(Regex("[^a-z0-9]+")).contains(term) -> 4
                    name.contains(term) -> 2
                    else -> 0
                }
            }.sum()
            if (t.isNotEmpty() && score == 0) return@mapNotNull null
            Triple(score, f, ft)
        }.sortedWith(compareByDescending<Triple<Int, File, WidgetType>> { it.first }.thenByDescending { it.second.lastModified() })
            .take(limit)
            .map { (_, f, ft) -> Hit(f.absolutePath, f.name, ft, f.length(), f.lastModified()) }
        Log.d(TAG, "find q='$query' type=$type → ${hits.size} of ${candidates.size}")
        return hits
    }

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    private fun scanDir(dir: File?, depth: Int, add: (File) -> Unit) {
        if (dir == null || depth < 0 || !dir.isDirectory) return
        val list = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in list) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) scanDir(f, depth - 1, add) else add(f)
        }
    }

    private fun queryMediaStore(context: Context, uri: Uri, add: (File) -> Unit) {
        runCatching {
            @Suppress("DEPRECATION")
            val proj = arrayOf(MediaStore.MediaColumns.DATA)
            context.contentResolver.query(uri, proj, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                var n = 0
                while (c.moveToNext() && n < 3000) {
                    val p = c.getString(0) ?: continue
                    add(File(p)); n++
                }
            }
        }.onFailure { Log.d(TAG, "MediaStore $uri: ${it.message}") }
    }

    /**
     * Make [source] a local file: http(s) → download to cache (by URL hash);
     * content:// → copy; plain path → as-is if it exists (also tries the
     * media-drop folder by bare name).
     */
    fun ensureLocal(context: Context, source: String, extHint: String? = null): File? {
        val s = source.trim()
        if (s.startsWith("http://") || s.startsWith("https://")) {
            val ext = extHint ?: s.substringBefore('?').substringAfterLast('.', "").take(5).ifBlank { "bin" }
            val out = File(DesktopStore.downloadsDir, sha1(s) + "." + ext)
            if (out.exists() && out.length() > 0) return out
            return runCatching {
                Net.http.newCall(Request.Builder().url(s).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    val tmp = File(out.parentFile, out.name + ".part")
                    r.body?.byteStream()?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    tmp.renameTo(out); out
                }
            }.onFailure { Log.w(TAG, "download failed $s: ${it.message}") }.getOrNull()
        }
        if (s.startsWith("content://")) {
            return runCatching {
                val out = File(DesktopStore.downloadsDir, sha1(s) + "." + (extHint ?: "bin"))
                context.contentResolver.openInputStream(Uri.parse(s))?.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                out
            }.getOrNull()
        }
        File(s).takeIf { it.isFile }?.let { return it }
        DesktopStore.mediaDropDir?.let { d -> File(d, s).takeIf { it.isFile }?.let { return it } }
        return null
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}
