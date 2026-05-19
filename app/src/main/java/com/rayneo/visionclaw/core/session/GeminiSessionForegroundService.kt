package com.rayneo.visionclaw.core.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Unipanel v2 — minimal foreground Service that keeps the Gemini
 * Live audio pipeline alive when visionclaw is in the background.
 *
 * Android 11+ blocks AudioRecord capture from background processes.
 * After the launcher swap (tapbrowser is the launcher) visionclaw
 * lives backgrounded; without a microphone-typed FGS the OS quietly
 * tears down the mic — Gemini stops responding even though the
 * WebSocket is connected, because no audio frames reach it.
 *
 * Design: this Service does NOT own the audio pipeline. The
 * existing AudioRecord / WebSocket / AudioTrack stay in visionclaw
 * MainActivity. The Service exists purely to attach
 * FOREGROUND_SERVICE_TYPE_MICROPHONE to the process so the Activity-
 * owned AudioRecord keeps working. Visionclaw starts the Service
 * when a Gemini Live session begins and stops it when the session
 * ends.
 *
 * Direct call from activateChatVoiceAssistant() / shutdown-
 * MultimodalSession() — NOT via voiceAssistantActive LiveData.
 * LiveData observers are lifecycle-aware and don't fire when
 * visionclaw is STOPPED (which it is during the warm-start),
 * so a LiveData hop misses the start.
 */
class GeminiSessionForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel(this)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("TapInsight assistant")
            .setContentText("Voice session active")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "startForeground OK — mic privilege attached")
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}", e)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GeminiFgs"
        private const val CHANNEL_ID = "tapinsight_voice_session"
        private const val NOTIFICATION_ID = 0x76_3F_45

        /** Start the FGS. Idempotent. Visionclaw calls this when a
         *  Gemini Live session begins; AudioRecord opens AFTER this
         *  call so the mic privilege is in place. */
        fun start(context: Context) {
            ensureNotificationChannel(context)
            val intent = Intent(context, GeminiSessionForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "start() dispatched")
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.message}", e)
            }
        }

        /** Stop the FGS. Called from shutdownMultimodalSession or
         *  any other path that drops the Gemini Live session. */
        fun stop(context: Context) {
            val intent = Intent(context, GeminiSessionForegroundService::class.java)
            try {
                context.stopService(intent)
                Log.d(TAG, "stop() dispatched")
            } catch (e: Exception) {
                Log.w(TAG, "stop failed: ${e.message}", e)
            }
        }

        private fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TapInsight voice session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while the Gemini voice assistant is listening."
                setShowBadge(false)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
