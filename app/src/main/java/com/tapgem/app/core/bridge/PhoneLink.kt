package com.tapgem.app.core.bridge

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The paired phone's cellular state, borrowed over Bluetooth.
 *
 * The X3 Pro has no radio of its own — no SIM, and `pm list features` reports no telephony at all —
 * so the status strip can only ever say "Wi-Fi" about itself. The phone it is tethered to does have
 * one, and as the hands-free unit of a Bluetooth HFP link the glasses are already told the carrier,
 * the signal strength and whether there is service; the stack keeps them in `HeadsetClientService`.
 * This picks that up so the strip can show the signal actually carrying your data when Wi-Fi is off.
 *
 * `BluetoothHeadsetClient` is a system API, so the constants are spelled out here and the profile
 * proxy is only attempted, never relied on: if the platform refuses, the broadcasts still arrive,
 * and if those are withheld too the caller falls back to the plain network label.
 */
object PhoneLink {
    private const val TAG = "PhoneLink"

    // android.bluetooth.BluetoothHeadsetClient — @SystemApi, so the strings are inlined.
    private const val ACTION_AG_EVENT = "android.bluetooth.headsetclient.profile.action.AG_EVENT"
    private const val ACTION_CONN_STATE = "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"
    private const val EXTRA_NETWORK_STATUS = "android.bluetooth.headsetclient.extra.NETWORK_STATUS"
    private const val EXTRA_SIGNAL = "android.bluetooth.headsetclient.extra.NETWORK_SIGNAL_STRENGTH"
    private const val EXTRA_OPERATOR = "android.bluetooth.headsetclient.extra.OPERATOR_NAME"
    private const val EXTRA_ROAMING = "android.bluetooth.headsetclient.extra.NETWORK_ROAMING"
    private const val PROFILE_HEADSET_CLIENT = 16

    /** 0..5 as HFP reports it, or null when the phone has not said. */
    @Volatile var signal: Int? = null; private set
    /** True once the phone reports it is registered on a network. */
    @Volatile var hasService: Boolean = false; private set
    @Volatile var operator: String? = null; private set
    @Volatile var roaming: Boolean = false; private set
    @Volatile var connected: Boolean = false; private set

    /** Anything at all worth showing instead of the bare network label. */
    val usable: Boolean get() = connected && (signal != null || !operator.isNullOrBlank())

    private var onChange: (() -> Unit)? = null
    private var receiver: BroadcastReceiver? = null

    fun start(context: Context, onChange: () -> Unit) {
        if (receiver != null) return
        this.onChange = onChange
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                i ?: return
                when (i.action) {
                    ACTION_AG_EVENT -> {
                        if (i.hasExtra(EXTRA_SIGNAL)) signal = i.getIntExtra(EXTRA_SIGNAL, -1).takeIf { it >= 0 }
                        if (i.hasExtra(EXTRA_NETWORK_STATUS)) hasService = i.getIntExtra(EXTRA_NETWORK_STATUS, 0) != 0
                        if (i.hasExtra(EXTRA_ROAMING)) roaming = i.getIntExtra(EXTRA_ROAMING, 0) != 0
                        if (i.hasExtra(EXTRA_OPERATOR)) operator = i.getStringExtra(EXTRA_OPERATOR)?.trim()?.takeIf { it.isNotBlank() }
                        connected = true
                    }
                    ACTION_CONN_STATE -> {
                        // 2 == BluetoothProfile.STATE_CONNECTED
                        connected = i.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, 0) == 2 ||
                            i.getIntExtra("android.bluetooth.profile.extra.STATE", 0) == 2
                        if (!connected) { signal = null; operator = null; hasService = false }
                    }
                    else -> return
                }
                this@PhoneLink.onChange?.invoke()
            }
        }
        val f = IntentFilter().apply { addAction(ACTION_AG_EVENT); addAction(ACTION_CONN_STATE) }
        runCatching { ContextCompat.registerReceiver(context, r, f, ContextCompat.RECEIVER_EXPORTED); receiver = r }
            .onFailure { Log.i(TAG, "HFP broadcasts unavailable: ${it.message}") }
        primeFromProxy(context)
    }

    /**
     * Broadcasts only fire when something changes, so a phone that has been sitting on five bars
     * since before launch would never announce itself. Ask the profile for its current state once;
     * a system-API refusal here is expected and simply leaves the strip on its fallback.
     */
    private fun primeFromProxy(context: Context) {
        runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            adapter.getProfileProxy(context, object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                    runCatching {
                        val devices = proxy.connectedDevices
                        connected = devices.isNotEmpty()
                        if (connected) onChange?.invoke()
                    }
                    runCatching { adapter.closeProfileProxy(profile, proxy) }
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, PROFILE_HEADSET_CLIENT)
        }.onFailure { Log.i(TAG, "headset-client proxy refused: ${it.message}") }
    }

    fun stop(context: Context) {
        receiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
        receiver = null; onChange = null
    }

    /** "Mint ▚▚▚▚▁" style: carrier plus a five-step bar, or null when there is nothing to say. */
    fun label(): String? {
        if (!usable) return null
        val s = signal
        val bars = if (s == null) "" else buildString { repeat(5) { append(if (it < s) '▮' else '▯') } }
        val name = operator?.take(10)
        return when {
            !hasService && s == null -> name?.let { "$it ·" } ?: "No service"
            name != null && bars.isNotEmpty() -> "$name $bars" + if (roaming) " R" else ""
            bars.isNotEmpty() -> bars + if (roaming) " R" else ""
            else -> name
        }
    }
}
