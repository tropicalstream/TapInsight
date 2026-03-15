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

        private val ASK_MAPS_EXPLORE_PATTERNS = listOf(
            // "tell me about the Golden Gate Bridge", "what is the Eiffel Tower"
            Regex("(?i)\\b(tell me about|what('?s| is)|explore|describe|info on|about)\\b.{1,60}\\b(bridge|tower|museum|monument|park|building|church|cathedral|stadium|arena|temple|palace|castle|plaza|square|landmark|statue|memorial)\\b"),
            // "explore [place name]"
            Regex("(?i)^\\s*(explore|tell me about|what('?s| is))\\s+(.{3,})\\s*$"),
            // "what landmarks are nearby", "nearby landmarks"
            Regex("(?i)\\b(landmark|landmarks|notable place|points? of interest)s?\\b.{0,20}\\b(near|around|nearby|close|here)\\b"),
            Regex("(?i)\\b(near|around|nearby)\\b.{0,20}\\b(landmark|landmarks|notable|points? of interest)s?\\b")
        )

        private val ASK_MAPS_3D_NAV_PATTERNS = listOf(
            // "navigate 3D to X", "3D directions to X", "show me in 3D"
            Regex("(?i)\\b(navigate|navigation|directions?|drive|go)\\s+(in\\s+)?3[dD]\\s+(to\\s+)?"),
            Regex("(?i)\\b3[dD]\\s+(navigate|navigation|directions?|route|view)\\b"),
            Regex("(?i)\\bshow\\s+(me\\s+)?(in\\s+)?3[dD]\\b")
        )

        private val RESEARCH_PATTERNS = listOf(
            Regex("(?i)^\\s*research\\s+(.+?)\\s*$"),
            Regex("(?i)^\\s*(?:please\\s+)?research\\s+(?:for me\\s+)?(.+?)\\s*$"),
            Regex("(?i)^\\s*(?:do|run)\\s+research\\s+on\\s+(.+?)\\s*$"),
            Regex("(?i)^\\s*(?:give me|do)\\s+a\\s+deep\\s+dive\\s+on\\s+(.+?)\\s*$"),
            Regex("(?i)^\\s*(?:analyze|brief me on)\\s+(.+?)\\s*$")
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

        // ── Ask Maps 3D Nav ("navigate 3D to X") ──────────────────
        if (ASK_MAPS_3D_NAV_PATTERNS.any { it.containsMatchIn(text) }) {
            return handleAskMaps3DNav(text)
        }

        // ── Ask Maps Explore ("tell me about the Golden Gate Bridge") ─
        if (ASK_MAPS_EXPLORE_PATTERNS.any { it.containsMatchIn(text) }) {
            return handleAskMapsExplore(text)
        }

        val researchTopic = RESEARCH_PATTERNS
            .firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (researchTopic != null) {
            return handleResearch(text, researchTopic)
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
                "[Use these results faithfully. Lead with the nearest OPEN option if one exists. " +
                "If the closest place is closed, say that briefly and then promote the nearest open one. " +
                "Preserve ETA, weather, and Maps details instead of replacing them with a generic summary. " +
                "The user asked: \"$transcript\"]"
        )
    }

    // ── Handler: Routes / Traffic ─────────────────────────────────────

    private suspend fun handleRoutes(transcript: String): AssistResult {
        // Extract origin and destination from transcript
        val (explicitOrigin, destination) = extractOriginAndDestination(transcript)

        if (destination.isBlank()) {
            return AssistResult(
                toolName = "google_routes",
                resultText = "Could not determine destination",
                contextPrompt = "[SYSTEM: The user asked about traffic/directions but I couldn't determine " +
                    "the destination. Ask them: 'Where would you like directions to?']"
            )
        }

        // Use explicit origin if user spoke one, otherwise fall back to GPS
        val origin: String = if (explicitOrigin.isNotBlank()) {
            explicitOrigin
        } else {
            val loc = locationProvider()
            if (loc == null) {
                return AssistResult(
                    toolName = "google_routes",
                    resultText = "GPS not available for route calculation",
                    contextPrompt = "[SYSTEM: The user asked about directions/traffic but GPS is not available. " +
                        "Tell them to enable Location Services on their glasses, or say 'from [address] to [destination]'.]"
                )
            }
            "${loc.latitude},${loc.longitude}"
        }

        val args = "{\"origin\":\"$origin\",\"destination\":\"$destination\"}"

        Log.d(TAG, "Routes assist: origin=$origin destination=$destination explicitOrigin=${explicitOrigin.isNotBlank()}")

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

    private suspend fun handleResearch(transcript: String, topic: String): AssistResult {
        val args = JSONObject().put("topic", topic).toString()
        Log.d(TAG, "Research assist: topic=$topic")
        val result = toolDispatcher.dispatch("research_topic", args)
        val resultText = result.getOrElse { "Research unavailable right now." }

        return AssistResult(
            toolName = "research_topic",
            resultText = resultText,
            contextPrompt = "[TOOL RESULT — research_topic]\n$resultText\n" +
                "[Summarize this research naturally for the user. The user asked: \"$transcript\"]"
        )
    }

    // ── Handler: Ask Maps — Explore ──────────────────────────────────

    private suspend fun handleAskMapsExplore(transcript: String): AssistResult {
        // Extract the place/query from transcript
        val query = extractExploreQuery(transcript)
        if (query.isBlank()) {
            return AssistResult(
                toolName = "ask_maps",
                resultText = "Could not determine what place to explore",
                contextPrompt = "[SYSTEM: The user asked about a place but I couldn't determine which one. " +
                    "Ask them: 'Which place would you like me to tell you about?']"
            )
        }

        val action = if (transcript.lowercase().contains("landmark") ||
            transcript.lowercase().contains("point of interest")) "nearby_landmarks" else "explore"

        val args = "{\"action\":\"$action\",\"query\":\"$query\"}"
        Log.d(TAG, "Ask Maps explore: query=$query action=$action")

        val result = toolDispatcher.dispatch("ask_maps", args)
        val resultText = result.getOrElse { "Place exploration failed: ${it.message}" }

        return AssistResult(
            toolName = "ask_maps",
            resultText = resultText,
            contextPrompt = "[TOOL RESULT — ask_maps explore]\n$resultText\n" +
                "[Share the AI-generated place summary naturally. Include key details like rating, " +
                "hours, and any interesting facts. If a 3D navigation link is available, mention " +
                "the user can say 'navigate 3D' to see it in photorealistic 3D. " +
                "The user asked: \"$transcript\"]"
        )
    }

    // ── Handler: Ask Maps — 3D Navigation ──────────────────────────

    private suspend fun handleAskMaps3DNav(transcript: String): AssistResult {
        val destination = extract3DNavDestination(transcript)
        if (destination.isBlank()) {
            return AssistResult(
                toolName = "ask_maps",
                resultText = "Could not determine 3D navigation destination",
                contextPrompt = "[SYSTEM: The user asked for 3D navigation but I couldn't determine the destination. " +
                    "Ask them: 'Where would you like 3D navigation to?']"
            )
        }

        val args = "{\"action\":\"navigate_3d\",\"destination\":\"$destination\"}"
        Log.d(TAG, "Ask Maps 3D nav: destination=$destination")

        val result = toolDispatcher.dispatch("ask_maps", args)
        val resultText = result.getOrElse { "3D navigation failed: ${it.message}" }

        return AssistResult(
            toolName = "ask_maps",
            resultText = resultText,
            contextPrompt = "[TOOL RESULT — ask_maps navigate_3d]\n$resultText\n" +
                "[Tell the user about the route with driving/walking ETAs. " +
                "Mention the 3D photorealistic view is loading. " +
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

    /**
     * Extract both an explicit origin and a destination from the user's transcript.
     * Handles patterns like:
     *   "directions from 123 Main St to 456 Oak Ave"
     *   "navigate from downtown to the airport"
     *   "how do I get from work to home"
     *   "directions to 456 Oak Ave"  (no origin → empty string)
     */
    private fun extractOriginAndDestination(transcript: String): Pair<String, String> {
        // Pattern 1: "from <origin> to <destination>"
        val fromTo = Regex("(?i)\\bfrom\\s+(.+?)\\s+to\\s+(.+?)\\s*[?.!]*$").find(transcript)
        if (fromTo != null) {
            val origin = fromTo.groupValues[1].trim().replace(Regex("[?.!]+$"), "").trim()
            val dest = fromTo.groupValues[2].trim().replace(Regex("[?.!]+$"), "").trim()
            if (origin.isNotBlank() && dest.isNotBlank()) {
                return origin to dest
            }
        }

        // No explicit origin — fall back to destination-only extraction
        return "" to extractDestination(transcript)
    }

    private fun extractExploreQuery(transcript: String): String {
        val patterns = listOf(
            Regex("(?i)(?:tell me about|what(?:'?s| is)|explore|describe|info on)\\s+(?:the\\s+)?(.+?)\\s*[?.!]*$"),
            Regex("(?i)(?:nearby|near me|around here)\\s+(.+?)\\s*[?.!]*$")
        )
        for (pattern in patterns) {
            val match = pattern.find(transcript)
            if (match != null) {
                val query = match.groupValues[1].trim()
                    .replace(Regex("[?.!]+$"), "").trim()
                if (query.isNotBlank()) return query
            }
        }
        // Fallback: strip common prefixes and return the rest
        return transcript
            .replace(Regex("(?i)^\\s*(tell me about|what('?s| is)|explore|describe|info on|nearby)\\s+"), "")
            .replace(Regex("[?.!]+$"), "")
            .trim()
    }

    private fun extract3DNavDestination(transcript: String): String {
        val patterns = listOf(
            Regex("(?i)(?:navigate|navigation|directions?)\\s+(?:in\\s+)?3[dD]\\s+(?:to\\s+)?(.+?)\\s*[?.!]*$"),
            Regex("(?i)3[dD]\\s+(?:navigate|navigation|directions?|route)\\s+(?:to\\s+)?(.+?)\\s*[?.!]*$"),
            Regex("(?i)show\\s+(?:me\\s+)?(?:in\\s+)?3[dD]\\s+(?:to\\s+)?(.+?)\\s*[?.!]*$")
        )
        for (pattern in patterns) {
            val match = pattern.find(transcript)
            if (match != null) {
                val dest = match.groupValues[1].trim()
                    .replace(Regex("[?.!]+$"), "").trim()
                if (dest.isNotBlank()) return dest
            }
        }
        // Fallback: extract destination from the "to X" pattern
        val toMatch = Regex("(?i)\\bto\\s+(.{3,})$").find(transcript)
        if (toMatch != null) {
            return toMatch.groupValues[1].replace(Regex("[?.!]+$"), "").trim()
        }
        return ""
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
