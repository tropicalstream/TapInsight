package com.rayneo.visionclaw.core.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * GoogleCalendarClient – fetches upcoming events from a public or
 * API-key-authenticated Google Calendar.
 *
 * Error-handling contract:
 *   • Missing / blank API key → [CalendarResult.ApiKeyMissing] (no crash).
 *   • Network or server errors → [CalendarResult.Error] with message.
 *   • Success → [CalendarResult.Success] with a list of [CalendarEvent].
 */
class GoogleCalendarClient(
    private val apiKeyProvider: () -> String?,
    private val accessTokenProvider: () -> String? = { null }
) {

    companion object {
        private const val TAG = "GoogleCalendar"
        private const val BASE_URL =
            "https://www.googleapis.com/calendar/v3/calendars"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    // ── Result types ─────────────────────────────────────────────────────

    data class CalendarEvent(
        val id: String,
        val summary: String,
        val start: Date?,
        val end: Date?,
        val location: String?,
        val description: String?
    )

    data class CalendarListEntry(
        val id: String,
        val summary: String,
        val primary: Boolean,
        val backgroundColor: String?,
        val accessRole: String?
    )

    sealed class CalendarResult {
        data class Success(val events: List<CalendarEvent>) : CalendarResult()
        data class Error(val message: String, val code: Int = -1) : CalendarResult()
        object ApiKeyMissing : CalendarResult()
    }

    sealed class CalendarListResult {
        data class Success(val calendars: List<CalendarListEntry>) : CalendarListResult()
        data class Error(val message: String) : CalendarListResult()
        object AuthRequired : CalendarListResult()
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Fetch all calendars visible to the authenticated user.
     * Requires OAuth — API keys can only access public calendars by ID.
     */
    suspend fun fetchCalendarList(): CalendarListResult = withContext(Dispatchers.IO) {
        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) {
            return@withContext CalendarListResult.AuthRequired
        }

        try {
            val urlStr = "https://www.googleapis.com/calendar/v3/users/me/calendarList"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $accessToken")
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val body = BufferedReader(
                    InputStreamReader(conn.inputStream, Charsets.UTF_8)
                ).use { it.readText() }

                val json = JSONObject(body)
                val items = json.optJSONArray("items")
                    ?: return@withContext CalendarListResult.Success(emptyList())

                val calendars = (0 until items.length()).map { i ->
                    val item = items.getJSONObject(i)
                    CalendarListEntry(
                        id = item.optString("id", ""),
                        summary = item.optString("summary", "(No name)"),
                        primary = item.optBoolean("primary", false),
                        backgroundColor = item.optString("backgroundColor", null),
                        accessRole = item.optString("accessRole", null)
                    )
                }

                Log.d(TAG, "Fetched ${calendars.size} calendars")
                CalendarListResult.Success(calendars)
            } else {
                val errorBody = BufferedReader(
                    InputStreamReader(conn.errorStream ?: conn.inputStream, Charsets.UTF_8)
                ).use { it.readText() }
                Log.e(TAG, "CalendarList HTTP $responseCode: $errorBody")
                CalendarListResult.Error("Calendar list error ($responseCode)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "CalendarList request failed", e)
            CalendarListResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Fetch upcoming events from the given calendar.
     *
     * @param calendarId  Calendar ID (default: "primary").
     * @param maxResults  Maximum events to return.
     * @param timeHorizonHours How far ahead to look (default: 24h).
     */
    suspend fun fetchUpcomingEvents(
        calendarId: String = "primary",
        maxResults: Int = 10,
        timeHorizonHours: Int = 24
    ): CalendarResult = withContext(Dispatchers.IO) {

        // ── 1. Resolve auth ────────────────────────────────────────
        val accessToken = accessTokenProvider()
        val apiKey = apiKeyProvider()
        if (accessToken.isNullOrBlank() && apiKey.isNullOrBlank()) {
            Log.w(TAG, "No OAuth token or API key available for Calendar")
            return@withContext CalendarResult.ApiKeyMissing
        }

        try {
            // ── 2. Build time window ─────────────────────────────────
            val isoFormat = SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US
            ).apply { timeZone = TimeZone.getTimeZone("UTC") }

            val now = Date()
            val horizon = Calendar.getInstance().apply {
                time = now
                add(Calendar.HOUR_OF_DAY, timeHorizonHours)
            }.time

            val timeMin = URLEncoder.encode(isoFormat.format(now), "UTF-8")
            val timeMax = URLEncoder.encode(isoFormat.format(horizon), "UTF-8")
            val encodedId = URLEncoder.encode(calendarId, "UTF-8")

            // ── 3. Execute HTTP request ──────────────────────────────
            val useOAuth = !accessToken.isNullOrBlank()
            val urlStr = if (useOAuth) {
                "$BASE_URL/$encodedId/events" +
                        "?timeMin=$timeMin" +
                        "&timeMax=$timeMax" +
                        "&maxResults=$maxResults" +
                        "&singleEvents=true" +
                        "&orderBy=startTime"
            } else {
                "$BASE_URL/$encodedId/events" +
                        "?key=$apiKey" +
                        "&timeMin=$timeMin" +
                        "&timeMax=$timeMax" +
                        "&maxResults=$maxResults" +
                        "&singleEvents=true" +
                        "&orderBy=startTime"
            }

            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                if (useOAuth) {
                    setRequestProperty("Authorization", "Bearer $accessToken")
                }
            }

            val responseCode = conn.responseCode

            // ── 4. Parse response ────────────────────────────────────
            if (responseCode in 200..299) {
                val body = BufferedReader(
                    InputStreamReader(conn.inputStream, Charsets.UTF_8)
                ).use { it.readText() }

                val json = JSONObject(body)
                val items = json.optJSONArray("items") ?: return@withContext CalendarResult.Success(emptyList())

                val events = (0 until items.length()).map { i ->
                    val item = items.getJSONObject(i)
                    val startObj = item.optJSONObject("start")
                    val endObj = item.optJSONObject("end")

                    CalendarEvent(
                        id = item.optString("id", ""),
                        summary = item.optString("summary", "(No title)"),
                        start = parseEventTime(startObj),
                        end = parseEventTime(endObj),
                        location = item.optString("location", null),
                        description = item.optString("description", null)
                    )
                }

                Log.d(TAG, "Fetched ${events.size} events")
                CalendarResult.Success(events)
            } else {
                val errorBody = BufferedReader(
                    InputStreamReader(conn.errorStream ?: conn.inputStream, Charsets.UTF_8)
                ).use { it.readText() }
                Log.e(TAG, "Calendar HTTP $responseCode: $errorBody")

                // Handle expired OAuth token
                if (responseCode == 401 && useOAuth) {
                    return@withContext CalendarResult.Error(
                        "OAuth token expired. Re-authorize in TapInsight setup.", 401
                    )
                }

                // Detect invalid API key
                if (responseCode == 400 || responseCode == 403) {
                    val lower = errorBody.lowercase()
                    if (lower.contains("api key") || lower.contains("apikey")
                        || lower.contains("forbidden") || lower.contains("invalid key")) {
                        return@withContext CalendarResult.ApiKeyMissing
                    }
                }
                CalendarResult.Error("Calendar error ($responseCode)", responseCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Calendar request failed", e)
            CalendarResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Create a new event on the given calendar.
     * Requires OAuth (write access) — API keys are read-only.
     */
    suspend fun createEvent(
        calendarId: String = "primary",
        title: String,
        startTimeIso: String,
        durationMinutes: Int = 60,
        location: String? = null,
        description: String? = null
    ): CalendarResult = withContext(Dispatchers.IO) {

        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) {
            return@withContext CalendarResult.Error(
                "OAuth required to create events. Authorize in TapInsight setup."
            )
        }

        try {
            val encodedId = URLEncoder.encode(calendarId, "UTF-8")
            val urlStr = "$BASE_URL/$encodedId/events"

            // Parse start time and compute end time
            val startDate = try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(
                    startTimeIso.replace("Z", "").substringBefore("+").substringBefore("-")
                )
            } catch (e: Exception) {
                // Try other formats
                try {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(startTimeIso)
                } catch (e2: Exception) {
                    return@withContext CalendarResult.Error("Invalid start time format: $startTimeIso")
                }
            }

            val endDate = Calendar.getInstance().apply {
                time = startDate!!
                add(Calendar.MINUTE, durationMinutes)
            }.time

            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }

            val eventBody = JSONObject().apply {
                put("summary", title)
                put("start", JSONObject().put("dateTime", isoFormat.format(startDate)))
                put("end", JSONObject().put("dateTime", isoFormat.format(endDate)))
                if (!location.isNullOrBlank()) put("location", location)
                if (!description.isNullOrBlank()) put("description", description)
            }

            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }

            conn.outputStream.use { it.write(eventBody.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val body = BufferedReader(
                    InputStreamReader(conn.inputStream, Charsets.UTF_8)
                ).use { it.readText() }
                val json = JSONObject(body)
                val event = CalendarEvent(
                    id = json.optString("id", ""),
                    summary = json.optString("summary", title),
                    start = startDate,
                    end = endDate,
                    location = location,
                    description = description
                )
                Log.i(TAG, "Created event: ${event.summary}")
                CalendarResult.Success(listOf(event))
            } else {
                val errorBody = BufferedReader(
                    InputStreamReader(conn.errorStream ?: conn.inputStream, Charsets.UTF_8)
                ).use { it.readText() }
                Log.e(TAG, "Create event HTTP $responseCode: $errorBody")
                CalendarResult.Error("Failed to create event ($responseCode)", responseCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Create event failed", e)
            CalendarResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun parseEventTime(obj: JSONObject?): Date? {
        if (obj == null) return null
        val dateTimeStr = obj.optString("dateTime", "")
        val dateStr = obj.optString("date", "")
        return try {
            when {
                dateTimeStr.isNotBlank() -> {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                        .parse(dateTimeStr)
                }
                dateStr.isNotBlank() -> {
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse event time: $e")
            null
        }
    }
}
