package com.rayneo.visionclaw.core.session

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.TapLink.app.unipanel.HudStateBridge
import com.rayneo.visionclaw.VisionClawApp
import com.rayneo.visionclaw.core.assistant.AssistantIntentParser
import com.rayneo.visionclaw.core.audio.GeminiAudioPlayer
import com.rayneo.visionclaw.core.network.GeminiRouter
import com.rayneo.visionclaw.core.network.HermesClient
import com.rayneo.visionclaw.core.storage.AppPreferences
import com.rayneo.visionclaw.core.tools.BrowserVisionTool
import com.rayneo.visionclaw.core.tools.ToolDispatcher
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
    @Volatile private var latestInputTranscript: String = ""
    @Volatile private var latestCameraFrame: String? = null

    /** Audio playback for Gemini's model audio responses. Lazy so we
     *  only allocate the AudioTrack when voice actually activates. */
    private val audioPlayer: GeminiAudioPlayer by lazy { GeminiAudioPlayer(appContext) }

    // ── Agent readout TTS (Hermes / TapClaw / OpenClaw) ──────────────
    // Bug fix — in the hermes branch the SELECTED readout engine (Gemini
    // TTS or Fish.audio, per the companion "Readout Voice" tab) speaks all
    // agent replies, not Gemini Live's own voice. We mirror that: when an
    // agent tool returns, we suppress Gemini Live's audio for a window and
    // synthesize+play the reply through the routed engine instead.
    @Volatile
    private var suppressGeminiOutputUntilMs: Long = 0L

    /** While true the mic loop stops streaming to Gemini so the agent
     *  readout playing out the speaker isn't echoed back into the session. */
    @Volatile
    private var agentReadoutActive: Boolean = false

    private val fishReadoutTtsClient by lazy(LazyThreadSafetyMode.NONE) {
        com.TapLink.app.media.FishTtsClient(
            // ROOT-CAUSE FIX (agent-readout silence): the companion app
            // defaults fish_format to "mp3", and the media-browser path
            // gets away with that because it hands the bytes to a WebView
            // <audio> element that decodes MP3. THIS pipeline instead
            // decodes WAV by hand (stripWavHeaderToPcm rejects anything
            // without a RIFF/WAVE header), so an MP3 reply parsed to null
            // and the selected Fish voice was silently dropped — the user
            // heard nothing (or the wrong Gemini fallback voice). Force
            // WAV here so the PCM decoder always gets a stream it can play.
            configProvider = {
                com.TapLink.app.media.resolveGlassesFishConfig(appContext)?.copy(format = "wav")
            }
        )
    }
    private val geminiReadoutTtsClient by lazy(LazyThreadSafetyMode.NONE) {
        com.TapLink.app.media.GlassesTtsClient(
            apiKeyProvider = { com.TapLink.app.media.resolveGlassesGeminiKey(appContext) }
        )
    }

    /** Phase 4b — browser_vision tool. Captures the WebView frame via
     *  the cross-module [com.TapLink.app.media.BrowserFrameHolder]
     *  (publisher: tapbrowser MainActivity) and asks Gemini's REST
     *  vision endpoint about it.
     *
     *  Phase 4c — the frameProvider now uses captureBase64JpegWithStats
     *  so we can log non-black sample counts and prove the screenshot
     *  isn't a blank ARGB_8888 bitmap (the known View.draw hardware-
     *  accelerated WebView failure mode codex called out). */
    private val browserVisionTool: BrowserVisionTool by lazy {
        BrowserVisionTool(
            context = appContext,
            frameProvider = ::captureWebViewBase64Logged
        )
    }

    /** Phase 4i — full Hermes-era native tools in the Service path.
     *
     * The old Activity Live pipeline routed every Gemini tool call through
     * ToolDispatcher. The unipanel Service path had regressed to
     * browser_vision-only, which made routes like open_taplink,
     * send_video_list, tapradio, ask_maps, and hermes_agent look "gone"
     * even though their declarations were still in GeminiRouter. */
    private val toolDispatcher: ToolDispatcher by lazy {
        val prefs = AppPreferences(appContext)
        val hermesClient = HermesClient(
            endpointUrlProvider = { prefs.hermesEndpoint.trim().takeIf { it.isNotBlank() } },
            apiKeyProvider = { prefs.hermesApiKey.trim().takeIf { it.isNotBlank() } },
            sessionIdProvider = { prefs.hermesSessionId.trim().takeIf { it.isNotBlank() } ?: "main" },
            timeoutMsProvider = {
                val seconds = prefs.hermesTimeoutSeconds.takeIf { it > 0 } ?: 30
                seconds.coerceAtLeast(5) * 1000
            }
        )
        ToolDispatcher(
            context = appContext,
            recentCardsProvider = { viewModel.getAssistantCardsSnapshot().map { it.text } },
            hermesClient = hermesClient,
            cameraFrameProvider = { latestCameraFrame },
            browserFrameProvider = ::captureWebViewBase64Logged
        )
    }

    /** Phase 4c — debounce + in-flight guard for the local-regex
     *  trigger path. When Gemini Live doesn't elect to call the
     *  browser_vision tool itself (Live tool dispatch can be flaky
     *  in 3.1), we sniff the input transcript for vision-intent
     *  phrases and call BrowserVisionTool directly. */
    @Volatile private var lastLocalVisionTriggerMs: Long = 0L
    @Volatile private var visionInFlight: Boolean = false

    /** Phase 4e — callback the Service installs so the pipeline can
     *  ask it to auto-start CameraX when a vision phrase fires. Set
     *  by [setAutoCameraEnabler] in Service.onCreate. */
    @Volatile private var autoCameraEnabler: (() -> Unit)? = null

    /** Service installs this so the pipeline can request a camera
     *  warmup when the user's voice triggers a vision phrase. Idempotent
     *  on the Service side. */
    fun setAutoCameraEnabler(enabler: () -> Unit) {
        autoCameraEnabler = enabler
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

    /**
     * Phase 4d — push one camera frame into the active Gemini Live
     * session. Called by the Service whenever
     * [com.rayneo.visionclaw.core.camera.FrameCaptureManager]
     * produces a new analyzed frame (CameraX ImageAnalysis use case).
     *
     * No-op if the session isn't ready yet — there's no point
     * sending video before onSessionReady fires, the model would
     * just drop the frame.
     */
    fun sendCameraFrame(base64: String) {
        latestCameraFrame = base64.takeIf { it.isNotBlank() }
        if (!liveSessionReady) return
        if (base64.isBlank()) return
        runCatching {
            liveSession?.sendImageChunkBase64(base64, "image/jpeg")
        }
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
                latestInputTranscript = text
                Log.d(TAG, "onInputTranscription partial='${text.take(120)}'")
                // Live partial transcript — surface in HUD so the user
                // sees what Gemini heard. Final commit to viewModel
                // happens on turnComplete to avoid mid-utterance noise.
                HudStateBridge.update { it.copy(transcript = text) }
                // Phase 4c (codex) — deterministic browser_vision trigger.
                // Gemini Live doesn't always pick the browser_vision
                // tool even when the user clearly asks "look at this" /
                // "what does this say". Sniff the partial transcript
                // and fire the tool ourselves with the latest text as
                // the question.
                maybeTriggerBrowserVisionLocally(text)
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
                // Drop Gemini Live's own audio while an agent reply is being
                // read aloud via the selected readout engine, so the two
                // voices never overlap (mirrors hermes suppressGeminiOutputUntilMs).
                if (android.os.SystemClock.uptimeMillis() < suppressGeminiOutputUntilMs) {
                    return
                }
                runCatching {
                    audioPlayer.playChunk(mimeType, data, muted = false, volume = 1f)
                }
                // Phase 4k — drive the MODEL (blue) oscilloscope from
                // Gemini's outgoing audio so the avatar orb glows blue
                // and pulses with the reply's loudness while it speaks.
                // Output audio is PCM16, same as the mic stream, so the
                // same peak helper applies. Phase is already THINKING
                // (set in onOutputTranscription); we just feed the level.
                runCatching {
                    val peak = calculatePcm16Peak(data, data.size)
                    val norm = (peak / 32_767f).coerceIn(0f, 1f)
                    HudStateBridge.update {
                        it.copy(
                            oscilloscopeLevel = norm,
                            oscilloscopeChannel = HudStateBridge.OscilloscopeChannel.MODEL
                        )
                    }
                }
            }

            override fun onToolCall(callId: String, name: String, args: String) {
                Log.i(TAG, "onToolCall: callId=$callId name=$name args=${args.take(160)}")
                when (name) {
                    "browser_vision" -> dispatchBrowserVision(callId, name, args)
                    else -> dispatchNativeTool(callId, name, args)
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
     * Phase 4i — restore the Hermes-era tool surface for the unipanel
     * Service voice path. Tool declarations already come from
     * GeminiRouter; this executes the matching local tool and sends the
     * result back to Gemini Live. URL-opening tools are also applied
     * locally so Gemini cannot swallow or mangle taplink:// results.
     */
    private fun dispatchNativeTool(callId: String, name: String, args: String) {
        val toolName = name.trim()
        if (toolName.isBlank()) return
        Log.i(TAG, "native tool dispatch: callId=$callId name=$toolName args=${args.take(160)}")

        if (!toolDispatcher.isSupported(toolName)) {
            Log.w(TAG, "native tool unsupported in Service: $toolName")
            runCatching {
                liveSession?.sendToolResponse(callId, toolName, "Unknown tool: $toolName")
            }
            return
        }

        scope.launch {
            HudStateBridge.update { it.copy(notification = "Running $toolName…") }
            val result = toolDispatcher.dispatch(toolName, args)
            val resultText = result.getOrElse { err ->
                Log.w(TAG, "native tool failed name=$toolName: ${err.message}")
                err.message?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "Tool $toolName is unavailable right now."
            }
            Log.i(TAG, "native tool result name=$toolName text='${resultText.take(180)}'")

            // Agent replies (Hermes / TapClaw / OpenClaw) are READ ALOUD via
            // the selected readout engine — not narrated by Gemini Live. Mirror
            // the hermes branch: suppress Gemini's audio, ack the tool with a
            // minimal "ok" so the Live session doesn't hang waiting, then
            // synthesize + play the reply through Fish/Gemini TTS.
            if (toolName in AGENT_READOUT_TOOLS) {
                Log.i(TAG, "agent tool $toolName → reading reply via selected readout engine")
                suppressGeminiOutputUntilMs =
                    android.os.SystemClock.uptimeMillis() + 30_000L
                runCatching { audioPlayer.stopAndFlush() }
                runCatching { liveSession?.sendToolResponse(callId, toolName, "ok") }
                HudStateBridge.update {
                    it.copy(notification = null, phase = HudStateBridge.VoicePhase.THINKING)
                }
                speakAgentReplyViaEngine(resultText)
                return@launch
            }

            maybeOpenTapLinkResult(toolName, resultText)
            val ok = runCatching {
                liveSession?.sendToolResponse(callId, toolName, resultText) == true
            }.getOrDefault(false)
            Log.i(TAG, "native tool sendToolResponse returned $ok name=$toolName callId=$callId")
            HudStateBridge.update { it.copy(notification = null) }
        }
    }

    /** Agent tools whose textual reply is read aloud via the selected
     *  readout engine instead of being narrated by Gemini Live. */
    private val AGENT_READOUT_TOOLS =
        setOf("hermes_agent", "tapclaw_agent", "research_topic")

    /**
     * Synthesize [text] via the selected readout engine (Fish.audio when
     * the companion "Readout Voice" is set to Fish and configured, else
     * Gemini TTS) and play it through the same AudioTrack Gemini Live uses.
     * Chunked so long reports don't exceed the per-call TTS limit. The mic
     * is muted and Gemini's own audio suppressed for the duration so the
     * two voices never overlap and the readout isn't echoed back.
     */
    private fun speakAgentReplyViaEngine(rawText: String) {
        val text = cleanReadoutText(rawText)
        if (text.isBlank()) {
            suppressGeminiOutputUntilMs = 0L
            return
        }
        agentReadoutActive = true
        scope.launch {
            var playedAny = false
            try {
                val chunks = chunkForReadout(text, 1600)
                for (chunk in chunks) {
                    if (chunk.isBlank()) continue
                    val pcm = synthesizeRoutedToPcm(chunk) ?: continue
                    // Keep Gemini's audio suppressed across the whole read.
                    suppressGeminiOutputUntilMs =
                        android.os.SystemClock.uptimeMillis() + 60_000L
                    HudStateBridge.update {
                        it.copy(
                            phase = HudStateBridge.VoicePhase.THINKING,
                            oscilloscopeLevel = 0.6f,
                            oscilloscopeChannel = HudStateBridge.OscilloscopeChannel.MODEL
                        )
                    }
                    runCatching {
                        audioPlayer.playChunk(
                            "audio/pcm;rate=${pcm.second}", pcm.first,
                            muted = false, volume = 1f
                        )
                    }.onSuccess { playedAny = true }
                }
                if (!playedAny) {
                    // Both engines yielded no playable audio. Don't fail
                    // silently — tell the user so a misconfigured TTS engine
                    // is visible instead of looking like a dead assistant.
                    Log.w(TAG, "agent readout produced no audio (TTS engine unavailable)")
                    HudStateBridge.update {
                        it.copy(notification = "Readout voice unavailable — check TTS settings")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "agent readout failed: ${e.message}", e)
            } finally {
                agentReadoutActive = false
                // Let the queued audio drain, then stop dropping Gemini's
                // audio and return to the listening state for follow-ups.
                suppressGeminiOutputUntilMs = 0L
                if (liveSessionReady) {
                    HudStateBridge.update {
                        it.copy(
                            phase = HudStateBridge.VoicePhase.LISTENING,
                            oscilloscopeLevel = 0f
                        )
                    }
                }
            }
        }
    }

    /** Route one chunk through Fish or Gemini TTS and return PCM + sample
     *  rate the [GeminiAudioPlayer] can stream, or null on failure. */
    private fun synthesizeRoutedToPcm(text: String): Pair<ByteArray, Int>? {
        val prefs = appContext.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
        val engine = (prefs.getString("readout_engine", "gemini") ?: "gemini").trim()
        if (engine == "fish" && com.TapLink.app.media.isFishReadoutReady(appContext)) {
            val fish = runCatching { fishReadoutTtsClient.synthesize(text) }.getOrNull()
            if (fish is com.TapLink.app.media.FishTtsClient.SynthesisResult.Success) {
                stripWavHeaderToPcm(fish.wavBytes)?.let { return it }
            }
            Log.w(TAG, "Fish readout unavailable for chunk — falling back to Gemini TTS")
        }
        val gem = runCatching { geminiReadoutTtsClient.synthesize(text, null) }.getOrNull()
        if (gem is com.TapLink.app.media.GlassesTtsClient.SynthesisResult.Success) {
            return stripWavHeaderToPcm(gem.wavBytes)
        }
        return null
    }

    /** Split [text] into <= [maxChars] pieces at sentence / whitespace
     *  boundaries so each fits the per-call TTS cap. */
    private fun chunkForReadout(text: String, maxChars: Int): List<String> {
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return listOf(trimmed)
        val out = ArrayList<String>()
        val sentences = trimmed.split(Regex("(?<=[.!?])\\s+"))
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.isNotEmpty() && sb.length + s.length + 1 > maxChars) {
                out.add(sb.toString().trim()); sb.setLength(0)
            }
            if (s.length > maxChars) {
                // A single mega-sentence: hard-split on whitespace.
                var rest = s
                while (rest.length > maxChars) {
                    val cut = rest.lastIndexOf(' ', maxChars).takeIf { it > 0 } ?: maxChars
                    out.add(rest.substring(0, cut).trim())
                    rest = rest.substring(cut).trim()
                }
                if (rest.isNotBlank()) sb.append(rest).append(' ')
            } else {
                sb.append(s).append(' ')
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.filter { it.isNotBlank() }
    }

    /** Light markdown / URL cleanup so the synthesized speech doesn't read
     *  out asterisks, backticks, or raw URLs. */
    private fun cleanReadoutText(text: String): String {
        return try {
            text.replace("\r\n", "\n")
                .replace(Regex("(?s)```.*?```"), " ")
                .replace(Regex("(?:https?://|www\\.)[^\\s<>\"']+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\*\\*([^*\\n]+?)\\*\\*"), "$1")
                .replace(Regex("\\*([^*\\n]+?)\\*"), "$1")
                .replace(Regex("[*_`#>|~]+"), " ")
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\s*\\n\\s*\\n\\s*"), "\n\n")
                .trim()
        } catch (e: Exception) {
            text.trim()
        }
    }

    /** Parse a WAV byte array into raw PCM + sample rate (ported from the
     *  visionclaw readout pipeline; tolerates Fish.audio streaming-size
     *  sentinels). Returns null if it isn't parseable PCM WAV. */
    private fun stripWavHeaderToPcm(wav: ByteArray): Pair<ByteArray, Int>? {
        if (wav.size < 44) return null
        if (wav[0] != 'R'.code.toByte() || wav[1] != 'I'.code.toByte() ||
            wav[2] != 'F'.code.toByte() || wav[3] != 'F'.code.toByte()
        ) return null
        if (wav[8] != 'W'.code.toByte() || wav[9] != 'A'.code.toByte() ||
            wav[10] != 'V'.code.toByte() || wav[11] != 'E'.code.toByte()
        ) return null
        fun u32(off: Int): Long =
            (wav[off].toLong() and 0xFFL) or
                ((wav[off + 1].toLong() and 0xFFL) shl 8) or
                ((wav[off + 2].toLong() and 0xFFL) shl 16) or
                ((wav[off + 3].toLong() and 0xFFL) shl 24)
        fun isStreamingSizeSentinel(sz: Long): Boolean = sz >= 0xFFFFFF00L || sz < 0L
        var sampleRate = 0
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val rawSz = u32(pos + 4)
            val sz: Long =
                if (isStreamingSizeSentinel(rawSz)) (wav.size - (pos + 8)).toLong().coerceAtLeast(0L)
                else rawSz.coerceAtLeast(0L)
            when (id) {
                "fmt " -> if (pos + 12 + 4 <= wav.size) sampleRate = u32(pos + 12).toInt()
                "data" -> {
                    val dataStart = pos + 8
                    val dataEnd = (dataStart.toLong() + sz)
                        .coerceAtMost(wav.size.toLong())
                        .coerceAtLeast(dataStart.toLong())
                        .toInt()
                    if (sampleRate <= 0) return null
                    return wav.copyOfRange(dataStart, dataEnd) to sampleRate
                }
            }
            val advance = sz + (sz and 1L)
            val nextPos = (pos.toLong() + 8L + advance).coerceAtMost(wav.size.toLong()).toInt()
            if (nextPos <= pos) return null
            pos = nextPos
        }
        return null
    }

    /**
     * Phase 4b — run browser_vision off the listener thread.
     * Phase 4c (codex) — added end-to-end instrumentation so we can
     * prove the screenshot is real and that the tool response gets
     * back to Gemini. Logs ["browser_vision trigger source=..."],
     * ["BrowserVisionTool result success/failure"], and
     * ["sendToolResponse returned true/false"].
     */
    private fun dispatchBrowserVision(callId: String, name: String, args: String) {
        Log.i(TAG, "browser_vision trigger source=toolCall callId=$callId")
        scope.launch {
            val question = parseQuestionArg(args)
            runBrowserVisionAndDeliver(
                question = question,
                callId = callId,
                toolName = name
            )
        }
    }

    /**
     * Phase 4c (codex) — local-regex fallback for the case where
     * Gemini Live doesn't pick the browser_vision tool itself.
     *
     * Sniffs the partial input transcript for vision-intent phrases.
     * Debounces with [MIN_LOCAL_VISION_INTERVAL_MS] (so a single
     * spoken utterance only fires once even though
     * onInputTranscription emits multiple partials) plus an in-flight
     * guard ([visionInFlight]) so we don't stack overlapping
     * captures.
     *
     * When triggered, runs the tool directly and injects the result
     * back into the Live session via [GeminiRouter.LiveSessionHandle
     * .sendClientText] — there's no Gemini-issued callId for this
     * path, so sendToolResponse isn't applicable. The injected text
     * tells Gemini what the screen says and instructs it to answer
     * the user briefly out loud.
     */
    private fun maybeTriggerBrowserVisionLocally(transcript: String) {
        val now = System.currentTimeMillis()
        if (visionInFlight) return
        if (now - lastLocalVisionTriggerMs < MIN_LOCAL_VISION_INTERVAL_MS) return
        if (liveSession == null) return
        val lower = transcript.lowercase().trim()
        val matched = VISION_TRIGGER_PHRASES.any { phrase -> lower.contains(phrase) }
        if (!matched) return

        lastLocalVisionTriggerMs = now
        visionInFlight = true
        Log.i(TAG, "browser_vision trigger source=localRegex transcript='${transcript.take(80)}'")
        // Phase 4e — auto-start CameraX so Gemini also has real-world
        // visual context for the rest of this voice session. Service-
        // side toggle is idempotent: a second call is a no-op while
        // the camera is already streaming. Red dot in the HUD
        // reflects this via CameraStateBridge.
        runCatching { autoCameraEnabler?.invoke() }
        scope.launch {
            try {
                runBrowserVisionAndDeliver(
                    question = transcript.ifBlank { DEFAULT_VISION_QUESTION },
                    callId = null,
                    toolName = null
                )
            } finally {
                visionInFlight = false
            }
        }
    }

    /**
     * Phase 4c — the shared body for both trigger paths. Captures
     * via [captureWebViewBase64Logged] (which logs nonblack pixel
     * count), runs [BrowserVisionTool], and delivers the result.
     *
     * If [callId] is non-null we route via sendToolResponse (the
     * Gemini Live tool-call path). If null we route via
     * sendClientText (the local-regex fallback path) — Gemini reads
     * the injected text on its next turn and synthesises a spoken
     * answer.
     */
    private suspend fun runBrowserVisionAndDeliver(
        question: String,
        callId: String?,
        toolName: String?
    ) {
        HudStateBridge.update {
            it.copy(notification = "Looking at the screen…")
        }
        val result = runCatching {
            browserVisionTool.execute(mapOf("question" to question))
        }
        val responseText = result.getOrNull()?.getOrElse { err ->
            Log.w(TAG, "BrowserVisionTool failure: ${err.message}")
            "I couldn't read the screen: ${err.message ?: "unknown error"}"
        } ?: run {
            val ex = result.exceptionOrNull()
            Log.w(TAG, "BrowserVisionTool threw: ${ex?.message}")
            "I couldn't read the screen: ${ex?.message ?: "unknown error"}"
        }
        Log.i(TAG, "BrowserVisionTool result success=${result.getOrNull()?.isSuccess == true} " +
            "text='${responseText.take(160)}'")
        HudStateBridge.update { it.copy(notification = null) }

        if (callId != null && toolName != null) {
            val ok = runCatching {
                liveSession?.sendToolResponse(callId, toolName, responseText) == true
            }.getOrDefault(false)
            Log.i(TAG, "sendToolResponse returned $ok (callId=$callId)")
        } else {
            val injection = "The user just asked about what's on screen. " +
                "Vision tool result: $responseText. " +
                "Answer the user's question briefly out loud based on that."
            val ok = runCatching {
                liveSession?.sendClientText(injection) == true
            }.getOrDefault(false)
            Log.i(TAG, "sendClientText (localRegex fallback) returned $ok")
        }
    }

    private fun maybeOpenTapLinkResult(toolName: String, resultText: String) {
        val openUrl = when {
            toolName in setOf("open_taplink", "send_video_list", "send_link_list") ->
                AssistantIntentParser.extractTapLinkUrl(resultText)
            resultText.contains("open_taplink:", ignoreCase = true) ->
                resultText.substringAfter("open_taplink:", "")
                    .substringBefore('\n')
                    .trim()
                    .let { AssistantIntentParser.normalizeTapLinkUrl(it) }
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return

        launchTapBrowserFromService(openUrl)
    }

    private fun launchTapBrowserFromService(openUrl: String) {
        val initialUrl = openUrl.removePrefix("taplink://").trim().ifBlank { return }
        val intent = Intent().apply {
            component = ComponentName("com.rayneo.visionclaw", TAPBROWSER_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_BROWSER_INITIAL_URL, initialUrl)
        }

        val lower = initialUrl.lowercase()
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
            val spec = AssistantIntentParser.parseExplicitYouTubePlaybackRequest(latestInputTranscript)
            val query = spec?.items?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: extractYouTubeSearchQuery(initialUrl)
            val mode = spec?.mode
                ?: if (latestInputTranscript.contains("music", ignoreCase = true) ||
                    latestInputTranscript.contains("song", ignoreCase = true)
                ) "music" else "video"
            if (!query.isNullOrBlank()) {
                putYouTubeAutoplayExtras(intent, query, mode, spec?.items.orEmpty())
            }
        }

        Log.i(TAG, "Launching TapBrowser from Service url=${initialUrl.take(180)}")
        runCatching { appContext.startActivity(intent) }
            .onFailure { Log.w(TAG, "TapBrowser launch failed: ${it.message}") }

        // Bug fix — when the launched URL plays audio (radio / YouTube /
        // any stream), the still-active Gemini Live session holds the
        // mic + AudioTrack, so the media can't start. End the voice
        // session so the WebView media gets the audio path (mirrors the
        // hermes branch, where launching media ends the multimodal
        // session). Non-audio pages leave the session running.
        if (isAudioMediaUrl(lower)) {
            Log.i(TAG, "Media URL launched — ending voice session so it can play audio")
            shutdown(reason = null)
        }
    }

    /** True for URLs that will play audio (radio streams, YouTube, media
     *  files / player), so the voice session must release the audio path. */
    private fun isAudioMediaUrl(lowerUrl: String): Boolean {
        return lowerUrl.contains("youtube.com") ||
            lowerUrl.contains("youtu.be") ||
            lowerUrl.contains("tapradio") ||
            lowerUrl.contains("radio") ||
            lowerUrl.contains("somafm") ||
            lowerUrl.contains("media_player") ||
            lowerUrl.contains("/stream") ||
            lowerUrl.contains("stream.") ||
            lowerUrl.contains(".mp3") ||
            lowerUrl.contains(".m3u") ||
            lowerUrl.contains(".aac") ||
            lowerUrl.contains(".pls")
    }

    private fun putYouTubeAutoplayExtras(
        intent: Intent,
        query: String,
        mode: String,
        queue: List<String>
    ) {
        intent.putExtra(EXTRA_YOUTUBE_AUTOPLAY_QUERY, query)
        intent.putExtra(EXTRA_YOUTUBE_AUTOPLAY_MODE, mode)
        if (queue.size > 1) {
            intent.putExtra(EXTRA_YOUTUBE_AUTOPLAY_QUEUE, org.json.JSONArray(queue).toString())
        }
    }

    private fun extractYouTubeSearchQuery(url: String): String? {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        return sequenceOf(
            uri.getQueryParameter("search_query"),
            uri.getQueryParameter("q")
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    /**
     * Phase 4c — capture wrapper used as the BrowserVisionTool
     * frame-provider. Logs hasWebView / width / height / base64
     * length / non-black pixel count so we can prove the screenshot
     * isn't blank before it goes over the wire to Gemini.
     */
    private fun captureWebViewBase64Logged(): String? {
        val stats = com.TapLink.app.media.BrowserFrameHolder.captureStats()
        val captured = com.TapLink.app.media.BrowserFrameHolder.captureBase64JpegWithStats()
        if (captured == null) {
            Log.w(
                TAG,
                "BrowserFrameHolder: hasWebView=${stats.hasWebView} " +
                    "w=${stats.width} h=${stats.height} base64=null (capture failed)"
            )
            return null
        }
        Log.i(
            TAG,
            "BrowserFrameHolder: hasWebView=${stats.hasWebView} " +
                "w=${captured.width} h=${captured.height} " +
                "base64Len=${captured.base64.length} " +
                "nonBlack=${captured.nonBlackSamples}/${captured.sampledPixels}"
        )
        if (captured.nonBlackSamples * 100 < captured.sampledPixels) {
            Log.w(
                TAG,
                "BrowserFrameHolder: capture looks BLANK (only " +
                    "${captured.nonBlackSamples}/${captured.sampledPixels} non-black). " +
                    "View.draw on a hardware-accelerated WebView may be the cause."
            )
        }
        return captured.base64
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
                    //
                    // Phase 4k — only drive the USER (red) oscilloscope
                    // while we're actually in the LISTENING phase. The mic
                    // keeps streaming during Gemini's reply (for barge-in),
                    // so without this gate the red mic level would clobber
                    // the blue MODEL level published from onModelAudio and
                    // the orb would flicker red/blue while Gemini speaks.
                    if (HudStateBridge.current().phase ==
                            HudStateBridge.VoicePhase.LISTENING &&
                        (norm > 0.04f || (System.currentTimeMillis() % 8L == 0L))
                    ) {
                        HudStateBridge.update {
                            it.copy(
                                oscilloscopeLevel = norm,
                                oscilloscopeChannel = HudStateBridge.OscilloscopeChannel.USER
                            )
                        }
                    }
                    // Don't stream the mic to Gemini while an agent reply is
                    // being read aloud via the readout engine — otherwise the
                    // speaker audio echoes back in and Gemini reacts to its
                    // own readout as a new user turn.
                    if (!agentReadoutActive) {
                        runCatching {
                            liveSession?.sendAudioChunkPcm16(chunk, read, SAMPLE_RATE_HZ)
                        }
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
        private const val TAPBROWSER_ACTIVITY = "com.TapLinkX3.app.MainActivity"
        private const val EXTRA_BROWSER_INITIAL_URL = "tapclaw_initial_url"
        private const val EXTRA_YOUTUBE_AUTOPLAY_QUERY = "tapclaw_youtube_autoplay_query"
        private const val EXTRA_YOUTUBE_AUTOPLAY_MODE = "tapclaw_youtube_autoplay_mode"
        private const val EXTRA_YOUTUBE_AUTOPLAY_QUEUE = "tapclaw_youtube_autoplay_queue"
        private const val DEFAULT_VISION_QUESTION =
            "Describe what's currently on the screen in plain English."

        /** Minimum gap between local-regex browser_vision triggers,
         *  so a single 2-3-second utterance (which emits many partials)
         *  doesn't fire the tool more than once. */
        private const val MIN_LOCAL_VISION_INTERVAL_MS = 3_000L

        /** Phrases that locally trigger browser_vision when Gemini
         *  Live doesn't elect to call the tool itself. All matched
         *  case-insensitively as substrings, so "what does this say
         *  here" / "could you look at this" / etc. also match. */
        private val VISION_TRIGGER_PHRASES = listOf(
            "look at this",
            "what does this say",
            "what's on screen",
            "what is on screen",
            "what's on the screen",
            "what is on the screen",
            "read this",
            "read the screen",
            "summarize this page",
            "summarise this page",
            "summarize the screen",
            "summarise the screen",
            "describe the screen",
            "describe this page",
            "what am i looking at"
        )
    }
}
