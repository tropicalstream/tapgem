package com.tapgem.app.core.network

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Conservative request blocker for web widgets: known ad / tracker hosts and
 * obvious ad paths on third-party pages get an empty 204. First-party media
 * sites are allow-listed so players keep working. Less to fetch and render
 * also means less to burn on the glasses. Ported from TapInsight's browser.
 */
object WebAdBlocker {

    fun intercept(url: String?): WebResourceResponse? {
        if (!shouldBlock(url)) return null
        return WebResourceResponse(
            "text/plain", "utf-8", 204, "No Content",
            mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    fun shouldBlock(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase(Locale.US)?.trim('.') ?: return false
        val path = uri.encodedPath?.lowercase(Locale.US).orEmpty()
        val query = uri.encodedQuery?.lowercase(Locale.US).orEmpty()
        if (isAllowedHost(host)) return false
        if (AD_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        if (AD_HOST_KEYWORDS.any { host.contains(it) }) return true
        if (AD_PATH_KEYWORDS.any { path.contains(it) || query.contains(it) }) return true
        return false
    }

    private fun isAllowedHost(host: String): Boolean {
        if (host == "127.0.0.1" || host == "localhost" || host == "::1") return true
        return ALLOWED.any { host == it || host.endsWith(".$it") }
    }

    private val ALLOWED = setOf(
        "radio.garden", "youtube.com", "youtu.be", "ytimg.com", "googlevideo.com", "spotify.com",
        "scdn.co", "spotifycdn.com", "archive.org", "google.com", "googleusercontent.com", "gstatic.com",
        "openstreetmap.org", "arcgisonline.com", "wikipedia.org", "wikimedia.org"
    )

    private val AD_HOSTS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
        "googletagmanager.com", "google-analytics.com", "adservice.google.com", "adsystem.com",
        "amazon-adsystem.com", "adsrvr.org", "adnxs.com", "rubiconproject.com", "pubmatic.com",
        "openx.net", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "scorecardresearch.com",
        "quantserve.com", "moatads.com", "branch.io", "segment.io", "segment.com", "hotjar.com",
        "fullstory.com", "sentry.io", "facebook.net", "connect.facebook.net", "analytics.tiktok.com",
        "snapads.com", "2mdn.net", "casalemedia.com", "indexww.com", "bidswitch.net", "smartadserver.com",
        "adroll.com", "adform.net", "chartbeat.com", "mixpanel.com", "newrelic.com", "nr-data.net",
        "bugsnag.com", "yieldmo.com", "sharethrough.com", "media.net", "33across.com", "zemanta.com",
        "revcontent.com", "mgid.com", "onesignal.com", "pushengage.com"
    )

    private val AD_HOST_KEYWORDS = setOf("adserver", "adservice", "adnxs", "tracking", "telemetry", "beacon")

    private val AD_PATH_KEYWORDS = setOf("/ads/", "/ad/", "/advert", "/analytics", "/tracking", "/track?", "/beacon", "/pixel?", "google_ads", "prebid")
}
