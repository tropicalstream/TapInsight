package com.TapLink.app.media

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.Locale

/**
 * WebView resource interceptor that serves files from the on-glasses Media
 * folder and the asset pages that drive the library UI through a single
 * virtual https host.
 *
 *   https://appassets.androidplatform.net/media/<relative-path>   (media bytes)
 *   https://appassets.androidplatform.net/assets/<filename>        (APK assets)
 *
 * Why both on one host: library_local.html and media_player.html issue
 * cross-origin sub-resource requests for their media. Loading the HTML
 * from file:///android_asset/... and the media from https://appassets
 * means the audio element is pulling cross-origin — which the WebView
 * silently drops for many sub-resource types (and which breaks entirely
 * when allowFileAccess = false). Serving both the page and its media from
 * the same virtual origin makes everything same-origin and lets <audio>
 * / <video> elements load the interceptor-backed URL without any CORS
 * gymnastics.
 *
 * The virtual host mirrors what WebViewAssetLoader uses, but we do the
 * routing by hand so we can:
 *
 *   • Honor Range / partial-content requests for audio/video seeking. The
 *     built-in PathHandler API only receives the path string, so it can't
 *     see the Range header — which means HTML5 <audio>/<video> can't
 *     resume mid-stream. Doing it ourselves gives us 206 + Content-Range.
 *   • Route through MediaLibraryService.resolveSafe(), which enforces
 *     Media-root containment the same way MediaLibraryBridge does, so a
 *     compromised asset page can't escape the library root even via
 *     absolute/encoded URL tricks.
 *
 * Not gated on the "trusted asset" check — the URL space is read-only and
 * contained to Media/ + the packaged assets, and we'd otherwise break
 * third-party HTTPS pages from ever loading these URLs if they tried
 * (they can't actually see them, but keeping the interceptor unconditional
 * is simpler and matches the semantics of a real asset host).
 */
