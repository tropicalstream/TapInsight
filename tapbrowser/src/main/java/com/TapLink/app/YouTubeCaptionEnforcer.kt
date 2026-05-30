package com.TapLinkX3.app

import android.webkit.WebView

/**
 * App-wide accessibility rule: YouTube captions should stay enabled anywhere a
 * YouTube player appears in TapBrowser. Independent of the Gemini / playlist
 * automation path so manual browsing, restored tabs, and secondary windows all
 * get the same behavior.
 *
 * The earlier version of this enforcer drove only `button.click()` on the CC
 * button, which is silently dropped by some WebView builds (the click is not
 * treated as a real user gesture, so YouTube ignores it) — exactly the
 * symptom the user kept reporting ("captions still not showing"). This pass
 * instead leans on the YouTube IFrame Player API (`player.loadModule` +
 * `player.setOption`) which is the underlying call the CC button drives — and
 * which works without a user gesture. The native HTML5 textTracks API and the
 * button click are kept as parallel fallbacks. A small injected stylesheet
 * also forces the caption containers visible in case YouTube's auto-hide ever
 * tries to fade them out for an idle-cursor state.
 *
 * Selector list deliberately covers desktop (`.ytp-*`) AND mobile
 * (`.ytm-*` / `.player-controls`) layouts because the tapbrowser WebView ships
 * a stripped UA that can land on either rendering depending on the page entry
 * point.
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
        (function taplinkAlwaysEnableYouTubeCaptions() {
            try {
                // Re-entrant: if we're already bound, just fire the enable
                // routine again (handles back/forward navigations into a new
                // watch page without losing the binding).
                if (window.__taplink_caption_enforcer_bound) {
                    if (typeof window.__taplink_enable_captions_now === 'function') {
                        window.__taplink_enable_captions_now();
                    }
                    return;
                }
                window.__taplink_caption_enforcer_bound = true;

                // ── CSS guarantee: caption containers stay visible ───────
                // YouTube's auto-hide can briefly drop opacity on the caption
                // window alongside the chrome. Force the caption layer to
                // always render once we've enabled it, so the user actually
                // sees the line that's playing. Selectors cover desktop and
                // mobile layouts.
                try {
                    if (!document.getElementById('__tl_caption_visible_style')) {
                        var s = document.createElement('style');
                        s.id = '__tl_caption_visible_style';
                        s.textContent =
                            '.ytp-caption-window-container,' +
                            '.caption-window,' +
                            '.ytp-caption-window-rollup,' +
                            '.ytp-caption-window-bottom,' +
                            '.captions-text,' +
                            '.ytp-caption-segment,' +
                            '.ytm-caption-window-container,' +
                            '.ytm-caption-window,' +
                            '.ytm-caption-text,' +
                            '.ytp-mobile-caption-window-container{' +
                            'opacity:1!important;visibility:visible!important;' +
                            'display:block!important;pointer-events:none}';
                        document.head.appendChild(s);
                    }
                } catch (e) {}

                // ── Strategy A — YouTube IFrame Player API ───────────────
                // This is the AUTHORITATIVE path: it's what the CC button
                // calls under the hood, and it works without a synthetic
                // user gesture (which WebViews can refuse for plain
                // `.click()`).
                function viaPlayerApi() {
                    try {
                        var p = document.getElementById('movie_player')
                            || document.querySelector('.html5-video-player');
                        if (!p) return false;
                        if (typeof p.loadModule === 'function') {
                            try { p.loadModule('captions'); } catch (e) {}
                            try { p.loadModule('cc'); } catch (e) {}
                        }
                        if (typeof p.getOption !== 'function' ||
                            typeof p.setOption !== 'function') return false;
                        var tracks = [];
                        try { tracks = p.getOption('captions', 'tracklist') || []; } catch (e) {}
                        if (!tracks || !tracks.length) {
                            try { tracks = p.getOption('cc', 'tracklist') || []; } catch (e) {}
                        }
                        if (!tracks || !tracks.length) return false;
                        // Prefer English; otherwise first available.
                        var pick = null;
                        for (var i = 0; i < tracks.length; i++) {
                            var lc = ((tracks[i].languageCode || '') + '').toLowerCase();
                            if (lc === 'en' || lc.indexOf('en-') === 0) { pick = tracks[i]; break; }
                        }
                        if (!pick) pick = tracks[0];
                        try { p.setOption('captions', 'track', pick); } catch (e) {}
                        try { p.setOption('cc',       'track', pick); } catch (e) {}
                        try { p.setOption('captions', 'reload', true); } catch (e) {}
                        return true;
                    } catch (e) { return false; }
                }

                // ── Strategy B — HTML5 textTracks ────────────────────────
                // For pages that wire the WebVTT captions through native
                // textTracks (some embed paths do).
                function viaTextTracks() {
                    var didOne = false;
                    try {
                        var videos = document.querySelectorAll('video');
                        for (var i = 0; i < videos.length; i++) {
                            var tracks = videos[i].textTracks || [];
                            var enabledOne = false;
                            for (var j = 0; j < tracks.length; j++) {
                                var kind = String(tracks[j].kind || '').toLowerCase();
                                if (kind === 'captions' || kind === 'subtitles') {
                                    if (!enabledOne) {
                                        try { tracks[j].mode = 'showing'; } catch (e) {}
                                        enabledOne = true;
                                        didOne = true;
                                    } else if (tracks[j].mode === 'showing') {
                                        try { tracks[j].mode = 'hidden'; } catch (e) {}
                                    }
                                }
                            }
                        }
                    } catch (e) {}
                    return didOne;
                }

                // ── Strategy C — button click (last resort) ──────────────
                // Kept for completeness, but never relied on as the sole
                // path: in some WebViews this is silently dropped.
                function isVisible(el) {
                    if (!el) return false;
                    var r = el.getBoundingClientRect && el.getBoundingClientRect();
                    if (!r || r.width <= 0 || r.height <= 0) return false;
                    var style = window.getComputedStyle ? window.getComputedStyle(el) : null;
                    return !style || (style.visibility !== 'hidden' &&
                                      style.display !== 'none' &&
                                      style.opacity !== '0');
                }
                function findCaptionButton() {
                    var selectors = [
                        '.ytp-subtitles-button',
                        'button.ytp-subtitles-button',
                        'button[aria-keyshortcuts="c"]',
                        'button[title*="Subtitles"]',
                        'button[aria-label*="Subtitles"]',
                        'button[title*="Captions"]',
                        'button[aria-label*="Captions"]',
                        '[role="button"][aria-label*="Subtitles"]',
                        '[role="button"][aria-label*="Captions"]'
                    ];
                    for (var i = 0; i < selectors.length; i++) {
                        var el = document.querySelector(selectors[i]);
                        if (el && isVisible(el)) return el;
                    }
                    return null;
                }
                function captionsAppearActive(button) {
                    if (!button) return false;
                    var aria = String(button.getAttribute('aria-pressed') || '').toLowerCase();
                    if (aria === 'true') return true;
                    if (button.classList && (
                        button.classList.contains('ytp-button-active') ||
                        button.classList.contains('ytp-subtitles-button-active')
                    )) return true;
                    var label = String(button.getAttribute('aria-label') || button.getAttribute('title') || '').toLowerCase();
                    return label.indexOf('off') >= 0;
                }
                function viaButton() {
                    var button = findCaptionButton();
                    if (!button || captionsAppearActive(button)) return false;
                    try { button.click(); return true; } catch (e) {}
                    return false;
                }

                // ── Detection: are captions actually rendering? ──────────
                // If yes, we stop poking the page — no more flashing.
                function captionsRendering() {
                    try {
                        var nodes = document.querySelectorAll(
                            '.ytp-caption-segment, .captions-text, ' +
                            '.caption-window, .ytp-caption-window-container, ' +
                            '.ytm-caption-text, .ytm-caption-window');
                        for (var i = 0; i < nodes.length; i++) {
                            var t = '';
                            try { t = (nodes[i].innerText || nodes[i].textContent || '').trim(); } catch (e) {}
                            if (t) return true;
                        }
                    } catch (e) {}
                    return false;
                }

                window.__taplink_enable_captions_now = function() {
                    if (captionsRendering()) return;
                    var api = viaPlayerApi();
                    var tracks = viaTextTracks();
                    if (!api && !tracks) viaButton();
                };

                function bindVideos() {
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        if (videos[i].__taplink_caption_listener) continue;
                        videos[i].__taplink_caption_listener = true;
                        videos[i].addEventListener('play', window.__taplink_enable_captions_now, true);
                        videos[i].addEventListener('loadedmetadata', window.__taplink_enable_captions_now, true);
                        videos[i].addEventListener('loadeddata', window.__taplink_enable_captions_now, true);
                    }
                }

                bindVideos();
                window.__taplink_enable_captions_now();
                // Brief burst of retries while the player's caption module
                // finishes loading (the tracklist isn't always populated
                // immediately after the video element appears).
                setTimeout(window.__taplink_enable_captions_now, 500);
                setTimeout(window.__taplink_enable_captions_now, 1500);
                setTimeout(window.__taplink_enable_captions_now, 3500);
                setTimeout(window.__taplink_enable_captions_now, 7500);

                // Sparse keep-alive — once captions are rendering this is
                // basically a no-op (captionsRendering() short-circuits it).
                setInterval(function() {
                    bindVideos();
                    window.__taplink_enable_captions_now();
                }, 4000);

                var observer = new MutationObserver(function() {
                    bindVideos();
                    window.__taplink_enable_captions_now();
                });
                observer.observe(document.documentElement || document.body, {
                    childList: true,
                    subtree: true
                });
                console.log('[TapLink-CC] enforcer active (player-API primary)');
            } catch (e) {
                try { console.log('[TapLink-CC] enforcer failed: ' + e); } catch (_e) {}
            }
        })();
        """.trimIndent()
}
