package com.rayneo.visionclaw.core.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.rayneo.visionclaw.BuildConfig
import com.rayneo.visionclaw.core.network.GoogleAirQualityClient
import com.rayneo.visionclaw.core.network.GoogleCalendarClient
import com.rayneo.visionclaw.core.network.GoogleDirectionsClient
import com.rayneo.visionclaw.core.network.GoogleOAuthManager
import com.rayneo.visionclaw.core.network.GooglePlacesClient
import com.rayneo.visionclaw.core.network.GoogleTasksClient
import com.rayneo.visionclaw.core.network.ResearchRouter
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Lightweight HTTP server that serves the AITap companion configuration pages.
 *
 * Open from any phone/computer on the same WiFi:
 *   http://<glasses-ip>:19110
 *
 * Pages:
 *   GET  /                → Setup page (API keys, model, personality)
 *   GET  /browser          → Browser settings + login sync
 *   GET  /dashboard        → Dashboard editor (categories & links)
 *   GET  /radio            → TapRadio station manager
 *
 * API:
 *   GET  /api/config      → all config as JSON
 *   POST /api/config      → save config from JSON body
 *   GET  /api/dashboard   → dashboard links/groups as JSON
 *   POST /api/dashboard   → save dashboard links/groups
 *   GET  /api/radio       → TapRadio stations as JSON array
 *   POST /api/radio       → save TapRadio stations
 */
