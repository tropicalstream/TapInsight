package com.TapLinkX3.app

import android.webkit.WebView

/**
 * YouTube caption helper for TapBrowser.
 *
 * History note (user-driven trade-off): earlier versions of this enforcer
 * auto-enabled captions via the IFrame Player API (`loadModule('captions') +
 * setOption('captions','track', pick)`). On the RayNeo glasses that path
 * left captions multiple seconds out of sync with the audio — almost
 * certainly because each `setOption('captions','track', …)` resets the
 * caption renderer's clock to t=0 while playback is already mid-video, and
 * the burst-retry pattern re-applied it repeatedly during the first seconds.
 *
 * After seven attempts to keep auto-enable + sync, the user (Mars) asked to
 * just let YouTube's native CC button handle activation — that path stays in
 * sync because YouTube enables the track from the current playback position,
 * not from t=0. So this object is now an intentional NO-OP for the enable
 * path; we only inject a tiny stylesheet that keeps the caption window
 * container visible if YouTube auto-hides it alongside the chrome. Once the
 * user taps the CC button on the player, captions appear in proper sync.
 */
object YouTubeCaptionEnforcer {
    fun maybeInject(view: WebView?, url: String?) {
        if (view == null || !isYouTubeUrl(url)) return
        view.evaluateJavascript(SCRIPT, null)
    }

    private fun isYouTubeUrl(url: String?): Boolean {
        val lower = url?.lowercase(java.util.Locale.US).orEmpty()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    private val SCRIPT =
        """
        (function taplinkYouTubeCaptionVisibility() {
            try {
                if (window.__taplink_caption_css_bound) return;
                window.__taplink_caption_css_bound = true;
                if (document.getElementById('__tl_caption_visible_style')) return;
                var s = document.createElement('style');
                s.id = '__tl_caption_visible_style';
                // Only the OUTER caption-window containers — never the inner
                // .ytp-caption-segment / .captions-text nodes, because YouTube
                // relies on those for cue-swap transitions.
                s.textContent =
                    '.ytp-caption-window-container,' +
                    '.caption-window,' +
                    '.ytm-caption-window-container,' +
                    '.ytm-caption-window,' +
                    '.ytp-mobile-caption-window-container{' +
                    'opacity:1!important;visibility:visible!important;' +
                    'pointer-events:none}';
                document.head.appendChild(s);
                console.log('[TapLink-CC] enforcer = visibility-only (manual CC button drives enable)');
            } catch (e) {
                try { console.log('[TapLink-CC] enforcer init failed: ' + e); } catch (_e) {}
            }
        })();
        """.trimIndent()
}
