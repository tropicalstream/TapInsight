package com.TapLinkX3.app

import android.webkit.WebView

/** Keeps YouTube captions available for hearing accessibility.
 *
 * Important: do not repeatedly drive the IFrame captions API here. On the
 * glasses that path can reset YouTube's caption clock and leave captions out
 * of sync. This helper instead makes a sparse, idempotent attempt to turn on
 * captions through YouTube's own CC button / textTracks, then only keeps the
 * caption containers visible while chrome fades.
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
        (function taplinkYouTubeCaptionEnforcer() {
            try {
                if (!window.__taplink_caption_css_bound) {
                    window.__taplink_caption_css_bound = true;
                    if (!document.getElementById('__tl_caption_visible_style')) {
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
                    }
                }
                if (window.__taplink_caption_enable_bound) {
                    if (typeof window.__taplink_enable_captions_once === 'function') {
                        window.__taplink_enable_captions_once('reinjected');
                    }
                    return;
                }
                window.__taplink_caption_enable_bound = true;

                // Per-video resolution state. We click the CC button at most a
                // few times per video, and NEVER on a video with no caption track
                // — clicking there pops YouTube's "This video does not have closed
                // captions" toast (the flashing bug) and, with the old full-DOM
                // MutationObserver, churned the CPU for the whole video (a primary
                // overheat source). State per videoKey: pending|active|unavailable.
                var state = Object.create(null);
                var tries = Object.create(null);

                function videoKey() {
                    var v = document.querySelector('video');
                    return location.href.split('&t=')[0] + '|' + (v && (v.currentSrc || v.src) || '');
                }

                // Authoritative caption availability from the player response:
                // true = caption tracks exist, false = player ready with none
                // (e.g. a live broadcast), null = player not ready yet.
                function captionTracks() {
                    try {
                        var mp = document.getElementById('movie_player');
                        var pr = (mp && mp.getPlayerResponse && mp.getPlayerResponse())
                            || window.ytInitialPlayerResponse || null;
                        if (pr && pr.captions) {
                            var tl = pr.captions.playerCaptionsTracklistRenderer;
                            var tracks = tl && tl.captionTracks;
                            return !!(tracks && tracks.length);
                        }
                        if (pr) return false;
                    } catch (e) {}
                    try {
                        var v = document.querySelector('video');
                        if (v && v.textTracks) {
                            for (var i = 0; i < v.textTracks.length; i++) {
                                var k = (v.textTracks[i].kind || '').toLowerCase();
                                if (k === 'captions' || k === 'subtitles') return true;
                            }
                        }
                    } catch (e) {}
                    return null;
                }

                function findCcButton() {
                    return document.querySelector('.ytp-subtitles-button') ||
                        document.querySelector('button[aria-label*="captions" i]') ||
                        document.querySelector('button[title*="captions" i]');
                }

                function captionsActive(btn) {
                    if (!btn) return false;
                    var pressed = (btn.getAttribute('aria-pressed') || '').toLowerCase();
                    if (pressed === 'true') return true;
                    var label = (
                        btn.getAttribute('aria-label') || btn.getAttribute('title') || ''
                    ).toLowerCase();
                    return label.indexOf('turn off') !== -1 ||
                        label.indexOf('captions on') !== -1 ||
                        btn.classList.contains('ytp-button-active');
                }

                // Force a caption/subtitle track to SHOWING. Returns true only
                // when a track was found AND is now showing — so callers can
                // tell real rendering from "button looks on but nothing draws".
                function showNativeTracks() {
                    var v = document.querySelector('video');
                    if (!v || !v.textTracks) return false;
                    for (var i = 0; i < v.textTracks.length; i++) {
                        var tr = v.textTracks[i];
                        var kind = (tr.kind || '').toLowerCase();
                        if (kind === 'captions' || kind === 'subtitles') {
                            if (tr.mode !== 'showing') tr.mode = 'showing';
                            return tr.mode === 'showing';
                        }
                    }
                    return false;
                }

                function enableNow(reason) {
                    var key = videoKey();
                    var s = state[key] || 'pending';
                    if (s === 'unavailable') return;
                    if (s === 'active') {
                        // Re-assert on each tick. Entering fullscreen (or any
                        // player rebuild) silently resets the caption render, so
                        // captions vanished until a manual CC toggle (Mars,
                        // fullscreen). Set the track showing again and, if the CC
                        // button slipped to off, click it back on. Idempotent —
                        // never an off→on toggle, so no flashing.
                        try {
                            showNativeTracks();
                            var aBtn = findCcButton();
                            if (aBtn && !captionsActive(aBtn)) {
                                try { aBtn.click(); } catch (e) {}
                            }
                        } catch (_e) {}
                        return;
                    }

                    var avail = captionTracks();
                    if (avail === false) {
                        // No caption track (e.g. a live broadcast): resolve as
                        // unavailable and NEVER click — this stops the flashing.
                        state[key] = 'unavailable';
                        return;
                    }

                    var btn = findCcButton();
                    // ALWAYS assert the native track to 'showing' — even when the
                    // CC button already reads "on". YouTube can start a video
                    // with CC enabled (button pressed) while the track mode is
                    // NOT 'showing', so nothing rendered until the user toggled
                    // CC again (Mars). textTrack.mode is idempotent (no flashing,
                    // unlike clicking / the IFrame API). Resolve to 'active' only
                    // once a track is genuinely showing, so a too-early call just
                    // retries on the next tick instead of locking in a blank.
                    var shown = false;
                    try { shown = showNativeTracks(); } catch (_e) {}

                    if (btn && captionsActive(btn)) {
                        if (shown) state[key] = 'active';
                        return; // not yet showing → stay pending, retry next tick
                    }

                    if (avail === true) {
                        if (btn && !captionsActive(btn)) {
                            try { btn.click(); } catch (e) {}
                        }
                        if (shown) state[key] = 'active';
                        return; // becomes 'active' on a later tick once it takes
                    }

                    // avail === null: player not ready. Try a bounded number of
                    // times, then give up quietly so we never spin or click blind.
                    var n = (tries[key] || 0) + 1;
                    tries[key] = n;
                    if (n >= 15) state[key] = 'unavailable';
                }

                window.__taplink_enable_captions_once = enableNow;

                // New videos are caught by play / loadedmetadata events and a slow
                // 4s interval — NOT a full-DOM-subtree MutationObserver, which
                // fired on every mutation of a live page and ran the CPU hot for
                // the whole session. enableNow() returns immediately once a video
                // is resolved, so the interval is cheap.
                setTimeout(function(){ enableNow('init-1'); }, 300);
                setTimeout(function(){ enableNow('init-2'); }, 1200);
                setTimeout(function(){ enableNow('init-3'); }, 3000);
                // 2s (was 4s) so captions return quickly after a fullscreen
                // enter/exit; the tick is cheap once a video is resolved.
                var lastFsState = !!document.getElementById('__taplink_fs_style');
                setInterval(function(){
                    // A fullscreen enter/exit resets the player's caption
                    // render — force a full re-enable for the current video so
                    // captions come back on their own (Mars).
                    var fsNow = !!document.getElementById('__taplink_fs_style');
                    if (fsNow !== lastFsState) {
                        lastFsState = fsNow;
                        var fk = videoKey();
                        if (state[fk] === 'active') { state[fk] = 'pending'; tries[fk] = 0; }
                    }
                    enableNow('interval');
                }, 2000);
                document.addEventListener('play', function(ev) {
                    if (ev && ev.target && ev.target.tagName === 'VIDEO') enableNow('play');
                }, true);
                document.addEventListener('loadedmetadata', function(ev) {
                    if (ev && ev.target && ev.target.tagName === 'VIDEO') enableNow('metadata');
                }, true);
                console.log('[TapLink-CC] enforcer = availability-gated CC enable (no DOM observer)');
            } catch (e) {
                try { console.log('[TapLink-CC] enforcer init failed: ' + e); } catch (_e) {}
            }
        })();
        """.trimIndent()
}
