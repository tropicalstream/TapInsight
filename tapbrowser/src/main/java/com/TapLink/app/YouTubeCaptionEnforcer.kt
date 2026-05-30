package com.TapLinkX3.app

import android.webkit.WebView

/**
 * App-wide accessibility rule: YouTube captions should stay enabled anywhere a
 * YouTube player appears in TapBrowser. This is intentionally independent from
 * the Gemini/playlist automation path so manual browsing, restored tabs, and
 * secondary windows all get the same behavior.
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
                if (window.__taplink_caption_enforcer_bound) {
                    if (typeof window.__taplink_enable_captions_now === 'function') {
                        window.__taplink_enable_captions_now();
                    }
                    return;
                }
                window.__taplink_caption_enforcer_bound = true;

                function isVisible(el) {
                    if (!el) return false;
                    var r = el.getBoundingClientRect && el.getBoundingClientRect();
                    if (!r || r.width <= 0 || r.height <= 0) return false;
                    var style = window.getComputedStyle ? window.getComputedStyle(el) : null;
                    return !style || (style.visibility !== 'hidden' && style.display !== 'none' && style.opacity !== '0');
                }

                function enableTextTracks() {
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        var tracks = videos[i].textTracks || [];
                        var enabledOne = false;
                        for (var j = 0; j < tracks.length; j++) {
                            var kind = String(tracks[j].kind || '').toLowerCase();
                            if (kind === 'captions' || kind === 'subtitles') {
                                if (!enabledOne) {
                                    tracks[j].mode = 'showing';
                                    enabledOne = true;
                                } else if (tracks[j].mode === 'showing') {
                                    tracks[j].mode = 'hidden';
                                }
                            }
                        }
                    }
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

                function enableViaButton() {
                    var button = findCaptionButton();
                    if (!button || captionsAppearActive(button)) return;
                    try { button.click(); } catch(e) {}
                }

                window.__taplink_enable_captions_now = function() {
                    enableTextTracks();
                    enableViaButton();
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
                setTimeout(window.__taplink_enable_captions_now, 500);
                setTimeout(window.__taplink_enable_captions_now, 1500);
                setTimeout(window.__taplink_enable_captions_now, 3500);
                setInterval(function() {
                    bindVideos();
                    window.__taplink_enable_captions_now();
                }, 2500);

                var observer = new MutationObserver(function() {
                    bindVideos();
                    window.__taplink_enable_captions_now();
                });
                observer.observe(document.documentElement || document.body, {
                    childList: true,
                    subtree: true
                });
                console.log('[TapLink-CC] YouTube caption enforcer active');
            } catch(e) {
                console.log('[TapLink-CC] caption enforcer failed: ' + e);
            }
        })();
        """.trimIndent()
}
