package com.tapgem.app.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Business names ("Glenview Taqueria") are not in OpenStreetMap often enough
 * for Nominatim, but Google Maps knows them. Without an API key the one
 * key-free way to ask Google is its own web app: an off-screen WebView loads
 * the search, Google resolves a unique match to a place page whose URL
 * carries the exact coordinates (`!3d<lat>!4d<lon>`) and the place's name.
 * Only that URL is read — no page content, no scraping.
 */
object GooglePlaceLookup {

    private const val TAG = "GooglePlaceLookup"
    private const val TIMEOUT_MS = 12_000L
    private const val POLL_MS = 350L
    private val main = Handler(Looper.getMainLooper())
    private val COORDS = Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)")
    private val PLACE_PATH = Regex("/maps/place/([^/@]+)")

    /** Coordinates + Google's name for [query]; [nearLat]/[nearLon] bias the search to where the user is. */
    suspend fun lookup(context: Context, query: String, nearLat: Double?, nearLon: Double?): Geocoder.Place? {
        val q = query.trim().takeIf { it.isNotBlank() } ?: return null
        val url = "https://www.google.com/maps/search/" + URLEncoder.encode(q, "UTF-8").replace("+", "%20") +
            (if (nearLat != null && nearLon != null) "/@%.5f,%.5f,14z".format(Locale.US, nearLat, nearLon) else "")
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                main.post {
                    var wv: WebView? = null
                    var done = false
                    fun finish(p: Geocoder.Place?) {
                        if (done) return
                        done = true
                        runCatching { wv?.stopLoading(); wv?.destroy() }
                        if (cont.isActive) cont.resume(p)
                    }
                    try {
                        wv = newHeadlessWebView(context)
                    } catch (e: Throwable) {
                        Log.w(TAG, "no WebView: ${e.message}"); finish(null); return@post
                    }
                    cont.invokeOnCancellation { main.post { finish(null) } }
                    val view = wv!!
                    var ticks = 0
                    val poll = object : Runnable {
                        override fun run() {
                            if (done) return
                            parse(view.url)?.let { finish(it); return }
                            if (++ticks % 6 == 0) Log.d(TAG, "waiting… at ${view.url?.take(160)} title=${view.title?.take(60)}")
                            main.postDelayed(this, POLL_MS)
                        }
                    }
                    view.webViewClient = object : WebViewClient() {
                        // Maps tries to hand off to its app (intent://…): stay on the web page instead.
                        override fun shouldOverrideUrlLoading(v: WebView, request: android.webkit.WebResourceRequest): Boolean {
                            val scheme = request.url.scheme?.lowercase(Locale.US)
                            return scheme != "http" && scheme != "https"
                        }
                        override fun onPageFinished(v: WebView, u: String?) { parse(u)?.let { finish(it) } }
                    }
                    view.loadUrl(url)
                    main.postDelayed(poll, POLL_MS)
                }
            }
        }.also { Log.i(TAG, "'$q' → ${it?.let { p -> "${p.label} @ ${p.lat},${p.lon}" } ?: "no unique place"} ($url)") }
    }

    /** `/maps/place/<Name,+Address>/@…/data=…!3d<lat>!4d<lon>` → a Place; anything else → null. */
    fun parse(url: String?): Geocoder.Place? {
        if (url.isNullOrBlank() || !url.contains("/maps/place/")) return null
        val m = COORDS.find(url) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        val raw = PLACE_PATH.find(url)?.groupValues?.get(1) ?: return null
        val full = runCatching { URLDecoder.decode(raw.replace("+", " "), "UTF-8") }.getOrDefault(raw)
        val name = full.substringBefore(',').trim().take(40).ifBlank { full.take(40) }
        return Geocoder.Place(lat, lon, name, "amenity")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newHeadlessWebView(context: Context): WebView {
        val wv = WebView(context.applicationContext)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.loadsImagesAutomatically = false   // we only need the redirect, not the map
        wv.settings.blockNetworkImage = true
        wv.webChromeClient = WebChromeClient()          // default: geolocation and permissions denied
        wv.layout(0, 0, 640, 480)
        return wv
    }
}
