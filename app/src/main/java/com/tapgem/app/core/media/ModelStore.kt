package com.tapgem.app.core.media

import android.content.Context
import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * 3D models fetched on request from Poly Pizza (poly.pizza), searched and pulled down the moment
 * someone asks for one — the same shape as [com.tapgem.app.core.music.SkinStore] for Winamp skins:
 * nothing ships with the app, because these are other people's work.
 *
 * Gated behind a free API key the way [com.tapgem.app.core.store.ApiKeyStore] gates the Gemini
 * key: read from a file the user drops on the device, never created or fetched by the app itself.
 * Creating that account is explicitly the user's to do.
 *
 * A licence tag on a Poly Pizza upload covers what the *uploader* made, not whatever a search term
 * happens to name. A model titled after a copyrighted design (a film ship, a game character) is
 * someone's fan work wearing a CC licence it cannot actually extend to that design — so a hit here
 * is a starting point to show the user, not a claim that it is clear to use.
 */
object ModelStore {
    private const val TAG = "ModelStore"
    private const val KEY_FILE_NAME = "poly_pizza_api_key.txt"
    private const val API = "https://api.poly.pizza/v1.1"   // the bare host 404s on every route

    private lateinit var appContext: Context
    private val cacheDir get() = File(appContext.filesDir, "models").apply { mkdirs() }
    fun init(context: Context) { appContext = context.applicationContext }

    fun hasKey(): Boolean = key() != null

    private fun key(): String? = runCatching {
        val dir = appContext.getExternalFilesDir(null) ?: return@runCatching null
        File(dir, KEY_FILE_NAME).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    class Model(val id: String, val title: String, val licence: String, val attribution: String,
                val downloadUrl: String, val thumb: String, val creator: String)

    private fun get(url: String): JSONObject? {
        val k = key() ?: return null
        return runCatching {
            val req = Request.Builder().url(url).header("x-auth-token", k)
                .header("User-Agent", "TapGem/1.0 (RayNeo X3 Pro)").build()
            Net.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.w(TAG, "HTTP ${r.code} for $url"); return null }
                JSONObject(r.body?.string().orEmpty())
            }
        }.onFailure { Log.w(TAG, "request failed: ${it.message}") }.getOrNull()
    }

    /** Keyword search. Licence 1 = CC0 only by default — no attribution burden on the glasses. */
    fun search(query: String, ccbyToo: Boolean = false, limit: Int = 8): List<Model> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val lic = if (ccbyToo) "" else "&License=1"
        val body = get("$API/search/$q?Limit=$limit$lic") ?: return emptyList()
        val results = body.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val m = results.optJSONObject(i) ?: return@mapNotNull null
            val dl = m.optString("Download").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Model(m.optString("ID"), m.optString("Title", "Untitled"), m.optString("Licence", ""),
                m.optString("Attribution", ""), dl, m.optString("Thumbnail", ""),
                m.optJSONObject("Creator")?.optString("Username").orEmpty())
        }
    }

    /** Download + cache by Poly Pizza id; a second request for the same id costs nothing. */
    fun fetch(model: Model): File? {
        val f = File(cacheDir, "${model.id}.glb")
        if (f.exists() && f.length() > 0) return f
        return runCatching {
            val req = Request.Builder().url(model.downloadUrl)
                .header("User-Agent", "TapGem/1.0 (RayNeo X3 Pro)").build()
            Net.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.w(TAG, "download HTTP ${r.code}"); return null }
                f.outputStream().use { out -> r.body?.byteStream()?.copyTo(out) }
            }
            recordAttribution(model)
            f
        }.onFailure { Log.w(TAG, "download failed: ${it.message}"); f.delete() }.getOrNull()
    }

    /** Every fetched model's required attribution, kept alongside the cache — not shown on the HUD,
     *  but available if the user ever asks "where did this come from". */
    private fun recordAttribution(m: Model) {
        runCatching {
            File(cacheDir, "attributions.txt").appendText(
                "${m.id}\t${m.title}\t${m.licence}\t${m.attribution}\n")
        }
    }
}
