package com.rayneo.visionclaw.ui

import android.app.Application
import android.util.Log
import android.util.Patterns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.rayneo.visionclaw.BuildConfig
import com.rayneo.visionclaw.core.config.AppConfig
import com.rayneo.visionclaw.core.model.ChatMessage
import com.rayneo.visionclaw.core.network.GeminiRouter
import com.rayneo.visionclaw.core.network.GoogleCalendarClient
import com.rayneo.visionclaw.core.network.GoogleNewsClient
import com.rayneo.visionclaw.core.network.GoogleTasksClient
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.storage.AppPreferences
import com.rayneo.visionclaw.core.storage.db.ChatDatabase
import com.rayneo.visionclaw.core.storage.db.ChatMessageDao
import com.rayneo.visionclaw.core.storage.db.ChatMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * MainViewModel – central state holder for the AITap HUD.
 *
 * Responsibilities:
 *   • Coordinates API calls (Gemini, Calendar) and surfaces errors.
 *   • Emits [apiKeyRequired] when any API returns a missing-key result.
 *   • Manages chat messages, calendar summary, web navigation, and active panel.
 *   • Tool calls are dispatched by ToolDispatcher in MainActivity.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val MAX_ASSISTANT_CHAT_CARDS = 20
        private const val SESSION_ONLY_CHAT_LOG = true
        private const val HUD_CALENDAR_REFRESH_MIN_INTERVAL_MS = 60_000L
        const val PANEL_CHAT = 0
        const val PANEL_WEB = 1
    }

    // ── Preferences ───────────────────────────────────────────────────────
    private val prefs = AppPreferences(application)
    val preferences: AppPreferences get() = prefs
    val appConfig = AppConfig.load(application)
    private val chatMessageDao: ChatMessageDao =
        ChatDatabase.getInstance(application).chatMessageDao()
    private val chatHistoryMutex = Mutex()

    // ── Network clients ──────────────────────────────────────────────────
    val geminiRouter = GeminiRouter(
        apiKeyProvider = {
            // Priority: SharedPreferences (companion app) > config.json > BuildConfig
            prefs.geminiApiKey.takeIf { it.isNotBlank() }
                ?: appConfig.apiKeys.geminiKey.trim().takeIf {
                    it.isNotBlank() && !it.equals("YOUR_KEY_HERE", ignoreCase = true)
                }
                ?: BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }
        },
        preferredModelProvider = {
            // Priority: SharedPreferences (companion app) > config.json
            prefs.geminiModelOverride.trim().takeIf { it.isNotBlank() }
                ?: appConfig.apiKeys.geminiModel.trim().takeIf { it.isNotBlank() }
        },
        personalityProvider = {
            prefs.personality.takeIf { it.isNotBlank() }
        },
        customSystemPromptProvider = {
            prefs.customSystemPrompt.takeIf { it.isNotBlank() }
        },
        identityProvider = {
            prefs.promptIdentity.takeIf { it.isNotBlank() }
        },
        routingRulesProvider = {
            prefs.promptRoutingRules.takeIf { it.isNotBlank() }
        },
        behaviorProvider = {
            prefs.promptBehavior.takeIf { it.isNotBlank() }
        },
        urlRulesProvider = {
            prefs.promptUrlRules.takeIf { it.isNotBlank() }
        },
        locationContextProvider = {
            latestDeviceLocationContext?.let { loc ->
                val ageSeconds = (System.currentTimeMillis() - loc.timestampMs) / 1000
                val fresh = if (ageSeconds < 300) "current" else "${ageSeconds / 60}min ago"
                buildString {
                    append("The user is at latitude ${loc.latitude}, longitude ${loc.longitude}")
                    append(" (accuracy: ${loc.accuracyMeters?.toInt() ?: "unknown"}m, $fresh).")
                    append(" Use this for google_places nearby searches and google_routes origin.")
                    append(" When the user asks 'where am I', use these coordinates to describe their location.")
                }
            }
        }
    )
    var calendarClient = GoogleCalendarClient(apiKeyProvider = { prefs.calendarApiKey })
        private set

    /** Replace the default calendar client with one that supports OAuth. */
    fun setCalendarClient(client: GoogleCalendarClient) {
        calendarClient = client
    }

    var tasksClient: GoogleTasksClient? = null
        private set

    fun setTasksClient(client: GoogleTasksClient) {
        tasksClient = client
    }

    private val newsClient = GoogleNewsClient()

    @Volatile
    private var latestDeviceLocationContext: DeviceLocationContext? = null
    @Volatile
    private var lastHudCalendarRefreshMs = 0L
    @Volatile
    private var lastHudTasksRefreshMs = 0L
    @Volatile
    private var lastHudNewsRefreshMs = 0L

    // ── Active panel ─────────────────────────────────────────────────────
    private val _activePanelIndex = MutableLiveData(PANEL_CHAT)
    val activePanelIndex: LiveData<Int> = _activePanelIndex

    fun setActivePanel(index: Int) {
        _activePanelIndex.value = if (index == PANEL_WEB) PANEL_WEB else PANEL_CHAT
    }

    // ── Chat messages (StateFlow for coroutine collection) ───────────────
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private var liveAssistantWorkingTurn: String = ""
    private var liveAssistantWorkingCardIndex = -1
    @Volatile private var historyHydrated = false

    init {
        if (SESSION_ONLY_CHAT_LOG) {
            _messages.value = emptyList()
            historyHydrated = true
            purgePersistedAssistantHistoryAsync()
        } else {
            hydrateAssistantHistoryBlocking()
        }
        // Fetch HUD data on startup.
        refreshHudUpcomingCalendar(force = true)
        refreshHudTasks(force = true)
        refreshHudNews(force = true)
    }

    fun hydrateAssistantHistory() {
        if (SESSION_ONLY_CHAT_LOG) {
            if (!historyHydrated) {
                _messages.value = emptyList()
                historyHydrated = true
            }
            return
        }
        if (historyHydrated) return
        viewModelScope.launch {
            val restored = loadPersistedAssistantMessages()
            _messages.value = restored
            historyHydrated = true
        }
    }

    fun getAssistantCardsSnapshot(): List<ChatMessage> {
        return _messages.value
            .filterNot { it.fromUser }
            .let { cards ->
                if (cards.size > MAX_ASSISTANT_CHAT_CARDS) {
                    cards.takeLast(MAX_ASSISTANT_CHAT_CARDS)
                } else {
                    cards
                }
            }
    }

    // ── Calendar summary (StateFlow for HUD display) ─────────────────────
    private val _calendarSummary = MutableStateFlow("")
    val calendarSummary: StateFlow<String> = _calendarSummary.asStateFlow()

    // ── Tasks summary (StateFlow for HUD display) ─────────────────────────
    private val _tasksSummary = MutableStateFlow("")
    val tasksSummary: StateFlow<String> = _tasksSummary.asStateFlow()

    // ── News summary (StateFlow for HUD display) ──────────────────────────
    private val _newsSummary = MutableStateFlow("")
    val newsSummary: StateFlow<String> = _newsSummary.asStateFlow()

    // ── API Key Required notification ────────────────────────────────────
    private val _apiKeyRequired = MutableLiveData<String?>()
    val apiKeyRequired: LiveData<String?> = _apiKeyRequired

    fun clearApiKeyRequired() {
        _apiKeyRequired.value = null
    }

    /** Central handler for missing API key — shows HUD notification without panel switching. */
    fun onApiKeyMissing(serviceName: String) {
        Log.w(TAG, "API key missing for: $serviceName")
        _apiKeyRequired.postValue("API Key Required ($serviceName)")
    }

    // ── Web navigation ───────────────────────────────────────────────────
    private val _webNavigationUrl = MutableLiveData<String?>()
    val webNavigationUrl: LiveData<String?> = _webNavigationUrl

    fun navigateWeb(url: String) {
        _webNavigationUrl.value = url
        _activePanelIndex.value = PANEL_WEB
    }

    fun clearWebNavigation() {
        _webNavigationUrl.value = null
    }

    /** Called by panel fragments to open a URL in the web panel. */
    fun openUrl(url: String) {
        navigateWeb(url)
    }

    fun updateDeviceLocationContext(context: DeviceLocationContext) {
        latestDeviceLocationContext = context
    }

    fun clearDeviceLocationContext() {
        latestDeviceLocationContext = null
    }

    fun getDeviceLocationContext(): DeviceLocationContext? {
        return latestDeviceLocationContext
    }

    // ── Chat / Gemini ────────────────────────────────────────────────────
    private val _chatResponse = MutableLiveData<String?>()
    val chatResponse: LiveData<String?> = _chatResponse

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    /**
     * Called by ChatPanelFragment when the user submits text input.
     * Adds user message, sends to Gemini, and appends the response.
     * Sends the input to Gemini and adds the result to chat.
     */
    fun submitChatInput(text: String) {
        viewModelScope.launch {
            _isLoading.value = true
            when (val result = geminiRouter.sendPrompt(text)) {
                is GeminiRouter.GeminiResult.Success -> {
                    val rendered = ensureBottomRawUrls(sanitizeAssistantDisplayText(result.text))
                    val fullLog = appendAssistantInteraction(rendered)
                    _chatResponse.postValue(fullLog)
                }
                is GeminiRouter.GeminiResult.ApiKeyMissing -> {
                    onApiKeyMissing("Gemini")
                }
                is GeminiRouter.GeminiResult.Error -> {
                    val fullLog = appendAssistantInteraction("Error: ${result.message}")
                    _chatResponse.postValue(fullLog)
                    Log.e(TAG, "Gemini error: ${result.message}")
                }
            }
            _isLoading.postValue(false)
        }
    }

    fun sendChatMessage(message: String, systemPrompt: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            when (val result = geminiRouter.sendPrompt(message, systemInstruction = systemPrompt)) {
                is GeminiRouter.GeminiResult.Success -> {
                    val rendered = ensureBottomRawUrls(sanitizeAssistantDisplayText(result.text))
                    val fullLog = appendAssistantInteraction(rendered)
                    _chatResponse.postValue(fullLog)
                }
                is GeminiRouter.GeminiResult.ApiKeyMissing -> {
                    onApiKeyMissing("Gemini")
                }
                is GeminiRouter.GeminiResult.Error -> {
                    val fullLog = appendAssistantInteraction("Error: ${result.message}")
                    _chatResponse.postValue(fullLog)
                    Log.e(TAG, "Gemini error: ${result.message}")
                }
            }
            _isLoading.postValue(false)
        }
    }

    /**
     * Appends a user message generated by the Gemini Live session.
     */
    fun appendLiveUserTranscript(text: String) {
        // Privacy requirement: never render user STT/input transcription in chat.
    }

    /**
     * Appends assistant text generated by Gemini Live output transcription.
     * Tool-call responses are handled natively by ToolDispatcher.
     */
    fun appendLiveAssistantTranscript(text: String) {
        val safe = sanitizeAssistantDisplayText(text)
        if (safe.isBlank()) return
        val rendered = ensureBottomRawUrls(safe)
        val fullLog = finalizeAssistantLiveTurn(rendered)
        _chatResponse.postValue(fullLog)
    }

    /**
     * Persist streaming Gemini output directly in chat. This keeps the chat log live-updated
     * without transient overlays while Gemini is still generating a turn.
     */
    fun appendLiveAssistantStreamChunk(text: String) {
        val safe = sanitizeAssistantDisplayText(text)
        if (safe.isBlank()) return
        val fullLog = appendLiveAssistantWorkingChunk(safe)
        _chatResponse.postValue(fullLog)
    }

    fun commitLiveAssistantStreamIfNeeded() {
        val live = liveAssistantWorkingTurn.trim()
        val hasWorkingCard = findLiveAssistantWorkingIndex(_messages.value) >= 0
        if (live.isBlank() && !hasWorkingCard) return

        if (live.isNotBlank() && !hasWorkingCard) {
            appendAssistantInteraction(live)
            return
        }
        clearLiveAssistantWorkingCard(commitIfPopulated = true)
    }

    fun resetLiveAssistantStream() {
        commitLiveAssistantStreamIfNeeded()
        liveAssistantWorkingTurn = ""
    }

    private fun sanitizeAssistantDisplayText(text: String): String {
        return text
            .lineSequence()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("thought:", ignoreCase = true) ||
                    t.startsWith("reasoning:", ignoreCase = true) ||
                    t.startsWith("<thinking>", ignoreCase = true) ||
                    t.startsWith("</thinking>", ignoreCase = true)
            }
            .joinToString("\n")
            .trim()
    }

    private fun ensureBottomRawUrls(text: String): String {
        val base = text.trim()
        if (base.isBlank()) return base

        val urls = LinkedHashSet<String>()
        val matcher = Patterns.WEB_URL.matcher(base)
        while (matcher.find()) {
            val raw = matcher.group().orEmpty().trim().trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?')
            if (raw.isBlank()) continue
            val display = normalizeUrl(raw)
            urls.add(display)
        }
        if (urls.isEmpty()) return base

        val urlBlock = urls.joinToString("\n")
        if (base.endsWith(urlBlock)) return base
        return "$base\n\n$urlBlock"
    }

    private fun extractFirstUrl(text: String): String? {
        val matcher = Patterns.WEB_URL.matcher(text)
        if (!matcher.find()) return null
        val raw = matcher.group().orEmpty().trim().trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?')
        if (raw.isBlank()) return null
        return normalizeUrl(raw)
    }

    private fun normalizeUrl(raw: String): String {
        return if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else {
            "https://$raw"
        }
    }

    private fun mergeStreamText(existing: String, incoming: String): String {
        val prev = existing.trim()
        val next = incoming.trim()
        if (prev.isBlank()) return next
        if (next.isBlank()) return prev
        if (next.startsWith(prev)) return next
        if (prev.startsWith(next)) return prev
        if (next.contains(prev)) return next
        if (prev.contains(next)) return prev

        val maxOverlap = minOf(prev.length, next.length)
        for (n in maxOverlap downTo 1) {
            if (prev.endsWith(next.substring(0, n))) {
                return prev + next.substring(n)
            }
        }
        return "$prev $next"
    }

    fun sendVisionQuery(prompt: String, imageBase64: String) {
        viewModelScope.launch {
            _isLoading.value = true
            when (val result = geminiRouter.sendVisionPrompt(prompt, imageBase64)) {
                is GeminiRouter.GeminiResult.Success -> {
                    val rendered = ensureBottomRawUrls(sanitizeAssistantDisplayText(result.text))
                    val fullLog = appendAssistantInteraction(rendered)
                    _chatResponse.postValue(fullLog)
                }
                is GeminiRouter.GeminiResult.ApiKeyMissing -> {
                    onApiKeyMissing("Gemini")
                }
                is GeminiRouter.GeminiResult.Error -> {
                    val fullLog = appendAssistantInteraction("Error: ${result.message}")
                    _chatResponse.postValue(fullLog)
                }
            }
            _isLoading.postValue(false)
        }
    }

    /**
     * Full end-to-end routing pipeline with tool call support.
     * Sends text and optional frame to Gemini, parses tool calls,
     * Routes queries through Gemini with tool calling.
     */
    /**
     * AITap: Routes queries through Gemini with tool calling.
     * Tool calls are handled natively by ToolDispatcher in MainActivity.
     */
    fun routeWithToolCalls(text: String, frameBase64: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val geminiResult = if (frameBase64 != null) {
                    geminiRouter.sendVisionPrompt(text, frameBase64)
                } else {
                    geminiRouter.sendPrompt(text)
                }

                when (geminiResult) {
                    is GeminiRouter.GeminiResult.Success -> {
                        val rendered = ensureBottomRawUrls(sanitizeAssistantDisplayText(geminiResult.text))
                        val fullLog = appendAssistantInteraction(rendered)
                        _chatResponse.postValue(fullLog)
                    }
                    is GeminiRouter.GeminiResult.ApiKeyMissing -> {
                        onApiKeyMissing("Gemini")
                    }
                    is GeminiRouter.GeminiResult.Error -> {
                        val fullLog = appendAssistantInteraction("Error: ${geminiResult.message}")
                        _chatResponse.postValue(fullLog)
                        Log.e(TAG, "Gemini error: ${geminiResult.message}")
                    }
                }
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    /**
     * AITap: HUD calendar refresh. Placeholder until google_calendar tool is fully wired.
     */
    fun refreshHudUpcomingCalendar(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val intervalMs = prefs.hudRefreshIntervalSeconds * 1000L
        if (!force && (now - lastHudCalendarRefreshMs) < intervalMs) {
            return
        }
        lastHudCalendarRefreshMs = now
        // Use the real GoogleCalendarClient for live calendar data.
        fetchCalendarEvents()
    }

    private fun appendLiveAssistantWorkingChunk(chunk: String): String {
        val next = chunk.trim()
        if (next.isBlank()) return currentAssistantVisibleLog()
        liveAssistantWorkingTurn =
            if (liveAssistantWorkingTurn.isBlank()) {
                next
            } else {
                mergeStreamText(liveAssistantWorkingTurn, next)
            }
        upsertLiveAssistantWorkingCard(liveAssistantWorkingTurn)
        return currentAssistantVisibleLog()
    }

    private fun finalizeAssistantLiveTurn(finalChunk: String): String {
        val finalText = finalChunk.trim()
        val mergedTurn =
            when {
                liveAssistantWorkingTurn.isBlank() -> finalText
                finalText.isBlank() -> liveAssistantWorkingTurn
                else -> mergeStreamText(liveAssistantWorkingTurn, finalText)
            }.trim()
        liveAssistantWorkingTurn = ""
        if (mergedTurn.isBlank()) {
            clearLiveAssistantWorkingCard(commitIfPopulated = true)
            return currentAssistantVisibleLog()
        }
        return appendAssistantInteraction(mergedTurn)
    }

    private fun appendAssistantInteraction(interactionText: String): String {
        val entry = interactionText.trim()
        if (entry.isBlank()) return currentAssistantVisibleLog()

        val current = _messages.value.toMutableList()
        val workingIndex = findLiveAssistantWorkingIndex(current)
        if (workingIndex >= 0) {
            current[workingIndex] = current[workingIndex].copy(text = entry, fromUser = false)
        } else {
            current += ChatMessage(text = entry, fromUser = false)
        }

        _messages.value = current.takeLast(MAX_ASSISTANT_CHAT_CARDS)
        liveAssistantWorkingTurn = ""
        liveAssistantWorkingCardIndex = -1
        persistAssistantEntry(entry)
        return entry
    }

    private fun currentAssistantVisibleLog(): String {
        val live = liveAssistantWorkingTurn.trim()
        if (live.isNotBlank()) return live
        return _messages.value.lastOrNull { !it.fromUser }?.text.orEmpty()
    }

    private fun hydrateAssistantHistoryBlocking() {
        val restored = runCatching {
            runBlocking {
                chatHistoryMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val existing = chatMessageDao.getAllMessages()
                        if (existing.isEmpty()) {
                            migrateLegacyPrefsHistoryLocked()
                        }
                        chatMessageDao.getAllMessages()
                    }
                }
            }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to hydrate assistant history", error)
            emptyList()
        }
        _messages.value = restored.map { it.toModel() }
        historyHydrated = true
    }

    private fun purgePersistedAssistantHistoryAsync() {
        viewModelScope.launch {
            runCatching {
                chatHistoryMutex.withLock {
                    withContext(Dispatchers.IO) {
                        chatMessageDao.deleteAllMessages()
                    }
                }
                prefs.setAssistantCardHistory(emptyList())
            }.onFailure { error ->
                Log.w(TAG, "Failed to purge persisted assistant history", error)
            }
        }
    }

    private suspend fun loadPersistedAssistantMessages(): List<ChatMessage> {
        val restored = chatHistoryMutex.withLock {
            withContext(Dispatchers.IO) {
                val existing = chatMessageDao.getAllMessages()
                if (existing.isNotEmpty()) {
                    existing
                } else {
                    migrateLegacyPrefsHistoryLocked()
                    chatMessageDao.getAllMessages()
                }
            }
        }
        return restored.map { it.toModel() }
    }

    private suspend fun migrateLegacyPrefsHistoryLocked() {
        val legacy = prefs.getAssistantCardHistory()
            .sortedBy { it.timestampMs }
            .filter { it.text.isNotBlank() }
            .takeLast(MAX_ASSISTANT_CHAT_CARDS)
        if (legacy.isEmpty()) return

        legacy.forEach { card ->
            chatMessageDao.insertAndTrim(
                ChatMessageEntity(
                    text = card.text.trim(),
                    url = card.url ?: extractFirstUrl(card.text),
                    timestamp = card.timestampMs
                ),
                maxItems = MAX_ASSISTANT_CHAT_CARDS
            )
        }
    }

    private fun persistAssistantEntry(text: String) {
        if (SESSION_ONLY_CHAT_LOG) return
        val clean = text.trim()
        if (clean.isBlank()) return
        val timestamp = System.currentTimeMillis()
        val url = extractFirstUrl(clean)

        viewModelScope.launch {
            val restored = runCatching {
                chatHistoryMutex.withLock {
                    withContext(Dispatchers.IO) {
                        chatMessageDao.insertAndTrim(
                            ChatMessageEntity(
                                text = clean,
                                url = url,
                                timestamp = timestamp
                            ),
                            maxItems = MAX_ASSISTANT_CHAT_CARDS
                        )
                        chatMessageDao.getAllMessages()
                    }
                }
            }.getOrElse { error ->
                Log.e(TAG, "Failed to persist assistant entry", error)
                return@launch
            }

            val rebuilt = restored.map { it.toModel() }.toMutableList()
            val live = liveAssistantWorkingTurn.trim()
            if (live.isNotBlank()) {
                rebuilt += ChatMessage(text = live, fromUser = false)
                liveAssistantWorkingCardIndex = rebuilt.lastIndex
            }
            _messages.value = rebuilt
        }
    }

    private fun upsertLiveAssistantWorkingCard(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val current = _messages.value.toMutableList()
        val index = findLiveAssistantWorkingIndex(current)
        if (index >= 0) {
            current[index] = current[index].copy(text = clean, fromUser = false)
        } else {
            if (current.size >= MAX_ASSISTANT_CHAT_CARDS + 1) {
                current.removeAt(0)
            }
            current += ChatMessage(text = clean, fromUser = false)
            liveAssistantWorkingCardIndex = current.lastIndex
        }
        _messages.value = current
        liveAssistantWorkingCardIndex = current.indexOfLast { !it.fromUser && it.text == clean }
    }

    private fun clearLiveAssistantWorkingCard(commitIfPopulated: Boolean = false) {
        val current = _messages.value.toMutableList()
        val index = findLiveAssistantWorkingIndex(current)
        if (index >= 0) {
            val candidate = current[index].text.trim()
            if (commitIfPopulated && candidate.isNotBlank()) {
                _messages.value = current.takeLast(MAX_ASSISTANT_CHAT_CARDS)
                liveAssistantWorkingCardIndex = -1
                liveAssistantWorkingTurn = ""
                persistAssistantEntry(candidate)
                return
            }
            current.removeAt(index)
            _messages.value = current
        }
        liveAssistantWorkingCardIndex = -1
    }

    private fun findLiveAssistantWorkingIndex(messages: List<ChatMessage>): Int {
        val index = liveAssistantWorkingCardIndex
        return if (index in messages.indices && !messages[index].fromUser) index else -1
    }

    private fun ChatMessageEntity.toModel(): ChatMessage {
        return ChatMessage(
            text = text,
            fromUser = false,
            timestampMs = timestamp
        )
    }

    // ── Calendar ─────────────────────────────────────────────────────────
    private val _calendarEvents = MutableLiveData<List<GoogleCalendarClient.CalendarEvent>>()
    val calendarEvents: LiveData<List<GoogleCalendarClient.CalendarEvent>> = _calendarEvents

    fun fetchCalendarEvents() {
        viewModelScope.launch {
            // Fetch from all enabled calendars
            val enabledIds = prefs.enabledCalendarIds
            val calendarIds = if (enabledIds.isEmpty()) {
                listOf(prefs.calendarId.ifBlank { "primary" })
            } else {
                enabledIds.toList()
            }

            val allEvents = mutableListOf<GoogleCalendarClient.CalendarEvent>()
            var anyApiKeyMissing = false

            for (calId in calendarIds) {
                val itemCount = prefs.getCalendarItemCount(calId)
                when (val result = calendarClient.fetchUpcomingEvents(calendarId = calId, maxResults = itemCount)) {
                    is GoogleCalendarClient.CalendarResult.Success -> {
                        allEvents.addAll(result.events)
                    }
                    is GoogleCalendarClient.CalendarResult.ApiKeyMissing -> {
                        anyApiKeyMissing = true
                    }
                    is GoogleCalendarClient.CalendarResult.Error -> {
                        Log.e(TAG, "Calendar error for $calId: ${result.message}")
                    }
                }
            }

            // Sort all events by start time
            allEvents.sortBy { it.start?.time ?: Long.MAX_VALUE }
            _calendarEvents.postValue(allEvents)

            val summary = if (allEvents.isEmpty()) {
                if (anyApiKeyMissing) {
                    onApiKeyMissing("Google Calendar")
                    return@launch
                }
                "No upcoming events"
            } else {
                val showTime = prefs.hudShowEventTime
                val timeFormat = SimpleDateFormat("h:mm a", Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                }
                val dateFormat = SimpleDateFormat("MMM d", Locale.US)
                allEvents.take(3).joinToString("\n") { event ->
                    if (showTime && event.start != null) {
                        val time = timeFormat.format(event.start)
                        val date = dateFormat.format(event.start)
                        "\u2022 $date $time — ${event.summary}"
                    } else {
                        "\u2022 ${event.summary}"
                    }
                }
            }
            _calendarSummary.value = summary
        }
    }

    /** Convenience for forcing a calendar refresh after config changes. */
    fun refreshCalendarNow() {
        refreshHudUpcomingCalendar(force = true)
    }

    // ── Tasks ─────────────────────────────────────────────────────────────

    /**
     * Refresh HUD tasks display. Throttled by hudRefreshIntervalSeconds.
     */
    fun refreshHudTasks(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val intervalMs = prefs.hudRefreshIntervalSeconds * 1000L
        if (!force && (now - lastHudTasksRefreshMs) < intervalMs) return
        lastHudTasksRefreshMs = now
        fetchHudTasks()
    }

    private fun fetchHudTasks() {
        val client = tasksClient ?: return
        viewModelScope.launch {
            val maxItems = prefs.tasksItemCount
            when (val result = client.fetchTasks(maxResults = maxItems)) {
                is GoogleTasksClient.TasksResult.Success -> {
                    val summary = if (result.tasks.isEmpty()) {
                        "No pending tasks"
                    } else {
                        val dateFormat = SimpleDateFormat("MMM d", Locale.US)
                        result.tasks.take(maxItems).joinToString("\n") { task ->
                            val dueStr = task.due?.let { " (${dateFormat.format(it)})" } ?: ""
                            "\u2022 ${task.title}$dueStr"
                        }
                    }
                    _tasksSummary.value = if (summary.contains("\n")) "TASKS\n$summary" else "TASKS: $summary"
                }
                is GoogleTasksClient.TasksResult.AuthRequired -> {
                    _tasksSummary.value = ""
                    Log.d(TAG, "Tasks: OAuth required")
                }
                is GoogleTasksClient.TasksResult.Error -> {
                    Log.e(TAG, "Tasks error: ${result.message}")
                }
            }
        }
    }

    // ── News ──────────────────────────────────────────────────────────────

    /**
     * Refresh HUD news headlines. Throttled by newsRefreshIntervalSeconds.
     */
    fun refreshHudNews(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val intervalMs = prefs.newsRefreshIntervalSeconds * 1000L
        if (!force && (now - lastHudNewsRefreshMs) < intervalMs) return
        lastHudNewsRefreshMs = now
        fetchHudNews()
    }

    private fun fetchHudNews() {
        viewModelScope.launch {
            val maxItems = prefs.newsItemCount
            when (val result = newsClient.fetchHeadlines(maxResults = maxItems)) {
                is GoogleNewsClient.NewsResult.Success -> {
                    val summary = if (result.headlines.isEmpty()) {
                        "No headlines"
                    } else {
                        result.headlines.take(maxItems).joinToString("\n") { "\u2022 ${it.title}" }
                    }
                    _newsSummary.value = if (summary.contains("\n")) "NEWS\n$summary" else "NEWS: $summary"
                }
                is GoogleNewsClient.NewsResult.Error -> {
                    Log.e(TAG, "News error: ${result.message}")
                }
            }
        }
    }

    // ── Voice assistant trigger ──────────────────────────────────────────
    private val _voiceAssistantActive = MutableLiveData(false)
    val voiceAssistantActive: LiveData<Boolean> = _voiceAssistantActive

    fun activateVoiceAssistant() {
        _voiceAssistantActive.value = true
    }

    fun deactivateVoiceAssistant() {
        _voiceAssistantActive.value = false
    }

    // ── HUD hold-progress (Siri-style ring) ──────────────────────────────
    private val _holdProgress = MutableLiveData(0f)
    val holdProgress: LiveData<Float> = _holdProgress

    fun updateHoldProgress(progress: Float) {
        _holdProgress.postValue(progress)
    }

    fun resetHoldProgress() {
        _holdProgress.postValue(0f)
    }

    // ── Multimodal readiness ─────────────────────────────────────────────
    private val _isCameraEnabled = MutableStateFlow(false)
    private val _isTextureViewReady = MutableStateFlow(false)

    fun setMultimodalCameraEnabled(enabled: Boolean) {
        _isCameraEnabled.value = enabled
    }

    fun setMultimodalTextureReady(ready: Boolean) {
        _isTextureViewReady.value = ready
    }

    fun canSendMultimodalFrame(): Boolean {
        return _isCameraEnabled.value && _isTextureViewReady.value
    }
}
