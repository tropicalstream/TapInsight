package com.rayneo.visionclaw.core.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.TapLink.app.unipanel.HudStateBridge
import com.TapLink.app.unipanel.VoiceServiceApi

/**
 * Unipanel v2 — foreground Service that hosts the Gemini Live voice
 * pipeline. Phase 3: this is now a bindable Service with a
 * [VoiceServiceApi] surface; Phase 4 will move the AudioRecord +
 * WebSocket + AudioTrack ownership in from visionclaw MainActivity.
 *
 * Two ways to wake the Service:
 *
 *   1. [start] / [stop] static helpers — fire-and-forget FGS lifecycle.
 *      visionclaw MainActivity uses these from
 *      `activateChatVoiceAssistant` / `shutdownMultimodalSession`. In
 *      unipanel mode visionclaw isn't running, so this path is a no-op
 *      today, but the code stays for any path that re-enables the
 *      Activity (e.g. a settings shell that opens the chat panel
 *      explicitly).
 *
 *   2. `bindService` from tapbrowser. The Service's [LocalBinder] is
 *      cast to [VoiceServiceApi] in tapbrowser; calling
 *      [VoiceServiceApi.activateVoice] is the unipanel voice-activate
 *      entry. Today the stub publishes a HudStateBridge update so the
 *      bind path can be verified via logcat; Phase 4 wires it to the
 *      actual pipeline.
 *
 * Foreground-vs-bound semantics: `bindService` alone does NOT promote
 * the Service to foreground (no mic privilege yet). The Service goes
 * foreground when [activateVoice] internally calls
 * [startForegroundIfNeeded], which is where the FGS notification +
 * `FOREGROUND_SERVICE_TYPE_MICROPHONE` are attached. Shutdown reverses
 * both (`stopForeground` + drop the notification).
 */
class GeminiSessionForegroundService : Service() {

    /**
     * In-process Binder. Implements [VoiceServiceApi] so tapbrowser
     * can interact with the Service without depending on this
     * visionclaw class.
     */
    private inner class LocalBinder : Binder(), VoiceServiceApi {
        override fun activateVoice() = this@GeminiSessionForegroundService.activateVoice()
        override fun shutdownVoice() = this@GeminiSessionForegroundService.shutdownVoice()
        override fun currentState(): HudStateBridge.State = HudStateBridge.current()
    }

    private val binder = LocalBinder()

    /** True while the Service is in foreground (FGS notification attached). */
    @Volatile
    private var foregroundActive: Boolean = false

    /** The real Gemini Live voice pipeline. Phase 4 — runs AudioRecord
     *  + GeminiRouter.startLiveAudioSession + GeminiAudioPlayer entirely
     *  inside this Service, no Activity dependency. Lazy so we don't
     *  allocate the audio stack until voice actually activates. */
    private val pipeline: GeminiVoicePipeline by lazy { GeminiVoicePipeline(this) }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind — issuing LocalBinder")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Preserve the v2 behavior: when started via [start], promote
        // immediately so visionclaw's existing call sites still get the
        // mic privilege. Bound-only consumers won't hit this path.
        startForegroundIfNeeded()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        // Release the live pipeline FIRST so AudioRecord / WebSocket
        // / AudioTrack are all torn down cleanly before the process
        // event ends. The pipeline's release() is idempotent and
        // tolerates being called when it's already idle.
        runCatching { pipeline.release() }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        foregroundActive = false
        super.onDestroy()
    }

    // ────────────────────────────────────────────────────────────────
    // VoiceServiceApi implementation — Phase 3 stubs
    // ────────────────────────────────────────────────────────────────

    /**
     * Phase 4 — drive the real Gemini Live voice pipeline.
     *
     * Order matters:
     *   1. Promote to foreground BEFORE the AudioRecord opens. Android
     *      11+ revokes the mic the instant an FGS misses its foreground
     *      window, and the AudioRecord constructor would throw
     *      SecurityException without the privilege already attached.
     *   2. Then [GeminiVoicePipeline.activate] starts the WebSocket;
     *      AudioRecord opens after onSessionReady fires (see the
     *      pipeline's startAudioStreaming).
     */
    private fun activateVoice() {
        Log.i(TAG, "activateVoice()")
        startForegroundIfNeeded()
        pipeline.activate()
    }

    /**
     * Phase 4 — tear down the live voice session. The pipeline shuts
     * down AudioRecord + WebSocket + AudioTrack and publishes IDLE
     * state. After that we drop the FGS notification but keep the
     * Service alive so a subsequent activateVoice() can re-promote
     * without paying the bindService round-trip.
     */
    private fun shutdownVoice() {
        Log.i(TAG, "shutdownVoice()")
        pipeline.shutdown(reason = null)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        foregroundActive = false
    }

    // ────────────────────────────────────────────────────────────────
    // FGS promotion
    // ────────────────────────────────────────────────────────────────

    private fun startForegroundIfNeeded() {
        if (foregroundActive) return
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
            foregroundActive = true
            Log.d(TAG, "startForeground OK — mic privilege attached")
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "GeminiFgs"
        private const val CHANNEL_ID = "tapinsight_voice_session"
        private const val NOTIFICATION_ID = 0x76_3F_45

        /** Fully-qualified class name string used by tapbrowser's
         *  bindService call. Tapbrowser can't import the Service class
         *  (visionclaw → tapbrowser module dependency), so it passes
         *  this name via Intent.setClassName at runtime. */
        const val FQN: String =
            "com.rayneo.visionclaw.core.session.GeminiSessionForegroundService"

        /** Start the FGS. Idempotent. Visionclaw calls this from
         *  activateChatVoiceAssistant when the Activity is hosting voice
         *  itself (legacy path; not used in unipanel mode where the
         *  Activity isn't running). */
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

        /** Stop the FGS. Legacy companion to [start]. */
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
