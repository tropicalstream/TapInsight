package com.rayneo.visionclaw.core.assistant

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

sealed interface AssistantIntent {
    data class OpenWeb(
        val url: String,
        val displayLabel: String
    ) : AssistantIntent

    data class Research(
        val topic: String
    ) : AssistantIntent
}

object AssistantIntentParser {
    private val OPEN_PATTERNS = listOf(
        Regex("(?i)^\\s*(?:open|launch|go to|visit|browse to|take me to|show me)\\s+(.+?)\\s*$"),
        Regex("(?i)^\\s*(?:open up)\\s+(.+?)\\s*$")
    )

    private val RESEARCH_PATTERNS = listOf(
        Regex("(?i)^\\s*research\\s+(.+?)\\s*$"),
        Regex("(?i)^\\s*(?:please\\s+)?research\\s+(?:for me\\s+)?(.+?)\\s*$"),
        Regex("(?i)^\\s*(?:do|run)\\s+research\\s+on\\s+(.+?)\\s*$"),
        Regex("(?i)^\\s*(?:give me|do)\\s+a\\s+deep\\s+dive\\s+on\\s+(.+?)\\s*$"),
        Regex("(?i)^\\s*(?:analyze|brief me on)\\s+(.+?)\\s*$")
    )

    private val DOMAIN_REGEX =
        Regex("(?i)\\b((?:https?://)?(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?:/\\S*)?)")

    private val LOCAL_APP_TARGETS = setOf(
        "settings",
        "chat",
        "calendar",
        "camera",
        "radio",
        "tapradio",
        "browser",
        "tapbrowser",
        "hud",
        "dashboard"
    )

    private val KNOWN_SITES = linkedMapOf(
        "cnn" to "https://www.cnn.com",
        "bbc" to "https://www.bbc.com",
        "wikipedia" to "https://www.wikipedia.org",
        "youtube" to "https://www.youtube.com",
        "reddit" to "https://www.reddit.com",
        "github" to "https://github.com",
        "x" to "https://x.com",
        "twitter" to "https://x.com",
        "gmail" to "https://mail.google.com",
        "google" to "https://www.google.com",
        "google maps" to "https://maps.google.com",
        "maps" to "https://maps.google.com",
        "google calendar" to "https://calendar.google.com",
        "calendar" to "https://calendar.google.com",
        "khan academy" to "https://www.khanacademy.org",
        "nasa" to "https://www.nasa.gov",
        "stack overflow" to "https://stackoverflow.com",
        "stackoverflow" to "https://stackoverflow.com",
        "openai" to "https://openai.com",
        "rayneo" to "https://www.rayneo.com"
    )

    fun parse(text: String): AssistantIntent? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null

        parseResearch(trimmed)?.let { return it }
        parseOpenWeb(trimmed)?.let { return it }
        return null
    }

    fun extractTapLinkUrl(resultText: String): String? {
        val trimmed = resultText.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("taplink://", ignoreCase = true)) {
            return normalizeUrl(trimmed.removePrefix("taplink://").trim())
        }
        return DOMAIN_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let { normalizeUrl(it) }
    }

    fun displayLabelForUrl(url: String): String = hostLabel(url)

    private fun parseResearch(text: String): AssistantIntent.Research? {
        val topic = RESEARCH_PATTERNS
            .firstNotNullOfOrNull { pattern ->
                pattern.find(text)?.groupValues?.getOrNull(1)
            }
            ?.trim()
            ?.trimEnd('.', '?', '!')
            .orEmpty()

        if (topic.isBlank()) return null
        return AssistantIntent.Research(topic)
    }

    private fun parseOpenWeb(text: String): AssistantIntent.OpenWeb? {
        val rawTarget = OPEN_PATTERNS
            .firstNotNullOfOrNull { pattern ->
                pattern.find(text)?.groupValues?.getOrNull(1)
            }
            ?.trim()
            ?.trimEnd('.', '?', '!')
            .orEmpty()

        if (rawTarget.isBlank()) return null

        val cleanedTarget = rawTarget
            .removePrefix("the ")
            .removePrefix("website ")
            .removePrefix("webpage ")
            .trim()

        if (cleanedTarget.isBlank()) return null
        if (LOCAL_APP_TARGETS.contains(cleanedTarget.lowercase(Locale.US))) return null

        val directMatch = DOMAIN_REGEX.find(cleanedTarget)?.groupValues?.getOrNull(1)
        if (!directMatch.isNullOrBlank()) {
            val url = normalizeUrl(directMatch)
            return AssistantIntent.OpenWeb(url = url, displayLabel = hostLabel(url))
        }

        val normalizedKey = cleanedTarget.lowercase(Locale.US)
        val mapped = KNOWN_SITES[normalizedKey]
        if (!mapped.isNullOrBlank()) {
            return AssistantIntent.OpenWeb(url = mapped, displayLabel = hostLabel(mapped))
        }

        val queryUrl = buildGoogleSearchUrl(cleanedTarget)
        return AssistantIntent.OpenWeb(url = queryUrl, displayLabel = cleanedTarget)
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun buildGoogleSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return "https://www.google.com/search?q=$encoded"
    }

    private fun hostLabel(url: String): String {
        return url
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .removePrefix("www.")
    }
}
