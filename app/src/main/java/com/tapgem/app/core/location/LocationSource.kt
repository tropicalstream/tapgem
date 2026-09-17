package com.tapgem.app.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.tapgem.app.core.network.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import okhttp3.MediaType.Companion.toMediaTypeOrNull

/**
 * Where the glasses are, derived the way the platform intends:
 *
 *  1. a fresh fix from [LocationManager.getCurrentLocation] on the fused
 *     provider (then network, then GPS), with a cancellation signal and a
 *     timeout — never a blocking or indefinite listener;
 *  2. else the freshest last-known fix any provider still holds;
 *  3. else a coarse IP-based estimate (clearly labelled as such).
 *
 * Fixes are cached for [FRESH_MS] so a burst of tool calls reuses one
 * request. Runtime permission is checked every time; without it only the
 * IP fallback is used. No Play Services are assumed (the X3 has none).
 */
object LocationSource {

    private const val TAG = "LocationSource"
    private const val FRESH_MS = 2 * 60_000L
    private const val FIX_TIMEOUT_MS = 7_000L
    private const val LAST_KNOWN_MAX_AGE_MS = 15 * 60_000L
    private const val PHONE_FIX_TIMEOUT_MS = 6_000L

    data class Fix(val lat: Double, val lon: Double, val accuracyM: Float, val source: String, val atMs: Long) {
        val isPrecise: Boolean get() = source != "ip"
        fun latLon(): String = "%.6f,%.6f".format(java.util.Locale.US, lat, lon)
    }

