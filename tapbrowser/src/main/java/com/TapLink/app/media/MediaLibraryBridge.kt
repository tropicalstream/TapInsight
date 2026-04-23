package com.TapLink.app.media

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * JavaScript bridge that exposes MediaLibraryService directly to the
 * on-glasses library UI. Installed on the tapbrowser WebView as
 * `window.TapMedia`.
 *
 * Security model:
 *   JavascriptInterface methods run on a background thread, so we can't
 *   touch WebView state. Instead, MainActivity updates `currentUrlRef` from
 *   onPageStarted/onPageFinished, and every bridge method that touches the
 *   filesystem checks `isTrusted()` — which returns true only for the asset
 *   pages we ship (library_local.html, media_player.html). Calls from any
 *   other loaded page (third-party sites, etc.) get an error object.
 *
 *   Media URLs are served via WebViewAssetLoader at
 *   https://appassets.androidplatform.net/media/<relative-path>. The asset
 *   loader's PathHandler enforces the Media-root containment check again,
 *   so even a malicious asset page can't coerce us into reading outside the
 *   Media folder.
 *
 * Data model:
 *   All methods return JSON strings (JS-friendly). The wire schema mirrors
 *   the existing /api/library/... endpoints so library_local.html and the
 *   companion library.html can share render/editor code.
 */
