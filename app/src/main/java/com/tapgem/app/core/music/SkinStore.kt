package com.tapgem.app.core.music

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream

/**
 * Classic skins, searched and fetched from the Internet Archive at runtime.
 *
 * Nothing is bundled: the skins are other people's artwork, so the app carries a reader for the
 * format and not a library of the files. A skin is a zip of bitmaps; the page cannot open a zip
 * over file:// (no file access from file URLs), so the unpacking happens out here and the page is
 * handed a map of sprite sheet → path to point an <img> at.
 */
object SkinStore {
    private const val TAG = "SkinStore"
    private const val SEARCH = "https://archive.org/advancedsearch.php"
    private const val DOWNLOAD = "https://archive.org/download"
    /** A game-themed skin from the archive's Winamp collection; the one the user asked to open with. */
    const val DEFAULT_ID = "was-mdk-2"

    private lateinit var appContext: Context
    private val dir get() = File(appContext.filesDir, "skins").apply { mkdirs() }
    fun init(context: Context) { appContext = context.applicationContext }

    data class Skin(val id: String, val title: String) {
        fun json(): JSONObject = JSONObject().put("id", id).put("title", title.removePrefix("Winamp Skin: "))
            .put("thumb", "$DOWNLOAD/$id/__ia_thumb.jpg")
    }

    private fun get(url: String, timeout: Int = 20_000): ByteArray? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = timeout; readTimeout = timeout; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TapGem/1.0 (RayNeo X3 Pro)")
            if (responseCode !in 200..299) { disconnect(); return null }
            inputStream.use { it.readBytes() }.also { disconnect() }
        }
    }.onFailure { Log.w(TAG, "GET $url: ${it.message}") }.getOrNull()

    /** Keyword search over the archive's Winamp skin collection. */
    fun search(query: String?, rows: Int = 24): List<Skin> {
        val q = query?.trim().orEmpty()
        val expr = if (q.isBlank()) "collection:(winampskins)" else "collection:(winampskins) AND (${q.replace("\"", "")})"
        val url = "$SEARCH?q=${URLEncoder.encode(expr, "UTF-8")}&fl%5B%5D=identifier&fl%5B%5D=title" +
            "&rows=$rows&page=1&output=json&sort%5B%5D=downloads+desc"
        val body = get(url)?.toString(Charsets.UTF_8) ?: return emptyList()
        return runCatching {
            val docs = JSONObject(body).getJSONObject("response").getJSONArray("docs")
            (0 until docs.length()).mapNotNull { i ->
                val d = docs.getJSONObject(i)
                val id = d.optString("identifier").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Skin(id, d.optString("title", id))
            }
        }.getOrDefault(emptyList())
    }

    /** True once the skin's bitmaps are unpacked on the glasses. */
    fun isInstalled(id: String) = File(dir, id).let { it.isDirectory && (it.list()?.isNotEmpty() == true) }

    /**
     * Fetch and unpack a skin, returning `sheet name (lower case, no extension) → absolute path`.
     * Cached: a skin is only pulled over the network once.
     */
    @Synchronized fun install(id: String): Map<String, String> {
        val out = File(dir, id)
        if (!isInstalled(id)) {
            val wsz = findSkinFile(id) ?: run { Log.w(TAG, "no skin file in $id"); return emptyMap() }
            val bytes = get("$DOWNLOAD/$id/${URLEncoder.encode(wsz, "UTF-8").replace("+", "%20")}", 40_000)
                ?: run { Log.w(TAG, "download failed for $id"); return emptyMap() }
            out.mkdirs()
            runCatching {
                ZipInputStream(bytes.inputStream()).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        val name = File(e.name).name
                        // Flatten: skins occasionally nest a folder, and never legitimately traverse.
                        if (!e.isDirectory && name.isNotBlank() && !name.startsWith(".")) {
                            File(out, name).outputStream().use { zip.copyTo(it) }
                        }
                        zip.closeEntry(); e = zip.nextEntry
                    }
                }
            }.onFailure { Log.w(TAG, "unzip $id: ${it.message}"); out.deleteRecursively(); return emptyMap() }
        }
        return (out.listFiles() ?: emptyArray()).filter { it.isFile }
            .associate { it.nameWithoutExtension.lowercase() to it.absolutePath }
    }

    /** The archive stores one `.wsz`/`.zip` per item, under a name we have to look up. */
    private fun findSkinFile(id: String): String? {
        val meta = get("https://archive.org/metadata/$id")?.toString(Charsets.UTF_8) ?: return null
        return runCatching {
            val files = JSONObject(meta).getJSONArray("files")
            (0 until files.length()).map { files.getJSONObject(it).optString("name") }
                .firstOrNull { it.endsWith(".wsz", true) || it.endsWith(".wal", true) || it.endsWith(".zip", true) }
        }.getOrNull()
    }

    /**
     * Every path that puts a skin on the player — the gallery tap, a card in the Skins panel, the
     * voice tool — comes through here, so this is the one place that can remember the choice.
     * Without it, reopening the window silently reverted to [DEFAULT_ID] and the skin you picked
     * looked like it had been thrown away.
     */
    fun installedJson(id: String): JSONObject {
        val map = install(id)
        if (map.isNotEmpty()) rememberWorn(id)
        return JSONObject().put("id", id).put("ok", map.isNotEmpty())
            .put("sheets", JSONObject().also { o -> map.forEach { (k, v) -> o.put(k, "file://$v") } })
    }

    private const val PREFS = "skin_store"
    private const val KEY_WORN = "last_worn"

    private fun rememberWorn(id: String) = runCatching {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WORN, id).apply()
    }

    /** The skin a freshly opened player should wear: whatever was last worn, else the bundled default. */
    fun lastWorn(): String = runCatching {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WORN, null)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_ID

    fun searchJson(query: String?): JSONArray =
        JSONArray().also { a -> search(query).forEach { a.put(it.json()) } }
}
