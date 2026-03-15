package com.rayneo.visionclaw.core.network

import android.util.Log
import com.rayneo.visionclaw.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * GeminiRouter – sends prompts to Google's Gemini API and returns
 * structured responses for the AITap AR assistant.
 *
 * Error-handling contract:
 *   • Missing / blank API key → [GeminiResult.ApiKeyMissing] (no crash).
 *   • Network or server errors → [GeminiResult.Error] with message.
 *   • Success → [GeminiResult.Success] with the response text.
 */
class GeminiRouter(
    private val apiKeyProvider: () -> String?,
    private val preferredModelProvider: () -> String? = { null },
    private val gatewayBaseUrlProvider: () -> String? = { null },
    private val gatewayTokenProvider: () -> String? = { null },
    private val personalityProvider: () -> String? = { null },
    private val customSystemPromptProvider: () -> String? = { null },
    private val identityProvider: () -> String? = { null },
    private val routingRulesProvider: () -> String? = { null },
    private val behaviorProvider: () -> String? = { null },
    private val urlRulesProvider: () -> String? = { null },
    private val locationContextProvider: () -> String? = { null }
) {

    companion object {
        private const val TAG = "GeminiRouter"
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models"
        private const val LIVE_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        private const val DEFAULT_MODEL = "gemini-3-flash-preview"
        private const val AUDIO_MODEL = "gemini-3-flash-preview"
        // Live WebSocket API only supports 2.5-flash native audio (Gemini 3 not yet supported).
        private const val DEFAULT_LIVE_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
        // ── Modular system prompt sections (each editable via companion app) ──

        internal const val DEFAULT_IDENTITY =
            "You are AITap, a proactive AI assistant integrated into RayNeo X3 AR glasses.\n" +
                "You can see through the user's camera and hear their voice in real-time."

        private const val DEFAULT_CAPABILITIES =
            "CAPABILITIES:\n" +
                "- Vision: Analyze what the user sees (assembly, cooking, reading, QR codes)\n" +
                "- Calendar: Query and create events via google_calendar tool\n" +
                "- Tasks: Query, create, and complete to-do items via google_tasks tool\n" +
                "- Notes: Save observations and quick memos to Google Keep via google_keep tool\n" +
                "- Contacts: Look up contacts via google_contacts tool\n" +
                "- Navigation: Check traffic, commute times, ETAs, and get directions via google_routes tool\n" +
                "- Places: Find nearby businesses, restaurants, cafes, gas stations with ratings and open/closed status via google_places tool\n" +
                "- Air quality: Check current AQI and pollutant conditions via google_air_quality tool\n" +
                "- Daily briefing: Build a multi-source daily brief with calendar, GPS proximity, public events, traffic, parking, weather, and AQI via daily_briefing tool\n" +
                "- Ask Maps: Explore places with AI summaries, 3D photorealistic navigation, nearby landmarks, and landmark-aware directions via ask_maps tool\n" +
                "- Music: Control Spotify via spotify_player tool and Sonos via sonos_control tool\n" +
                "- Communication: Send messages via send_message and place calls via place_call tool\n" +
                "- Camera: Save photos via camera_action tool\n" +
                "- Web: Open URLs in TapBrowser via open_taplink tool\n" +
                "- Research: Produce long-form research briefs via research_topic tool\n" +
                "- Memory: Recall recent conversations from context cache via get_context tool"

        internal const val DEFAULT_ROUTING_RULES =
            "TOOL ROUTING RULES:\n" +
                "1) For calendar questions (today, tomorrow, rest of day, upcoming, what's next, am I free, schedule, meetings), " +
                "always call google_calendar. Never ask for calendar provider.\n" +
                "2) For reminders, todos, or task lists, call google_tasks (query/create/complete).\n" +
                "3) For personal notes or quick memos, call google_keep.\n" +
                "4) For ANY question about directions, traffic, commute time, ETA, travel time, " +
                "how long to get somewhere, or route planning, ALWAYS call google_routes. " +
                "Use origin='current' if the user doesn't specify a starting point.\n" +
                "5) For music playback, call spotify_player or sonos_control.\n" +
                "6) For contacts/phone numbers, call google_contacts.\n" +
                "7) For sending texts or making calls, call send_message or place_call.\n" +
                "8) For finding nearby restaurants, cafes, gas stations, pharmacies, or checking what's open nearby, " +
                "ALWAYS call google_places. Use type like 'restaurant', 'cafe', 'gas_station', etc. " +
                "If the closest place is closed, explicitly promote a DIFFERENT nearby open option instead. " +
                "Never describe the same closed place as the open fallback. Include walking ETA, driving ETA, " +
                "weather, and a Maps link when available.\n" +
                "9) ONLY call daily_briefing when the user explicitly asks for a 'daily briefing', 'daily brief', or 'ultimate daily brief'. " +
                "Never use daily_briefing for generic calendar, events-near-me, what's open, nearby places, traffic, weather, or route questions.\n" +
                "10) For air quality, AQI, smoke, pollution, or whether the air is safe right now, ALWAYS call google_air_quality.\n" +
                "11) For requests to research, analyze, brief, or do a deep dive on a topic, ALWAYS call research_topic. " +
                "Do not open the browser unless the user explicitly asks to open a site.\n" +
                "12) For 'tell me about [place]', 'explore [place]', 'what is [landmark]', 'navigate 3D to', 'show me in 3D', " +
                "'what landmarks are nearby', or 'nearby landmarks', ALWAYS call ask_maps. " +
                "Use action='explore' for place info, action='navigate_3d' for 3D navigation, " +
                "action='landmark_directions' for landmark-aware directions, action='nearby_landmarks' for landmark discovery.\n" +
                "13) If a tool fails, reply with one short sentence and a retry suggestion. Never show logs."

        internal const val DEFAULT_BEHAVIOR =
            "PROACTIVE BEHAVIOR:\n" +
                "- When you see a QR code, automatically offer to scan it\n" +
                "- When you see text (menu, sign, document), offer to read it aloud\n" +
                "- When you detect assembly/cooking context, offer step-by-step guidance\n\n" +
                "HUD OUTPUT RULES:\n" +
                "Keep responses to 1-6 lines.\n" +
                "Never output stack traces, logs, HTTP status codes, raw JSON, or diagnostics.\n" +
                "Never repeat the user's transcript.\n" +
                "For calendar answers, format each event as: TIME — TITLE (LOCATION/ONLINE).\n" +
                "For nearby places, prefer the nearest OPEN option, then include ETA, weather, and a Maps link if present.\n\n" +
                "Privacy: DO NOT transcribe or display user speech back in the chat. " +
                "Only display your own responses and valid research links."

        internal const val DEFAULT_URL_RULES =
            "URL RULES:\n" +
                "All links must use https:// format.\n" +
                "Always prefer direct, known URLs when you can confidently provide them " +
                "(e.g. https://www.youtube.com/@depechemode for an official YouTube channel).\n" +
                "Only when you cannot determine a direct URL, provide a Google Search link:\n" +
                "- For video queries: https://www.google.com/search?q=QUERY+HERE&tbm=vid\n" +
                "- For all other queries: https://www.google.com/search?q=QUERY+HERE\n" +
                "Replace spaces with + signs."
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        @Suppress("unused") private const val GATEWAY_KEY_PLACEHOLDER = "gateway"
    }

    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // Gemini Live already speaks over an active streaming channel and OkHttp will
            // still answer any server-initiated ping frames automatically. Client-initiated
            // pings here were causing otherwise healthy long responses to die with
            // "no pong response" failures mid-turn.
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun sanitizeApiKey(raw: String?): String? {
        val key = raw.orEmpty().trim()
        if (key.isBlank()) return null
        return if (
            key.equals("REPLACE_WITH_YOUR_GEMINI_KEY", ignoreCase = true) ||
            key.equals("YOUR_GEMINI_API_KEY", ignoreCase = true) ||
            key.equals("your_actual_key_here_abc123", ignoreCase = true)
        ) {
            null
        } else {
            key
        }
    }

    private fun resolveApiKey(): String? {
        sanitizeApiKey(apiKeyProvider())?.let { return it }
        return sanitizeApiKey(BuildConfig.GEMINI_API_KEY)
    }

    private fun resolvePreferredModel(requestedModel: String, defaultModel: String): String {
        val configured = preferredModelProvider().orEmpty().trim()
        if (configured.isBlank()) return requestedModel
        return if (requestedModel == defaultModel) configured else requestedModel
    }

    private fun resolvePreferredLiveModel(requestedModel: String): String {
        val configured = preferredModelProvider().orEmpty().trim()
        if (configured.isBlank()) return requestedModel
        val isLiveCapable =
            configured.contains("native-audio", ignoreCase = true) ||
                configured.contains("live", ignoreCase = true)
        return if (isLiveCapable) configured else requestedModel
    }

    private fun resolveGatewayBaseUrl(): String? {
        val raw = gatewayBaseUrlProvider().orEmpty().trim().trimEnd('/')
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> "http://$raw"
        }
    }

    private fun resolveGatewayToken(): String? {
        val token = gatewayTokenProvider().orEmpty().trim()
        return token.takeIf { it.isNotBlank() }
    }

    private fun resolveLiveWebSocketUrl(gatewayBaseUrl: String?): String {
        if (gatewayBaseUrl.isNullOrBlank()) return LIVE_WS_URL
        val wsBase = when {
            gatewayBaseUrl.startsWith("https://") -> "wss://${gatewayBaseUrl.removePrefix("https://")}"
            gatewayBaseUrl.startsWith("http://") -> "ws://${gatewayBaseUrl.removePrefix("http://")}"
            gatewayBaseUrl.startsWith("wss://") || gatewayBaseUrl.startsWith("ws://") -> gatewayBaseUrl
            else -> "ws://$gatewayBaseUrl"
        }.trimEnd('/')
        return "$wsBase/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    /** Sealed result type — callers never see raw exceptions. */
    sealed class GeminiResult {
        data class Success(val text: String, val model: String) : GeminiResult()
        data class Error(val message: String, val code: Int = -1) : GeminiResult()
        object ApiKeyMissing : GeminiResult()
    }

    interface LiveSessionListener {
        fun onSessionReady()
        fun onInputTranscription(text: String)
        fun onOutputTranscription(text: String)
        fun onModelText(text: String)
        fun onModelAudio(mimeType: String, data: ByteArray)
        fun onToolCall(callId: String, name: String, args: String)
        fun onTurnComplete(finishReason: String?)
        fun onError(message: String)
        fun onClosed(code: Int, reason: String)
    }

    class LiveSessionHandle internal constructor(
        private val socket: WebSocket
    ) {
        fun sendAudioChunkPcm16(bytes: ByteArray, size: Int, sampleRateHz: Int = 16_000): Boolean {
            if (size <= 0) return false
            val chunk = JSONObject()
                .put("mimeType", "audio/pcm;rate=$sampleRateHz")
                .put("data", Base64.getEncoder().encodeToString(bytes.copyOf(size)))
            val payload = JSONObject()
                .put("realtimeInput", JSONObject().put("audio", chunk))
            return socket.send(payload.toString())
        }

        fun sendAudioEnd(): Boolean {
            val payload = JSONObject().put(
                "realtimeInput",
                JSONObject().put("audioStreamEnd", true)
            )
            return socket.send(payload.toString())
        }

        fun sendImageChunkBase64(imageBase64: String, mimeType: String = "image/jpeg"): Boolean {
            if (imageBase64.isBlank()) return false
            val mediaChunk = JSONObject()
                .put("mimeType", mimeType)
                .put("data", imageBase64)
            val payload = JSONObject().put(
                "realtimeInput",
                JSONObject().put("mediaChunks", JSONArray().put(mediaChunk))
            )
            return socket.send(payload.toString())
        }

        /**
         * Inject a text message into the Live session as client context.
         * Used by ToolAssistEngine to feed tool results directly because
         * the native-audio model's function-calling is unreliable.
         */
        fun sendClientText(text: String): Boolean {
            if (text.isBlank()) return false
            val payload = JSONObject().put(
                "clientContent",
                JSONObject()
                    .put("turns", JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(
                                JSONObject().put("text", text)
                            ))
                    ))
                    .put("turnComplete", true)
            )
            Log.d(TAG, "Injecting clientContent text: ${text.take(300)}")
            return socket.send(payload.toString())
        }

        fun sendToolResponse(callId: String, functionName: String, result: String): Boolean {
            if (callId.isBlank() || functionName.isBlank()) return false
            val functionResponse = JSONObject()
                .put("id", callId)
                .put("name", functionName)
                .put("response", JSONObject().put("result", result))
            // Gemini Live API expects exactly one top-level key: toolResponse (camelCase).
            // Sending both camelCase and snake_case in the same payload corrupts the frame
            // and prevents Gemini from generating a spoken reply.
            val payload = JSONObject().put(
                "toolResponse",
                JSONObject().put("functionResponses", JSONArray().put(functionResponse))
            )
            Log.d(
                TAG,
                "Sending toolResponse to Gemini callId=$callId function=$functionName payload=${payload.toString().take(500)}"
            )
            return socket.send(payload.toString())
        }

        fun close() {
            socket.close(1000, "client_close")
        }
    }

    fun startLiveAudioSession(
        listener: LiveSessionListener,
        model: String = DEFAULT_LIVE_MODEL,
        responseModality: String = "AUDIO",
        forceDirect: Boolean = false
    ): LiveSessionHandle? {
        val apiKey = resolveApiKey()
        // Hard lock: Gemini Live media stream is always direct-to-Google.
        val gatewayBaseUrl: String? = null
        val gatewayToken: String? = null
        val usingGatewayRoute = !gatewayBaseUrl.isNullOrBlank()
        if (apiKey.isNullOrBlank() && gatewayBaseUrl.isNullOrBlank()) {
            listener.onError("Gemini API key missing for Live session.")
            return null
        }
        val effectiveApiKey = apiKey?.takeIf { it.isNotBlank() } ?: GATEWAY_KEY_PLACEHOLDER
        val liveWsUrl = resolveLiveWebSocketUrl(gatewayBaseUrl)

        val requestBuilder = Request.Builder()
            .url("$liveWsUrl?key=$effectiveApiKey")
        if (usingGatewayRoute && !gatewayToken.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $gatewayToken")
            requestBuilder.addHeader("X-Clawd-Token", gatewayToken)
        }
        val request = requestBuilder.build()
        val requestedLiveModel = resolvePreferredLiveModel(model)
        val route = if (gatewayBaseUrl.isNullOrBlank()) "direct-google-live" else "gateway-live"
        Log.d(TAG, "Starting Gemini Live route=$route model=$requestedLiveModel")

        val socket = wsClient.newWebSocket(request, object : WebSocketListener() {
            private var setupReady = false
            private var setupSent = false
            private var gatewayAuthComplete = !usingGatewayRoute
            private var gatewayConnectRequestId: String? = null

            private fun notifySetupReady() {
                if (setupReady) return
                setupReady = true
                listener.onSessionReady()
            }

            private fun sendGatewayConnect(webSocket: WebSocket, nonce: String?, challengeTs: Long?): Boolean {
                if (gatewayToken.isNullOrBlank()) {
                    listener.onError("Gateway token missing for Live session.")
                    return false
                }
                if (!gatewayConnectRequestId.isNullOrBlank()) {
                    return true
                }

                val reqId = "connect-" + System.currentTimeMillis()
                val auth = JSONObject().put("token", gatewayToken)
                val params = JSONObject()
                    .put("minProtocol", 3)
                    .put("maxProtocol", 3)
                    .put(
                        "client",
                        JSONObject()
                            .put("id", "clawdbot-android")
                            .put("version", BuildConfig.VERSION_NAME)
                            .put("platform", "android")
                            .put("mode", "webchat")
                            .put("instanceId", "rayneo-x3")
                    )
                    .put("role", "operator")
                    .put(
                        "scopes",
                        JSONArray()
                            .put("operator.admin")
                            .put("operator.approvals")
                            .put("operator.pairing")
                    )
                    .put("caps", JSONArray())
                    .put("auth", auth)
                    .put("userAgent", "TapClawX3/" + BuildConfig.VERSION_NAME)
                    .put("locale", java.util.Locale.getDefault().toLanguageTag())

                val frame = JSONObject()
                    .put("type", "req")
                    .put("id", reqId)
                    .put("method", "connect")
                    .put("params", params)

                val sent = webSocket.send(frame.toString())
                if (!sent) {
                    listener.onError("Failed to send gateway connect request.")
                } else {
                    gatewayConnectRequestId = reqId
                    Log.d(TAG, "Gateway connect request sent id=" + reqId)
                }
                return sent
            }

            private fun sendSetup(webSocket: WebSocket): Boolean {
                if (setupSent) return true
                if (usingGatewayRoute && !gatewayAuthComplete) return false
                val modelId =
                    if (requestedLiveModel.startsWith("models/")) {
                        requestedLiveModel
                    } else {
                        "models/$requestedLiveModel"
                    }
                // Build effective system prompt: full override OR modular sections + personality.
                val effectivePrompt = buildString {
                    val custom = customSystemPromptProvider()?.trim().orEmpty()
                    if (custom.isNotBlank()) {
                        // Full override for power users
                        append(custom)
                    } else {
                        // Build from editable sections — blank = use default
                        append(identityProvider()?.takeIf { it.isNotBlank() } ?: DEFAULT_IDENTITY)
                        append("\n\n")
                        append(DEFAULT_CAPABILITIES)
                        append("\n\n")
                        append(routingRulesProvider()?.takeIf { it.isNotBlank() } ?: DEFAULT_ROUTING_RULES)
                        append("\n\n")
                        append(behaviorProvider()?.takeIf { it.isNotBlank() } ?: DEFAULT_BEHAVIOR)
                        append("\n\n")
                        append(urlRulesProvider()?.takeIf { it.isNotBlank() } ?: DEFAULT_URL_RULES)
                    }
                    // Inject current device location so Gemini knows where the user is
                    val locationCtx = locationContextProvider()?.trim().orEmpty()
                    if (locationCtx.isNotBlank()) {
                        append("\n\nCURRENT LOCATION:\n")
                        append(locationCtx)
                    }
                    val personality = personalityProvider()?.trim().orEmpty()
                    if (personality.isNotBlank()) {
                        append("\n\nPERSONALITY:\n")
                        append(personality)
                    }
                }
                val setup = JSONObject().put(
                    "setup",
                    JSONObject()
                        .put("model", modelId)
                        .put(
                            "systemInstruction",
                            JSONObject().put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put("text", effectivePrompt)
                                )
                            )
                        )
                        .put("generationConfig", JSONObject().put(
                            "responseModalities",
                            JSONArray().put(responseModality)
                        ))
                        .put("inputAudioTranscription", JSONObject())
                        .put("outputAudioTranscription", JSONObject())
                        .put("tools", JSONArray().put(
                            JSONObject().put("functionDeclarations", buildAiTapToolDeclarations())
                        ))
                )
                Log.d(TAG, "Gemini Live setup payload: ${setup.toString().take(400)}")
                val sent = webSocket.send(setup.toString())
                if (!sent) {
                    listener.onError("Failed to send Gemini Live setup message.")
                    return false
                }
                setupSent = true
                return true
            }

            private fun handleLiveMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text)
                    val eventType = root.optString("type", "").trim()
                    val eventName = root.optString("event", "").trim()

                    if (usingGatewayRoute) {
                        val isChallenge =
                            eventType.equals("event", ignoreCase = true) &&
                                eventName.equals("connect.challenge", ignoreCase = true)
                        if (isChallenge) {
                            val payload = root.optJSONObject("payload")
                            val nonce = payload?.optString("nonce")
                            val challengeTs = payload?.optLong("ts")?.takeIf { it > 0L }
                            sendGatewayConnect(
                                webSocket = webSocket,
                                nonce = nonce,
                                challengeTs = challengeTs
                            )
                            return@runCatching
                        }

                        val isConnectResponse = eventType.equals("res", ignoreCase = true)
                        if (isConnectResponse) {
                            val responseId = root.optString("id", "").trim()
                            val pendingId = gatewayConnectRequestId
                            if (!pendingId.isNullOrBlank() && responseId == pendingId) {
                                gatewayConnectRequestId = null
                                val ok = root.optBoolean("ok", false)
                                if (ok) {
                                    gatewayAuthComplete = true
                                    Log.d(TAG, "Gateway connect accepted")
                                    val methods = root.optJSONObject("payload")
                                        ?.optJSONObject("features")
                                        ?.optJSONArray("methods")
                                    if (methods != null) {
                                        val list = mutableListOf<String>()
                                        for (i in 0 until methods.length()) {
                                            val name = methods.optString(i).trim()
                                            if (name.isNotBlank()) list.add(name)
                                        }
                                        Log.d(TAG, "Gateway methods: " + list.joinToString(","))
                                    }
                                    sendSetup(webSocket)
                                } else {
                                    val errMsg =
                                        root.optJSONObject("error")?.optString("message")
                                            ?.takeIf { it.isNotBlank() }
                                            ?: "Gateway connect rejected"
                                    listener.onError(errMsg)
                                }
                                return@runCatching
                            }
                        }

                        val authSucceeded =
                            eventType.equals("auth_success", ignoreCase = true) ||
                                eventName.equals("auth_success", ignoreCase = true) ||
                                root.optBoolean("auth_success", false) ||
                                root.optBoolean("authenticated", false)
                        if (authSucceeded) {
                            gatewayAuthComplete = true
                            sendSetup(webSocket)
                            return@runCatching
                        }

                        val authFailed =
                            eventType.equals("auth_failed", ignoreCase = true) ||
                                eventName.equals("auth_failed", ignoreCase = true) ||
                                eventName.equals("connect.denied", ignoreCase = true) ||
                                root.optBoolean("auth_failed", false)
                        if (authFailed) {
                            listener.onError("Gateway authentication failed.")
                            return@runCatching
                        }
                    }

                    val error = root.optJSONObject("error")
                    if (error != null) {
                        val msg = error.optString("message", "Gemini Live returned an error.")
                        listener.onError(msg)
                        return@runCatching
                    }

                    if (root.has("setupComplete") ||
                        root.has("setup_complete") ||
                        root.has("setupcomplete")
                    ) {
                        notifySetupReady()
                    }

                    val serverContent = root.optJSONObject("serverContent")
                        ?: root.optJSONObject("server_content")
                    if (serverContent != null) {
                        notifySetupReady()
                        val inputTx = (serverContent
                            .optJSONObject("inputTranscription")
                            ?: serverContent.optJSONObject("input_transcription"))
                            ?.optString("text", "")
                            .orEmpty()
                            .trim()
                        if (inputTx.isNotBlank()) {
                            listener.onInputTranscription(inputTx)
                        }

                        val outputTx = (serverContent
                            .optJSONObject("outputTranscription")
                            ?: serverContent.optJSONObject("output_transcription"))
                            ?.optString("text", "")
                            .orEmpty()
                            .trim()
                        if (outputTx.isNotBlank()) {
                            listener.onOutputTranscription(outputTx)
                        }

                        val parts = (serverContent
                            .optJSONObject("modelTurn")
                            ?: serverContent.optJSONObject("model_turn"))
                            ?.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val part = parts.optJSONObject(i) ?: continue
                                val textPart = part.optString("text", "").trim()
                                if (textPart.isNotBlank()) {
                                    listener.onModelText(textPart)
                                }

                                val inlineData = part.optJSONObject("inlineData")
                                    ?: part.optJSONObject("inline_data")
                                if (inlineData != null) {
                                    val mime = inlineData.optString("mimeType", "")
                                    val encoded = inlineData.optString("data", "")
                                    if (mime.startsWith("audio/") && encoded.isNotBlank()) {
                                        val audioBytes = Base64.getDecoder().decode(encoded)
                                        listener.onModelAudio(mime, audioBytes)
                                    }
                                }
                            }
                        }

                        val finishReason = sequenceOf(
                            serverContent.optString("finishReason", ""),
                            serverContent.optString("finish_reason", ""),
                            root.optString("finishReason", ""),
                            root.optString("finish_reason", "")
                        ).map { it.trim() }.firstOrNull { it.isNotBlank() }

                        if (serverContent.optBoolean("turnComplete", false) ||
                            serverContent.optBoolean("turn_complete", false) ||
                            serverContent.optBoolean("generationComplete", false) ||
                            serverContent.optBoolean("generation_complete", false) ||
                            finishReason.equals("STOP", ignoreCase = true)
                        ) {
                            listener.onTurnComplete(finishReason)
                        }
                    }

                    val toolCall = root.optJSONObject("toolCall")
                        ?: root.optJSONObject("tool_call")
                    if (toolCall != null) {
                        val functionCalls = toolCall.optJSONArray("functionCalls")
                            ?: toolCall.optJSONArray("function_calls")
                        if (functionCalls != null) {
                            for (i in 0 until functionCalls.length()) {
                                val call = functionCalls.optJSONObject(i) ?: continue
                                val functionCall = call.optJSONObject("functionCall")
                                    ?: call.optJSONObject("function_call")
                                val callId = sequenceOf(
                                    call.optString("id", "").trim(),
                                    call.optString("callId", "").trim(),
                                    functionCall?.optString("id", "")?.trim().orEmpty()
                                ).firstOrNull { it.isNotBlank() }
                                    ?: "tool-call-${System.currentTimeMillis()}-$i"
                                val name = sequenceOf(
                                    functionCall?.optString("name", "")?.trim().orEmpty(),
                                    call.optString("name", "").trim()
                                ).firstOrNull { it.isNotBlank() }.orEmpty()
                                val args = sequenceOf(
                                    functionCall?.optJSONObject("args")?.toString().orEmpty(),
                                    functionCall?.optString("args", "")?.trim().orEmpty(),
                                    call.optJSONObject("args")?.toString().orEmpty(),
                                    call.optString("args", "")?.trim().orEmpty()
                                ).firstOrNull { it.isNotBlank() }.orEmpty()
                                if (name.isNotBlank()) {
                                    listener.onToolCall(callId, name, args)
                                }
                            }
                        }
                    }
                }.onFailure {
                    listener.onError("Failed to parse Live response: ${it.message}")
                }
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Gemini Live websocket opened")
                if (usingGatewayRoute) {
                    Log.d(TAG, "Gateway live connected; awaiting connect.challenge")
                    return
                }
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Gemini Live inbound: ${text.take(600)}")
                handleLiveMessage(webSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(
                    TAG,
                    "Gemini Live inbound binary: size=${bytes.size} preview=${bytes.hex().take(64)}"
                )
                val decoded = runCatching { bytes.utf8() }.getOrNull()
                if (!decoded.isNullOrBlank()) {
                    val isAudioChunk = decoded.contains("\"inlineData\"") && decoded.contains("\"audio/")
                    if (isAudioChunk) {
                        Log.d(TAG, "Gemini Live inbound binary-decoded: audio chunk")
                    } else {
                        val preview = decoded
                            .replace('\n', ' ')
                            .replace('\r', ' ')
                            .take(260)
                        Log.d(TAG, "Gemini Live inbound binary-decoded: $preview")
                    }
                    handleLiveMessage(webSocket, decoded)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                val body = runCatching { response?.peekBody(1024)?.string() }.getOrNull()
                Log.e(TAG, "Gemini Live websocket failure code=$code body=$body", t)
                listener.onError("Gemini Live connection failed: ${t.message ?: "unknown error"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(code, reason)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Gemini Live websocket closing code=$code reason=$reason")
                webSocket.close(code, reason)
            }
        })
        return LiveSessionHandle(socket)
    }

    /**
     * Send a text prompt to Gemini.
     *
     * @param prompt   The user's natural-language query.
     * @param model    Gemini model identifier (default: gemini-flash-lite-latest).
     * @param systemInstruction Optional system-level instruction.
     * @return [GeminiResult] — never throws.
     */
    suspend fun sendPrompt(
        prompt: String,
        model: String = DEFAULT_MODEL,
        systemInstruction: String? = null
    ): GeminiResult = withContext(Dispatchers.IO) {
        // ── 1. Guard: API key ────────────────────────────────────────
        val apiKey = resolveApiKey()
        val gatewayBaseUrl = resolveGatewayBaseUrl()
        if (apiKey.isNullOrBlank() && gatewayBaseUrl.isNullOrBlank()) {
            Log.w(TAG, "Gemini API key is missing — returning ApiKeyMissing")
            return@withContext GeminiResult.ApiKeyMissing
        }
        val effectiveApiKey = apiKey?.takeIf { it.isNotBlank() } ?: GATEWAY_KEY_PLACEHOLDER

        try {
            // ── 2. Build request JSON ────────────────────────────────
            val contentsArray = JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(
                        JSONObject().put("text", prompt)
                    ))
                }
            )

            val requestBody = JSONObject().apply {
                put("contents", contentsArray)
                if (!systemInstruction.isNullOrBlank()) {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().put(
                            JSONObject().put("text", systemInstruction)
                        ))
                    })
                }
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 2048)
                    put("topP", 0.95)
                })
            }

            val requestedModel = resolvePreferredModel(model, DEFAULT_MODEL)
            val modelsToTry = buildModelFallbackList(requestedModel, audioPreferred = false)
            var lastError: GeminiResult.Error? = null
            for (candidateModel in modelsToTry) {
                val http = postGenerateContent(effectiveApiKey, candidateModel, requestBody)
                if (http.code in 200..299) {
                    val text = extractResponseText(http.body)
                    Log.d(TAG, "Gemini response OK (${text.length} chars, model=$candidateModel)")
                    return@withContext GeminiResult.Success(text = text, model = candidateModel)
                }

                Log.e(TAG, "Gemini HTTP ${http.code} model=$candidateModel: ${http.body}")
                if (isApiKeyError(http.code, http.body)) {
                    return@withContext GeminiResult.ApiKeyMissing
                }

                lastError = buildFriendlyError(
                    code = http.code,
                    errorBody = http.body,
                    defaultPrefix = "Gemini API error"
                )

                if (!shouldTryNextModel(http.code, http.body)) {
                    return@withContext lastError
                }
            }

            lastError ?: GeminiResult.Error("No compatible Gemini model available", 404)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini request failed", e)
            GeminiResult.Error(message = e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Send a multimodal prompt (text + base64 image).
     */
    suspend fun sendVisionPrompt(
        prompt: String,
        imageBase64: String,
        mimeType: String = "image/jpeg",
        model: String = DEFAULT_MODEL
    ): GeminiResult = withContext(Dispatchers.IO) {
        val apiKey = resolveApiKey()
        val gatewayBaseUrl = resolveGatewayBaseUrl()
        if (apiKey.isNullOrBlank() && gatewayBaseUrl.isNullOrBlank()) {
            Log.w(TAG, "Gemini API key missing for vision prompt")
            return@withContext GeminiResult.ApiKeyMissing
        }
        val effectiveApiKey = apiKey?.takeIf { it.isNotBlank() } ?: GATEWAY_KEY_PLACEHOLDER

        try {
            val parts = JSONArray().apply {
                put(JSONObject().put("text", prompt))
                put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", mimeType)
                        put("data", imageBase64)
                    })
                })
            }

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", parts)
                    }
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.4)
                    put("maxOutputTokens", 2048)
                })
            }

            val requestedModel = resolvePreferredModel(model, DEFAULT_MODEL)
            val modelsToTry = buildModelFallbackList(requestedModel, audioPreferred = false)
            var lastError: GeminiResult.Error? = null
            for (candidateModel in modelsToTry) {
                val http = postGenerateContent(effectiveApiKey, candidateModel, requestBody)
                if (http.code in 200..299) {
                    val text = extractResponseText(http.body)
                    return@withContext GeminiResult.Success(text = text, model = candidateModel)
                }

                Log.e(TAG, "Gemini vision HTTP ${http.code} model=$candidateModel: ${http.body}")
                if (isApiKeyError(http.code, http.body)) {
                    return@withContext GeminiResult.ApiKeyMissing
                }

                lastError = buildFriendlyError(
                    code = http.code,
                    errorBody = http.body,
                    defaultPrefix = "Vision API error"
                )
                if (!shouldTryNextModel(http.code, http.body)) {
                    return@withContext lastError
                }
            }

            lastError ?: GeminiResult.Error("No compatible Gemini vision model available", 404)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini vision request failed", e)
            GeminiResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Send an audio clip to Gemini and return a plain-text transcription.
     */
    suspend fun sendAudioTranscription(
        audioBase64: String,
        mimeType: String = "audio/mp4",
        model: String = AUDIO_MODEL
    ): GeminiResult = withContext(Dispatchers.IO) {
        val apiKey = resolveApiKey()
        val gatewayBaseUrl = resolveGatewayBaseUrl()
        if (apiKey.isNullOrBlank() && gatewayBaseUrl.isNullOrBlank()) {
            Log.w(TAG, "Gemini API key missing for audio transcription")
            return@withContext GeminiResult.ApiKeyMissing
        }
        val effectiveApiKey = apiKey?.takeIf { it.isNotBlank() } ?: GATEWAY_KEY_PLACEHOLDER

        try {
            val parts = JSONArray().apply {
                put(JSONObject().put(
                    "text",
                    "Transcribe this spoken request exactly. Return only the transcript text."
                ))
                put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", mimeType)
                        put("data", audioBase64)
                    })
                })
            }

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", parts)
                    }
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 256)
                })
            }

            val requestedModel = resolvePreferredModel(model, AUDIO_MODEL)
            val modelsToTry = buildModelFallbackList(requestedModel, audioPreferred = true)
            var lastError: GeminiResult.Error? = null
            for (candidateModel in modelsToTry) {
                val http = postGenerateContent(effectiveApiKey, candidateModel, requestBody)
                if (http.code in 200..299) {
                    val text = extractResponseText(http.body).trim()
                    return@withContext GeminiResult.Success(text = text, model = candidateModel)
                }

                Log.e(TAG, "Gemini audio HTTP ${http.code} model=$candidateModel: ${http.body}")
                if (isApiKeyError(http.code, http.body)) {
                    return@withContext GeminiResult.ApiKeyMissing
                }

                lastError = buildFriendlyError(
                    code = http.code,
                    errorBody = http.body,
                    defaultPrefix = "Audio transcription API error"
                )
                if (!shouldTryNextModel(http.code, http.body)) {
                    return@withContext lastError
                }
            }

            lastError ?: GeminiResult.Error("No compatible Gemini audio model available", 404)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini audio transcription failed", e)
            GeminiResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    private data class HttpResponse(val code: Int, val body: String)

    private fun postGenerateContent(
        apiKey: String,
        model: String,
        requestBody: JSONObject
    ): HttpResponse {
        val gatewayBase = resolveGatewayBaseUrl()?.let { "$it/v1beta/models" }
        val canFallbackToDirect = apiKey != GATEWAY_KEY_PLACEHOLDER

        if (!gatewayBase.isNullOrBlank()) {
            val gatewayUrl = "$gatewayBase/$model:generateContent?key=$apiKey"
            val gatewayAttempt = runCatching {
                postGenerateContentToUrl(gatewayUrl, requestBody)
            }
            if (gatewayAttempt.isSuccess) {
                val gatewayResponse = gatewayAttempt.getOrThrow()
                if (gatewayResponse.code in 200..299 || !canFallbackToDirect) {
                    return gatewayResponse
                }
                Log.w(TAG, "Gateway HTTP ${gatewayResponse.code}; falling back to direct Gemini")
            } else {
                val error = gatewayAttempt.exceptionOrNull()
                Log.w(
                    TAG,
                    "Gateway request failed (${error?.javaClass?.simpleName}: ${error?.message}); falling back to direct Gemini"
                )
                if (!canFallbackToDirect) {
                    throw error ?: IllegalStateException("Gateway request failed")
                }
            }
        }

        val directUrl = "$BASE_URL/$model:generateContent?key=$apiKey"
        return postGenerateContentToUrl(directUrl, requestBody)
    }

    private fun postGenerateContentToUrl(
        url: String,
        requestBody: JSONObject
    ): HttpResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
        }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(requestBody.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) {
            conn.inputStream
        } else {
            conn.errorStream ?: conn.inputStream
        }
        val body = BufferedReader(
            InputStreamReader(stream, Charsets.UTF_8)
        ).use { it.readText() }
        return HttpResponse(responseCode, body)
    }
    private fun extractResponseText(body: String): String {
        val json = JSONObject(body)
        return json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text", "")
            ?: ""
    }

    private fun isApiKeyError(code: Int, errorBody: String): Boolean {
        if (code != 400 && code != 403) return false
        val lowerErr = errorBody.lowercase()
        return lowerErr.contains("api_key") ||
            lowerErr.contains("api key") ||
            lowerErr.contains("invalid key") ||
            lowerErr.contains("permission denied")
    }

    private fun shouldTryNextModel(code: Int, errorBody: String): Boolean {
        if (code == 429) return true
        if (code == 404) return true
        val lower = errorBody.lowercase()
        return lower.contains("is not found") ||
            lower.contains("model not found") ||
            lower.contains("not supported for generatecontent")
    }

    private fun buildFriendlyError(
        code: Int,
        errorBody: String,
        defaultPrefix: String
    ): GeminiResult.Error {
        val lower = errorBody.lowercase()
        if (code == 429 && (
                lower.contains("resource_exhausted") ||
                    lower.contains("quota exceeded") ||
                    lower.contains("limit: 0")
                )
        ) {
            return GeminiResult.Error(
                message = "Gemini quota exhausted. Free tier: 10 req/min for 2.5-flash. " +
                    "Check your key at ai.google.dev or wait a minute.",
                code = code
            )
        }
        if (code == 404 && (
                lower.contains("is not found") ||
                    lower.contains("not supported for generatecontent")
                )
        ) {
            return GeminiResult.Error(
                message = "Requested Gemini model is unavailable for this API key/project.",
                code = code
            )
        }
        return GeminiResult.Error("$defaultPrefix ($code)", code)
    }

    private fun buildModelFallbackList(model: String, audioPreferred: Boolean): List<String> {
        // All candidates must be free-tier models with active quota.
        // Order: requested model → 3 flash → 2.5 flash → 2.5 pro (last resort).
        // Gemini 2.0 and 1.5 variants are deprecated.
        val fallbacks = listOf(
            model,
            "gemini-3-flash-preview",
            "gemini-2.5-flash",
            "gemini-2.5-pro"
        )
        return fallbacks.distinct()
    }

    /** Build the AITap native tool declarations for Gemini Live setup. */
    private fun buildAiTapToolDeclarations(): JSONArray {
            val tools = JSONArray()

            // google_calendar
            tools.put(JSONObject()
                .put("name", "google_calendar")
                .put("description", "Query or create Google Calendar events across ALL user calendars. Use 'query' to check upcoming events, 'create' to add a new event. Searches all enabled calendars automatically.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: 'query' to list upcoming events, or 'create' to add a new event."))
                        .put("query", JSONObject().put("type", "STRING")
                            .put("description", "Natural language calendar query (e.g. 'events today'). Used with 'query' action."))
                        .put("hours", JSONObject().put("type", "STRING")
                            .put("description", "Hours ahead to look for events (default 48). For 'today' use 24, for 'tomorrow' use 48, for 'this week' use 168. Used with 'query' action."))
                        .put("title", JSONObject().put("type", "STRING")
                            .put("description", "Event title. Required for 'create' action."))
                        .put("start_time", JSONObject().put("type", "STRING")
                            .put("description", "ISO 8601 start time (e.g. '2025-06-15T14:00:00'). Required for 'create' action."))
                        .put("duration_minutes", JSONObject().put("type", "STRING")
                            .put("description", "Event duration in minutes (default 60). Used with 'create' action."))
                        .put("location", JSONObject().put("type", "STRING")
                            .put("description", "Event location. Optional, used with 'create' action."))
                        .put("description", JSONObject().put("type", "STRING")
                            .put("description", "Event description. Optional, used with 'create' action.")))
                    .put("required", JSONArray().put("action"))))

            // google_keep (local notes — Google Keep API is restricted)
            tools.put(JSONObject()
                .put("name", "google_keep")
                .put("description", "Create, append to, or list notes stored locally on the glasses. Notes persist across restarts. Use 'create' for new notes, 'append' to add to existing, 'list' to show recent notes.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: 'create' for new note, 'append' to add to existing note by title, 'list' to show recent notes."))
                        .put("title", JSONObject().put("type", "STRING")
                            .put("description", "Note title. Required for create/append."))
                        .put("content", JSONObject().put("type", "STRING")
                            .put("description", "Note content text. Required for create/append.")))
                    .put("required", JSONArray().put("action"))))

            // google_contacts
            tools.put(JSONObject()
                .put("name", "google_contacts")
                .put("description", "Look up contacts to get phone numbers or emails.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: search or get."))
                        .put("name", JSONObject().put("type", "STRING")
                            .put("description", "Contact name to search for.")))
                    .put("required", JSONArray().put("action").put("name"))))

            // google_routes
            tools.put(JSONObject()
                .put("name", "google_routes")
                .put("description", "Get directions, traffic conditions, commute time, ETAs, and route planning between locations. Call this for ANY question about traffic, how long a drive takes, or getting somewhere. IMPORTANT: If the user specifies a starting address (e.g. 'from 123 Main St to 456 Oak Ave'), pass it as 'origin'. If not specified, use 'current' to use GPS.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("origin", JSONObject().put("type", "STRING")
                            .put("description", "Starting location. Pass the user's spoken starting address if they provide one (e.g. 'from 123 Main St'). Use 'current' for current GPS location. Defaults to 'current' if not provided."))
                        .put("destination", JSONObject().put("type", "STRING")
                            .put("description", "Destination address or place name."))
                        .put("mode", JSONObject().put("type", "STRING")
                            .put("description", "Travel mode: driving, transit, walking, or bicycling.")))
                    .put("required", JSONArray().put("destination"))))

            // spotify_player
            tools.put(JSONObject()
                .put("name", "spotify_player")
                .put("description", "Control Spotify music playback: play, pause, skip, search, save.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: play, pause, next, previous, save, search."))
                        .put("query", JSONObject().put("type", "STRING")
                            .put("description", "Search query or song/artist/playlist name.")))
                    .put("required", JSONArray().put("action"))))

            // sonos_control
            tools.put(JSONObject()
                .put("name", "sonos_control")
                .put("description", "Control Sonos home speakers: play, pause, volume, group rooms.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: play, pause, volume, group."))
                        .put("room", JSONObject().put("type", "STRING")
                            .put("description", "Room or speaker name."))
                        .put("volume", JSONObject().put("type", "STRING")
                            .put("description", "Volume level (0-100).")))
                    .put("required", JSONArray().put("action"))))

            // send_message
            tools.put(JSONObject()
                .put("name", "send_message")
                .put("description", "Send an SMS or text message to a contact or phone number.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("recipient", JSONObject().put("type", "STRING")
                            .put("description", "Contact name or phone number."))
                        .put("message", JSONObject().put("type", "STRING")
                            .put("description", "Message text to send.")))
                    .put("required", JSONArray().put("recipient").put("message"))))

            // place_call
            tools.put(JSONObject()
                .put("name", "place_call")
                .put("description", "Initiate a phone call to a contact or phone number.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("recipient", JSONObject().put("type", "STRING")
                            .put("description", "Contact name or phone number to call.")))
                    .put("required", JSONArray().put("recipient"))))

            // camera_action
            tools.put(JSONObject()
                .put("name", "camera_action")
                .put("description", "Save a photo from the camera, trigger QR scan, or start/stop audio recording.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: save_photo, read_qr, start_recording, stop_recording."))
                        .put("title", JSONObject().put("type", "STRING")
                            .put("description", "Optional title or label for the saved item.")))
                    .put("required", JSONArray().put("action"))))

            // open_taplink
            tools.put(JSONObject()
                .put("name", "open_taplink")
                .put("description", "Open a URL in the TapBrowser AR web viewer.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("url", JSONObject().put("type", "STRING")
                            .put("description", "The URL to open.")))
                    .put("required", JSONArray().put("url"))))

            tools.put(JSONObject()
                .put("name", "research_topic")
                .put("description", "Use the configured research API to generate a detailed research brief on a topic. Call this for 'research', 'deep dive', or 'analyze' requests instead of opening the browser.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("topic", JSONObject().put("type", "STRING")
                            .put("description", "The topic to research in depth.")))
                    .put("required", JSONArray().put("topic"))))

            tools.put(JSONObject()
                .put("name", "daily_briefing")
                .put("description", "Generate the user's full daily brief for today using calendar, GPS proximity, Bay Area public events, traffic, parking, weather, and AQI. Only call this when the user explicitly asks for a daily briefing by name, not for ordinary calendar or nearby-event questions.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("focus", JSONObject().put("type", "STRING")
                            .put("description", "Optional focus hint such as 'today' or 'morning'.")))))

            // get_context
            tools.put(JSONObject()
                .put("name", "get_context")
                .put("description", "Recall information from the cached conversation context and recent interactions.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("query", JSONObject().put("type", "STRING")
                            .put("description", "What to recall from context."))
                        .put("time_range", JSONObject().put("type", "STRING")
                            .put("description", "Optional time range filter (e.g. 'last hour', 'today').")))
                    .put("required", JSONArray().put("query"))))

            // google_tasks
            tools.put(JSONObject()
                .put("name", "google_tasks")
                .put("description", "Query, create, or complete Google Tasks (todos/reminders). Use 'query' to list pending tasks, 'create' to add a new task, 'complete' to mark a task done.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: 'query' to list tasks, 'create' to add a task, 'complete' to mark done."))
                        .put("title", JSONObject().put("type", "STRING")
                            .put("description", "Task title. Required for 'create' action."))
                        .put("notes", JSONObject().put("type", "STRING")
                            .put("description", "Task notes/details. Optional, used with 'create'."))
                        .put("due_date", JSONObject().put("type", "STRING")
                            .put("description", "Due date in RFC 3339 format (e.g. '2025-06-15T00:00:00.000Z'). Optional."))
                        .put("task_id", JSONObject().put("type", "STRING")
                            .put("description", "Task ID. Required for 'complete' action."))
                        .put("count", JSONObject().put("type", "STRING")
                            .put("description", "Max tasks to return (default 10). Used with 'query'.")))
                    .put("required", JSONArray().put("action"))))

            // google_news
            tools.put(JSONObject()
                .put("name", "google_news")
                .put("description", "Fetch top news headlines from Google News. Use to answer questions about current events or show news on the HUD.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: 'query' to fetch headlines."))
                        .put("count", JSONObject().put("type", "STRING")
                            .put("description", "Number of headlines to fetch (default 5).")))
                    .put("required", JSONArray().put("action"))))

            // google_places
            tools.put(JSONObject()
                .put("name", "google_places")
                .put("description", "Find nearby businesses, restaurants, cafes, gas stations, pharmacies, and more. Returns places with ratings, open/closed status, addresses, ETA context, and a Maps URL. When the closest result is closed, prefer the nearest open option.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("type", JSONObject().put("type", "STRING")
                            .put("description", "Place type: restaurant, cafe, gas_station, pharmacy, hospital, supermarket, bar, bakery, bank, parking, gym, lodging, etc."))
                        .put("query", JSONObject().put("type", "STRING")
                            .put("description", "Optional natural language query to help resolve type (e.g. 'tacos', 'sushi', 'urgent care')."))
                        .put("radius", JSONObject().put("type", "STRING")
                            .put("description", "Search radius in meters (default 1500, max 5000).")))
                    .put("required", JSONArray().put("type"))))

            // ask_maps — unified map intelligence
            tools.put(JSONObject()
                .put("name", "ask_maps")
                .put("description", "Explore places with AI-generated summaries, get 3D navigation with photorealistic views, " +
                    "find nearby landmarks, and get landmark-aware directions. Use this for questions like 'tell me about [place]', " +
                    "'navigate 3D to [destination]', 'what landmarks are nearby', or 'explore [location]'. " +
                    "Returns AI-generated place insights, ratings, hours, and 3D AR navigation links.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: 'explore' for AI-generated place summaries and details, " +
                                "'navigate_3d' to launch photorealistic 3D AR navigation, " +
                                "'landmark_directions' for turn-by-turn with landmark context, " +
                                "'nearby_landmarks' to discover notable places nearby."))
                        .put("query", JSONObject().put("type", "STRING")
                            .put("description", "Place name, address, or search query (e.g. 'Golden Gate Bridge', 'best sushi in SF')."))
                        .put("destination", JSONObject().put("type", "STRING")
                            .put("description", "Destination address for navigation actions."))
                        .put("place_id", JSONObject().put("type", "STRING")
                            .put("description", "Optional Google Place ID for direct lookup.")))
                    .put("required", JSONArray().put("action"))))

            // google_air_quality
            tools.put(JSONObject()
                .put("name", "google_air_quality")
                .put("description", "Get the current air quality index (AQI) and dominant pollutant for the user's current location.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("detail", JSONObject().put("type", "STRING")
                            .put("description", "Optional detail level, such as 'brief' or 'full'.")))))

            return tools
        }
}
