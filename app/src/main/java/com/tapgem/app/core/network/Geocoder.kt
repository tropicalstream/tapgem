package com.tapgem.app.core.network

import android.util.Log
import com.tapgem.app.core.media.Net
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** Place name → coordinates (Nominatim), plus a coarse "where am I" via IP. */
object Geocoder {

    private const val TAG = "Geocoder"
    private const val UA = "TapGem/1.0 (RayNeo X3 Pro AR glasses; contact: tropicalstream on GitHub)"

    data class Place(val lat: Double, val lon: Double, val label: String, val kind: String = "")

    private val HERE = setOf("here", "me", "my location", "where i am", "where am i", "current location",
        "my position", "around me", "nearby", "my area", "this location", "wherever i am")

    fun isHere(q: String?): Boolean {
        val s = q?.trim()?.lowercase(Locale.US)?.replace(Regex("[?.!]"), "") ?: return true
        return s.isBlank() || s in HERE || s.startsWith("where i am") || s.startsWith("my current")
    }

    fun lookup(query: String?): Place? {
        if (isHere(query)) return locateByIp()
        val q = query!!.trim()
        return runCatching {
            val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + URLEncoder.encode(q, "UTF-8")
            val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en").build()
            Net.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val arr = JSONArray(r.body?.string().orEmpty())
                if (arr.length() == 0) return null
                val o = arr.getJSONObject(0)
                Place(
                    lat = o.getString("lat").toDouble(), lon = o.getString("lon").toDouble(),
                    label = o.optString("name").ifBlank { o.optString("display_name").substringBefore(',') },
                    kind = o.optString("addresstype").ifBlank { o.optString("type") }
                )
            }
        }.onFailure { Log.w(TAG, "nominatim '$q': ${it.message}") }.getOrNull()
    }

    /** Suggested zoom for what the user named: a country needs ~5, a street ~16. */
    fun zoomFor(kind: String, fallback: Int = 13): Int = when (kind.lowercase(Locale.US)) {
        "country" -> 5
        "state", "province", "region" -> 7
        "county", "district" -> 10
        "city", "municipality" -> 12
        "town", "borough" -> 13
        "village", "suburb", "neighbourhood", "quarter", "hamlet" -> 14
        "road", "street", "residential", "pedestrian", "primary", "secondary", "tertiary" -> 16
        "building", "house", "amenity", "shop", "tourism", "leisure", "office", "railway", "aeroway" -> 17
        else -> fallback
    }

    /** Coordinates → a short place label ("Downtown, Oakland"). */
    fun reverse(lat: Double, lon: Double): String? = runCatching {
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=16&lat=%.6f&lon=%.6f".format(Locale.US, lat, lon)
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en").build()
        Net.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val o = JSONObject(r.body?.string().orEmpty())
            val a = o.optJSONObject("address")
            val parts = listOfNotNull(
                a?.optString("road")?.takeIf { it.isNotBlank() },
                (a?.optString("neighbourhood")?.takeIf { it.isNotBlank() } ?: a?.optString("suburb")?.takeIf { it.isNotBlank() }),
                (a?.optString("city")?.takeIf { it.isNotBlank() } ?: a?.optString("town")?.takeIf { it.isNotBlank() } ?: a?.optString("village")?.takeIf { it.isNotBlank() })
            ).distinct()
            parts.take(3).joinToString(", ").ifBlank { o.optString("display_name").substringBefore(',') }
        }
    }.onFailure { Log.w(TAG, "reverse: ${it.message}") }.getOrNull()

    fun locateByIp(): Place? {
        runCatching {
            val req = Request.Builder().url("https://ipinfo.io/json").header("User-Agent", UA).build()
            Net.http.newCall(req).execute().use { r ->
                val o = JSONObject(r.body?.string().orEmpty())
                val loc = o.optString("loc").split(',')
                if (loc.size == 2) return Place(loc[0].toDouble(), loc[1].toDouble(),
                    listOf(o.optString("city"), o.optString("region")).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Your area" }, "city")
            }
        }.onFailure { Log.w(TAG, "ipinfo: ${it.message}") }
        runCatching {
            val req = Request.Builder().url("http://ip-api.com/json/?fields=status,city,regionName,lat,lon").header("User-Agent", UA).build()
            Net.http.newCall(req).execute().use { r ->
                val o = JSONObject(r.body?.string().orEmpty())
                if (o.optString("status") == "success") return Place(o.getDouble("lat"), o.getDouble("lon"),
                    listOf(o.optString("city"), o.optString("regionName")).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Your area" }, "city")
            }
        }.onFailure { Log.w(TAG, "ip-api: ${it.message}") }
        return null
    }
}
