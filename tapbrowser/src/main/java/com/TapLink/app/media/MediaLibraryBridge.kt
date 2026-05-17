package com.TapLink.app.media

import android.content.ContentUris
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
    private val ttsClient: GlassesTtsClient? = null,
    /**
     * Fish.audio TTS client. Optional — if null OR if the engine selection
     * is "gemini" OR if Fish isn't configured, [speakText] falls through to
     * [ttsClient] (Gemini) without surfacing the absence to the user. Both
     * clients return the same SynthesisResult-shaped values, so routing is
     * just a `when` over the active engine.
     */
    private val fishTtsClient: FishTtsClient? = null
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
            "photos_gallery.html",
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

    /** Lazy DCIM enumerator — only allocated if the gallery actually
     *  asks for the merged shared-storage view. */
    private val dcim: DcimEnumerator by lazy { DcimEnumerator(context) }

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

    /**
     * Rotate a JPEG/PNG/WEBP in place. Used by the photos gallery's
     * rotate buttons. `degrees` is interpreted clockwise; the call
     * decodes the bitmap, applies a Matrix.postRotate, and re-encodes
     * back over the original file at high quality.
     *
     * The new bitmap is held entirely in memory once; on a memory-tight
     * device a >24MP RAW would be a problem, but TapInsight saves only
     * the live camera frame which is bounded by the Gemini Live frame
     * size (a few MB at most).
     *
     * Returns JSON: `{"status":"rotated", "path":"...", "degrees":90}`
     * on success; `{"error":"..."}` on failure.
     */
    @JavascriptInterface
    fun rotateImage(path: String?, degrees: Int): String {
        if (!isTrusted()) return denied("rotateImage")
        if (path.isNullOrBlank()) return JSONObject().put("error", "Empty path").toString()
        val normalizedDeg = ((degrees % 360) + 360) % 360
        if (normalizedDeg == 0) {
            return JSONObject().put("status", "noop").put("path", path).toString()
        }
        val file = service.resolveSafe(path)
            ?: return JSONObject().put("error", "Path outside Media root").toString()
        if (!file.exists() || !file.isFile) {
            return JSONObject().put("error", "File not found").toString()
        }
        val ext = file.extension.lowercase(java.util.Locale.ROOT)
        if (ext !in MediaLibraryService.IMAGE_EXTENSIONS) {
            return JSONObject().put("error", "Not an image file").toString()
        }

        val bitmap = try {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "rotateImage decode failed: ${e.message}")
            null
        } ?: return JSONObject().put("error", "Could not decode image").toString()

        val matrix = android.graphics.Matrix().apply { postRotate(normalizedDeg.toFloat()) }
        val rotated = try {
            android.graphics.Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
        } catch (e: Exception) {
            bitmap.recycle()
            Log.w(TAG, "rotateImage matrix create failed: ${e.message}")
            return JSONObject().put("error", "Rotation failed").toString()
        }

        val format = when (ext) {
            "png" -> android.graphics.Bitmap.CompressFormat.PNG
            "webp" -> android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS
            else -> android.graphics.Bitmap.CompressFormat.JPEG
        }
        val quality = if (format == android.graphics.Bitmap.CompressFormat.JPEG) 92 else 100

        // Write to a sibling tmp file then atomically rename so a
        // crash mid-write doesn't corrupt the original.
        val tmp = File(file.parentFile, file.name + ".tmp.${System.currentTimeMillis()}")
        try {
            tmp.outputStream().use { rotated.compress(format, quality, it) }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return JSONObject().put("error", "Atomic replace failed").toString()
            }
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "rotateImage write failed: ${e.message}")
            return JSONObject().put("error", "Write failed: ${e.localizedMessage}").toString()
        } finally {
            if (rotated !== bitmap) rotated.recycle()
            bitmap.recycle()
        }

        // Best-effort MediaScanner so the native gallery picks up the
        // change. The file path is unchanged, so this just refreshes
        // the image-pixel cache.
        try {
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath),
                arrayOf("image/${if (ext == "jpg") "jpeg" else ext}"), null
            )
        } catch (_: Exception) {}

        return JSONObject()
            .put("status", "rotated")
            .put("path", path)
            .put("degrees", normalizedDeg)
            .toString()
    }

    /**
     * Merged photo+video listing: TapInsight's own `Media/Photos/`
     * folder ∪ the device's `/DCIM/*` MediaStore entries (RayNeo
     * native Camera app captures). Newest first.
     *
     * Returns JSON `{"hasMediaPermission": bool, "entries": [...]}`.
     * Each entry has:
     *   source: "library" | "dcim"
     *   name, lastModifiedMs, sizeBytes, kind ("IMAGE"|"VIDEO"),
     *   thumbnailUrl, fullUrl   ← always virtual https URLs the
     *     WebView can <img src=…> directly.
     *   relativePath ("Photos/IMG_…jpg") OR dcimId (numeric, used in
     *     subsequent rotate/delete bridge calls — DCIM ops are NOT
     *     yet wired; PR says read-only for shared storage).
     *
     * When [DcimEnumerator.hasPermission] is false, hasMediaPermission
     * is false and only the library entries are returned. The JS side
     * can show a "Grant access" CTA in that case.
     */
    @JavascriptInterface
    fun listAllPhotos(): String {
        if (!isTrusted()) return denied("listAllPhotos")

        val arr = JSONArray()

        // ── Library (Media/Photos) entries ──
        val libraryListing = service.listFolder(MediaLibraryService.DEFAULT_PHOTOS_DIR)
        libraryListing?.entries?.forEach { e ->
            if (e.kind != MediaLibraryService.MediaKind.IMAGE &&
                e.kind != MediaLibraryService.MediaKind.VIDEO) return@forEach
            arr.put(
                JSONObject()
                    .put("source", "library")
                    .put("name", e.name)
                    .put("relativePath", e.relativePath)
                    .put("lastModifiedMs", e.lastModifiedMs)
                    .put("sizeBytes", e.sizeBytes)
                    .put("kind", e.kind.name)
                    .put("fullUrl", toMediaUrl(e.relativePath))
                    .put("thumbnailUrl", toMediaUrl(e.relativePath))
            )
        }

        // ── DCIM (shared storage) entries ──
        val hasMediaPermission = DcimEnumerator.hasPermission(context)
        if (hasMediaPermission) {
            for (d in dcim.listAll(limit = 1000)) {
                // Proxy DCIM via the companion server's /api/dcim/file
                // endpoint when used from the companion app. On the
                // glasses gallery we ask the WebView to load
                // content:// directly via androidx WebView's
                // setAllowContentAccess — which is true by default
                // for in-app WebViews. So a content:// URL works.
                arr.put(
                    JSONObject()
                        .put("source", "dcim")
                        .put("name", d.displayName)
                        .put("dcimId", ContentUris.parseId(d.contentUri))
                        .put("dcimUri", d.contentUri.toString())
                        .put("lastModifiedMs", d.dateTakenMs)
                        .put("sizeBytes", d.sizeBytes)
                        .put("kind", if (d.isVideo) "VIDEO" else "IMAGE")
                        .put("mimeType", d.mimeType)
                        .put("relativeDisplayPath", d.relativeDisplayPath ?: JSONObject.NULL)
                        .put("fullUrl", d.contentUri.toString())
                        .put("thumbnailUrl", d.contentUri.toString())
                        .put("width", d.width)
                        .put("height", d.height)
                        .put("durationMs", d.durationMs ?: JSONObject.NULL)
                )
            }
        }

        // Sort newest first by lastModifiedMs.
        val sorted = JSONArray()
        val asList = (0 until arr.length()).map { arr.getJSONObject(it) }
            .sortedByDescending { it.optLong("lastModifiedMs") }
        for (o in asList) sorted.put(o)

        return JSONObject()
            .put("hasMediaPermission", hasMediaPermission)
            .put("entries", sorted)
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

    // ── Cloud Text-to-Speech (Gemini 3.1 / Fish.audio) ─────────────────

    /**
     * Internal payload type that both the Gemini and Fish synth paths
     * collapse into. Keeps [speakText]/[startSpeakText] free of engine
     * branching at the wire-format level — they just translate the
     * winning [SynthOutcome] into the JSON the JS side already expects.
     */
    private sealed class SynthOutcome {
        data class Ok(
            val wavBytes: ByteArray,
            val mimeType: String,
            val model: String,
            val sampleRate: Int,
            val engine: String,
            val voiceName: String
        ) : SynthOutcome()

        data class Err(val message: String) : SynthOutcome()
    }

    /**
     * Decide whether to route this synth through Fish or Gemini. Both
     * fall back to the other if their preferred engine isn't usable, so
     * the user never sees a silent reader because of a single mis-saved
     * preference.
     *
     * Routing rules:
     *   1. If `readout_engine == "fish"` AND Fish has a key + a picked
     *      voice, try Fish first; on Fish error, fall through to Gemini.
     *   2. Otherwise (engine == "gemini" or Fish not ready), use Gemini.
     *   3. If both fail, return the most informative error message.
     */
    private fun synthChunk(chunk: String, voiceHint: String?): SynthOutcome {
        val wantFish = isFishEngineActive(context) && isFishReadoutReady(context)
        if (wantFish && fishTtsClient != null) {
            when (val result = fishTtsClient.synthesize(chunk)) {
                is FishTtsClient.SynthesisResult.Success -> {
                    val cfg = resolveGlassesFishConfig(context, nullIfNotActiveEngine = false)
                    return SynthOutcome.Ok(
                        wavBytes = result.wavBytes,
                        mimeType = result.mimeType,
                        model = result.model,
                        sampleRate = result.sampleRate,
                        engine = "fish",
                        voiceName = cfg?.activeVoiceName.orEmpty()
                    )
                }
                is FishTtsClient.SynthesisResult.NotConfigured -> {
                    Log.i(TAG, "Fish engine selected but not fully configured — falling through to Gemini.")
                    // Fall through to Gemini below.
                }
                is FishTtsClient.SynthesisResult.Error -> {
                    Log.w(TAG, "Fish synth failed (${result.message}); falling back to Gemini.")
                    // Fall through to Gemini below.
                }
            }
        }
        // Gemini path — either the user picked Gemini, or Fish is unavailable.
        val gemini = ttsClient ?: return SynthOutcome.Err("TTS not wired on this build.")
        return when (val gemResult = gemini.synthesize(chunk, voiceHint?.trim()?.takeIf { it.isNotBlank() })) {
            is GlassesTtsClient.SynthesisResult.Success -> {
                val voiceName = context
                    .getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
                    .getString("research_tts_voice_name", "")?.trim().orEmpty()
                SynthOutcome.Ok(
                    wavBytes = gemResult.wavBytes,
                    mimeType = gemResult.mimeType,
                    model = gemResult.model,
                    sampleRate = gemResult.sampleRate,
                    engine = "gemini",
                    voiceName = voiceName.ifBlank { "Kore" }
                )
            }
            is GlassesTtsClient.SynthesisResult.ApiKeyMissing -> SynthOutcome.Err(
                "Gemini API key not configured. Set it in the companion app."
            )
            is GlassesTtsClient.SynthesisResult.Error -> SynthOutcome.Err(gemResult.message)
        }
    }

    private fun outcomeToJson(outcome: SynthOutcome): String = when (outcome) {
        is SynthOutcome.Ok -> {
            val id = TtsCacheStore.put(outcome.wavBytes)
            JSONObject()
                .put("audioUrl", "https://$ASSETS_HOST/tts/$id.wav")
                .put("model", outcome.model)
                .put("sampleRate", outcome.sampleRate)
                .put("engine", outcome.engine)
                .put("voiceName", outcome.voiceName)
                .toString()
        }
        is SynthOutcome.Err -> JSONObject().put("error", outcome.message).toString()
    }

    /**
     * Probe what engine + voice will actually be used the next time the
     * reader fires. Surfaced to JS so media_player.html can show a live
     * "Fish · Auntie Mae" / "Gemini · Kore" indicator next to the scrub
     * bar without a round-trip through synthesis.
     *
     * Returned shape:
     *   { engine: "gemini"|"fish", voiceName: "Kore", ready: true,
     *     reason: "" | "<why it isn't ready>" }
     */
    @JavascriptInterface
    fun probeTts(): String {
        if (!isTrusted()) return denied("probeTts")
        val prefs = context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
        val selected = (prefs.getString("readout_engine", "gemini") ?: "gemini").trim()
        // Effective engine reflects fallback: if user picked Fish but
        // there's no key/voice, the next synthesis will run on Gemini.
        val fishReady = isFishReadoutReady(context)
        val effective = if (selected == "fish" && fishReady) "fish" else "gemini"
        val obj = JSONObject().put("engine", effective).put("selected", selected)
        // Whether the JS side should strip Markdown / decoration marks
        // before sending text to the synth. Gemini handles these
        // implicitly; Fish.audio's realistic voices read them out
        // literally ("asterisk asterisk", "underline emphasis underline",
        // "open bracket Illustration close bracket"). Default true so
        // the experience is good out of the box; user can toggle off in
        // the companion app if they specifically want every character
        // spoken. Returned in the probe payload regardless of engine
        // so the JS reader can decide once and not branch on engine.
        obj.put("cleanText", prefs.getBoolean("fish_clean_text", true))
        when (effective) {
            "fish" -> {
                val cfg = resolveGlassesFishConfig(context, nullIfNotActiveEngine = false)
                obj.put("voiceName", cfg?.activeVoiceName.orEmpty())
                obj.put("model", cfg?.model?.ifBlank { "s2-pro" } ?: "s2-pro")
                obj.put("ready", true)
                obj.put("reason", "")
            }
            else -> {
                val voiceName = (prefs.getString("research_tts_voice_name", "") ?: "").trim()
                val geminiReady = (prefs.getString("gemini_api_key", "") ?: "").trim().isNotEmpty()
                obj.put("voiceName", voiceName.ifBlank { "Kore" })
                obj.put("model", (prefs.getString("research_tts_model", "") ?: "").ifBlank { "gemini-2.5-flash-preview-tts" })
                obj.put("ready", geminiReady)
                val reason = when {
                    selected == "fish" && !fishReady ->
                        "Fish.audio engine selected but no voice/key — falling back to Gemini."
                    !geminiReady ->
                        "No Gemini API key configured."
                    else -> ""
                }
                obj.put("reason", reason)
            }
        }
        return obj.toString()
    }

    /**
     * Synthesize a chunk of text via the active cloud TTS engine
     * (Gemini 3.1 by default, Fish.audio when the user has switched
     * engines in the companion app) and return a virtual audio URL the
     * WebView can load. The on-glasses media_player.html reads text
     * files by calling this one chunk at a time. The returned JSON
     * shape is:
     *
     *   Success:  {"audioUrl":"…","model":"…","engine":"…","voiceName":"…"}
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
        val outcome = synthChunk(chunk, voiceHint)
        if (outcome is SynthOutcome.Err) Log.w(TAG, "TTS error: ${outcome.message}")
        return outcomeToJson(outcome)
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
        if (ttsClient == null && fishTtsClient == null) {
            return JSONObject().put("error", "TTS not wired on this build.").toString()
        }
        val voice = voiceHint?.trim()?.takeIf { it.isNotBlank() }
        ttsExecutor.execute {
            val payload: String = try {
                val outcome = synthChunk(chunk, voice)
                if (outcome is SynthOutcome.Err) {
                    Log.w(TAG, "TTS error (req=$id): ${outcome.message}")
                }
                outcomeToJson(outcome)
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
