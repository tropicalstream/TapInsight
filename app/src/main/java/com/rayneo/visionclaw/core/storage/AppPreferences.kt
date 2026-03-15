package com.rayneo.visionclaw.core.storage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value).apply()

    var researchProvider: String
        get() = prefs.getString(KEY_RESEARCH_PROVIDER, "gemini") ?: "gemini"
        set(value) = prefs.edit().putString(KEY_RESEARCH_PROVIDER, value).apply()

    var researchApiKey: String
        get() = prefs.getString(KEY_RESEARCH_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RESEARCH_API_KEY, value).apply()

    var researchModel: String
        get() = prefs.getString(KEY_RESEARCH_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RESEARCH_MODEL, value).apply()

    var calendarApiKey: String
        get() = prefs.getString(KEY_CALENDAR_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CALENDAR_API_KEY, value).apply()

    var calendarId: String
        get() = prefs.getString(KEY_CALENDAR_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CALENDAR_ID, value).apply()

    var openClawEndpoint: String
        get() = prefs.getString(KEY_OPENCLAW_ENDPOINT, DEFAULT_OPENCLAW_ENDPOINT) ?: DEFAULT_OPENCLAW_ENDPOINT
        set(value) = prefs.edit().putString(KEY_OPENCLAW_ENDPOINT, value).apply()

    var ttsVolume: Float
        get() = prefs.getFloat(KEY_TTS_VOLUME, 0.80f)
        set(value) = prefs.edit().putFloat(KEY_TTS_VOLUME, value.coerceIn(0f, 1f)).apply()

    var ttsMuted: Boolean
        get() = prefs.getBoolean(KEY_TTS_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_TTS_MUTED, value).apply()

    var musicMuted: Boolean
        get() = prefs.getBoolean(KEY_MUSIC_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MUSIC_MUTED, value).apply()

    var webDesktopMode: Boolean
        get() = prefs.getBoolean(KEY_WEB_DESKTOP_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_WEB_DESKTOP_MODE, value).apply()

    var webPointerSensitivity: Float
        get() = prefs.getFloat(KEY_WEB_POINTER_SENSITIVITY, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_WEB_POINTER_SENSITIVITY, value.coerceIn(0.4f, 1.8f)).apply()

    var webForceDarkMode: Boolean
        get() = prefs.getBoolean(KEY_WEB_FORCE_DARK_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_WEB_FORCE_DARK_MODE, value).apply()

    var browserShowSystemInfo: Boolean
        get() = prefs.getBoolean(KEY_BROWSER_SHOW_SYSTEM_INFO, true)
        set(value) = prefs.edit().putBoolean(KEY_BROWSER_SHOW_SYSTEM_INFO, value).apply()

    /** Custom system prompt override. If blank, the built-in prompt is used. */
    var customSystemPrompt: String
        get() = prefs.getString(KEY_CUSTOM_SYSTEM_PROMPT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_SYSTEM_PROMPT, value).apply()

    /** Personality description injected after the system prompt. */
    var personality: String
        get() = prefs.getString(KEY_PERSONALITY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PERSONALITY, value).apply()

    /** Editable prompt section: Identity (who is AITap). Blank = use default. */
    var promptIdentity: String
        get() = prefs.getString(KEY_PROMPT_IDENTITY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROMPT_IDENTITY, value).apply()

    /** Editable prompt section: Tool routing rules. Blank = use default. */
    var promptRoutingRules: String
        get() = prefs.getString(KEY_PROMPT_ROUTING_RULES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROMPT_ROUTING_RULES, value).apply()

    /** Editable prompt section: Proactive behavior + HUD output + privacy. Blank = use default. */
    var promptBehavior: String
        get() = prefs.getString(KEY_PROMPT_BEHAVIOR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROMPT_BEHAVIOR, value).apply()

    /** Editable prompt section: URL generation rules. Blank = use default. */
    var promptUrlRules: String
        get() = prefs.getString(KEY_PROMPT_URL_RULES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROMPT_URL_RULES, value).apply()

    /** Spotify OAuth client ID. */
    var spotifyClientId: String
        get() = prefs.getString(KEY_SPOTIFY_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPOTIFY_CLIENT_ID, value).apply()

    /** Spotify OAuth client secret. */
    var spotifyClientSecret: String
        get() = prefs.getString(KEY_SPOTIFY_CLIENT_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPOTIFY_CLIENT_SECRET, value).apply()

    /** Google OAuth client ID (for Calendar, Keep, Contacts). */
    var googleOAuthClientId: String
        get() = prefs.getString(KEY_GOOGLE_OAUTH_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_OAUTH_CLIENT_ID, value).apply()

    /** Google OAuth client secret (for Web application type). */
    var googleOAuthClientSecret: String
        get() = prefs.getString(KEY_GOOGLE_OAUTH_CLIENT_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_OAUTH_CLIENT_SECRET, value).apply()

    /** Google OAuth access token (short-lived). */
    var googleOAuthAccessToken: String
        get() = prefs.getString(KEY_GOOGLE_OAUTH_ACCESS_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_OAUTH_ACCESS_TOKEN, value).apply()

    /** Google OAuth refresh token (long-lived). */
    var googleOAuthRefreshToken: String
        get() = prefs.getString(KEY_GOOGLE_OAUTH_REFRESH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_OAUTH_REFRESH_TOKEN, value).apply()

    /** Expiry timestamp (epoch ms) for the current access token. */
    var googleOAuthTokenExpiryMs: Long
        get() = prefs.getLong(KEY_GOOGLE_OAUTH_TOKEN_EXPIRY_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_GOOGLE_OAUTH_TOKEN_EXPIRY_MS, value).apply()

    /** Returns true if a non-expired OAuth access token exists. */
    fun isGoogleOAuthTokenValid(): Boolean =
        googleOAuthAccessToken.isNotBlank() &&
                System.currentTimeMillis() < googleOAuthTokenExpiryMs

    /** Returns true if any Google OAuth tokens are stored (even if expired). */
    fun hasGoogleOAuthTokens(): Boolean =
        googleOAuthRefreshToken.isNotBlank()

    /** Clears all Google OAuth tokens (forces re-authorization). */
    fun clearGoogleOAuthTokens() {
        prefs.edit()
            .remove(KEY_GOOGLE_OAUTH_ACCESS_TOKEN)
            .remove(KEY_GOOGLE_OAUTH_REFRESH_TOKEN)
            .remove(KEY_GOOGLE_OAUTH_TOKEN_EXPIRY_MS)
            .apply()
    }

    // ── HUD Display Settings ────────────────────────────────────────────

    /** Show calendar widget on HUD. */
    var hudShowCalendar: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_CALENDAR, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_CALENDAR, value).apply()

    /** Show traffic & commute info on HUD. */
    var hudShowTraffic: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_TRAFFIC, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_TRAFFIC, value).apply()

    /** Show floating notifications on HUD. */
    var hudShowNotifications: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_NOTIFICATIONS, value).apply()

    /** HUD widget refresh interval in seconds (5–300). */
    var hudRefreshIntervalSeconds: Int
        get() = prefs.getInt(KEY_HUD_REFRESH_INTERVAL_SECONDS, 60).coerceIn(5, 300)
        set(value) = prefs.edit().putInt(KEY_HUD_REFRESH_INTERVAL_SECONDS, value.coerceIn(5, 300)).apply()

    /** Show event time/date on the HUD calendar widget. */
    var hudShowEventTime: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_EVENT_TIME, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_EVENT_TIME, value).apply()

    /** Comma-separated list of enabled Google Calendar IDs. Empty = primary only. */
    var enabledCalendarIds: Set<String>
        get() {
            val raw = prefs.getString(KEY_ENABLED_CALENDAR_IDS, "") ?: ""
            return if (raw.isBlank()) emptySet()
            else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }
        set(value) = prefs.edit().putString(KEY_ENABLED_CALENDAR_IDS, value.joinToString(",")).apply()

    /** Show tasks widget on HUD. */
    var hudShowTasks: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_TASKS, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_TASKS, value).apply()

    /** Show news headlines on HUD. */
    var hudShowNews: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_NEWS, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_NEWS, value).apply()

    /** Number of tasks to show on HUD (1-10). */
    var tasksItemCount: Int
        get() = prefs.getInt(KEY_TASKS_ITEM_COUNT, 5).coerceIn(1, 10)
        set(value) = prefs.edit().putInt(KEY_TASKS_ITEM_COUNT, value.coerceIn(1, 10)).apply()

    /** Number of news headlines to show on HUD (1-10). */
    var newsItemCount: Int
        get() = prefs.getInt(KEY_NEWS_ITEM_COUNT, 3).coerceIn(1, 10)
        set(value) = prefs.edit().putInt(KEY_NEWS_ITEM_COUNT, value.coerceIn(1, 10)).apply()

    /** News headline refresh interval in seconds (60-3600). */
    var newsRefreshIntervalSeconds: Int
        get() = prefs.getInt(KEY_NEWS_REFRESH_INTERVAL_SECONDS, 600).coerceIn(60, 3600)
        set(value) = prefs.edit().putInt(KEY_NEWS_REFRESH_INTERVAL_SECONDS, value.coerceIn(60, 3600)).apply()

    /** Comma-separated display order for HUD cards (e.g. "calendar,tasks,news"). */
    var hudDisplayOrder: String
        get() = prefs.getString(KEY_HUD_DISPLAY_ORDER, "calendar,tasks,news") ?: "calendar,tasks,news"
        set(value) = prefs.edit().putString(KEY_HUD_DISPLAY_ORDER, value).apply()

    /** JSON map of calendar ID → item count. E.g. {"primary":3,"other@gmail.com":5} */
    var calendarItemCounts: String
        get() = prefs.getString(KEY_CALENDAR_ITEM_COUNTS, "{}") ?: "{}"
        set(value) = prefs.edit().putString(KEY_CALENDAR_ITEM_COUNTS, value).apply()

    /** Get item count for a specific calendar. Default 3. */
    fun getCalendarItemCount(calendarId: String): Int {
        return try {
            val map: Map<String, Double> = gson.fromJson(
                calendarItemCounts,
                object : TypeToken<Map<String, Double>>() {}.type
            )
            map[calendarId]?.toInt()?.coerceIn(1, 10) ?: 3
        } catch (_: Exception) { 3 }
    }

    /** Google Maps / Routes API key. */
    var googleMapsApiKey: String
        get() = prefs.getString(KEY_GOOGLE_MAPS_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_MAPS_API_KEY, value).apply()

    /** Gemini model override (e.g. "gemini-2.5-pro"). Blank = use default. */
    var geminiModelOverride: String
        get() = prefs.getString(KEY_GEMINI_MODEL_OVERRIDE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL_OVERRIDE, value).apply()

    fun getBookmarks(): List<Bookmark> {
        val raw = prefs.getString(KEY_BOOKMARKS, "[]") ?: "[]"
        return runCatching {
            gson.fromJson<List<Bookmark>>(raw, object : TypeToken<List<Bookmark>>() {}.type)
        }.getOrDefault(emptyList())
    }

    fun addBookmark(bookmark: Bookmark) {
        val updated = getBookmarks().toMutableList().apply { add(bookmark) }
        prefs.edit().putString(KEY_BOOKMARKS, gson.toJson(updated)).apply()
    }

    fun removeBookmark(url: String) {
        val updated = getBookmarks().filterNot { it.url == url }
        prefs.edit().putString(KEY_BOOKMARKS, gson.toJson(updated)).apply()
    }

    fun getAssistantCardHistory(): List<PersistedAssistantCard> {
        val raw = prefs.getString(KEY_ASSISTANT_CARD_HISTORY, "[]") ?: "[]"
        val structured = runCatching {
            gson.fromJson<List<PersistedAssistantCard>>(
                raw,
                object : TypeToken<List<PersistedAssistantCard>>() {}.type
            )
        }.getOrNull()
            ?.mapNotNull { card ->
                val text = card.text.trim()
                if (text.isBlank()) null else card.copy(text = text)
            }
            .orEmpty()
        if (structured.isNotEmpty()) return structured

        // Backward compatibility: migrate legacy string-only history.
        val legacy = runCatching {
            gson.fromJson<List<String>>(raw, object : TypeToken<List<String>>() {}.type)
        }.getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (legacy.isEmpty()) return emptyList()

        val base = System.currentTimeMillis()
        return legacy.mapIndexed { index, text ->
            PersistedAssistantCard(
                text = text,
                url = null,
                timestampMs = base + index
            )
        }
    }

    fun setAssistantCardHistory(cards: List<PersistedAssistantCard>) {
        prefs.edit().putString(KEY_ASSISTANT_CARD_HISTORY, gson.toJson(cards)).apply()
    }

    companion object {
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_RESEARCH_PROVIDER = "research_provider"
        private const val KEY_RESEARCH_API_KEY = "research_api_key"
        private const val KEY_RESEARCH_MODEL = "research_model"
        private const val KEY_CALENDAR_API_KEY = "calendar_api_key"
        private const val KEY_CALENDAR_ID = "calendar_id"
        private const val KEY_OPENCLAW_ENDPOINT = "openclaw_endpoint"
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_ASSISTANT_CARD_HISTORY = "assistant_card_history"
        private const val KEY_TTS_VOLUME = "tts_volume"
        private const val KEY_TTS_MUTED = "tts_muted"
        private const val KEY_MUSIC_MUTED = "music_muted"
        private const val KEY_WEB_DESKTOP_MODE = "web_desktop_mode"
        private const val KEY_WEB_POINTER_SENSITIVITY = "web_pointer_sensitivity"
        private const val KEY_WEB_FORCE_DARK_MODE = "web_force_dark_mode"
        private const val KEY_BROWSER_SHOW_SYSTEM_INFO = "browser_show_system_info"
        private const val KEY_CUSTOM_SYSTEM_PROMPT = "custom_system_prompt"
        private const val KEY_PERSONALITY = "personality"
        private const val KEY_SPOTIFY_CLIENT_ID = "spotify_client_id"
        private const val KEY_SPOTIFY_CLIENT_SECRET = "spotify_client_secret"
        private const val KEY_GOOGLE_OAUTH_CLIENT_ID = "google_oauth_client_id"
        private const val KEY_GOOGLE_OAUTH_CLIENT_SECRET = "google_oauth_client_secret"
        private const val KEY_GOOGLE_OAUTH_ACCESS_TOKEN = "google_oauth_access_token"
        private const val KEY_GOOGLE_OAUTH_REFRESH_TOKEN = "google_oauth_refresh_token"
        private const val KEY_GOOGLE_OAUTH_TOKEN_EXPIRY_MS = "google_oauth_token_expiry_ms"
        private const val KEY_GOOGLE_MAPS_API_KEY = "google_maps_api_key"
        private const val KEY_GEMINI_MODEL_OVERRIDE = "gemini_model_override"
        private const val KEY_HUD_SHOW_CALENDAR = "hud_show_calendar"
        private const val KEY_HUD_SHOW_TRAFFIC = "hud_show_traffic"
        private const val KEY_HUD_SHOW_NOTIFICATIONS = "hud_show_notifications"
        private const val KEY_HUD_REFRESH_INTERVAL_SECONDS = "hud_refresh_interval_seconds"
        private const val KEY_HUD_SHOW_EVENT_TIME = "hud_show_event_time"
        private const val KEY_ENABLED_CALENDAR_IDS = "enabled_calendar_ids"
        private const val KEY_HUD_SHOW_TASKS = "hud_show_tasks"
        private const val KEY_HUD_SHOW_NEWS = "hud_show_news"
        private const val KEY_TASKS_ITEM_COUNT = "tasks_item_count"
        private const val KEY_NEWS_ITEM_COUNT = "news_item_count"
        private const val KEY_NEWS_REFRESH_INTERVAL_SECONDS = "news_refresh_interval_seconds"
        private const val KEY_CALENDAR_ITEM_COUNTS = "calendar_item_counts"
        private const val KEY_HUD_DISPLAY_ORDER = "hud_display_order"
        private const val KEY_PROMPT_IDENTITY = "prompt_identity"
        private const val KEY_PROMPT_ROUTING_RULES = "prompt_routing_rules"
        private const val KEY_PROMPT_BEHAVIOR = "prompt_behavior"
        private const val KEY_PROMPT_URL_RULES = "prompt_url_rules"
        private const val DEFAULT_OPENCLAW_ENDPOINT = ""
    }
}

data class Bookmark(
    val title: String,
    val url: String
)

data class PersistedAssistantCard(
    val text: String,
    val url: String?,
    val timestampMs: Long
)
