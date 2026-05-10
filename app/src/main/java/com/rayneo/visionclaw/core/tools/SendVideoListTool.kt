package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Renders a scrollable picker list of video suggestions on the user's AR
 * glasses. Each row opens that specific title on YouTube; a "Play all as a
 * playlist" button opens YouTube's playlists-tab search for the topic.
 *
 * Flow: Gemini describes a few options verbally (Phase A in DEFAULT_URL_RULES),
 * then calls this tool so the user has something tappable to choose from.
 */
class SendVideoListTool(private val context: Context) : AiTapTool {
    override val name = "send_video_list"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val topicArg = args["topic"].orEmpty().trim()
        val titleArg = args["title"].orEmpty().trim().ifBlank { topicArg }
        val rawVideos = args["videos"].orEmpty().trim()
        val display = args["display"].orEmpty().trim().lowercase(Locale.US)

        if (rawVideos.isBlank()) {
            return Result.failure(
                IllegalArgumentException("send_video_list requires a 'videos' JSON array")
            )
        }

        // Gemini sometimes wraps its JSON in backticks or code fences. Strip
        // them before parsing so we don't reject a good call for formatting.
        val cleaned = rawVideos
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        val videosArray = try {
            JSONArray(cleaned)
        } catch (e: Exception) {
            Log.w("SendVideoListTool", "videos arg was not a JSON array: ${e.message}")
            return Result.failure(
                IllegalArgumentException(
                    "send_video_list 'videos' must be a JSON array of {title, creator, reason} objects."
                )
            )
        }

        if (videosArray.length() == 0) {
            return Result.failure(
                IllegalArgumentException("send_video_list needs at least one video in 'videos'.")
            )
        }

        // Normalize each entry so the HTML page can trust the shape.
        val normalized = JSONArray()
        for (i in 0 until videosArray.length()) {
            val entry = videosArray.opt(i)
            val obj = when (entry) {
                is JSONObject -> entry
                is String -> JSONObject().put("title", entry)
                else -> continue
            }
            val title = obj.optString("title").trim()
            if (title.isBlank()) continue
            normalized.put(
                JSONObject()
                    .put("title", title)
                    .put("creator", obj.optString("creator").trim())
                    .put("reason", obj.optString("reason").trim())
            )
        }

        if (normalized.length() == 0) {
            return Result.failure(
                IllegalArgumentException("send_video_list couldn't read any valid video titles.")
            )
        }

        val payload = JSONObject()
            .put("title", titleArg.ifBlank { "Video picks" })
            .put("topic", topicArg)
            .put("videos", normalized)
            .toString()
        cacheRecentSuggestions(payload)

        if (display in setOf("cache", "voice", "none", "no_open", "hidden")) {
            val listText = buildString {
                append("Cached ${normalized.length()} YouTube suggestions for '$topicArg'. ")
                append("Read these exact titles aloud, then offer to send the picker or play one/all of them:\n")
                for (i in 0 until normalized.length()) {
                    val item = normalized.optJSONObject(i) ?: continue
                    append(i + 1)
                    append(". ")
                    append(item.optString("title"))
                    val creator = item.optString("creator").trim()
                    if (creator.isNotBlank()) {
                        append(" by ")
                        append(creator)
                    }
                    val reason = item.optString("reason").trim()
                    if (reason.isNotBlank()) {
                        append(" — ")
                        append(reason)
                    }
                    append('\n')
                }
                append("\nRULE 19 reminder (media search augmentation): Before speaking, run your built-in ")
                append("googleSearch tool internally for current context on '$topicArg' and fold the best matches ")
                append("into a unified spoken response (skip Google augmentation only if the query is an exact ")
                append("YouTube channel name or unambiguous title). DO NOT call open_taplink to a Google search ")
                append("URL — that is a separate user-visible action under RULE ZERO-E. Always close with ONE ")
                append("escalation offer: 'I can also have TapClaw dig deeper if you want a wider net.' Wait for ")
                append("explicit confirmation before calling tapclaw_agent or open_taplink. ")
            }
            Log.d("SendVideoListTool", "Cached voice list topic='$topicArg' count=${normalized.length()}")
            return Result.success(listText.trim())
        }

        val encoded = URLEncoder.encode(payload, "UTF-8")
        val url = "file:///android_asset/video_list.html?data=$encoded"
        Log.d(
            "SendVideoListTool",
            "Rendering list topic='$topicArg' count=${normalized.length()} url=${url.take(120)}"
        )
        // Matching TapLinkTool's return shape so the MainActivity autoOpenUrl
        // interceptor can forward this straight to TapBrowser without a
        // round-trip through Gemini.
        return Result.success("taplink://$url")
    }

    private fun cacheRecentSuggestions(payload: String) {
        context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("recent_youtube_suggestions_json", payload)
            .putLong("recent_youtube_suggestions_ms", System.currentTimeMillis())
            .apply()
    }
}
