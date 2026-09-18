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
    /** An intermediate stop ("Glenview Taqueria on the way to school"). */
    class Via(val lat: Double, val lon: Double, val label: String)
    class Route(val coords: List<DoubleArray>, val steps: List<Step>, val distM: Double, val durS: Double, val mode: String, val dest: String,
                val via: List<Via> = emptyList()) {
        val destLat: Double get() = coords.last()[1]
        val destLon: Double get() = coords.last()[0]

        /** Stops not yet reached from where the traveller is (passed = nearer the end than the stop). */
        fun remainingVia(lat: Double, lon: Double): List<Via> {
            if (via.isEmpty()) return via
            val at = nearestCoordIndex(lat, lon)
            return via.filter { v -> nearestCoordIndex(v.lat, v.lon) > at + 3 }
        }

        /** Polyline index nearest to each step's manoeuvre point (lazy). */
        private val stepIdx: List<Int> by lazy { steps.map { st -> nearestCoordIndex(st.lat, st.lon) } }

        fun nearestCoordIndex(lat: Double, lon: Double): Int {
            var best = 0; var bd = Double.MAX_VALUE
            for (i in coords.indices) { val d = distanceM(lat, lon, coords[i][1], coords[i][0]); if (d < bd) { bd = d; best = i } }
            return best
        }

        /** Shortest distance from a position to the route line, in metres. */
        fun distanceToRoute(lat: Double, lon: Double): Double {
            if (coords.size < 2) return distanceM(lat, lon, destLat, destLon)
            val kx = Math.cos(Math.toRadians(lat)) * 111_320.0; val ky = 110_540.0
            val px = lon * kx; val py = lat * ky
            var best = Double.MAX_VALUE
            for (i in 0 until coords.size - 1) {
                val ax = coords[i][0] * kx; val ay = coords[i][1] * ky; val bx = coords[i + 1][0] * kx; val by = coords[i + 1][1] * ky
                val dx = bx - ax; val dy = by - ay
                val t = if (dx == 0.0 && dy == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                val cx = ax + t * dx; val cy = ay + t * dy
                val d = Math.hypot(px - cx, py - cy)
                if (d < best) best = d
            }
            return best
        }

        /**
         * The step the traveller is on: the last step whose manoeuvre lies at or
         * before the nearest point of the route — so a fix half-way down a
         * street reports that street's instruction, not the turn already made.
         */
        fun stepIndexNear(lat: Double, lon: Double): Int {
            if (steps.isEmpty()) return 0
            val at = nearestCoordIndex(lat, lon)
            var idx = 0
            for (i in steps.indices) if (stepIdx[i] <= at) idx = i
            // Within 25 m of the next manoeuvre → announce it already.
            if (idx + 1 < steps.size && distanceM(lat, lon, steps[idx + 1].lat, steps[idx + 1].lon) < 25.0) idx += 1
            return idx
        }
        fun toJson(): JSONObject = JSONObject()
            .put("coords", JSONArray().also { a -> coords.forEach { c -> a.put(JSONArray().put(c[0]).put(c[1])) } })
            .put("steps", JSONArray().also { a -> steps.forEach { s -> a.put(JSONObject().put("lat", s.lat).put("lon", s.lon).put("text", s.text).put("dist", s.distM).put("dur", s.durS)) } })
            .put("via", JSONArray().also { a -> via.forEach { v -> a.put(JSONObject().put("lat", v.lat).put("lon", v.lon).put("label", v.label)) } })
            .put("dist", distM).put("dur", durS).put("mode", mode).put("dest", dest)

        companion object {
            fun fromJson(s: String): Route? = runCatching {
                val o = JSONObject(s)
                val coords = o.getJSONArray("coords").let { a -> List(a.length()) { i -> val c = a.getJSONArray(i); doubleArrayOf(c.getDouble(0), c.getDouble(1)) } }
                val steps = o.getJSONArray("steps").let { a -> List(a.length()) { i -> val st = a.getJSONObject(i); Step(st.getDouble("lat"), st.getDouble("lon"), st.getString("text"), st.getDouble("dist"), st.getDouble("dur")) } }
                val via = o.optJSONArray("via")?.let { a -> List(a.length()) { i -> val v = a.getJSONObject(i); Via(v.getDouble("lat"), v.getDouble("lon"), v.optString("label")) } } ?: emptyList()
                Route(coords, steps, o.getDouble("dist"), o.getDouble("dur"), o.optString("mode", "foot"), o.optString("dest"), via)
            }.getOrNull()
        }
    }

    /**
     * mode: driving | walking | bicycling (transit falls back to walking).
     * [via] stops are routed through in order; each leg's arrival becomes "Arrive at <stop>".
     */
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, mode: String, dest: String, via: List<Via> = emptyList()): Route? {
        val profile = when (mode) { "driving" -> "car"; "bicycling" -> "bike"; else -> "foot" }
        val points = listOf(fromLon to fromLat) + via.map { it.lon to it.lat } + listOf(toLon to toLat)
        val url = "https://routing.openstreetmap.de/routed-$profile/route/v1/driving/" +
            points.joinToString(";") { "%.6f,%.6f".format(Locale.US, it.first, it.second) } +
            "?steps=true&overview=full&geometries=geojson&alternatives=false"
        return runCatching {
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            Net.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val o = JSONObject(r.body?.string().orEmpty())
                if (o.optString("code") != "Ok") error(o.optString("message", o.optString("code")))
                val route = o.getJSONArray("routes").getJSONObject(0)
                val geom = route.getJSONObject("geometry").getJSONArray("coordinates")
                val raw = List(geom.length()) { i -> val c = geom.getJSONArray(i); doubleArrayOf(c.getDouble(0), c.getDouble(1)) }
                val coords = simplify(raw)
                val steps = ArrayList<Step>()
                val legs = route.getJSONArray("legs")
                for (l in 0 until legs.length()) {
                    val ss = legs.getJSONObject(l).getJSONArray("steps")
                    val stop = via.getOrNull(l)   // this leg ends at stop l (the last leg ends at the destination)
                    for (i in 0 until ss.length()) {
                        val st = ss.getJSONObject(i)
                        val man = st.getJSONObject("maneuver")
                        val loc = man.getJSONArray("location")
                        var text = instruction(st, man, mode)
                        if (stop != null && man.optString("type") == "arrive") text = text.replace("your destination", stop.label) + " — then continue to ${if (l + 1 < via.size) via[l + 1].label else dest}"
                        steps += Step(loc.getDouble(1), loc.getDouble(0), text, st.optDouble("distance", 0.0), st.optDouble("duration", 0.0))
                    }
                }
                Route(coords, steps, route.optDouble("distance", 0.0), route.optDouble("duration", 0.0), mode, dest, via)
            }
        }.onFailure { Log.w(TAG, "route failed: ${it.message}") }.getOrNull()
    }

    /**
     * Shrink the polyline without cutting corners: Douglas–Peucker keeps every bend that
     * matters at the tolerance and drops the points along straight streets. The tolerance
     * grows until the line fits in [MAX_POINTS], so a cross-country route still draws the
     * road network rather than a straight line through the buildings.
     */
    fun simplify(raw: List<DoubleArray>, maxPoints: Int = MAX_POINTS): List<DoubleArray> {
        if (raw.size <= maxPoints) return raw
        var tolM = 3.0
        var out = raw
        while (out.size > maxPoints && tolM < 5_000.0) { out = douglasPeucker(raw, tolM); tolM *= 1.8 }
        return out
    }
    private const val MAX_POINTS = 4000

    private fun douglasPeucker(pts: List<DoubleArray>, tolM: Double): List<DoubleArray> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.size - 1] = true
        val kx = Math.cos(Math.toRadians(pts[0][1])) * 111_320.0; val ky = 110_540.0
        val stack = ArrayDeque<IntArray>(); stack.addLast(intArrayOf(0, pts.size - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast().let { it[0] to it[1] }
            if (b - a < 2) continue
            val ax = pts[a][0] * kx; val ay = pts[a][1] * ky; val bx = pts[b][0] * kx; val by = pts[b][1] * ky
            val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
            var best = -1; var bd = 0.0
            for (i in a + 1 until b) {
                val px = pts[i][0] * kx; val py = pts[i][1] * ky
                val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                val d = Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
                if (d > bd) { bd = d; best = i }
            }
            if (best >= 0 && bd > tolM) { keep[best] = true; stack.addLast(intArrayOf(a, best)); stack.addLast(intArrayOf(best, b)) }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
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
