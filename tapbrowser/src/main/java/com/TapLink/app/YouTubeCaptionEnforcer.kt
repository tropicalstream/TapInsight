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
                var apiDone = Object.create(null);
                // Cue-feed watchdog: how many consecutive ticks the caption
                // window has been on-screen but EMPTY, and whether we've already
                // done the one-time un-stick toggle for this video.
                var txtEmpty = Object.create(null);
                var recovered = Object.create(null);

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

                // Drive YouTube's OWN caption module ON via the player API. The
                // native textTrack.mode does NOT render YouTube's caption overlay
                // (it's module-driven), which is why captions only appeared after
                // a manual CC click. Called ONCE per video / per fullscreen
                // re-enable (apiDone gate), never per tick, so it can't reset the
                // caption clock. Returns true if a caption track was selected.
                // How much caption text is currently drawn in the window. The
                // window can read "on" (button pressed, track selected) yet be
                // EMPTY because the cue feed stalled — this is exactly what the
                // on-device log showed (txt=0). We use this to detect that.
                function captionTextLen() {
                    var cc = document.querySelector(
                        '.ytp-caption-window-container,.caption-window,' +
                        '.ytm-caption-window-container,.ytp-mobile-caption-window-container');
                    return cc ? (cc.textContent || '').trim().length : 0;
                }

                // One real off->on toggle via the CC button — the same action a
                // user performs by hand, which is the only thing observed to
                // actually start the cue feed. Done at most once per video.
                function recoverCaptions() {
                    var b = findCcButton();
                    if (!b) return false;
                    try { b.click(); } catch (e) {}   // -> off
                    setTimeout(function() {
                        try {
                            var b2 = findCcButton();
                            if (b2 && !captionsActive(b2)) b2.click();   // -> on, feeds cues
                        } catch (e) {}
                    }, 300);
                    return true;
                }

                function forceCaptions() {
                    var mp = document.getElementById('movie_player');
                    if (!mp || typeof mp.setOption !== 'function') return false;
                    try {
                        var list = null;
                        try { list = mp.getOption('captions', 'tracklist'); } catch (e) {}
                        if ((!list || !list.length) && typeof mp.loadModule === 'function') {
                            try { mp.loadModule('captions'); } catch (e) {}
                            try { list = mp.getOption('captions', 'tracklist'); } catch (e) {}
                        }
                        if (list && list.length) {
                            var track = null;
                            for (var i = 0; i < list.length; i++) {
                                if ((list[i].kind || '') !== 'asr') { track = list[i]; break; }
                            }
                            if (!track) track = list[0];
                            mp.setOption('captions', 'track', track);
                            return true;
                        }
                    } catch (e) {}
                    return false;
                }

                function enableNow(reason) {
                    var key = videoKey();
                    var s = state[key] || 'pending';
                    if (s === 'unavailable') return;
                    if (s === 'active') {
                        // Re-assert on each tick. Entering fullscreen (or any
                        // player rebuild) silently resets the caption render, so
                        // captions vanished until a manual CC toggle (user,
                        // fullscreen). Set the track showing again and, if the CC
                        // button slipped to off, click it back on. Idempotent —
                        // never an off→on toggle, so no flashing.
                        try {
                            showNativeTracks();
                            var aBtn = findCcButton();
                            if (aBtn && !captionsActive(aBtn)) {
                                try { aBtn.click(); } catch (e) {}
                            }
                            // Cue-feed watchdog. The window can read "on" yet
                            // stay empty (stuck feed — the txt=0 bug). If it's
                            // been empty for a few ticks, do ONE off->on toggle
                            // for this video to start the cues drawing (user).
                            if (captionTextLen() > 0) {
                                txtEmpty[key] = 0;
                            } else {
                                txtEmpty[key] = (txtEmpty[key] || 0) + 1;
                                if (txtEmpty[key] >= 3 && !recovered[key]) {
                                    recovered[key] = true;
                                    recoverCaptions();
                                    try { console.log('[TapLink-CC] recover: off->on toggle (empty window)'); } catch (e) {}
                                }
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

                    if (avail === true) {
                        // Drive YouTube's module ON via the player API — ONCE per
                        // video (apiDone gate) so the caption clock isn't reset.
                        // The textTrack.mode + CC-button click are backups for
                        // layouts where the API isn't exposed.
                        var forced = apiDone[key] === true;
                        if (!forced) {
                            try { forced = forceCaptions(); } catch (_e) {}
                            if (forced) apiDone[key] = true;
                        }
                        try { showNativeTracks(); } catch (_e) {}
                        var btn = findCcButton();
                        if (btn && !captionsActive(btn)) {
                            try { btn.click(); } catch (e) {}
                        }
                        // Resolved once we forced the module OR the button reads on.
                        if (apiDone[key] || (btn && captionsActive(btn))) state[key] = 'active';
                        try {
                            var mp = document.getElementById('movie_player');
                            var cc = document.querySelector(
                                '.ytp-caption-window-container,.caption-window,' +
                                '.ytm-caption-window-container,.ytp-mobile-caption-window-container');
                            var ccInfo = 'none';
                            if (cc) {
                                var cs = getComputedStyle(cc);
                                var r = cc.getBoundingClientRect();
                                ccInfo = 'vis=' + cs.visibility + ' op=' + cs.opacity +
                                    ' disp=' + cs.display + ' w=' + Math.round(r.width) +
                                    ' h=' + Math.round(r.height) + ' top=' + Math.round(r.top) +
                                    ' txt=' + ((cc.textContent || '').trim().length);
                            }
                            console.log('[TapLink-CC] enableNow reason=' + reason +
                                ' avail=' + avail + ' forced=' + forced +
                                ' apiExposed=' + !!(mp && typeof mp.setOption === 'function') +
                                ' btn=' + !!btn +
                                ' btnActive=' + !!(btn && captionsActive(btn)) +
                                ' state=' + state[key] + ' ccWin=[' + ccInfo + ']');
                        } catch (_e) {}
                        return;
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
                    // captions come back on their own (user).
                    var fsNow = !!document.getElementById('__taplink_fs_style');
                    if (fsNow !== lastFsState) {
                        lastFsState = fsNow;
                        var fk = videoKey();
                        // Force a full re-enable (including the player-API call)
                        // so captions re-render after the fullscreen transition.
                        if (state[fk] === 'active') {
                            state[fk] = 'pending'; tries[fk] = 0; apiDone[fk] = false;
                            txtEmpty[fk] = 0; recovered[fk] = false;
                        }
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