    @Volatile private var cached: Fix? = null

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Best available position, or null if nothing at all could be determined. */
    suspend fun current(context: Context, allowIpFallback: Boolean = true, maxAgeMs: Long = FRESH_MS): Fix? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        // Tier 0 — the paired phone's real GPS through RayNeo's IPC (what the stock navigation uses).
        // A fresh cached push, else the launcher's last fix, else start the stream and wait briefly.
        PhoneGps.latest(maxAgeMs)?.let { cached = it; return@withContext it }
        if (PhoneGps.isPhoneConnected(app)) {
            PhoneGps.oneShot(app, maxAgeMs)?.let { cached = it; return@withContext it }
            PhoneGps.awaitFix(app, timeoutMs = PHONE_FIX_TIMEOUT_MS, maxAgeMs = maxAgeMs)?.let { cached = it; return@withContext it }
        } else runCatching { PhoneGps.oneShot(app, maxAgeMs) }.getOrNull()?.let { cached = it; return@withContext it }
        cached?.takeIf { SystemClock.elapsedRealtime() - it.atMs < maxAgeMs && (allowIpFallback || it.isPrecise) }?.let { return@withContext it }
        val fix = platformFix(app) ?: lastKnown(app) ?: wifiFix(app)?.takeIf { allowIpFallback || it.isPrecise } ?: (if (allowIpFallback) ipFix() else null)
        if (fix != null) cached = fix
        fix
    }

    /** Keep the phone stream alive (navigation): cheap to call repeatedly. */
    fun keepPhoneStream(context: Context) = PhoneGps.ensureStreaming(context)

    private suspend fun platformFix(context: Context): Fix? {
        if (!hasPermission(context)) { Log.i(TAG, "no location permission — skipping platform fix"); return null }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !lm.isLocationEnabled) { Log.i(TAG, "location services off"); return null }
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER); add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        for (p in providers) {
            val loc = requestOnce(lm, p) ?: continue
            Log.i(TAG, "fix from $p acc=${loc.accuracy}m")
            return Fix(loc.latitude, loc.longitude, loc.accuracy, p, SystemClock.elapsedRealtime())
        }
        return null
    }

    private suspend fun requestOnce(lm: LocationManager, provider: String): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val signal = CancellationSignal()
        return try {
            withTimeoutOrNull(FIX_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val executor = Executor { it.run() }
                    try {
                        lm.getCurrentLocation(provider, signal, executor) { loc -> if (cont.isActive) cont.resume(loc) }
                    } catch (e: SecurityException) { if (cont.isActive) cont.resume(null) }
                      catch (e: IllegalArgumentException) { if (cont.isActive) cont.resume(null) }
                    cont.invokeOnCancellation { runCatching { signal.cancel() } }
                }
            }.also { if (it == null) runCatching { signal.cancel() } }
        } catch (e: Exception) { Log.w(TAG, "$provider: ${e.message}"); null }
    }

    private fun lastKnown(context: Context): Fix? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val now = SystemClock.elapsedRealtimeNanos()
        val best = lm.allProviders.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { (now - it.elapsedRealtimeNanos) / 1_000_000L < LAST_KNOWN_MAX_AGE_MS }
            .minByOrNull { it.accuracy } ?: return null
        Log.i(TAG, "last-known fix from ${best.provider} acc=${best.accuracy}m")
        return Fix(best.latitude, best.longitude, best.accuracy, "last:${best.provider}", SystemClock.elapsedRealtime())
    }

    /**
     * Wi-Fi positioning through BeaconDB (open, MLS-compatible): the X3 Pro
     * has no GPS or network location provider at all, so the access points
     * around it are the best signal there is. APs whose SSID ends in _nomap
     * are honoured and skipped. If BeaconDB knows none of them it answers
     * with its own IP estimate, which is labelled as such.
     */
    private fun wifiFix(context: Context): Fix? {
        if (!hasPermission(context)) return null
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return null
        val scan = runCatching { @Suppress("DEPRECATION") wm.scanResults }.getOrNull().orEmpty()
            .filter { it.BSSID != null && !(it.SSID ?: "").endsWith("_nomap") && !(it.SSID ?: "").contains("_optout") }
            .sortedByDescending { it.level }.take(16)
        val aps = org.json.JSONArray()
        scan.forEach { r ->
            aps.put(org.json.JSONObject().put("macAddress", r.BSSID).put("signalStrength", r.level).put("frequency", r.frequency))
        }
        val body = org.json.JSONObject().put("wifiAccessPoints", aps).put("considerIp", true).toString()
        return runCatching {
            val req = okhttp3.Request.Builder().url("https://api.beacondb.net/v1/geolocate")
                .header("User-Agent", "TapGem/1.0 (RayNeo X3 Pro)")
                .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body)).build()
            com.tapgem.app.core.media.Net.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val o = org.json.JSONObject(r.body?.string().orEmpty())
                val loc = o.getJSONObject("location")
                val acc = o.optDouble("accuracy", 5000.0).toFloat()
                val ipFallback = o.optString("fallback") == "ipf" || scan.isEmpty()
                Log.i(TAG, "beacondb: ${scan.size} APs → acc=${acc.toInt()}m${if (ipFallback) " (ip fallback)" else ""}")
                Fix(loc.getDouble("lat"), loc.getDouble("lng"), acc, if (ipFallback) "ip" else "wifi", SystemClock.elapsedRealtime())
            }
        }.onFailure { Log.w(TAG, "beacondb: ${it.message}") }.getOrNull()
    }

    private fun ipFix(): Fix? = Geocoder.locateByIp()?.let {
        Log.i(TAG, "IP-based estimate (${it.label})")
        Fix(it.lat, it.lon, 5_000f, "ip", SystemClock.elapsedRealtime())
    }

    /** Human words for the fix: "near Downtown Oakland (Wi-Fi fix, ±40 m)". */
    fun describe(fix: Fix, placeLabel: String?): String {
        val how = when {
            fix.source == "phone" -> "from your phone's GPS, about ${fix.accuracyM.toInt()} m"
            fix.source == "ip" -> "rough, from your internet connection"
            fix.source == "wifi" -> "from nearby Wi-Fi, about ${fix.accuracyM.toInt()} m"
            fix.source.startsWith("last") -> "recent fix, about ${fix.accuracyM.toInt()} m"
            else -> "about ${fix.accuracyM.toInt()} m"
        }
        return (placeLabel?.let { "near $it " } ?: "at ${fix.latLon()} ") + "($how)"
    }
}
