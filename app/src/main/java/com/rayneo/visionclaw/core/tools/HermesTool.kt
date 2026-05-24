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

        return when (val result = hermesClient.sendMessage(query, context, imageBase64)) {
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
