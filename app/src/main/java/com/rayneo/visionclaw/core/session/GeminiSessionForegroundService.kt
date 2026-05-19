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
 * Phase 2 Step 2f.2 — minimal foreground Service that keeps the
 * Gemini Live audio pipeline alive when visionclaw is in the
 * background.
 *
 * Why we need this: Android 11+ blocks `AudioRecord` capture from
 * background processes. After the Step 2f launcher swap, tapbrowser
 * is the user-visible Activity and visionclaw runs warm-started
 * but invisible. Without a foreground Service the OS treats
 * visionclaw as background and quietly tears down the microphone
 * — Gemini stops responding even though the WebSocket is still
 * connected, because no audio frames are reaching it.
 *
 * Design: this Service does NOT own the audio pipeline. The
 * existing `AudioRecord`, `geminiLiveSession` WebSocket, and
 * `AudioTrack` playback stay in `MainActivity` exactly where they
 * are. The Service exists purely to attach
 * `FOREGROUND_SERVICE_TYPE_MICROPHONE` to the process so the OS
 * keeps letting the Activity-owned `AudioRecord` capture frames.
 * Visionclaw is responsible for starting the Service when a
 * Gemini Live session begins and stopping it when the session
 * ends.
 *
 * That keeps the diff small and the risk surface narrow. A future
 * iteration can move the actual audio pipeline ownership into the
 * Service if we discover the Activity-side lifecycle is fragile.
 *
 * Notification: required by Android for any FGS. We use a low-
 * priority channel so it doesn't make sound or vibrate, and a
 * concise label that explains the live mic to the user.
 */
class GeminiSessionForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel(this)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
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
                // Android 10+: explicit foregroundServiceType is required.
                // ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE is the
                // declared type in the manifest; pass it here too so the
                // platform validates the call.
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "startForeground OK")
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}", e)
        }

        // START_NOT_STICKY: if the system kills us under memory pressure,
        // do NOT auto-restart. visionclaw is the lifecycle owner; it
        // will re-call start() when it activates Gemini Live again.
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
        // Arbitrary stable ID so repeat start calls collapse onto the
        // same notification slot.
        private const val NOTIFICATION_ID = 0x76_3F_45

        /**
         * Start the foreground service. Idempotent — repeat calls are
         * safe (the system collapses duplicate startForeground calls
         * once the notification is already up). Call this whenever
         * a Gemini Live session begins so the OS keeps the mic open
         * across the chat→browser swap.
         */
        fun start(context: Context) {
            ensureNotificationChannel(context)
            val intent = Intent(context, GeminiSessionForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.message}", e)
            }
        }

        /**
         * Stop the foreground service. Call this when the Gemini Live
         * session ends (shutdownMultimodalSession, deactivateVoice-
         * Assistant, etc.) so the OS reclaims the mic privilege and
         * the user's notification shade clears up.
         */
        fun stop(context: Context) {
            val intent = Intent(context, GeminiSessionForegroundService::class.java)
            try {
                context.stopService(intent)
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
