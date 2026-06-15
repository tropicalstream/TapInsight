package com.TapLinkX3.app

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView

/**
 * Stability-biased YouTube hints for the RayNeo X3 WebView path. SmartTube
 * can chase high-quality streams because it bypasses WebView and owns its
 * ExoPlayer/MediaCodec track selection. TapBrowser runs YouTube inside the
 * vendor WebView, where HD decode + compositor pressure has shown reboot-time
 * native tombstones on this hardware.
 *
 * What these knobs do:
 *  1. Clear TapInsight's old forced-HD preference cookie (`PREF=f6=400&hd=1`)
 *     so YouTube's adaptive bitrate is free to pick a resolution that fits the
 *     small player and the hardware H.264 decoder.
 *  2. Do NOT force a low quality. Earlier builds capped to `small` (240p); on
 *     this hardware that forcing dropped playback onto the CPU/software-decode
 *     path and ran HOTTER than the HW-accelerated stream ABR picks on its own —
 *     a primary cause of the ~12-minute overheat. Quality is left to ABR.
 *  3. Avoid off-screen pre-raster buffers and use bound renderer priority so
 *     Android can kill/restart the renderer before the whole system is at risk.
 *
 * Nothing in this file changes UI, layout, manifest, theme, or
 * navigation behavior — these are pure performance-and-quality knobs.
 */
object VideoQualityHints {

    private const val TAG = "TapVideoQualityHints"

    /**
     * Remove TapInsight's old "default to HD" cookie. Idempotent — calling
     * twice just keeps the cookie absent. Should be called once early in
     * Activity start before the first YouTube navigation.
     */
    fun primeYouTubeHdCookie(@Suppress("UNUSED_PARAMETER") context: Context) {
        val cm = try { CookieManager.getInstance() } catch (e: Exception) {
            Log.w(TAG, "primeYouTubeHdCookie: CookieManager unavailable", e)
            return
        }
        cm.setAcceptCookie(true)
        for (domain in listOf("https://www.youtube.com", "https://m.youtube.com", "https://youtu.be")) {
            cm.setCookie(domain, "PREF=; Path=/; Max-Age=0; Secure")
        }
        cm.flush()
        Log.d(TAG, "primeYouTubeHdCookie: cleared TapInsight HD PREF for youtube domains")
    }

    /**
     * Assert playsInline on the YouTube page if it's a YouTube URL. Called from
     * `WebViewClient.onPageFinished` — already on the UI thread there so
     * [WebView.evaluateJavascript] is safe.
     *
     * Deliberately does NOT force a playback quality. An earlier version poked
     * `setPlaybackQualityRange('small','small')` on a 500ms retry loop to cap at
     * 240p; on this hardware that forcing dropped playback onto software decode
     * and ran HOTTER than the HW-accelerated stream YouTube's adaptive bitrate
     * picks for the small player — a primary cause of the ~12-minute overheat.
     * Quality is now left entirely to ABR.
     *
     * Safe to call on any URL — the script no-ops if it can't find a video.
     */
    fun maybeApplyYouTubeQualityShim(webView: WebView, url: String?) {
        if (url == null) return
        val lower = url.lowercase()
        if (!lower.contains("youtube.com") && !lower.contains("youtu.be")) return
        // Quality is intentionally LEFT to YouTube's adaptive bitrate. ABR picks
        // a resolution suited to the small (~640x480/eye) player and the device's
        // hardware H.264 decoder. Earlier builds force-capped to 'small' (240p);
        // on this hardware that forcing pushed playback onto the CPU/software-
        // decode path and ran HOTTER than the HW-accelerated stream ABR would
        // otherwise pick — the opposite of the intent, and a primary source of
        // the ~12-minute overheat/reboot. We now only assert playsInline so the
        // video stays embedded; we do NOT down-force the quality (no
        // setPlaybackQuality, no polling loop).
        val js = """
            (function(){
                try {
                    var v = document.querySelector('video');
                    if (v && typeof v.playsInline === 'boolean') v.playsInline = true;
                } catch (e) { /* ignore */ }
            })();
        """.trimIndent().replace("\n", " ")
        try {
            webView.evaluateJavascript(js, null)
        } catch (e: Exception) {
            Log.w(TAG, "maybeApplyYouTubeQualityShim: evaluateJavascript failed", e)
        }
    }

    /**
     * Reassert WebView performance flags that meaningfully affect
     * video playback smoothness. Safe to call multiple times — every
     * call is a setter on the same WebView, no allocation.
     */
    fun applyMediaPerformanceSettings(webView: WebView) {
        try {
            // Bound keeps the visible renderer from being treated as idle, but
            // still lets Android reclaim it before the rest of the system gets
            // into trouble. A renderer kill is recoverable; a vendor video-stack
            // reboot is not.
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)

            // Keep off-screen pre-raster OFF on the glasses. It can improve
            // tab-swap polish, but it asks WebView to retain extra tiles/frame
            // buffers while video is playing.
            try {
                @Suppress("UNRESOLVED_REFERENCE")
                webView.javaClass.getMethod("setOffscreenPreRaster", java.lang.Boolean.TYPE)
                    .invoke(webView, false)
            } catch (_: Throwable) {
                // Method missing on some vendor WebView builds; skip
                // silently — the renderer priority bump above is the
                // larger win and is already in effect.
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyMediaPerformanceSettings: failed", e)
        }
    }
}
