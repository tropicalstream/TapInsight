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
            "remove" -> remove(args)
            "list" -> list()
            "clear" -> {
                HudPinStore.clear()
                Result.success("Cleared all HUD pins.")
            }
            else -> Result.failure(
                IllegalArgumentException(
                    "Unknown hud_pin action '$action'. Use add_icon, add_note, " +
                        "add_picture, remove, list, or clear."
                )
            )
        }
    }

    private fun addIcon(args: Map<String, String>): Result<String> {
        var url = (args["url"] ?: "").trim()
        var label = (args["label"] ?: "").trim()
        if (url.isBlank() || url.equals("current", ignoreCase = true)) {
            // "pin this station / this video / this page" → resolve from
            // REAL playback state, never from Gemini's memory of a URL.
            // Ladder (most-specific first):
            //   1. NowPlayingBridge — the native TapRadio player. Rebuild
            //      the radio.html autoplay URL exactly like TapRadioTool
            //      does, so tapping the pin restarts the actual stream.
            //      Label = station name (what Mars saw instead: the page
            //      title 'Now Playing', which labels nothing).
            //   2. LastUrlStore.currentMedia() — YouTube/Spotify media
            //      entries carry real titles.
            //   3. LastUrlStore.latest() — any page, incl. our own asset
            //      viewers.
            val np = com.TapLink.app.unipanel.NowPlayingBridge
            val npStream = np.streamUrl
            if (np.isPlaying && !npStream.isNullOrBlank()) {
                val station = np.stationName?.trim().orEmpty()
                url = buildRadioReplayUrl(npStream, station)
                if (label.isBlank()) {
                    label = station.ifBlank { np.trackName ?: np.trackTitle ?: "Radio" }
                }
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
                if (label.isBlank()) {
                    label = entry.title?.trim()
                        ?.takeIf { it.isNotBlank() && !it.equals("Now Playing", true) }
                        ?: hostLabel(url)
                }
            }
        }
        val isAsset = url.startsWith("file:///android_asset/")
        if (!url.startsWith("http://") && !url.startsWith("https://") && !isAsset) {
            return Result.failure(
                IllegalArgumentException(
                    "hud_pin add_icon needs an http(s) URL (or url='current'), got: $url"
                )
            )
        }
        if (label.isBlank()) label = hostLabel(url)
        label = label.take(24)
        val added = HudPinStore.add(
            HudPinStore.HudPin(type = HudPinStore.TYPE_ICON, label = label, payload = url)
        )
        return capacityResult(added, "Pinned \"$label\" to the HUD — tapping it opens it.")
    }

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
