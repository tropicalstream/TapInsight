package com.TapLinkX3.app

import android.webkit.WebView

/**
 * Glasses-specific layout shim for radio.garden.
 *
 * Radio Garden's desktop/tablet panel assumes more vertical room than the X3
 * browser viewport. On Settings, its content can be pushed under the persistent
 * now-playing card, so the user sees the Settings tab selected but no settings
 * body. This adapter keeps the left card scrollable and temporarily suppresses
 * the now-playing card only while Settings is active.
 */
object RadioGardenAdapter {
    fun maybeInject(webView: WebView?, url: String?) {
        if (webView == null || !isRadioGarden(url)) return
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun isRadioGarden(url: String?): Boolean =
        url?.contains("radio.garden", ignoreCase = true) == true

    private val script = """
        (function() {
          if (window.__taplinkRadioGardenAdapterV2) {
            try { window.__taplinkRadioGardenAdapterV2.refresh(); } catch (e) {}
            return;
          }

          function textOf(el) {
            return ((el && el.innerText) || '').replace(/\s+/g, ' ').trim();
          }

          function hasText(el, value) {
            return textOf(el).toLowerCase() === value.toLowerCase();
          }

          function rectOk(r) {
            return r && r.width > 0 && r.height > 0;
          }

          function findVisibleByText(value) {
            var all = document.querySelectorAll('button, a, div, span, h1, h2, h3, p');
            for (var i = 0; i < all.length; i++) {
              var el = all[i];
              if (!hasText(el, value)) continue;
              var r = el.getBoundingClientRect();
              if (rectOk(r)) return el;
            }
            return null;
          }

          function findPanel(anchor) {
            var node = anchor;
            var best = null;
            while (node && node !== document.body && node.nodeType === 1) {
              var r = node.getBoundingClientRect();
              if (
                rectOk(r) &&
                r.left <= 24 &&
                r.top <= 32 &&
                r.width >= 220 &&
                r.width <= 420 &&
                r.height >= 120
              ) {
                best = node;
              }
              node = node.parentElement;
            }
            return best;
          }

          function containsAllNavLabels(el) {
            var t = textOf(el).toLowerCase();
            return t.indexOf('explore') >= 0 &&
              t.indexOf('favorites') >= 0 &&
              t.indexOf('browse') >= 0 &&
              t.indexOf('search') >= 0 &&
              t.indexOf('settings') >= 0;
          }

          function findNav(panel) {
            if (!panel) return null;
            var nodes = panel.querySelectorAll('nav, div, ul, section');
            var best = null;
            var bestArea = Infinity;
            for (var i = 0; i < nodes.length; i++) {
              var el = nodes[i];
              if (!containsAllNavLabels(el)) continue;
              var r = el.getBoundingClientRect();
              if (!rectOk(r)) continue;
              var area = r.width * r.height;
              if (r.height >= 36 && r.height <= 96 && area < bestArea) {
                best = el;
                bestArea = area;
              }
            }
            return best;
          }

          function findNowPlaying(panel, nav) {
            if (!panel || !nav) return null;
            var navBottom = nav.getBoundingClientRect().bottom;
            var nodes = Array.prototype.slice.call(panel.children || []);
            var best = null;
            for (var i = 0; i < nodes.length; i++) {
              var el = nodes[i];
              var r = el.getBoundingClientRect();
              if (!rectOk(r) || r.top < navBottom - 2) continue;
              var t = textOf(el).toLowerCase();
              var looksLikePlayer =
                t.indexOf('settings') < 0 &&
                (t.indexOf('united states') >= 0 ||
                 t.indexOf('radio ') >= 0 ||
                 el.querySelector('audio, video, button, [role="slider"]'));
              if (looksLikePlayer) {
                best = el;
                break;
              }
            }
            return best;
          }

          function labelWrap(nav) {
            if (!nav) return;
            ['Explore', 'Favorites', 'Browse', 'Search', 'Settings'].forEach(function(label) {
              var el = findVisibleByText(label);
              if (!el || !nav.contains(el)) return;
              el.style.whiteSpace = 'normal';
              el.style.lineHeight = '1.05';
              el.style.textAlign = 'center';
              el.style.fontSize = '11px';
              var button = el.closest('button, a, [role="button"], [role="tab"]');
              if (button) {
                button.style.minWidth = '54px';
                button.style.paddingLeft = '2px';
                button.style.paddingRight = '2px';
                button.style.overflow = 'visible';
              }
            });
          }

          function settingsActive() {
            var heading = findVisibleByText('Settings');
            if (heading) {
              var r = heading.getBoundingClientRect();
              if (r.left < 60 && r.top < 110) return true;
            }
            var settings = findVisibleByText('Settings');
            if (settings) {
              var style = getComputedStyle(settings);
              return /0,\s*190,\s*120|rgb\(0,\s*204,\s*136\)|rgb\(0,\s*200,\s*120\)/.test(style.color);
            }
            return false;
          }

          function refresh() {
            var anchor = findVisibleByText('Settings') || findVisibleByText('Explore');
            var panel = findPanel(anchor);
            if (!panel) return;
            var nav = findNav(panel);
            labelWrap(nav);

            panel.style.maxHeight = 'calc(100vh - 12px)';
            panel.style.overflowY = 'auto';
            panel.style.overscrollBehavior = 'contain';
            panel.style.webkitOverflowScrolling = 'touch';

            if (nav) {
              nav.style.minHeight = '60px';
              nav.style.display = nav.style.display || 'flex';
              nav.style.alignItems = 'center';
              nav.style.justifyContent = 'space-around';
            }

            var player = findNowPlaying(panel, nav);
            if (player) {
              if (settingsActive()) {
                player.setAttribute('data-taplink-rg-hidden-settings-player', '1');
                player.style.display = 'none';
              } else if (player.getAttribute('data-taplink-rg-hidden-settings-player') === '1') {
                player.style.display = '';
                player.removeAttribute('data-taplink-rg-hidden-settings-player');
              }
            }
          }

          var style = document.createElement('style');
          style.id = 'taplink-radio-garden-glasses-style';
          style.textContent = [
            '@media (max-height: 520px) {',
            '  body { overflow: hidden !important; }',
            '  * { -webkit-tap-highlight-color: transparent; }',
            '  [data-taplink-rg-hidden-settings-player="1"] { display: none !important; }',
            '}'
          ].join('\n');
          document.head.appendChild(style);

          var observer = new MutationObserver(function() { window.clearTimeout(refresh.timer); refresh.timer = window.setTimeout(refresh, 80); });
          observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style', 'aria-selected'] });
          window.addEventListener('resize', refresh, { passive: true });
          document.addEventListener('click', function() { window.setTimeout(refresh, 60); window.setTimeout(refresh, 240); }, true);

          window.__taplinkRadioGardenAdapterV2 = { refresh: refresh };
          refresh();
          window.setTimeout(refresh, 300);
          window.setTimeout(refresh, 1000);
        })();
    """.trimIndent()
}
