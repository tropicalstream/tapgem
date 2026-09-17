package com.tapgem.app.core.network

import android.util.Log
import com.tapgem.app.core.media.Net
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turn-by-turn routes from OSRM (the FOSSGIS servers behind openstreetmap.de:
 * car / foot / bike profiles, no key). The result is compact JSON the map
 * page draws and the widget stores as its content.
 */
object Router {

    private const val TAG = "Router"
    private const val UA = "TapGem/1.0 (RayNeo X3 Pro AR glasses)"

    class Step(val lat: Double, val lon: Double, val text: String, val distM: Double, val durS: Double)
    class Route(val coords: List<DoubleArray>, val steps: List<Step>, val distM: Double, val durS: Double, val mode: String, val dest: String) {
        fun toJson(): JSONObject = JSONObject()
            .put("coords", JSONArray().also { a -> coords.forEach { c -> a.put(JSONArray().put(c[0]).put(c[1])) } })
            .put("steps", JSONArray().also { a -> steps.forEach { s -> a.put(JSONObject().put("lat", s.lat).put("lon", s.lon).put("text", s.text).put("dist", s.distM).put("dur", s.durS)) } })
            .put("dist", distM).put("dur", durS).put("mode", mode).put("dest", dest)

        companion object {
            fun fromJson(s: String): Route? = runCatching {
                val o = JSONObject(s)
                val coords = o.getJSONArray("coords").let { a -> List(a.length()) { i -> val c = a.getJSONArray(i); doubleArrayOf(c.getDouble(0), c.getDouble(1)) } }
                val steps = o.getJSONArray("steps").let { a -> List(a.length()) { i -> val st = a.getJSONObject(i); Step(st.getDouble("lat"), st.getDouble("lon"), st.getString("text"), st.getDouble("dist"), st.getDouble("dur")) } }
                Route(coords, steps, o.getDouble("dist"), o.getDouble("dur"), o.optString("mode", "foot"), o.optString("dest"))
            }.getOrNull()
        }
    }

    /** mode: driving | walking | bicycling (transit falls back to walking). */
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, mode: String, dest: String): Route? {
        val profile = when (mode) { "driving" -> "car"; "bicycling" -> "bike"; else -> "foot" }
        val url = "https://routing.openstreetmap.de/routed-$profile/route/v1/driving/" +
            "%.6f,%.6f;%.6f,%.6f?steps=true&overview=full&geometries=geojson&alternatives=false".format(Locale.US, fromLon, fromLat, toLon, toLat)
        return runCatching {
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            Net.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val o = JSONObject(r.body?.string().orEmpty())
                if (o.optString("code") != "Ok") error(o.optString("message", o.optString("code")))
                val route = o.getJSONArray("routes").getJSONObject(0)
                val geom = route.getJSONObject("geometry").getJSONArray("coordinates")
                val raw = List(geom.length()) { i -> val c = geom.getJSONArray(i); doubleArrayOf(c.getDouble(0), c.getDouble(1)) }
                val coords = if (raw.size <= 600) raw else raw.filterIndexed { i, _ -> i % (raw.size / 600 + 1) == 0 || i == raw.size - 1 }
                val steps = ArrayList<Step>()
                val legs = route.getJSONArray("legs")
                for (l in 0 until legs.length()) {
                    val ss = legs.getJSONObject(l).getJSONArray("steps")
                    for (i in 0 until ss.length()) {
                        val st = ss.getJSONObject(i)
                        val man = st.getJSONObject("maneuver")
                        val loc = man.getJSONArray("location")
                        steps += Step(loc.getDouble(1), loc.getDouble(0), instruction(st, man, mode), st.optDouble("distance", 0.0), st.optDouble("duration", 0.0))
                    }
                }
                Route(coords, steps, route.optDouble("distance", 0.0), route.optDouble("duration", 0.0), mode, dest)
            }
        }.onFailure { Log.w(TAG, "route failed: ${it.message}") }.getOrNull()
    }

    private fun instruction(step: JSONObject, man: JSONObject, mode: String): String {
        val name = step.optString("name").takeIf { it.isNotBlank() }
        val type = man.optString("type"); val mod = man.optString("modifier")
        val turn = when (mod) {
            "sharp left" -> "sharp left"; "slight left" -> "slightly left"; "left" -> "left"
            "sharp right" -> "sharp right"; "slight right" -> "slightly right"; "right" -> "right"
            "uturn" -> "around"; "straight" -> "straight"; else -> mod
        }
        val onto = name?.let { " onto $it" } ?: ""
        val on = name?.let { " on $it" } ?: ""
        return when (type) {
            "depart" -> "Head ${cardinal(man.optDouble("bearing_after", -1.0))}$on"
            "arrive" -> "Arrive at your destination" + when (mod) { "left" -> ", on the left"; "right" -> ", on the right"; else -> "" }
            "turn" -> if (turn == "straight") "Continue straight$onto" else if (turn == "around") "Make a U-turn$onto" else "Turn $turn$onto"
            "new name", "continue" -> "Continue$onto"
            "merge" -> "Merge $turn$onto"
            "fork" -> "Keep $turn at the fork$onto"
            "end of road" -> "At the end of the road turn $turn$onto"
            "on ramp" -> "Take the ramp $turn$onto"
            "off ramp" -> "Take the exit $turn$onto"
            "roundabout", "rotary" -> "At the roundabout take exit ${man.optInt("exit", 1)}$onto"
            "exit roundabout", "exit rotary" -> "Exit the roundabout$onto"
            "notification" -> "Continue$on"
            else -> (if (turn.isNotBlank() && turn != "straight") "Turn $turn$onto" else "Continue$onto")
        }.trim()
    }

    private fun cardinal(bearing: Double): String {
        if (bearing < 0) return "out"
        val dirs = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
        return dirs[((bearing + 22.5) / 45.0).toInt() % 8]
    }

    /** "350 ft" / "0.8 mi" — US units for a US user; metres if the locale isn't. */
    fun distance(m: Double): String {
        val us = Locale.getDefault().country == "US" || Locale.getDefault().country.isBlank()
        return if (us) {
            val ft = m * 3.28084
            if (ft < 1000) "${(ft / 10).roundToInt() * 10} ft" else "%.1f mi".format(Locale.US, m / 1609.344)
        } else if (m < 1000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(Locale.US, m / 1000)
    }

    fun duration(s: Double): String {
        val min = (s / 60).roundToInt()
        return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(abs(1 - a)))
    }
}
