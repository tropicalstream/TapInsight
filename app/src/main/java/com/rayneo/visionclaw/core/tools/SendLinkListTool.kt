package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Renders a tappable list of arbitrary URLs (articles, audio, videos, PDFs,
 * generic web pages — anything that isn't a YouTube playback request) on the
 * user's AR glasses inside TapBrowser.
 *
 * Built as the visible-card path for "find me links / URLs / articles"
 * style queries that previously suffered from one of two bad outcomes:
 *   • Gemini cached titles via send_video_list display='cache' and never
 *     wrote URLs into the chat card → user heard "I have N links" but
 *     saw nothing.
 *   • Gemini emitted URLs in its spoken response → URLs got read aloud
 *     character by character ("h-t-t-p-s-c-o-l-o-n-slash-slash…").
 *
 * With this tool, Gemini speaks only the titles + a short offer
 * ("want me to send these as a list?"), the user confirms, and the list
 * page opens in TapBrowser. URLs never travel through TTS, but they are
 * fully visible and tappable.
 *
 * Mirror of [SendVideoListTool]'s shape: tool returns a `taplink://` URL
 * pointing at a bundled HTML asset (`link_list.html`) with the list
 * payload encoded into the `?data=` query parameter. MainActivity's
 * autoOpenUrl interceptor recognises the prefix and forwards directly to
 * TapBrowser without round-tripping the URL back through Gemini (which
 * tends to mangle long encoded query strings).
 */
class SendLinkListTool(private val context: Context) : AiTapTool {
    override val name = "send_link_list"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val topicArg = args["topic"].orEmpty().trim()
        val titleArg = args["title"].orEmpty().trim().ifBlank { topicArg }
        val rawLinks = args["links"].orEmpty().trim()

        if (rawLinks.isBlank()) {
            return Result.failure(
                IllegalArgumentException("send_link_list requires a 'links' JSON array.")
            )
        }

        // Gemini occasionally wraps its JSON in code fences. Strip them so a
        // formatting hiccup doesn't reject an otherwise valid call.
        val cleaned = rawLinks
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        val linksArray = try {
            JSONArray(cleaned)
        } catch (e: Exception) {
            Log.w("SendLinkListTool", "links arg was not a JSON array: ${e.message}")
            return Result.failure(
                IllegalArgumentException(
                    "send_link_list 'links' must be a JSON array of {title, url, type, summary} objects."
                )
            )
        }

        if (linksArray.length() == 0) {
            return Result.failure(
                IllegalArgumentException("send_link_list needs at least one entry in 'links'.")
            )
        }

        // Normalise each entry. Drop ones that lack a url or title — the
        // HTML page can't render a row with neither. `type` is optional;
        // when missing we emit "web" so the page still routes sanely.
        val normalized = JSONArray()
        val rejectedRedirects = mutableListOf<String>()
        for (i in 0 until linksArray.length()) {
            val obj = linksArray.optJSONObject(i) ?: continue
            val title = obj.optString("title").trim()
            // Mutable so the YouTube watch-URL rewrite below can update it.
            var url = obj.optString("url").trim()
            if (title.isBlank() || url.isBlank()) continue

            // Reject Google's vertexaisearch grounding-redirect URLs.
            // These are opaque server-side redirects that the model
            // pulls from googleSearch grounding chunks; they resolve
            // unpredictably (a Wikipedia-titled redirect often lands
            // on YouTube), so the user's tap doesn't match the entry's
            // title or type. Drop them and let Gemini retry with
            // direct URLs.
            val urlLower = url.lowercase(Locale.US)
            if (urlLower.contains("vertexaisearch.cloud.google.com") ||
                urlLower.contains("/grounding-api-redirect/")
            ) {
                Log.w(
                    "SendLinkListTool",
                    "Rejecting grounding-redirect URL for entry '${title.take(60)}': $url"
                )
                rejectedRedirects.add(title)
                continue
            }

            // Reject hallucinated YouTube watch URLs. Real YouTube IDs
            // are exactly 11 characters of [A-Za-z0-9_-]. The model
            // routinely invents IDs that look right but 404. We can
            // catch most invented URLs by validating the ID shape, but
            // we can't catch IDs that happen to be syntactically valid
            // and point to an unrelated video — that needs a HEAD-check
            // pass we don't do yet. For now, rewrite hallucination-
            // prone watch URLs to a search URL using the entry's title,
            // which always resolves to SOMETHING relevant.
            val watchMatch = Regex(
                """^https?://(?:www\.)?youtube\.com/watch\?(?:[^&]*&)*v=([^&\s]+)"""
            ).find(url)
            if (watchMatch != null) {
                val videoId = watchMatch.groupValues[1]
                val validId = videoId.matches(Regex("^[A-Za-z0-9_-]{11}$"))
                if (!validId) {
                    Log.w(
                        "SendLinkListTool",
                        "YouTube watch URL has invalid ID '$videoId' for entry '${title.take(60)}'; " +
                            "rewriting to search URL."
                    )
                    val q = java.net.URLEncoder.encode(title, "UTF-8")
                        .replace("%20", "+")
                    url = "https://www.youtube.com/results?search_query=$q"
                }
            }

            // Normalise type to lowercase so the HTML page can string-match
            // without per-call defensive lowercasing. Unknown types fall
            // through to "web" rather than rejecting the entry.
            val type = obj.optString("type").trim().lowercase(Locale.US).ifBlank { "web" }
            val summary = obj.optString("summary").trim()
            normalized.put(
                JSONObject()
                    .put("title", title)
                    .put("url", url)
                    .put("type", type)
                    .put("summary", summary)
            )
        }

        // If any entries were rejected, surface a useful error so Gemini
        // can retry with real URLs. Without this, partial lists slip
        // through silently and the user only notices when they tap an
        // entry and it opens the wrong page.
        if (rejectedRedirects.isNotEmpty() && normalized.length() == 0) {
            return Result.failure(
                IllegalArgumentException(
                    "send_link_list rejected: every URL was a vertexaisearch grounding redirect " +
                        "(${rejectedRedirects.size} entries). These are opaque Google redirects that resolve " +
                        "unpredictably. Use the original source URLs from the grounding metadata's 'web.uri' " +
                        "field — direct URLs like https://en.wikipedia.org/wiki/X, https://www.youtube.com/" +
                        "watch?v=Y, https://archive.org/details/Z. Do NOT use any URL whose host is " +
                        "vertexaisearch.cloud.google.com."
                )
            )
        }
        if (rejectedRedirects.isNotEmpty()) {
            Log.w(
                "SendLinkListTool",
                "Dropped ${rejectedRedirects.size} grounding-redirect entries; kept ${normalized.length()}"
            )
        }

        if (normalized.length() == 0) {
            return Result.failure(
                IllegalArgumentException(
                    "send_link_list couldn't read any valid {title, url} pairs."
                )
            )
        }

        val payload = JSONObject()
            .put("title", titleArg.ifBlank { "Links" })
            .put("topic", topicArg)
            .put("links", normalized)
            .toString()

        val encoded = URLEncoder.encode(payload, "UTF-8")
        val url = "file:///android_asset/link_list.html?data=$encoded"
        // Log each entry on its own line so logcat's per-line truncation
        // doesn't hide what Gemini actually emitted. The data param itself
        // is too long to log in one line; this gives us a full audit trail
        // of {type, title, url} for every entry rendered.
        Log.d(
            "SendLinkListTool",
            "Rendering link list topic='$topicArg' count=${normalized.length()}"
        )
        for (i in 0 until normalized.length()) {
            val entry = normalized.optJSONObject(i) ?: continue
            Log.d(
                "SendLinkListTool",
                "  entry[$i] type=${entry.optString("type")} " +
                    "title='${entry.optString("title").take(80)}' " +
                    "url='${entry.optString("url")}'"
            )
        }
        return Result.success("taplink://$url")
    }
}
