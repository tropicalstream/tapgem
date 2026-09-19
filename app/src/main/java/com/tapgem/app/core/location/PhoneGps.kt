package com.tapgem.app.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.ffalconxr.mercury.ipc.Launcher
import com.ffalconxr.mercury.ipc.Response
import com.ffalconxr.mercury.ipc.helpers.GPSIPCHelper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * The paired phone's real GPS, exactly the way RayNeo's own navigation gets
 * it: the RayNeo AR phone app streams the phone's fused Location over the
 * BLE link to the glasses' launcher, which fans it out to registered apps
 * through the RayNeo IPC SDK (RemoteMultiService → GPSIPCHelper). The
 * payload is a serialised android.location.Location (mLatitude, mLongitude,
 * mHorizontalAccuracyMeters, mSpeed, mBearing, mTime…), pushed about every
 * 5 s while registered.
 *
 * Usage pattern: [latest] for a cached fix, [awaitFix] to start the stream
 * and wait briefly for the first push, [oneShot] for the launcher's cached
 * last fix without streaming. Streaming stops on its own [IDLE_STOP_MS]
 * after the last consumer asked for it, so the phone isn't drained.
 */
object PhoneGps {

    private const val TAG = "PhoneGps"
    private const val IDLE_STOP_MS = 3 * 60_000L
    private const val INTERACT_PROVIDER = "content://com.ffalconxr.mercury.launcher.InteractProvider"

    @SuppressLint("StaticFieldLeak")
    private var launcher: Launcher? = null
    @Volatile private var streaming = false
    @Volatile private var lastRequestedMs = 0L
    @Volatile var latestFix: LocationSource.Fix? = null; private set
    /** Last status from the launcher: 0 phone connected, 1 not connected, 2 registered, 3 push timeout. */
    @Volatile var lastStatus: Int = -1; private set
    @Volatile var lastStatusMessage: String? = null; private set
    private val waiters = CopyOnWriteArrayList<(LocationSource.Fix) -> Unit>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleStop = Runnable { stopStreaming() }

    private val responseListener = Launcher.OnResponseListener { r -> onResponse(r) }
    private val connectionListener = Launcher.OnConnectionStateListener { st ->
        Log.i(TAG, "launcher IPC $st")
        if (st == Launcher.ConnectionState.CONNECTED && streaming) launcher?.let { runCatching { GPSIPCHelper.registerGPSInfo(appContext!!) } }
    }
    @SuppressLint("StaticFieldLeak") private var appContext: Context? = null

    /** BLE link to the phone, as the launcher reports it (Settings.Global "ble_connect_state"). */
    fun isPhoneConnected(context: Context): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, "ble_connect_state", 0) != 0 }.getOrDefault(false)

    fun latest(maxAgeMs: Long): LocationSource.Fix? =
        latestFix?.takeIf { SystemClock.elapsedRealtime() - it.atMs < maxAgeMs }

    /** Begin (or keep) the phone GPS stream; idempotent. */
    @Synchronized
    fun ensureStreaming(context: Context) {
        val app = context.applicationContext
        appContext = app
        lastRequestedMs = SystemClock.elapsedRealtime()
        main.removeCallbacks(idleStop); main.postDelayed(idleStop, IDLE_STOP_MS)
        if (streaming) return
        streaming = true
        runCatching {
            val l = launcher ?: Launcher.getInstance(app).also {
                launcher = it
                it.addOnResponseListener(responseListener)
                it.addOnConnectionState(connectionListener)
            }
            if (!l.isReady) l.connect()
            GPSIPCHelper.registerGPSInfo(app)   // waits for the binder itself, then sends start_location_stream_pushing
            Log.i(TAG, "phone GPS stream requested (phone connected=${isPhoneConnected(app)})")
        }.onFailure { Log.w(TAG, "start stream: ${it.message}"); streaming = false }
    }

    @Synchronized
    fun stopStreaming() {
        if (!streaming) return
        streaming = false
        appContext?.let { ctx -> runCatching { GPSIPCHelper.unRegisterGPSInfo(ctx) } }
        Log.i(TAG, "phone GPS stream released")
    }

    /** Start streaming and wait up to [timeoutMs] for a fresh push (or return the cached one). */
    suspend fun awaitFix(context: Context, timeoutMs: Long, maxAgeMs: Long): LocationSource.Fix? {
        latest(maxAgeMs)?.let { return it }
        ensureStreaming(context)
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val w: (LocationSource.Fix) -> Unit = { f -> if (cont.isActive) cont.resume(f) }
                waiters += w
                cont.invokeOnCancellation { waiters.remove(w) }
            }
        }
    }

    /** The launcher's cached last phone fix (no streaming), if it is younger than [maxAgeMs]. */
    fun oneShot(context: Context, maxAgeMs: Long): LocationSource.Fix? = runCatching {
        val ret = context.contentResolver.call(Uri.parse(INTERACT_PROVIDER), "requestLocationOneShot", null,
            Bundle().apply { putLong("availableDurationInMills", maxAgeMs) }) ?: return null
        val json = ret.getString("location")?.takeIf { it.isNotBlank() } ?: return null
        parseFix(JSONObject(json))?.also { latestFix = it; Log.i(TAG, "one-shot phone fix acc=${it.accuracyM.toInt()}m") }
    }.onFailure { Log.d(TAG, "one-shot: ${it.message}") }.getOrNull()

    private fun onResponse(r: Response) {
        val data = r.data ?: return
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return
        if (o.has("mLatitude") && o.has("mLongitude")) {
            val fix = parseFix(o) ?: return
            latestFix = fix
            Log.d(TAG, "phone fix ${fix.latLon()} acc=${fix.accuracyM.toInt()}m")
            val ws = waiters.toList(); waiters.clear(); ws.forEach { runCatching { it(fix) } }
            return
        }
        if (o.optString("datatype") == "gps_push") {
            lastStatus = o.optInt("resultCode", -1); lastStatusMessage = o.optString("resultMessage").takeIf { it.isNotBlank() }
            Log.i(TAG, "gps_push status $lastStatus ${lastStatusMessage.orEmpty()}")
        }
    }

    private fun parseFix(o: JSONObject): LocationSource.Fix? {
        val lat = o.optDouble("mLatitude", Double.NaN); val lon = o.optDouble("mLongitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) return null
        val acc = o.optDouble("mHorizontalAccuracyMeters", 20.0).toFloat().coerceAtLeast(1f)
        // Age the fix by the phone's own timestamp when present.
        val ageMs = o.optLong("mTime", 0L).takeIf { it > 0 }?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: 0L
        // Course only while actually moving: a standing phone reports 0 speed and a stale or zero bearing.
        val speed = o.optDouble("mSpeed", Double.NaN).toFloat().takeIf { !it.isNaN() && it >= 0f }
        val bearing = o.optDouble("mBearing", Double.NaN).toFloat().takeIf { !it.isNaN() && speed != null && speed > 0.5f && (o.optBoolean("mHasBearing", true)) }
        return LocationSource.Fix(lat, lon, acc, "phone", SystemClock.elapsedRealtime() - ageMs.coerceAtMost(10 * 60_000L), speed, bearing)
    }

    /** Words for the user when no phone fix arrives. */
    fun whyNot(context: Context): String = when {
        !isPhoneConnected(context) -> "your phone isn't connected to the glasses — open the RayNeo app and connect"
        lastStatus == 1 -> "the RayNeo app says the phone isn't connected"
        lastStatus == 3 -> "the phone stopped sending its position (check that the RayNeo app has location set to Always)"
        else -> "the phone hasn't sent a position yet (give the RayNeo app location permission 'Always')"
    }
}
