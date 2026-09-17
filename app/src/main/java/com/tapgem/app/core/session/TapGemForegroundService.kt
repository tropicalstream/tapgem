package com.tapgem.app.core.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tapgem.app.core.bridge.HudStateBridge
import com.tapgem.app.core.bridge.VoiceServiceApi

/**
 * Foreground Service hosting the voice pipeline. Promoted to foreground
 * BEFORE the AudioRecord opens (Android 11+ revokes the mic otherwise) and
 * demoted whenever a session ends — including ones the pipeline ends on its
 * own (silence watchdog, server close, errors).
 */
class TapGemForegroundService : Service() {

    private inner class LocalBinder : Binder(), VoiceServiceApi {
        override fun activateVoice() = this@TapGemForegroundService.activateVoice()
        override fun shutdownVoice() = this@TapGemForegroundService.shutdownVoice()
        override fun currentState(): HudStateBridge.State = HudStateBridge.current()
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var foregroundActive = false
    private val pipeline: GeminiVoicePipeline by lazy {
        GeminiVoicePipeline(this).also { p -> p.onSessionEnded = { main.post { demote() } } }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundIfNeeded()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { pipeline.release() }
        demote()
        super.onDestroy()
    }

    private fun activateVoice() {
        startForegroundIfNeeded()
        pipeline.activate()
    }

    private fun shutdownVoice() {
        pipeline.shutdown(reason = null)
        demote()
    }

    private fun demote() {
        if (!foregroundActive) return
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        foregroundActive = false
    }

    private fun startForegroundIfNeeded() {
        if (foregroundActive) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "TapGem voice session", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); enableVibration(false)
            })
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("TapGem")
            .setContentText("Assistant listening")
            .setOngoing(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                @Suppress("DEPRECATION") startForeground(NOTIFICATION_ID, notification)
            }
            foregroundActive = true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "TapGemFgs"
        private const val CHANNEL_ID = "tapgem_voice_session"
        private const val NOTIFICATION_ID = 0x74_61_70
    }
}
