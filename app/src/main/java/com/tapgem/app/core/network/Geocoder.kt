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

    data class Place(val lat: Double, val lon: Double, val label: String, val kind: String = "", val display: String = "")

    private val HERE = setOf("here", "me", "my location", "where i am", "where am i", "current location",
        "my position", "around me", "nearby", "my area", "this location", "wherever i am")

    fun isHere(q: String?): Boolean {
        val s = q?.trim()?.lowercase(Locale.US)?.replace(Regex("[?.!]"), "") ?: return true
        return s.isBlank() || s in HERE || s.startsWith("where i am") || s.startsWith("my current")
    }

    /** [nearLat]/[nearLon] bias results towards the user (a viewbox Nominatim prefers but is not bound to). */
    fun lookup(query: String?, nearLat: Double? = null, nearLon: Double? = null): Place? {
        if (isHere(query)) return locateByIp()
        val q = query!!.trim()
        return runCatching {
            val bias = if (nearLat != null && nearLon != null)
                "&viewbox=%.4f,%.4f,%.4f,%.4f&bounded=0".format(Locale.US, nearLon - 0.6, nearLat + 0.5, nearLon + 0.6, nearLat - 0.5) else ""
            val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + URLEncoder.encode(q, "UTF-8") + bias
            val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en").build()
            Net.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val arr = JSONArray(r.body?.string().orEmpty())
                if (arr.length() == 0) return null
                val o = arr.getJSONObject(0)
                Place(
                    lat = o.getString("lat").toDouble(), lon = o.getString("lon").toDouble(),
                    label = o.optString("name").ifBlank { o.optString("display_name").substringBefore(',') },
                    kind = o.optString("addresstype").ifBlank { o.optString("type") },
                    display = o.optString("display_name")
                )
            }
        }.onFailure { Log.w(TAG, "nominatim '$q': ${it.message}") }.getOrNull()
    }

    private val STOP = setOf("the", "a", "an", "to", "at", "in", "on", "of", "and", "near", "by", "street", "st", "ave", "avenue", "road", "rd", "blvd", "boulevard")
    private fun words(s: String) = s.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+")).filter { it.length > 1 && it !in STOP }

    /** Does a geocoder hit plausibly mean what the user said? (Most of their words appear in its address line.) */
    fun matches(userText: String, p: Place): Boolean {
        val uw = words(userText); if (uw.isEmpty()) return true
        val hay = (p.display.ifBlank { p.label }).lowercase(Locale.US)
        val hit = uw.count { hay.contains(it) }
        return hit * 2 >= uw.size
    }

    /** The name to show/speak: the geocoder's when it echoes the user's words, else the user's own words. */
    fun bestLabel(userText: String, p: Place): String {
        val uw = words(userText); val lw = words(p.label)
        val echo = lw.isNotEmpty() && lw.count { it in uw } * 2 >= lw.size
        return if (echo || uw.isEmpty()) p.label else userText.trim().split(Regex("\\s+")).joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }.take(40)
    }

    /**
     * Place-name resolution for navigation: OSM first (addresses, schools, cities),
     * then Google Maps for the business names OSM lacks or mismatches — biased to
     * where the user is.
     */
    class FarAway(val place: Place, val km: Double, val nearby: Place?) : Exception()

    /** Words that mean the user deliberately named somewhere else ("…, Tennessee", "in Portland"). */
    private val EXPLICIT_PLACE = Regex("[,]|\\b(in|near)\\s+[A-Z]|\\b(alabama|alaska|arizona|arkansas|california|colorado|connecticut|delaware|florida|georgia|hawaii|idaho|illinois|indiana|iowa|kansas|kentucky|louisiana|maine|maryland|massachusetts|michigan|minnesota|mississippi|missouri|montana|nebraska|nevada|hampshire|jersey|mexico|york|carolina|dakota|ohio|oklahoma|oregon|pennsylvania|rhode|tennessee|texas|utah|vermont|virginia|washington|wisconsin|wyoming|canada|mexico|europe|japan|uk|france|germany|italy|spain)\\b", RegexOption.IGNORE_CASE)

    /**
     * Resolves a spoken place near the user: OSM first (biased to their area), Google Maps
     * for business names OSM lacks or mismatches. A hit hundreds of km away for a name that
     * doesn't say where ("Monterey Middle School" heard for Montera) throws [FarAway] with
     * the closest alternative Google knows, instead of routing 1,100 miles on foot.
     */
    suspend fun resolve(context: android.content.Context, query: String?, nearLat: Double?, nearLon: Double?): Place? {
        if (isHere(query)) return locateByIp()
        val q = query!!.trim()
        val osm = lookup(q, nearLat, nearLon)
        fun km(p: Place) = if (nearLat != null && nearLon != null) Router.distanceM(nearLat, nearLon, p.lat, p.lon) / 1000.0 else 0.0
        val osmOk = osm != null && matches(q, osm)
        if (osmOk && km(osm!!) <= FAR_KM) return osm.copy(label = bestLabel(q, osm))
        val g = GooglePlaceLookup.lookup(context, q, nearLat, nearLon)
        if (g != null && km(g) <= FAR_KM) return g.copy(label = bestLabel(q, g))
        val far = (if (osmOk) osm else g) ?: osm ?: return null
        if (km(far) > FAR_KM && !EXPLICIT_PLACE.containsMatchIn(q)) throw FarAway(far.copy(label = bestLabel(q, far)), km(far), g?.takeIf { it !== far && km(it) <= FAR_KM })
        return far.copy(label = bestLabel(q, far))
    }

    /** Beyond this, a bare name almost certainly isn't what a person on foot or in a car meant. */
    private const val FAR_KM = 300.0

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
