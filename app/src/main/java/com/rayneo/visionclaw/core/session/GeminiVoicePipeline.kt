package com.rayneo.visionclaw.core.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.TapLink.app.unipanel.HudStateBridge
import com.rayneo.visionclaw.VisionClawApp
import com.rayneo.visionclaw.core.audio.GeminiAudioPlayer
import com.rayneo.visionclaw.core.network.GeminiRouter
import com.rayneo.visionclaw.core.tools.BrowserVisionTool
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Unipanel v2 Phase 4 — the real Gemini Live voice pipeline, extracted
 * out of visionclaw MainActivity so it runs inside the bound voice
 * Service instead of an Activity.
 *
 * Scope of this Phase 4a: AUDIO-ONLY voice chat.
 *   - Opens AudioRecord on the user's mic.
 *   - Connects to Gemini Live via the existing
 *     [GeminiRouter.startLiveAudioSession] entry point.
 *   - Streams PCM-16 frames to Gemini.
 *   - Receives model audio + writes it to [GeminiAudioPlayer].
 *   - Writes user transcripts through
 *     [com.rayneo.visionclaw.ui.MainViewModel.appendUserUtterance] (via
 *     `onInputTranscription` accumulation) and assistant transcripts
 *     through `appendLiveAssistantStreamChunk` /
 *     `commitLiveAssistantStreamIfNeeded` so the existing ChatCardBridge
 *     publisher mirrors them into the unipanel overlay mini-cards.
 *   - Publishes voice phase / oscilloscope level / connection status /
 *     transient HUD notifications through [HudStateBridge] so tapbrowser
 *     can render them in the overlay.
 *
 * **Deferred** (NOT in Phase 4a):
 *   - Camera frames to Gemini Live (multimodal video).
 *   - Tool calls — browser_vision, calendar, etc. need the full
 *     ToolDispatcher / ToolAssistEngine wiring from MainActivity. For
 *     now we send a no-op tool response so Gemini doesn't hang.
 *   - Audio effects (AEC / NS / AGC). The unipanel build is on RayNeo
 *     glasses where the OS audio source already does most of this.
 *   - Barge-in detection / idle watchdog.
 *
 * Lifecycle: instances are created by [GeminiSessionForegroundService]
 * in onCreate and torn down in onDestroy. [activate] / [shutdown] are
 * idempotent and safe to call from the Service's binder thread.
 *
 * Threading: Service binder calls land on the binder thread; we hop
 * to [scope] (Dispatchers.IO) for the WebSocket connect because the
 * existing GeminiRouter does synchronous setup work. The AudioRecord
 * read loop runs on a dedicated [Thread] tagged THREAD_PRIORITY_AUDIO.
 */
class GeminiVoicePipeline(context: Context) {

    /** Application context — holding a reference is safe because we
     *  never finish() the app. We avoid holding the Service context
     *  to dodge configuration-change pitfalls. */
    private val appContext: Context = context.applicationContext