class MediaFileInterceptor(
    private val context: Context,
    private val service: MediaLibraryService
) {

    companion object {
        private const val TAG = "MediaFileInterceptor"
        const val HOST = "appassets.androidplatform.net"
        const val MEDIA_PREFIX = "/media/"
        const val ASSETS_PREFIX = "/assets/"
        const val TTS_PREFIX = "/tts/"

        /**
         * Filenames we're willing to serve out of the APK assets/ folder via
         * https://appassets.androidplatform.net/assets/ . Kept narrow on
         * purpose — a stray third-party page shouldn't be able to walk
         * assets/ just because it guessed a name.
         */
        private val ALLOWED_ASSET_PAGES = setOf(
            "library_local.html",
            "media_player.html"
        )

        /** URL of an on-glasses asset page served through this interceptor. */
        fun assetUrl(filename: String): String =
            "https://$HOST$ASSETS_PREFIX${filename.trimStart('/')}"

        /** Return true if the url matches our virtual media host+prefix. */
        fun matches(url: String?): Boolean {
            if (url == null) return false
            val lower = url.lowercase(Locale.US)
            return (lower.startsWith("https://$HOST/media/") ||
                    lower.startsWith("http://$HOST/media/"))
        }

        /** Return true if the url is one of our https-served asset pages. */
        fun matchesAssetPage(url: String?): Boolean {
            if (url == null) return false
            val lower = url.lowercase(Locale.US)
            return (lower.startsWith("https://$HOST/assets/") ||
                    lower.startsWith("http://$HOST/assets/"))
        }
    }

    /**
     * Attempt to handle a WebView resource request. Returns null if the
     * request isn't for our virtual media host, so the caller can fall
     * through to normal network loading.
     */
    fun handle(request: WebResourceRequest?): WebResourceResponse? {
        // Pin to a non-null local so Kotlin smart-casts `req` for later
        // header lookups — `request?.url` alone does not flow a non-null
        // guarantee back to the parameter reference.
        val req = request ?: return null
        val uri = req.url ?: return null
        if (!HOST.equals(uri.host, ignoreCase = true)) return null
        val rawPath = uri.path ?: return null

        // Visibility on the on-glasses hang: log once per request. If the
        // spinner is stuck, `adb logcat | grep MediaFileInterceptor` shows
        // whether a /assets/media_player.html main-frame request AND the
        // subsequent /media/... <audio> request both arrived.
        Log.d(TAG, "handle url=${req.url} method=${req.method} range=${req.requestHeaders?.entries?.firstOrNull { it.key.equals("Range", true) }?.value}")

        // ── /assets/<file> → APK assets (HTML pages that drive the UI) ──
        if (rawPath.startsWith(ASSETS_PREFIX)) {
            return handleAssetRequest(rawPath.removePrefix(ASSETS_PREFIX))
        }

        // ── /tts/<id>.wav → synthesized audio from TtsCacheStore ──
        // Written by MediaLibraryBridge.speakText() when the on-glasses media
        // player wants to read a text file aloud via Gemini 3.1. Same virtual
        // origin as /media/ so the WebView doesn't trip over CORS.
        if (rawPath.startsWith(TTS_PREFIX)) {
            return handleTtsRequest(rawPath.removePrefix(TTS_PREFIX))
        }

        if (!rawPath.startsWith(MEDIA_PREFIX)) return null

        val encodedRel = rawPath.removePrefix(MEDIA_PREFIX)
        val relPath = try {
            URLDecoder.decode(encodedRel, "UTF-8")
        } catch (e: Exception) {
            Log.w(TAG, "Bad URL encoding in /media path: $encodedRel")
            return errorResponse(400, "Bad Request")
        }

        val file = service.resolveSafe(relPath)
            ?: run {
                Log.w(TAG, "/media path outside root: $relPath")
                return errorResponse(403, "Forbidden")
            }
        if (!file.exists() || !file.isFile) {
            Log.w(TAG, "/media file not found: relPath=$relPath resolved=${file.path}")
            return errorResponse(404, "Not Found")
        }

        val mime = guessMime(file.name)
        val totalLength = file.length()
        val rangeHeader = req.requestHeaders
            ?.entries
            ?.firstOrNull { it.key.equals("Range", ignoreCase = true) }
            ?.value
            ?.trim()

        return try {
            if (!rangeHeader.isNullOrEmpty()) {
                buildPartialResponse(file, totalLength, rangeHeader, mime)
                    ?: buildFullResponse(file, totalLength, mime)
            } else {
                buildFullResponse(file, totalLength, mime)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to serve $relPath: ${e.message}")
            errorResponse(500, "Internal Error")
        }
    }

    // ── Asset pages (library_local.html, media_player.html, …) ────────

    /**
     * Serve a file out of the APK's assets/ folder.
     *
     * Path safety: we refuse anything containing "..", leading "/", or a
     * backslash, and only accept an allowlisted set of filenames so a
     * compromised remote page can't brute-force its way into reading
     * arbitrary assets via the virtual host. We intentionally keep the
     * set small — just the pages that drive the library/player UI — and
     * add entries as we need them.
     */
    private fun handleAssetRequest(rawRel: String): WebResourceResponse {
        val cleaned = try {
            URLDecoder.decode(rawRel, "UTF-8")
        } catch (e: Exception) {
            return errorResponse(400, "Bad Request")
        }
        // Strip query/fragment (shouldn't arrive here, but be defensive).
        val bare = cleaned.substringBefore('?').substringBefore('#')
        if (bare.isEmpty() ||
            bare.contains("..") ||
            bare.startsWith("/") ||
            bare.contains('\\')
        ) {
            Log.w(TAG, "Rejecting /assets request: $bare")
            return errorResponse(403, "Forbidden")
        }
        if (bare !in ALLOWED_ASSET_PAGES) {
            Log.w(TAG, "Rejecting non-allowlisted /assets request: $bare")
            return errorResponse(404, "Not Found")
        }

        return try {
            // Read the asset fully so we can hand the WebView a real
            // Content-Length header. Some WebView builds sit on a
            // spinner when the main-frame response is a chunked stream
            // with no declared length, and these pages are small.
            val bytes = context.assets.open(bare).use { it.readBytes() }
            val mime = guessMime(bare)
            val headers = linkedMapOf(
                "Content-Length" to bytes.size.toString(),
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*"
            )
            val encoding = if (mime.startsWith("text/")) "UTF-8" else null
            val resp = WebResourceResponse(mime, encoding, ByteArrayInputStream(bytes))
            resp.responseHeaders = headers
            resp.setStatusCodeAndReasonPhrase(200, "OK")
            Log.d(TAG, "served asset $bare (${bytes.size} bytes, $mime)")
            resp
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open asset $bare: ${e.message}")
            errorResponse(404, "Not Found")
        }
    }

    // ── /tts/<id>.wav → from TtsCacheStore ───────────────────────────

    /**
     * Serve a synthesized TTS WAV out of [TtsCacheStore]. The bridge wrote
     * it there right before asking the JS side to load the URL, so the lookup
     * should hit on the first request; a cache miss only happens if the
     * WebView retries after the cache evicted the entry (bounded LRU).
     */
    private fun handleTtsRequest(rawId: String): WebResourceResponse {
        val bare = rawId.substringBefore('?').substringBefore('#')
        // Strip trailing .wav the JS side appended for the mime hint.
        val id = bare.removeSuffix(".wav")
        val bytes = TtsCacheStore.get(id)
            ?: return errorResponse(404, "TTS cache miss")
        val headers = linkedMapOf(
            "Content-Length" to bytes.size.toString(),
            "Accept-Ranges" to "bytes",
            "Cache-Control" to "no-store",
            "Access-Control-Allow-Origin" to "*"
        )
        val resp = WebResourceResponse("audio/wav", null, ByteArrayInputStream(bytes))
        resp.responseHeaders = headers
        resp.setStatusCodeAndReasonPhrase(200, "OK")
        Log.d(TAG, "served TTS $id (${bytes.size}B)")
        return resp
    }

    // ── Response builders ─────────────────────────────────────────────

    private fun buildFullResponse(
        file: File,
        length: Long,
        mime: String
    ): WebResourceResponse {
        val headers = linkedMapOf(
            "Content-Length" to length.toString(),
            "Accept-Ranges" to "bytes",
            "Cache-Control" to "no-store",
            "Access-Control-Allow-Origin" to "*"
        )
        val encoding = if (mime.startsWith("text/")) "UTF-8" else null
        val resp = WebResourceResponse(mime, encoding, FileInputStream(file))
        resp.responseHeaders = headers
        resp.setStatusCodeAndReasonPhrase(200, "OK")
        return resp
    }

    private fun buildPartialResponse(
        file: File,
        totalLength: Long,
        rangeHeader: String,
        mime: String
    ): WebResourceResponse? {
        val (start, end) = parseRange(rangeHeader, totalLength) ?: run {
            // Return 416 so the client knows to retry without Range.
            val headers = linkedMapOf(
                "Content-Range" to "bytes */$totalLength",
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*"
            )
            val resp = WebResourceResponse(
                "text/plain",
                "UTF-8",
                ByteArrayInputStream(ByteArray(0))
            )
            resp.responseHeaders = headers
            resp.setStatusCodeAndReasonPhrase(416, "Range Not Satisfiable")
            return resp
        }

        val contentLength = end - start + 1
        val stream = BoundedFileStream(file, start, contentLength)
        val headers = linkedMapOf(
            "Content-Type" to mime,
            "Content-Length" to contentLength.toString(),
            "Accept-Ranges" to "bytes",
            "Content-Range" to "bytes $start-$end/$totalLength",
            "Cache-Control" to "no-store",
            "Access-Control-Allow-Origin" to "*"
        )
        val encoding = if (mime.startsWith("text/")) "UTF-8" else null
        val resp = WebResourceResponse(mime, encoding, stream)
        resp.responseHeaders = headers
        resp.setStatusCodeAndReasonPhrase(206, "Partial Content")
        return resp
    }

    private fun errorResponse(code: Int, reason: String): WebResourceResponse {
        val resp = WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(reason.toByteArray(Charsets.UTF_8))
        )
        resp.responseHeaders = mapOf(
            "Cache-Control" to "no-store",
            "Access-Control-Allow-Origin" to "*"
        )
        resp.setStatusCodeAndReasonPhrase(code, reason)
        return resp
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Parse an HTTP Range header of the form `bytes=<start>-<end>` (either
     * end optional). Returns null on malformed input or unsatisfiable ranges.
     * Only the first byte-range-spec is honored — we don't support
     * multipart/byteranges.
     */
    private fun parseRange(header: String, totalLength: Long): Pair<Long, Long>? {
        if (totalLength <= 0) return null
        val spec = header.substringAfter("bytes=", "").substringBefore(',').trim()
        if (spec.isEmpty()) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startStr = spec.substring(0, dash).trim()
        val endStr = spec.substring(dash + 1).trim()

        val start: Long
        val end: Long
        when {
            startStr.isEmpty() -> {
                // Suffix-range: "bytes=-500" means last 500 bytes.
                val suffix = endStr.toLongOrNull() ?: return null
                if (suffix <= 0) return null
                start = (totalLength - suffix).coerceAtLeast(0L)
                end = totalLength - 1
            }
            endStr.isEmpty() -> {
                // Open-ended range: "bytes=500-" means 500 to end.
                start = startStr.toLongOrNull() ?: return null
                end = totalLength - 1
            }
            else -> {
                start = startStr.toLongOrNull() ?: return null
                end = endStr.toLongOrNull() ?: return null
            }
        }
        if (start < 0 || end < start || start >= totalLength) return null
        val clampedEnd = end.coerceAtMost(totalLength - 1)
        return start to clampedEnd
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/aac"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "weba" -> "audio/webm"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "m3u" -> "audio/x-mpegurl"
            "m3u8" -> "application/vnd.apple.mpegurl"
            "txt", "log", "rtf" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "yaml", "yml" -> "application/x-yaml"
            "toml", "ini", "cfg", "conf" -> "text/plain"
            "html", "htm" -> "text/html"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
    }

    // ── Bounded stream ────────────────────────────────────────────────

    /**
     * FileInputStream wrapper that starts at [startOffset] and reads at
     * most [remaining] bytes. WebView consumes this on a background thread
     * and closes it when the request ends.
     */
    private class BoundedFileStream(
        file: File,
        startOffset: Long,
        initialRemaining: Long
    ) : InputStream() {
        private val fis = FileInputStream(file).also {
            if (startOffset > 0) {
                var skipped = 0L
                while (skipped < startOffset) {
                    val s = it.skip(startOffset - skipped)
                    if (s <= 0) break
                    skipped += s
                }
            }
        }
        private var remaining: Long = initialRemaining

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = fis.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = fis.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int {
            val raw = fis.available()
            return minOf(raw.toLong(), remaining.coerceAtLeast(0L)).toInt()
        }

        override fun close() {
            try { fis.close() } catch (_: Exception) {}
        }
    }
}
