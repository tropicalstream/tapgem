package com.tapgem.app.core.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Which way the wearer is looking, in degrees from true north.
 *
 * The X3's fused rotation vector (QTI, magnetometer-referenced) gives the
 * device's attitude; the glasses' optical axis is the device −Z (the screen
 * normal points back at the eyes, gravity lies along +Y when worn upright), so
 * the heading is the bearing of that axis projected on the ground. Looking
 * steeply up or down leaves the projection meaningless, so the last level
 * heading is held. Magnetic north is corrected to true north with the
 * declination at the current fix. Listeners get a smoothed value at ~5 Hz and
 * only when it moved.
 *
 * Reference-counted: [acquire] while a heading-up minimap is showing,
 * [release] when it closes; the sensor runs only in between.
 */
object HeadingSource : SensorEventListener {

    private const val TAG = "HeadingSource"
    private const val PUSH_MS = 200L
    private const val MIN_DELTA_DEG = 1.5f
    private const val STEEP_SIN = 0.87f          // |sin(pitch)| above this (~60°): hold the last heading

    class Heading(val deg: Float, val reliable: Boolean, val atMs: Long)

    fun interface Listener { fun onHeading(h: Heading) }

    private var sm: SensorManager? = null
    private var sensor: Sensor? = null
    private var refs = 0
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val rot = FloatArray(9)
    private var smoothX = 0.0; private var smoothY = 0.0   // unit-vector low-pass of the heading
    private var haveSmooth = false
    private var lastPushedDeg = Float.NaN
    private var lastPushMs = 0L
    private var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM   // until the sensor says otherwise
    @Volatile private var declinationDeg = 0f
    @Volatile var latest: Heading? = null; private set
    /** Set to 180 if the optical axis turns out to be +Z on some firmware; kept for a one-line fix. */
    @Volatile var axisOffsetDeg = 0f

    val available: Boolean get() = sensor != null

    fun acquire(context: Context, listener: Listener) {
        listeners.addIfAbsent(listener)
        if (refs++ > 0) return
        val m = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sm = m
        sensor = m.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: m.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        val s = sensor ?: run { Log.w(TAG, "no rotation vector sensor"); return }
        haveSmooth = false; lastPushedDeg = Float.NaN
        m.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
        Log.i(TAG, "listening to ${s.name}")
    }

    fun release(listener: Listener) {
        listeners.remove(listener)
        if (refs == 0) return
        if (--refs > 0) return
        runCatching { sm?.unregisterListener(this) }
        sensor = null
        Log.i(TAG, "stopped")
    }

    /** True north needs the declination where the wearer is; call with each position fix. */
    fun updateDeclination(lat: Double, lon: Double) {
        declinationDeg = runCatching { GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, System.currentTimeMillis()).declination }.getOrDefault(0f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { this.accuracy = accuracy }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR && event.sensor.type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        // World components of the device −Z axis: column 3 of R, negated (x east, y north, z up).
        val fx = -rot[2]; val fy = -rot[5]; val fz = -rot[8]
        if (abs(fz) > STEEP_SIN) return                     // looking at the floor / the sky: hold
        val horiz = Math.hypot(fx.toDouble(), fy.toDouble()).takeIf { it > 1e-3 } ?: return
        val magnetic = Math.toDegrees(atan2(fx / horiz, fy / horiz))
        val trueDeg = norm(magnetic + declinationDeg + axisOffsetDeg)
        // Smooth on the unit circle so 359° → 1° doesn't swing through 180°.
        val r = Math.toRadians(trueDeg); val a = if (haveSmooth) 0.35 else 1.0
        smoothX = smoothX * (1 - a) + cos(r) * a; smoothY = smoothY * (1 - a) + sin(r) * a; haveSmooth = true
        val deg = norm(Math.toDegrees(atan2(smoothY, smoothX))).toFloat()
        val now = SystemClock.elapsedRealtime()
        // values[4] is the fused estimate's own heading error (radians; -1 = unknown).
        val err = event.values.getOrNull(4) ?: -1f
        val reliable = accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE && (err < 0f || err < 0.5f)
        latest = Heading(deg, reliable, now)
        if (now - lastPushMs < PUSH_MS) return
        if (!lastPushedDeg.isNaN() && angleDiff(deg, lastPushedDeg) < MIN_DELTA_DEG) return
        lastPushMs = now; lastPushedDeg = deg
        val h = latest ?: return
        main.post { listeners.forEach { runCatching { it.onHeading(h) } } }
    }

    private fun norm(d: Double): Double { var x = d % 360.0; if (x < 0) x += 360.0; return x }
    fun angleDiff(a: Float, b: Float): Float { val d = abs((a - b + 540f) % 360f - 180f); return d }
}