    /** Shared MainViewModel, populated by VisionClawApp lazy. Reads
     *  geminiRouter from here so the pipeline never constructs its
     *  own client — auth, endpoint, and config stay in one place. */
    private val viewModel by lazy {
        (appContext as VisionClawApp).viewModel
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null

    @Volatile private var liveSession: GeminiRouter.LiveSessionHandle? = null
    @Volatile private var liveSessionReady: Boolean = false
    @Volatile private var captureActive: Boolean = false
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioThread: Thread? = null

    /** Audio playback for Gemini's model audio responses. Lazy so we
     *  only allocate the AudioTrack when voice actually activates. */
    private val audioPlayer: GeminiAudioPlayer by lazy { GeminiAudioPlayer(appContext) }

    /** Phase 4b — browser_vision tool. Captures the WebView frame via
     *  the cross-module [com.TapLink.app.media.BrowserFrameHolder]
     *  (publisher: tapbrowser MainActivity) and asks Gemini's REST
     *  vision endpoint about it. Lazy so we don't pay the OkHttp init
     *  cost until Gemini actually calls the tool. */
    private val browserVisionTool: BrowserVisionTool by lazy {
        BrowserVisionTool(
            context = appContext,
            frameProvider = { com.TapLink.app.media.BrowserFrameHolder.captureBase64Jpeg() }
        )
    }

    /**
     * Begin a voice session. Connects the WebSocket; AudioRecord opens
     * after [GeminiRouter.LiveSessionListener.onSessionReady] fires so
     * we don't start streaming bytes into a not-yet-ready socket.
     *
     * Idempotent: a second call while already active is a no-op.
     */
    fun activate() {
        if (captureActive || liveSession != null || connectJob?.isActive == true) {
            Log.d(TAG, "activate(): already in progress / active, skipping")
            return
        }

        // Permission gate. The Service is microphone-typed but
        // RECORD_AUDIO is a separate runtime permission; if the user
        // never granted it (Phase 2 visionclaw onCreate handles the
        // grant request) the AudioRecord constructor would crash with
        // SecurityException.
        if (ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "activate(): RECORD_AUDIO not granted")
            HudStateBridge.update {
                it.copy(
                    phase = HudStateBridge.VoicePhase.IDLE,
                    connection = HudStateBridge.ConnectionStatus.ERROR,
                    notification = "Microphone permission needed."
                )
            }
            return
        }

        Log.i(TAG, "activate(): starting voice session")

        // Reset the live assistant stream buffer so a new turn doesn't
        // accidentally concatenate with the previous one.
        runCatching { viewModel.resetLiveAssistantStream() }
        runCatching { viewModel.activateVoiceAssistant() }

        HudStateBridge.update {
            it.copy(
                phase = HudStateBridge.VoicePhase.LISTENING,
                connection = HudStateBridge.ConnectionStatus.CONNECTING,
                transcript = "Connecting…",
                notification = null,
                oscilloscopeLevel = 0f
            )
        }

        val listener = createListener()
        connectJob = scope.launch {
            val handle = runCatching {
                viewModel.geminiRouter.startLiveAudioSession(listener)
            }.getOrNull()

            if (handle == null) {
                Log.w(TAG, "startLiveAudioSession returned null")
                HudStateBridge.update {
                    it.copy(
                        phase = HudStateBridge.VoicePhase.IDLE,
                        connection = HudStateBridge.ConnectionStatus.ERROR,
                        notification = "Could not connect to Gemini Live."
                    )
                }
                runCatching { viewModel.deactivateVoiceAssistant() }
                return@launch
            }

            liveSession = handle
            Log.i(TAG, "Live session handle acquired; awaiting onSessionReady")
        }
    }

    /**
     * End the current voice session. Tears down AudioRecord, closes
     * the WebSocket, stops AudioTrack playback, and publishes IDLE
     * state. Idempotent; safe to call from any thread.
     *
     * @param reason optional one-line message surfaced in the HUD.
     */
    fun shutdown(reason: String? = null) {
        if (!captureActive && liveSession == null && connectJob?.isActive != true) {
            Log.d(TAG, "shutdown(): already idle, skipping")
            return
        }
        Log.i(TAG, "shutdown(reason=$reason)")

        captureActive = false
        val thread = audioThread
        audioThread = null
        runCatching { thread?.interrupt() }

        val rec = audioRecord
        audioRecord = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }

        val session = liveSession
        liveSession = null
        liveSessionReady = false
        runCatching { session?.close() }

        connectJob?.cancel()
        connectJob = null

        runCatching { audioPlayer.stopAndFlush() }

        // Commit any pending assistant stream chunk so it appears as a
        // mini-card rather than vanishing on shutdown mid-response.
        runCatching { viewModel.commitLiveAssistantStreamIfNeeded() }
        runCatching { viewModel.resetLiveAssistantStream() }
        runCatching { viewModel.deactivateVoiceAssistant() }

