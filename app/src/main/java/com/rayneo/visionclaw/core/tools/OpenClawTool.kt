package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.assistant.AssistantIntentParser
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.network.OpenClawClient
import com.rayneo.visionclaw.core.storage.LastUrlStore
import com.rayneo.visionclaw.core.storage.ReadableArtifactStore

/**
 * OpenClawTool — bridges Gemini Live tool calls to a user's personal
 * OpenClaw AI assistant running on their own server/device.
 *
 * Triggered via the "tapclaw" prefix in voice commands:
 *   "tapclaw check my emails"
 *   "tapclaw turn off the lights"
 *   "tapclaw what do you see" (sends camera frame as attachment)
 *
 * OpenClaw handles smart home, email, calendars, web automation,
 * health data, productivity apps, custom agent skills, and
 * image/vision analysis (when a camera frame is attached).
 */
class OpenClawTool(
    context: Context,
    private val openClawClient: OpenClawClient,
    private val locationProvider: () -> DeviceLocationContext?,
    private val frameProvider: () -> String? = { null }
) : AiTapTool {

    companion object {
        private const val TAG = "OpenClawTool"

        /** Keywords that suggest the user wants vision/image analysis */
        private val VISION_KEYWORDS = setOf(
            "see", "look", "image", "photo", "picture", "camera",
            "analyze", "describe", "identify", "recognize", "read",
            "what is this", "what's this", "show", "visual", "scan",
            "ocr", "text in", "sign", "label", "screen", "view"
        )
    }

    override val name = "tapclaw_agent"
    private val artifactStore = ReadableArtifactStore(context)
    private val lastUrlStore = LastUrlStore(context)

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val rawQuery = args["query"]?.trim().orEmpty()
        if (rawQuery.isBlank()) {
            return Result.failure(Exception("No query provided for OpenClaw."))
        }

        // ── URL-grounding pass ─────────────────────────────────────────
        // Before we ship the query off to TapClaw, rewrite it so that any
        // URL Gemini tried to embed is either (a) a real URL the user
        // actually opened recently or (b) a safe, descriptive placeholder.
        // Without this step, Gemini routinely hallucinates YouTube URLs
        // when the user says "email me this video".
        val query = groundUrlsInQuery(rawQuery)
        if (query != rawQuery) {
            Log.i(TAG, "Grounded outgoing query — rewrote hallucinated/placeholder URLs.\n  before=${rawQuery.take(240)}\n  after=${query.take(240)}")
        }

        // Check if query implies vision — attach camera frame if available
        val includeImage = args["include_image"]?.toBooleanStrictOrNull() == true
            || isVisionQuery(query)
        val imageBase64 = if (includeImage) frameProvider() else null
        val hasImage = !imageBase64.isNullOrBlank()

        val frameSizeKb = imageBase64?.length?.div(1024) ?: 0
        Log.d(TAG, "Sending to OpenClaw: query=${query.take(100)} vision=$includeImage hasFrame=$hasImage frameSizeKb=$frameSizeKb")
        if (hasImage) {
            Log.d(TAG, "Frame preview (first 80 chars): ${imageBase64!!.take(80)}")
        }

        // Skip location context entirely — the blocking GPS resolver adds ~13s
        // of delay before the WebSocket call even starts. OpenClaw has its own
        // context and doesn't need glasses GPS for most queries.
        // Only include Gemini-provided context if present.
        val context = args["context"]?.takeIf { it.isNotBlank() }

        return when (val result = openClawClient.sendMessage(query, context, imageBase64)) {
            is OpenClawClient.ClawResult.Success -> {
                val response = buildString {
                    append(result.text)
                    if (!result.sessionId.isNullOrBlank() && result.sessionId != "main") {
                        append("\n[OpenClaw session: ${result.sessionId}]")
                    }
                }
                // Defense-in-depth: rewrite hallucinated media domains at source.
                // GPT-5.4 persistently invents wrong domains (api.tapclaw.com, etc.)
                // — the only correct media relay is relay.tapinsight.uk.
                val sanitized = rewriteAllUrlsInText(response)
                val readable = extractReadableText(sanitized)
                if (readable.isNotBlank()) {
                    artifactStore.saveLatest(
                        kind = ReadableArtifactStore.ArtifactKind.TAPCLAW_RESULT,
                        title = deriveTitle(readable),
                        text = readable,
                        sourceLabel = "tapclaw"
                    )
                }
                Result.success(sanitized)
            }
            is OpenClawClient.ClawResult.NotConfigured ->
                Result.failure(Exception(
                    "TapClaw is not configured. Set the gateway URL and token in TapInsight setup."
                ))
            is OpenClawClient.ClawResult.Error ->
                Result.failure(Exception(result.message))
        }
    }

    /** Rewrite hallucinated media URLs in text from OpenClaw responses. */
    private fun rewriteAllUrlsInText(text: String): String {
        val urlPattern = Regex("""https?://[^\s"'<>\]]+""")
        var result = text
        for (match in urlPattern.findAll(text)) {
            val original = match.value
            val rewritten = AssistantIntentParser.rewriteHallucinatedMediaDomain(original)
            if (rewritten != original) {
                result = result.replace(original, rewritten)
                Log.w(TAG, "Rewrote hallucinated URL at source: $original → $rewritten")
            }
        }
        return result
    }

    /**
     * Ground URLs in an outgoing TapClaw query so we never ask TapClaw to
     * email/share a URL Gemini hallucinated.
     *
     * Two transforms run in order:
     *
     *   1. Placeholder substitution — any of `{last_video_url}`,
     *      `{last_media_url}`, `{last_url}`, `{current_video}`,
     *      `{current_url}`, `{this_video}`, `{now_playing}` gets replaced
     *      with the most relevant URL we've actually opened recently.
     *      These placeholders are documented in the tapclaw_agent tool
     *      schema so Gemini is encouraged to use them instead of
     *      reproducing a URL from memory.
     *
     *   2. Hallucination check — any YouTube watch URL already embedded in
     *      the query is validated against [LastUrlStore]. If its 11-char
     *      video ID hasn't been seen in this session, it's replaced with
     *      the canonical last-viewed URL (or, absent that, stripped out
     *      and described as "[last viewed video]" so TapClaw's own AI can
     *      handle it gracefully).
     *
     * Relay-domain rewrites (api.tapclaw.com → relay.tapinsight.uk) are
     * still performed via [rewriteAllUrlsInText] once the response comes
     * back — that's a different failure mode.
     */
    private fun groundUrlsInQuery(raw: String): String {
        var s = raw

        // ── Pass 1: placeholder substitution ──
        val currentMedia = lastUrlStore.currentMedia()
        val currentAny = lastUrlStore.latest()
        val videoEntry = lastUrlStore.latestOfKinds(
            LastUrlStore.UrlKind.YOUTUBE_VIDEO,
            LastUrlStore.UrlKind.YOUTUBE_SEARCH
        )

        data class Placeholder(val pattern: Regex, val resolver: () -> String?)
        val placeholders = listOf(
            Placeholder(Regex("""\{\s*(?:last|current|this)[_\s]*video(?:[_\s]*url)?\s*\}""", RegexOption.IGNORE_CASE)) {
                videoEntry?.url ?: currentMedia?.url
            },
            Placeholder(Regex("""\{\s*(?:last|current)[_\s]*media(?:[_\s]*url)?\s*\}""", RegexOption.IGNORE_CASE)) {
                currentMedia?.url
            },
            Placeholder(Regex("""\{\s*now[_\s]*playing\s*\}""", RegexOption.IGNORE_CASE)) {
                currentMedia?.url
            },
            Placeholder(Regex("""\{\s*(?:last|current)[_\s]*url\s*\}""", RegexOption.IGNORE_CASE)) {
                currentAny?.url
            }
        )
        for (p in placeholders) {
            if (!p.pattern.containsMatchIn(s)) continue
            val replacement = p.resolver()
            s = if (replacement.isNullOrBlank()) {
                p.pattern.replace(s, "[no recent URL recorded]")
            } else {
                p.pattern.replace(s, Regex.escapeReplacement(replacement))
            }
        }

        // ── Pass 2: hallucinated YouTube URL check ──
        // Scan for anything that looks like a YouTube watch URL and validate
        // its 11-char video ID against recently-recorded entries. If we've
        // never opened that ID, treat it as a hallucination.
        val ytWatchPattern = Regex(
            """https?://(?:www\.|m\.)?(?:youtube\.com/watch\?[^\s"'<>)]*v=[A-Za-z0-9_\-]{11}[^\s"'<>)]*|youtu\.be/[A-Za-z0-9_\-]{11}(?:[?&][^\s"'<>)]*)?)"""
        )
        val canonicalYouTubeForSubstitution = videoEntry?.url?.takeIf {
            LastUrlStore.isCanonicalYouTubeVideoUrl(it)
        }
        s = ytWatchPattern.replace(s) { match ->
            val candidate = match.value
            val videoId = LastUrlStore.extractYouTubeVideoId(candidate)
            when {
                videoId == null -> candidate
                lastUrlStore.hasSeenYouTubeVideoId(videoId) -> {
                    // Real URL — keep it, but normalize to the one we actually
                    // saw so analytics / deduping inside TapClaw aren't
                    // confused by surface variations (watch?v=ID&t=… etc.).
                    lastUrlStore.findByYouTubeVideoId(videoId)?.url ?: candidate
                }
                canonicalYouTubeForSubstitution != null -> {
                    Log.w(
                        TAG,
                        "Replacing likely-hallucinated YouTube URL $candidate " +
                            "(video id=$videoId never opened in TapBrowser) → " +
                            "last-seen $canonicalYouTubeForSubstitution"
                    )
                    canonicalYouTubeForSubstitution
                }
                else -> {
                    Log.w(
                        TAG,
                        "Stripping hallucinated YouTube URL $candidate " +
                            "(no canonical replacement available)"
                    )
                    "[last viewed video — video URL unavailable on glasses]"
                }
            }
        }

        return s
    }

    private fun extractReadableText(resultText: String): String {
        return resultText.lineSequence()
            .map { it.trimEnd() }
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.isBlank() ||
                    trimmed.startsWith("open_taplink:", ignoreCase = true) ||
                    trimmed.startsWith("[OpenClaw session:", ignoreCase = true)
            }
            .joinToString("\n")
            .trim()
    }

    private fun deriveTitle(text: String): String {
        return text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.take(72)
            ?.trimEnd()
            .orEmpty()
            .ifBlank { "TapClaw result" }
    }

    /**
     * Heuristic check: does the query imply the user wants OpenClaw to
     * look at something through the camera?
     */
    private fun isVisionQuery(query: String): Boolean {
        val lower = query.lowercase()
        return VISION_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }
}