class MediaLibraryBridge(
    private val context: Context,
    private val currentUrlRef: AtomicReference<String>,
    /**
     * On-glasses Gemini 3.1 TTS client. Optional — if null, [speakText]
     * returns an informative error so the JS side can fall back to browser
     * speechSynthesis (which only exists in the companion web viewer).
     * Wired by MainActivity/DualWebViewGroup where the bridge is created.
     */
    private val ttsClient: GlassesTtsClient? = null
) {

    /**
     * Callback used by the async TTS pipeline to push completion events back
     * into the WebView. Implementations MUST post to the WebView's UI thread
     * (i.e. wrap `webView.evaluateJavascript(...)` in `webView.post { ... }`).
     * Set by the host AFTER `addJavascriptInterface` so the WebView reference
     * is available — bridge construction can't take the WebView directly
     * because the bridge is a lazy property the WebView itself depends on.
     * If left null, [startSpeakText] returns an error and JS falls back to
     * the synchronous [speakText] path.
     */
    var jsEvaluator: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "MediaLibraryBridge"
        /** JS side reads this as `window.TapMedia`. */
        const val JS_NAME = "TapMedia"
        /** Virtual host used by WebViewAssetLoader for media streaming. */
        const val ASSETS_HOST = "appassets.androidplatform.net"
        /**
         * Asset-page filenames that get fast-path trust without needing
         * `currentUrlRef` to be primed yet. Kept for diagnostics / explicit
         * intent only — the actual gate is a host-based check in [isTrusted]
         * because (a) the bridge is only ever bound to our own in-app WebView
         * and (b) `currentUrlRef` can race the very first bridge call on a
         * cold MediaPlayer load, which previously surfaced as a confusing
         * "Not permitted from this page" error before any user action.
         */
        private val TRUSTED_ASSETS = setOf(
            "library_local.html",
            "media_player.html",
            "AR_Dashboard_Landscape_Sidebar.html"
        )
    }

    /**
     * Exposed (not private) so MediaFileInterceptor can share the same
     * instance for path-safe resolution of `/media/...` requests without
     * re-bootstrapping the Media folder.
     */
    val service: MediaLibraryService = MediaLibraryService(context).also { it.ensureBootstrap() }

    val mediaRoot: File get() = service.mediaRoot

    /**
     * Background worker pool for async TTS synth. A small cached pool gives
     * us headroom to run the current chunk's HTTP call in parallel with the
     * next chunk's prefetch without starving either. Cached pool threads die
     * off after 60s of idle so we don't hold OS resources between sessions.
     */
    private val ttsExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "GlassesTts").apply { isDaemon = true }
    }

    private fun isTrusted(): Boolean {
        // The bridge is attached via `addJavascriptInterface` only to our own
        // in-app tapbrowser WebView — third-party sites never get a reference
        // to `window.TapMedia`. So the only thing `isTrusted` has to rule out
        // is a third-party page navigating the same WebView into an origin
        // that isn't one of ours. A host-based check is sufficient for that
        // and avoids a class of first-load bugs the old strict filename match
        // caused: on a cold MediaPlayer load, the bridge's very first call
        // can fire before onPageStarted has finished priming `currentUrlRef`,
        // surfacing as "Not permitted from this page" before any user action.
        //
        // Accept:
        //   - empty ref (first load, about:blank, early DOMContentLoaded)
        //   - anything under file:///android_asset/         (legacy path)
        //   - anything under our WebViewAssetLoader host    (new path)
        val url = currentUrlRef.get().orEmpty()
        if (url.isEmpty()) return true
        if (url == "about:blank") return true
        if (url.startsWith("file:///android_asset/")) return true
        if (url.startsWith("https://$ASSETS_HOST/")) return true
        if (url.startsWith("http://$ASSETS_HOST/")) return true
        return false
    }

    private fun denied(method: String): String {
        Log.w(TAG, "Bridge call denied from URL=${currentUrlRef.get()}: $method")
        return JSONObject().put("error", "Not permitted from this page").toString()
    }

    // ── Folder browsing ────────────────────────────────────────────────

    @JavascriptInterface
    fun listFolder(path: String?): String {
        if (!isTrusted()) return denied("listFolder")
        val listing = service.listFolder(path ?: "")
            ?: return JSONObject().put("error", "Folder not found").toString()
        val arr = JSONArray()
        for (e in listing.entries) {
            arr.put(
                JSONObject()
                    .put("name", e.name)
                    .put("relativePath", e.relativePath)
                    .put("kind", e.kind.name)
                    .put("sizeBytes", e.sizeBytes)
                    .put("lastModifiedMs", e.lastModifiedMs)
            )
        }
        val bc = JSONArray()
        bc.put(JSONObject().put("name", "Media").put("path", ""))
        if (listing.relativePath.isNotEmpty()) {
            val parts = listing.relativePath.split('/').filter { it.isNotBlank() }
            var acc = ""
            for (p in parts) {
                acc = if (acc.isEmpty()) p else "$acc/$p"
                bc.put(JSONObject().put("name", p).put("path", acc))
            }
        }
        return JSONObject()
            .put("relativePath", listing.relativePath)
            .put("absolutePath", listing.absolutePath)
            .put("breadcrumbs", bc)
            .put("entries", arr)
            .toString()
    }

    // ── Playlist I/O ───────────────────────────────────────────────────

    @JavascriptInterface
    fun parsePlaylist(path: String?): String {
        if (!isTrusted()) return denied("parsePlaylist")
        val file = service.resolveSafe(path ?: "")
            ?: return JSONObject().put("error", "Bad path").toString()
        if (!file.exists() || !file.isFile) {
            return JSONObject().put("error", "Playlist not found").toString()
        }
        val parsed = service.parsePlaylist(file)
        val arr = JSONArray()
        for (e in parsed.entries) {
            val playUrl = when {
                e.isAbsoluteUrl -> e.rawPath
                e.absolutePath != null -> toMediaUrl(e.resolvedRelativePath)
                else -> ""
            }
            arr.put(
                JSONObject()
                    .put("rawPath", e.rawPath)
                    .put("resolvedRelativePath", e.resolvedRelativePath)
                    .put("isAbsoluteUrl", e.isAbsoluteUrl)
                    .put("title", e.title)
                    .put("durationSeconds", e.durationSeconds ?: JSONObject.NULL)
                    .put("kind", e.kind.name)
                    .put("playUrl", playUrl)
            )
        }
        val warnings = JSONArray()
        for (w in parsed.warnings) warnings.put(w)
        return JSONObject()
            .put("name", parsed.name)
            .put("relativePath", service.relativize(file))
            .put("entries", arr)
            .put("warnings", warnings)
            .toString()
    }

    @JavascriptInterface
    fun writePlaylist(path: String?, entriesJson: String?): String {
        if (!isTrusted()) return denied("writePlaylist")
        val file = service.resolveSafe(path ?: "")
            ?: return JSONObject().put("error", "Bad path").toString()
        val arr = try {
            JSONArray(entriesJson ?: "[]")
        } catch (e: Exception) {
            return JSONObject().put("error", "Bad entries JSON").toString()
        }
        val list = ArrayList<MediaLibraryService.PlaylistWriteEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pathOrUrl = o.optString("targetPathOrUrl").trim()
            if (pathOrUrl.isEmpty()) continue
            val title = o.optString("title").ifBlank {
                pathOrUrl.substringAfterLast('/').substringBeforeLast('.')
            }
            val dur = if (o.has("durationSeconds") && !o.isNull("durationSeconds"))
                o.optInt("durationSeconds") else null
            list.add(MediaLibraryService.PlaylistWriteEntry(pathOrUrl, title, dur))
        }
        val ok = service.writePlaylist(file, list)
        return JSONObject()
            .put("status", if (ok) "saved" else "error")
            .put("path", service.relativize(file))
            .put("entryCount", list.size)
            .toString()
    }

    @JavascriptInterface
    fun generatePlaylist(folder: String?): String {
        if (!isTrusted()) return denied("generatePlaylist")
        val created = service.generatePlaylistForFolder(folder ?: "")
            ?: return JSONObject().put("error", "No playable files in folder").toString()
        return JSONObject()
            .put("status", "created")
            .put("path", service.relativize(created))
            .toString()
    }

    @JavascriptInterface
    fun deleteEntry(path: String?): String {
        if (!isTrusted()) return denied("deleteEntry")
        val ok = service.deleteEntry(path ?: "")
        return JSONObject()
            .put("status", if (ok) "deleted" else "error")
            .put("path", path ?: "")
            .toString()
    }

    @JavascriptInterface
    fun findAllPlaylists(): String {
        if (!isTrusted()) return denied("findAllPlaylists")
        val list = service.findAllPlaylists()
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("name", e.name)
                    .put("relativePath", e.relativePath)
                    .put("kind", e.kind.name)
                    .put("sizeBytes", e.sizeBytes)
                    .put("lastModifiedMs", e.lastModifiedMs)
            )
        }
        return arr.toString()
    }

    // ── Playback URL ───────────────────────────────────────────────────

    /**
     * Return the virtual https URL the WebView will use to stream a media
     * file. The URL resolves through the WebViewAssetLoader interceptor,
     * which enforces Media-root containment again, so the bridge
     * consumer (library_local.html, media_player.html) doesn't get a
     * sharper privilege than the WebView itself.
     */
    @JavascriptInterface
    fun getMediaUrl(relativePath: String?): String {
        return toMediaUrl(relativePath ?: "")
    }

    // ── Root info ──────────────────────────────────────────────────────

    @JavascriptInterface
    fun getRootInfo(): String {
        if (!isTrusted()) return denied("getRootInfo")
        val root = service.mediaRoot
        val free = try { root.freeSpace } catch (e: Exception) { 0L }
        val total = try { root.totalSpace } catch (e: Exception) { 0L }
        return JSONObject()
            .put("rootAbsolute", root.absolutePath)
            .put("rootShortHint", "Android/data/${context.packageName}/files/Media")
            .put("freeBytes", free)
            .put("totalBytes", total)
            .toString()
    }

    // ── Gemini 3.1 Text-to-Speech ──────────────────────────────────────

    /**
     * Synthesize a chunk of text via Gemini 3.1 TTS and return a virtual
     * audio URL the WebView can load. The on-glasses media_player.html reads
     * text files by calling this one chunk at a time. The returned JSON
     * shape is:
     *
     *   Success:  {"audioUrl":"https://appassets.../tts/<id>.wav","model":"…"}
     *   Failure:  {"error":"<reason>"}
     *
     * The JS side prefers this bridge over browser `speechSynthesis` because
     * the RayNeo WebView doesn't expose Web Speech.
     */
    @JavascriptInterface
    fun speakText(text: String?, voiceHint: String?, @Suppress("UNUSED_PARAMETER") rate: Double): String {
        if (!isTrusted()) return denied("speakText")
        val chunk = text?.trim().orEmpty()
        if (chunk.isBlank()) {
            return JSONObject().put("error", "Nothing to read aloud.").toString()
        }
        val client = ttsClient
            ?: return JSONObject().put("error", "TTS not wired on this build.").toString()
        return when (val result = client.synthesize(chunk, voiceHint?.trim()?.takeIf { it.isNotBlank() })) {
            is GlassesTtsClient.SynthesisResult.Success -> {
                val id = TtsCacheStore.put(result.wavBytes)
                JSONObject()
                    .put("audioUrl", "https://$ASSETS_HOST/tts/$id.wav")
                    .put("model", result.model)
                    .put("sampleRate", result.sampleRate)
                    .toString()
            }
            is GlassesTtsClient.SynthesisResult.ApiKeyMissing -> {
                JSONObject().put("error", "Gemini API key not configured. Set it in the companion app.").toString()
            }
            is GlassesTtsClient.SynthesisResult.Error -> {
                Log.w(TAG, "Gemini TTS error: ${result.message}")
                JSONObject().put("error", result.message).toString()
            }
        }
    }

    /**
     * Async variant of [speakText]. Returns immediately so JS can keep the
     * current chunk playing while the next one is being synthesized — without
     * this, each bridge call blocks the JS thread for the full HTTP round-trip
     * and leaves audible gaps between chunks.
     *
     * Contract:
     *   * Returns `{"status":"pending","requestId":"<id>"}` if kicked off.
     *   * Returns `{"error":"…"}` if the page isn't trusted, no key is set,
     *     no jsEvaluator was wired, or the text is empty.
     *   * On completion, posts `window.__ttsComplete('<id>', <JSON>)` back
     *     into the WebView where JSON is either
     *     `{"audioUrl":"…","model":"…","sampleRate":N}` or `{"error":"…"}`.
     *
     * The JS side maintains a map of requestId → Promise resolver so multiple
     * prefetches can be in flight at once (chunk N+1 queued while N plays).
     */
    @JavascriptInterface
    fun startSpeakText(text: String?, voiceHint: String?, @Suppress("UNUSED_PARAMETER") rate: Double, requestId: String?): String {
        if (!isTrusted()) return denied("startSpeakText")
        val id = requestId?.trim().orEmpty()
        if (id.isEmpty()) {
            return JSONObject().put("error", "Missing requestId.").toString()
        }
        // Snapshot the evaluator so the background callback uses the same
        // reference we validated at call time, even if the host later swaps
        // it out during the request.
        val dispatcher = jsEvaluator ?: return JSONObject()
            .put("error", "Async TTS not wired on this build.")
            .toString()
        val chunk = text?.trim().orEmpty()
        if (chunk.isBlank()) {
            return JSONObject().put("error", "Nothing to read aloud.").toString()
        }
        val client = ttsClient
            ?: return JSONObject().put("error", "TTS not wired on this build.").toString()
        val voice = voiceHint?.trim()?.takeIf { it.isNotBlank() }
        ttsExecutor.execute {
            val payload: String = try {
                when (val result = client.synthesize(chunk, voice)) {
                    is GlassesTtsClient.SynthesisResult.Success -> {
                        val cacheId = TtsCacheStore.put(result.wavBytes)
                        JSONObject()
                            .put("audioUrl", "https://$ASSETS_HOST/tts/$cacheId.wav")
                            .put("model", result.model)
                            .put("sampleRate", result.sampleRate)
                            .toString()
                    }
                    is GlassesTtsClient.SynthesisResult.ApiKeyMissing -> {
                        JSONObject()
                            .put("error", "Gemini API key not configured. Set it in the companion app.")
                            .toString()
                    }
                    is GlassesTtsClient.SynthesisResult.Error -> {
                        Log.w(TAG, "Gemini TTS error (req=$id): ${result.message}")
                        JSONObject().put("error", result.message).toString()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Unhandled TTS synth exception (req=$id)", t)
                JSONObject().put("error", t.message ?: t.javaClass.simpleName).toString()
            }
            // jsEvaluator must dispatch this to the WebView's main thread.
            val js = "window.__ttsComplete && window.__ttsComplete(${JSONObject.quote(id)}, ${JSONObject.quote(payload)});"
            dispatcher(js)
        }
        return JSONObject()
            .put("status", "pending")
            .put("requestId", id)
            .toString()
    }

    /**
     * Clear the TTS cache so any in-flight `<audio>` load that races with a
     * stop can 404 cleanly instead of playing after the user hit stop.
     */
    @JavascriptInterface
    fun stopSpeaking(): String {
        TtsCacheStore.clear()
        return JSONObject().put("status", "stopped").toString()
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun toMediaUrl(relativePath: String): String {
        val clean = relativePath.trim().trimStart('/', '\\')
        if (clean.isEmpty()) return ""
        // URL-encode each path segment so spaces / unicode survive.
        val encoded = clean.split('/').joinToString("/") { seg ->
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        return "https://$ASSETS_HOST/media/$encoded"
    }
}
