package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Base64
import android.util.Log
import com.TapLink.app.unipanel.HudPinStore
import com.rayneo.visionclaw.core.storage.LastUrlStore
import java.io.File
import java.util.Locale

/**
 * hud_pin — Gemini's write path onto the HUD pin board ("pin that to
 * my HUD"). State lives in [HudPinStore] (tapbrowser module, same
 * process); the board UI re-renders through the store's observer, so
 * this tool never touches views.
 *
 * Screen-derived pinning works by composition with the existing
 * vision tools: Gemini reads the screen via browser_vision /
 * browser_page_text, then calls hud_pin with what it found —
 *   • Spotify / tapradio open → add_icon with url="current"
 *     (resolved from LastUrlStore, the ground-truth URL ledger)
 *   • text on screen → add_note with the text browser_vision read
 *   • "pin that picture of a cat" → add_picture source="screen"
 *     (grabs the current vision frame — camera when it's on, else
 *     the browser viewport — and saves it locally)
 */
class HudPinTool(
    private val context: Context,
    /**
     * Base64 JPEG of the current vision frame. Same provider as
     * browser_vision (bestVisionFrameBase64): live camera when
     * streaming, TapBrowser viewport otherwise, null when neither
     * is available.
     */
    private val frameProvider: () -> String?
) : AiTapTool {

    override val name = "hud_pin"

    companion object {
        private const val TAG = "HudPinTool"
        private const val PIN_DIR = "hud_pins"
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        HudPinStore.init(context)
        return when (val action = (args["action"] ?: "").trim().lowercase(Locale.US)) {
            "add_icon" -> addIcon(args)
            "add_note" -> addNote(args)
            "add_picture" -> addPicture(args)
            "add_live" -> addLive(args)
            "remove" -> remove(args)
            "list" -> list()
            "clear" -> {
                HudPinStore.clear()
                Result.success("Cleared all HUD pins.")
            }
            else -> Result.failure(
                IllegalArgumentException(
                    "Unknown hud_pin action '$action'. Use add_icon, add_note, " +
                        "add_picture, add_live, remove, list, or clear."
                )
            )
        }
    }

    /**
     * Live card — a watch query over ANY source: "Warriors score",
     * "top AI headline", "new trending Rust repos on GitHub", "changes
     * to <url>". The LiveCardEngine refreshes it on interval; a
     * sourceUrl scopes the watch to one page, otherwise the engine
     * answers with web-search grounding.
     */
    private fun addLive(args: Map<String, String>): Result<String> {
        val query = (args["query"] ?: args["text"] ?: "").trim()
        if (query.isBlank()) {
            return Result.failure(
                IllegalArgumentException(
                    "hud_pin add_live needs 'query' — what to watch, e.g. " +
                        "'Warriors score' or 'top AI headline'."
                )
            )
        }
        val rawSource = (args["source"] ?: "").trim()

        // GUARDRAIL — a live card must WATCH something that CHANGES.
        // Gemini has been observed routing a static radio-station link
        // into add_live ('add the first station from the tapradio list'),
        // which produced a pointlessly self-refreshing link card. Same
        // philosophy as TapLinkTool's YouTube guardrail: the tool knows
        // better than the caller. Static media links, and open/play-shaped
        // queries with no changing-info words, become ICON pins instead.
        val staticMediaLink = rawSource.isNotBlank() && Regex(
            "radio\\.html|media_player\\.html|youtube\\.com/watch|youtu\\.be/|" +
                "open\\.spotify\\.com|\\.(mp3|m4a|aac|flac|ogg|m3u8?|pls|mp4|mkv|webm)([?#]|$)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(rawSource)
        val opensSomething = Regex(
            "^(open|play|launch|tune|start|link|shortcut|go to|pin)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(query)
        val watchesChanges = Regex(
            "score|news|headline|update|change|track|watch|follow|monitor|" +
                "latest|price|weather|status|new\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(query)
        if (staticMediaLink || (opensSomething && !watchesChanges)) {
            val staticUrl = rawSource.takeIf {
                it.startsWith("http://") || it.startsWith("https://") ||
                    it.startsWith("file:///android_asset/")
            } ?: return Result.failure(
                IllegalArgumentException(
                    "'$query' is a static thing to OPEN, not information that changes — " +
                        "use add_icon (with the real URL, or url='current' if it's open or " +
                        "playing right now) instead of add_live."
                )
            )
            val iconLabel = (args["label"] ?: "").trim()
                .ifBlank { query.removePrefix("open").removePrefix("play").trim() }
                .ifBlank { hostLabel(staticUrl) }
                .take(24)
            val addedIcon = HudPinStore.add(
                HudPinStore.HudPin(
                    type = HudPinStore.TYPE_ICON, label = iconLabel, payload = staticUrl
                )
            )
            return capacityResult(
                addedIcon,
                "That target is a static link, so it's pinned as a regular icon " +
                    "(\"$iconLabel\") instead of a live card — live cards are only for " +
                    "information that changes over time. Tell the user it's pinned."
            )
        }

        val source = rawSource.takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        }
        val label = (args["label"] ?: "").trim().ifBlank {
            query.split(Regex("\\s+")).take(3).joinToString(" ")
        }.take(24)
        val intervalMin = (args["interval_minutes"] ?: "").trim()
            .toIntOrNull()?.coerceIn(1, 180) ?: 5
        com.rayneo.visionclaw.core.live.LiveCardEngine.ensureStarted(context)
        val added = HudPinStore.add(
            HudPinStore.HudPin(
                type = HudPinStore.TYPE_LIVE,
                label = label,
                payload = query,
                sourceUrl = source,
                intervalSec = intervalMin * 60
            )
        )
        if (added) HudPinStore.all()
            .firstOrNull { it.type == HudPinStore.TYPE_LIVE && it.payload == query }
            ?.let { HudPinStore.requestRefresh(it.id) }
        return capacityResult(
            added,
            "Added the live card \"$label\" — it will refresh every $intervalMin minute(s)" +
                (source?.let { " from $it" } ?: " via web search") +
                ". First update is fetching now."
        )
    }

    private fun addIcon(args: Map<String, String>): Result<String> {
        var url = (args["url"] ?: "").trim()
        val givenLabel = (args["label"] ?: "").trim()
        // Real metadata title, resolved alongside the URL below. It BEATS
        // a generic caller label: Gemini routinely passes fillers like
        // 'Current Video' even when told not to, and a pin labeled
        // 'Current Video' labels nothing (Mars's exact complaint).
        var metaTitle: String? = null

        val live = com.TapLink.app.media.BrowserFrameHolder.currentPageInfo()
        if (url.isBlank() || url.equals("current", ignoreCase = true)) {
            // "pin this station / this video / this page" → resolve from
            // REAL state, never from Gemini's memory of a URL. Ladder:
            //   1. NowPlayingBridge — native TapRadio player. Rebuild the
            //      radio.html autoplay URL like TapRadioTool does, so the
            //      pin restarts the actual stream. Title = station name.
            //   2. BrowserFrameHolder.currentPageInfo() — the LIVE WebView
            //      URL + document title. Catches manual in-page navigation
            //      (tapping a YouTube thumbnail) that the LastUrlStore
            //      ledger never sees.
            //   3. LastUrlStore currentMedia()/latest() — tool-opened URLs
            //      with recorded titles, as the fallback.
            val np = com.TapLink.app.unipanel.NowPlayingBridge
            val npStream = np.streamUrl
            if (np.isPlaying && !npStream.isNullOrBlank()) {
                val station = np.stationName?.trim().orEmpty()
                url = buildRadioReplayUrl(npStream, station)
                metaTitle = station.ifBlank { np.trackName ?: np.trackTitle ?: "Radio" }
            } else if (live != null) {
                url = live.url
                metaTitle = cleanMediaTitle(live.title)
            } else {
                val store = LastUrlStore(context)
                val entry = store.currentMedia() ?: store.latest()
                    ?: return Result.failure(
                        IllegalStateException(
                            "Nothing is playing and nothing has been opened in the browser " +
                                "yet — ask the user for a URL or what they want pinned."
                        )
                    )
                url = entry.url
                metaTitle = cleanMediaTitle(entry.title)
            }
        } else if (live != null && url == live.url) {
            // Explicit URL that happens to BE the live page — use its
            // real document title for the same reason.
            metaTitle = cleanMediaTitle(live.title)
        }
        val isAsset = url.startsWith("file:///android_asset/")
        if (!url.startsWith("http://") && !url.startsWith("https://") && !isAsset) {
            return Result.failure(
                IllegalArgumentException(
                    "hud_pin add_icon needs an http(s) URL (or url='current'), got: $url"
                )
            )
        }
        val label = (
            if (isGenericLabel(givenLabel)) metaTitle?.takeIf { it.isNotBlank() }
                ?: givenLabel.ifBlank { hostLabel(url) }
            else givenLabel
        ).take(24)
        val added = HudPinStore.add(
            HudPinStore.HudPin(type = HudPinStore.TYPE_ICON, label = label, payload = url)
        )
        return capacityResult(added, "Pinned \"$label\" to the HUD — tapping it opens it.")
    }

    /** Filler labels an assistant emits when it doesn't know the name.
     *  Any of these lose to real metadata. */
    private fun isGenericLabel(label: String): Boolean {
        val l = label.trim().lowercase(Locale.US)
        return l.isBlank() || l in setOf(
            "current", "current video", "this video", "the video", "video",
            "current page", "this page", "the page", "page",
            "current station", "this station", "station",
            "current media", "this media", "media",
            "now playing", "current song", "this song", "link", "pin"
        )
    }

    /** Strip platform suffixes so 'Cat Video - YouTube' pins as 'Cat Video'. */
    private fun cleanMediaTitle(title: String?): String? =
        title?.trim()
            ?.replace(Regex("\\s*[-–|—]\\s*YouTube( Music)?\\s*$", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s*[-–|—]\\s*Spotify\\s*$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("Now Playing", ignoreCase = true) }

    /**
     * Rebuild the radio.html autoplay URL the way TapRadioTool's
     * buildNativePlayUrl does (playUrl/playName/autoplay), minus the
     * timestamp nonce — the pin must be STABLE so re-pinning the same
     * station dedupes in the store; the player adds its own cache
     * busting on load.
     */
    private fun buildRadioReplayUrl(streamUrl: String, name: String): String {
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val params = mutableListOf("playUrl=${enc(streamUrl)}")
        if (name.isNotBlank()) params += "playName=${enc(name)}"
        params += "autoplay=1"
        return "file:///android_asset/radio.html?${params.joinToString("&")}"
    }

    private fun addNote(args: Map<String, String>): Result<String> {
        val text = (args["text"] ?: "").trim()
        if (text.isBlank()) {
            return Result.failure(
                IllegalArgumentException(
                    "hud_pin add_note needs 'text' — the note body. If the text is on " +
                        "the user's screen, read it with browser_vision first, then call " +
                        "add_note with what you read."
                )
            )
        }
        val label = (args["label"] ?: "").trim().ifBlank {
            text.split(Regex("\\s+")).take(3).joinToString(" ")
        }.take(24)
        val added = HudPinStore.add(
            HudPinStore.HudPin(
                type = HudPinStore.TYPE_NOTE,
                label = label,
                payload = text.take(280),
                linkUrl = "https://tasks.google.com"
            )
        )
        return capacityResult(
            added,
            "Posted the note \"$label\" to the HUD — tapping it opens Google Tasks."
        )
    }

    private fun addPicture(args: Map<String, String>): Result<String> {
        val source = (args["source"] ?: "screen").trim()
        var label = (args["label"] ?: "").trim().ifBlank { "picture" }.take(24)
        val payload: String
        if (source.startsWith("http://") || source.startsWith("https://")) {
            payload = source
        } else {
            // "pin that picture" → save the current vision frame locally so
            // the pin survives the page navigating away.
            val base64 = frameProvider()
                ?: return Result.failure(
                    IllegalStateException(
                        "Couldn't capture the screen — no camera frame and no browser " +
                            "view available right now."
                    )
                )
            val bytes = try {
                Base64.decode(base64, Base64.DEFAULT)
            } catch (e: Exception) {
                return Result.failure(IllegalStateException("Screen capture decode failed."))
            }
            val dir = File(context.filesDir, PIN_DIR).apply { mkdirs() }
            val file = File(dir, "pin_${System.currentTimeMillis()}.jpg")
            try {
                file.writeBytes(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save pin capture: ${e.message}")
                return Result.failure(IllegalStateException("Couldn't save the capture."))
            }
            payload = file.absolutePath
        }
        val added = HudPinStore.add(
            HudPinStore.HudPin(type = HudPinStore.TYPE_PICTURE, label = label, payload = payload)
        )
        if (!added) {
            // don't leak the saved capture when the board is full
            if (!payload.startsWith("http")) runCatching { File(payload).delete() }
        }
        return capacityResult(
            added,
            "Pinned the picture \"$label\" to the HUD — tapping it opens it full screen."
        )
    }

    private fun remove(args: Map<String, String>): Result<String> {
        val query = (args["label"] ?: args["text"] ?: "").trim()
        if (query.isBlank()) {
            return Result.failure(IllegalArgumentException("hud_pin remove needs 'label'."))
        }
        val removedLabel = HudPinStore.removeByLabel(query)
            ?: return Result.success(
                "No HUD pin matches \"$query\". Current pins: ${labelsSummary()}"
            )
        return Result.success("Removed the \"$removedLabel\" pin from the HUD.")
    }

    private fun list(): Result<String> {
        val pins = HudPinStore.all()
        if (pins.isEmpty()) return Result.success("The HUD pin board is empty.")
        val lines = pins.joinToString("; ") { "\"${it.label}\" (${it.type})" }
        return Result.success("HUD pins (${pins.size}/${HudPinStore.MAX_PINS}): $lines")
    }

    private fun labelsSummary(): String {
        val pins = HudPinStore.all()
        return if (pins.isEmpty()) "none" else pins.joinToString(", ") { "\"${it.label}\"" }
    }

    private fun capacityResult(added: Boolean, successText: String): Result<String> =
        if (added) {
            Result.success(successText)
        } else {
            Result.success(
                "The HUD pin board is full (${HudPinStore.MAX_PINS} pins). Ask the user " +
                    "which pin to remove first. Current pins: ${labelsSummary()}"
            )
        }

    private fun hostLabel(url: String): String {
        val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
        return host.removePrefix("www.").substringBefore(".").ifBlank { "link" }
    }
}
