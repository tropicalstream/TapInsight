package com.rayneo.visionclaw.core.tools

import android.util.Log
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import org.json.JSONObject

/**
 * Client-side tool assist that bypasses Gemini native-audio's broken function
 * calling.  When the user's spoken input matches a tool-worthy pattern we
 * proactively execute the tool locally and return the result text so the caller
 * can inject it into the Live session as a clientContent message.
 *
 * This approach treats the native-audio model as a *conversational layer* —
 * we feed it the data and it speaks about it, instead of waiting for it to
 * call tools (which it almost never does with gemini-2.5-flash-native-audio).
 */
class ToolAssistEngine(
    private val toolDispatcher: ToolDispatcher,
    private val locationProvider: () -> DeviceLocationContext?
) {

    companion object {
        private const val TAG = "ToolAssistEngine"

        // ── Pattern groups ────────────────────────────────────────────
        // Each entry maps a regex to the tool name + arg builder.

        private val PLACES_PATTERNS = listOf(
            // "find a cafe near me", "open restaurants nearby", "gas station close by"
            Regex("(?i)\\b(find|show|any|are there|where('?s| is| are)|look for|search for|nearest|closest|nearby|near me|open)\\b.{0,40}\\b(restaurant|cafe|coffee|food|eat|gas station|fuel|pharmacy|drug ?store|grocery|supermarket|hospital|clinic|bar|pub|bakery|bank|atm|parking|gym|hotel|motel|lodging|store|shop)s?\\b"),
            // "coffee near me", "restaurants around here", "places to eat"
            Regex("(?i)\\b(restaurant|cafe|coffee|food|gas station|fuel|pharmacy|grocery|supermarket|hospital|bar|bakery|bank|atm|parking|gym|hotel|shop|store)s?\\b.{0,30}\\b(near|around|close|nearby|open|here)\\b"),
            // "I'm hungry", "I need coffee", "where can I eat"
            Regex("(?i)\\b(i'?m hungry|i need (coffee|food|gas|fuel)|where can i (eat|get (coffee|food|gas)))\\b"),
            // "what's open near me", "places open now"
            Regex("(?i)\\b(what'?s|places?) open\\b.{0,20}\\b(near|around|here|now)\\b")
        )

        private val ROUTES_PATTERNS = listOf(
            // "directions to X", "navigate to X", "how do I get to X"
            Regex("(?i)\\b(direction|navigate|route|driving|drive)s?\\s+(to|from)\\b"),
            Regex("(?i)\\bhow (do i|to) get to\\b"),
            // "traffic to Houston", "traffic on I-10", "how's traffic"
            Regex("(?i)\\b(traffic|commute|travel time|drive time|eta)\\b.{0,40}\\b(to|on|from|for|like|right now)\\b"),
            Regex("(?i)\\bhow('?s| is| long)\\b.{0,20}\\b(traffic|drive|commute)\\b")
        )

        private val LOCATION_PATTERNS = listOf(
            // "where am I", "what's my location", "my coordinates"
            Regex("(?i)\\bwhere am i\\b"),
            Regex("(?i)\\b(what'?s|what is) my (location|position|coordinates|address)\\b"),
            Regex("(?i)\\bmy (current )?(location|position|coordinates|gps)\\b"),
            Regex("(?i)\\bwhere (are we|is this|is here)\\b")
        )
    }

    data class AssistResult(
        val toolName: String,
        val resultText: String,
        val contextPrompt: String   // what we inject into the Live session
    )

    /**
     * Analyse the user's spoken transcript and, if it matches a tool pattern,
     * proactively execute the tool and return the result.  Returns null when
     * no pattern matches (i.e. let Gemini handle it normally).
     */
    suspend fun maybeAssist(transcript: String): AssistResult? {
        val text = transcript.trim()
        if (text.length < 4) return null

        // ── Location ("where am I?") ─────────────────────────────────
        if (LOCATION_PATTERNS.any { it.containsMatchIn(text) }) {
            return handleLocation(text)
        }

        // ── Places ("find a cafe near me") ───────────────────────────
        if (PLACES_PATTERNS.any { it.containsMatchIn(text) }) {
            return handlePlaces(text)
        }

        // ── Routes / Traffic ("traffic to Houston") ──────────────────
        if (ROUTES_PATTERNS.any { it.containsMatchIn(text) }) {
            return handleRoutes(text)
        }

        return null
    }

    // ── Handler: Location ─────────────────────────────────────────────

    private fun handleLocation(transcript: String): AssistResult {
        val loc = locationProvider()
        if (loc == null) {
            return AssistResult(
                toolName = "location",
                resultText = "GPS not available",
                contextPrompt = "[SYSTEM: The user asked about their location but GPS is not available. " +
                    "Tell them to enable Location Services on their glasses.]"
            )
        }
        val ageSeconds = (System.currentTimeMillis() - loc.timestampMs) / 1000
        val fresh = if (ageSeconds < 300) "current" else "${ageSeconds / 60} minutes ago"
        val info = buildString {
            append("Latitude: ${loc.latitude}, Longitude: ${loc.longitude}")
            append(" (accuracy: ${loc.accuracyMeters?.toInt() ?: "unknown"}m, $fresh)")
            loc.altitudeMeters?.let { alt: Double -> append(", altitude: ${alt.toInt()}m") }
            loc.speedMps?.let { spd: Float -> if (spd > 0.5f) append(", speed: ${"%.1f".format(spd * 2.237)} mph") }
        }
        return AssistResult(
            toolName = "location",
            resultText = info,
            contextPrompt = "[TOOL RESULT — location]\n$info\n" +
                "[Describe the user's approximate location using these coordinates. " +
                "Mention the nearest city/area and any relevant context. The user asked: \"$transcript\"]"
        )
    }

    // ── Handler: Places ───────────────────────────────────────────────

    private suspend fun handlePlaces(transcript: String): AssistResult {
        val loc = locationProvider()
        if (loc == null) {
            return AssistResult(
                toolName = "google_places",
                resultText = "GPS not available for nearby search",
                contextPrompt = "[SYSTEM: The user asked about nearby places but GPS is not available. " +
                    "Tell them to enable Location Services on their glasses.]"
            )
        }

        // Extract the place type from the transcript
        val placeType = extractPlaceType(transcript)
        val args = buildString {
            append("{\"type\":\"$placeType\"")
            // Pass the raw query too so the tool can use it
            val cleaned = transcript.replace(Regex("(?i)(near me|nearby|around here|close by|around|here)"), "").trim()
            if (cleaned.isNotBlank()) append(",\"query\":\"$cleaned\"")
            append(",\"radius\":\"1500\"}")
        }

        Log.d(TAG, "Places assist: type=$placeType args=$args")

        val result = toolDispatcher.dispatch("google_places", args)
        val resultText = result.getOrElse { "Places search failed: ${it.message}" }

        return AssistResult(
            toolName = "google_places",
            resultText = resultText,
            contextPrompt = "[TOOL RESULT — google_places nearby search]\n$resultText\n" +
                "[Read these results to the user naturally. Highlight which places are open now, " +
                "their ratings, and distances. The user asked: \"$transcript\"]"
        )
    }

    // ── Handler: Routes / Traffic ─────────────────────────────────────

    private suspend fun handleRoutes(transcript: String): AssistResult {
        val loc = locationProvider()
        if (loc == null) {
            return AssistResult(
                toolName = "google_routes",
                resultText = "GPS not available for route calculation",
                contextPrompt = "[SYSTEM: The user asked about directions/traffic but GPS is not available. " +
                    "Tell them to enable Location Services on their glasses.]"
            )
        }

        // Extract destination from transcript
        val destination = extractDestination(transcript)
        if (destination.isBlank()) {
            return AssistResult(
                toolName = "google_routes",
                resultText = "Could not determine destination",
                contextPrompt = "[SYSTEM: The user asked about traffic/directions but I couldn't determine " +
                    "the destination. Ask them: 'Where would you like directions to?']"
            )
        }

        val origin = "${loc.latitude},${loc.longitude}"
        val args = "{\"origin\":\"$origin\",\"destination\":\"$destination\"}"

        Log.d(TAG, "Routes assist: origin=$origin destination=$destination")

        val result = toolDispatcher.dispatch("google_routes", args)
        val resultText = result.getOrElse { "Route lookup failed: ${it.message}" }

        return AssistResult(
            toolName = "google_routes",
            resultText = resultText,
            contextPrompt = "[TOOL RESULT — google_routes]\n$resultText\n" +
                "[Tell the user about the route, travel time, and traffic conditions naturally. " +
                "The user asked: \"$transcript\"]"
        )
    }

    // ── Extractors ────────────────────────────────────────────────────

    private fun extractPlaceType(transcript: String): String {
        val lower = transcript.lowercase()
        return when {
            lower.contains("coffee") || lower.contains("cafe") -> "cafe"
            lower.contains("restaurant") || lower.contains("food") ||
                lower.contains("eat") || lower.contains("hungry") -> "restaurant"
            lower.contains("gas") || lower.contains("fuel") -> "gas_station"
            lower.contains("pharmacy") || lower.contains("drug") -> "pharmacy"
            lower.contains("grocery") || lower.contains("supermarket") -> "supermarket"
            lower.contains("hospital") || lower.contains("clinic") ||
                lower.contains("emergency") -> "hospital"
            lower.contains("bar") || lower.contains("pub") -> "bar"
            lower.contains("bakery") -> "bakery"
            lower.contains("bank") || lower.contains("atm") -> "bank"
            lower.contains("parking") -> "parking"
            lower.contains("gym") || lower.contains("fitness") -> "gym"
            lower.contains("hotel") || lower.contains("motel") ||
                lower.contains("lodging") -> "lodging"
            lower.contains("store") || lower.contains("shop") -> "store"
            else -> "restaurant" // reasonable default
        }
    }

    private fun extractDestination(transcript: String): String {
        val lower = transcript.lowercase()
        // Strip known prefixes to isolate the destination
        val patterns = listOf(
            Regex("(?i)(give me |get me |show me )?(direction|navigate|route|driving)s?\\s+(to|for)\\s+"),
            Regex("(?i)how (do i|to|long to) (get to|drive to|reach)\\s+"),
            Regex("(?i)(traffic|commute|drive time|travel time|eta)\\s+(to|for|from here to)\\s+"),
            Regex("(?i)how('?s| is| long is)\\s+(the )?(traffic|drive|commute)\\s+(to|for)\\s+")
        )
        for (pattern in patterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val dest = transcript.substring(match.range.last + 1).trim()
                    .replace(Regex("[?.!]+$"), "")  // strip trailing punctuation
                    .trim()
                if (dest.isNotBlank()) return dest
            }
        }
        // Fallback: look for "to <destination>" pattern
        val toMatch = Regex("(?i)\\bto\\s+(.{3,})$").find(lower)
        if (toMatch != null) {
            return toMatch.groupValues[1].replace(Regex("[?.!]+$"), "").trim()
        }
        return ""
    }
}
