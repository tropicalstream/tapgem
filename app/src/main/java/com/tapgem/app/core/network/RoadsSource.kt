package com.tapgem.app.core.network

import android.util.Log
import com.tapgem.app.core.media.Net
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * The streets around a point, as vector polylines for the navigation HUD's
 * minimap (Overpass, no key). Results are cached on a ~250 m grid so a walk
 * down one block is a single request, and a failure only costs the minimap
 * its surroundings — the route line and position never depend on it.
 */
object RoadsSource {

    private const val TAG = "RoadsSource"
    private const val RADIUS_M = 450
    private const val CACHE_MAX = 24
    private const val MIN_GAP_MS = 20_000L
    private val ENDPOINTS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter")
    private val EXCLUDE = "construction|proposed|platform|corridor|elevator|raceway|bus_guideway|escape"

    /** Cache key: the grid cell; value: the compact JSON handed to the page. */
    private val cache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > CACHE_MAX
    }
    private var lastFetchMs = 0L
    @Volatile private var inFlight: String? = null

    /** The cell a position falls in — the minimap re-fetches only when this changes. */
    fun cellOf(lat: Double, lon: Double): String {
        val ky = 250.0 / 111_320.0
        val kx = 250.0 / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        return "%d,%d".format(Locale.US, (lat / ky).roundToInt(), (lon / kx).roundToInt())
    }

    fun cached(lat: Double, lon: Double): String? = synchronized(cache) { cache[cellOf(lat, lon)] }

    /**
     * Roads around (lat, lon) as `{"c":[lat,lon],"r":450,"roads":[{"k":"residential","n":"Park Blvd","p":[[lat,lon],…]},…]}`,
     * from cache when possible. Blocking; call off-main. Null when Overpass is unreachable.
     */
    fun around(lat: Double, lon: Double): String? {
        val key = cellOf(lat, lon)
        synchronized(cache) { cache[key]?.let { return it } }
        if (inFlight == key) return null
        val now = System.currentTimeMillis()
        if (now - lastFetchMs < MIN_GAP_MS) return null       // one request in flight per ~20 s, whatever the fix rate
        lastFetchMs = now; inFlight = key
        try {
            val q = """[out:json][timeout:12];way(around:$RADIUS_M,%.6f,%.6f)["highway"]["highway"!~"$EXCLUDE"];out geom tags;"""
                .format(Locale.US, lat, lon)
            for (ep in ENDPOINTS) {
                val json = runCatching {
                    val req = Request.Builder().url(ep).header("User-Agent", "TapGem/1.0 (RayNeo X3 Pro AR glasses)")
                        .post(("data=" + java.net.URLEncoder.encode(q, "UTF-8")).toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
                    Net.http.newCall(req).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body?.string().orEmpty() }
                }.onFailure { Log.w(TAG, "$ep: ${it.message}") }.getOrNull() ?: continue
                val compact = compact(json, lat, lon) ?: continue
                synchronized(cache) { cache[key] = compact }
                return compact
            }
            return null
        } finally { inFlight = null }
    }

    private fun compact(json: String, lat: Double, lon: Double): String? = runCatching {
        val els = JSONObject(json).getJSONArray("elements")
        val roads = JSONArray()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val geom = e.optJSONArray("geometry") ?: continue
            if (geom.length() < 2) continue
            val pts = JSONArray()
            for (j in 0 until geom.length()) { val g = geom.getJSONObject(j); pts.put(JSONArray().put(round(g.getDouble("lat"))).put(round(g.getDouble("lon")))) }
            val road = JSONObject().put("k", tags.optString("highway")).put("p", pts)
            tags.optString("name").takeIf { it.isNotBlank() }?.let { road.put("n", it) }
            roads.put(road)
        }
        JSONObject().put("c", JSONArray().put(lat).put(lon)).put("r", RADIUS_M).put("roads", roads).toString()
    }.onFailure { Log.w(TAG, "parse: ${it.message}") }.getOrNull()

    private fun round(v: Double): Double = (v * 1e6).roundToInt() / 1e6
}