        HudStateBridge.update {
            it.copy(
                phase = HudStateBridge.VoicePhase.IDLE,
                connection = HudStateBridge.ConnectionStatus.IDLE,
                transcript = null,
                oscilloscopeLevel = 0f,
                notification = reason
            )
        }
    }

    /** Release everything. Called from Service.onDestroy. */
    fun release() {
        shutdown(reason = null)
        runCatching { audioPlayer.release() }
    }

    // ────────────────────────────────────────────────────────────────
    // Internals
    // ────────────────────────────────────────────────────────────────

    private fun createListener(): GeminiRouter.LiveSessionListener {
        return object : GeminiRouter.LiveSessionListener {
            override fun onSessionReady() {
                Log.i(TAG, "onSessionReady")
                liveSessionReady = true
                HudStateBridge.update {
                    it.copy(
                        connection = HudStateBridge.ConnectionStatus.GEMINI_CONNECTED,
                        transcript = "Listening…",
                        notification = null
                    )
                }
                startAudioStreaming()
            }

            override fun onInputTranscription(text: String) {
                if (text.isBlank()) return
                // Live partial transcript — surface in HUD so the user
                // sees what Gemini heard. Final commit to viewModel
                // happens on turnComplete to avoid mid-utterance noise.
                HudStateBridge.update { it.copy(transcript = text) }
            }

            override fun onOutputTranscription(text: String) {
                if (text.isBlank()) return
                runCatching { viewModel.appendLiveAssistantStreamChunk(text) }
                HudStateBridge.update {
                    it.copy(phase = HudStateBridge.VoicePhase.THINKING)
                }
            }

            override fun onModelText(text: String) {
                // Audio responses come through onOutputTranscription instead.
                // onModelText fires for text-only responses, which we don't
                // request here.
            }

            override fun onModelAudio(mimeType: String, data: ByteArray) {
                if (data.isEmpty()) return
                runCatching {
                    audioPlayer.playChunk(mimeType, data, muted = false, volume = 1f)
                }
            }

            override fun onToolCall(callId: String, name: String, args: String) {
                Log.i(TAG, "onToolCall: callId=$callId name=$name args=${args.take(160)}")
                when (name) {
                    "browser_vision" -> dispatchBrowserVision(callId, name, args)
                    else -> {
                        // Phase 4b ships browser_vision only. Other tools
                        // (calendar, places, maps, news, etc.) need the
                        // full ToolDispatcher / ToolAssistEngine wiring
                        // from visionclaw MainActivity. Reply with a
                        // human-friendly stub so Gemini doesn't hang
                        // waiting for a result.
                        Log.w(TAG, "onToolCall: $name not yet wired in Service")
                        runCatching {
                            liveSession?.sendToolResponse(
                                callId, name,
                                "Tool '$name' isn't available in the unipanel build yet."
                            )
                        }
                    }
                }
            }

            override fun onTurnComplete(finishReason: String?) {
                Log.d(TAG, "onTurnComplete: finishReason=$finishReason")
                runCatching { viewModel.commitLiveAssistantStreamIfNeeded() }
                runCatching { viewModel.resetLiveAssistantStream() }
                runCatching { audioPlayer.notifyTurnComplete() }
                if (liveSessionReady) {
                    HudStateBridge.update {
                        it.copy(
                            phase = HudStateBridge.VoicePhase.LISTENING,
                            transcript = "Listening…"
                        )
                    }
                }
            }

            override fun onError(message: String) {
                Log.w(TAG, "onError: $message")
                shutdown(reason = "Voice error: $message")
            }

            override fun onClosed(code: Int, reason: String) {
                Log.i(TAG, "onClosed: code=$code reason=$reason")
                // Only invoke shutdown if we initiated; the close path is
                // idempotent so calling it from a remote-close event is
                // safe — it just resets HUD state.
                shutdown(reason = if (code == 1000) null else "Voice session closed.")
            }

            override fun onGroundingMetadata(chunks: List<GeminiRouter.GroundingChunk>) {
                // No-op for the audio-only path; URL grounding only matters
                // for tool-call-driven research turns which are Phase 4b.
            }
        }
    }

    /**
     * Phase 4b — run browser_vision off the listener thread. Captures
     * the WebView frame, asks Gemini's REST vision endpoint about it,
     * then returns the answer through [GeminiRouter.LiveSessionHandle
     * .sendToolResponse] so the Live model can speak it.
     *
     * Args from Gemini come as a JSON string. We accept "question",
     * "query", or "prompt" — same aliases the tool's own [BrowserVision
     * Tool.execute] accepts — and fall back to a generic describe-this
     * prompt if none of them landed (which can happen if Gemini calls
     * the tool with no parameters; we don't want to error out).
     */
    private fun dispatchBrowserVision(callId: String, name: String, args: String) {
        scope.launch {
            val question = parseQuestionArg(args)
            HudStateBridge.update {
                it.copy(notification = "Looking at the screen…")
            }
            val result = runCatching {
                browserVisionTool.execute(mapOf("question" to question))
            }
            val responseText = result.getOrNull()?.getOrElse { err ->
                Log.w(TAG, "browser_vision failed: ${err.message}")
                "I couldn't read the screen: ${err.message ?: "unknown error"}"
            } ?: run {
                val ex = result.exceptionOrNull()
                Log.w(TAG, "browser_vision threw: ${ex?.message}")
                "I couldn't read the screen: ${ex?.message ?: "unknown error"}"
            }
            Log.d(TAG, "browser_vision response: ${responseText.take(200)}")
            HudStateBridge.update { it.copy(notification = null) }
            runCatching {
                liveSession?.sendToolResponse(callId, name, responseText)
            }
        }
    }

    private fun parseQuestionArg(args: String): String {
        val parsed = runCatching { JSONObject(args) }.getOrNull() ?: return DEFAULT_VISION_QUESTION
        val q = parsed.optString("question").trim()
            .ifBlank { parsed.optString("query").trim() }
            .ifBlank { parsed.optString("prompt").trim() }
        return q.ifBlank { DEFAULT_VISION_QUESTION }
    }

    private fun startAudioStreaming() {
        if (captureActive) return
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "startAudioStreaming: minBufferSize=$minBuffer")
            HudStateBridge.update {
                it.copy(notification = "Microphone buffer could not be created.")
            }
            return
        }
        val bufferSize = maxOf(minBuffer * 2, 4096)
        val recorder = createAudioRecord(bufferSize)
        if (recorder == null) {
            Log.w(TAG, "startAudioStreaming: AudioRecord init failed")
            HudStateBridge.update {
                it.copy(notification = "Microphone could not be opened.")
            }
            return
        }

        audioRecord = recorder
        captureActive = true
        runCatching { recorder.startRecording() }

        audioThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val chunk = ByteArray(2048)
            var loggedFirstFrame = false
            while (captureActive) {
                val read = try {
                    recorder.read(chunk, 0, chunk.size)
                } catch (e: Throwable) {
                    Log.w(TAG, "audio read threw: ${e.message}")
                    break
                }
                if (read > 0) {
                    if (!loggedFirstFrame) {
                        loggedFirstFrame = true
                        Log.d(TAG, "First mic frame: $read bytes")
                    }
                    val peak = calculatePcm16Peak(chunk, read)
                    val norm = (peak / 32_767f).coerceIn(0f, 1f)
                    // Throttle HUD updates — the read loop is much faster
                    // than the overlay can usefully draw.
                    if (norm > 0.04f || (System.currentTimeMillis() % 8L == 0L)) {
                        HudStateBridge.update {
                            it.copy(
                                oscilloscopeLevel = norm,
                                oscilloscopeChannel = HudStateBridge.OscilloscopeChannel.USER
                            )
                        }
                    }
                    runCatching {
                        liveSession?.sendAudioChunkPcm16(chunk, read, SAMPLE_RATE_HZ)
                    }
                } else if (read < 0) {
                    Log.w(TAG, "audio read error code=$read")
                }
            }
            Log.d(TAG, "Audio thread exiting")
        }, "GeminiVoicePipelineAudioThread").apply {
            isDaemon = true
            start()
        }
    }

    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val rec = runCatching {
                AudioRecord(
                    source,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(
                    TAG,
                    "AudioRecord opened with source=" +
                        if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) "VOICE_COMM" else "MIC"
                )
                return rec
            }
            runCatching { rec.release() }
        }
        return null
    }

    private fun calculatePcm16Peak(data: ByteArray, size: Int): Int {
        var peak = 0
        var i = 0
        val limit = size - 1
        while (i < limit) {
            val lo = data[i].toInt() and 0xFF
            val hi = data[i + 1].toInt() shl 8
            val sample = (hi or lo).toShort().toInt()
            val abs = if (sample < 0) -sample else sample
            if (abs > peak) peak = abs
            i += 2
        }
        return peak
    }

    companion object {
        private const val TAG = "GeminiVoicePipe"
        private const val SAMPLE_RATE_HZ = 16_000
        private const val DEFAULT_VISION_QUESTION =
            "Describe what's currently on the screen in plain English."
    }
}
