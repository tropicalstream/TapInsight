package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.network.HermesClient
import com.rayneo.visionclaw.core.storage.ReadableArtifactStore

/**
 * HermesTool — Gemini Live tool registered as `hermes_agent`.
 *
 * When the user says something prefixed with "hermes" (case-insensitive),
 * RULE ZERO in GeminiRouter routes the full request to this tool. The
 * tool forwards the text + optional camera frame to the user's Hermes
 * Agent API server via [HermesClient] and returns the streamed response.
 */
class HermesTool(
    context: Context,
    private val hermesClient: HermesClient,
    private val locationProvider: () -> DeviceLocationContext?,
    private val frameProvider: () -> String? = { null }
) : AiTapTool {

    companion object {
        private const val TAG = "HermesTool"

        /** Words that suggest the user wants vision/image analysis */
        private val VISION_KEYWORDS = setOf(
            "see", "look", "image", "photo", "picture", "camera",
            "analyze", "describe", "identify", "recognize", "read",
            "what is this", "what's this", "show", "visual", "scan",
            "ocr", "text in", "sign", "label", "screen", "view"
        )

        private val EXPLICIT_CAMERA_QUERY = Regex(
            """(?i)\b(camera|photo|picture|image|screen|sign|label|what\s+do\s+you\s+see|what\s+am\s+i\s+looking\s+at|through\s+my\s+glasses|visible|in\s+front\s+of\s+me)\b"""
        )
    }

    override val name = "hermes_agent"
    private val appContext = context.applicationContext
    private val artifactStore = ReadableArtifactStore(context)

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val query = args["query"]?.trim().orEmpty()
        if (query.isBlank()) {
            return Result.failure(Exception("No query provided for Hermes."))
        }

        // An explicit on-screen frame (raw WebView screenshot) supplied by the
        // caller wins over the live camera: when the user asked Hermes to look
        // at what's ON SCREEN, send those exact pixels, not the camera.
        val screenFrame = args["image_base64"]?.takeIf { it.isNotBlank() }
        val explicitCameraQuery = EXPLICIT_CAMERA_QUERY.containsMatchIn(query)
        val includeImage = screenFrame != null
            || (args["include_image"]?.toBooleanStrictOrNull() == true && explicitCameraQuery)
            || isVisionQuery(query)
        val imageBase64 = screenFrame ?: if (includeImage) frameProvider() else null
        val hasImage = !imageBase64.isNullOrBlank()

        Log.d(TAG, "Hermes call: vision=$includeImage hasFrame=$hasImage query=${query.take(100)}")
        val context = args["context"]?.takeIf { it.isNotBlank() }

        // Append the media-delivery contract so Hermes knows HOW to actually
        // play / show media on the glasses (not just describe a link). The
        // glasses act on an `open_taplink:<url>` directive line in the reply.
        val augmentedQuery = query + mediaRoutingHint()

        return when (val result = hermesClient.sendMessage(augmentedQuery, context, imageBase64)) {
            is HermesClient.ClawResult.Success -> {
                val response = buildString {
                    append(result.text)
                    if (!result.sessionId.isNullOrBlank() && result.sessionId != "main") {
                        append("\n[Hermes session: ${result.sessionId}]")
                    }
                }
                val readable = extractReadableText(response)
                if (readable.isNotBlank()) {
                    artifactStore.saveLatest(
                        kind = ReadableArtifactStore.ArtifactKind.TAPCLAW_RESULT,
                        title = deriveTitle(readable),
                        text = readable,
                        sourceLabel = "hermes"
                    )
                }
                Result.success(response)
            }
            is HermesClient.ClawResult.NotConfigured ->
                Result.failure(Exception("Hermes is not configured. Set the endpoint URL and API key in TapInsight setup."))
            is HermesClient.ClawResult.Error ->
                Result.failure(Exception(result.message))
        }
    }

    /**
     * Media-delivery contract appended to every Hermes query. Tells Hermes how
     * to make the glasses actually PLAY/SHOW media (audio, video, images) — not
     * just speak a link — and how to SAVE it into the on-glasses Media Library.
     * The glasses parse an `open_taplink:<url>` directive line out of the reply,
     * open it in the native TapInsight player, and strip it from the spoken /
     * displayed text. Guidance only — it has no effect when no media is involved.
     */
    private fun mediaRoutingHint(): String {
        return "\n\n[TapInsight media delivery — how to PLAY or SHOW media on the user's AR glasses " +
            "(not just send a link the user must tap): end your reply with a directive on its OWN " +
            "final line, exactly 'open_taplink:<URL>'. The <URL> MUST be an absolute, publicly " +
            "fetchable http(s) URL pointing at the media in its NATIVE format (e.g. .mp3/.m4a/.aac " +
            "audio, .mp4/.webm video, .jpg/.png image) — NEVER a Hermes server-local path such as " +
            "/home/.../song.mp3 (the glasses cannot read your filesystem). If the file only exists " +
            "locally, first expose it at a public URL (your media relay) and use that. Pick the " +
            "player by type: " +
            "AUDIO → open_taplink:https://appassets.androidplatform.net/assets/media_player.html?type=audio&url=<URL-ENCODED-AUDIO-URL>&title=<NAME>; " +
            "VIDEO → the same with type=video; " +
            "IMAGE → open_taplink:<direct-image-URL> (the viewer displays it); " +
            "a direct video page like YouTube → open_taplink:<watch-url>. " +
            "Keep the SPOKEN sentence short (e.g. 'Playing Woven Strings Atlas.') — the directive " +
            "line is consumed by the glasses and is never read aloud. " +
            "To additionally SAVE media into the on-glasses Media Library (folders Music/, Videos/, " +
            "Text/, Playlists/) rather than only playing it, use the TapInsight companion API " +
            "(POST /api/library/write); only state it was saved after the write or a library " +
            "listing confirms it.]"
    }

    private fun extractReadableText(resultText: String): String {
        return resultText.lineSequence()
            .map { it.trimEnd() }
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.isBlank() ||
                    trimmed.startsWith("open_taplink:", ignoreCase = true) ||
                    trimmed.startsWith("[Hermes session:", ignoreCase = true)
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
            .ifBlank { "Hermes result" }
    }

    private fun isVisionQuery(query: String): Boolean {
        val lower = query.lowercase()
        return VISION_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }
}
