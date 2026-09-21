package com.tapgem.app.core.media

import android.content.Context
import android.util.Log
import com.tapgem.app.core.store.DesktopStore
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Books fetched on request from Project Gutenberg, so the glasses are not limited to whatever was
 * pushed over adb: ask for a book by name and it arrives as an epub and opens.
 *
 * Searching goes through Gutendex (gutendex.com), Gutenberg's JSON index — no key, no account, and
 * it reports the epub URL directly, which the site's own search page does not. The file itself
 * comes from gutenberg.org. Both are public domain in the US, which is the point of using this
 * archive rather than scraping a bookshop.
 *
 * Downloads land in the media-drop folder beside anything pushed by hand, so a book fetched once
 * is found by name from then on and costs nothing to reopen.
 */
object BookStore {
    private const val TAG = "BookStore"
    private const val API = "https://gutendex.com/books"
    private const val UA = "TapGem/1.0 (RayNeo X3 Pro; personal sideloaded build)"

    class Book(val id: Int, val title: String, val author: String, val epubUrl: String, val downloads: Int) {
        /** "Frankenstein — Mary Shelley", as it should read in a title bar. */
        val label: String get() = if (author.isBlank()) title else "$title — $author"
    }

    /**
     * Gutendex sorts by popularity, which is usually the right answer for a bare title: the most
     * downloaded "Frankenstein" is Shelley's, not a later abridgement. Editions of the same work
     * are near-duplicates here, so the ranking below only has to prefer a real title match.
     */
    fun search(query: String, limit: Int = 8): List<Book> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val body = get("$API?search=$q") ?: return emptyList()
        val results = body.optJSONArray("results") ?: return emptyList()
        return (0 until minOf(results.length(), limit)).mapNotNull { i ->
            val b = results.optJSONObject(i) ?: return@mapNotNull null
            val formats = b.optJSONObject("formats") ?: return@mapNotNull null
            // "application/epub+zip"; the key occasionally carries a charset suffix.
            val epub = formats.keys().asSequence().firstOrNull { it.contains("epub") }
                ?.let { formats.optString(it) }?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val authors = b.optJSONArray("authors")
            val author = (0 until (authors?.length() ?: 0))
                .mapNotNull { j -> authors?.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() } }
                .joinToString(", ")
            Book(b.optInt("id"), b.optString("title", "Untitled"), tidyAuthor(author), epub,
                b.optInt("download_count"))
        }
    }

    /** The archive files authors "Shelley, Mary Wollstonecraft"; nobody says it that way. */
    private fun tidyAuthor(name: String): String {
        if (!name.contains(',') || name.count { it == ',' } > 1) return name
        val (last, first) = name.split(',', limit = 2).map { it.trim() }
        return if (first.isBlank()) last else "$first $last"
    }

    /** Download once, keep it. A book already on the glasses is returned as it stands. */
    fun fetch(context: Context, book: Book): File? {
        val dir = DesktopStore.mediaDropDir ?: context.getExternalFilesDir(null) ?: return null
        dir.mkdirs()
        val f = File(dir, "${fileName(book)}.epub")
        if (f.exists() && f.length() > 0) return f
        return runCatching {
            val req = Request.Builder().url(book.epubUrl).header("User-Agent", UA).build()
            Net.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.w(TAG, "download HTTP ${r.code} for ${book.epubUrl}"); return null }
                f.outputStream().use { out -> r.body?.byteStream()?.copyTo(out) }
            }
            // An epub is a zip: anything else that arrived is an error page, not a book.
            if (f.length() < 1_000 || f.readBytes(2).let { it.size < 2 || it[0] != 'P'.code.toByte() || it[1] != 'K'.code.toByte() }) {
                Log.w(TAG, "not an epub (${f.length()} bytes)"); f.delete(); return null
            }
            f
        }.onFailure { Log.w(TAG, "download failed: ${it.message}"); f.delete() }.getOrNull()
    }

    private fun fileName(book: Book): String =
        book.title.replace(Regex("[^A-Za-z0-9 '-]"), " ").replace(Regex("\\s+"), "_").trim('_').take(60)
            .ifBlank { "gutenberg_${book.id}" }

    private fun get(url: String): JSONObject? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        Net.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "HTTP ${r.code} for $url"); return null }
            JSONObject(r.body?.string().orEmpty())
        }
    }.onFailure { Log.w(TAG, "search failed: ${it.message}") }.getOrNull()

    /** Read the first bytes without pulling the whole file into memory. */
    private fun File.readBytes(n: Int): ByteArray = runCatching {
        inputStream().use { s -> ByteArray(n).also { buf -> s.read(buf) } }
    }.getOrDefault(ByteArray(0))
}