class CompanionServer(
    private val context: Context,
    port: Int = 19110,
    var oauthManager: GoogleOAuthManager? = null,
    /** Provides the latest device GPS location for the Location test button. */
    var locationProvider: (() -> com.rayneo.visionclaw.core.model.DeviceLocationContext?)? = null,
    var calendarSummaryProvider: (() -> String)? = null,
    var tasksSummaryProvider: (() -> String)? = null,
    var newsSummaryProvider: (() -> String)? = null,
    var airQualityTextProvider: (() -> String?)? = null,
    var airQualityValueProvider: (() -> Int?)? = null
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "CompanionServer"
        private const val PREFS_NAME = "visionclaw_prefs"
        private const val DASHBOARD_PREFS_KEY = "dashboard_data"

        /** JS bridge for the Setup page (index.html). */
        private const val SETUP_BRIDGE_JS = """
// REST API bridge (replaces Android JavascriptInterface for phone/computer access)
const AiTapBridge = {
  _cache: {},
  async _loadAll() {
    try {
      const r = await fetch('/api/config');
      if (r.ok) this._cache = await r.json();
    } catch(e) { console.error('Load failed:', e); }
  },
  getString(key) { return (this._cache[key] || '').toString(); },
  putString(key, v) { this._cache[key] = v; },
  putFloat(key, v) { this._cache[key] = v; },
  putBoolean(key, v) { this._cache[key] = v; },
  async applyConfig() {
    try {
      const r = await fetch('/api/config', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(this._cache)
      });
      return r.ok;
    } catch(e) { console.error('Save failed:', e); return false; }
  }
};

function hasBridge() { return true; }

async function loadAll() {
  await AiTapBridge._loadAll();
  for (const [id, cfg] of Object.entries(FIELDS)) {
    const el = document.getElementById(id);
    el.value = AiTapBridge.getString(cfg.key);
  }
  if (typeof renderHudOrderList === 'function') renderHudOrderList();
  showStatus('Configuration loaded.', 'ok');
}

async function saveAll() {
  for (const [id, cfg] of Object.entries(FIELDS)) {
    const el = document.getElementById(id);
    const v = el.value.trim();
    if (cfg.type === 'float') AiTapBridge.putFloat(cfg.key, parseFloat(v) || 0.8);
    else if (cfg.type === 'bool') AiTapBridge.putBoolean(cfg.key, v === 'true');
    else if (cfg.type === 'int') AiTapBridge.putFloat(cfg.key, parseInt(v) || 60);
    else AiTapBridge.putString(cfg.key, v);
  }
  const ok = await AiTapBridge.applyConfig();
  showStatus(ok ? 'Configuration saved! Restart AITap to apply.' : 'Failed to save.', ok ? 'ok' : 'err');
}

document.addEventListener('DOMContentLoaded', () => { loadAll(); if (typeof checkOAuthStatus === 'function') checkOAuthStatus(); });
"""

        /** JS bridge for the Browser page (browser.html). */
        private const val BROWSER_BRIDGE_JS = """
// REST API bridge for browser settings page
const AiTapBridge = {
  _cache: {},
  async _loadAll() {
    try {
      const r = await fetch('/api/config');
      if (r.ok) this._cache = await r.json();
    } catch(e) { console.error('Load failed:', e); }
  },
  getString(key) { return (this._cache[key] || '').toString(); },
  putString(key, v) { this._cache[key] = v; },
  putFloat(key, v) { this._cache[key] = v; },
  putBoolean(key, v) { this._cache[key] = v; },
  async applyConfig() {
    try {
      const r = await fetch('/api/config', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(this._cache)
      });
      return r.ok;
    } catch(e) { console.error('Save failed:', e); return false; }
  }
};

function hasBridge() { return true; }

async function loadAll() {
  await AiTapBridge._loadAll();
  for (const [id, cfg] of Object.entries(BROWSER_FIELDS)) {
    const el = document.getElementById(id);
    try { el.value = AiTapBridge.getString(cfg.key) || ''; } catch(e) {}
  }
  try {
    const raw = AiTapBridge.getString('browser_cookies');
    if (raw) {
      const parsed = JSON.parse(raw);
      cookieEntries = parsed.map((e, i) => ({ ...e, id: i }));
      nextCookieId = cookieEntries.length;
    }
  } catch(e) { console.error('Cookie load error:', e); }
  renderCookieList();
  showStatus('Settings loaded.', 'ok');
}

async function saveAll() {
  for (const [id, cfg] of Object.entries(BROWSER_FIELDS)) {
    const el = document.getElementById(id);
    const v = el.value.trim();
    if (cfg.type === 'float') AiTapBridge.putFloat(cfg.key, parseFloat(v) || 1.0);
    else if (cfg.type === 'bool') AiTapBridge.putBoolean(cfg.key, v === 'true');
    else AiTapBridge.putString(cfg.key, v);
  }
  document.querySelectorAll('.cookie-entry').forEach(el => {
    const id = parseInt(el.dataset.id);
    const entry = cookieEntries.find(e => e.id === id);
    if (entry) {
      entry.domain = el.querySelector('input[type="text"]').value.trim();
      entry.cookies = el.querySelector('textarea').value.trim();
    }
  });
  const cookieData = cookieEntries
    .filter(e => e.domain)
    .map(e => ({ domain: e.domain, cookies: e.cookies, label: e.label || '' }));
  AiTapBridge.putString('browser_cookies', JSON.stringify(cookieData));
  const ok = await AiTapBridge.applyConfig();
  showStatus(ok ? 'Settings saved! Cookies will sync on next browser launch.' : 'Failed to save.', ok ? 'ok' : 'err');
}

document.addEventListener('DOMContentLoaded', loadAll);
"""
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Config keys the companion pages can read/write. */
    private val allowedKeys = setOf(
        // Setup page keys
        "gemini_api_key",
        "gemini_model_override",
        "research_provider",
        "research_api_key",
        "research_model",
        "learnlm_model",
        "calendar_api_key",
        "calendar_id",
        "google_maps_api_key",
        "google_oauth_client_id",
        "google_oauth_client_secret",
        "spotify_client_id",
        "spotify_client_secret",
        "personality",
        "custom_system_prompt",
        "tts_volume",
        "tts_muted",
        // Browser page keys
        "web_desktop_mode",
        "web_force_dark_mode",
        "web_pointer_sensitivity",
        "browser_show_system_info",
        "browser_cookies",
        // HUD display keys
        "hud_show_calendar",
        "hud_show_traffic",
        "hud_show_notifications",
        "hud_refresh_interval_seconds",
        "hud_show_event_time",
        "enabled_calendar_ids",
        "hud_show_tasks",
        "hud_show_news",
        "tasks_item_count",
        "news_item_count",
        "news_refresh_interval_seconds",
        "calendar_item_counts",
        "hud_display_order",
        "prompt_identity",
        "prompt_routing_rules",
        "prompt_behavior",
        "prompt_url_rules"
    )

    /** Keys readable via /api/oauth/status (no secrets exposed). */
    private val oauthStatusKeys = setOf(
        "google_oauth_client_id",
        "google_oauth_token_expiry_ms"
    )

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method
        return try {
            when {
                uri == "/" || uri == "/index.html" -> serveAssetPage("companion/index.html", SETUP_BRIDGE_JS)
                uri == "/browser" || uri == "/browser.html" -> serveAssetPage("companion/browser.html", BROWSER_BRIDGE_JS)
                uri == "/dashboard" || uri == "/dashboard.html" -> serveRawAsset("companion/dashboard.html")
                uri == "/radio" || uri == "/radio.html" -> serveRawAsset("companion/radio.html")
                uri == "/api/radio" && method == Method.GET -> serveRadioStations()
                uri == "/api/radio" && method == Method.POST -> saveRadioStations(session)
                uri == "/api/config" && method == Method.GET -> serveConfig()
                uri == "/api/config" && method == Method.POST -> saveConfig(session)
                uri == "/api/dashboard" && method == Method.GET -> serveDashboard()
                uri == "/api/dashboard" && method == Method.POST -> saveDashboard(session)
                uri == "/oauth/callback" && method == Method.GET -> handleOAuthCallback(session)
                uri == "/api/oauth/exchange" && method == Method.POST -> handleOAuthExchange(session)
                uri == "/api/oauth/status" && method == Method.GET -> serveOAuthStatus()
                uri == "/api/calendars" && method == Method.GET -> fetchCalendarList()
                uri == "/api/verify/calendar" && method == Method.GET -> verifyCalendar()
                uri == "/api/verify/directions" && method == Method.GET -> verifyDirections()
                uri == "/api/verify/tasks" && method == Method.GET -> verifyTasks()
                uri == "/api/verify/places" && method == Method.GET -> verifyPlaces()
                uri == "/api/verify/location" && method == Method.GET -> verifyLocation()
                uri == "/api/verify/traffic" && method == Method.GET -> verifyTraffic()
                uri == "/api/verify/air_quality" && method == Method.GET -> verifyAirQuality()
                uri == "/api/verify/research" && method == Method.GET -> verifyResearch()
                uri == "/api/hud_state" && method == Method.GET -> serveHudState()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun serveAssetPage(assetPath: String, bridgeJs: String): Response {
        val html = context.assets.open(assetPath)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val setupMarker =
            "// Auto-load on page ready\n" +
                "document.addEventListener('DOMContentLoaded', () => { loadAll(); checkOAuthStatus(); });"
        val browserMarker =
            "// Auto-load on page ready\n" +
                "document.addEventListener('DOMContentLoaded', loadAll);"

        // Replace the page-local bridge with the REST bridge used by the laptop/phone companion UI.
        val patchedHtml = when {
            html.contains(setupMarker) -> html.replace(setupMarker, bridgeJs)
            html.contains(browserMarker) -> html.replace(browserMarker, bridgeJs)
            html.contains("</body>") -> html.replace("</body>", "<script>\n$bridgeJs\n</script>\n</body>")
            else -> html + "\n<script>\n$bridgeJs\n</script>\n"
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html", patchedHtml)
    }

    private fun serveConfig(): Response {
        val json = JSONObject()
        for (key in allowedKeys) {
            when (val value = prefs.all[key]) {
                is String -> json.put(key, value)
                is Float -> json.put(key, value.toDouble())
                is Boolean -> json.put(key, value)
                is Int -> json.put(key, value)
                else -> json.put(key, prefs.getString(key, "") ?: "")
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun saveConfig(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""
        if (postData.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json", """{"error":"Empty body"}"""
            )
        }

        val json = JSONObject(postData)
        val editor = prefs.edit()
        for (key in allowedKeys) {
            if (!json.has(key)) continue
            when (key) {
                "tts_volume", "web_pointer_sensitivity" ->
                    editor.putFloat(key, json.optDouble(key, if (key == "tts_volume") 0.8 else 1.0).toFloat())
                "tts_muted", "web_desktop_mode", "web_force_dark_mode", "browser_show_system_info",
                "hud_show_calendar", "hud_show_traffic", "hud_show_notifications",
                "hud_show_event_time", "hud_show_tasks", "hud_show_news" ->
                    editor.putBoolean(key, json.optBoolean(key, true))
                "hud_refresh_interval_seconds" ->
                    editor.putInt(key, json.optInt(key, 60).coerceIn(5, 300))
                "tasks_item_count" ->
                    editor.putInt(key, json.optInt(key, 5).coerceIn(1, 10))
                "news_item_count" ->
                    editor.putInt(key, json.optInt(key, 3).coerceIn(1, 10))
                "news_refresh_interval_seconds" ->
                    editor.putInt(key, json.optInt(key, 600).coerceIn(60, 3600))
                else -> editor.putString(key, json.optString(key, ""))
            }
        }
        editor.apply()
        Log.d(TAG, "Config saved from companion app")
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"saved"}""")
    }

    /** Serve an asset page as-is (no JS bridge patching needed). */
    private fun serveRawAsset(assetPath: String): Response {
        val html = context.assets.open(assetPath)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    /** Return the saved dashboard JSON (apps + groups). */
    private fun serveDashboard(): Response {
        val raw = prefs.getString(DASHBOARD_PREFS_KEY, null) ?: "{}"
        return newFixedLengthResponse(Response.Status.OK, "application/json", raw)
    }

    /** Save the full dashboard JSON (apps + groups). */
    private fun saveDashboard(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""
        if (postData.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json", """{"error":"Empty body"}"""
            )
        }
        // Validate it's valid JSON
        try {
            JSONObject(postData)
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json", """{"error":"Invalid JSON"}"""
            )
        }
        prefs.edit().putString(DASHBOARD_PREFS_KEY, postData).apply()
        Log.d(TAG, "Dashboard saved from companion app (${postData.length} chars)")
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"saved"}""")
    }

    // ── TapRadio ───────────────────────────────────────────────────────

    private val RADIO_PREFS_KEY = "tapradio_stations"

    /** Return saved TapRadio stations JSON array. */
    private fun serveRadioStations(): Response {
        val raw = prefs.getString(RADIO_PREFS_KEY, null) ?: "[]"
        return newFixedLengthResponse(Response.Status.OK, "application/json", raw)
    }

    /** Save TapRadio stations JSON array from the companion editor. */
    private fun saveRadioStations(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""
        if (postData.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json", """{"error":"Empty body"}"""
            )
        }
        prefs.edit().putString(RADIO_PREFS_KEY, postData).apply()
        Log.d(TAG, "TapRadio stations saved (${postData.length} chars)")
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"saved"}""")
    }

    // ── OAuth ─────────────────────────────────────────────────────────

    /**
     * Handle Google OAuth redirect: /oauth/callback?code=...
     * Exchanges the authorization code for tokens and stores them.
     */
    private fun handleOAuthCallback(session: IHTTPSession): Response {
        val params = session.parms ?: emptyMap()
        val code = params["code"]
        val error = params["error"]

        if (error != null) {
            Log.w(TAG, "OAuth error: $error")
            return newFixedLengthResponse(
                Response.Status.OK, "text/html",
                oauthResultPage(false, "Authorization denied: $error")
            )
        }

        if (code.isNullOrBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/html",
                oauthResultPage(false, "No authorization code received.")
            )
        }

        val mgr = oauthManager
        if (mgr == null) {
            Log.e(TAG, "OAuth manager not initialized")
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/html",
                oauthResultPage(false, "OAuth manager not ready. Restart AITap and try again.")
            )
        }

        // Reconstruct the redirect URI from the incoming request
        val host = session.headers["host"] ?: "localhost:19110"
        val redirectUri = "http://$host/oauth/callback"

        // Exchange code for tokens (blocking in NanoHTTPD thread)
        val result = runBlocking { mgr.exchangeCodeForTokensDetailed(code, redirectUri) }

        return newFixedLengthResponse(
            Response.Status.OK, "text/html",
            oauthResultPage(
                result.success,
                if (result.success) "Google account authorized! You can close this tab."
                else "Token exchange failed: ${result.errorDetail}<br><br><small>Redirect URI used: $redirectUri</small>"
            )
        )
    }

    /**
     * Handle manual OAuth code submission: POST /api/oauth/exchange
     * Body: {"code": "...", "redirect_uri": "http://<glasses-ip>:19110/oauth/callback"}
     */
    private fun handleOAuthExchange(session: IHTTPSession): Response {
        return try {
            // Read POST body
            val body = HashMap<String, String>()
            session.parseBody(body)
            val postData = body["postData"] ?: ""

            if (postData.isBlank()) {
                Log.e(TAG, "OAuth exchange: empty body")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json", """{"error":"Empty body"}"""
                )
            }

            Log.d(TAG, "OAuth exchange: received body (${postData.length} chars)")

            val json = try { JSONObject(postData) } catch (e: Exception) {
                Log.e(TAG, "OAuth exchange: invalid JSON", e)
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json", """{"error":"Invalid JSON"}"""
                )
            }

            val code = json.optString("code", "").trim()
            val host = session.headers["host"] ?: "localhost:19110"
            val defaultRedirectUri = "http://$host/oauth/callback"
            val redirectUri = json.optString("redirect_uri", defaultRedirectUri).trim()

            if (code.isBlank()) {
                Log.e(TAG, "OAuth exchange: no code in body")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json", """{"error":"No code provided"}"""
                )
            }

            Log.d(TAG, "OAuth exchange: code=${code.take(10)}..., redirect=$redirectUri")

            val mgr = oauthManager
            if (mgr == null) {
                Log.e(TAG, "OAuth manager not initialized")
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "application/json",
                    """{"error":"OAuth manager not ready. Restart AITap and try again."}"""
                )
            }

            val success = runBlocking { mgr.exchangeCodeForTokens(code, redirectUri) }

            if (success) {
                Log.i(TAG, "OAuth exchange: success!")
                newFixedLengthResponse(
                    Response.Status.OK, "application/json", """{"status":"authorized"}"""
                )
            } else {
                Log.e(TAG, "OAuth exchange: token exchange failed")
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json",
                    """{"error":"Token exchange failed. Check Client ID and Secret."}"""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "OAuth exchange: unexpected error", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
                """{"error":"Server error: ${e.message?.replace("\"", "'")}"}"""
            )
        }
    }

    /** Returns OAuth authorization status as JSON. */
    private fun serveOAuthStatus(): Response {
        val hasToken = prefs.getString("google_oauth_refresh_token", "")?.isNotBlank() == true
        val expiryMs = prefs.getLong("google_oauth_token_expiry_ms", 0L)
        val isValid = hasToken && System.currentTimeMillis() < expiryMs
        val json = JSONObject().apply {
            put("authorized", hasToken)
            put("token_valid", isValid)
            put("expiry_ms", expiryMs)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun serveHudState(): Response {
        return jsonResponse(JSONObject().apply {
            put("hud_show_calendar", prefs.getBoolean("hud_show_calendar", true))
            put("hud_show_tasks", prefs.getBoolean("hud_show_tasks", true))
            put("hud_show_news", prefs.getBoolean("hud_show_news", true))
            put("calendar_summary", calendarSummaryProvider?.invoke().orEmpty())
            put("tasks_summary", tasksSummaryProvider?.invoke().orEmpty())
            put("news_summary", newsSummaryProvider?.invoke().orEmpty())
            put("aqi_text", airQualityTextProvider?.invoke() ?: JSONObject.NULL)
            put("aqi_value", airQualityValueProvider?.invoke() ?: JSONObject.NULL)
            put("location_provider", locationProvider?.invoke()?.provider ?: JSONObject.NULL)
        })
    }

    // ── Calendar List ────────────────────────────────────────────────────

    /** Fetch all calendars visible to the OAuth-authenticated user. */
    private fun fetchCalendarList(): Response {
        val hasOAuth = prefs.getString("google_oauth_refresh_token", "")?.isNotBlank() == true
        if (!hasOAuth) {
            return jsonResponse(JSONObject().apply {
                put("status", "auth_required")
                put("message", "OAuth authorization required to list calendars")
                put("calendars", org.json.JSONArray())
            })
        }

        return try {
            val mgr = oauthManager ?: return jsonResponse(JSONObject().apply {
                put("status", "error")
                put("message", "OAuth manager not initialized")
                put("calendars", org.json.JSONArray())
            })

            val calendarApiKey = prefs.getString("calendar_api_key", "") ?: ""
            val client = GoogleCalendarClient(
                apiKeyProvider = { calendarApiKey.takeIf { it.isNotBlank() } },
                accessTokenProvider = { runBlocking { mgr.getValidAccessToken() } },
                context = context
            )
            val result = runBlocking { client.fetchCalendarList() }

            when (result) {
                is GoogleCalendarClient.CalendarListResult.Success -> {
                    val arr = org.json.JSONArray()
                    result.calendars.forEach { cal ->
                        arr.put(JSONObject().apply {
                            put("id", cal.id)
                            put("summary", cal.summary)
                            put("primary", cal.primary)
                            if (cal.backgroundColor != null) put("backgroundColor", cal.backgroundColor)
                            if (cal.accessRole != null) put("accessRole", cal.accessRole)
                        })
                    }
                    jsonResponse(JSONObject().apply {
                        put("status", "success")
                        put("calendars", arr)
                    })
                }
                is GoogleCalendarClient.CalendarListResult.AuthRequired ->
                    jsonResponse(JSONObject().apply {
                        put("status", "auth_required")
                        put("message", "OAuth authorization required")
                        put("calendars", org.json.JSONArray())
                    })
                is GoogleCalendarClient.CalendarListResult.Error ->
                    jsonResponse(JSONObject().apply {
                        put("status", "error")
                        put("message", result.message)
                        put("calendars", org.json.JSONArray())
                    })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Calendar list error", e)
            jsonResponse(JSONObject().apply {
                put("status", "error")
                put("message", "Exception: ${e.message}")
                put("calendars", org.json.JSONArray())
            })
        }
    }

    // ── API Verification ──────────────────────────────────────────────────

    /** Test Google Calendar API with current credentials. */
    private fun verifyCalendar(): Response {
        val calendarApiKey = prefs.getString("calendar_api_key", "") ?: ""
        val calendarId = (prefs.getString("calendar_id", "") ?: "").ifBlank { "primary" }
        val hasOAuth = prefs.getString("google_oauth_refresh_token", "")?.isNotBlank() == true
        Log.d(TAG, "verifyCalendar start hasOAuth=$hasOAuth hasApiKey=${calendarApiKey.isNotBlank()} calendarId=$calendarId")

        if (calendarApiKey.isBlank() && !hasOAuth) {
            return jsonResponse(JSONObject().apply {
                put("service", "calendar")
                put("status", "not_configured")
                put("message", "No OAuth token or Calendar API key configured")
                put("has_oauth", false)
                put("has_api_key", false)
                put("calendar_id", calendarId)
            })
        }

        return try {
            val mgr = oauthManager
            val client = GoogleCalendarClient(
                apiKeyProvider = { calendarApiKey.takeIf { it.isNotBlank() } },
                accessTokenProvider = {
                    if (mgr != null && hasOAuth) runBlocking { mgr.getValidAccessToken() } else null
                },
                context = context
            )
            val result = runBlocking { client.fetchUpcomingEvents(calendarId, maxResults = 5) }

            when (result) {
                is GoogleCalendarClient.CalendarResult.Success -> {
                    Log.d(TAG, "verifyCalendar success events=${result.events.size}")
                    val eventSummary = if (result.events.isEmpty()) {
                        "No events in next 24h"
                    } else {
                        result.events.take(3).joinToString(", ") { it.summary }
                    }
                    jsonResponse(JSONObject().apply {
                        put("service", "calendar")
                        put("status", "success")
                        put("message", "Connected! ${result.events.size} event(s): $eventSummary")
                        put("has_oauth", hasOAuth)
                        put("has_api_key", calendarApiKey.isNotBlank())
                        put("calendar_id", calendarId)
                        put("event_count", result.events.size)
                    })
                }
                is GoogleCalendarClient.CalendarResult.ApiKeyMissing -> jsonResponse(JSONObject().apply {
                    Log.w(TAG, "verifyCalendar api key missing/invalid")
                    put("service", "calendar")
                    put("status", "failed")
                    put("message", "API key is missing or invalid. Enable Calendar API in GCP and check your key.")
                    put("has_oauth", hasOAuth)
                    put("has_api_key", calendarApiKey.isNotBlank())
                    put("calendar_id", calendarId)
                })
                is GoogleCalendarClient.CalendarResult.Error -> jsonResponse(JSONObject().apply {
                    Log.e(TAG, "verifyCalendar error message=${result.message} code=${result.code}")
                    put("service", "calendar")
                    put("status", "failed")
                    put("message", result.message)
                    put("has_oauth", hasOAuth)
                    put("has_api_key", calendarApiKey.isNotBlank())
                    put("calendar_id", calendarId)
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Calendar verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "calendar")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_oauth", hasOAuth)
                put("has_api_key", calendarApiKey.isNotBlank())
                put("calendar_id", calendarId)
            })
        }
    }

    /** Test Google Directions API with current credentials. */
    private fun verifyDirections(): Response {
        val mapsApiKey = prefs.getString("google_maps_api_key", "") ?: ""

        if (mapsApiKey.isBlank()) {
            return jsonResponse(JSONObject().apply {
                put("service", "directions")
                put("status", "not_configured")
                put("message", "Google Maps API key not configured")
                put("has_api_key", false)
            })
        }

        return try {
            val client = GoogleDirectionsClient(apiKeyProvider = { mapsApiKey }, context = context)
            val result = runBlocking {
                client.getDirections("Times Square, NYC", "Central Park, NYC", "driving")
            }

            when (result) {
                is GoogleDirectionsClient.DirectionsResult.Success -> jsonResponse(JSONObject().apply {
                    put("service", "directions")
                    put("status", "success")
                    put("message", "Connected! Test route: ${result.distance}, ${result.duration}")
                    put("has_api_key", true)
                })
                is GoogleDirectionsClient.DirectionsResult.ApiKeyMissing -> jsonResponse(JSONObject().apply {
                    put("service", "directions")
                    put("status", "failed")
                    put("message", "API key is invalid. Enable Directions API in GCP.")
                    put("has_api_key", true)
                })
                is GoogleDirectionsClient.DirectionsResult.Error -> jsonResponse(JSONObject().apply {
                    put("service", "directions")
                    put("status", "failed")
                    put("message", result.message)
                    put("has_api_key", true)
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Directions verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "directions")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_api_key", mapsApiKey.isNotBlank())
            })
        }
    }

    /** Test Google Tasks API with current OAuth credentials. */
    private fun verifyTasks(): Response {
        val hasOAuth = prefs.getString("google_oauth_refresh_token", "")?.isNotBlank() == true

        if (!hasOAuth) {
            return jsonResponse(JSONObject().apply {
                put("service", "tasks")
                put("status", "not_configured")
                put("message", "OAuth authorization required for Tasks API. Complete Step 6 and re-authorize with Tasks scope.")
                put("has_oauth", false)
            })
        }

        return try {
            val mgr = oauthManager ?: return jsonResponse(JSONObject().apply {
                put("service", "tasks")
                put("status", "failed")
                put("message", "OAuth manager not initialized")
                put("has_oauth", true)
            })

            val client = GoogleTasksClient(
                accessTokenProvider = { runBlocking { mgr.getValidAccessToken() } },
                context = context
            )
            val result = runBlocking { client.fetchTasks(maxResults = 3) }

            when (result) {
                is GoogleTasksClient.TasksResult.Success -> {
                    val taskSummary = if (result.tasks.isEmpty()) {
                        "No pending tasks"
                    } else {
                        result.tasks.take(3).joinToString(", ") { it.title }
                    }
                    jsonResponse(JSONObject().apply {
                        put("service", "tasks")
                        put("status", "success")
                        put("message", "Connected! ${result.tasks.size} task(s): $taskSummary")
                        put("has_oauth", true)
                        put("task_count", result.tasks.size)
                    })
                }
                is GoogleTasksClient.TasksResult.AuthRequired ->
                    jsonResponse(JSONObject().apply {
                        put("service", "tasks")
                        put("status", "failed")
                        put("message", "OAuth token missing or expired. Re-authorize with Tasks scope.")
                        put("has_oauth", true)
                    })
                is GoogleTasksClient.TasksResult.Error ->
                    jsonResponse(JSONObject().apply {
                        put("service", "tasks")
                        put("status", "failed")
                        put("message", result.message)
                        put("has_oauth", true)
                    })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tasks verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "tasks")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_oauth", true)
            })
        }
    }

    /** Test Google Places API (Nearby Search) with current Maps API key. */
    private fun verifyPlaces(): Response {
        val mapsApiKey = prefs.getString("google_maps_api_key", "") ?: ""

        if (mapsApiKey.isBlank()) {
            return jsonResponse(JSONObject().apply {
                put("service", "places")
                put("status", "not_configured")
                put("message", "Google Maps API key not configured. Places API uses the same key as Directions.")
                put("has_api_key", false)
            })
        }

        return try {
            val client = GooglePlacesClient(apiKeyProvider = { mapsApiKey }, context = context)
            // Test with a search for restaurants near a known location (Houston, TX)
            val result = runBlocking {
                client.searchNearby(
                    latitude = 29.7604,
                    longitude = -95.3698,
                    types = listOf("restaurant"),
                    radiusMeters = 1000.0,
                    maxResults = 3
                )
            }

            when (result) {
                is GooglePlacesClient.PlacesResult.Success -> {
                    val placeSummary = if (result.places.isEmpty()) {
                        "No restaurants found (API works but no results at test location)"
                    } else {
                        result.places.take(3).joinToString(", ") { place ->
                            val open = when (place.isOpen) {
                                true -> "Open"
                                false -> "Closed"
                                null -> ""
                            }
                            "${place.name}${if (open.isNotBlank()) " ($open)" else ""}"
                        }
                    }
                    jsonResponse(JSONObject().apply {
                        put("service", "places")
                        put("status", "success")
                        put("message", "Connected! ${result.places.size} place(s): $placeSummary")
                        put("has_api_key", true)
                        put("place_count", result.places.size)
                    })
                }
                is GooglePlacesClient.PlacesResult.ApiKeyMissing -> jsonResponse(JSONObject().apply {
                    put("service", "places")
                    put("status", "failed")
                    put("message", "API key is missing or invalid.")
                    put("has_api_key", mapsApiKey.isNotBlank())
                })
                is GooglePlacesClient.PlacesResult.Error -> jsonResponse(JSONObject().apply {
                    put("service", "places")
                    put("status", "failed")
                    put("message", result.message)
                    put("has_api_key", true)
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Places verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "places")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_api_key", mapsApiKey.isNotBlank())
            })
        }
    }

    /** Test GPS location availability on the device. */
    private fun verifyLocation(): Response {
        val loc = locationProvider?.invoke()

        if (loc == null) {
            return jsonResponse(JSONObject().apply {
                put("service", "location")
                put("status", "failed")
                put("message", "Device location not available. Make sure Location Services are enabled " +
                    "on the glasses (Settings → Location → On) and the app has location permission.")
                put("has_gps", false)
            })
        }

        val ageSeconds = (System.currentTimeMillis() - loc.timestampMs) / 1000
        val fresh = if (ageSeconds < 300) "current" else "${ageSeconds / 60}min old"
        val sourceLabel =
            when (loc.provider) {
                "ip_geolocation" -> "Approximate network location"
                else -> "Location active"
            }
        return jsonResponse(JSONObject().apply {
            put("service", "location")
            put("status", "success")
            put("message", "$sourceLabel: Lat: ${"%.6f".format(loc.latitude)}, " +
                "Lng: ${"%.6f".format(loc.longitude)} " +
                "(accuracy: ${loc.accuracyMeters?.toInt() ?: "?"}m, $fresh)" +
                (loc.altitudeMeters?.let { alt: Double -> ", alt: ${alt.toInt()}m" } ?: "") +
                (loc.speedMps?.let { spd: Float -> if (spd > 0.5f) ", speed: ${"%.1f".format(spd * 2.237)}mph" else "" } ?: ""))
            put("has_gps", loc.provider != "ip_geolocation")
            put("provider", loc.provider ?: JSONObject.NULL)
            put("latitude", loc.latitude)
            put("longitude", loc.longitude)
            put("accuracy_meters", loc.accuracyMeters ?: -1)
            put("age_seconds", ageSeconds)
        })
    }

    /** Test Google Directions API with live GPS (origin = device) to a known destination. */
    private fun verifyTraffic(): Response {
        val mapsApiKey = prefs.getString("google_maps_api_key", "") ?: ""
        val loc = locationProvider?.invoke()

        if (mapsApiKey.isBlank()) {
            return jsonResponse(JSONObject().apply {
                put("service", "traffic")
                put("status", "not_configured")
                put("message", "Google Maps API key not configured. Set it above and save first.")
                put("has_api_key", false)
                put("has_gps", loc != null)
            })
        }

        if (loc == null) {
            return jsonResponse(JSONObject().apply {
                put("service", "traffic")
                put("status", "failed")
                put("message", "GPS not available — cannot test traffic from your current location. " +
                    "Enable Location Services on the glasses.")
                put("has_api_key", true)
                put("has_gps", false)
            })
        }

        return try {
            val origin = "${loc.latitude},${loc.longitude}"
            // Use a well-known nearby city as the destination test
            val destination = "Houston, TX"
            val client = GoogleDirectionsClient(apiKeyProvider = { mapsApiKey }, context = context)
            val result = runBlocking {
                client.getDirections(origin = origin, destination = destination)
            }

            when (result) {
                is GoogleDirectionsClient.DirectionsResult.Success -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "traffic")
                        put("status", "success")
                        put("message", "Routes API working! From your location to $destination: " +
                            "${result.duration} (${result.distance})" +
                            (result.durationInTraffic?.let { " — with traffic: $it" } ?: ""))
                        put("has_api_key", true)
                        put("has_gps", true)
                        put("origin", origin)
                        put("destination", destination)
                    })
                }
                is GoogleDirectionsClient.DirectionsResult.Error -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "traffic")
                        put("status", "failed")
                        put("message", "Routes API error: ${result.message}")
                        put("has_api_key", true)
                        put("has_gps", true)
                    })
                }
                is GoogleDirectionsClient.DirectionsResult.ApiKeyMissing -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "traffic")
                        put("status", "failed")
                        put("message", "API key missing or invalid.")
                        put("has_api_key", false)
                        put("has_gps", true)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Traffic verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "traffic")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_api_key", mapsApiKey.isNotBlank())
                put("has_gps", true)
            })
        }
    }

    /** Test Google Air Quality API with live GPS location. */
    private fun verifyAirQuality(): Response {
        val mapsApiKey = prefs.getString("google_maps_api_key", "") ?: ""
        val loc = locationProvider?.invoke()

        if (mapsApiKey.isBlank()) {
            return jsonResponse(JSONObject().apply {
                put("service", "air_quality")
                put("status", "not_configured")
                put("message", "Google Maps API key not configured. Air Quality uses the same key.")
                put("has_api_key", false)
                put("has_gps", loc != null)
            })
        }

        if (loc == null) {
            return jsonResponse(JSONObject().apply {
                put("service", "air_quality")
                put("status", "failed")
                put("message", "GPS not available — cannot test air quality from the glasses.")
                put("has_api_key", true)
                put("has_gps", false)
            })
        }

        return try {
            val client = GoogleAirQualityClient(apiKeyProvider = { mapsApiKey }, context = context)
            when (
                val result = runBlocking {
                    client.fetchCurrentConditions(loc.latitude, loc.longitude)
                }
            ) {
                is GoogleAirQualityClient.AirQualityResult.Success -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "air_quality")
                        put("status", "success")
                        put(
                            "message",
                            "Connected! ${result.index.label}" +
                                (result.index.dominantPollutant?.let { " — dominant pollutant: $it" } ?: "")
                        )
                        put("has_api_key", true)
                        put("has_gps", true)
                        put("aqi", result.index.aqi ?: JSONObject.NULL)
                    })
                }
                is GoogleAirQualityClient.AirQualityResult.ApiKeyMissing -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "air_quality")
                        put("status", "failed")
                        put("message", "Air Quality API key missing or invalid.")
                        put("has_api_key", false)
                        put("has_gps", true)
                    })
                }
                is GoogleAirQualityClient.AirQualityResult.Error -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "air_quality")
                        put("status", "failed")
                        put("message", result.message)
                        put("has_api_key", true)
                        put("has_gps", true)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Air quality verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "air_quality")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_api_key", mapsApiKey.isNotBlank())
                put("has_gps", loc != null)
            })
        }
    }

    /** Test the configured research provider with a short sample prompt. */
    private fun verifyResearch(): Response {
        val provider = (prefs.getString("research_provider", "") ?: "").trim().ifBlank { "gemini" }
        val router = ResearchRouter(
            providerProvider = { provider },
            apiKeyProvider = { prefs.getString("research_api_key", "")?.trim() },
            modelProvider = { prefs.getString("research_model", "")?.trim() },
            geminiFallbackApiKeyProvider = {
                (prefs.getString("gemini_api_key", "") ?: "").trim().ifBlank {
                    BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }
                }
            },
            context = context
        )

        return try {
            when (val result = runBlocking { router.research("current capabilities of TapInsight") }) {
                is ResearchRouter.ResearchResult.Success -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "research")
                        put("status", "success")
                        put("message", "Connected! ${result.provider} / ${result.model}")
                        put("provider", result.provider)
                        put("model", result.model)
                        put("preview", result.text.take(240))
                    })
                }
                is ResearchRouter.ResearchResult.ApiKeyMissing -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "research")
                        put("status", "not_configured")
                        put("message", "Research provider API key missing. Configure it in the companion app.")
                        put("provider", provider)
                    })
                }
                is ResearchRouter.ResearchResult.Error -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "research")
                        put("status", "failed")
                        put("message", result.message)
                        put("provider", provider)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Research verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "research")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("provider", provider)
            })
        }
    }

    private fun jsonResponse(json: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())

    /** Simple HTML result page shown after OAuth redirect. */
    private fun oauthResultPage(success: Boolean, message: String): String {
        val color = if (success) "#4caf50" else "#f44336"
        val icon = if (success) "&#10003;" else "&#10007;"
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>TapInsight OAuth</title>
            <style>
              body { font-family: -apple-system, sans-serif; display: flex; justify-content: center;
                     align-items: center; height: 100vh; margin: 0; background: #111; color: #eee; }
              .card { text-align: center; padding: 40px; border-radius: 12px; background: #1a1a1a;
                      border: 1px solid #333; max-width: 400px; }
              .icon { font-size: 48px; color: $color; margin-bottom: 16px; }
              .msg { font-size: 16px; line-height: 1.5; }
              a { color: #6ea8fe; }
            </style></head>
            <body><div class="card">
              <div class="icon">$icon</div>
              <div class="msg">$message</div>
              ${if (success) "" else "<p><a href='/'>Back to Setup</a></p>"}
            </div></body></html>
        """.trimIndent()
    }

    fun startServer() {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.d(TAG, "Companion server started on port $listeningPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start companion server: ${e.message}", e)
        }
    }

    fun stopServer() {
        stop()
        Log.d(TAG, "Companion server stopped")
    }
}
