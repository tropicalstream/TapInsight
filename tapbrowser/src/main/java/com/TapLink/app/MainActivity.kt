package com.TapLinkX3.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.Camera
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ImageReader
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Parcel
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.view.Choreographer
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.camera.CameraConfigurationUtils
import com.ffalconxr.mercury.ipc.Launcher
import com.ffalconxr.mercury.ipc.helpers.GPSIPCHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import com.TapLink.app.media.MediaFileInterceptor
import com.TapLink.app.media.MediaLibraryBridge
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import org.json.JSONObject

interface NavigationListener {
    fun onNavigationBackPressed()
    fun onNavigationForwardPressed()
    fun onQuitPressed()
    fun onSettingsPressed()
    fun onRefreshPressed()
    fun onHomePressed()
    fun onHyperlinkPressed()
}

interface LinkEditingListener {
    fun onShowLinkEditing()
    fun onHideLinkEditing()
    fun onSendCharacterToLink(character: String)
    fun onSendBackspaceInLink()
    fun onSendEnterInLink()
    fun onSendClearInLink()
    fun isLinkEditing(): Boolean
}

class MainActivity :
        AppCompatActivity(),
        DualWebViewGroup.DualWebViewGroupListener,
        NavigationListener,
        CustomKeyboardView.OnKeyboardActionListener,
        BookmarkListener,
        BookmarkKeyboardListener,
        LinkEditingListener,
        DualWebViewGroup.MaskToggleListener,
        DualWebViewGroup.AnchorToggleListener,
        DualWebViewGroup.WindowCallback {

    companion object {
        private const val EXTRA_BROWSER_INITIAL_URL = "tapclaw_initial_url"

        /**
         * Phase 4v — reader-mode script. Extracts the main article (paragraph
         * density heuristic à la Safari/Firefox Reader), strips chrome/ads,
         * and re-renders it in a dark, large-text, high-contrast layout tuned
         * for the RayNeo X3 Pro (pure-black background reads as see-through on
         * the AR display; off-white text; 21px / 1.65 line-height). Running it
         * again reloads the page to exit.
         */
        private const val READER_MODE_JS = """
(function(){
  try {
    var D=document, W=window;
    if (W.__tapReaderActive) { return 'reader:already'; }
    function t(el){ return el ? (el.innerText||el.textContent||'').trim() : ''; }
    function esc(s){ return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
    var title='';
    var m=D.querySelector('meta[property="og:title"]'); if(m) title=m.getAttribute('content')||'';
    if(!title){ var h=D.querySelector('h1'); if(h) title=t(h); }
    if(!title) title=D.title||'';
    var best=null, bestScore=0;
    var nodes=D.querySelectorAll('article, main, [role=main], section, div');
    for(var i=0;i<nodes.length;i++){
      var el=nodes[i], ps=el.querySelectorAll('p');
      if(!ps.length) continue;
      var s=0;
      for(var j=0;j<ps.length;j++){ var p=t(ps[j]); if(p.length>40) s+=p.length; }
      s -= el.querySelectorAll('a').length*25;
      if(s>bestScore){ bestScore=s; best=el; }
    }
    var src=D.querySelector('article')||best||D.body;
    var clone=src.cloneNode(true);
    var junk=clone.querySelectorAll('script,style,noscript,iframe,form,button,input,select,svg,canvas,nav,aside,header,footer,[role=navigation],[role=banner],[role=complementary],[aria-hidden=true]');
    for(var k=0;k<junk.length;k++){ junk[k].remove(); }
    // Convert images to text markers — a text-browser (lynx) shows no
    // images, just an [image] placeholder / alt text.
    var imgs=clone.querySelectorAll('img');
    for(var a=0;a<imgs.length;a++){
      var alt=(imgs[a].getAttribute('alt')||'').trim();
      var rep=D.createElement('span'); rep.className='img';
      rep.textContent='[image'+(alt?': '+alt:'')+']';
      if(imgs[a].parentNode) imgs[a].parentNode.replaceChild(rep, imgs[a]);
    }
    var html=clone.innerHTML;
    if((clone.innerText||'').trim().length<200){
      var body=(D.body.innerText||'').trim();
      html='<p>'+esc(body).replace(/\n{2,}/g,'</p><p>').replace(/\n/g,'<br>')+'</p>';
    }
    // Bold monospace terminal look — modelled on the lynx text browser:
    // pure-black bg, bright bold text, bold cyan underlined links, code in
    // terminal green.
    var css='html,body{margin:0;padding:0;background:#000;}'+
      '.tr{background:#000;color:#FFFFFF;font-family:"DejaVu Sans Mono","Courier New",monospace;font-weight:700;-webkit-font-smoothing:antialiased;}'+
      '.tr .doc{max-width:760px;margin:0 auto;padding:26px 20px 90px;font-size:22px;line-height:1.5;}'+
      '.tr h1{font-size:26px;font-weight:700;text-transform:uppercase;letter-spacing:1px;color:#FFFFFF;margin:0 0 16px;border-bottom:2px solid #FFFFFF;padding-bottom:8px;}'+
      '.tr h2{font-size:23px;font-weight:700;color:#FFFFFF;margin:26px 0 10px;}'+
      '.tr h3{font-size:22px;font-weight:700;color:#E8E8E8;margin:20px 0 8px;}'+
      '.tr p{margin:0 0 16px;color:#FFFFFF;font-weight:700;}'+
      '.tr a{color:#3FE0FF;font-weight:700;text-decoration:underline;}'+
      '.tr li{margin:6px 0;} .tr ul,.tr ol{padding-left:26px;}'+
      '.tr .img{color:#888;font-weight:400;}'+
      '.tr blockquote{border-left:3px solid #FFFFFF;margin:14px 0;padding:2px 0 2px 14px;color:#CFCFCF;}'+
      '.tr pre,.tr code{background:#111;color:#7CFF7C;font-weight:700;border-radius:4px;}'+
      '.tr pre{padding:10px;overflow-x:auto;}';
    D.documentElement.innerHTML='<head><meta name="viewport" content="width=device-width, initial-scale=1"><style>'+css+'</style></head><body class="tr"><article class="doc">'+(title?'<h1>'+esc(title)+'</h1>':'')+html+'</article></body>';
    W.__tapReaderActive=true;
    W.scrollTo(0,0);
    return 'reader:on';
  } catch(e){ return 'reader:err'; }
})();
"""

        /** Exit reader mode: restore the original page (only if reader mode
         *  is active) by reloading. Reader mode is sticky — it stays until
         *  this runs. */
        private const val READER_MODE_EXIT_JS = """
(function(){
  try {
    if (window.__tapReaderActive) { window.__tapReaderActive=false; window.location.reload(); return 'reader:off'; }
    return 'reader:noop';
  } catch(e){ return 'reader:err'; }
})();
"""

        private const val EXTRA_RETURN_TO_CHAT_ON_DOUBLE_TAP =
                "tapclaw_return_to_chat_double_tap"
        /** Set by visionclaw MainActivity when it warm-starts us for
         *  the browser_vision tool. We finish onCreate, attach the
         *  WebView to BrowserFrameHolder, then push our task to the
         *  back so the chat panel stays foreground. */
        private const val EXTRA_BROWSER_WARM_START = "tapclaw_warm_start"
        /** Phase 4g — left-arm SHORT-TAP camera-toggle thresholds.
         *  Mirror the visionclaw constants so the gesture feels
         *  identical to the Hermes-branch UX. */
        private const val LEFT_ARM_TAP_MAX_MS = 300L
        private const val LEFT_ARM_TAP_MOVE_TOLERANCE_PX = 30f

        /** Unipanel v2 — flag we pass back to visionclaw when WE
         *  warm-start IT. Must match the value in
         *  com.rayneo.visionclaw.MainActivity.EXTRA_TAPCLAW_WARM_START.
         *  We declare a parallel copy here because the module
         *  dependency runs visionclaw → tapbrowser, so tapbrowser
         *  can't reference visionclaw classes at compile time. */
        private const val EXTRA_TAPCLAW_WARM_START = "visionclaw_warm_start"
        private const val EXTRA_YOUTUBE_AUTOPLAY_QUERY = "tapclaw_youtube_autoplay_query"
        private const val EXTRA_YOUTUBE_AUTOPLAY_MODE = "tapclaw_youtube_autoplay_mode"
        private const val EXTRA_YOUTUBE_AUTOPLAY_QUEUE = "tapclaw_youtube_autoplay_queue"
        private const val TAPCLAW_MAIN_ACTIVITY = "com.rayneo.visionclaw.MainActivity"
        private const val UNIPANEL_ASSISTANT_CARD_DISPLAY_MS = 6_000L
        private const val UNIPANEL_HEARTBEAT_DISPLAY_MS = 6_000L
        private var activeInstanceRef: WeakReference<MainActivity>? = null

        // ── Static ExoPlayer reference ──
        // When the Activity is destroyed and recreated (common on AR glasses with
        // limited RAM), the instance-level nativeRadioPlayer field is lost but the
        // ExoPlayer may still be playing audio in the background. This static ref
        // lets a new Activity instance stop an orphaned player from a previous
        // instance, preventing two streams from playing simultaneously.
        @Volatile
        private var staticNativeRadioPlayer: ExoPlayer? = null

        /**
         * Nuclear stop for any TapRadio playback that may be alive in this process.
         *
         * This covers every known resurrection path observed in the field:
         *   1. The static ExoPlayer slot (orphaned from a prior Activity instance).
         *   2. The active Activity instance's own ExoPlayer + metadata fields +
         *      progress ticker + audio focus.
         *   3. The radio.html / podcasts.html / spotify.html WebView pages that
         *      can hold a live <audio> element or pending JS timers even after
         *      the native ExoPlayer is gone — we navigate those to about:blank
         *      so the DOM is torn down.
         *   4. The persisted `BrowserPrefs.last_url` + `BrowserPrefs.webview_state`
         *      that `tryRestoreSession()` reads on cold start. If those still
         *      point at a radio.html?playUrl=… URL, the next launch auto-plays
         *      the saved stream — which is the bug the user has complained
         *      about repeatedly. We scrub those back to the default dashboard.
         *   5. The `visionclaw_prefs.tapradio_now_playing_*` HUD state.
         *
         * Safe to call from any thread. All instance-scoped work is hopped
         * to the Activity's UI thread internally.
         *
         * @param context any Context in this process — used to reach
         *   SharedPreferences when no active Activity instance is alive.
         *   Pass `null` to skip prefs sanitization (e.g. from the tapbrowser
         *   Activity itself during its own onCreate, where the caller will
         *   load a fresh URL anyway).
         */
        @JvmStatic
        @JvmOverloads
        fun stopOrphanedNativeRadioPlayer(context: android.content.Context? = null) {
            // 1. Stop the orphaned static ExoPlayer (may be from a dead Activity).
            try {
                staticNativeRadioPlayer?.stop()
                staticNativeRadioPlayer?.release()
            } catch (_: Exception) {}
            staticNativeRadioPlayer = null

            // 2. Stop the live Activity instance's player + clear its metadata +
            //    kill any radio WebView pages (HTML5 <audio> / pending JS timers).
            val activity = activeInstanceRef?.get()
            if (activity != null) {
                try {
                    activity.runOnUiThread {
                        try {
                            val local = activity.nativeRadioPlayer
                            try {
                                local?.stop()
                                local?.release()
                            } catch (_: Exception) {}
                            activity.nativeRadioPlayer = null
                            activity.nativeRadioUrl = null
                            activity.nativeRadioStationName = null
                            activity.nativeRadioGenre = null
                            activity.nativeRadioKind = null
                            activity.nativeRadioError = null
                            activity.nativeRadioPreparing = false
                            activity.nativeRadioBuffering = false
                            try {
                                activity.uiHandler.removeCallbacks(activity.nativeRadioProgressTicker)
                            } catch (_: Exception) {}
                            try {
                                activity.audioManager?.abandonAudioFocus(activity.nativeRadioFocusListener)
                            } catch (_: Exception) {}
                            // Tear down the radio.html / podcasts.html / spotify.html
                            // DOM in every WebView tab so no stray <audio> element
                            // and no pending setTimeout can resurrect playback.
                            if (activity::dualWebViewGroup.isInitialized) {
                                activity.dualWebViewGroup.getAllWebViews().forEach { wv ->
                                    val u = wv.url.orEmpty().lowercase(Locale.US)
                                    if (u.contains("radio.html") ||
                                        u.contains("podcasts.html") ||
                                        u.contains("spotify.html")) {
                                        try { wv.stopLoading() } catch (_: Exception) {}
                                        try {
                                            wv.evaluateJavascript(
                                                "try{document.querySelectorAll('audio,video').forEach(function(e){try{e.pause();e.muted=true;e.src='';e.removeAttribute('src');e.load();}catch(_){}});}catch(_){}",
                                                null
                                            )
                                        } catch (_: Exception) {}
                                        try { wv.loadUrl("about:blank") } catch (_: Exception) {}
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }

            // 3. Sanitize the persisted URL / WebView state + HUD prefs so a
            //    subsequent cold start can't auto-resume the dead stream.
            val ctx: android.content.Context? = context ?: activity
            if (ctx != null) {
                try {
                    val browserPrefs = ctx.getSharedPreferences(
                        Constants.BROWSER_PREFS_NAME, android.content.Context.MODE_PRIVATE
                    )
                    val savedUrl = browserPrefs.getString(Constants.KEY_LAST_URL, null)
                    if (savedUrl != null && isRadioAutoplayUrl(savedUrl)) {
                        // Replace with the default dashboard AND wipe the
                        // Parcelable webview_state bundle — otherwise restoreState
                        // will replay the old navigation history including the
                        // auto-play URL.
                        browserPrefs.edit()
                            .putString(Constants.KEY_LAST_URL, Constants.DEFAULT_URL)
                            .remove(Constants.KEY_WEBVIEW_STATE)
                            .commit()
                    }
                } catch (_: Exception) {}
                // Also scrub the per-window state ("saved_windows_state" in
                // TapLinkPrefs). DualWebViewGroup writes a JSON document with
                // each window's url + base64 WebView bundle; if any window
                // still points at a radio-autoplay URL, purge the whole
                // document so restoreState can't replay it.
                try {
                    val windowPrefs = ctx.getSharedPreferences(
                        Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE
                    )
                    val saved = windowPrefs.getString("saved_windows_state", null)
                    if (saved != null && isRadioAutoplayUrl(saved)) {
                        windowPrefs.edit()
                            .remove("saved_windows_state")
                            .commit()
                    }
                } catch (_: Exception) {}
                try {
                    ctx.getSharedPreferences("visionclaw_prefs", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("tapradio_now_playing_active", false)
                        .putLong("tapradio_now_playing_updated_at", System.currentTimeMillis())
                        .remove("tapradio_now_playing_name")
                        .remove("tapradio_now_playing_genre")
                        .remove("tapradio_now_playing_kind")
                        .remove("tapradio_now_playing_url")
                        .remove("tapradio_now_playing_position_ms")
                        .remove("tapradio_now_playing_duration_ms")
                        .remove("tapradio_now_playing_error")
                        .commit()
                } catch (_: Exception) {}
            }
        }

        /**
         * True when the URL is a TapRadio auto-play URL whose mere presence
         * in `BrowserPrefs.last_url` would cause `tryRestoreSession()` to
         * restart playback on next launch.
         *
         * Also used by `DualWebViewGroup.restoreState()` to skip per-window
         * URLs that would otherwise auto-restart playback through the
         * `saved_windows_state` path.
         */
        @JvmStatic
        internal fun isRadioAutoplayUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            val isRadioPage = lower.contains("radio.html") ||
                lower.contains("podcasts.html") ||
                lower.contains("spotify.html")
            if (!isRadioPage) return false
            return lower.contains("playurl=") ||
                lower.contains("autoplay=1") ||
                lower.contains("spotifyqueue=")
        }

        @JvmStatic
        fun prepareForIncomingYouTubeAutoplay() {
            activeInstanceRef?.get()?.let { activity ->
                activity.runOnUiThread {
                    activity.prepareForIncomingYouTubeAutoplayInternal()
                }
            }
        }
    }

    fun updateCursorSensitivity(progress: Int) {
        cursorSensitivity = progress
        // Map 0-100 to 0.0f - 0.9f gain. 50 -> 0.45f
        cursorGain = 0.9f * (progress / 100f)
    }

    private val H2V_GAIN = 1.0f // how strongly horizontal motion affects vertical scroll
    private val X_INVERT = -1.0f // 1 = left -> up (what you want). Use -1 to flip.
    private val Y_INVERT = -1.0f // 1 = drag up -> up. Use -1 to flip if needed.
    lateinit var dualWebViewGroup: DualWebViewGroup
    private lateinit var webView: WebView
    private lateinit var mainContainer: FrameLayout
    private lateinit var gestureDetector: GestureDetector
    private lateinit var templeDoubleTapDetector: GestureDetector
    private var pendingMaskSingleTapRunnable: Runnable? = null

    private fun cancelPendingMaskSingleTap() {
        pendingMaskSingleTapRunnable?.let { uiHandler.removeCallbacks(it) }
        pendingMaskSingleTapRunnable = null
    }

    private fun scheduleMaskSingleTap(deviceDescription: String) {
        cancelPendingMaskSingleTap()
        val runnable = Runnable {
            pendingMaskSingleTapRunnable = null
            if (::dualWebViewGroup.isInitialized && dualWebViewGroup.isScreenMasked()) {
                DebugLog.d("MaskGesture", "single-tap → play/pause $deviceDescription")
                runCatching { dualWebViewGroup.onMaskSingleTapPlayPause() }
            }
        }
        pendingMaskSingleTapRunnable = runnable
        val delayMs = android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 40L
        uiHandler.postDelayed(runnable, delayMs)
    }

    /**
     * Dim-mode gesture detector. Owns single-tap (play/pause) and
     * double-tap (exit dim mode) for the entire duration the mask
     * overlay is up. Horizontal flings are consumed but intentionally
     * ignored so arm swipes cannot misfire as media skip commands.
     * Lazy so we don't construct it before MainActivity.onCreate runs
     * (it captures `this` as the detector context).
     *
     * Why a separate detector instead of reusing the main one:
     *   • The main `gestureDetector` has onDoubleTap wired to
     *     return-to-TapClaw / browser-back navigation. Reusing it
     *     would mean double-tap in dim mode either fires that
     *     navigation OR our exit, depending on order.
     *   • Single-tap on the main gestureDetector is the click
     *     primitive for everything in normal browsing — page taps,
     *     button activation, link follow. Repurposing it for
     *     play/pause would require context-conditional behavior
     *     scattered across many call sites.
     *   • A dedicated detector that only fires while masked is
     *     simpler to reason about and maintain.
     */
    private val maskedGestureDetector: GestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                return ::dualWebViewGroup.isInitialized && dualWebViewGroup.isScreenMasked()
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!::dualWebViewGroup.isInitialized || !dualWebViewGroup.isScreenMasked()) {
                    return false
                }
                // Filter out left-arm (volume pad) taps — only the right-arm
                // temple trackpad should drive play/pause in dim mode. The
                // user reported swipes were correctly right-arm-only after
                // the earlier filter on onFling, but taps were still firing
                // from EITHER arm. Same device-name allow-list as the fling
                // path for behavioural consistency: cyttsp6_mt = left/volume,
                // cyttsp5_mt = right/temple.
                if (isIgnoredMaskInputDevice(e)) {
                    DebugLog.d(
                        "MaskGesture",
                        "single-tap IGNORED — left-arm device ${describeDevice(e)}"
                    )
                    return false
                }
                scheduleMaskSingleTap(describeDevice(e))
                return true
            }

            /**
             * Centralised device-name allow-list check for dim-mode gestures.
             * Returns true when the event came from a device we want to
             * silence — currently only the X3 Pro's left-arm volume pad
             * (cyttsp6_mt). Used by both onSingleTapUp and onFling so taps
             * and swipes share the same arm-mapping policy.
             */
            private fun isIgnoredMaskInputDevice(e: MotionEvent): Boolean {
                val devName = try {
                    android.view.InputDevice.getDevice(e.deviceId)?.name.orEmpty()
                } catch (_: Exception) { "" }
                return devName.isNotEmpty() && devName in ignoreFlingsFromDeviceNames
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (::dualWebViewGroup.isInitialized && dualWebViewGroup.isScreenMasked()) {
                    cancelPendingMaskSingleTap()
                    DebugLog.d("MaskGesture", "double-tap → exit dim mode ${describeDevice(e)}")
                    runCatching { dualWebViewGroup.unmaskScreen() }
                    setUnipanelHudVisible(true)
                    return true
                }
                return false
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                if (!::dualWebViewGroup.isInitialized || !dualWebViewGroup.isScreenMasked()) {
                    return false
                }
                cancelPendingMaskSingleTap()
                DebugLog.d(
                    "MaskGesture",
                    "fling ignored; swipe-to-skip is disabled ${describeDevice(e2)}"
                )
                return true
            }
        })
    }

    /**
     * Names of input devices whose flings must NOT trigger dim-mode
     * track-skip. Filtered by NAME (not InputDevice.id) because IDs
     * can shuffle after reboot or USB reconnect; names are stable
     * hardware identifiers exposed by the kernel input subsystem.
     *
     * On the RayNeo X3 Pro:
     *   - cyttsp5_mt = RIGHT-arm temple trackpad (the one we WANT
     *                   for swipe-to-skip)
     *   - cyttsp6_mt = LEFT-arm pad (volume control — swiping it
     *                   for volume was being mis-interpreted as a
     *                   track-skip swipe before this filter)
     *
     * If you add a new compatible glasses model later, drop its
     * volume-pad device name here too. Anything not in this set
     * passes through to the swipe-direction logic.
     */
    private val ignoreFlingsFromDeviceNames: Set<String> = setOf(
        "cyttsp6_mt"
    )

    /**
     * Format an InputDevice fingerprint for logcat. Includes the id,
     * source-class bits, and the device's display name when Android
     * exposes one (cyttsp5_mt / cyttsp6_mt for the X3 Pro temple +
     * left-arm pads, virtual / null for synthesized events). Used by
     * the dim-mode gesture detector so we can distinguish left-arm
     * (volume) from right-arm (temple) swipes without guessing.
     */
    private fun describeDevice(e: MotionEvent): String {
        val id = e.deviceId
        val src = e.source
        val name = try {
            android.view.InputDevice.getDevice(id)?.name ?: "<unknown>"
        } catch (_: Exception) { "<unknown>" }
        return "[dev=$id name=\"$name\" src=0x${java.lang.Integer.toHexString(src)}]"
    }

    private var isSimulatingTouchEvent = false
    private var isCursorVisible = true
    private var isMouseTapMode = false
    private var lastMouseRawX = Float.NaN
    private var lastMouseRawY = Float.NaN
    private var lastMouseMappedX = Float.NaN
    private var lastMouseMappedY = Float.NaN
    private var mouseGestureDownTime = 0L
    private var mouseGestureActive = false
    private var mouseSwipeTracking = false
    private var mouseSwipeStartedOnCustomUi = false
    private var mouseSwipeDownDispatched = false
    private var mouseSwipeStartX = 0f
    private var mouseSwipeStartY = 0f
    private var mouseSwipeLastX = 0f
    private var mouseSwipeLastY = 0f
    private var mouseSwipeDownTime = 0L

    private fun refreshCursor() {
        dualWebViewGroup.updateCursorPosition(lastCursorX, lastCursorY, isCursorVisible)
    }

    private fun refreshCursor(visible: Boolean) {
        isCursorVisible = visible
        refreshCursor()
    }

    private fun centerCursor(visible: Boolean = isCursorVisible) {
        lastCursorX = 320f
        lastCursorY = 240f
        isCursorVisible = visible
        refreshCursor()
    }

    private fun isMousePointerEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return true
        if (event.pointerCount <= 0) return false
        return event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
    }

    private fun toggleMouseTapMode() {
        val enableMouseTapMode = !isMouseTapMode
        isMouseTapMode = enableMouseTapMode

        if (enableMouseTapMode) {
            cancelActiveTouchScrollGesture()
            if (::dualWebViewGroup.isInitialized && dualWebViewGroup.isInScrollMode()) {
                dualWebViewGroup.setScrollMode(false)
            }
            isCursorVisible = false
            cursorJustAppeared = false
            if (::cursorLeftView.isInitialized) {
                cursorLeftView.visibility = View.GONE
            }
            if (::cursorRightView.isInitialized) {
                cursorRightView.visibility = View.GONE
            }
            refreshCursor(false)
            dualWebViewGroup.showToast("Mouse tap mode enabled")
        } else {
            isCursorVisible = true
            refreshCursor(true)
            dualWebViewGroup.showToast("Cursor mode enabled")
        }
    }

    private fun ensureMouseTapModeEnabled() {
        if (isMouseTapMode) return
        toggleMouseTapMode()
    }

    private fun ensureMouseTapModeDisabled() {
        if (!isMouseTapMode) return
        toggleMouseTapMode()
    }

    private fun autoEnterMouseModeForMudraInput(event: MotionEvent) {
        val deviceName = event.device?.name ?: InputDevice.getDevice(event.deviceId)?.name ?: return
        if (!deviceName.contains("Mudra", ignoreCase = true)) return
        ensureMouseTapModeEnabled()
    }
    private var isToggling = false
    private var lastCursorX = 320f
    private var lastCursorY = 240f
    private var isDispatchingTouchEvent = false
    private var isGestureHandled = false
    private var wasTouchOnBookmarks = false

    private val CAMERA_REQUEST_CODE = 1001
    private val CAMERA_PERMISSION_CODE = 100
    private val LOCATION_PERMISSION_REQUEST_CODE = 1002
    private var cameraPermissionGranted = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null
    private val FILE_CHOOSER_REQUEST_CODE = 999 // Any unique code
    private var cameraImageUri: Uri? = null
    private var isCapturing = false // Add this flag to prevent multiple captures

    private var lastClickTime = 0L
    private val MIN_CLICK_INTERVAL = 500L // Minimum time between clicks

    // In MainActivity, add these properties to track cursor state and position
    private var lastKnownCursorX = 320f // Default center position
    private var lastKnownCursorY = 240f // Default center position
    private var lastKnownWebViewX = 0f
    private var lastKnownWebViewY = 0f
    private var cursorJustAppeared = false // Track if cursor just appeared

    private var lastGpsLat: Double? = null
    private var lastGpsLon: Double? = null

    private var currentVelocityX = 0f
    private var currentVelocityY = 0f
    private val movementDecay = 0.9f // Decay factor to slow down gradually
    private val updateInterval = 16L // Update interval in ms for smooth motion
    private val handler = Handler(Looper.getMainLooper())

    private val longPressTimeout = 200L // Milliseconds threshold for tap vs long press

    private lateinit var cursorLeftView: ImageView
    private lateinit var cursorRightView: ImageView

    private var keyboardView: CustomKeyboardView? = null
    private var isKeyboardVisible = false
    private var wasKeyboardVisibleAtDown = false
    private var wasTouchOnKeyboard = false

    private val prefsName = Constants.BROWSER_PREFS_NAME
    private val keyLastUrl = Constants.KEY_LAST_URL
    private var lastUrl: String? = null
    private var isUrlEditing = false
    private var returnToChatOnDoubleTap = false
    private var startupUrlOverride: String? = null
    private var youtubeAutoplayQuery: String? = null
    private var youtubeAutoplayMode: String? = null
    /** User-requested YouTube search queue, one top result per spoken item. */
    private var youtubeAutoplayQueue: List<String> = emptyList()
    private var youtubeAutoplayQueueIndex: Int = 0
    /** Ordered list of video IDs scraped from YouTube search results */
    private var youtubePlaylist: List<String> = emptyList()
    /** Index into youtubePlaylist of the currently-playing video */
    private var youtubePlaylistIndex: Int = 0
    /** Last URL we injected the bootstrap script for (prevents double-injection) */
    private var lastYouTubeInjectionUrl: String? = null
    /** Set true during nuclear WebView clearing so onPageStarted's about:blank
     *  recovery doesn't reload the old YouTube page. */
    @Volatile private var nuclearCleanupInProgress = false
    @Volatile private var webViewsPausedForReturnToChat = false

    // User Agent management
    private var defaultUserAgent: String? = null
    private var customUserAgent: String? = null

    private fun shouldUseDesktopUaForYouTube(url: String?, autoplayMode: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lowerUrl = url.lowercase(Locale.US)
        if (!lowerUrl.contains("youtube.com") && !lowerUrl.contains("youtu.be")) return false
        val mode = autoplayMode?.trim()?.lowercase(Locale.US).orEmpty()
        val isFeedScrapeMode =
            (mode == "history" && lowerUrl.contains("/feed/history") && !lowerUrl.contains("/watch")) ||
            (mode == "subscriptions" && lowerUrl.contains("/feed/subscriptions") && !lowerUrl.contains("/watch"))
        return !isFeedScrapeMode
    }

    private var keyboardListener: DualWebViewGroup.KeyboardListener? = null

    private val PERMISSIONS_REQUEST_CODE = 123
    /**
     * Request code for the in-app "Grant access to device photos" flow
     * triggered from photos_gallery.html via
     * [MediaLibraryBridge.requestMediaPermission]. The RayNeo X3 Pro
     * doesn't expose a Settings UI for runtime permissions, so this is
     * the only way the user can authorize READ_MEDIA_IMAGES /
     * READ_MEDIA_VIDEO on the glasses.
     */
    private val MEDIA_PERMISSIONS_REQUEST_CODE = 124
    private var pendingPermissionRequest: PermissionRequest? = null
    private var qrScanCallbackWebView: WebView? = null
    private var isQrScanInProgress = false
    private var pendingNativeQrStart = false
    private var nativeQrScannerView: DecoratedBarcodeView? = null
    private val defaultQrZoomRatio = 3.0
    private var audioManager: AudioManager? = null

    // ── Media Library bridge ──────────────────────────────────────────────
    // Atomic URL ref is read by the JS bridge on a background thread to
    // decide whether the calling asset page is trusted. Updated from
    // onPageStarted / onPageFinished below (both the MainActivity and
    // DualWebViewGroup WebViewClients keep it in sync as the user navigates).
    private val mediaBridgeUrlRef: AtomicReference<String> = AtomicReference("")
    /**
     * Gemini 3.1 TTS client used by the on-glasses media player to read text
     * files aloud. Reads the API key from the shared "visionclaw_prefs"
     * SharedPreferences (same key the companion app writes), so configuring
     * the key once from the phone is all that's needed.
     */
    private val glassesTtsClient: com.TapLink.app.media.GlassesTtsClient by lazy {
        com.TapLink.app.media.GlassesTtsClient(
            apiKeyProvider = { com.TapLink.app.media.resolveGlassesGeminiKey(this) }
        )
    }
    /**
     * Fish.audio TTS client. Reads the per-call config out of
     * "visionclaw_prefs" lazily on every synth, so flipping engines /
     * picking a new voice in the companion app takes effect on the very
     * next chunk without restarting the bridge or the activity.
     */
    private val glassesFishTtsClient: com.TapLink.app.media.FishTtsClient by lazy {
        com.TapLink.app.media.FishTtsClient(
            configProvider = { com.TapLink.app.media.resolveGlassesFishConfig(this) }
        )
    }
    private val mediaLibraryBridge: MediaLibraryBridge by lazy {
        MediaLibraryBridge(this, mediaBridgeUrlRef, glassesTtsClient, glassesFishTtsClient)
    }
    private val mediaFileInterceptor: MediaFileInterceptor by lazy {
        MediaFileInterceptor(this, mediaLibraryBridge.service)
    }
    private var nativeRadioPlayer: ExoPlayer? = null
    private var nativeRadioUrl: String? = null
    private var nativeRadioStationName: String? = null
    private var nativeRadioGenre: String? = null
    private var nativeRadioKind: String? = null   // "podcast" or "radio" — controls seek bar visibility
    private var nativeRadioPreparing = false
    private var nativeRadioBuffering = false
    private var nativeRadioError: String? = null
    private val nativeRadioProgressTicker =
            object : Runnable {
                override fun run() {
                    if (!shouldKeepNativeRadioProgressTickerRunning()) return
                    applyNativeRadioPlaybackUiState(scheduleDelayedBroadcasts = false)
                    uiHandler.postDelayed(this, 1000L)
                }
            }
    private val nativeRadioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when {
            change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                runOnUiThread { pauseNativeRadioStreamInternal(abandonFocus = false) }
            }
        }
    }
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var fullScreenCustomView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUiVisibility: Int = 0
    private var originalOrientation: Int = 0
    private var wasKeyboardDismissedByEnter = false
    private var suppressWebClickUntil = 0L

    private var preMaskCursorState = false
    private var preMaskCursorX = 0f
    private var preMaskCursorY = 0f
    private var closeChatOnNextPageStart = false
    private var closeChatOnNextPageStartDeadlineMs = 0L

    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingCursorUpdate = false

    private val onBackPressedCallback =
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        fullScreenCustomView != null -> {
                            hideFullScreenCustomView()
                        }
                        isKeyboardVisible || dualWebViewGroup.isUrlEditing() -> {
                            // Hide keyboard and exit URL editing
                            hideCustomKeyboard()
                        }
                        isCursorVisible -> {
                            // Hide cursor
                            toggleCursorVisibility(forceHide = true)
                        }
                        else -> {
                            // Remove the callback and let the system handle back
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            // Re-enable for next time
                            isEnabled = true
                        }
                    }
                }
            }

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var isAnchored = false // Will be loaded from preferences in onCreate
        set(value) {
            field = value
            if (::dualWebViewGroup.isInitialized) {
                dualWebViewGroup.isAnchored = value
            }
        }

    // Smoothing and performance parameters for anchored mode
    private val TRANSLATION_SCALE =
            2000f // Adjusted for better visual stability (approx 36 deg FOV)

    // Dynamic smoothing factors (controlled by user preference)
    // Range: 0 (fastest/least smooth) to 100 (slowest/most smooth)
    private var smoothnessLevel = 40 // Default: fairly smooth
    private var anchorSmoothingFactor = 0.08f // Calculated from smoothnessLevel
    private var velocitySmoothing = 0.15f // Calculated from smoothnessLevel

    // Cursor sensitivity for non-anchored mode
    private var cursorSensitivity = 50 // Default 50 corresponds to 0.45f gain
    private var cursorGain = 0.45f

    // Velocity tracking for double exponential smoothing
    private var smoothedDeltaX = 0f
    private var smoothedDeltaY = 0f
    private var smoothedRollDeg = 0f

    // Frame timing for vsync
    private var lastFrameTime = 0L
    private val MIN_FRAME_INTERVAL_MS = 8L // ~120 FPS max (displays may be 90-120Hz)

    private var sensorEventListener = createSensorEventListener()
    private var shouldResetInitialQuaternion = false
    private var pendingDoubleTapAction = false

    private var ipcLauncher: Launcher? = null
    private var gpsUpdatesRegistered = false
    private val gpsHandler = Handler(Looper.getMainLooper())
    private var gpsStopRunnable: Runnable? = null
    private var lastGpsRequestAt = 0L
    private val GPS_IDLE_TIMEOUT_MS = 60000L

    private val doubleTapLock = Any()
    private var isProcessingDoubleTap = false
    private var lastDoubleTapStartTime = 0L
    private val DOUBLE_TAP_CONFIRMATION_DELAY = 200L

    // Triple tap detection for re-centering in anchored mode
    private var lastTapTime = 0L
    private var firstTapTime = 0L
    private var tapCount = 0
    private val TAP_INTERVAL = 400L // Max time between consecutive taps
    private val TRIPLE_TAP_DURATION = 800L // Max time for entire 3-tap sequence
    private var isTripleTapInProgress = false

    private var settingsMenu: View? = null

    private val gpsResponseListener =
            Launcher.OnResponseListener { response ->
                if (response?.data == null) return@OnResponseListener

                try {
                    val jo = JSONObject(response.data)
                    if (jo.has("mLatitude") && jo.has("mLongitude")) {
                        val mLatitude = jo.getDouble("mLatitude")
                        val mLongitude = jo.getDouble("mLongitude")

                        // Save for page reloads
                        lastGpsLat = mLatitude
                        lastGpsLon = mLongitude

                        // Inject location into WebView on UI thread
                        runOnUiThread { dualWebViewGroup.injectLocation(mLatitude, mLongitude) }
                    }
                } catch (e: Exception) {
                    DebugLog.e("GpsData", "Error processing GPS data: ${e.message}")
                }
            }

    private val cursorToggleLock = Any()
    private var potentialTapEvent: MotionEvent? = null

    private var pendingTouchHandler: Handler? = null
    private var pendingTouchRunnable: Runnable? = null

    // Touch scroll simulation state for mobile mode
    private var isTouchScrollActive = false
    private var touchScrollDownTime = 0L
    private var touchScrollCurrentY = 240f // Start at center
    private var accumulatedScrollY = 0f // Accumulate small scroll deltas

    private fun cancelActiveTouchScrollGesture() {
        pendingTouchRunnable?.let { pendingTouchHandler?.removeCallbacks(it) }
        pendingTouchRunnable = null
        accumulatedScrollY = 0f

        if (!isTouchScrollActive || !::webView.isInitialized) return

        val now = SystemClock.uptimeMillis()
        val cancelEvent =
                MotionEvent.obtain(
                        touchScrollDownTime,
                        now,
                        MotionEvent.ACTION_CANCEL,
                        lastCursorX,
                        touchScrollCurrentY,
                        0
                )
        cancelEvent.source = InputDevice.SOURCE_TOUCHSCREEN
        isSimulatingTouchEvent = true
        try {
            webView.dispatchTouchEvent(cancelEvent)
        } finally {
            isSimulatingTouchEvent = false
        }
        cancelEvent.recycle()
        isTouchScrollActive = false
    }

    private val notificationReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == NotificationService.ACTION_NOTIFICATION_POSTED) {
                        val packageName = intent.getStringExtra(NotificationService.EXTRA_PACKAGE)
                        val title = intent.getStringExtra(NotificationService.EXTRA_TITLE)
                        val text = intent.getStringExtra(NotificationService.EXTRA_TEXT)

                        DebugLog.d(
                                "MainActivity",
                                "Received notification from $packageName: $title - $text"
                        )

                        // Show a custom toast with the notification
                        dualWebViewGroup.showToast("Notification: $title - $text", 3000L)
                    }
                }
            }

    init {
        DebugLog.d("LinkEditing", "MainActivity initialized, isUrlEditing=$isUrlEditing")
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility", "DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        // ── Stop any orphaned radio player from a previous Activity instance ──
        // If the system destroyed the old Activity (common on AR glasses with limited
        // RAM) and onDestroy didn't run or ran too late, the static ref lets us kill
        // the orphaned ExoPlayer before this new instance starts its own stream.
        stopOrphanedNativeRadioPlayer()

        runCatching { com.ffalcon.mercury.android.sdk.MercurySDK.init(application) }
        parseTapClawLaunchIntent(intent)
        // SmartTube-inspired video-quality hint: prime the YouTube
        // "default to HD" cookie before any WebView fires up. With this
        // cookie in place, YouTube serves the high-bitrate variant
        // family (1080p where available) instead of the default 720p
        // mobile stream. Single biggest WebView-side video win.
        runCatching { VideoQualityHints.primeYouTubeHdCookie(this@MainActivity) }
        super.onCreate(savedInstanceState)
        activeInstanceRef = WeakReference(this)
        // Set window background to black immediately
        window.setBackgroundDrawableResource(android.R.color.black)

        // Set initial brightness to 10% to reduce power consumption
        window.attributes = window.attributes.apply { screenBrightness = 0.1f }

        // Force hardware acceleration but with black background
        window.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // Prevent any drawing until we're ready
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(false)
        }

        // Set content view with black background
        setContentView(R.layout.tapbrowser_activity_main)

        findViewById<View>(android.R.id.content).setBackgroundColor(Color.BLACK)

        mainContainer = findViewById(R.id.mainContainer)

        // Phase 2 Step 2c.1: HUD clock ticker. unipanelHudTime is a
        // TextView at the top-center of unipanelOverlay; this runnable
        // refreshes it every 30s with the current HH:MM. First real
        // piece of chat-HUD content rendered over the browser. Future
        // steps add AI status, battery, calendar, etc. to the same
        // strip and migrate to the live MainViewModel-driven values.
        startUnipanelHudClockTicker()

        // Phase 2 Step 2c.3: subscribe to ChatCardBridge so the mini
        // chat-card stack reflects the visionclaw conversation in
        // real time. The bridge fires the listener immediately with
        // its current snapshot, then again on every publish, so the
        // stack renders correctly even when this Activity is the
        // user's entry point (cold launch with prior chat history
        // already loaded into MainViewModel).
        startUnipanelMiniCardObserver()

        // Phase 2 Step 2c.4: subscribe to CameraStateBridge so the
        // top-right CAM chip shows/hides in sync with visionclaw's
        // voiceAssistantActive LiveData. Read-only status indicator
        // — the live camera frames stay in the visionclaw chat panel
        // for now; this just tells the user "yes, Gemini is watching".
        startUnipanelCameraChipObserver()

        // Unipanel v2 Phase 3 — bind to the GeminiVoiceService so
        // tapbrowser can drive voice activation directly without
        // launching visionclaw MainActivity. Pure plumbing today; the
        // user-visible voice gesture wires in Phase 6. See
        // tasks/UNIPANEL_V2_SERVICE_REFACTOR.md for the phase plan.
        startVoiceServiceBinding()

        // Unipanel v2 Phase 6-essentials — wire the voice pill in the
        // HUD strip. Click → toggle voice via the binder; subscribed
        // to HudStateBridge for visual feedback (color by VoicePhase).
        // Pipeline still Phase 4; today's tap just bounces Service
        // state through the bridge so Mars can verify the bind path.
        startUnipanelVoicePill()
        startBrowserCommandObserver()
        // Phase 4d (Mars revision) — passive red-dot indicator.
        // Lights up when CameraX or browser_vision is active. The
        // manual CAM toggle button is gone; vision is voice-triggered.
        startUnipanelVisionDotObserver()
        // Phase 4h — AI status badge ("G") tints from HudStateBridge
        // connection status so it tracks the Gemini Live state.
        startUnipanelHudAiBadgeObserver()
        // Hermes HUD bridge: live events/tasks/news, AQI, heartbeat
        // ticker, and the OpenClaw/Hermes gateway badges all share
        // the same process-local state as the Service voice path.
        startUnipanelHudStateObserver()
        // Phase 4g — configure the burgundy preview frame's
        // PreviewView and seed its SurfaceProvider into the Service.
        startUnipanelCameraPreviewBinding()

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        // Add this to disable default keyboard
        window.setFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )

        // After basic window setup but before using any settings

        supportActionBar?.hide()

        keyboardListener =
                object : DualWebViewGroup.KeyboardListener {
                    override fun onShowKeyboard() {
                        showCustomKeyboard()
                    }

                    override fun onHideKeyboard() {
                        hideCustomKeyboard()
                    }
                }

        // Initialize DualWebViewGroup first
        dualWebViewGroup = findViewById(R.id.dualWebViewGroup)
        dualWebViewGroup.listener = this
        dualWebViewGroup.navigationListener = this
        dualWebViewGroup.maskToggleListener = this
        dualWebViewGroup.windowCallback = this
        dualWebViewGroup.restoreState()

        // Load saved anchored mode state
        isAnchored =
                getSharedPreferences(prefsName, MODE_PRIVATE)
                        .getBoolean("isAnchored", false) // Default to false on first run

        dualWebViewGroup.isAnchored = isAnchored

        if (isAnchored) {
            dualWebViewGroup.startAnchoring()
        } else {
            dualWebViewGroup.stopAnchoring()
        }

        // Load cursor sensitivity
        cursorSensitivity =
                getSharedPreferences(prefsName, MODE_PRIVATE).getInt("cursorSensitivity", 50)
        updateCursorSensitivity(cursorSensitivity)

        // Initialize GestureDetector
        gestureDetector =
                GestureDetector(
                        this,
                        object : SimpleOnGestureListener() {
                            private var isProcessingTap = false
                            private var totalScrollDistance = 0f
                            private var doubleTapRunnable: Runnable? = null

                            override fun onDown(e: MotionEvent): Boolean {
                                totalScrollDistance = 0f
                                DebugLog.d(
                                        "GestureInput",
                                        """
            Gesture Down:
            Source: ${e.source}
            Device: ${e.device?.name}
            ButtonState: ${e.buttonState}
            Pressure: ${e.pressure}
            Size: ${e.size}
            EventTime: ${e.eventTime}
            DownTime: ${e.downTime}
            Duration: ${e.eventTime - e.downTime}ms
        """.trimIndent()
                                )

                                // Store the down event for potential tap
                                potentialTapEvent = MotionEvent.obtain(e)

                                // Triple tap detection for screen re-centering in anchored mode
                                val currentTime = e.eventTime
                                if (currentTime - lastTapTime > TAP_INTERVAL) {
                                    DebugLog.d("TripleTapDebug", "Starting new tap sequence")
                                    tapCount = 1
                                    firstTapTime = currentTime
                                    isTripleTapInProgress = false
                                } else {
                                    tapCount++
                                    DebugLog.d(
                                            "TripleTapDebug",
                                            "Tap count increased to: $tapCount"
                                    )
                                }
                                lastTapTime = currentTime

                                // Check for triple tap
                                if (tapCount == 3 &&
                                                (currentTime - firstTapTime) <= TRIPLE_TAP_DURATION
                                ) {
                                    DebugLog.d(
                                            "TripleTapDebug",
                                            "Triple tap detected! Time from first tap: ${currentTime - firstTapTime}ms"
                                    )
                                    // Explicitly cancel the specific double tap runnable
                                    doubleTapRunnable?.let { handler.removeCallbacks(it) }
                                    doubleTapRunnable = null

                                    handler.removeCallbacksAndMessages(null)
                                    synchronized(doubleTapLock) { pendingDoubleTapAction = false }
                                    isTripleTapInProgress = true
                                    tapCount = 0

                                    if (isAnchored) {
                                        // Reset translations to center the view
                                        shouldResetInitialQuaternion = true
                                        dualWebViewGroup.updateLeftEyePosition(
                                                0f,
                                                0f,
                                                0f
                                        ) // Reset translations and rotation
                                        dualWebViewGroup.showToast("Screen Re-centered")
                                    } else {
                                        // Non-anchored triple tap: Toggle Scroll Mode
                                        if (dualWebViewGroup.isInScrollMode()) {
                                            toggleCursorVisibility(forceShow = true)
                                            dualWebViewGroup.showToast("Cursor mode activated")
                                        } else {
                                            toggleCursorVisibility(forceHide = true)
                                            dualWebViewGroup.showToast(
                                                    "Scroll mode activated, triple tap again to leave"
                                            )
                                        }
                                    }
                                    return true
                                }

                                return true
                            }

                            override fun onLongPress(e: MotionEvent) {
                                tapCount = 0
                                DebugLog.d(
                                        "RingInput",
                                        """
            Long Press:
            Source: ${e.source}
            Device: ${e.device?.name}
            ButtonState: ${e.buttonState}
            Pressure: ${e.pressure}
            Duration: ${e.eventTime - e.downTime}ms
        """.trimIndent()
                                )
                            }

                            override fun onScroll(
                                    e1: MotionEvent?,
                                    e2: MotionEvent,
                                    distanceX: Float,
                                    distanceY: Float
                            ): Boolean {
                                tapCount = 0 // Reset tap count on scroll to prevent accidental
                                // triple-tap detection
                                totalScrollDistance +=
                                        kotlin.math.sqrt(
                                                distanceX * distanceX + distanceY * distanceY
                                        )

                                if (isAnchored && isCursorVisible) {
                                    // In anchored cursor mode, cursor movement should not become
                                    // a synthetic touch swipe on the page.
                                    cancelActiveTouchScrollGesture()
                                    return true
                                }

                                // When ANCHORED or SCROLL MODE: both X and Y move the page
                                // vertically
                                if ((!isCursorVisible &&
                                                (isAnchored || dualWebViewGroup.isInScrollMode())) &&
                                                !isKeyboardVisible &&
                                                !dualWebViewGroup.isScreenMasked()
                                ) {
                                    // Map horizontal to vertical: LEFT -> UP, RIGHT -> DOWN
                                    // GestureDetector gives incremental deltas since last callback
                                    val horizontalAsVertical = (-distanceX) * X_INVERT * H2V_GAIN
                                    val verticalFromDrag = distanceY * Y_INVERT

                                    val scale = dualWebViewGroup.uiScale
                                    val verticalDelta =
                                            (horizontalAsVertical + verticalFromDrag) / scale

                                    if (kotlin.math.abs(verticalDelta) >= 1f) {
                                        if (dualWebViewGroup.isDesktopMode()) {
                                            // Desktop mode: Use mouse scroll wheel simulation
                                            val pointerCoords = MotionEvent.PointerCoords()
                                            pointerCoords.x = 320f
                                            pointerCoords.y = 240f
                                            pointerCoords.setAxisValue(
                                                    MotionEvent.AXIS_VSCROLL,
                                                    verticalDelta / 30f
                                            )

                                            val pointerProperties = MotionEvent.PointerProperties()
                                            pointerProperties.id = 0
                                            pointerProperties.toolType = MotionEvent.TOOL_TYPE_MOUSE

                                            val event =
                                                    MotionEvent.obtain(
                                                            SystemClock.uptimeMillis(),
                                                            SystemClock.uptimeMillis(),
                                                            MotionEvent.ACTION_SCROLL,
                                                            1,
                                                            arrayOf(pointerProperties),
                                                            arrayOf(pointerCoords),
                                                            0,
                                                            0,
                                                            1.0f,
                                                            1.0f,
                                                            0,
                                                            0,
                                                            InputDevice.SOURCE_MOUSE,
                                                            0
                                                    )

                                            webView.dispatchGenericMotionEvent(event)
                                            event.recycle()
                                        } else {
                                            // Mobile mode: Use touch swipe simulation
                                            val now = SystemClock.uptimeMillis()

                                            // Accumulate scroll delta for smoother swiping
                                            accumulatedScrollY += verticalDelta

                                            if (!isTouchScrollActive) {
                                                // Prevent accidental clicks by requiring a minimum
                                                // movement threshold
                                                // before starting a swipe gesture. 15px is
                                                // typically safe for touch slop.
                                                if (kotlin.math.abs(accumulatedScrollY) < 15f) {
                                                    return true
                                                }

                                                // Start a new touch gesture at the last known
                                                // cursor position
                                                isTouchScrollActive = true
                                                touchScrollDownTime = now
                                                // Use last known cursor X/Y to ensure we scroll the
                                                // correct element
                                                touchScrollCurrentY = lastCursorY
                                                // accumulatedScrollY is valid and > threshold,
                                                // proceed using it

                                                val downEvent =
                                                        MotionEvent.obtain(
                                                                touchScrollDownTime,
                                                                now,
                                                                MotionEvent.ACTION_DOWN,
                                                                lastCursorX,
                                                                touchScrollCurrentY,
                                                                0
                                                        )
                                                downEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                                                isSimulatingTouchEvent = true
                                                try {
                                                    webView.dispatchTouchEvent(downEvent)
                                                } finally {
                                                    isSimulatingTouchEvent = false
                                                }
                                                downEvent.recycle()
                                            }

                                            // Apply scroll delta (Positive delta = Move DOWN = Drag
                                            // DOWN)
                                            // User wants: Swipe Forward (Up/Neg) -> Go Down (Scroll
                                            // Down)
                                            // Scroll Down -> Drag UP (Decrease Y)
                                            // So if Delta < 0, we want CurrentY to DECREASE.
                                            // So we ADD delta.
                                            var candidateY =
                                                    touchScrollCurrentY + accumulatedScrollY

                                            // Check bounds and loop gesture if needed to allow
                                            // continuous scrolling
                                            if (candidateY < 10f || candidateY > 470f) {
                                                // End current gesture with CANCEL to prevent acting
                                                // as a click at the edge
                                                val cancelEvent =
                                                        MotionEvent.obtain(
                                                                touchScrollDownTime,
                                                                now,
                                                                MotionEvent.ACTION_CANCEL,
                                                                lastCursorX,
                                                                touchScrollCurrentY,
                                                                0
                                                        )
                                                cancelEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                                                isSimulatingTouchEvent = true
                                                try {
                                                    webView.dispatchTouchEvent(cancelEvent)
                                                } finally {
                                                    isSimulatingTouchEvent = false
                                                }
                                                cancelEvent.recycle()

                                                // Reset to center to regain runway
                                                touchScrollCurrentY = 240f
                                                touchScrollDownTime = now

                                                // Start new gesture
                                                val downEvent =
                                                        MotionEvent.obtain(
                                                                touchScrollDownTime,
                                                                now,
                                                                MotionEvent.ACTION_DOWN,
                                                                lastCursorX,
                                                                touchScrollCurrentY,
                                                                0
                                                        )
                                                downEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                                                isSimulatingTouchEvent = true
                                                try {
                                                    webView.dispatchTouchEvent(downEvent)
                                                } finally {
                                                    isSimulatingTouchEvent = false
                                                }
                                                downEvent.recycle()

                                                // Re-calculate candidate
                                                candidateY =
                                                        touchScrollCurrentY + accumulatedScrollY
                                            }

                                            touchScrollCurrentY = candidateY.coerceIn(0f, 480f)
                                            accumulatedScrollY = 0f

                                            val moveEvent =
                                                    MotionEvent.obtain(
                                                            touchScrollDownTime,
                                                            now,
                                                            MotionEvent.ACTION_MOVE,
                                                            lastCursorX, // Keep X locked to gaze or
                                                            // original? Using gaze for
                                                            // now as per previous
                                                            touchScrollCurrentY,
                                                            0
                                                    )
                                            moveEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                                            isSimulatingTouchEvent = true
                                            try {
                                                webView.dispatchTouchEvent(moveEvent)
                                            } finally {
                                                isSimulatingTouchEvent = false
                                            }
                                            moveEvent.recycle()

                                            // Schedule ACTION_CANCEL to complete the gesture if
                                            // scrolling stops. Using CANCEL prevents "clicks" on
                                            // lift.
                                            if (pendingTouchHandler == null) {
                                                pendingTouchHandler =
                                                        Handler(Looper.getMainLooper())
                                            }
                                            pendingTouchRunnable?.let {
                                                pendingTouchHandler?.removeCallbacks(it)
                                            }

                                            pendingTouchRunnable = Runnable {
                                                if (isTouchScrollActive) {
                                                    val upTime = SystemClock.uptimeMillis()
                                                    val cancelEvent =
                                                            MotionEvent.obtain(
                                                                    touchScrollDownTime,
                                                                    upTime,
                                                                    MotionEvent.ACTION_CANCEL,
                                                                    lastCursorX,
                                                                    touchScrollCurrentY,
                                                                    0
                                                            )
                                                    cancelEvent.source =
                                                            InputDevice.SOURCE_TOUCHSCREEN
                                                    isSimulatingTouchEvent = true
                                                    try {
                                                        webView.dispatchTouchEvent(cancelEvent)
                                                    } finally {
                                                        isSimulatingTouchEvent = false
                                                    }
                                                    cancelEvent.recycle()
                                                    isTouchScrollActive = false
                                                    DebugLog.d(
                                                            "ScrollMode",
                                                            "Touch scroll gesture cancelled via timeout"
                                                    )
                                                }
                                            }
                                            pendingTouchHandler?.postDelayed(
                                                    pendingTouchRunnable!!,
                                                    150
                                            )
                                        }
                                    }
                                    return true
                                }

                                // Not anchored: keep your existing cursor-follow logic
                                // val cursorGain = 0.45f // using class member cursorGain instead
                                val dx = -distanceX * cursorGain
                                val dy = -distanceY * cursorGain
                                if (!isAnchored && !dualWebViewGroup.isInScrollMode()) {
                                    // Clamp to single eye dimensions (640x480), not full dual
                                    // display width
                                    val maxW = 640f
                                    val maxH = 480f
                                    lastCursorX = (lastCursorX + dx).coerceIn(0f, maxW)
                                    lastCursorY = (lastCursorY + dy).coerceIn(0f, maxH)

                                    val loc = IntArray(2)
                                    webView.getLocationOnScreen(loc)
                                    lastKnownWebViewX = lastCursorX - loc[0]
                                    lastKnownWebViewY = lastCursorY - loc[1]
                                    refreshCursor(true)
                                    DebugLog.d("GestureInput", "Trapped!")
                                    return true
                                }

                                return false
                            }
                            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                                DebugLog.d("RingInput", "Single Tap from device: ${e.device?.name}")

                                if (totalScrollDistance > 10f) {
                                    DebugLog.d(
                                            "GestureInput",
                                            "Tap ignored due to swipe distance: $totalScrollDistance"
                                    )
                                    return false
                                }

                                handleUserInteraction()

                                // If the touch interaction started on the bookmarks view, consume
                                // the tap here
                                // to prevent it from propagating to the WebView (even if bookmarks
                                // closed in the meantime)
                                if (wasTouchOnBookmarks) {
                                    return true
                                }

                                // Don't handle single taps that are part of a triple tap sequence
                                if (isTripleTapInProgress) {
                                    isTripleTapInProgress = false
                                    return true
                                }

                                // Phase 4k.10 — RESTORE the browser on a single tap whenever
                                // it's collapsed to the HUD-only focus view. This must run
                                // BEFORE the cursor-visibility branch below: the normal
                                // dispatchTouchEventAtCursor() path (which holds the overlay
                                // hit-test + the browserPanelHidden -> showBrowserPanel()
                                // restore) was ONLY being called when isCursorVisible was true.
                                // Once the cursor's idle timeout hid it, a tap merely re-showed
                                // the cursor and the browser never came back — that's the
                                // "tap-to-toggle stopped working" regression. Driving
                                // dispatchTouchEventAtCursor() directly here reuses the exact
                                // same routing (an interactive HUD widget such as the voice orb
                                // still wins; otherwise the empty-space tap restores the
                                // browser) regardless of whether the cursor happens to be
                                // visible.
                                if (browserPanelHidden &&
                                    ::dualWebViewGroup.isInitialized &&
                                    !dualWebViewGroup.isScreenMasked()
                                ) {
                                    if (!cursorJustAppeared && !isSimulatingTouchEvent) {
                                        dispatchTouchEventAtCursor()
                                    }
                                    return true
                                }

                                // When masked, don't consume the tap - let it reach the unmask
                                // button and media controls
                                // The mask overlay itself will block touches to web content
                                if (dualWebViewGroup.isScreenMasked()) {
                                    dispatchTouchEventAtCursor()
                                    return true
                                }

                                if (isProcessingTap) return true

                                isProcessingTap = true
                                Handler(Looper.getMainLooper())
                                        .postDelayed({ isProcessingTap = false }, 300)

                                // Bookmark taps are now handled exclusively by
                                // DualWebViewGroup.onTouchEvent
                                // to prevent double-dispatch issues with actions like "Set Home"

                                when {
                                    isToggling && cursorJustAppeared -> {
                                        DebugLog.d(
                                                "TouchDebug",
                                                "Ignoring tap during cursor appearance"
                                        )
                                        return true
                                    }
                                    isCursorVisible -> {
                                        // Check if this is a long press
                                        if (e.eventTime - e.downTime > longPressTimeout) {
                                            // ignoring input interaction")
                                            return true
                                        }

                                        // Handle regular clicks when cursor is visible
                                        if (!cursorJustAppeared && !isSimulatingTouchEvent) {
                                            val UILocation = IntArray(2)
                                            dualWebViewGroup.leftEyeUIContainer.getLocationOnScreen(
                                                    UILocation
                                            )

                                            // Dispatch the touch event at the current cursor
                                            // position
                                            dispatchTouchEventAtCursor()
                                        }
                                    }
                                    else -> {
                                        // In scroll mode (cursor hidden), let taps pass through to
                                        // the WebView
                                        // User can exit scroll mode via the dedicated unhide button
                                        if (dualWebViewGroup.isInScrollMode()) {
                                            return false // Don't consume - let tap go to WebView
                                        }
                                        // ROOT-CAUSE FIX (avatar tap often fails to activate
                                        // Gemini): when the cursor has idle-hidden, a single tap
                                        // on an interactive HUD widget — the voice orb / clock
                                        // strip / top row, all wired to toggle the voice session
                                        // — was being LOST. The tap only re-showed the cursor and
                                        // never dispatched, so the user had to tap twice. If the
                                        // resting cursor is over an overlay widget, fire it now;
                                        // otherwise fall back to just waking the cursor as before.
                                        val pt =
                                            runCatching { currentCursorInteractionPoint() }
                                                .getOrNull()
                                        if (pt != null &&
                                            dispatchUnipanelOverlayTouchIfHit(pt.first, pt.second)
                                        ) {
                                            if (!isCursorVisible) {
                                                isSimulatingTouchEvent = true
                                                toggleCursorVisibility()
                                            }
                                            return true
                                        }
                                        isSimulatingTouchEvent = true
                                        toggleCursorVisibility()
                                    }
                                }
                                return true
                            }

                            override fun onDoubleTap(e: MotionEvent): Boolean {
                                // Prevent double tap back navigation if keyboard is visible
                                if (isKeyboardVisible) {
                                    DebugLog.d(
                                            "DoubleTapDebug",
                                            "Double tap ignored because keyboard is visible"
                                    )
                                    return true // Consume the event so it doesn't propagate
                                }

                                // If this is part of a triple tap sequence (which just toggled
                                // mode), ignore double tap
                                if (isTripleTapInProgress) {
                                    DebugLog.d(
                                            "DoubleTapDebug",
                                            "Double tap ignored - part of triple tap sequence"
                                    )
                                    return true
                                }

                                // ROOT-CAUSE FIX (double-tap doesn't cancel Gemini): cancel
                                // an active voice session IMMEDIATELY, before the scroll-mode
                                // guard below. During a live session the cursor frequently
                                // auto-hides into scroll mode, and the `isInScrollMode` early
                                // return was swallowing the double-tap so the cancel never
                                // ran. A cancel gesture must work regardless of cursor/scroll
                                // state, so short-circuit here.
                                if (isGeminiExitSurfaceActive()) {
                                    DebugLog.d(
                                        "DoubleTapDebug",
                                        "Main-pad double-tap — cancelling active Gemini session"
                                    )
                                    exitGeminiFully()
                                    return true
                                }

                                val isInScrollMode = dualWebViewGroup.isInScrollMode()
                                DebugLog.d(
                                        "DoubleTapDebug",
                                        """onDoubleTap called. isProcessingDoubleTap: $isProcessingDoubleTap, isInScrollMode: $isInScrollMode"""
                                )

                                if (isInScrollMode) {
                                    DebugLog.d(
                                            "DoubleTapDebug",
                                            "Double tap ignored because in scroll mode"
                                    )
                                    return true
                                }

                                synchronized(doubleTapLock) {
                                    // Safety check: Reset if flag has been stuck for too long
                                    // (>500ms)
                                    val currentTime = SystemClock.uptimeMillis()
                                    if (isProcessingDoubleTap &&
                                                    lastDoubleTapStartTime > 0 &&
                                                    currentTime - lastDoubleTapStartTime > 500
                                    ) {
                                        DebugLog.d(
                                                "DoubleTapDebug",
                                                "Resetting stuck isProcessingDoubleTap flag"
                                        )
                                        isProcessingDoubleTap = false
                                    }

                                    if (isProcessingDoubleTap) {
                                        DebugLog.d(
                                                "DoubleTapDebug",
                                                "Skipping - already processing double tap"
                                        )
                                        return true
                                    }
                                    isProcessingDoubleTap = true
                                    lastDoubleTapStartTime = currentTime
                                    pendingDoubleTapAction = true

                                    // Calculate dynamic delay to ensure we wait until AFTER the
                                    // triple tap window closes
                                    val timeSinceFirstTap = SystemClock.uptimeMillis() - firstTapTime
                                    val remainingTripleTapWindow =
                                            TRIPLE_TAP_DURATION - timeSinceFirstTap

                                    // Make sure we wait at least a small buffer after the window
                                    // closes
                                    // But cap the delay to avoid excessive waiting if the window is
                                    // huge (though 800ms is reasonable)
                                    val delay =
                                            if (remainingTripleTapWindow > 0)
                                                    remainingTripleTapWindow + 30
                                            else DOUBLE_TAP_CONFIRMATION_DELAY

                                    DebugLog.d(
                                            "DoubleTapDebug",
                                            "Scheduling double tap action. Delay: ${delay}ms (Window remaining: $remainingTripleTapWindow)"
                                    )

                                    doubleTapRunnable = Runnable {
                                        synchronized(doubleTapLock) {
                                            try {
                                                // Final check for triple tap
                                                if (isTripleTapInProgress) {
                                                    DebugLog.d(
                                                            "DoubleTapDebug",
                                                            "Aborting double tap action - triple tap in progress"
                                                    )
                                                    return@Runnable
                                                }

                                                if (pendingDoubleTapAction) {
                                                    DebugLog.d(
                                                            "DoubleTapDebug",
                                                            "Executing pending double tap action"
                                                    )
                                                    performDoubleTapBackNavigation()
                                                }
                                            } finally {
                                                // Note: Do NOT reset tapCount/lastTapTime here
                                                pendingDoubleTapAction = false
                                                isProcessingDoubleTap = false
                                                lastDoubleTapStartTime = 0L
                                                doubleTapRunnable = null
                                            }
                                        }
                                    }

                                    handler.postDelayed(doubleTapRunnable!!, delay)
                                }

                                return true
                            }

                            private fun performDoubleTapBackNavigation() {
                                // Dim-mode exit short-circuit. When the user is
                                // in the minimal dim overlay, double-tap is the
                                // documented "exit dim mode" gesture — it must
                                // not fall through into the return-to-TapClaw
                                // path (which would shut down audio + switch
                                // panels) or the browser-back path. We just
                                // unmask and stay where we are; media keeps
                                // playing because we don't pause anything.
                                if (::dualWebViewGroup.isInitialized &&
                                    dualWebViewGroup.isScreenMasked()
                                ) {
                                    DebugLog.d(
                                        "DoubleTapDebug",
                                        "Dim-mode active — double-tap exits dim mode only"
                                    )
                                    runCatching { dualWebViewGroup.unmaskScreen() }
                                    setUnipanelHudVisible(true)
                                    return
                                }
                                // Double-tap priority: if a Gemini voice session is
                                // active (phase != IDLE), CANCEL it. If the browser was
                                // single-tap-hidden, restore it. Otherwise roll the
                                // HUD/chat up or down for a full-screen browser. (Dim
                                // mode is handled by the short-circuit above; single-tap
                                // on empty space still toggles the browser view.)
                                val voiceActive = isGeminiExitSurfaceActive()
                                when {
                                    voiceActive -> {
                                        DebugLog.d(
                                            "DoubleTapDebug",
                                            "Gemini active — double-tap cancels the voice session"
                                        )
                                        exitGeminiFully()
                                    }
                                    browserPanelHidden -> {
                                        DebugLog.d(
                                            "DoubleTapDebug",
                                            "Restoring browser portion (was focus-hidden)"
                                        )
                                        showBrowserPanel()
                                    }
                                    else -> {
                                        // Mars: double-tap rolls the HUD/chat up and
                                        // down to give a full-screen browser (was: toggle
                                        // the browser's own nav bars).
                                        DebugLog.d(
                                            "DoubleTapDebug",
                                            "Rolling HUD/chat for full-screen browser"
                                        )
                                        toggleUnipanelHudRoll()
                                    }
                                }
                                return
                            }

                            // Phase 4d — legacy double-tap path below is dead code
                            // in unipanel mode; kept here in a separate, never-
                            // called function so the Activity superclass code I'd
                            // otherwise rip out is preserved verbatim for a future
                            // recovery commit if we ever bring chat-panel back.
                            @Suppress("UNUSED_PARAMETER", "unused")
                            private fun performDoubleTapBackNavigationLegacyUnused() {
                                if (returnToChatOnDoubleTap) {
                                    // Explicitly stop any media (YouTube or generic
                                    // HTML5 <video>/<audio>) BEFORE handing control
                                    // back to TapClaw. onPause() alone is not
                                    // sufficient here: when FLAG_ACTIVITY_REORDER_TO_FRONT
                                    // brings TapClaw up, TapBrowser's Activity can
                                    // remain in a paused-but-visible state where
                                    // the onPause pause-media guard may have already
                                    // been bypassed (e.g. screen-masked branch), so
                                    // YouTube keeps pumping audio. Stopping media
                                    // here makes the return-to-chat gesture silence
                                    // playback unconditionally.
                                    if (::dualWebViewGroup.isInitialized) {
                                        runCatching {
                                            dualWebViewGroup.pauseYouTubeMediaAcrossAllWindows(
                                                resetTracking = false
                                            )
                                        }
                                        runCatching { dualWebViewGroup.pauseAllWindowsMedia() }
                                        runCatching { dualWebViewGroup.clearTrackedMediaPlayback() }
                                        // The dark visualizer / masked YouTube path can keep audio
                                        // alive inside iframe-backed players even after the softer
                                        // pause hooks run. Use the stronger kill path before
                                        // returning to chat so playback is always silenced.
                                        runCatching { killAllWebViewAudio(resumeWebViewsAfterKill = false) }
                                    }
                                    runCatching { stopNativeRadioStream() }
                                    runCatching { clearTapRadioPlaybackPrefs() }
                                    // Nuclear purge: also navigates any radio.html /
                                    // podcasts.html / spotify.html WebView to
                                    // about:blank and scrubs `BrowserPrefs.last_url`
                                    // + `webview_state` so a later cold start (AR
                                    // glasses can aggressively kill backgrounded
                                    // Activities to reclaim RAM) cannot resurrect
                                    // the stream via tryRestoreSession().
                                    runCatching {
                                        stopOrphanedNativeRadioPlayer(this@MainActivity)
                                    }
                                    try {
                                        startActivity(
                                            Intent().setClassName(this@MainActivity, TAPCLAW_MAIN_ACTIVITY)
                                                .addFlags(
                                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                                )
                                        )
                                    } catch (e: Exception) {
                                        DebugLog.e("DoubleTapDebug", "Failed to return to TapClaw", e)
                                        finish()
                                    }
                                    return
                                }

                                val isScreenMasked = dualWebViewGroup.isScreenMasked()
                                val hasHistory = webView.canGoBack()

                                DebugLog.d(
                                        "DoubleTapDebug",
                                        """Double tap confirmed. isScreenMasked=$isScreenMasked, isKeyboardVisible=$isKeyboardVisible, canGoBack=$hasHistory"""
                                )

                                if (!hasHistory) {
                                    DebugLog.d(
                                            "DoubleTapDebug",
                                            "No history entry available for goBack()"
                                    )
                                    return
                                }

                                onNavigationBackPressed()
                            }
                        }
                )

        templeDoubleTapDetector =
                GestureDetector(
                        this,
                        object : SimpleOnGestureListener() {
                            override fun onDown(e: MotionEvent): Boolean = true

                            override fun onDoubleTap(e: MotionEvent): Boolean {
                                // ROOT-CAUSE FIX (double-tap doesn't cancel Gemini):
                                // the temple/side-arm pad (cyttsp6) is routed here, and
                                // this handler previously ONLY toggled mouse-tap mode —
                                // it never checked for an active voice session. So a
                                // double-tap on the natural "cancel" pad silently flipped
                                // mouse mode instead of stopping Gemini. Cancel an active
                                // session first; only fall through to the mode toggle when
                                // no voice session is running.
                                val voiceActive = isGeminiExitSurfaceActive()
                                if (voiceActive) {
                                    DebugLog.d(
                                        "DoubleTapDebug",
                                        "Temple double-tap — cancelling active Gemini session"
                                    )
                                    exitGeminiFully()
                                    return true
                                }
                                toggleMouseTapMode()
                                return true
                            }
                        }
                )

        // Create and set up bookmarks view
        val bookmarksView =
                BookmarksView(this).apply {
                    setKeyboardListener(this@MainActivity) // Set keyboard listener directly
                    setBookmarkListener(
                            this@MainActivity
                    ) // Add this line to set the bookmark listener
                }

        // Set up bookmarks view in DualWebViewGroup
        dualWebViewGroup.setBookmarksView(bookmarksView)

        // Ensure settings and bookmarks are closed on app startup
        dualWebViewGroup.resetUiState()

        bookmarksView.setKeyboardListener(this)
        DebugLog.d("BookmarksDebug", "BookmarksView set in onCreate")

        // Set up the keyboard listener
        dualWebViewGroup.keyboardListener =
                object : DualWebViewGroup.KeyboardListener {
                    override fun onShowKeyboard() {
                        showCustomKeyboard()
                    }

                    override fun onHideKeyboard() {
                        hideCustomKeyboard()
                    }
                }

        // Set up the mic listener for the chat view
        dualWebViewGroup.micListener =
                object : ChatView.MicListener {
                    override fun onMicrophonePressed() {
                        this@MainActivity.onMicrophonePressed()
                    }
                }

        // Cursor views setup
        // Set up the cursor views directly in the main container
        cursorLeftView =
                ImageView(this).apply {
                    layoutParams = ViewGroup.LayoutParams(24, 24) // Adjust size as needed
                    setImageResource(R.drawable.cursor_arrow_image)
                    scaleType =
                            ImageView.ScaleType
                                    .FIT_START // Anchor to top-left for accurate click alignment
                    x = 320f
                    y = 240f
                    visibility = View.GONE
                }
        cursorRightView =
                ImageView(this).apply {
                    layoutParams = ViewGroup.LayoutParams(24, 24)
                    setImageResource(R.drawable.cursor_arrow_image)
                    scaleType =
                            ImageView.ScaleType
                                    .FIT_START // Anchor to top-left for accurate click alignment
                    x = 960f
                    y = 240f
                    visibility = View.GONE
                }

        // Add cursor views to the main container
        mainContainer.apply {
            addView(cursorLeftView)
            addView(cursorRightView)
        }

        webView = dualWebViewGroup.getWebView()

        // Register with the cross-module BrowserFrameHolder so the
        // visionclaw tool layer (BrowserVisionTool) can pull viewport
        // screenshots on demand without holding a direct reference.
        // Re-attaches on every WebView swap (multi-window cases).
        com.TapLink.app.media.BrowserFrameHolder.attach(webView)

        // If this launch was a warm-start kicked off by visionclaw
        // MainActivity (purely to make the WebView available for the
        // browser_vision tool), push our task to the back so the chat
        // panel stays foreground. The WebView is already attached
        // above, which is all the warm-start needed. We post the
        // moveTaskToBack to the WebView's next layout pass so onCreate
        // has time to wire up the rest of the Activity state before
        // backgrounding.
        val warmStartedByVisionclaw =
                intent.getBooleanExtra(EXTRA_BROWSER_WARM_START, false)
        if (warmStartedByVisionclaw) {
            webView.post {
                try {
                    moveTaskToBack(true)
                    DebugLog.d("WarmStart", "tapbrowser warm-started; sent task to back")
                } catch (e: Exception) {
                    DebugLog.w("WarmStart", "moveTaskToBack failed: ${e.message}")
                }
            }
        }
        // Unipanel v2 reverse warm-start: DISABLED. The previous attempt
        // here would, on tapbrowser cold launch, kick visionclaw to
        // onCreate and have visionclaw moveTaskToBack(true) itself. The
        // intent flag combo (NEW_TASK | SINGLE_TOP) was supposed to land
        // visionclaw in its own task; in practice both Activities have
        // the default taskAffinity (= application's packageName), so
        // Android reused tapbrowser's task instead of creating a new
        // one. visionclaw's moveTaskToBack then backgrounded the WHOLE
        // shared task — including tapbrowser — and the RayNeo launcher
        // killed com.rayneo.visionclaw for losing foreground.
        //
        // For now the browser launches alone; visionclaw doesn't run on
        // cold start. The user-visible consequence is that voice
        // activation from the overlay won't work until visionclaw is
        // running — followup needs a Service-based voice path (so
        // there's no Activity-task fight) or a separate taskAffinity
        // on visionclaw + an explicit REORDER_TO_FRONT bounce back to
        // tapbrowser after visionclaw onCreates. Both are non-trivial
        // and worth doing in their own commit with logcat verification.

        webView.setOnTouchListener { _, event ->
            val isMouseEvent = isMousePointerEvent(event)

            // Clear any pending touch events when a new touch starts
            // or when touch ends/cancels
            if (event.action == MotionEvent.ACTION_DOWN ||
                            event.action == MotionEvent.ACTION_UP ||
                            event.action == MotionEvent.ACTION_CANCEL
            ) {
                pendingTouchRunnable?.let { pendingTouchHandler?.removeCallbacks(it) }
                pendingTouchRunnable = null
            }

            if (isAnchored && isKeyboardVisible) {
                return@setOnTouchListener false
            }

            if (isSimulatingTouchEvent) {
                return@setOnTouchListener false
            }

            if (isMouseTapMode && isMouseEvent) {
                return@setOnTouchListener false
            }

            if (isKeyboardVisible) {
                return@setOnTouchListener true
            }

            // Use the cached result from dispatchTouchEvent instead of calling gestureDetector
            // again
            // This prevents double-processing which can corrupt gesture state
            val handled = isGestureHandled

            // Add check for settings menu visibility
            if (dualWebViewGroup.isSettingsVisible()) {
                return@setOnTouchListener isCursorVisible // Let the event propagate to the settings
                // menu
            }

            // In scroll mode, let taps pass through to WebView
            // The gesture detector still sees all events via dispatchTouchEvent,
            // so double-tap detection still works independently
            // In scroll mode, let taps pass through to WebView ONLY if anchored
            // If not anchored, we want the cursor to handle the event (move/wake)
            if (dualWebViewGroup.isInScrollMode() && isAnchored) {
                // Always return false to let taps reach the WebView (for clicking links, etc.)
                // The gesture detector has already processed the event in dispatchTouchEvent
                return@setOnTouchListener false
            }

            if (isCursorVisible && !isMouseEvent) {
                return@setOnTouchListener true
            }

            if (event.action == MotionEvent.ACTION_UP && !handled) {}

            handled
        }

        // Enable storage + JS features required by modern web apps (auth/session state, etc.).
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        @Suppress("DEPRECATION") run { webView.settings.databaseEnabled = true }

        webView.webViewClient =
                object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        DebugLog.d("YouTubeAuto", "onPageStarted[1]: url=$url")
                        // If cursor was visible, store its position
                        if (isCursorVisible) {
                            lastKnownCursorX = lastCursorX
                            lastKnownCursorY = lastCursorY
                        }
                        // Force desktop UA for YouTube when autoplay is active so we
                        // get predictable desktop DOM with standard <a href="/watch?v=..."> links.
                        if (!youtubeAutoplayQuery.isNullOrBlank() &&
                            !youtubeAutoplayMode.isNullOrBlank() &&
                            url != null &&
                            (url.contains("youtube.com") || url.contains("youtu.be"))
                        ) {
                            val desktopUA = if (::dualWebViewGroup.isInitialized) {
                                dualWebViewGroup.getDesktopUserAgent()
                            } else null
                            if (!desktopUA.isNullOrBlank() && view?.settings?.userAgentString != desktopUA) {
                                view?.settings?.userAgentString = desktopUA
                            }
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)

                        if (::dualWebViewGroup.isInitialized) {
                            dualWebViewGroup.saveWindowMetadataState()
                        }

                        // Force enable input on all potential input fields
                        webView.evaluateJavascript(
                                """
                (function() {
                    function enableInput(element) {
                        element.style.webkitUserSelect = 'text';
                        element.style.userSelect = 'text';
                        element.setAttribute('inputmode', 'text');
                    }

                    document.querySelectorAll('input,textarea,[contenteditable="true"]')
                        .forEach(enableInput);

                    // Create observer for dynamically added elements
                    new MutationObserver((mutations) => {
                        mutations.forEach((mutation) => {
                            mutation.addedNodes.forEach((node) => {
                                if (node.nodeType === 1) {  // ELEMENT_NODE
                                    if (node.matches('input,textarea,[contenteditable="true"]')) {
                                        enableInput(node);
                                    }
                                    node.querySelectorAll('input,textarea,[contenteditable="true"]')
                                        .forEach(enableInput);
                                }
                            });
                        });
                    }).observe(document.body, {
                        childList: true,
                        subtree: true
                    });
                })();
            """,
                                null
                        )

                        wasKeyboardDismissedByEnter = false

                        // Log focus state

                        // Update scrollbar visibility based on new content
                        dualWebViewGroup.updateScrollBarsVisibility()

                        // Lock viewport scale to avoid zoom-loop behavior on X3 trackpad.
                        val viewportContent =
                                if (dualWebViewGroup.isDesktopMode()) {
                                    "width=1280, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"
                                } else {
                                    "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"
                                }
                        webView.evaluateJavascript(
                                """
                        (function() {
                            var viewport = document.querySelector('meta[name="viewport"]');
                            if (!viewport) {
                                viewport = document.createElement('meta');
                                viewport.name = 'viewport';
                                document.head.appendChild(viewport);
                            }
                            viewport.content = '$viewportContent';
                        })();
                        """,
                                null
                        )

                        // Auto-unmute YouTube videos that start muted due to autoplay policy
                        DebugLog.d("YouTubeAuto", "onPageFinished: url=$url")
                        if (url != null && (url.contains("youtube.com") || url.contains("youtu.be"))) {
                            webView.evaluateJavascript(
                                    """
                            (function() {
                                var attempts = 0;
                                function tryUnmute() {
                                    var videos = document.querySelectorAll('video');
                                    var unmuted = false;
                                    videos.forEach(function(v) {
                                        if (v.muted) { v.muted = false; unmuted = true; }
                                    });
                                    if (!unmuted || videos.length === 0) {
                                        var muteBtn = document.querySelector('.ytp-mute-button');
                                        if (muteBtn) {
                                            var vol = (muteBtn.getAttribute('data-title-no-tooltip') ||
                                                       muteBtn.getAttribute('title') || '').toLowerCase();
                                            if (vol.indexOf('unmute') >= 0 || vol.indexOf('muted') >= 0) {
                                                muteBtn.click(); unmuted = true;
                                            }
                                        }
                                    }
                                    attempts++;
                                    if (!unmuted && attempts < 15) setTimeout(tryUnmute, 800);
                                }
                                setTimeout(tryUnmute, 1500);
                                var obs = new MutationObserver(function() {
                                    document.querySelectorAll('video').forEach(function(v) {
                                        if (v.muted && !v.dataset.taplinkUnmuted) {
                                            v.muted = false; v.dataset.taplinkUnmuted = 'true';
                                        }
                                    });
                                });
                                if (document.body) obs.observe(document.body, { childList: true, subtree: true });
                            })();
                            """,
                                    null
                            )
                            injectYouTubePlaylistAutomation(webView, url)
                        }
                    }

                    override fun doUpdateVisitedHistory(
                            view: WebView?,
                            url: String?,
                            isReload: Boolean
                    ) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        // ${view?.canGoBack()}")
                    }
                }

        cursorLeftView.apply {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }

        cursorRightView.apply {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }

        webView.setBackgroundColor(Color.BLACK)
        dualWebViewGroup.updateBrowsingMode(dualWebViewGroup.isDesktopMode())

        // Set up the listener
        dualWebViewGroup.linkEditingListener = this

        // Add after other listener assignments
        dualWebViewGroup.anchorToggleListener = this

        // Initialize sensor manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        // Load preferences (use TapLinkPrefs for settings that are saved there)
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        smoothnessLevel = prefs.getInt(Constants.KEY_ANCHOR_SMOOTHNESS, 40)
        updateSmoothnessFactors(smoothnessLevel)

        // Note: isAnchored is already loaded earlier from BrowserPrefs
        DebugLog.d("AnchorDebug", "Anchored state (loaded earlier): $isAnchored")

        // After initializing webView and dualWebViewGroup but before loadInitialPage()
        // Set initial cursor position
        // Make cursor visible
        cursorLeftView.visibility = View.VISIBLE
        cursorRightView.visibility = View.VISIBLE
        centerCursor(true)

        // Start in saved anchored mode state
        if (isAnchored) {
            rotationSensor?.let { sensor ->
                sensorEventListener = createSensorEventListener()
                sensorManager.registerListener(
                        sensorEventListener,
                        sensor,
                        SensorManager.SENSOR_DELAY_UI
                )
            }
            dualWebViewGroup.startAnchoring()
        } else {
            // Not anchored - make sure anchored mode is off
            dualWebViewGroup.stopAnchoring()
        }

        // Then try to restore the previous state
        setupWebView() // This will attempt to load the saved URL

        val hasExplicitStartupUrl = !startupUrlOverride.isNullOrBlank()
        // Only fall back to the dashboard when we are not servicing an explicit launch URL.
        if ((webView.url == null || webView.url == "about:blank") && !hasExplicitStartupUrl) {
            webView.clearCache(true)
            webView.clearHistory()
            webView.loadUrl(Constants.DEFAULT_URL)
        }

        startupUrlOverride
                ?.takeIf { it.isNotBlank() }
                ?.let { overrideUrl ->
                    val formatted = formatUrl(overrideUrl)
                    val isYouTube = formatted.contains("youtube.com") || formatted.contains("youtu.be")
                    // Force desktop UA for YouTube autoplay
                    if (!youtubeAutoplayQuery.isNullOrBlank() &&
                        !youtubeAutoplayMode.isNullOrBlank() && isYouTube
                    ) {
                        val targetUa = if (shouldUseDesktopUaForYouTube(formatted, youtubeAutoplayMode)) {
                            if (::dualWebViewGroup.isInitialized) dualWebViewGroup.getDesktopUserAgent() else null
                        } else {
                            customUserAgent
                        }
                        if (!targetUa.isNullOrBlank()) {
                            webView.settings.userAgentString = targetUa
                        }
                    }
                    // If launching directly into YouTube, wipe the restored
                    // browsing history so the WebView doesn't try to load
                    // old pages (CNN, Fox News, etc.) from the back stack.
                    if (isYouTube) {
                        webView = dualWebViewGroup.resetToSingleWindow(loadDefaultUrl = false)
                        com.TapLink.app.media.BrowserFrameHolder.attach(webView)
                        webView.stopLoading()
                        webView.clearHistory()
                        webView.clearCache(true)
                        try {
                            getSharedPreferences(prefsName, MODE_PRIVATE).edit()
                                .remove(Constants.KEY_WEBVIEW_STATE)
                                .apply()
                        } catch (_: Exception) {}
                        DebugLog.d("YouTubeAuto", "loadInitialPage: cleared history/cache for YouTube cold start")
                    }
                    if (isAddressOrMapsUrl(formatted)) {
                        // Aggressively kill ALL audio across ALL WebViews before loading map
                        killAllWebViewAudio()
                        webView.settings.mediaPlaybackRequiresUserGesture = true // block audio on map page
                        nuclearCleanupInProgress = true
                        webView.loadUrl("about:blank")
                        val arNavUrl = buildArNavUrl(formatted)
                        DebugLog.d("ARNav", "coldStart: intercepted → $arNavUrl")
                        webView.postDelayed({
                            nuclearCleanupInProgress = false
                            webView.loadUrl(arNavUrl)
                        }, 200)
                        persistActiveUrl("tapclaw_intent_arnav", arNavUrl, webView)
                    } else {
                        webView.settings.mediaPlaybackRequiresUserGesture = false // restore for YouTube etc.
                        webView.loadUrl(formatted)
                        persistActiveUrl("tapclaw_intent", formatted, webView)
                    }
                    startupUrlOverride = null
                }

        // Initialize camera after WebView setup
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionGranted = true
        } else {
            // Request camera permission if we don't have it
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }
        initializeSpeechRecognition() // Initialize speech recognition after WebView setup

        // Call permission check during setup
        checkAndRequestPermissions()

        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            DebugLog.e("Sensor", "No rotation vector sensor found")
        } else {
            DebugLog.d("Sensor", "Rotation vector sensor found")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incomingOverrideUrl =
                intent.getStringExtra(EXTRA_BROWSER_INITIAL_URL)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
        val incomingFormattedUrl = incomingOverrideUrl?.let { formatUrl(it) }
        val incomingIsYouTube =
                incomingFormattedUrl?.let {
                    it.contains("youtube.com", ignoreCase = true) ||
                            it.contains("youtu.be", ignoreCase = true)
                } == true
        if (incomingIsYouTube && ::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.pauseYouTubeMediaAcrossAllWindows()
        }
        // IMPORTANT: Snapshot the OLD autoplay state BEFORE parsing the new intent,
        // so we can tell if Gemini is sending a fresh YouTube request or a non-YouTube URL.
        val hadOldAutoplay = !youtubeAutoplayQuery.isNullOrBlank() && !youtubeAutoplayMode.isNullOrBlank()
        parseTapClawLaunchIntent(intent)
        val overrideUrl = startupUrlOverride
        if (::webView.isInitialized && !overrideUrl.isNullOrBlank()) {
            val formatted = formatUrl(overrideUrl)
            val isYouTube = formatted.contains("youtube.com") || formatted.contains("youtu.be")
            DebugLog.d("YouTubeAuto", "onNewIntent: url=$formatted isYouTube=$isYouTube " +
                "query='${youtubeAutoplayQuery}' mode='${youtubeAutoplayMode}' " +
                "hadOldAutoplay=$hadOldAutoplay playlistSize=${youtubePlaylist.size}")

            // Force desktop UA for YouTube autoplay so we get standard desktop DOM
            if (!youtubeAutoplayQuery.isNullOrBlank() &&
                !youtubeAutoplayMode.isNullOrBlank() && isYouTube
            ) {
                val targetUa = if (shouldUseDesktopUaForYouTube(formatted, youtubeAutoplayMode)) {
                    if (::dualWebViewGroup.isInitialized) dualWebViewGroup.getDesktopUserAgent() else null
                } else {
                    customUserAgent
                }
                if (!targetUa.isNullOrBlank()) {
                    webView.settings.userAgentString = targetUa
                }
            }

            // ── Clean up before loading a new YouTube URL ──
            // AVOID navigating to about:blank — it triggers onPageStarted/
            // onPageFinished callbacks that save state, restore history URLs
            // (CNN, Fox News, etc.), and fight with DualWebViewGroup's
            // session persistence.  Instead: stop current load, kill media
            // via JS, clear Kotlin-side state, then directly load the new URL.
            // When loadUrl() is called the WebView engine internally tears
            // down the old page (and its media pipeline) before building
            // the new one, which is sufficient.
            if (isYouTube) {
                webView = dualWebViewGroup.resetToSingleWindow(loadDefaultUrl = false)
                com.TapLink.app.media.BrowserFrameHolder.attach(webView)
                // 1. Stop everything
                webView.stopLoading()

                // 2. Wipe the WebView's back/forward history + disk cache so
                //    no stale pages (CNN, Fox News, etc.) can be restored or
                //    replayed by the navigation stack or session persistence.
                webView.clearHistory()
                webView.clearCache(true)

                // 3. Kill media + all our injected timers in the old page
                webView.evaluateJavascript(
                    "(function(){" +
                    "try{document.querySelectorAll('video,audio').forEach(function(el){" +
                    "try{el.pause();el.removeAttribute('src');el.load();}catch(e){}});}catch(e){}" +
                    "var id=window.setTimeout(function(){},0);while(id--)clearTimeout(id);" +
                    "var iid=window.setInterval(function(){},0);while(iid--)clearInterval(iid);" +
                    "})()", null
                )

                // 4. Clear ALL stale Kotlin-side state
                lastYouTubeInjectionUrl = null
                youtubePlaylist = emptyList()
                youtubePlaylistIndex = 0

                // 5. Also clear the persisted WebView state from SharedPreferences
                //    so that if the app is killed+restarted, tryRestoreSession()
                //    doesn't reload the old browsing history.
                try {
                    getSharedPreferences(prefsName, MODE_PRIVATE).edit()
                        .remove(Constants.KEY_WEBVIEW_STATE)
                        .apply()
                } catch (_: Exception) {}

                DebugLog.d("YouTubeAuto", "onNewIntent: cleared history + cache + playlist + persisted state")

                // 6. Load the new YouTube URL on a clean slate
                webView.settings.mediaPlaybackRequiresUserGesture = false // restore for YouTube
                webView.loadUrl(formatted)
                persistActiveUrl("tapclaw_new_intent", formatted, webView)
            } else if (isAddressOrMapsUrl(formatted)) {
                // ── AR Navigation HUD ──
                if (hadOldAutoplay) {
                    youtubeAutoplayQuery = null
                    youtubeAutoplayMode = null
                    youtubeAutoplayQueue = emptyList()
                    youtubeAutoplayQueueIndex = 0
                    youtubePlaylist = emptyList()
                    youtubePlaylistIndex = 0
                    lastYouTubeInjectionUrl = null
                }
                // Aggressively kill ALL audio across ALL WebViews before loading map
                killAllWebViewAudio()
                webView.settings.mediaPlaybackRequiresUserGesture = true // block audio on map page
                nuclearCleanupInProgress = true
                webView.loadUrl("about:blank")
                val arNavUrl = buildArNavUrl(formatted)
                DebugLog.d("ARNav", "onNewIntent: intercepted → $arNavUrl")
                webView.postDelayed({
                    nuclearCleanupInProgress = false
                    webView.loadUrl(arNavUrl)
                }, 200)
                persistActiveUrl("tapclaw_new_intent_arnav", arNavUrl, webView)
            } else {
                // Non-YouTube URL — clear any leftover YouTube state
                if (hadOldAutoplay) {
                    youtubeAutoplayQuery = null
                    youtubeAutoplayMode = null
                    youtubeAutoplayQueue = emptyList()
                    youtubeAutoplayQueueIndex = 0
                    youtubePlaylist = emptyList()
                    youtubePlaylistIndex = 0
                    lastYouTubeInjectionUrl = null
                    DebugLog.d("YouTubeAuto", "onNewIntent: non-YT URL, cleared autoplay state")
                }

                // ── RADIO HANDOFF: stop the current native radio stream BEFORE loading ──
                // When switching podcasts/stations, the old ExoPlayer must be released
                // before the new page loads and starts a new stream. Doing this here in
                // Kotlin (on the UI thread) is reliable; relying on JavaScript calling
                // stopNativeRadioStream() through the bridge during page init is racy
                // because runOnUiBlocking can time out while the UI thread is busy
                // processing the page navigation.
                if (formatted.contains("radio.html", ignoreCase = true) &&
                    formatted.contains("playUrl=", ignoreCase = true)) {
                    if (nativeRadioPlayer != null) {
                        DebugLog.d("TapRadioNative", "onNewIntent: stopping current radio stream before loading new radio.html")
                        releaseNativeRadioPlayer(clearMetadata = true, abandonFocus = false)
                    }
                }

                webView.settings.mediaPlaybackRequiresUserGesture = false // restore for non-map
                webView.loadUrl(formatted)
                persistActiveUrl("tapclaw_new_intent", formatted, webView)
            }
            startupUrlOverride = null
        }
        syncTapRadioPlaybackUi()
    }

    private fun parseTapClawLaunchIntent(intent: Intent?) {
        if (intent == null) return
        returnToChatOnDoubleTap =
                intent.getBooleanExtra(
                        EXTRA_RETURN_TO_CHAT_ON_DOUBLE_TAP,
                        returnToChatOnDoubleTap
                )
        startupUrlOverride =
                intent.getStringExtra(EXTRA_BROWSER_INITIAL_URL)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: startupUrlOverride
        val incomingQueue = intent.getStringExtra(EXTRA_YOUTUBE_AUTOPLAY_QUEUE)
            ?.let { raw ->
                runCatching {
                    val arr = org.json.JSONArray(raw)
                    val items = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optString(i, "").trim()
                        if (item.isNotBlank() && items.none { existing -> existing.equals(item, ignoreCase = true) }) {
                            items.add(item)
                        }
                    }
                    items.toList()
                }.getOrDefault(emptyList())
            }
            .orEmpty()
        if (incomingQueue.isNotEmpty()) {
            youtubeAutoplayQueue = incomingQueue
            youtubeAutoplayQueueIndex = 0
        } else if (intent.hasExtra(EXTRA_YOUTUBE_AUTOPLAY_QUERY)) {
            youtubeAutoplayQueue = emptyList()
            youtubeAutoplayQueueIndex = 0
        }
        youtubeAutoplayQuery =
                incomingQueue.firstOrNull()
                        ?: intent.getStringExtra(EXTRA_YOUTUBE_AUTOPLAY_QUERY)
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                        ?: youtubeAutoplayQuery
        youtubeAutoplayMode =
                intent.getStringExtra(EXTRA_YOUTUBE_AUTOPLAY_MODE)
                        ?.trim()
                        ?.lowercase(Locale.US)
                        ?.takeIf { it == "video" || it == "music" || it == "subscriptions" || it == "history" }
                        ?: youtubeAutoplayMode
    }

    private fun injectYouTubePlaylistAutomation(view: WebView, url: String) {
        DebugLog.d("YouTubeAuto", "injectYouTubePlaylistAutomation called — url=$url")
        var query = youtubeAutoplayQuery?.trim().orEmpty()
        var mode = youtubeAutoplayMode?.trim().orEmpty()
        DebugLog.d("YouTubeAuto", "  extras: query='$query' mode='$mode'")

        // Fallback: extract autoplay parameters from the URL itself
        // (covers typed-chat and Gemini open_taplink paths).
        // Also picks up `taplink_autoplay_queue` for the "Play all" button
        // in video_list.html, which encodes a JSON array of song-search
        // phrases. Without this, "Play all" used to land on YouTube's
        // playlist-tab search instead of actually playing the picked
        // songs in order.
        if ((query.isBlank() || mode.isBlank()) && url.contains("taplink_autoplay=")) {
            try {
                val uri = android.net.Uri.parse(url)
                val urlMode = uri.getQueryParameter("taplink_autoplay")
                    ?.trim()?.lowercase(Locale.US)
                    ?.takeIf { it == "video" || it == "music" || it == "subscriptions" || it == "history" }
                val urlQuery = uri.getQueryParameter("search_query")?.trim()
                val urlQueueJson = uri.getQueryParameter("taplink_autoplay_queue")?.trim()
                if (!urlMode.isNullOrBlank()) {
                    mode = urlMode
                    query = when {
                        !urlQuery.isNullOrBlank() -> urlQuery
                        urlMode == "subscriptions" -> "subscriptions"
                        urlMode == "history" -> "history"
                        else -> query
                    }
                    youtubeAutoplayQuery = query
                    youtubeAutoplayMode = mode

                    // If the URL also carries a queue, hydrate it. The
                    // queue is JSON-encoded `["song1 artist1", ...]`
                    // produced by video_list.html's Play-all button.
                    if (!urlQueueJson.isNullOrBlank()) {
                        val parsedQueue = runCatching {
                            val arr = org.json.JSONArray(urlQueueJson)
                            val items = mutableListOf<String>()
                            for (i in 0 until arr.length()) {
                                val item = arr.optString(i, "").trim()
                                if (item.isNotBlank() &&
                                    items.none { existing -> existing.equals(item, ignoreCase = true) }
                                ) {
                                    items.add(item)
                                }
                            }
                            items.toList()
                        }.getOrDefault(emptyList())
                        if (parsedQueue.isNotEmpty()) {
                            youtubeAutoplayQueue = parsedQueue
                            youtubeAutoplayQueueIndex = 0
                            DebugLog.d(
                                "YouTubeAuto",
                                "  URL queue hydrated: size=${parsedQueue.size} first='${parsedQueue.first()}'"
                            )
                        }
                    }

                    DebugLog.d("YouTubeAuto", "  URL fallback: query='$query' mode='$mode'")
                }
            } catch (_: Exception) { /* ignore malformed URIs */ }
        }

        if (query.isBlank() || mode.isBlank()) {
            DebugLog.d("YouTubeAuto", "  SKIPPING — query or mode is blank")
            return
        }
        DebugLog.d("YouTubeAuto", "  INJECTING bootstrap JS for query='$query' mode='$mode'")
        // Only reset injection flags if this is a genuinely new page (different URL).
        // This prevents double-injection when onPageFinished fires multiple times
        // (iframes, redirects), which would toggle fullscreen on and off.
        val urlBase = url.substringBefore("#").substringBefore("&t=")
        if (urlBase != lastYouTubeInjectionUrl) {
            lastYouTubeInjectionUrl = urlBase
            view.evaluateJavascript("window.__taplink_yt_injected=false;window.__taplink_watch_injected=false;", null)
        }
        view.evaluateJavascript(buildYouTubeAutomationBootstrapScript(query, mode), null)
    }

    /**
     * Completely rewritten YouTube automation — simple & robust.
     *
     * SEARCH PAGE: finds the first clickable video link and navigates to it.
     * WATCH  PAGE: enables captions, unmutes, injects a floating ↻ replay
     *              button (bottom-left), and lets YouTube's built-in autoplay
     *              handle the next video.
     */
    internal fun buildYouTubeAutomationBootstrapScript(query: String, mode: String): String {
        return """
            (function(){
                console.log('[TapLink-YT] Bootstrap injected, url=' + location.href);
                if (window.__taplink_yt_injected) { console.log('[TapLink-YT] Already injected, skipping'); return; }
                window.__taplink_yt_injected = true;

                var loc = location.href || '';
                var autoplayMode = ${org.json.JSONObject.quote(mode)};
                var wantsSubscriptions = autoplayMode === 'subscriptions';
                var wantsHistory = autoplayMode === 'history';
                var isSearch = loc.indexOf('youtube.com/results') >= 0;
                var isSubscriptions = loc.indexOf('youtube.com/feed/subscriptions') >= 0;
                var isHistory = loc.indexOf('youtube.com/feed/history') >= 0;
                var isWatch  = loc.indexOf('youtube.com/watch') >= 0
                            || loc.indexOf('youtu.be/') >= 0;
                console.log('[TapLink-YT] isSearch=' + isSearch + ' isSubscriptions=' + isSubscriptions + ' isHistory=' + isHistory + ' isWatch=' + isWatch + ' wantsSubscriptions=' + wantsSubscriptions + ' wantsHistory=' + wantsHistory);

                function extractVideoIdFromHref(href) {
                    if (!href || href.indexOf('/shorts/') >= 0) return null;
                    var m = href.match(/[?&]v=([A-Za-z0-9_-]{11})/);
                    return m ? m[1] : null;
                }

                /* ── Extract unique 11-char video IDs from InnerTube JSON ──
                 *  Parses the JSON structure to pull only videoRenderer items
                 *  from itemSectionRenderer (actual history), skipping sidebar
                 *  recommendations, shorts shelves, and other non-history content.
                 *  Falls back to regex if JSON parsing fails. */
                function extractVideoIdsFromJson(jsonStr) {
                    var ids = [], seen = {};
                    function addId(id) {
                        if (id && id.length === 11 && !seen[id]) { seen[id] = true; ids.push(id); }
                    }
                    try {
                        var data = JSON.parse(jsonStr);
                        /* Navigate: contents.twoColumnBrowseResultsRenderer.tabs[].tabRenderer
                           .content.sectionListRenderer.contents[].itemSectionRenderer.contents[]
                           .videoRenderer.videoId — these are the actual history entries */
                        var tabs = ((data.contents || {}).twoColumnBrowseResultsRenderer || {}).tabs || [];
                        for (var t = 0; t < tabs.length; t++) {
                            var sections = ((((tabs[t].tabRenderer || {}).content || {}).sectionListRenderer || {}).contents) || [];
                            for (var s = 0; s < sections.length; s++) {
                                var items = ((sections[s].itemSectionRenderer || {}).contents) || [];
                                for (var i = 0; i < items.length; i++) {
                                    if (items[i].videoRenderer && items[i].videoRenderer.videoId) {
                                        addId(items[i].videoRenderer.videoId);
                                    }
                                }
                            }
                        }
                        if (ids.length > 0) {
                            console.log('[TapLink-YT] Parsed ' + ids.length + ' history IDs from JSON structure');
                            return ids;
                        }
                    } catch(e) {
                        console.warn('[TapLink-YT] JSON parse failed, falling back to regex:', e.message);
                    }
                    /* Fallback: regex extraction from raw text */
                    var re = /"videoId"\s*:\s*"([A-Za-z0-9_-]{11})"/g;
                    var m;
                    while ((m = re.exec(jsonStr)) !== null) { addId(m[1]); }
                    return ids;
                }

                /* ── Get InnerTube API key from YouTube's global config ── */
                function getInnertubeApiKey() {
                    try { if (window.ytcfg && ytcfg.get) return ytcfg.get('INNERTUBE_API_KEY') || ''; } catch(e) {}
                    try { if (window.ytcfg && ytcfg.data_) return ytcfg.data_.INNERTUBE_API_KEY || ''; } catch(e) {}
                    // Fallback: scan page source for the key
                    try {
                        var html = document.documentElement.innerHTML;
                        var km = html.match(/"INNERTUBE_API_KEY"\s*:\s*"([^"]+)"/);
                        if (km) return km[1];
                    } catch(e) {}
                    return '';
                }

                function getClientVersion() {
                    var clientVersion = '2.20260101.00.00';
                    try {
                        var cv = (ytcfg.get && ytcfg.get('INNERTUBE_CLIENT_VERSION')) ||
                                 (ytcfg.data_ && ytcfg.data_.INNERTUBE_CLIENT_VERSION);
                        if (cv) clientVersion = cv;
                    } catch(e) {}
                    return clientVersion;
                }

                function getVisitorData() {
                    try { if (ytcfg.get) return ytcfg.get('VISITOR_DATA') || ''; } catch(e) {}
                    try { if (ytcfg.data_) return ytcfg.data_.VISITOR_DATA || ''; } catch(e) {}
                    return '';
                }

                /* ── Generate SAPISIDHASH authorization for authenticated InnerTube requests ── */
                function getSapisidFromCookies() {
                    var m = document.cookie.match(/(?:^|;\s*)SAPISID=([^;]+)/);
                    if (m) return m[1];
                    var m3 = document.cookie.match(/(?:^|;\s*)__Secure-3PAPISID=([^;]+)/);
                    if (m3) return m3[1];
                    return '';
                }

                function sha1Hex(str) {
                    // Simple synchronous SHA-1 for SAPISIDHASH (SubtleCrypto is async, so use fallback)
                    // Encode the string to bytes
                    var encoder = new TextEncoder();
                    var data = encoder.encode(str);
                    // Use SubtleCrypto as a promise
                    return crypto.subtle.digest('SHA-1', data).then(function(buf) {
                        var arr = new Uint8Array(buf);
                        var hex = '';
                        for (var i = 0; i < arr.length; i++) {
                            hex += ('0' + arr[i].toString(16)).slice(-2);
                        }
                        return hex;
                    });
                }

                function generateSapiSidHash() {
                    var sapisid = getSapisidFromCookies();
                    if (!sapisid) return Promise.resolve('');
                    var ts = Math.floor(Date.now() / 1000);
                    var origin = 'https://www.youtube.com';
                    return sha1Hex(ts + ' ' + sapisid + ' ' + origin).then(function(hash) {
                        return 'SAPISIDHASH ' + ts + '_' + hash;
                    });
                }

                /* ── Build authenticated headers for InnerTube API ── */
                function getAuthHeaders() {
                    return generateSapiSidHash().then(function(authHash) {
                        var headers = { 'Content-Type': 'application/json' };
                        if (authHash) {
                            headers['Authorization'] = authHash;
                            console.log('[TapLink-YT] SAPISIDHASH auth header generated');
                        } else {
                            console.log('[TapLink-YT] No SAPISID cookie — request will be unauthenticated');
                        }
                        try { var si = ytcfg.get('SESSION_INDEX'); if (si !== undefined && si !== null) headers['X-Goog-AuthUser'] = String(si); } catch(e) {}
                        try { var pageCl = ytcfg.get('PAGE_CL'); if (pageCl) headers['X-Goog-PageId'] = String(pageCl); } catch(e) {}
                        try { var idTok = ytcfg.get('ID_TOKEN'); if (idTok) headers['X-Youtube-Identity-Token'] = idTok; } catch(e) {}
                        headers['X-Youtube-Client-Name'] = '1';
                        headers['X-Youtube-Client-Version'] = getClientVersion();
                        headers['Origin'] = 'https://www.youtube.com';
                        headers['Referer'] = 'https://www.youtube.com/';
                        return headers;
                    }).catch(function(e) {
                        console.warn('[TapLink-YT] Auth header generation failed:', e);
                        return { 'Content-Type': 'application/json' };
                    });
                }

                /* ── Build InnerTube request body with full client context ── */
                function buildBrowseBody(browseId) {
                    var body = {
                        browseId: browseId,
                        context: {
                            client: {
                                clientName: 'WEB',
                                clientVersion: getClientVersion(),
                                hl: 'en',
                                gl: 'US'
                            }
                        }
                    };
                    var vd = getVisitorData();
                    if (vd) body.context.client.visitorData = vd;
                    return body;
                }

                /* ── Fetch subscription video IDs via YouTube InnerTube browse API ── */
                function fetchSubscriptionIds() {
                    var apiKey = getInnertubeApiKey();
                    console.log('[TapLink-YT] InnerTube API key: ' + (apiKey ? apiKey.substring(0,8) + '...' : 'MISSING'));
                    if (!apiKey) return Promise.resolve([]);

                    return getAuthHeaders().then(function(headers) {
                        return fetch('https://www.youtube.com/youtubei/v1/browse?key=' + apiKey + '&prettyPrint=false', {
                            method: 'POST',
                            credentials: 'same-origin',
                            headers: headers,
                            body: JSON.stringify(buildBrowseBody('FEsubscriptions'))
                        });
                    })
                    .then(function(r) {
                        if (!r.ok) throw new Error('HTTP ' + r.status);
                        return r.text();
                    })
                    .then(function(text) {
                        var ids = extractVideoIdsFromJson(text);
                        console.log('[TapLink-YT] InnerTube subscriptions returned ' + ids.length + ' video IDs');
                        return ids;
                    })
                    .catch(function(e) {
                        console.error('[TapLink-YT] InnerTube subscriptions failed:', e);
                        return [];
                    });
                }

                /* ── Fetch history video IDs via YouTube InnerTube browse API ── */
                function fetchHistoryIds() {
                    var apiKey = getInnertubeApiKey();
                    console.log('[TapLink-YT] InnerTube API key (history): ' + (apiKey ? apiKey.substring(0,8) + '...' : 'MISSING'));
                    if (!apiKey) return Promise.resolve([]);

                    return getAuthHeaders().then(function(headers) {
                        return fetch('https://www.youtube.com/youtubei/v1/browse?key=' + apiKey + '&prettyPrint=false', {
                            method: 'POST',
                            credentials: 'same-origin',
                            headers: headers,
                            body: JSON.stringify(buildBrowseBody('FEhistory'))
                        });
                    })
                    .then(function(r) {
                        if (!r.ok) throw new Error('HTTP ' + r.status);
                        return r.text();
                    })
                    .then(function(text) {
                        var ids = extractVideoIdsFromJson(text);
                        console.log('[TapLink-YT] InnerTube history returned ' + ids.length + ' video IDs');
                        return ids;
                    })
                    .catch(function(e) {
                        console.error('[TapLink-YT] InnerTube history failed:', e);
                        return [];
                    });
                }

                /* ── Collect search IDs from page data (for search results pages) ── */
                function collectSearchIds() {
                    var ids = [], seen = {};
                    function addId(id) { if (id && id.length === 11 && !seen[id]) { seen[id]=true; ids.push(id); } }
                    // 1. ytInitialData
                    try {
                        if (window.ytInitialData) {
                            extractVideoIdsFromJson(JSON.stringify(window.ytInitialData)).forEach(addId);
                        }
                    } catch(e) {}
                    // 2. Script tags
                    if (ids.length < 5) {
                        try {
                            var scripts = document.querySelectorAll('script');
                            for (var k = 0; k < scripts.length; k++) {
                                var txt = scripts[k].textContent || '';
                                if (txt.indexOf('"videoId"') < 0) continue;
                                extractVideoIdsFromJson(txt).forEach(addId);
                            }
                        } catch(e) {}
                    }
                    // 3. DOM links
                    var allLinks = document.querySelectorAll('a[href*="/watch?v="]');
                    for (var j = 0; j < allLinks.length; j++) {
                        var vid = extractVideoIdFromHref(allLinks[j].getAttribute('href') || '');
                        if (vid) addId(vid);
                    }
                    return ids;
                }

                /* ── Collect feed entries in the exact DOM order shown to the user ── */
                function collectRenderedFeedEntries(feedType) {
                    var entries = [], seen = {};
                    function addEntry(id, title, tag) {
                        if (!id || id.length !== 11 || seen[id]) return;
                        seen[id] = true;
                        entries.push({ id: id, title: title || '', tag: tag || '' });
                    }
                    function collectFromSelectorList(selectorList) {
                        for (var s = 0; s < selectorList.length; s++) {
                            var items = document.querySelectorAll(selectorList[s]);
                            if (!items || !items.length) continue;
                            for (var i = 0; i < items.length; i++) {
                                var item = items[i];
                                var rect = item.getBoundingClientRect ? item.getBoundingClientRect() : null;
                                if (rect && rect.width <= 0 && rect.height <= 0) continue;
                                var link = item.querySelector('a#video-title[href*="/watch?v="], a#thumbnail[href*="/watch?v="], a[href*="/watch?v="]');
                                if (!link) continue;
                                var vid = extractVideoIdFromHref(link.getAttribute('href') || '');
                                if (!vid) continue;
                                var title = link.getAttribute('title') || link.textContent || '';
                                title = (title || '').replace(/\s+/g, ' ').trim();
                                addEntry(vid, title, item.tagName || selectorList[s]);
                            }
                            if (entries.length > 0) return entries;
                        }
                        return entries;
                    }

                    if (feedType === 'history') {
                        return collectFromSelectorList([
                            'ytd-browse[page-subtype="history"] ytd-rich-item-renderer',
                            'ytd-browse[page-subtype="history"] ytd-rich-grid-media',
                            'ytd-browse[page-subtype="history"] ytd-grid-video-renderer',
                            'ytd-browse[page-subtype="history"] ytd-item-section-renderer #contents ytd-video-renderer',
                            'ytd-browse[page-subtype="history"] #contents ytd-video-renderer',
                            'ytd-page-manager ytd-browse[page-subtype="history"] ytd-rich-grid-media',
                            'ytd-page-manager ytd-browse[page-subtype="history"] ytd-video-renderer'
                        ]);
                    }

                    if (feedType === 'subscriptions') {
                        return collectFromSelectorList([
                            'ytd-browse[page-subtype="subscriptions"] ytd-rich-item-renderer',
                            'ytd-browse[page-subtype="subscriptions"] ytd-rich-grid-media',
                            'ytd-browse[page-subtype="subscriptions"] ytd-grid-video-renderer'
                        ]);
                    }

                    return collectFromSelectorList([
                        'ytd-video-renderer',
                        'ytd-rich-item-renderer ytd-rich-grid-media',
                        'ytd-grid-video-renderer',
                        'ytd-rich-grid-media',
                        'ytd-playlist-panel-video-renderer'
                    ]);
                }

                function collectRenderedFeedIds(feedType) {
                    return collectRenderedFeedEntries(feedType).map(function(entry) { return entry.id; });
                }

                function logFeedSnapshot(sourceLabel, feedType, entries) {
                    try {
                        var browse = document.querySelector('ytd-browse');
                        var subtype = browse ? (browse.getAttribute('page-subtype') || '') : '';
                        console.log('[TapLink-YT] ' + sourceLabel + ' page subtype=' + subtype + ' feedType=' + feedType + ' entries=' + entries.length);
                        entries.slice(0, 10).forEach(function(entry, idx) {
                            console.log('[TapLink-YT] ' + sourceLabel + ' #' + (idx + 1) + ' ' + entry.id + ' [' + entry.tag + '] ' + entry.title);
                        });
                    } catch (e) {
                        console.log('[TapLink-YT] ' + sourceLabel + ' snapshot log failed: ' + e);
                    }
                }

                function collectRenderedFeedIdsWithScroll(sourceLabel, feedType, minIds, maxScrolls, callback) {
                    var pass = 0;
                    var stablePasses = 0;
                    var lastSignature = '';

                    function tick() {
                        var entries = collectRenderedFeedEntries(feedType);
                        var ids = entries.map(function(entry) { return entry.id; });
                        var signature = ids.slice(0, 8).join(',');
                        console.log('[TapLink-YT] ' + sourceLabel + ' DOM pass ' + pass + ': found ' + ids.length + ' videos');
                        logFeedSnapshot(sourceLabel + ' pass ' + pass, feedType, entries);

                        if (ids.length >= minIds) {
                            callback(ids);
                            return;
                        }

                        if (ids.length > 0) {
                            if (signature === lastSignature) stablePasses++; else stablePasses = 0;
                            if (stablePasses >= 2 || pass >= maxScrolls) {
                                callback(ids);
                                return;
                            }
                        } else if (pass >= maxScrolls) {
                            callback(ids);
                            return;
                        }

                        lastSignature = signature;
                        pass++;
                        window.scrollBy(0, Math.max(window.innerHeight * 1.5, 900));
                        setTimeout(tick, 1400);
                    }

                    setTimeout(tick, 1800);
                }

                function finishAndPlay(ids, sourceLabel) {
                    ids = ids.slice(0, 30);
                    console.log('[TapLink-YT] Final playlist (' + sourceLabel + '): ' + ids.length + ' videos');
                    console.log('[TapLink-YT] IDs: ' + ids.slice(0, 10).join(', '));
                    try {
                        var bridge = window.GroqBridge;
                        if (bridge && bridge.setYouTubePlaylist) {
                            bridge.setYouTubePlaylist(JSON.stringify(ids));
                        }
                    } catch(e) { console.log('[TapLink-YT] Bridge error: ' + e); }
                    location.href = 'https://www.youtube.com/watch?v=' + ids[0] + '&autoplay=1&cc_load_policy=1';
                }

                /* ── SUBSCRIPTIONS: prefer exact rendered feed order, fall back to API ── */
                if (wantsSubscriptions && !isWatch) {
                    if (isSubscriptions) {
                        console.log('[TapLink-YT] Collecting subscriptions from rendered feed order...');
                        collectRenderedFeedIdsWithScroll('subscriptions', 'subscriptions', 18, 6, function(feedIds) {
                            if (feedIds.length >= 1) {
                                finishAndPlay(feedIds, 'rendered subscriptions feed');
                                return;
                            }
                            console.log('[TapLink-YT] Rendered subscriptions feed was empty — falling back to InnerTube');
                            fetchSubscriptionIds().then(function(ids) {
                                if (ids.length >= 1) {
                                    finishAndPlay(ids, 'InnerTube subscriptions');
                                    return;
                                }
                                var fallbackIds = collectSearchIds();
                                if (fallbackIds.length >= 1) {
                                    finishAndPlay(fallbackIds, 'ytInitialData subscriptions fallback');
                                    return;
                                }
                                console.log('[TapLink-YT] No subscription videos found via any method');
                            });
                        });
                    } else {
                        console.log('[TapLink-YT] Fetching subscriptions via InnerTube API...');
                        fetchSubscriptionIds().then(function(ids) {
                            if (ids.length >= 1) {
                                finishAndPlay(ids, 'InnerTube subscriptions');
                                return;
                            }
                            var fallbackIds = collectSearchIds();
                            if (fallbackIds.length >= 1) {
                                finishAndPlay(fallbackIds, 'ytInitialData subscriptions fallback');
                                return;
                            }
                            console.log('[TapLink-YT] No subscription videos found via any method');
                        });
                    }
                    return;
                }

                /* ── HISTORY: prefer exact rendered history order, fall back to API ── */
                if (wantsHistory && !isWatch) {
                    if (isHistory) {
                        console.log('[TapLink-YT] Collecting history from rendered feed order...');
                        collectRenderedFeedIdsWithScroll('history', 'history', 12, 7, function(feedIds) {
                            if (feedIds.length >= 1) {
                                finishAndPlay(feedIds, 'rendered history feed');
                                return;
                            }
                            console.log('[TapLink-YT] Rendered history feed was empty — falling back to InnerTube');
                            fetchHistoryIds().then(function(ids) {
                                if (ids.length >= 1) {
                                    finishAndPlay(ids, 'InnerTube history');
                                    return;
                                }
                                var fallbackIds = collectSearchIds();
                                if (fallbackIds.length >= 1) {
                                    finishAndPlay(fallbackIds, 'ytInitialData history fallback');
                                    return;
                                }
                                console.log('[TapLink-YT] No history videos found via any method');
                            });
                        });
                    } else {
                        console.log('[TapLink-YT] Fetching history via InnerTube API...');
                        fetchHistoryIds().then(function(ids) {
                            if (ids.length >= 1) {
                                finishAndPlay(ids, 'InnerTube history');
                                return;
                            }
                            var fallbackIds = collectSearchIds();
                            if (fallbackIds.length >= 1) {
                                finishAndPlay(fallbackIds, 'ytInitialData history fallback');
                                return;
                            }
                            console.log('[TapLink-YT] No history videos found via any method');
                        });
                    }
                    return;
                }

                /* ── SEARCH PAGE: collect IDs from page data ── */
                if (isSearch) {
                    var scrollCount = 0;
                    var maxScrolls = 8;

                    function scrollAndCollect() {
                        var ids = collectSearchIds();
                        console.log('[TapLink-YT] Scroll ' + scrollCount + '/' + maxScrolls + ': found ' + ids.length + ' videos');

                        if (ids.length >= 20 || scrollCount >= maxScrolls) {
                            if (ids.length === 0) {
                                if (scrollCount < maxScrolls + 3) {
                                    scrollCount++;
                                    window.scrollBy(0, window.innerHeight * 2);
                                    setTimeout(scrollAndCollect, 2000);
                                    return;
                                }
                                console.log('[TapLink-YT] GAVE UP — no videos found');
                                return;
                            }
                            finishAndPlay(ids, 'search results');
                            return;
                        }

                        scrollCount++;
                        window.scrollBy(0, window.innerHeight * 2);
                        setTimeout(scrollAndCollect, 1500);
                    }

                    setTimeout(scrollAndCollect, 2000);
                    return;
                }

                /* ── WATCH PAGE: wait for playing → fullscreen → captions → hijack next ── */
                if (isWatch) {
                    console.log('[TapLink-YT] Watch page detected');

                    var fsDone = false;
                    var ccDone = false;
                    var nextHijacked = false;
                    var boundVideoEl = null;

                    /* ── Suppress the captions-on hint that flashes on load ──
                     *
                     * When we programmatically toggle the CC button (sometimes
                     * 2-3 times during the rearm cycle), YouTube responds with
                     * a transient overlay reading "English / Click ⚙ for
                     * settings". Each rearm flashes another copy. The user
                     * has explicitly opted into captions-by-default, so this
                     * promo offers no information and only adds visual noise.
                     *
                     * We attack it three ways so we catch it whatever YouTube
                     * version is loaded:
                     *
                     *   1. CSS rule on .ytp-popup-promo (classic class name).
                     *   2. CSS rule on the .ytp-bezel transient toast WHEN
                     *      its text content matches the captions-hint phrase
                     *      — implemented via JS, not CSS, since CSS can't
                     *      match by text.
                     *   3. A MutationObserver watching for either element
                     *      type appearing in the DOM, hiding it the instant
                     *      it's added (before the first paint shows it).
                     *
                     * We auto-disconnect after 20s so the rest of the
                     * player's bezel toasts (volume, brightness, seek) keep
                     * working normally for the rest of the session.
                     */
                    (function suppressCaptionsHint() {
                        try {
                            if (document.getElementById('__tl_cc_hint_style')) return;
                            var s = document.createElement('style');
                            s.id = '__tl_cc_hint_style';
                            s.textContent =
                                '.ytp-popup-promo,' +
                                '.ytp-cards-button-icon-default,' +
                                '.iv-promo,' +
                                '.iv-promo-base{display:none!important;visibility:hidden!important;opacity:0!important}';
                            document.head.appendChild(s);
                        } catch (e) {}
                        var HINT_RX = /for settings|click[\s\S]*?settings/i;
                        function killNode(n) {
                            try {
                                n.style.setProperty('display','none','important');
                                n.style.setProperty('visibility','hidden','important');
                                n.style.setProperty('opacity','0','important');
                                // Also hide a known popup/promo/bezel ancestor so we
                                // don't leave an empty box behind the hidden text.
                                var anc = n.closest && n.closest('.ytp-popup-promo,.ytp-popup,.ytp-bezel,.ytp-tooltip,[class*="promo"]');
                                if (anc && anc !== n) {
                                    anc.style.setProperty('display','none','important');
                                    anc.style.setProperty('visibility','hidden','important');
                                }
                            } catch (e) {}
                        }
                        function hideMatchingNodes(root) {
                            try {
                                // Match by TEXT regardless of class — the promo's
                                // class name varies by YouTube build (it slipped
                                // past the old fixed selector list). Scan the node
                                // itself plus every YouTube-player element under it,
                                // and hide any whose SHORT text matches the hint.
                                // Bounding the length avoids nuking big containers
                                // and the real caption text (captions never say
                                // "for settings").
                                var candidates = [];
                                if (root.nodeType === 1) candidates.push(root);
                                var els = root.querySelectorAll
                                    ? root.querySelectorAll('[class*="ytp-"],[class*="promo"],[class*="popup"],[class*="tooltip"],[class*="bezel"]')
                                    : [];
                                for (var q = 0; q < els.length; q++) candidates.push(els[q]);
                                for (var i = 0; i < candidates.length; i++) {
                                    var n = candidates[i];
                                    var txt = '';
                                    try { txt = (n.innerText || n.textContent || '').trim(); } catch (e) {}
                                    if (txt && txt.length < 90 && HINT_RX.test(txt)) {
                                        killNode(n);
                                    }
                                }
                            } catch (e) {}
                        }
                        // Initial sweep + tick for the first 10s.
                        hideMatchingNodes(document);
                        var ticks = 0;
                        var iid = setInterval(function() {
                            hideMatchingNodes(document);
                            if (++ticks > 100) { try { clearInterval(iid); } catch (e) {} }
                        }, 100);
                        // Observer for instant suppression on insertion.
                        try {
                            if (window.MutationObserver) {
                                var mo = new MutationObserver(function(muts) {
                                    for (var i = 0; i < muts.length; i++) {
                                        var added = muts[i].addedNodes;
                                        if (!added) continue;
                                        for (var j = 0; j < added.length; j++) {
                                            var node = added[j];
                                            if (node && node.nodeType === 1) hideMatchingNodes(node);
                                        }
                                    }
                                });
                                mo.observe(document.body || document.documentElement,
                                           { childList: true, subtree: true });
                                // Keep watching for the lifetime of the page —
                                // the promo re-appears every time captions are
                                // (re)enabled during playback, well past the old
                                // 20s window, which is why it "kept flashing".
                                setTimeout(function() { try { mo.disconnect(); } catch (e) {} }, 600000);
                            }
                        } catch (e) {}
                    })();

                    /* ── CSS FULLSCREEN ──
                       Since WebView blocks ALL programmatic fullscreen (user gesture
                       required), we use CSS injection to make the video fill the
                       viewport and have Kotlin enter immersive mode. No tap/key
                       simulation needed. Works reliably. */

                    function enterCssFullscreen() {
                        if (fsDone) return;
                        // Don't auto-enter CSS fullscreen if user manually chose a different view mode
                        if (typeof window.__tl_view_mode !== 'undefined' && window.__tl_view_mode !== 0) {
                            console.log('[TapLink-YT] Skipping auto CSS fs — user chose view mode ' + window.__tl_view_mode);
                            fsDone = true;
                            return;
                        }
                        fsDone = true;
                        console.log('[TapLink-YT] Entering CSS fullscreen mode');
                        try { window.GroqBridge.enterCssFullscreen(); } catch(e) {
                            console.log('[TapLink-YT] enterCssFullscreen bridge failed: ' + e);
                        }
                    }

                    /* Wait for video playback, then enter CSS fullscreen after 2s */
                    var videoCheckCount = 0;
                    function waitForVideoPlaying() {
                        var v = document.querySelector('video');
                        if (v) {
                            console.log('[TapLink-YT] Video found: paused=' + v.paused + ' readyState=' + v.readyState + ' currentTime=' + v.currentTime.toFixed(1));
                            if (!v.paused && v.readyState >= 3 && v.currentTime > 0.5) {
                                console.log('[TapLink-YT] Video playing (t=' + v.currentTime.toFixed(1) + ') → CSS fullscreen in 2s');
                                setTimeout(enterCssFullscreen, 2000);
                                return;
                            }
                            var started = false;
                            function onTimeUpdate() {
                                if (started) return;
                                if (v.currentTime > 0.5 && !v.paused) {
                                    started = true;
                                    v.removeEventListener('timeupdate', onTimeUpdate);
                                    console.log('[TapLink-YT] Video timeupdate confirms playback (t=' + v.currentTime.toFixed(1) + ') → CSS fullscreen in 2s');
                                    setTimeout(enterCssFullscreen, 2000);
                                }
                            }
                            v.addEventListener('timeupdate', onTimeUpdate);
                            if (v.paused) {
                                v.play().catch(function(e) {
                                    v.muted = true;
                                    v.play().catch(function(){});
                                });
                            }
                            if (v.muted) v.muted = false;
                            setTimeout(function() {
                                if (!started && !fsDone) {
                                    started = true;
                                    v.removeEventListener('timeupdate', onTimeUpdate);
                                    console.log('[TapLink-YT] SAFETY: 15s elapsed, forcing CSS fullscreen');
                                    enterCssFullscreen();
                                }
                            }, 15000);
                            return;
                        }
                        videoCheckCount++;
                        if (videoCheckCount < 40) {
                            if (videoCheckCount % 10 === 0) console.log('[TapLink-YT] Waiting for video element... attempt ' + videoCheckCount);
                            setTimeout(waitForVideoPlaying, 300);
                        }
                    }
                    waitForVideoPlaying();

                    /* ── NAV BUTTONS: inject immediately (buttons go on document.body) ── */
                    try { window.GroqBridge.injectNavButtons(); } catch(e) {
                        console.log('[TapLink-YT] injectNavButtons bridge failed: ' + e);
                    }

                    /* ── CC ── */
                    function captionTrackLoaded(videoEl) {
                        try {
                            var tracks = videoEl && videoEl.textTracks ? videoEl.textTracks : null;
                            if (!tracks) return false;
                            for (var i = 0; i < tracks.length; i++) {
                                var track = tracks[i];
                                if (!track || track.mode !== 'showing') continue;
                                var activeCueCount = 0;
                                var totalCueCount = 0;
                                try { activeCueCount = track.activeCues ? track.activeCues.length : 0; } catch (e) {}
                                try { totalCueCount = track.cues ? track.cues.length : 0; } catch (e) {}
                                if (activeCueCount > 0 || totalCueCount > 0) return true;
                            }
                        } catch (e) {}
                        return false;
                    }

                    function captionsReady(videoEl) {
                        var nodes = document.querySelectorAll('.ytp-caption-segment, .captions-text, .caption-window, .ytp-caption-window-container');
                        for (var i = 0; i < nodes.length; i++) {
                            var text = '';
                            try { text = (nodes[i].innerText || nodes[i].textContent || '').trim(); } catch (e) {}
                            if (text) return true;
                        }
                        return captionTrackLoaded(videoEl);
                    }

                    function primeCaptions(videoEl) {
                        var trackLoaded = false;
                        // (a) Legacy textTracks nudge.
                        try {
                            var tracks = videoEl && videoEl.textTracks ? videoEl.textTracks : null;
                            if (tracks) {
                                for (var i = 0; i < tracks.length; i++) {
                                    if (!tracks[i]) continue;
                                    try { tracks[i].mode = 'showing'; } catch (e) {}
                                    if (tracks[i].mode === 'showing') {
                                        var activeCueCount = 0;
                                        var totalCueCount = 0;
                                        try { activeCueCount = tracks[i].activeCues ? tracks[i].activeCues.length : 0; } catch (e) {}
                                        try { totalCueCount = tracks[i].cues ? tracks[i].cues.length : 0; } catch (e) {}
                                        if (activeCueCount > 0 || totalCueCount > 0) trackLoaded = true;
                                    }
                                }
                            }
                        } catch (e) {}
                        // (b) YouTube player API — the reliable path. Load the
                        // captions module, enumerate tracks, and explicitly
                        // select one (prefer English). This is what finally
                        // causes captions to actually RENDER when cc_load_policy=1
                        // leaves the button visually "on" but the track silent.
                        try {
                            var player = document.getElementById('movie_player');
                            if (player) {
                                if (player.loadModule) { try { player.loadModule('captions'); } catch(e) {} }
                                if (player.loadModule) { try { player.loadModule('cc'); } catch(e) {} }
                                var tracklist = [];
                                try { tracklist = player.getOption ? (player.getOption('captions', 'tracklist') || []) : []; } catch(e) {}
                                if (!tracklist || tracklist.length === 0) {
                                    try { tracklist = player.getOption ? (player.getOption('cc', 'tracklist') || []) : []; } catch(e) {}
                                }
                                if (tracklist && tracklist.length > 0) {
                                    var pick = null;
                                    for (var j = 0; j < tracklist.length; j++) {
                                        var lc = ((tracklist[j].languageCode || '') + '').toLowerCase();
                                        if (lc === 'en' || lc.indexOf('en-') === 0) { pick = tracklist[j]; break; }
                                    }
                                    if (!pick) pick = tracklist[0];
                                    try { if (player.setOption) player.setOption('captions', 'track', pick); } catch(e) {}
                                    try { if (player.setOption) player.setOption('cc',       'track', pick); } catch(e) {}
                                    try { if (player.setOption) player.setOption('captions', 'reload', true); } catch(e) {}
                                    trackLoaded = true;
                                }
                            }
                        } catch (e) {}
                        return trackLoaded;
                    }

                    /* Force an OFF → ON cycle with a dwell long enough that
                     * YouTube actually applies the off state before we re-enable.
                     * Shorter dwells can be swallowed (YouTube treats two fast
                     * clicks as one), which is exactly what leaves the user
                     * needing to click twice by hand. */
                    function rearmCaptionsButton() {
                        if (window.__taplink_cc_rearm_pending) return;
                        window.__taplink_cc_rearm_pending = true;
                        setTimeout(function() {
                            var retryBtn = document.querySelector('.ytp-subtitles-button');
                            if (!retryBtn) { window.__taplink_cc_rearm_pending = false; return; }
                            var retryPressed = retryBtn.getAttribute('aria-pressed') === 'true';
                            if (retryPressed) retryBtn.click();
                            setTimeout(function() {
                                var finalBtn = document.querySelector('.ytp-subtitles-button');
                                if (finalBtn && finalBtn.getAttribute('aria-pressed') !== 'true') {
                                    finalBtn.click();
                                }
                                // After the re-click, force the player API to
                                // actually pick a caption track — clicking
                                // alone sometimes still leaves no track.
                                setTimeout(function() {
                                    primeCaptions(document.querySelector('video'));
                                    window.__taplink_cc_rearm_pending = false;
                                }, 260);
                            }, 520);
                        }, 260);
                    }

                    function enableCC() {
                        var ccBtn = document.querySelector('.ytp-subtitles-button');
                        if (!ccBtn) return;
                        var v = document.querySelector('video');
                        var pressed = ccBtn.getAttribute('aria-pressed') === 'true';
                        var ready = captionsReady(v);

                        if (ccDone && pressed && ready) {
                            return;
                        }

                        if (pressed && ready) {
                            ccDone = true;
                            return;
                        }

                        if (!pressed) {
                            ccBtn.click();
                            console.log('[TapLink-YT] CC enable requested');
                            setTimeout(function() {
                                primeCaptions(document.querySelector('video'));
                            }, 180);
                            ccDone = false;
                            return;
                        }

                        // Button is pressed but captions are NOT actually
                        // rendering. Try the player-API priming first — it's
                        // cheap, and if a tracklist is available it will force
                        // a track. If that doesn't take within a tick, rearm.
                        var primed = primeCaptions(v);
                        if (primed) {
                            // Give the player a beat to render, then verify.
                            setTimeout(function() {
                                if (!captionsReady(document.querySelector('video'))) {
                                    rearmCaptionsButton();
                                }
                            }, 350);
                            return;
                        }
                        var retryCount = window.__taplink_cc_retry_count || 0;
                        if (retryCount < 12) {
                            window.__taplink_cc_retry_count = retryCount + 1;
                            window.__taplink_cc_last_retry_ms = Date.now();
                            console.log('[TapLink-YT] CC button on but no captions — rearming attempt ' + (retryCount + 1));
                            rearmCaptionsButton();
                        } else {
                            var lastRetryMs = window.__taplink_cc_last_retry_ms || 0;
                            if (Date.now() - lastRetryMs > 3000) {
                                console.log('[TapLink-YT] CC still not ready — starting another retry cycle');
                                window.__taplink_cc_retry_count = 0;
                            }
                        }
                    }

                    /* ── ENSURE PLAY (only until playback first starts) ──
                       Uses window-level flag so re-injections don't reset it. */
                    function ensurePlay() {
                        if (window.__taplink_playback_started) return;
                        var v = document.querySelector('video');
                        if (!v) return;
                        if (v.muted) v.muted = false;
                        if (!v.paused && v.currentTime > 0.5) {
                            window.__taplink_playback_started = true;
                            console.log('[TapLink-YT] Playback confirmed, ensurePlay disabled');
                            return;
                        }
                        if (v.paused) v.play().catch(function(){});
                    }

                    /* ── HIJACK NEXT BUTTON to use our playlist ── */
                    function hijackNextButton() {
                        if (nextHijacked) return;
                        var nb = document.querySelector('.ytp-next-button');
                        if (!nb) return;
                        nextHijacked = true;
                        var clone = nb.cloneNode(true);
                        nb.parentNode.replaceChild(clone, nb);
                        clone.addEventListener('click', function(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            e.stopImmediatePropagation();
                            console.log('[TapLink-YT] Next button → TapLink playlist');
                            try { window.GroqBridge.playNextInPlaylist(); }
                            catch(err) { console.log('[TapLink-YT] Bridge error: ' + err); }
                        }, true);
                        console.log('[TapLink-YT] Next button hijacked');
                    }

                    /* ── AUTO-ADVANCE when video ends ── */
                    function bindEnded() {
                        var v = document.querySelector('video');
                        if (!v || v === boundVideoEl) return;
                        boundVideoEl = v;
                        v.addEventListener('ended', function() {
                            console.log('[TapLink-YT] Video ended — playing next');
                            try { window.GroqBridge.playNextInPlaylist(); }
                            catch(e) { console.log('[TapLink-YT] Bridge error: ' + e); }
                        });
                        console.log('[TapLink-YT] ended listener bound');
                    }

                    /* Periodic tick for CC, play, hijack, ended.
                       Fullscreen is handled separately by the 'playing' event. */
                    var watchAttempts = 0;
                    function tick() {
                        enableCC();
                        ensurePlay();
                        hijackNextButton();
                        bindEnded();
                        watchAttempts++;
                        if (watchAttempts < 45) setTimeout(tick, 1000);
                    }
                    setTimeout(tick, 1000);
                }
            })();
        """.trimIndent()
    }

    /**
     * Lightweight watch-page script for when a YouTube watch URL is opened
     * directly (e.g. via taplink_playlist=1). Enables captions, unmutes,
     * and adds the floating replay button.
     */
    private fun buildYouTubeWatchAutomationScript(): String {
        return """
            (function(){
                if (window.__taplink_watch_injected) return;
                window.__taplink_watch_injected = true;
                console.log('[TapLink-YT] Watch automation script injected');

                var fsDone = false;
                var ccDone = false;
                var nextHijacked = false;
                var boundVideoEl = null;

                /* ── FULLSCREEN: wait 8s for YouTube to settle, then try webkitEnterFullscreen or native tap ── */
                document.addEventListener('fullscreenchange', function() {
                    if (document.fullscreenElement) { fsDone = true; }
                });
                document.addEventListener('webkitfullscreenchange', function() {
                    if (document.webkitFullscreenElement) { fsDone = true; }
                });
                /* CSS FULLSCREEN — same approach as bootstrap */
                function enterCssFs() {
                    if (fsDone) return;
                    if (typeof window.__tl_view_mode !== 'undefined' && window.__tl_view_mode !== 0) {
                        console.log('[TapLink-YT] watch: skipping auto CSS fs — user chose view ' + window.__tl_view_mode);
                        fsDone = true;
                        return;
                    }
                    fsDone = true;
                    console.log('[TapLink-YT] watch: entering CSS fullscreen');
                    try { window.GroqBridge.enterCssFullscreen(); } catch(e) {}
                }
                var vc = 0;
                function waitForPlaying() {
                    var v = document.querySelector('video');
                    if (v) {
                        if (!v.paused && v.readyState >= 3 && v.currentTime > 0.5) {
                            console.log('[TapLink-YT] watch: video playing (t=' + v.currentTime.toFixed(1) + ') → CSS fs in 2s');
                            setTimeout(enterCssFs, 2000);
                            return;
                        }
                        var started = false;
                        function onTime() {
                            if (started) return;
                            if (v.currentTime > 0.5 && !v.paused) {
                                started = true;
                                v.removeEventListener('timeupdate', onTime);
                                console.log('[TapLink-YT] watch: timeupdate (t=' + v.currentTime.toFixed(1) + ') → CSS fs in 2s');
                                setTimeout(enterCssFs, 2000);
                            }
                        }
                        v.addEventListener('timeupdate', onTime);
                        if (v.paused) v.play().catch(function(){});
                        if (v.muted) v.muted = false;
                        setTimeout(function() {
                            if (!started && !fsDone) { started = true; v.removeEventListener('timeupdate', onTime); enterCssFs(); }
                        }, 15000);
                        return;
                    }
                    vc++;
                    if (vc < 40) setTimeout(waitForPlaying, 300);
                }
                waitForPlaying();

                /* ── NAV BUTTONS: inject immediately (buttons go on document.body) ── */
                try { window.GroqBridge.injectNavButtons(); } catch(e) {}

                function captionTrackLoaded(videoEl) {
                    try {
                        var tracks = videoEl && videoEl.textTracks ? videoEl.textTracks : null;
                        if (!tracks) return false;
                        for (var i = 0; i < tracks.length; i++) {
                            var track = tracks[i];
                            if (!track || track.mode !== 'showing') continue;
                            var activeCueCount = 0;
                            var totalCueCount = 0;
                            try { activeCueCount = track.activeCues ? track.activeCues.length : 0; } catch (e) {}
                            try { totalCueCount = track.cues ? track.cues.length : 0; } catch (e) {}
                            if (activeCueCount > 0 || totalCueCount > 0) return true;
                        }
                    } catch (e) {}
                    return false;
                }
                function captionsReady(videoEl) {
                    var nodes = document.querySelectorAll('.ytp-caption-segment, .captions-text, .caption-window, .ytp-caption-window-container');
                    for (var i = 0; i < nodes.length; i++) {
                        var text = '';
                        try { text = (nodes[i].innerText || nodes[i].textContent || '').trim(); } catch (e) {}
                        if (text) return true;
                    }
                    return captionTrackLoaded(videoEl);
                }
                function primeCaptions(videoEl) {
                    var trackLoaded = false;
                    try {
                        var tracks = videoEl && videoEl.textTracks ? videoEl.textTracks : null;
                        if (tracks) {
                            for (var i = 0; i < tracks.length; i++) {
                                if (!tracks[i]) continue;
                                try { tracks[i].mode = 'showing'; } catch (e) {}
                                if (tracks[i].mode === 'showing') {
                                    var activeCueCount = 0;
                                    var totalCueCount = 0;
                                    try { activeCueCount = tracks[i].activeCues ? tracks[i].activeCues.length : 0; } catch (e) {}
                                    try { totalCueCount = tracks[i].cues ? tracks[i].cues.length : 0; } catch (e) {}
                                    if (activeCueCount > 0 || totalCueCount > 0) trackLoaded = true;
                                }
                            }
                        }
                    } catch (e) {}
                    try {
                        var player = document.getElementById('movie_player');
                        if (player) {
                            if (player.loadModule) { try { player.loadModule('captions'); } catch(e) {} }
                            if (player.loadModule) { try { player.loadModule('cc'); } catch(e) {} }
                            var tracklist = [];
                            try { tracklist = player.getOption ? (player.getOption('captions', 'tracklist') || []) : []; } catch(e) {}
                            if (!tracklist || tracklist.length === 0) {
                                try { tracklist = player.getOption ? (player.getOption('cc', 'tracklist') || []) : []; } catch(e) {}
                            }
                            if (tracklist && tracklist.length > 0) {
                                var pick = null;
                                for (var j = 0; j < tracklist.length; j++) {
                                    var lc = ((tracklist[j].languageCode || '') + '').toLowerCase();
                                    if (lc === 'en' || lc.indexOf('en-') === 0) { pick = tracklist[j]; break; }
                                }
                                if (!pick) pick = tracklist[0];
                                try { if (player.setOption) player.setOption('captions', 'track', pick); } catch(e) {}
                                try { if (player.setOption) player.setOption('cc',       'track', pick); } catch(e) {}
                                try { if (player.setOption) player.setOption('captions', 'reload', true); } catch(e) {}
                                trackLoaded = true;
                            }
                        }
                    } catch (e) {}
                    return trackLoaded;
                }
                function rearmCaptionsButton() {
                    if (window.__taplink_watch_cc_rearm_pending) return;
                    window.__taplink_watch_cc_rearm_pending = true;
                    setTimeout(function() {
                        var retryBtn = document.querySelector('.ytp-subtitles-button');
                        if (retryBtn) {
                            var retryPressed = retryBtn.getAttribute('aria-pressed') === 'true';
                            if (retryPressed) retryBtn.click();
                            setTimeout(function() {
                                var finalBtn = document.querySelector('.ytp-subtitles-button');
                                if (finalBtn && finalBtn.getAttribute('aria-pressed') !== 'true') {
                                    finalBtn.click();
                                }
                                setTimeout(function() {
                                    primeCaptions(document.querySelector('video'));
                                    window.__taplink_watch_cc_rearm_pending = false;
                                }, 260);
                            }, 520);
                            return;
                        }
                        window.__taplink_watch_cc_rearm_pending = false;
                    }, 260);
                }
                function enableCC() {
                    var btn = document.querySelector('.ytp-subtitles-button');
                    if (!btn) return;
                    var v = document.querySelector('video');
                    var pressed = btn.getAttribute('aria-pressed') === 'true';
                    var ready = captionsReady(v);
                    if (ccDone && pressed && ready) {
                        return;
                    }
                    if (pressed) {
                        ready = primeCaptions(v) || ready;
                        if (ready) {
                            ccDone = true;
                            return;
                        }
                    }
                    if (!pressed) {
                        btn.click();
                        setTimeout(function() {
                            primeCaptions(document.querySelector('video'));
                        }, 180);
                        ccDone = false;
                        return;
                    }
                    // Pressed but captions not rendering — rearm regardless of playback readiness,
                    // because cc_load_policy=1 leaves the button aria-pressed=true without actually
                    // loading a track on first navigation.
                    var retryCount = window.__taplink_watch_cc_retry_count || 0;
                    if (retryCount < 12) {
                        window.__taplink_watch_cc_retry_count = retryCount + 1;
                        window.__taplink_watch_cc_last_retry_ms = Date.now();
                        rearmCaptionsButton();
                    } else {
                        var lastRetryMs = window.__taplink_watch_cc_last_retry_ms || 0;
                        if (Date.now() - lastRetryMs > 3000) {
                            window.__taplink_watch_cc_retry_count = 0;
                        }
                    }
                }
                function ensurePlay() {
                    if (window.__taplink_playback_started) return;
                    var v = document.querySelector('video');
                    if (!v) return;
                    if (v.muted) v.muted = false;
                    if (!v.paused && v.currentTime > 0.5) {
                        window.__taplink_playback_started = true;
                        return;
                    }
                    if (v.paused) v.play().catch(function(){});
                }
                function hijackNextButton() {
                    if (nextHijacked) return;
                    var nb = document.querySelector('.ytp-next-button');
                    if (!nb) return;
                    nextHijacked = true;
                    var clone = nb.cloneNode(true);
                    nb.parentNode.replaceChild(clone, nb);
                    clone.addEventListener('click', function(e) {
                        e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
                        try { window.GroqBridge.playNextInPlaylist(); } catch(err) {}
                    }, true);
                }
                function bindEnded() {
                    var v = document.querySelector('video');
                    if (!v || v === boundVideoEl) return;
                    boundVideoEl = v;
                    v.addEventListener('ended', function() {
                        try { window.GroqBridge.playNextInPlaylist(); } catch(e) {}
                    });
                }

                var attempts = 0;
                function tick() {
                    enableCC(); ensurePlay(); hijackNextButton(); bindEnded();
                    attempts++;
                    if (attempts < 45) setTimeout(tick, 1000);
                }
                setTimeout(tick, 1000);
            })();
        """.trimIndent()
    }

    // Add method to handle hyperlink button press
    override fun onHyperlinkPressed() {
        DebugLog.d("LinkEditing", "onHyperlinkPressed called")
        dualWebViewGroup.showLinkEditing()
    }

    override fun onNavigationForwardPressed() {
        if (webView.canGoForward()) {
            webView.goForward()
        }
    }

    override fun onPause() {
        super.onPause()
        stopCloudTts()

        if (nativeQrScannerView != null || isQrScanInProgress) {
            isQrScanInProgress = false
            pendingNativeQrStart = false
            stopNativeQrScannerOverlay()
        }

        try {
            unregisterReceiver(notificationReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }

        if (isAnchored) {
            // Just unregister the sensor listener to save resources
            sensorManager.unregisterListener(sensorEventListener)
        }

        // IMPORTANT: Do NOT pause YouTube/radio media on onPause.
        //
        // Pressing the sleep button on the RayNeo X3 Pro triggers onPause here (the OS powers
        // down the display), but the user is still wearing the glasses and expects audio to
        // keep playing. The previous isScreenMasked() gate was insufficient — isScreenMasked()
        // only returns true when the user explicitly taps the mask toggle, NOT when the power
        // button is pressed, so any sleep-button press was muting audio. Android's audio focus
        // system will handle cleanup if the user actually leaves the app; we do not need to
        // proactively kill our own playback here.
        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.setHostPaused(true)
            // Keep a lightweight snapshot on pause so projector-off/resume does not block audio.
            dualWebViewGroup.saveWindowMetadataState()
        }
    }

    override fun onResume() {
        super.onResume()

        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.setHostPaused(false)
            if (webViewsPausedForReturnToChat) {
                webViewsPausedForReturnToChat = false
                dualWebViewGroup.getAllWebViews().forEach { wv ->
                    wv.onResume()
                    // Clear the muteguard installed by killAllWebViewAudio
                    // so the next play action on this WebView actually
                    // produces audio. Without this, after one
                    // exit-during-load → return cycle the user would
                    // hit a silently-broken player on subsequent visits.
                    wv.evaluateJavascript(
                        "(function(){try{" +
                            "window.__taplink_muteGuard=false;" +
                            "if(window.__taplink_muteGuardObserver){" +
                                "try{window.__taplink_muteGuardObserver.disconnect();}catch(e){}" +
                                "window.__taplink_muteGuardObserver=null;" +
                            "}" +
                        "}catch(e){}})();",
                        null
                    )
                }
            }
        }

        // Register notification receiver
        val filter = IntentFilter(NotificationService.ACTION_NOTIFICATION_POSTED)
        ContextCompat.registerReceiver(
                this,
                notificationReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Restart mirroring to right eye
        dualWebViewGroup.startRefreshing()
        syncActiveBrowserChrome(webView)
        syncTapRadioPlaybackUi()

        // Check for notification listener permission

        if (isAnchored) {
            // Re-register the sensor listener
            rotationSensor?.let { sensor ->
                sensorManager.registerListener(
                        sensorEventListener,
                        sensor,
                        SensorManager.SENSOR_DELAY_UI
                )
            }
        }
    }

    private fun syncTapRadioPlaybackUi() {
        if (!::dualWebViewGroup.isInitialized) return
        syncNativeRadioToolbarState(scheduleDelayedBroadcasts = false)
        val targetPages = setOf("radio.html", "podcasts.html")
        // Sync at multiple intervals to ensure TapRadio pages receive the state
        // even if the page is still loading during the first attempt.
        val syncAction = Runnable {
            dualWebViewGroup.getAllWebViews().forEach { candidate ->
                val url = candidate.url.orEmpty()
                if (targetPages.none { url.contains(it, ignoreCase = true) }) return@forEach
                candidate.post {
                    candidate.evaluateJavascript(
                        "(function(){if(window.tapRadioSyncPlaybackUi){window.tapRadioSyncPlaybackUi();}})();",
                        null
                    )
                }
            }
            dualWebViewGroup.refreshMaskedNowPlaying()
        }
        syncAction.run()
        uiHandler.postDelayed(syncAction, 300L)
        uiHandler.postDelayed(syncAction, 1200L)
        uiHandler.postDelayed(syncAction, 2600L)
    }

    private fun syncActiveBrowserChrome(
            targetWebView: WebView = webView,
            includeDelayedPasses: Boolean = true
    ) {
        if (!::dualWebViewGroup.isInitialized) return

        fun performSync() {
            if (!dualWebViewGroup.isActiveWebView(targetWebView)) return
            injectJavaScriptForInputFocus(targetWebView)
            dualWebViewGroup.clearExternalScrollMetrics()
            dualWebViewGroup.injectPageObservers(targetWebView)
            dualWebViewGroup.updateScrollBarsVisibility()
            if (isKeyboardVisible) {
                notifyKeyboardStateToWebView(targetWebView, true)
            }
            targetWebView.evaluateJavascript(
                    """
                (function() {
                    try {
                        if (window.__taplinkReportScroll) {
                            window.__taplinkReportScroll();
                            if (window.__taplinkWarmupScroll) {
                                window.__taplinkWarmupScroll();
                            }
                        }
                    } catch (e) {
                        console.warn('[TapLink] scroll sync failed:', e);
                    }
                })();
                """.trimIndent(),
                    null
            )
        }

        targetWebView.post { performSync() }
        if (!includeDelayedPasses) return

        longArrayOf(250L, 750L, 1500L).forEach { delayMs ->
            uiHandler.postDelayed({ performSync() }, delayMs)
        }
    }

    private fun syncNativeRadioToolbarState(scheduleDelayedBroadcasts: Boolean = false) {
        if (!::dualWebViewGroup.isInitialized) return
        applyNativeRadioPlaybackUiState(scheduleDelayedBroadcasts = scheduleDelayedBroadcasts)
    }

    fun getLastLocation(): Pair<Double, Double>? {
        return if (lastGpsLat != null && lastGpsLon != null) {
            Pair(lastGpsLat!!, lastGpsLon!!)
        } else {
            null
        }
    }

    private fun ensureGpsUpdates() {
        if (gpsUpdatesRegistered) return

        if (ipcLauncher == null) {
            ipcLauncher = Launcher.getInstance(this)
        }
        ipcLauncher?.addOnResponseListener(gpsResponseListener)
        GPSIPCHelper.registerGPSInfo(this)
        gpsUpdatesRegistered = true
    }

    private fun noteGeolocationUse() {
        lastGpsRequestAt = SystemClock.elapsedRealtime()
        ensureGpsUpdates()

        gpsStopRunnable?.let { gpsHandler.removeCallbacks(it) }
        gpsStopRunnable =
                object : Runnable {
                    override fun run() {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastGpsRequestAt >= GPS_IDLE_TIMEOUT_MS) {
                            stopGpsUpdates()
                        } else {
                            gpsHandler.postDelayed(this, GPS_IDLE_TIMEOUT_MS)
                        }
                    }
                }
        gpsHandler.postDelayed(gpsStopRunnable!!, GPS_IDLE_TIMEOUT_MS)
    }

    private fun stopGpsUpdates() {
        if (!gpsUpdatesRegistered) return

        GPSIPCHelper.unRegisterGPSInfo(this)
        ipcLauncher?.removeOnResponseListener(gpsResponseListener)
        ipcLauncher?.disConnect()
        ipcLauncher = null
        gpsUpdatesRegistered = false
        gpsStopRunnable?.let { gpsHandler.removeCallbacks(it) }
        gpsStopRunnable = null
    }

    override fun getCurrentUrl(): String {
        return dualWebViewGroup.getWebView().url ?: Constants.DEFAULT_URL
    }

    fun openUrlInNewTab(url: String) {
        if (!::dualWebViewGroup.isInitialized) return
        val formattedUrl = formatUrl(url)
        val newWebView = dualWebViewGroup.createNewWindow()
        if (dualWebViewGroup.isChatVisible()) {
            closeChatOnNextPageStart = true
            closeChatOnNextPageStartDeadlineMs = SystemClock.uptimeMillis() + 5000L
        }
        newWebView.loadUrl(formattedUrl)
    }

    fun getActiveWebViewUrlOrNull(): String? {
        if (!::dualWebViewGroup.isInitialized) return null
        return dualWebViewGroup.getWebView().url
    }

    override fun onBookmarkSelected(url: String) {
        val formattedUrl =
                when {
                    // Check for file: protocol specifically
                    url.startsWith("file:", ignoreCase = true) -> url
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    url.contains(".") -> "https://$url"
                    else -> "https://www.google.com/search?q=${Uri.encode(url)}"
                }
        webView.loadUrl(formattedUrl)
    }

    private fun handleMaskToggle() {
        // Close settings menu if open to prevent state desync
        dualWebViewGroup.hideSettings()

        // de-anchor when masking to avoid issues
        if (isAnchored) {
            toggleAnchor()
        }

        // Store current cursor state before masking
        preMaskCursorState = isCursorVisible
        preMaskCursorX = lastCursorX
        preMaskCursorY = lastCursorY

        // Hide cursor
        isCursorVisible = false
        cursorLeftView.visibility = View.GONE
        cursorRightView.visibility = View.GONE
        refreshCursor(false)

        // Mask the screen — and hide the HUD overlay so dim mode is fully dark.
        dualWebViewGroup.maskScreen()
        setUnipanelHudVisible(false)
    }

    override fun onSendCharacterToLink(character: String) {
        if (dualWebViewGroup.isUrlEditing()) {
            val currentText = dualWebViewGroup.getCurrentLinkText()
            val currentPosition =
                    dualWebViewGroup.getCurrentUrlEditField()?.selectionStart ?: currentText.length

            val newText = StringBuilder(currentText).insert(currentPosition, character).toString()

            dualWebViewGroup.setLinkText(newText)
            dualWebViewGroup.getCurrentUrlEditField()?.setSelection(currentPosition + 1)
        }
    }

    override fun onSendBackspaceInLink() {
        if (dualWebViewGroup.isUrlEditing()) {
            val currentText = dualWebViewGroup.getCurrentLinkText()
            val currentPosition =
                    dualWebViewGroup.getCurrentUrlEditField()?.selectionStart ?: currentText.length

            if (currentPosition > 0) {
                // Delete character before cursor position
                val newText =
                        StringBuilder(currentText).deleteCharAt(currentPosition - 1).toString()

                dualWebViewGroup.setLinkText(newText)

                // Move cursor back one position
                dualWebViewGroup.getCurrentUrlEditField()?.setSelection(currentPosition - 1)
            }
        }
    }

    override fun onMaskTogglePressed() {
        handleMaskToggle()
    }

    /**
     * Phase 4q — dim mode hides the WHOLE unipanel HUD overlay (clock,
     * avatar, AQI, tiers, chat card) along with the browser, so the screen
     * goes fully dark. Exiting dim mode (double-tap) restores it. GONE — not
     * just transparent — so it also stops intercepting taps while dimmed,
     * letting the mask overlay's play/pause + double-tap-exit gestures
     * fall through cleanly.
     */
    private fun setUnipanelHudVisible(visible: Boolean) {
        runCatching {
            val overlay = findViewById<View?>(R.id.unipanelOverlay) ?: return
            if (visible) {
                // Always clear any roll transform so dim-mode restore can't
                // leave the HUD slid off-screen / transparent, and restore the
                // browser's reserved HUD lane so the two stay in sync.
                overlay.animate().cancel()
                overlay.translationY = 0f
                overlay.alpha = 1f
                hudRolledUp = false
                runCatching { dualWebViewGroup.setHudLaneReserved(true) }
            }
            overlay.visibility = if (visible) View.VISIBLE else View.GONE
            updateMinimalIndicators()
        }
    }

    /**
     * Phase 4s — double-tap "full-screen browser" toggle. Rolls the whole
     * unipanel HUD/chat overlay UP off the top of the screen (then hides it)
     * so the browser fills the display, and rolls it back DOWN on the next
     * double-tap. Replaces the old menu-bar toggle. Independent of dim mode
     * and the single-tap browser show/hide.
     */
    @Volatile
    private var hudRolledUp = false

    private fun toggleUnipanelHudRoll() {
        val overlay = findViewById<View?>(R.id.unipanelOverlay) ?: return
        hudRolledUp = !hudRolledUp
        overlay.animate().cancel()
        val distance = (overlay.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels)
            .toFloat()
        if (hudRolledUp) {
            // Roll up and out → full-screen browser. Collapse the browser's
            // own side/bottom nav bars AND drop the reserved HUD lane so the
            // WebView grows up to the very top edge (y=0), not just the HUD
            // overlay being hidden.
            runCatching { dualWebViewGroup.setHudLaneReserved(false) }
            runCatching { dualWebViewGroup.setNavBarsHidden(true) }
            overlay.animate()
                .translationY(-distance)
                .alpha(0f)
                .setDuration(200)
                .withEndAction { overlay.visibility = View.GONE }
                .start()
        } else {
            // Roll back down into view; restore the browser nav bars and the
            // reserved HUD lane so the WebView sits below the HUD again.
            runCatching { dualWebViewGroup.setHudLaneReserved(true) }
            runCatching { dualWebViewGroup.setNavBarsHidden(false) }
            overlay.visibility = View.VISIBLE
            overlay.translationY = -distance
            overlay.alpha = 0f
            overlay.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(200)
                .start()
        }
        DebugLog.d("DoubleTapDebug", "HUD roll ${if (hudRolledUp) "up (full-screen browser)" else "down"}")
        updateMinimalIndicators()
    }

    /**
     * YouTube "Full" view mode = real full-screen browser: hide the surrounding
     * app chrome (unipanel HUD overlay + the browser's own side/bottom nav bars
     * + the reserved HUD lane) so the video fills the display. Theater/Mini call
     * this with hidden=false to bring the chrome back. The in-page Full/Theater/
     * Mini and prev/next buttons live in the WebView, so they stay visible.
     */
    fun applyYoutubeFullscreenChrome(hidden: Boolean) {
        runCatching {
            val overlay = findViewById<View?>(R.id.unipanelOverlay)
            if (hidden) {
                hudRolledUp = true
                runCatching { dualWebViewGroup.setHudLaneReserved(false) }
                runCatching { dualWebViewGroup.setNavBarsHidden(true) }
                overlay?.animate()?.cancel()
                overlay?.visibility = View.GONE
            } else {
                hudRolledUp = false
                runCatching { dualWebViewGroup.setHudLaneReserved(true) }
                runCatching { dualWebViewGroup.setNavBarsHidden(false) }
                overlay?.animate()?.cancel()
                overlay?.translationY = 0f
                overlay?.alpha = 1f
                overlay?.visibility = View.VISIBLE
            }
            updateMinimalIndicators()
        }
    }

    override fun onSendEnterInLink() {
        isUrlEditing = false
        dualWebViewGroup.toggleIsUrlEditing(false)
        isKeyboardVisible = false
        if (dualWebViewGroup.isUrlEditing()) {
            val url = dualWebViewGroup.getCurrentLinkText()
            val formattedUrl = formatUrl(url)
            webView.loadUrl(formattedUrl)
            dualWebViewGroup.hideLinkEditing()
            hideCustomKeyboard()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            webView.requestFocus()
        }
    }

    // Helper function for quaternion multiplication
    fun quaternionMultiply(q1: FloatArray, q2: FloatArray): FloatArray {
        val w = q1[0] * q2[0] - q1[1] * q2[1] - q1[2] * q2[2] - q1[3] * q2[3]
        val x = q1[0] * q2[1] + q1[1] * q2[0] + q1[2] * q2[3] - q1[3] * q2[2]
        val y = q1[0] * q2[2] - q1[1] * q2[3] + q1[2] * q2[0] + q1[3] * q2[1]
        val z = q1[0] * q2[3] + q1[1] * q2[2] - q1[2] * q2[1] + q1[3] * q2[0]
        return floatArrayOf(w, x, y, z)
    }

    // Helper function for quaternion inversion
    fun quaternionInverse(q: FloatArray): FloatArray {
        val magnitudeSquared = q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]
        if (magnitudeSquared == 0f) return floatArrayOf(0f, 0f, 0f, 0f)
        val invMagnitude = 1f / magnitudeSquared
        return floatArrayOf(
                q[0] * invMagnitude,
                -q[1] * invMagnitude,
                -q[2] * invMagnitude,
                -q[3] * invMagnitude
        )
    }

    private fun normalizeQuaternion(q: FloatArray): FloatArray {
        val len = kotlin.math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (len > 0) {
            return floatArrayOf(q[0] / len, q[1] / len, q[2] / len, q[3] / len)
        }
        return q
    }

    private fun quaternionSlerp(qa: FloatArray, qb: FloatArray, t: Float): FloatArray {
        // q = [w, x, y, z]
        val w1 = qa[0]
        val x1 = qa[1]
        val y1 = qa[2]
        val z1 = qa[3]
        var w2 = qb[0]
        var x2 = qb[1]
        var y2 = qb[2]
        var z2 = qb[3]

        var dot = w1 * w2 + x1 * x2 + y1 * y2 + z1 * z2

        // If the dot product is negative, slerp won't take the shorter path.
        // So we negate one quaternion.
        if (dot < 0.0f) {
            w2 = -w2
            x2 = -x2
            y2 = -y2
            z2 = -z2
            dot = -dot
        }

        val DOT_THRESHOLD = 0.9995f
        if (dot > DOT_THRESHOLD) {
            // If the inputs are too close for comfort, linearly interpolate
            // and normalize.
            val result =
                    floatArrayOf(
                            w1 + t * (w2 - w1),
                            x1 + t * (x2 - x1),
                            y1 + t * (y2 - y1),
                            z1 + t * (z2 - z1)
                    )
            return normalizeQuaternion(result)
        }

        val theta_0 = kotlin.math.acos(dot) // theta_0 = angle between input vectors
        val theta = theta_0 * t // theta = angle between v0 and result
        val sin_theta = kotlin.math.sin(theta) // compute this value only once
        val sin_theta_0 = kotlin.math.sin(theta_0) // compute this value only once

        val s0 =
                kotlin.math.cos(theta) -
                        dot * sin_theta / sin_theta_0 // == sin(theta_0 - theta) / sin(theta_0)
        val s1 = sin_theta / sin_theta_0

        return floatArrayOf(
                s0 * w1 + s1 * w2,
                s0 * x1 + s1 * x2,
                s0 * y1 + s1 * y2,
                s0 * z1 + s1 * z2
        )
    }

    private fun ensureCameraThread() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun initializeCamera() {
        if (imageReader != null) return

        ensureCameraThread()
        val handler = cameraHandler ?: Handler(Looper.getMainLooper())

        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

            // Set up ImageReader for capturing photos
            imageReader =
                    ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.JPEG, 2)
                            .apply {
                                setOnImageAvailableListener(
                                        { reader ->
                                            // When an image is captured
                                            val image = reader.acquireLatestImage()
                                            try {
                                                // Convert image to base64 for web upload
                                                val buffer = image.planes[0].buffer
                                                val bytes = ByteArray(buffer.capacity())
                                                buffer.get(bytes)
                                                val base64Image =
                                                        Base64.encodeToString(bytes, Base64.DEFAULT)

                                                // Send image back to Google's image search
                                                runOnUiThread {
                                                    webView.evaluateJavascript(
                                                            """
                            (function() {
                                // Create a File object from base64
                                fetch('data:image/jpeg;base64,$base64Image')
                                    .then(res => res.blob())
                                    .then(blob => {
                                        const file = new File([blob], "image.jpg", { type: 'image/jpeg' });

                                        // Find or create file input
                                        let input = document.querySelector('input[type="file"][name="encoded_image"]');
                                        if (!input) {
                                            input = document.createElement('input');
                                            input.type = 'file';
                                            input.name = 'encoded_image';
                                            document.body.appendChild(input);
                                        }

                                        // Create FileList with our image
                                        const dataTransfer = new DataTransfer();
                                        dataTransfer.items.add(file);
                                        input.files = dataTransfer.files;

                                        // Trigger form submission
                                        input.dispatchEvent(new Event('change', { bubbles: true }));
                                    });
                            })();
                        """.trimIndent(),
                                                            null
                                                    )
                                                }
                                            } finally {
                                                image.close()
                                            }
                                        },
                                        handler
                                )
                            }
        } catch (e: Exception) {
            DebugLog.e("Camera", "Failed to initialize camera system", e)
            runOnUiThread {
                webView.evaluateJavascript("alert('Failed to initialize camera system.');", null)
            }
        }
    }

    override fun onSendClearInLink() {
        if (dualWebViewGroup.isUrlEditing()) {
            dualWebViewGroup.setLinkText("")
            // Set cursor at the beginning
            dualWebViewGroup.getCurrentUrlEditField()?.setSelection(0)
        }
    }

    override fun onShowKeyboardForEdit(text: String) {
        DebugLog.d("MainActivity", "onShowKeyboardForEdit called with text: $text")

        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { showCustomKeyboard() }
            return
        }

        showCustomKeyboard()
    }

    override fun onShowKeyboardForNew() {
        showCustomKeyboard()
    }

    override fun onShowLinkEditing() {
        DebugLog.d("LinkEditing", "onShowLinkEditing called")
        isUrlEditing = true // Make sure this state is set
        dualWebViewGroup.toggleIsUrlEditing(isUrlEditing)
        showCustomKeyboard()
    }

    override fun onHideLinkEditing() {
        DebugLog.d("LinkEditing", "onHideLinkEditing called")
        isUrlEditing = false
        dualWebViewGroup.toggleIsUrlEditing(isUrlEditing)
        DebugLog.d("LinkEditing", "isUrlEditing set to false")
        hideCustomKeyboard()
    }

    private fun sendCharacterToLinkEditText(character: String) {
        DebugLog.d("LinkEditing", "sendCharacterToLinkEditText called with: $character")
        if (dualWebViewGroup.isUrlEditing()) {
            val currentText = dualWebViewGroup.getCurrentLinkText()
            val cursorPosition =
                    dualWebViewGroup.getCurrentUrlEditField()?.selectionStart ?: currentText.length

            val newText = StringBuilder(currentText).insert(cursorPosition, character).toString()
            dualWebViewGroup.setLinkText(newText)
            dualWebViewGroup.getCurrentUrlEditField()?.setSelection(cursorPosition + 1)
        }
    }

    private fun sendBackspaceInLinkEditText() {
        DebugLog.d("LinkEditing", "sendBackspaceInLinkEditText called")
        if (dualWebViewGroup.isUrlEditing()) {
            val currentText = dualWebViewGroup.getCurrentLinkText()
            val cursorPosition =
                    dualWebViewGroup.getCurrentUrlEditField()?.selectionStart ?: currentText.length

            if (cursorPosition > 0) {
                val newText = StringBuilder(currentText).deleteCharAt(cursorPosition - 1).toString()
                dualWebViewGroup.setLinkText(newText)
                dualWebViewGroup.getCurrentUrlEditField()?.setSelection(cursorPosition - 1)
            }
        }
    }

    private fun sendEnterInLinkEditText() {
        if (dualWebViewGroup.isUrlEditing()) {
            val url = dualWebViewGroup.getCurrentLinkText()
            val formattedUrl = formatUrl(url)
            webView.loadUrl(formattedUrl)
            dualWebViewGroup.hideLinkEditing()
            keyboardListener?.onHideKeyboard()
        }
    }

    override fun isLinkEditing(): Boolean = isUrlEditing

    private fun formatUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://") -> url
            url.contains(".") -> "https://$url"
            else -> "https://www.google.com/search?q=${Uri.encode(url)}"
        }
    }

    // ── AR Navigation interception ────────────────────────────────────────

    private fun isAddressOrMapsUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("file://")) return false  // local asset pages (e.g. multi_pin_map.html)
        return lower.contains("maps.google.com") ||
               lower.contains("google.com/maps") ||
               lower.contains("maps.app.goo.gl") ||
               lower.contains("goo.gl/maps") ||
               lower.contains("waze.com/ul") ||
               lower.startsWith("geo:") ||
               lower.contains("/maps/dir/") ||
               lower.contains("/maps/place/") ||
               lower.contains("/maps/search")
    }

    /**
     * Aggressively stop all audio/video playback across ALL WebView instances.
     * Pauses and mutes all media elements, clears their src, and stops loading.
     */
    private fun killAllWebViewAudio(resumeWebViewsAfterKill: Boolean = true) {
        try {
            // Two-phase teardown:
            //
            // Phase 1 (immediate kill) — pause + scrub every <video>,
            // <audio>, and <iframe> currently in the DOM.
            //
            // Phase 2 (persistent muteguard) — install a guard that
            // catches any media element added LATER. The motivating
            // bug: if the user double-taps to return to chat WHILE a
            // YouTube watch page is still loading, the <video> element
            // doesn't exist yet, so phase 1 finds nothing to pause.
            // Then YouTube finishes wiring up the player and calls
            // .play() on the freshly-created element — audio leaks
            // into the chat screen. The guard handles this by:
            //   (a) replacing HTMLMediaElement.prototype.play with a
            //       rejected promise so any later .play() call no-ops;
            //   (b) installing a MutationObserver that pauses + mutes
            //       any media element added to the DOM after install;
            //   (c) being idempotent — re-running this on subsequent
            //       kill calls doesn't double-install.
            // The guard sticks until the WebView is destroyed or the
            // page is reloaded, which is exactly the lifetime we want.
            val killJs = """
                (function(){
                    document.querySelectorAll('video,audio,iframe').forEach(function(v){
                        try{
                            if(v.tagName==='IFRAME'){v.src='about:blank';return;}
                            v.pause();v.muted=true;v.src='';v.load();
                        }catch(e){}
                    });
                    try{
                        var ctx=window.AudioContext||window.webkitAudioContext;
                        if(window._audioCtx){window._audioCtx.close();}
                    }catch(e){}
                    // ── Persistent muteguard ─────────────────────────
                    if(!window.__taplink_muteGuard){
                        window.__taplink_muteGuard=true;
                        try{
                            var origPlay=HTMLMediaElement.prototype.play;
                            HTMLMediaElement.prototype.play=function(){
                                if(window.__taplink_muteGuard){
                                    try{this.pause();}catch(e){}
                                    try{this.muted=true;}catch(e){}
                                    return Promise.reject(
                                        new DOMException('TapInsight muteguard active','NotAllowedError')
                                    );
                                }
                                return origPlay.apply(this,arguments);
                            };
                        }catch(e){}
                        try{
                            var silenceAll=function(){
                                document.querySelectorAll('video,audio').forEach(function(el){
                                    try{el.pause();el.autoplay=false;el.muted=true;el.removeAttribute('src');el.load();}catch(e){}
                                });
                            };
                            if(window.MutationObserver){
                                var mo=new MutationObserver(function(muts){
                                    for(var i=0;i<muts.length;i++){
                                        var added=muts[i].addedNodes;
                                        if(!added) continue;
                                        for(var j=0;j<added.length;j++){
                                            var n=added[j];
                                            if(!n||n.nodeType!==1) continue;
                                            if(n.tagName==='VIDEO'||n.tagName==='AUDIO'){
                                                try{n.pause();n.autoplay=false;n.muted=true;}catch(e){}
                                            } else if(n.querySelectorAll){
                                                var inner=n.querySelectorAll('video,audio');
                                                for(var k=0;k<inner.length;k++){
                                                    try{inner[k].pause();inner[k].autoplay=false;inner[k].muted=true;}catch(e){}
                                                }
                                            }
                                        }
                                    }
                                });
                                mo.observe(document.documentElement||document,{childList:true,subtree:true});
                                window.__taplink_muteGuardObserver=mo;
                            }
                        }catch(e){}
                    }
                })();
            """.trimIndent()

            if (::dualWebViewGroup.isInitialized) {
                dualWebViewGroup.getAllWebViews().forEach { wv ->
                    wv.stopLoading()
                    wv.evaluateJavascript(killJs, null)
                    // Android-level pause stops all timers, JS execution, plugins/media
                    wv.onPause()
                }
                if (resumeWebViewsAfterKill) {
                    // Map loads need the WebViews live again. Return-to-chat does
                    // not: resuming here can let autoplay pages recreate audio.
                    dualWebViewGroup.getAllWebViews().firstOrNull()?.postDelayed({
                        dualWebViewGroup.getAllWebViews().forEach { it.onResume() }
                    }, 100)
                } else {
                    webViewsPausedForReturnToChat = true
                }
            }
            // Request transient audio focus to interrupt system-level playback,
            // then abandon after a brief delay so the system properly processes the interruption
            try {
                val am = audioManager ?: (getSystemService(AUDIO_SERVICE) as? AudioManager)
                val focusListener = AudioManager.OnAudioFocusChangeListener { /* no-op for kill */ }
                am?.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                // Abandon after 200ms to let the system process the interruption
                android.os.Handler(Looper.getMainLooper()).postDelayed({
                    am?.abandonAudioFocus(focusListener)
                }, 200)
            } catch (_: Exception) {}

            DebugLog.d("ARNav", "killAllWebViewAudio: killed audio on all WebViews")
        } catch (e: Exception) {
            DebugLog.e("ARNav", "killAllWebViewAudio error", e)
        }
    }

    private fun buildArNavUrl(originalUrl: String): String {
        val dest = extractDestinationFromUrl(originalUrl)
        val searchQuery = extractTaplinkSearchQueryFromUrl(originalUrl)
        val googleKey = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
            .getString("google_maps_api_key", "") ?: ""
        val explicitOrigin = extractOriginCoordsFromUrl(originalUrl)
        val lat = explicitOrigin?.first ?: (lastGpsLat ?: 0.0)
        val lng = explicitOrigin?.second ?: (lastGpsLon ?: 0.0)
        val originLocked = if (explicitOrigin != null) 1 else 0
        DebugLog.d("ARNav", "buildArNavUrl: originalUrl='${originalUrl.take(200)}'")
        DebugLog.d("ARNav", "  dest='$dest' search='${searchQuery ?: ""}' lat=$lat lng=$lng originLocked=$originLocked gkey=${if (googleKey.isNotBlank()) googleKey.take(8) + "..." else "MISSING"}")
        // ar_nav.html renders a full 3D photorealistic route overview
        return "file:///android_asset/ar_nav.html" +
               "?dest=${Uri.encode(dest)}" +
               "&search=${Uri.encode(searchQuery ?: "")}" +
               "&gkey=${Uri.encode(googleKey)}" +
               "&lat=$lat" +
               "&lng=$lng" +
               "&origin_locked=$originLocked"
    }

    private fun extractOriginCoordsFromUrl(url: String): Pair<Double, Double>? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        for (param in listOf("origin", "saddr")) {
            val raw = uri.getQueryParameter(param)?.trim().orEmpty()
            if (raw.isBlank()) continue
            parseLatLng(raw)?.let { return it }
        }
        return null
    }

    private fun extractTaplinkSearchQueryFromUrl(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        return uri.getQueryParameter("taplink_query")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseLatLng(raw: String): Pair<Double, Double>? {
        val match = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$""")
            .find(Uri.decode(raw)) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lng = match.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    private fun extractDestinationFromUrl(url: String): String {
        var raw: String? = null
        var extractMethod = "none"
        try {
            val uri = Uri.parse(url)
            if (uri.scheme == "geo") {
                val q = uri.getQueryParameter("q")
                if (!q.isNullOrBlank()) { raw = q; extractMethod = "geo:q" }
                else {
                    val ssp = uri.schemeSpecificPart?.substringBefore('?')
                    if (!ssp.isNullOrBlank()) { raw = ssp; extractMethod = "geo:ssp" }
                }
            }
            if (raw == null) {
                for (param in listOf("q", "query", "daddr", "destination")) {
                    val v = uri.getQueryParameter(param)
                    if (!v.isNullOrBlank()) { raw = v; extractMethod = "param:$param"; break }
                }
            }
            if (raw == null) {
                val path = uri.path ?: ""
                val placeMatch = Regex("/maps/place/([^/@]+)").find(path)
                if (placeMatch != null) {
                    raw = Uri.decode(placeMatch.groupValues[1]).replace("+", " ")
                    extractMethod = "path:place"
                }
            }
            if (raw == null) {
                val path = uri.path ?: ""
                val dirMatch = Regex("/maps/dir/[^/]+/([^/@]+)").find(path)
                if (dirMatch != null) {
                    raw = Uri.decode(dirMatch.groupValues[1]).replace("+", " ")
                    extractMethod = "path:dir"
                }
            }
        } catch (e: Exception) {
            DebugLog.e("ARNav", "extractDestinationFromUrl parse error", e)
        }
        DebugLog.d("ARNav", "extractDestinationFromUrl: method=$extractMethod raw='${(raw ?: url).take(120)}'")
        return cleanAddressText(raw ?: url)
    }

    /** Strip conversational chat text so the geocoder gets a clean destination query. */
    private fun cleanAddressText(text: String): String {
        var c = text.trim()
            .replace(Regex("""\s+"""), " ")
            .removePrefix("→")
            .trim()

        val addressRegex = Regex(
            """\b\d{1,5}\s+[A-Za-z0-9.'#\- ]+\s(?:St|Street|Ave|Avenue|Blvd|Boulevard|Rd|Road|Dr|Drive|Ln|Lane|Way|Pl|Place|Ct|Court|Pkwy|Parkway|Ter|Terrace)\b(?:,\s*[A-Za-z .'-]+){0,3}""",
            RegexOption.IGNORE_CASE
        )
        addressRegex.find(c)?.value?.trim()?.trimEnd('.', ',', ';', ':')?.let {
            DebugLog.d("ARNav", "cleanAddressText[address]: '$text' → '$it'")
            return it
        }

        val patterns = listOf(
            Regex("""\baddress:\s*(.+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:is\s+)?(?:located|location)\s+at\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""\bis\s+at\s+(.+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(c)
            if (match != null) {
                c = match.groupValues[1].trim()
                break
            }
        }

        val imp = Regex("""^(?:find|visit|go\s+to|head\s+to|navigate\s+to|directions?\s+to)\s+(.+)""", RegexOption.IGNORE_CASE).find(c)
        if (imp != null) c = imp.groupValues[1].trim()

        c = c
            .replace(Regex("""\b(?:currently\s+)?(?:open\s*now|openow|closed|closednow)\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(?:clear|cloudy|overcast|rain|showers|fog|drizzle|snow|storm)\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\bAQI\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b\d{1,3}°\s*[FC]\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(?:walk|drive|transit|eta|parking|weather|temperature)\b.*$""", RegexOption.IGNORE_CASE), "")

        listOf(" — ", " - ", " | ", ". ").forEach { separator ->
            val idx = c.indexOf(separator)
            if (idx > 5) c = c.substring(0, idx)
        }

        c = c.trim().trimEnd('.', ',', ';', ' ')
        DebugLog.d("ARNav", "cleanAddressText: '$text' → '$c'")
        return c
    }

    private fun isStreamingSite(url: String?): Boolean {
        if (url == null) return false
        val streamingDomains =
                listOf(
                        "netflix.com",
                        "disneyplus.com",
                        "hulu.com",
                        "primevideo.com",
                        "amazon.com/gp/video",
                        "max.com",
                        "peacocktv.com",
                        "apple.com/tv",
                        "tv.apple.com",
                        "tubitv.com",
                        "pluto.tv",
                        "paramountplus.com",
                        "discoveryplus.com"
                )
        return streamingDomains.any { url.contains(it, ignoreCase = true) }
    }

    private fun initializeSpeechRecognition() {
        // Check if speech recognition is available
        val isAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        DebugLog.d("SpeechRecognition", "Recognition available: $isAvailable")

        if (!isAvailable) {
            DebugLog.w("SpeechRecognition", "Speech recognition not available on this device")
            return
        }

        try {
            speechRecognizer =
                    SpeechRecognizer.createSpeechRecognizer(this).apply {
                        setRecognitionListener(
                                object : RecognitionListener {
                                    override fun onResults(results: Bundle?) {
                                        isListeningForSpeech = false
                                        results?.getStringArrayList(
                                                        SpeechRecognizer.RESULTS_RECOGNITION
                                                )
                                                ?.let { matches ->
                                                    if (matches.isNotEmpty()) {
                                                        val text = matches[0]
                                                        runOnUiThread {
                                                            onShowKeyboardForEdit(text)
                                                            // Handle inserting text based on what
                                                            // input is focused
                                                            val editFieldVisible =
                                                                    dualWebViewGroup
                                                                            .urlEditText
                                                                            .visibility ==
                                                                            View.VISIBLE

                                                            when {
                                                                dualWebViewGroup
                                                                        .isBookmarksExpanded() &&
                                                                        !editFieldVisible -> {
                                                                    // Handle bookmark menu
                                                                    // navigation - maybe search
                                                                    // bookmarks?
                                                                    // For now just toast or ignore
                                                                }
                                                                editFieldVisible -> {
                                                                    // Handle any edit field input
                                                                    // (URL or bookmark)
                                                                    val currentText =
                                                                            dualWebViewGroup
                                                                                    .getCurrentLinkText()
                                                                    val cursorPosition =
                                                                            dualWebViewGroup
                                                                                    .urlEditText
                                                                                    .selectionStart
                                                                    // Insert the text at cursor
                                                                    // position
                                                                    val newText =
                                                                            StringBuilder(
                                                                                            currentText
                                                                                    )
                                                                                    .insert(
                                                                                            cursorPosition,
                                                                                            text
                                                                                    )
                                                                                    .toString()

                                                                    // Set text and move cursor
                                                                    // after inserted text
                                                                    dualWebViewGroup.setLinkText(
                                                                            newText,
                                                                            cursorPosition +
                                                                                    text.length
                                                                    )
                                                                }
                                                                dualWebViewGroup.getDialogInput() !=
                                                                        null -> {
                                                                    val input =
                                                                            dualWebViewGroup
                                                                                    .getDialogInput()!!
                                                                    val currentText =
                                                                            input.text.toString()
                                                                    val cursorPosition =
                                                                            input.selectionStart
                                                                    val newText =
                                                                            StringBuilder(
                                                                                            currentText
                                                                                    )
                                                                                    .insert(
                                                                                            cursorPosition,
                                                                                            text
                                                                                    )
                                                                                    .toString()
                                                                    input.setText(newText)
                                                                    input.setSelection(
                                                                            cursorPosition +
                                                                                    text.length
                                                                    )
                                                                }
                                                                else -> {
                                                                    sendTextToWebView(text)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                    }

                                    // Implement other RecognitionListener methods with empty bodies
                                    override fun onReadyForSpeech(params: Bundle?) {
                                        DebugLog.d("SpeechRecognition", "Ready for speech")
                                        dualWebViewGroup.showToast("Listening...")
                                    }
                                    override fun onBeginningOfSpeech() {
                                        DebugLog.d("SpeechRecognition", "Speech started")
                                    }
                                    override fun onRmsChanged(rmsdB: Float) {}
                                    override fun onBufferReceived(buffer: ByteArray?) {}
                                    override fun onEndOfSpeech() {
                                        DebugLog.d("SpeechRecognition", "Speech ended")
                                        isListeningForSpeech = false
                                    }
                                    override fun onError(error: Int) {
                                        isListeningForSpeech = false
                                        val errorMsg =
                                                when (error) {
                                                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                                            "Network timeout"
                                                    SpeechRecognizer.ERROR_NETWORK ->
                                                            "Network error"
                                                    SpeechRecognizer.ERROR_AUDIO ->
                                                            "Audio recording error"
                                                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                                                    SpeechRecognizer.ERROR_CLIENT ->
                                                            "Speech service unavailable"
                                                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                                            "No speech detected"
                                                    SpeechRecognizer.ERROR_NO_MATCH ->
                                                            "No match found"
                                                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                                            "Recognizer busy"
                                                    SpeechRecognizer
                                                            .ERROR_INSUFFICIENT_PERMISSIONS ->
                                                            "Permission denied"
                                                    else -> "Error: $error"
                                                }
                                        DebugLog.e("SpeechRecognition", "Error: $error ($errorMsg)")
                                        dualWebViewGroup.showToast(errorMsg)
                                    }
                                    override fun onPartialResults(partialResults: Bundle?) {}
                                    override fun onEvent(eventType: Int, params: Bundle?) {}
                                }
                        )
                    }
            DebugLog.d("SpeechRecognition", "SpeechRecognizer created successfully")
        } catch (e: Exception) {
            DebugLog.e("SpeechRecognition", "Failed to create SpeechRecognizer", e)
            speechRecognizer = null
        }
    }

    fun hideCustomKeyboard() {
        DebugLog.d("KeyboardDebug", "Hiding keyboard")

        // First blur any focused element
        webView.evaluateJavascript(
                """
       (function() {
           const activeElement = document.activeElement;
           if (activeElement && activeElement !== document.body) {
               activeElement.blur();
               // For React/custom components that might need extra cleanup
               const event = new Event('blur', { bubbles: true });
               activeElement.dispatchEvent(event);
           }
       })();
       """,
                null
        )

        // First handle cleanup of keyboard state
        keyboardView?.visibility = View.GONE
        isKeyboardVisible = false
        keyboardView?.let { dualWebViewGroup.setKeyboard(it) }

        // Show info bars when keyboard hides
        dualWebViewGroup.showInfoBars()

        // Reset interaction states
        isSimulatingTouchEvent = false
        cursorJustAppeared = false
        isToggling = false

        // Instruct DualWebViewGroup to hide the link field
        dualWebViewGroup.hideLinkEditing()

        // Clean up input state
        webView.evaluateJavascript(
                """
        (function() {
            var activeElement = document.activeElement;
            if (activeElement) {
                activeElement.blur();
            }
        })();
    """,
                null
        )

        // Notify DualWebViewGroup about keyboard being hidden
        dualWebViewGroup.onKeyboardHidden()

        // Restore original webView state
        webView.translationY = 0f

        // Clear any existing animations
        webView.clearAnimation()

        // Force layout update
        webView.requestLayout()
        webView.parent?.requestLayout()

        // Show cursor if not in URL editing mode

        isUrlEditing = false

        dualWebViewGroup.post { dualWebViewGroup.updateScrollBarsVisibility() }
        syncKeyboardAwarePageState()

        dualWebViewGroup.cleanupResources()
    }

    override fun onClearPressed() {
        when {
            dualWebViewGroup.isBookmarksExpanded() &&
                    dualWebViewGroup.urlEditText.visibility != View.VISIBLE -> {
                // Handle bookmark menu navigation
                dualWebViewGroup.getBookmarksView().handleKeyboardInput("clear")
            }
            dualWebViewGroup.urlEditText.visibility == View.VISIBLE -> {
                // Clear edit field for both URL and bookmark editing
                dualWebViewGroup.setLinkText("")
            }
            dualWebViewGroup.getDialogInput() != null -> {
                dualWebViewGroup.getDialogInput()?.setText("")
            }
            else -> {
                // Preserve existing JavaScript functionality for web content
                runOnUiThread {
                    webView.evaluateJavascript(
                            """
    (function() {
        var el = document.activeElement;
        if (!el) {
            console.log('No active element found');
            return null;
        }

        function simulateClearInput(element) {
            // Start composition
            const compStart = new Event('compositionstart', { bubbles: true });
            element.dispatchEvent(compStart);

            // Create beforeinput event
            const beforeInputEvent = new InputEvent('beforeinput', {
                bubbles: true,
                cancelable: true,
                inputType: 'deleteContent',
                data: null
            });
            element.dispatchEvent(beforeInputEvent);

            if (!beforeInputEvent.defaultPrevented) {
                // Use execCommand for deletion
                if (document.execCommand) {
                    // First select all
                    document.execCommand('selectAll', false);
                    // Then delete selection
                    document.execCommand('delete', false);
                }

                // Dispatch native input event
                const nativeInputEvent = new Event('input', { bubbles: true });
                element.dispatchEvent(nativeInputEvent);
            }

            // End composition
            const compEnd = new Event('compositionend', { bubbles: true });
            element.dispatchEvent(compEnd);

            // Handle React components
            if (element._valueTracker) {
                element._valueTracker.setValue('');
                element.dispatchEvent(new Event('input', { bubbles: true }));
            }

            return {
                success: true,
                type: element.type
            };
        }

        return JSON.stringify(simulateClearInput(el));
    })();
    """,
                            null
                    )
                }
            }
        }
    }

    override fun onMoveCursorLeft() {
        runOnUiThread { moveCursor(-1) }
    }

    override fun onMoveCursorRight() {
        runOnUiThread { moveCursor(1) }
    }

    private var isListeningForSpeech = false
    private var groqAudioService: GroqAudioService? = null
    private var lastMicPressTime = 0L

    private fun setVoiceAssistantAudioRoute(enabled: Boolean) {
        val value = if (enabled) "voiceassistant" else "off"
        try {
            audioManager?.mode = AudioManager.MODE_NORMAL
            audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE,
                    0
            )
            audioManager?.setParameters("audio_source_record=$value")
            DebugLog.d("AudioRoute", "audio_source_record=$value")
        } catch (e: Exception) {
            DebugLog.e("AudioRoute", "Failed to set audio route: $value", e)
        }
    }

    fun prepareAudioForTtsPlayback() {
        runOnUiThread { setVoiceAssistantAudioRoute(true) }
    }

    override fun onMicrophonePressed() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastMicPressTime < 500) {
            DebugLog.d("SpeechRecognition", "Ignoring rapid microphone press")
            return
        }
        lastMicPressTime = currentTime

        DebugLog.d(
                "SpeechRecognition",
                "onMicrophonePressed called, isListening: $isListeningForSpeech"
        )
        runOnUiThread {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                DebugLog.d("SpeechRecognition", "Requesting audio permission")
                requestPermissions(
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        PERMISSIONS_REQUEST_CODE
                )
                return@runOnUiThread
            }

            if (groqAudioService == null) {
                initializeGroqService()
            }

            if (!groqAudioService!!.hasApiKey()) {
                showGroqKeyDialog()
                return@runOnUiThread
            }

            if (groqAudioService!!.isRecording()) {
                // Stop listening
                DebugLog.d("SpeechRecognition", "Stopping Groq recording")
                groqAudioService?.stopRecording()
                setVoiceAssistantAudioRoute(false)
                dualWebViewGroup.showToast("Processing...")
            } else {
                // Start listening
                DebugLog.d("SpeechRecognition", "Starting Groq recording")
                setVoiceAssistantAudioRoute(true)
                groqAudioService?.startRecording()
                dualWebViewGroup.showToast("Listening...")
            }
        }
    }

    fun showGroqKeyDialog() {
        if (groqAudioService == null) {
            initializeGroqService()
        }
        val currentKey = groqAudioService?.getApiKey()
        dualWebViewGroup.showPromptDialog(
                "Enter Groq API Key",
                currentKey,
                { key ->
                    groqAudioService?.setApiKey(key)
                    dualWebViewGroup.showToast("API Key Saved")
                    hideCustomKeyboard()
                },
                { dualWebViewGroup.showToast("API Key Required for Voice") }
        )
    }

    private fun initializeGroqService() {
        groqAudioService =
                GroqAudioService(this).apply {
                    setListener(
                            object : GroqAudioService.TranscriptionListener {
                                override fun onTranscriptionResult(text: String) {
                                    DebugLog.d("SpeechRecognition", "Groq result: $text")
                                    runOnUiThread {
                                        // Restore playback route after recording so chat TTS is
                                        // audible.
                                        setVoiceAssistantAudioRoute(true)
                                        // If chat is visible, insert text there
                                        if (dualWebViewGroup.isChatVisible()) {
                                            dualWebViewGroup.insertVoiceToChatInput(text)
                                        } else {
                                            handleVoiceResult(text)
                                        }
                                        dualWebViewGroup.showToast("Success")
                                        keyboardView?.setMicActive(false)
                                        dualWebViewGroup.setChatMicActive(false)
                                    }
                                }

                                override fun onError(message: String) {
                                    DebugLog.e("SpeechRecognition", "Groq error: $message")
                                    runOnUiThread {
                                        setVoiceAssistantAudioRoute(false)
                                        dualWebViewGroup.showToast("Voice Error: $message")
                                        keyboardView?.setMicActive(false)
                                        dualWebViewGroup.setChatMicActive(false)
                                        if (message.contains("No API Key")) {
                                            showGroqKeyDialog()
                                        }
                                    }
                                }

                                override fun onRecordingStart() {
                                    DebugLog.d("SpeechRecognition", "Groq recording started")
                                    runOnUiThread {
                                        isListeningForSpeech = true
                                        keyboardView?.setMicActive(true)
                                        dualWebViewGroup.setChatMicActive(true)
                                    }
                                }

                                override fun onRecordingStop() {
                                    DebugLog.d("SpeechRecognition", "Groq recording stopped")
                                    runOnUiThread {
                                        isListeningForSpeech = false
                                        // Don't turn off mic indicator yet, wait for processing
                                        // result
                                    }
                                }
                            }
                    )
                }
    }

    private fun handleVoiceResult(text: String) {
        if (text.isBlank()) return

        onShowKeyboardForEdit(text)
        val editFieldVisible = dualWebViewGroup.urlEditText.visibility == View.VISIBLE

        when {
            dualWebViewGroup.isBookmarksExpanded() && !editFieldVisible -> {
                // Handle bookmark menu navigation - maybe search bookmarks?
                // For now just ignore
            }
            editFieldVisible -> {
                // Handle URL/bookmark edit field input
                val currentText = dualWebViewGroup.getCurrentLinkText()
                val cursorPosition = dualWebViewGroup.urlEditText.selectionStart.coerceAtLeast(0)
                val newText = StringBuilder(currentText).insert(cursorPosition, text).toString()
                dualWebViewGroup.setLinkText(newText, cursorPosition + text.length)
            }
            dualWebViewGroup.getDialogInput() != null -> {
                val input = dualWebViewGroup.getDialogInput()!!
                val currentText = input.text.toString()
                val cursorPosition = input.selectionStart.coerceAtLeast(0)
                val newText = StringBuilder(currentText).insert(cursorPosition, text).toString()
                input.setText(newText)
                input.setSelection((cursorPosition + text.length).coerceAtMost(newText.length))
            }
            else -> {
                sendTextToWebView(text)
            }
        }

        isListeningForSpeech = false
    }

    private fun moveCursor(offset: Int) {
        val focusedView = currentFocus
        if (focusedView != null) {
            val inputConnection = BaseInputConnection(focusedView, true)
            val now = SystemClock.uptimeMillis()
            val keyCode =
                    if (offset < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            val keyEventDown = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val keyEventUp = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            inputConnection.sendKeyEvent(keyEventDown)
            inputConnection.sendKeyEvent(keyEventUp)
        } else {
            DebugLog.d("MainActivity", "No focused view to move cursor")
        }
    }

    /**
     * Sync dashboard data between SharedPreferences (companion app) and
     * the WebView's localStorage used by the AR Dashboard HTML.
     *
     * 1. If SharedPreferences has data from the companion editor, write it
     *    into localStorage and re-render the dashboard.
     * 2. Override the dashboard's `persistState` to also write back to
     *    SharedPreferences via AndroidInterface so edits on the glasses
     *    are visible to the companion app.
     */
    private fun injectDashboardSync(view: WebView?) {
        view?.evaluateJavascript("""
        (function() {
            var KEY = 'dashboardLinksV1';
            function ensureTapRadio(parsed) {
                var changed = false;
                parsed.apps = parsed.apps || {};
                parsed.groups = Array.isArray(parsed.groups) ? parsed.groups : [];
                if (!parsed.apps.tapradio) {
                    parsed.apps.tapradio = { name: 'TapRadio', url: 'file:///android_asset/radio.html' };
                    changed = true;
                }
                var music = parsed.groups.find(function(group) {
                    return String((group && group.title) || '').trim().toLowerCase() === 'music / streaming';
                });
                if (!music) {
                    music = { title: 'Music / Streaming', cls: 'sec-music', keys: ['tapradio'] };
                    parsed.groups.push(music);
                    changed = true;
                }
                if (!Array.isArray(music.keys)) {
                    music.keys = [];
                    changed = true;
                }
                if (!music.keys.includes('tapradio')) {
                    music.keys.unshift('tapradio');
                    changed = true;
                }
                return changed;
            }
            function ensureMediaLibrary(parsed) {
                var changed = false;
                parsed.apps = parsed.apps || {};
                parsed.groups = Array.isArray(parsed.groups) ? parsed.groups : [];
                // URLs we previously owned for the Media Library tile —
                // anything on this list gets healed forward to the current
                // glasses-local bridge page. Custom user URLs are preserved.
                var LIBRARY_OWNED_URLS = [
                    'file:///android_asset/library_launcher.html',
                    'file:///android_asset/library_local.html',
                    'https://appassets.androidplatform.net/assets/library_local.html',
                    'https://127.0.0.1:19110/library',
                    'http://127.0.0.1:19110/library'
                ];
                // Load the library page through the same virtual origin that
                // serves media bytes (/media/...), so <audio>/<video> inside
                // the page can fetch those URLs without file:// → https://
                // cross-origin weirdness.
                var TARGET_URL = 'https://appassets.androidplatform.net/assets/library_local.html';
                if (!parsed.apps.medialibrary) {
                    parsed.apps.medialibrary = { name: 'Media Library', url: TARGET_URL };
                    changed = true;
                } else {
                    var curUrl = parsed.apps.medialibrary.url || '';
                    if (LIBRARY_OWNED_URLS.indexOf(curUrl) >= 0 && curUrl !== TARGET_URL) {
                        parsed.apps.medialibrary.url = TARGET_URL;
                        changed = true;
                    }
                }
                var nav = parsed.groups.find(function(group) {
                    return String((group && group.title) || '').trim().toLowerCase() === 'navigation / entertainment';
                });
                if (!nav) {
                    nav = { title: 'Navigation / Entertainment', cls: 'sec-nav', keys: ['medialibrary'] };
                    parsed.groups.unshift(nav);
                    changed = true;
                }
                if (!Array.isArray(nav.keys)) {
                    nav.keys = [];
                    changed = true;
                }
                if (!nav.keys.includes('medialibrary')) {
                    nav.keys.unshift('medialibrary');
                    changed = true;
                }
                return changed;
            }
            // Pull companion-edited data from SharedPreferences
            var saved = '';
            try { saved = window.AndroidInterface.getDashboardData(); } catch(e) {}
            if (saved && saved.length > 2) {
                try {
                    var parsed = JSON.parse(saved);
                    if (parsed.apps && parsed.groups) {
                        var changed = ensureTapRadio(parsed);
                        changed = ensureMediaLibrary(parsed) || changed;
                        var serialized = JSON.stringify(parsed);
                        localStorage.setItem(KEY, serialized);
                        if (changed && window.AndroidInterface) {
                            window.AndroidInterface.saveDashboardData(serialized);
                        }
                        // Update the in-memory state and re-render
                        if (typeof state !== 'undefined') {
                            state.apps = parsed.apps;
                            state.groups = parsed.groups;
                            if (typeof renderAll === 'function') renderAll();
                        }
                    }
                } catch(e) { console.error('[Dashboard] Sync parse error:', e); }
            }
            // Hook persistState to also write back to SharedPreferences
            var origPersist = (typeof persistState === 'function') ? persistState : null;
            window.persistState = function() {
                if (origPersist) origPersist();
                try {
                    var data = localStorage.getItem(KEY);
                    if (data && window.AndroidInterface) {
                        window.AndroidInterface.saveDashboardData(data);
                    }
                } catch(e) {}
            };
        })();
        """.trimIndent(), null)
    }

    fun injectJavaScriptForInputFocus(targetWebView: WebView = webView) {
        targetWebView.evaluateJavascript(
                """
    (function() {
        if (window.__taplinkInputBridgeInstalled) {
            return true;
        }

        // Store state about known popups to prevent re-triggering
        const knownPopups = new WeakSet();

        function notifyInputFocus() {
            try {
                if (window.GroqBridge && typeof window.GroqBridge.onInputFocus === 'function') {
                    window.GroqBridge.onInputFocus();
                    return true;
                }
            } catch (e) {
                console.log('TapLink focus bridge error: ' + e.toString());
            }
            return false;
        }

        function canActuallyInputText(element) {
            try {
                if (!element) return false;

                // If we've previously identified this as part of a popup, skip input checks
                if (knownPopups.has(element)) {
                    console.log('Element is part of known popup, skipping input checks');
                    return false;
                }

                // First check if it's a popup/menu element
                if (element.getAttribute('aria-haspopup') === 'true' ||
                    element.getAttribute('aria-expanded') === 'false' ||
                    element.getAttribute('role') === 'button' ||
                    element.getAttribute('role') === 'menu' ||
                    element.getAttribute('role') === 'menuitem') {
                    console.log('Element identified as popup/menu');

                    // Mark this and its children as known popup elements
                    knownPopups.add(element);
                    element.querySelectorAll('*').forEach(child => knownPopups.add(child));

                    return false;
                }

                // Rest of the input detection code remains the same
                if (element instanceof HTMLInputElement) {
                    const textInputTypes = ['text', 'email', 'password', 'search', 'tel', 'url', 'number'];
                    return textInputTypes.includes(element.type);
                }

                if (element instanceof HTMLTextAreaElement) return true;
                if (element.isContentEditable) return true;

                return false;
            } catch (e) {
                console.log('Input validation error: ' + e.toString());
                return false;
            }
        }

        function rememberEditableTarget(element) {
            if (!canActuallyInputText(element)) return null;
            window.__taplinkLastEditable = element;
            window.__taplinkLastEditableAt = Date.now();
            return element;
        }

        function resolveShadowEditable(root) {
            if (!root) return null;
            const active = root.activeElement;
            if (canActuallyInputText(active)) return active;
            if (active && active.shadowRoot) {
                const nested = resolveShadowEditable(active.shadowRoot);
                if (nested) return nested;
            }
            if (root.querySelector) {
                const candidate = root.querySelector('input, textarea, [contenteditable=""], [contenteditable="true"]');
                if (canActuallyInputText(candidate)) return candidate;
            }
            return null;
        }

        window.__taplinkResolveInputTarget = function() {
            let active = document.activeElement;
            if (active && active.shadowRoot) {
                const shadowEditable = resolveShadowEditable(active.shadowRoot);
                if (shadowEditable) return rememberEditableTarget(shadowEditable);
            }
            if (canActuallyInputText(active)) return rememberEditableTarget(active);

            const stored = window.__taplinkLastEditable;
            if (stored && stored.isConnected && canActuallyInputText(stored)) {
                try {
                    stored.focus({ preventScroll: true });
                } catch (e) {
                    try { stored.focus(); } catch (_) {}
                }
                return rememberEditableTarget(stored);
            }
            return null;
        };

        // Function to handle clicks
        function handleClick(event) {
            console.log('Click event detected');
            let target = event.target;
            let currentNode = target;

            // Log the click path
            console.log('Click path:', {
                targetTag: target.tagName,
                targetClass: target.className,
                targetRole: target.getAttribute('role')
            });

            while (currentNode && currentNode !== document.body) {
                if (canActuallyInputText(currentNode)) {
                    console.log('Found input-capable element');
                    rememberEditableTarget(currentNode);
                    notifyInputFocus();
                    break;
                }
                currentNode = currentNode.parentElement;
            }
        }

        function handleFocusIn(event) {
            const target = event && event.target ? event.target : document.activeElement;
            if (canActuallyInputText(target)) {
                rememberEditableTarget(target);
                notifyInputFocus();
            }
        }

        function handleSelectionChange() {
            const target =
                typeof window.__taplinkResolveInputTarget === 'function'
                    ? window.__taplinkResolveInputTarget()
                    : document.activeElement;
            if (canActuallyInputText(target)) {
                rememberEditableTarget(target);
            }
        }

        // Remove any existing listeners to prevent duplicates
        document.removeEventListener('click', handleClick, true);
        document.removeEventListener('focusin', handleFocusIn, true);
        document.removeEventListener('selectionchange', handleSelectionChange, true);

        // Add the click listener
        document.addEventListener('click', handleClick, true);
        document.addEventListener('focusin', handleFocusIn, true);
        document.addEventListener('selectionchange', handleSelectionChange, true);

        // Set up a more robust mutation observer
        const observer = new MutationObserver((mutations) => {
            mutations.forEach((mutation) => {
                // For any new nodes, check if they're part of a popup
                mutation.addedNodes.forEach((node) => {
                    if (node.nodeType === 1) { // ELEMENT_NODE
                        if (node.getAttribute('role') === 'menu' ||
                            node.getAttribute('role') === 'dialog' ||
                            node.getAttribute('aria-haspopup') === 'true') {
                            console.log('New popup/menu element detected');
                            knownPopups.add(node);
                            // Mark all children as part of the popup
                            node.querySelectorAll('*').forEach(child => knownPopups.add(child));
                        }
                    }
                });
            });
        });

        observer.observe(document.body, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['role', 'aria-haspopup']
        });

        window.__taplinkInputBridgeInstalled = true;
    })();
    """,
                null
        )
    }

    private fun buildWebInputActionScript(actionBody: String): String =
            """
        (function() {
            function isEditableElement(element) {
                try {
                    if (!element) return false;
                    if (element instanceof HTMLInputElement) {
                        var type = String(element.type || 'text').toLowerCase();
                        var allowedTypes = ['text', 'email', 'password', 'search', 'tel', 'url', 'number'];
                        return allowedTypes.indexOf(type) !== -1 && !element.disabled && !element.readOnly;
                    }
                    if (element instanceof HTMLTextAreaElement) {
                        return !element.disabled && !element.readOnly;
                    }
                    return !!element.isContentEditable;
                } catch (e) {
                    return false;
                }
            }

            function rememberEditableElement(element) {
                if (isEditableElement(element)) {
                    window.__taplinkLastEditable = element;
                    window.__taplinkLastEditableAt = Date.now();
                    return element;
                }
                return null;
            }

            function resolveShadowEditable(root) {
                if (!root) return null;
                var active = root.activeElement;
                if (isEditableElement(active)) return active;
                if (active && active.shadowRoot) {
                    var nested = resolveShadowEditable(active.shadowRoot);
                    if (nested) return nested;
                }
                if (root.querySelector) {
                    var candidate = root.querySelector('input, textarea, [contenteditable=""], [contenteditable="true"]');
                    if (isEditableElement(candidate)) return candidate;
                }
                return null;
            }

            function resolveEditableElement() {
                if (typeof window.__taplinkResolveInputTarget === 'function') {
                    try {
                        var bridgeResolved = window.__taplinkResolveInputTarget();
                        if (isEditableElement(bridgeResolved)) {
                            return rememberEditableElement(bridgeResolved);
                        }
                    } catch (e) {}
                }

                var active = document.activeElement;
                if (active && active.shadowRoot) {
                    var shadowEditable = resolveShadowEditable(active.shadowRoot);
                    if (isEditableElement(shadowEditable)) {
                        return rememberEditableElement(shadowEditable);
                    }
                }
                if (isEditableElement(active)) {
                    return rememberEditableElement(active);
                }

                // Fallback 1: previously remembered editable element
                var stored = window.__taplinkLastEditable;
                if (stored && stored.isConnected && isEditableElement(stored)) {
                    return rememberEditableElement(stored);
                }

                // Fallback 2: DOM query for visible editable elements.
                // When the custom keyboard steals Android-level focus from
                // the WebView, document.activeElement becomes <body> even
                // though an <input> was focused moments ago. Find any
                // visible, non-hidden input/textarea and use it.
                var candidates = document.querySelectorAll(
                    'input:not([type="hidden"]):not([disabled]):not([readonly]), ' +
                    'textarea:not([disabled]):not([readonly]), ' +
                    '[contenteditable="true"], [contenteditable=""]'
                );
                for (var i = 0; i < candidates.length; i++) {
                    var c = candidates[i];
                    // Check visibility: skip elements that are display:none
                    // or inside a hidden container.
                    if (c.offsetParent === null && c.style.position !== 'fixed') continue;
                    var rect = c.getBoundingClientRect();
                    if (rect.width <= 0 || rect.height <= 0) continue;
                    // If there's only one visible editable, use it.
                    // If there are multiple, prefer the most recently
                    // interacted one (fall through to the first visible).
                    return rememberEditableElement(c);
                }
                return null;
            }

            function focusElement(element) {
                if (!element || typeof element.focus !== 'function') return;
                try {
                    element.focus({ preventScroll: true });
                } catch (e) {
                    try { element.focus(); } catch (_) {}
                }
            }

            function createInputEvent(type, inputType, data) {
                try {
                    return new InputEvent(type, {
                        bubbles: true,
                        cancelable: type === 'beforeinput',
                        composed: true,
                        inputType: inputType,
                        data: data
                    });
                } catch (e) {
                    var evt = new Event(type, {
                        bubbles: true,
                        cancelable: type === 'beforeinput',
                        composed: true
                    });
                    evt.inputType = inputType;
                    evt.data = data;
                    return evt;
                }
            }

            function setNativeValue(element, value) {
                try {
                    if (element instanceof HTMLTextAreaElement) {
                        var areaDescriptor = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value');
                        if (areaDescriptor && areaDescriptor.set) {
                            areaDescriptor.set.call(element, value);
                            return;
                        }
                    }
                    if (element instanceof HTMLInputElement) {
                        var inputDescriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
                        if (inputDescriptor && inputDescriptor.set) {
                            inputDescriptor.set.call(element, value);
                            return;
                        }
                    }
                } catch (e) {}
                element.value = value;
            }

            function syncReactTracker(element, oldValue) {
                try {
                    if (element && element._valueTracker) {
                        element._valueTracker.setValue(oldValue);
                    }
                } catch (e) {}
            }

            var el = resolveEditableElement();
            if (!el) {
                console.log('TapLink: no editable element found');
                return null;
            }

            focusElement(el);
            rememberEditableElement(el);
            ${actionBody.trimIndent()}
        })();
        """.trimIndent()

    private fun notifyActivePageKeyboardState(isVisible: Boolean) {
        val targetWebView = if (::webView.isInitialized) webView else return
        notifyKeyboardStateToWebView(targetWebView, isVisible)
    }

    private fun notifyKeyboardStateToWebView(targetWebView: WebView, isVisible: Boolean) {
        val keyboardHeight =
                if (isVisible) {
                    listOf(
                                    dualWebViewGroup.keyboardContainer.height,
                                    dualWebViewGroup.keyboardContainer.measuredHeight,
                                    keyboardView?.height ?: 0
                            )
                            .firstOrNull { it > 0 } ?: 160
                } else {
                    0
                }

        targetWebView.evaluateJavascript(
                """
            (function() {
                try {
                    if (typeof window.tapBrowserSetKeyboardState === 'function') {
                        window.tapBrowserSetKeyboardState(${if (isVisible) "true" else "false"}, $keyboardHeight);
                        return true;
                    }
                } catch (e) {
                    console.warn('TapLink keyboard-state bridge failed:', e);
                }
                return false;
            })();
            """.trimIndent(),
                null
        )
    }

    private fun syncKeyboardAwarePageState() {
        if (!::dualWebViewGroup.isInitialized) return
        val isVisible = isKeyboardVisible
        val syncAction = Runnable {
            dualWebViewGroup.getAllWebViews().forEach { candidate ->
                candidate.post { notifyKeyboardStateToWebView(candidate, isVisible) }
            }
        }
        syncAction.run()
        uiHandler.postDelayed(syncAction, 300L)
        uiHandler.postDelayed(syncAction, 1200L)
    }

    // ── Keyboard text injection — cloned from TAPLINKX3 (proven working) ──
    // Uses simple document.activeElement + execCommand approach.
    // Does NOT use buildWebInputActionScript or resolveEditableElement.

    private fun sendCharacterToWebView(character: String) {
        sendTextToWebView(character)
    }

    private fun sendTextToWebView(text: String) {
        if (dualWebViewGroup.isUrlEditing()) {
            text.forEach { char -> sendCharacterToLinkEditText(char.toString()) }
            return
        }
        if (dualWebViewGroup.isChatVisible()) {
            dualWebViewGroup.sendTextToChatInput(text)
            return
        } else {
            webView.evaluateJavascript(
                    """
        (function() {
            var el = document.activeElement;
            if (!el) {
                console.log('No active element found');
                return null;
            }

            function simulateNaturalInput(element, text) {
                const compStart = new Event('compositionstart', { bubbles: true });
                element.dispatchEvent(compStart);

                const originalValue = element.value || '';

                const beforeInputEvent = new InputEvent('beforeinput', {
                    bubbles: true,
                    cancelable: true,
                    inputType: 'insertText',
                    data: text
                });
                element.dispatchEvent(beforeInputEvent);

                if (!beforeInputEvent.defaultPrevented) {
                    const nativeInputEvent = new Event('input', { bubbles: true });
                    element.dispatchEvent(nativeInputEvent);

                    if (document.execCommand) {
                        document.execCommand('insertText', false, text);
                    } else {
                        const start = element.selectionStart;
                        const end = element.selectionEnd;
                        element.value = originalValue.slice(0, start) +
                                      text +
                                      originalValue.slice(end);
                    }
                }

                const compEnd = new Event('compositionend', { bubbles: true });
                element.dispatchEvent(compEnd);

                if (element._valueTracker) {
                    element._valueTracker.setValue(originalValue);
                    element.dispatchEvent(new Event('input', { bubbles: true }));
                }

                return {
                    success: true,
                    type: element.type,
                    originalValue: originalValue,
                    newValue: element.value
                };
            }

            return JSON.stringify(simulateNaturalInput(el, ${JSONObject.quote(text)}));
        })();
        """
            ) { result -> DebugLog.d("InputDebug", "JavaScript result: $result") }
        }
    }

    private fun sendBackspaceToWebView() {
        if (dualWebViewGroup.isUrlEditing()) {
            sendBackspaceInLinkEditText()
            return
        }
        if (dualWebViewGroup.isChatVisible()) {
            dualWebViewGroup.sendBackspaceToChatInput()
            return
        } else {
            webView.evaluateJavascript(
                    """
        (function() {
            var el = document.activeElement;
            if (!el) {
                console.log('No active element found');
                return null;
            }

            const initialState = {
                value: el.value,
                selectionStart: el.selectionStart,
                selectionEnd: el.selectionEnd
            };

            function simulateNaturalBackspace(element) {
                const beforeInputEvent = new InputEvent('beforeinput', {
                    bubbles: true,
                    cancelable: true,
                    inputType: 'deleteContentBackward'
                });
                element.dispatchEvent(beforeInputEvent);

                if (!beforeInputEvent.defaultPrevented) {
                    let deletionSuccessful = false;
                    const originalValue = element.value;

                    if (!deletionSuccessful && document.execCommand) {
                        try {
                            document.execCommand('delete', false);
                            deletionSuccessful = element.value !== originalValue;
                        } catch (e) {}
                    }

                    if (!deletionSuccessful) {
                        const backspaceKey = new KeyboardEvent('keydown', {
                            key: 'Backspace',
                            code: 'Backspace',
                            keyCode: 8,
                            which: 8,
                            bubbles: true,
                            cancelable: true
                        });
                        element.dispatchEvent(backspaceKey);
                        deletionSuccessful = element.value !== originalValue;
                    }

                    if (!deletionSuccessful) {
                        const start = element.selectionStart;
                        const end = element.selectionEnd;

                        if (start === end && start > 0) {
                            element.value = element.value.substring(0, start - 1) +
                                          element.value.substring(end);
                            element.setSelectionRange(start - 1, start - 1);
                            deletionSuccessful = true;
                        } else if (start !== end) {
                            element.value = element.value.substring(0, start) +
                                          element.value.substring(end);
                            element.setSelectionRange(start, start);
                            deletionSuccessful = true;
                        }
                    }

                    if (deletionSuccessful) {
                        element.dispatchEvent(new Event('input', { bubbles: true }));

                        if (element._valueTracker) {
                            element._valueTracker.setValue('');
                            element.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                    }
                }

                return {
                    success: true,
                    initialState: initialState,
                    finalState: {
                        value: el.value,
                        selectionStart: el.selectionStart,
                        selectionEnd: el.selectionEnd
                    }
                };
            }

            return JSON.stringify(simulateNaturalBackspace(el));
        })();
        """
            ) { result -> DebugLog.d("InputDebug", "Backspace JavaScript result: $result") }
        }
    }

    private fun suppressImmediateWebClickLeak() {
        suppressWebClickUntil = SystemClock.uptimeMillis() + 250L
    }

    /**
     * Phase 2 Step 2c.1: drives the unipanel HUD clock.
     *
     * Refreshes the unipanelHudTime TextView every 30 s with the
     * current local time in HH:MM. Lightweight by design — no
     * BroadcastReceiver, no allocations on tick. Posts itself
     * recursively via uiHandler so the loop stops cleanly when the
     * Activity goes away (no explicit cancel needed; the Handler is
     * already main-thread-bound).
     *
     * Future Step 2c migrations will subscribe this surface to live
     * data from the shared MainViewModel rather than just rendering
     * the wall clock.
     */
    private fun startUnipanelHudClockTicker() {
        val timeTv = findViewById<android.widget.TextView?>(R.id.unipanelHudTime) ?: return
        val dateTv = findViewById<android.widget.TextView?>(R.id.unipanelHudDate)
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val dateFmt = java.text.SimpleDateFormat("EEE · MMM d", java.util.Locale.US)
        val ticker = object : Runnable {
            override fun run() {
                try {
                    val now = java.util.Date()
                    timeTv.text = timeFmt.format(now)
                    dateTv?.text = dateFmt.format(now)
                } catch (_: Exception) {}
                uiHandler.postDelayed(this, 30_000L)
            }
        }
        uiHandler.post(ticker)
        startUnipanelHudBatteryReceiver()
        startUnipanelHudNetworkObserver()
    }

    /**
     * Phase 4f — connectivity indicator. Registers a
     * [ConnectivityManager.NetworkCallback] for the default network
     * and tags the type (Wi-Fi / cellular / offline). Colors mimic
     * the system status-bar convention: amber for Wi-Fi, blue for
     * cellular, red for offline. Updates fire on bind so the field
     * is correct on first paint.
     */
    private var unipanelHudNetworkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun startUnipanelHudNetworkObserver() {
        // Network/Wi-Fi text was removed from the compact glasses HUD
        // to make room for the Events/Tasks/News panel beside the avatar.
        // Keep the method as a lifecycle no-op so older comments/call-sites
        // don't need a broader cleanup.
    }

    /**
     * Phase 4b — battery indicator in the HUD strip. Registers a
     * receiver for ACTION_BATTERY_CHANGED (sticky broadcast on
     * Android, so registerReceiver(null, ...) returns the latest
     * value immediately — no need to wait for the first change).
     */
    private var unipanelHudBatteryReceiver: android.content.BroadcastReceiver? = null

    private fun startUnipanelHudBatteryReceiver() {
        val tv = findViewById<android.widget.TextView?>(R.id.unipanelHudBattery) ?: return
        val render = { intent: android.content.Intent? ->
            try {
                if (intent != null) {
                    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                    val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                    val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                    val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == android.os.BatteryManager.BATTERY_STATUS_FULL
                    val pillSymbol = if (charging) "⚡" else "▮"
                    tv.text = if (pct >= 0) "$pillSymbol $pct%" else "— %"
                }
            } catch (_: Exception) {}
        }
        // Seed with the current sticky broadcast (no listener registration
        // needed for this — passing null receiver returns the latest intent).
        runCatching {
            render(
                registerReceiver(
                    null,
                    android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                )
            )
        }
        // Subscribe to live changes so the indicator stays current.
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                render(intent)
            }
        }
        runCatching {
            registerReceiver(
                receiver,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            unipanelHudBatteryReceiver = receiver
        }
    }

    /**
     * Phase 2 Step 2c.3: subscribe the mini chat-card stack to the
     * shared [com.TapLink.app.unipanel.ChatCardBridge] so the three
     * TextViews above the WebView reflect the live visionclaw chat.
     *
     * Render order: the bottom of the stack is the OLDEST visible
     * card and the top is the NEWEST. visionclaw publishes its
     * `messages` list oldest-first, matching how its chat panel
     * renders, so we walk it from the end and assign:
     *   • cards[N-1] → unipanelMiniCard1 (top, most recent)
     *   • cards[N-2] → unipanelMiniCard2
     *   • cards[N-3] → unipanelMiniCard3 (bottom, oldest visible)
     *
     * Each row's background switches at runtime between the
     * assistant / user drawable so the colour follows the speaker
     * — Step 2c.2's XML defaults are just placeholders.
     *
     * Empty slots (less than 3 messages in history) are hidden via
     * View.GONE so the stack collapses gracefully instead of
     * showing blank pills.
     *
     * Listener fires on the publisher's thread (visionclaw's main
     * scope dispatcher); we hop to [uiHandler] to be safe before
     * touching the View tree.
     */
    private var unipanelChatCardSubscription: AutoCloseable? = null
    @Volatile
    private var unipanelAssistantCardDismissedThroughMs: Long = 0L
    private val hideUnipanelAssistantCardRunnable = Runnable {
        // Phase 4k.5 — the card box is the ScrollView now; hide it and
        // clear the inner text.
        findViewById<View?>(R.id.unipanelMiniCardScroll)?.visibility = View.GONE
        findViewById<android.widget.TextView?>(R.id.unipanelMiniCard1)?.text = ""
    }

    /**
     * Phase 4g — left-arm (cyttsp6_mt) SHORT-TAP detection. Ported
     * from visionclaw MainActivity.consumedByLeftArmTap. Fires
     * voiceServiceApi.toggleCamera() on a single quick tap so the
     * user can flip the CameraX feed on/off the same way the Hermes
     * branch did. Returns true to indicate the gesture was consumed —
     * callers must NOT also forward the same UP to other detectors
     * (which would interpret it as their own tap).
     *
     * The check runs through ACTION_DOWN/MOVE/UP/CANCEL to filter
     * out long-presses, drags, and cancels. Threshold constants
     * mirror visionclaw exactly so behavior matches Hermes.
     */
    private var leftArmTapDownTimeMs: Long = 0L
    private var leftArmTapDownX: Float = 0f
    private var leftArmTapDownY: Float = 0f
    private var leftArmTapTracking: Boolean = false
    private var leftArmTapMovedTooFar: Boolean = false
    private var rightArmLastTapUpMs: Long = 0L
    private var pendingRightArmSingleTapAction: Runnable? = null

    // ── Right-arm (cyttsp5_mt) physical-tap KEY double-tap state ──────────
    // On the X3 Pro the RIGHT arm trackpad delivers a physical tap as a
    // hardware KEY event (KEYCODE_BUTTON_A / KEYCODE_DPAD_CENTER) through
    // dispatchKeyEvent — NOT as a MotionEvent. (Cursor motion comes through
    // dispatchTouchEvent as cyttsp5_mt, but the click/tap itself is a key.)
    // The Hermes branch detected the right-arm double-tap in
    // TrackpadGestureEngine.onKeyEvent and ended the session. The unipanel
    // tapbrowser never wired a key-path double-tap detector, so right-arm
    // double-taps never reached exitGeminiFully(). These fields back the
    // detector below.
    private var rightArmKeyDownMs: Long = 0L
    private var rightArmKeyTracking: Boolean = false
    private var rightArmKeyLastTapUpMs: Long = 0L
    // A single physical tap should release well within this; longer = a hold.
    private val RIGHT_ARM_KEY_TAP_MAX_MS = 400L
    // Ignore a "second tap" that arrives almost instantly — that's the same
    // physical tap echoed as a second keycode, not a real double-tap.
    private val RIGHT_ARM_KEY_DOUBLE_TAP_MIN_GAP_MS = 40L
    // Upper bound between the two taps to count as a double-tap.
    private val RIGHT_ARM_KEY_DOUBLE_TAP_WINDOW_MS = 320L

    private fun consumedByLeftArmTap(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                leftArmTapDownTimeMs = SystemClock.uptimeMillis()
                leftArmTapDownX = ev.x
                leftArmTapDownY = ev.y
                leftArmTapMovedTooFar = false
                leftArmTapTracking = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (leftArmTapTracking && !leftArmTapMovedTooFar) {
                    val dx = ev.x - leftArmTapDownX
                    val dy = ev.y - leftArmTapDownY
                    if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) >
                            LEFT_ARM_TAP_MOVE_TOLERANCE_PX) {
                        leftArmTapMovedTooFar = true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val wasTracking = leftArmTapTracking
                val movedTooFar = leftArmTapMovedTooFar
                leftArmTapTracking = false
                if (!wasTracking || movedTooFar) return false
                val elapsed = SystemClock.uptimeMillis() - leftArmTapDownTimeMs
                if (elapsed >= LEFT_ARM_TAP_MAX_MS) return false
                val api = voiceServiceApi ?: return false
                pendingRightArmSingleTapAction?.let { uiHandler.removeCallbacks(it) }
                val action = Runnable {
                    pendingRightArmSingleTapAction = null
                    // Mars rebind: the left-arm short tap ACTIVATES Gemini when no
                    // session is running; only WHILE a session is active does it
                    // toggle the camera on/off.
                    val voiceActive =
                        com.TapLink.app.unipanel.HudStateBridge.current().phase !=
                            com.TapLink.app.unipanel.HudStateBridge.VoicePhase.IDLE
                    if (!voiceActive) {
                        DebugLog.d("LeftArmTap", "short tap (${elapsed}ms) → activateVoice (no session)")
                        runCatching { api.activateVoice() }
                    } else {
                        DebugLog.d(
                            "LeftArmTap",
                            "short tap (${elapsed}ms) → toggleCamera (was=${api.isCameraOn()})"
                        )
                        runCatching { api.toggleCamera() }
                    }
                }
                pendingRightArmSingleTapAction = action
                uiHandler.postDelayed(
                    action,
                    android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 20L
                )
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                leftArmTapTracking = false
            }
        }
        return false
    }

    private fun consumedByRightArmGeminiExitDoubleTap(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            rightArmLastTapUpMs = 0L
            return false
        }
        if (ev.actionMasked != MotionEvent.ACTION_UP) return false

        val now = ev.eventTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
        val previous = rightArmLastTapUpMs
        rightArmLastTapUpMs = now
        val withinDoubleTap =
            previous > 0L &&
                now - previous <= android.view.ViewConfiguration.getDoubleTapTimeout()
        if (!withinDoubleTap) return false

        rightArmLastTapUpMs = 0L
        if (!isGeminiExitSurfaceActive()) return false

        pendingRightArmSingleTapAction?.let { uiHandler.removeCallbacks(it) }
        pendingRightArmSingleTapAction = null
        DebugLog.d(
            "DoubleTapDebug",
            "Right-arm early double-tap — full Gemini exit before short-tap handling"
        )
        exitGeminiFully()
        return true
    }

    /**
     * Right-arm (cyttsp5_mt) DOUBLE-TAP → full Gemini exit, detected on the
     * KEY path (this is the proven Hermes mechanism). The right-arm physical
     * tap arrives as KEYCODE_BUTTON_A / KEYCODE_DPAD_CENTER via
     * [dispatchKeyEvent], so a touch/GestureDetector-based detector never sees
     * it — which is why earlier attempts to wire the exit to the touch path
     * never fired for the right arm.
     *
     * Behaviour, carefully scoped so it never breaks normal browsing:
     *   - We only ever CONSUME the event (return true) in the one case where
     *     we actually fire the exit (a real double-tap WHILE a Gemini session /
     *     camera / chat bubble is active). Every other key event is observed
     *     for timing only and passed through untouched (return false), so a
     *     single right-arm tap still clicks/selects in the browser exactly as
     *     before.
     *   - Detection keys off ACTION_UP (like Hermes) with a tap-length cap so a
     *     long press isn't counted, and a small min-gap so a single physical
     *     tap echoed as two keycodes isn't mistaken for a double-tap.
     */
    private fun consumedByRightArmKeyGeminiExitDoubleTap(event: KeyEvent): Boolean {
        val isTapKey = event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER
        if (!isTapKey) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    rightArmKeyDownMs = SystemClock.uptimeMillis()
                    rightArmKeyTracking = true
                }
                // Never consume DOWN — preserves the normal browser click.
                return false
            }
            KeyEvent.ACTION_UP -> {
                if (!rightArmKeyTracking) return false
                rightArmKeyTracking = false
                val elapsed = SystemClock.uptimeMillis() - rightArmKeyDownMs
                if (elapsed >= RIGHT_ARM_KEY_TAP_MAX_MS) {
                    // Held too long — treat as not-a-tap and reset.
                    rightArmKeyLastTapUpMs = 0L
                    return false
                }
                val now = SystemClock.uptimeMillis()
                val previous = rightArmKeyLastTapUpMs
                val gap = now - previous
                val isDoubleTap = previous > 0L &&
                    gap in RIGHT_ARM_KEY_DOUBLE_TAP_MIN_GAP_MS..RIGHT_ARM_KEY_DOUBLE_TAP_WINDOW_MS
                if (isDoubleTap) {
                    rightArmKeyLastTapUpMs = 0L
                    if (!isGeminiExitSurfaceActive()) return false
                    pendingRightArmSingleTapAction?.let { uiHandler.removeCallbacks(it) }
                    pendingRightArmSingleTapAction = null
                    DebugLog.d(
                        "DoubleTapDebug",
                        "Right-arm KEY double-tap (code=${event.keyCode}, gap=${gap}ms) → full Gemini exit"
                    )
                    exitGeminiFully()
                    return true
                }
                // First tap of a potential double-tap — record and pass through.
                rightArmKeyLastTapUpMs = now
                return false
            }
        }
        return false
    }

    /** True when the foreground WebView is on a YouTube watch (video) page. */
    private fun isViewingYoutubeWatchPage(): Boolean {
        val url = runCatching { webView.url }.getOrNull().orEmpty()
        return url.contains("youtube.com/watch", ignoreCase = true) ||
            url.contains("youtu.be/", ignoreCase = true) ||
            (url.contains("youtube.com", ignoreCase = true) && url.contains("v=", ignoreCase = true))
    }

    /**
     * Toggle play/pause on the YouTube video the user is watching. Used by the
     * empty-space tap while on a watch page (instead of collapsing the
     * browser). Acts directly on the largest HTML5 <video> element so it
     * doesn't depend on YouTube's own click-to-toggle (which is unreliable in
     * the WebView) or on tracked isMediaPlaying state (which can drift).
     */
    private fun toggleYoutubeVideoPlayback() {
        val js = """
            (function(){
              try{
                var vids=[].slice.call(document.querySelectorAll('video'))
                  .filter(function(v){return v.readyState>0 || v.currentTime>0 || v.duration>0;});
                if(!vids.length) vids=[].slice.call(document.querySelectorAll('video'));
                if(!vids.length) return 'novideo';
                vids.sort(function(a,b){
                  return (b.clientWidth*b.clientHeight)-(a.clientWidth*a.clientHeight);
                });
                var v=vids[0];
                if(v.paused){ v.play(); return 'play'; }
                v.pause(); return 'pause';
              }catch(e){ return 'err:'+e; }
            })();
        """.trimIndent()
        runCatching {
            webView.evaluateJavascript(js) { r ->
                DebugLog.d("YouTubeTap", "empty-space tap → video ${r?.trim('"')}")
            }
        }
    }

    /** Phase 4d (Mars revision) — double-tap now toggles tapbrowser's
     *  OWN side + bottom navigation bars via the existing
     *  [DualWebViewGroup.setNavBarsHidden] path (same toggle the
     *  bottom-right corner "box" button performs). The unipanel HUD
     *  overlay (clock / MIC / mini cards / vision dot) stays visible
     *  the whole time. */
    private fun toggleBrowserNavBars() {
        if (!::dualWebViewGroup.isInitialized) return
        val currentlyHidden = runCatching { dualWebViewGroup.isNavBarsHidden() }
            .getOrDefault(false)
        runCatching { dualWebViewGroup.setNavBarsHidden(!currentlyHidden) }
        DebugLog.d(
            "DoubleTapToggle",
            "Browser nav bars now ${if (!currentlyHidden) "HIDDEN" else "VISIBLE"}"
        )
    }

    /**
     * Phase 4k (Mars revision) — "focus mode" for the browser portion.
     *
     * Tapping empty space in the browser collapses the whole browser
     * (WebView content + the side/bottom nav bars) so only the unipanel
     * HUD strip and the chat card float over black. Double-tap brings
     * the browser back.
     *
     * Implementation note: the cursor views and the unipanel overlay
     * are siblings of [dualWebViewGroup] under mainContainer, so setting
     * the group INVISIBLE hides the web content + nav bars in one move
     * while leaving the cursor and HUD/chat fully visible and tappable.
     * INVISIBLE (not GONE) preserves the page + WebView state and avoids
     * a relayout, so restore is instant.
     */
    @Volatile
    private var browserPanelHidden = false

    private fun hideBrowserPanel() {
        if (!::dualWebViewGroup.isInitialized) return
        if (browserPanelHidden) return
        browserPanelHidden = true
        runCatching { dualWebViewGroup.visibility = View.INVISIBLE }
        DebugLog.d("BrowserFocus", "Browser portion hidden — HUD + chat only")
    }

    private fun showBrowserPanel() {
        if (!::dualWebViewGroup.isInitialized) return
        if (!browserPanelHidden) return
        browserPanelHidden = false
        runCatching { dualWebViewGroup.visibility = View.VISIBLE }
        DebugLog.d("BrowserFocus", "Browser portion restored")
    }

    // ──────────────────────────────────────────────────────────────────
    // Unipanel v2 Phase 3 — GeminiVoiceService binding
    //
    // tapbrowser binds (BIND_AUTO_CREATE) to the foreground Service that
    // hosts the voice pipeline. The Service is in the visionclaw `app`
    // module so we can't import its class here — bindService is given
    // an Intent constructed via setClassName(packageName, FQN), and the
    // returned IBinder is cast to [com.TapLink.app.unipanel.VoiceServiceApi]
    // which both sides implement.
    //
    // The bind does NOT promote the Service to foreground (no mic
    // privilege attached yet). FGS promotion happens inside the
    // Service when [VoiceServiceApi.activateVoice] is invoked. Phase 3
    // doesn't wire any gesture to that — this is purely the plumbing
    // commit, verified by:
    //   adb logcat | grep -E "GeminiFgs|VoiceBind"
    //
    // Phase 4 will move the actual AudioRecord + WebSocket + AudioTrack
    // ownership into the Service.
    // Phase 6 will wire the user-facing gesture (tap on the chat-card
    // stack, or whatever Mars's swipe/tap UX lands on) to call
    // voiceServiceApi?.activateVoice() / shutdownVoice().
    // ──────────────────────────────────────────────────────────────────
    @Volatile
    private var voiceServiceApi: com.TapLink.app.unipanel.VoiceServiceApi? = null

    private val voiceServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val api = service as? com.TapLink.app.unipanel.VoiceServiceApi
            if (api == null) {
                DebugLog.w(
                    "VoiceBind",
                    "onServiceConnected: returned binder is not a VoiceServiceApi"
                )
                return
            }
            voiceServiceApi = api
            DebugLog.d("VoiceBind", "bound to ${name?.shortClassName}")
            // ROOT-CAUSE FIX (avatar tap sometimes does nothing right after
            // launch): if the user tapped the orb before the Service finished
            // binding, that tap was dropped with only a log. Flush a recent
            // pending activation now that the binder is live (TTL-guarded so a
            // stale tap can't silently start voice much later).
            if (pendingVoiceActivateUntilMs > SystemClock.uptimeMillis()) {
                pendingVoiceActivateUntilMs = 0L
                if (com.TapLink.app.unipanel.HudStateBridge.current().phase ==
                    com.TapLink.app.unipanel.HudStateBridge.VoicePhase.IDLE
                ) {
                    DebugLog.d("VoiceBind", "Flushing pending voice activation after bind")
                    runCatching { api.activateVoice() }
                }
            }
            // Phase 4g — install the unipanel PreviewView's
            // SurfaceProvider so the next CameraX bind shows a live
            // feed in the camera-preview frame. The PreviewView is
            // owned by tapbrowser's layout; CameraX runs in the
            // Service. Crossing the binder hands the surface
            // provider to FrameCaptureManager.start.
            runCatching {
                val pv = findViewById<androidx.camera.view.PreviewView?>(
                    R.id.unipanelCameraPreviewView
                )
                if (pv != null) {
                    api.setCameraPreviewSurfaceProvider(pv.surfaceProvider)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            DebugLog.w("VoiceBind", "onServiceDisconnected from ${name?.shortClassName}")
            voiceServiceApi = null
        }

        override fun onBindingDied(name: ComponentName?) {
            DebugLog.w("VoiceBind", "onBindingDied for ${name?.shortClassName}")
            voiceServiceApi = null
        }

        override fun onNullBinding(name: ComponentName?) {
            DebugLog.w("VoiceBind", "onNullBinding for ${name?.shortClassName}")
            voiceServiceApi = null
        }
    }

    private var voiceServiceBound: Boolean = false

    /** Set when the user taps to activate voice before the Service binder is
     *  ready; flushed in onServiceConnected. TTL-guarded (uptime millis). */
    @Volatile
    private var pendingVoiceActivateUntilMs: Long = 0L

    /**
     * Bind to the visionclaw GeminiVoiceService (defined as
     * `com.rayneo.visionclaw.core.session.GeminiSessionForegroundService`
     * — we identify it by string FQN since this module can't see the
     * class). Idempotent: the bind only happens once per Activity
     * lifetime.
     *
     * BIND_AUTO_CREATE creates the Service if it isn't running but does
     * NOT make it foreground — that promotion happens lazily inside
     * the Service when activateVoice is invoked. Voice-off means
     * "Service bound but not foreground" which is the right resting
     * state.
     */
    private fun startVoiceServiceBinding() {
        if (voiceServiceBound) return
        try {
            val intent = Intent().setClassName(
                packageName,
                com.TapLink.app.unipanel.VoiceServiceApi.SERVICE_FQN
            )
            val ok = bindService(intent, voiceServiceConnection, Context.BIND_AUTO_CREATE)
            if (ok) {
                voiceServiceBound = true
                DebugLog.d("VoiceBind", "bindService dispatched, awaiting onServiceConnected")
            } else {
                DebugLog.w("VoiceBind", "bindService returned false — service not findable")
            }
        } catch (e: Exception) {
            DebugLog.w("VoiceBind", "bindService threw: ${e.message}")
        }
    }

    private fun stopVoiceServiceBinding() {
        if (!voiceServiceBound) return
        try {
            unbindService(voiceServiceConnection)
            DebugLog.d("VoiceBind", "unbindService dispatched")
        } catch (e: Exception) {
            DebugLog.w("VoiceBind", "unbindService threw: ${e.message}")
        }
        voiceServiceBound = false
        voiceServiceApi = null
    }

    private fun startUnipanelMiniCardObserver() {
        // Phase 4k.5 — single Gemini reply card. The text lives in
        // unipanelMiniCard1 and scrolls inside the unipanelMiniCardScroll
        // box (Hermes-style auto-scroll to the newest text). Tapping the
        // card runs a Google search of the reply text in the browser —
        // it no longer expands.
        val card1 = findViewById<android.widget.TextView?>(R.id.unipanelMiniCard1)
        val scroll = findViewById<View?>(R.id.unipanelMiniCardScroll)
        if (card1 == null || scroll == null) {
            DebugLog.w(
                "Unipanel",
                "Mini card observer: card views missing, skipping subscription"
            )
            return
        }
        // Phase 4k.6 — the onClick goes on the TextView, not the
        // ScrollView: ScrollView.onTouchEvent doesn't call performClick,
        // so a clickable ScrollView never fires onClick (that's why the
        // earlier tap did nothing). The overlay hit-test resolves to the
        // clickable TextView (an interactive descendant), so dispatching
        // to it fires this handler.
        card1.setOnClickListener {
            val text = card1.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) openUnipanelChatCardSearch(text)
        }

        unipanelChatCardSubscription?.runCatching { close() }
        unipanelChatCardSubscription =
            com.TapLink.app.unipanel.ChatCardBridge.observe { cards ->
                uiHandler.post { renderUnipanelAssistantCard(card1, scroll, cards) }
            }
        scroll.post { repositionUnipanelAssistantCard() }
    }

    /**
     * Phase 4k.5 — tapping the chat card opens a Google search of the
     * reply text in the browser, mirroring the Hermes branch's generic
     * search (buildGenericSearchUrl → google.com/search?q=…). The browser
     * is un-hidden first in case we're in the tap-to-collapse focus view.
     */
    private fun openUnipanelChatCardSearch(text: String) {
        val cleaned = text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
            .take(1000)
        if (cleaned.isBlank()) return
        val encoded = try {
            java.net.URLEncoder.encode(cleaned, "UTF-8")
        } catch (_: Exception) {
            return
        }
        val url = "https://www.google.com/search?q=$encoded"
        showBrowserPanel()
        runCatching { openUrlInNewTab(url) }
        DebugLog.d("Unipanel", "Chat card tapped → search: ${cleaned.take(60)}")
    }

    /**
     * Phase 4k.5 — pure render. Picks the most recent ASSISTANT card and
     * shows it in the scroll box; auto-scrolls to the newest text and
     * caps the box height (grow-to-content up to ~96dp, then scroll).
     * GONE when there's no assistant text.
     */
    private fun renderUnipanelAssistantCard(
        card: android.widget.TextView,
        scroll: View,
        cards: List<com.TapLink.app.unipanel.ChatCardBridge.Card>
    ) {
        val latestAssistantCard = cards.lastOrNull { !it.fromUser && it.text.isNotBlank() }
        val latestAssistant = latestAssistantCard
            ?.takeIf { it.timestampMs > unipanelAssistantCardDismissedThroughMs }
            ?.text
        uiHandler.removeCallbacks(hideUnipanelAssistantCardRunnable)
        if (latestAssistant == null) {
            card.text = ""
            scroll.visibility = View.GONE
            return
        }
        card.text = latestAssistant
        scroll.visibility = View.VISIBLE
        repositionUnipanelAssistantCard()
        // Short replies sit at the top, long ones scroll. Auto-scroll
        // to the newest text as the reply streams in (Hermes behavior).
        scroll.post {
            repositionUnipanelAssistantCard()
            (scroll as? android.widget.ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
        uiHandler.postDelayed(
            hideUnipanelAssistantCardRunnable,
            UNIPANEL_ASSISTANT_CARD_DISPLAY_MS
        )
    }

    /**
     * Place the Gemini output card in the measured lane between the red
     * camera preview and the tiered HUD text. This keeps it on top of the
     * WebView without covering either overlay control:
     *
     *   camera preview | 8dp gap | Gemini card | 8dp gap | events/tasks/news
     *
     * If the right HUD panel has not measured yet, fall back to the parent
     * right edge. If the lane is unusually narrow, keep a readable minimum
     * width and let the card stop before the panel on the next layout pass.
     */
    private fun repositionUnipanelAssistantCard() {
        val card = findViewById<View?>(R.id.unipanelMiniCardScroll) ?: return
        val overlay = findViewById<ViewGroup?>(R.id.unipanelOverlay) ?: return
        val camera = findViewById<View?>(R.id.unipanelCameraPreviewFrame)
        val tierPanel = findViewById<View?>(R.id.unipanelHudTierPanel)
        val heartbeat = findViewById<View?>(R.id.unipanelHudHeartbeatText)

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val left = ((camera?.left ?: dp(14)) + (camera?.width?.takeIf { it > 0 } ?: dp(96)) + dp(8))
            .coerceAtLeast(dp(118))
        val rightLimit = (tierPanel?.left?.takeIf { it > 0 } ?: (overlay.width - dp(8))) - dp(8)
        val minWidth = dp(180)
        val maxWidth = dp(360)
        val available = (rightLimit - left).coerceAtLeast(minWidth)
        val width = available.coerceAtMost(maxWidth)

        val heartbeatVisible = heartbeat != null && heartbeat.visibility == View.VISIBLE
        val top = if (heartbeatVisible) dp(70) else dp(50)
        val height = dp(76)

        val lp = card.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        var changed = false
        if (lp.leftMargin != left) {
            lp.leftMargin = left
            changed = true
        }
        if (lp.topMargin != top) {
            lp.topMargin = top
            changed = true
        }
        if (lp.width != width) {
            lp.width = width
            changed = true
        }
        if (lp.height != height) {
            lp.height = height
            changed = true
        }
        if (lp.gravity != (Gravity.TOP or Gravity.START)) {
            lp.gravity = Gravity.TOP or Gravity.START
            changed = true
        }
        if (changed) card.layoutParams = lp
    }

    /**
     * Phase 4f (Mars revision) — the legacy top-right CAM chip is
     * gone (Mars: "two red dots, only the single red dot should
     * appear"). The Phase 4e [startUnipanelVisionDotObserver] subscriber
     * on the HUD-strip dot is the sole vision-active indicator now.
     *
     * Field + stub are kept so the onCreate / onDestroy bookkeeping
     * sites that reference them don't need to be torn out; both
     * resolve cleanly and the unsubscribe in onDestroy is a no-op.
     */
    private var unipanelCameraChipSubscription: AutoCloseable? = null

    private fun startUnipanelCameraChipObserver() {
        // No-op — chip View deleted from layout in Phase 4f.
    }

    /**
     * Unipanel v2 Phase 6 — voice toggle pill in the HUD strip.
     *
     * Subscribes to [com.TapLink.app.unipanel.HudStateBridge] for visual
     * feedback (tints the pill by VoicePhase) and registers an onClick
     * that toggles voice via the bound [com.TapLink.app.unipanel.VoiceServiceApi].
     *
     * Pill placement matches codex's HUD spec:
     *   - Sits inside the HUD strip (which is opaque-inert today; the
     *     pill itself is opaque-clickable so it owns its taps).
     *   - Doesn't cover dashboard tiles — strip is wrap_content and
     *     pinned top-center.
     *   - Tap is the only gesture; no long-press to keep things simple.
     *
     * Phase 4 will make activateVoice actually start the audio pipeline.
     * Phase 3 stubs in the Service just bounce a HudStateBridge state
     * change so Mars can verify the bind path: tap pill → red, tap
     * again → blue-grey. If color flips on tap, the binder is alive
     * end-to-end.
     */
    private var unipanelVoicePillSubscription: AutoCloseable? = null

    private fun startUnipanelVoicePill() {
        // Phase 4k — the "MIC" pill became the Hermes-style avatar orb.
        // The orb container owns the tap; the rest of the HUD strip is
        // still a generous fallback tap surface for the same toggle.
        val orb = findViewById<View?>(R.id.unipanelVoiceOrb)
        if (orb == null) {
            DebugLog.w(
                "Unipanel",
                "Voice orb: view missing, skipping subscription"
            )
            return
        }
        val toggleHandler = View.OnClickListener {
            val api = voiceServiceApi
            if (api == null) {
                DebugLog.w(
                    "VoiceBind",
                    "Voice toggle tap: voiceServiceApi is null — queuing activation until bind"
                )
                // Don't drop the tap: remember it so onServiceConnected can
                // start the session the moment the binder arrives.
                pendingVoiceActivateUntilMs = SystemClock.uptimeMillis() + 4000L
                runCatching { startVoiceServiceBinding() }
                return@OnClickListener
            }
            val phase = com.TapLink.app.unipanel.HudStateBridge.current().phase
            if (phase == com.TapLink.app.unipanel.HudStateBridge.VoicePhase.IDLE) {
                DebugLog.d("VoiceBind", "Voice toggle: activateVoice()")
                api.activateVoice()
            } else {
                DebugLog.d("VoiceBind", "Voice toggle (phase=$phase): shutdownVoice()")
                api.shutdownVoice()
            }
        }
        // ONLY the avatar orb activates / cancels Gemini (Mars: "only if I
        // tap the avatar should it activate gemini"). Previously the whole
        // top HUD row + feed strip + clock strip shared this handler, so a
        // tap on empty HUD space activated voice by accident.
        orb.setOnClickListener(toggleHandler)
        // The clock / status pill is not the avatar, so it follows the
        // predefined gesture instead: toggle the browser view.
        val browserToggleHandler = View.OnClickListener {
            if (browserPanelHidden) showBrowserPanel() else hideBrowserPanel()
        }
        findViewById<View?>(R.id.unipanelHudStrip)?.setOnClickListener(browserToggleHandler)
        // Transparent empty regions must NOT intercept the tap at all — drop
        // any previously-installed handler and make them non-clickable so a
        // tap on genuinely empty space falls through the overlay hit-test to
        // the standard browser toggle in the cursor pipeline.
        findViewById<View?>(R.id.unipanelHudFeedStrip)?.let {
            it.setOnClickListener(null); it.isClickable = false
        }
        findViewById<View?>(R.id.unipanelTopHudRow)?.let {
            it.setOnClickListener(null); it.isClickable = false
        }

        // Load the avatar image (custom orb if the user uploaded one via
        // the companion app, else the default earth orb) and clip round.
        applyUnipanelVoiceOrbImage()

        unipanelVoicePillSubscription?.runCatching { close() }
        unipanelVoicePillSubscription =
            com.TapLink.app.unipanel.HudStateBridge.observe { state ->
                uiHandler.post {
                    renderUnipanelVoiceOrb(state)
                    updateMinimalIndicators()
                }
            }
    }

    /** Last camera on/off state from CameraStateBridge, for the minimal
     *  indicator (the bridge only delivers deltas). */
    @Volatile
    private var unipanelCameraOnState = false

    /**
     * Phase 4z — minimal status indicators shown ONLY when the full HUD/chat
     * overlay is hidden (rolled up via double-tap, or hidden in dim mode).
     * Red dot = Gemini session active; tiny red camera next to it = camera
     * streaming. When the HUD is showing, the normal HUD carries the cues, so
     * these stay hidden.
     */
    private fun updateMinimalIndicators() {
        val container = findViewById<View?>(R.id.unipanelMinIndicator) ?: return
        val dot = findViewById<View?>(R.id.unipanelMinGeminiDot)
        val cam = findViewById<View?>(R.id.unipanelMinCameraIcon)
        val overlay = findViewById<View?>(R.id.unipanelOverlay)
        val hudHidden = hudRolledUp || overlay == null || overlay.visibility != View.VISIBLE
        val geminiActive = com.TapLink.app.unipanel.HudStateBridge.current().phase !=
            com.TapLink.app.unipanel.HudStateBridge.VoicePhase.IDLE
        val showDot = hudHidden && geminiActive
        val showCam = hudHidden && unipanelCameraOnState
        dot?.visibility = if (showDot) View.VISIBLE else View.GONE
        cam?.visibility = if (showCam) View.VISIBLE else View.GONE
        container.visibility = if (showDot || showCam) View.VISIBLE else View.GONE
    }

    private fun isGeminiExitSurfaceActive(): Boolean {
        val hudState = com.TapLink.app.unipanel.HudStateBridge.current()
        val voiceActive =
            hudState.phase != com.TapLink.app.unipanel.HudStateBridge.VoicePhase.IDLE ||
                hudState.connection == com.TapLink.app.unipanel.HudStateBridge.ConnectionStatus.CONNECTING ||
                hudState.connection == com.TapLink.app.unipanel.HudStateBridge.ConnectionStatus.GEMINI_CONNECTED ||
                hudState.connection == com.TapLink.app.unipanel.HudStateBridge.ConnectionStatus.TOOLS_READY
        val cameraActive = runCatching {
            voiceServiceApi?.isCameraOn() == true ||
                com.TapLink.app.unipanel.CameraStateBridge.current()
        }.getOrDefault(false)
        val chatBubbleOpen =
            findViewById<View?>(R.id.unipanelMiniCardScroll)?.visibility == View.VISIBLE ||
                com.TapLink.app.unipanel.ChatCardBridge.current().any {
                    !it.fromUser && it.text.isNotBlank() &&
                        it.timestampMs > unipanelAssistantCardDismissedThroughMs
                }
        return voiceActive || cameraActive || chatBubbleOpen
    }

    /**
     * Phase 4aa — full Gemini exit (right-arm double-tap, when a session is
     * active): close the voice session, turn the camera off if it's on, close
     * the chat bubble, and let the avatar return to its non-active state. Each
     * step is guarded so it's safe even if some part isn't currently up.
     */
    private fun exitGeminiFully() {
        val api = voiceServiceApi
        val lastAssistantTimestamp = com.TapLink.app.unipanel.ChatCardBridge.current()
            .asSequence()
            .filter { !it.fromUser && it.text.isNotBlank() }
            .map { it.timestampMs }
            .maxOrNull()
            ?: System.currentTimeMillis()
        unipanelAssistantCardDismissedThroughMs =
            maxOf(unipanelAssistantCardDismissedThroughMs, lastAssistantTimestamp)

        runCatching { if (api?.isCameraOn() == true) api.toggleCamera() }
        runCatching { api?.shutdownVoice() }
        com.TapLink.app.unipanel.HudStateBridge.update {
            it.copy(
                phase = com.TapLink.app.unipanel.HudStateBridge.VoicePhase.IDLE,
                connection = com.TapLink.app.unipanel.HudStateBridge.ConnectionStatus.IDLE,
                transcript = null,
                oscilloscopeLevel = 0f,
                notification = null
            )
        }
        runCatching { com.TapLink.app.unipanel.CameraStateBridge.publish(false) }
        unipanelCameraOnState = false
        runCatching {
            findViewById<View?>(R.id.unipanelCameraPreviewFrame)?.visibility = View.GONE
            findViewById<View?>(R.id.unipanelVisionDot)?.visibility = View.GONE
        }
        // Close the chat bubble locally and suppress the current card so a
        // late ViewModel/ChatCardBridge publish can't reopen it after exit.
        runCatching { com.TapLink.app.unipanel.ChatCardBridge.publish(emptyList()) }
        uiHandler.removeCallbacks(hideUnipanelAssistantCardRunnable)
        runCatching {
            findViewById<View?>(R.id.unipanelMiniCardScroll)?.visibility = View.GONE
            findViewById<android.widget.TextView?>(R.id.unipanelMiniCard1)?.text = ""
        }
        // The avatar ring returns to idle via HudStateBridge phase -> IDLE
        // (published by shutdownVoice). Refresh the minimal corner indicators.
        runCatching { updateMinimalIndicators() }
        DebugLog.d(
            "DoubleTapDebug",
            "Full Gemini exit (session + camera + chat bubble + avatar)"
        )
    }

    private var browserCommandSubscription: AutoCloseable? = null

    /**
     * Phase 4v — observe one-shot browser commands from the voice pipeline.
     * Currently just "toggle reader mode": when the user asks Gemini to
     * render the shown page in reader mode, reflow the current WebView page
     * into a clean dark reader view (and toggle back on a second request).
     */
    private fun startBrowserCommandObserver() {
        browserCommandSubscription?.runCatching { close() }
        browserCommandSubscription =
            com.TapLink.app.unipanel.BrowserCommandBridge.observe { enabled ->
                uiHandler.post { applyReaderModeToCurrentPage(enabled) }
            }
    }

    /**
     * Enter ([enabled]=true) or exit reader mode on the current WebView.
     * Entering extracts the article (paragraph-density heuristic, à la
     * Safari/Firefox Reader), strips chrome/ads, and re-renders it in a bold
     * monospace dark "lynx-style" view tuned for the RayNeo X3 Pro. Reader
     * mode is sticky — it stays until [enabled]=false, which reloads the
     * page to restore the original.
     */
    private fun applyReaderModeToCurrentPage(enabled: Boolean) {
        if (!::dualWebViewGroup.isInitialized) return
        // Make sure the browser is actually visible so the result is seen.
        runCatching { if (browserPanelHidden) showBrowserPanel() }
        val js = if (enabled) READER_MODE_JS else READER_MODE_EXIT_JS
        runCatching { webView.evaluateJavascript(js, null) }
        DebugLog.d("ReaderMode", "Reader mode ${if (enabled) "ENTER" else "EXIT"} injected")
    }

    /**
     * Phase 4k — load the avatar orb bitmap and apply a circular clip.
     * The custom orb PNG lives in the application's private filesDir
     * (written by the companion server's /api/orb/upload). tapbrowser
     * can't import visionclaw's OrbImageStore, but both modules run in
     * the same process so `filesDir` resolves to the same directory —
     * we read the file path directly. Falls back to the bundled earth
     * orb vector when no custom image is present.
     */
    private fun applyUnipanelVoiceOrbImage() {
        val img = findViewById<android.widget.ImageView?>(R.id.unipanelVoiceOrbImage) ?: return
        img.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        img.clipToOutline = true
        val custom = java.io.File(filesDir, "custom_orb.png")
        val bmp = if (custom.exists()) {
            runCatching { android.graphics.BitmapFactory.decodeFile(custom.absolutePath) }
                .getOrNull()
        } else {
            null
        }
        if (bmp != null) {
            img.setImageBitmap(bmp)
        } else {
            img.setImageResource(R.drawable.ic_unipanel_earth_orb)
        }
    }

    /** Phase 4d (Mars revision) — replaces the manual CAM pill with
     *  a passive red-dot indicator. Lights up while CameraX is
     *  streaming frames into the Live session OR while browser_vision
     *  is in flight. No tap handler — vision is voice-triggered now. */
    private var unipanelVisionDotSubscription: AutoCloseable? = null

    /** Phase 4h — AI status badge ("G") tinted from HudStateBridge.
     *  Green = Gemini connected, amber = connecting, red = error/idle. */
    private var unipanelHudAiBadgeSubscription: AutoCloseable? = null

    private fun startUnipanelHudAiBadgeObserver() {
        val badge = findViewById<android.widget.TextView?>(R.id.unipanelHudAiBadge) ?: return
        unipanelHudAiBadgeSubscription?.runCatching { close() }
        unipanelHudAiBadgeSubscription =
            com.TapLink.app.unipanel.HudStateBridge.observe { state ->
                uiHandler.post {
                    // Bug fix — the "G" reflects Gemini API health, not
                    // whether a turn is in progress. It stays GREEN
                    // whenever the API is usable (including IDLE between
                    // turns / before a session) — only amber while
                    // actively connecting and red on a real error. Before,
                    // IDLE turned it blue-grey, so G "switched off" every
                    // time Gemini stopped talking.
                    val tint = when (state.connection) {
                        com.TapLink.app.unipanel.HudStateBridge.ConnectionStatus
                            .CONNECTING -> 0xFFFFB347.toInt()          // amber
                        com.TapLink.app.unipanel.HudStateBridge.ConnectionStatus
                            .DEGRADED,
                        com.TapLink.app.unipanel.HudStateBridge.ConnectionStatus
                            .ERROR -> 0xFFE57373.toInt()               // red
                        else -> 0xFF34D399.toInt()                     // green (healthy / idle)
                    }
                    runCatching {
                        badge.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(tint)
                    }
                }
            }
    }

    private var unipanelHudStateSubscription: AutoCloseable? = null
    private var unipanelHeartbeatScrollAnimator: android.animation.ValueAnimator? = null
    private var unipanelHeartbeatClearRunnable: Runnable? = null

    // ── Heartbeat ticker anti-flash throttle ─────────────────────────────
    // renderUnipanelHeartbeat runs on EVERY HUD state update; without a hold
    // a burst of rapidly-changing heartbeat messages reset the ticker (text +
    // scroll) many times a second, so it flashed by too fast to read. Hold
    // each DISTINCT message on screen for at least this long, coalescing to
    // the latest message. Clears (empty text) bypass the hold so exit is
    // immediate.
    private val UNIPANEL_HEARTBEAT_MIN_HOLD_MS = 2_000L
    private var lastHeartbeatRenderedText: String? = null
    private var lastHeartbeatRenderedAtMs: Long = 0L
    private var pendingHeartbeatRenderRunnable: Runnable? = null

    private val hideUnipanelHeartbeatRunnable = Runnable {
        val tv = findViewById<android.widget.TextView?>(R.id.unipanelHudHeartbeatText) ?: return@Runnable
        unipanelHeartbeatScrollAnimator?.cancel()
        unipanelHeartbeatScrollAnimator = null
        tv.animate().cancel()
        tv.visibility = View.GONE
        tv.alpha = 1f
        tv.scrollX = 0
        repositionUnipanelAssistantCard()
    }

    private fun startUnipanelHudStateObserver() {
        unipanelHudStateSubscription?.runCatching { close() }
        unipanelHudStateSubscription =
            com.TapLink.app.unipanel.HudStateBridge.observe { state ->
                uiHandler.post { renderUnipanelHudState(state) }
            }
    }

    private fun renderUnipanelHudState(
        state: com.TapLink.app.unipanel.HudStateBridge.State
    ) {
        renderUnipanelTieredHud(state)

        findViewById<android.widget.TextView?>(R.id.unipanelHudAqi)?.let { aqi ->
            // Mars: drop the long descriptive label — show just "AQI <n>",
            // colour-coded (green healthy / yellow moderate / red unhealthy).
            val value = state.airQualityValue
            aqi.text = "AQI ${value ?: "--"}"
            aqi.setTextColor(colorForUnipanelAqi(value))
        }

        renderUnipanelGatewayBadge(
            badge = findViewById(R.id.unipanelHudHermesBadge),
            status = state.hermesStatus
        )
        renderUnipanelGatewayBadge(
            badge = findViewById(R.id.unipanelHudOpenClawBadge),
            status = state.openClawStatus
        )
        renderUnipanelHeartbeat(state)
    }

    /**
     * Phase 4k.3 — render the tiered HUD info panel (Calendar Events /
     * Tasks &amp; Reminders / News Headlines) in the order the user set in
     * the companion app (state.hudDisplayOrder). Each row is a colour-keyed
     * pill: a bold label in the category colour followed by the value in
     * soft white, two-line ellipsized so nothing scrolls off-screen.
     */
    private data class UnipanelHudTier(val label: String, val value: String, val color: Int)

    private fun renderUnipanelTieredHud(
        state: com.TapLink.app.unipanel.HudStateBridge.State
    ) {
        val slots = listOf(
            findViewById<android.widget.TextView?>(R.id.unipanelHudTier0),
            findViewById<android.widget.TextView?>(R.id.unipanelHudTier1),
            findViewById<android.widget.TextView?>(R.id.unipanelHudTier2)
        )
        val order = state.hudDisplayOrder
            .split(",")
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("calendar", "tasks", "news") }
        val tiers = order.mapNotNull { key ->
            when (key) {
                "calendar" -> UnipanelHudTier(
                    "Events", unipanelHudFieldBody("Events", state.calendarSummary),
                    0xFF00E5FF.toInt()
                )
                "tasks" -> UnipanelHudTier(
                    "Tasks", unipanelHudFieldBody("Tasks", state.tasksSummary),
                    0xFF00E676.toInt()
                )
                "news" -> UnipanelHudTier(
                    "News", unipanelHudFieldBody("News", state.newsSummary),
                    0xFFFFB14A.toInt()
                )
                else -> null
            }
        }
        for (i in slots.indices) {
            val slot = slots[i] ?: continue
            val tier = tiers.getOrNull(i)
            if (tier == null) {
                slot.visibility = View.GONE
            } else {
                slot.text = buildUnipanelTierLine(tier)
                slot.visibility = View.VISIBLE
            }
        }
        findViewById<View?>(R.id.unipanelHudTierPanel)?.post {
            repositionUnipanelAssistantCard()
        }
    }

    /** Bold colour-keyed label + soft-white value for one tiered HUD row. */
    private fun buildUnipanelTierLine(
        tier: UnipanelHudTier
    ): CharSequence {
        val label = tier.label.uppercase(Locale.US)
        val sb = android.text.SpannableStringBuilder()
        sb.append(label)
        sb.setSpan(
            android.text.style.ForegroundColorSpan(tier.color),
            0, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            0, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.append("  ")
        val valueStart = sb.length
        sb.append(tier.value)
        sb.setSpan(
            android.text.style.ForegroundColorSpan(0xFFE6EEF5.toInt()),
            valueStart, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return sb
    }

    private fun unipanelHudFieldBody(label: String, raw: String): String {
        val body = raw
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("$label:")
            .removePrefix(label.uppercase(Locale.US) + ":")
            .trim()
        return if (body.isBlank()) "—" else body.take(90)
    }

    private fun colorForUnipanelAqi(aqi: Int?): Int {
        val value = aqi ?: return 0xCCFFFFFF.toInt()
        // Google's Universal AQI is 0–100 where HIGHER is CLEANER, so the
        // colour bands run the opposite way to the US EPA scale:
        //   >= 60  → healthy   (green)
        //   40–59  → moderate  (yellow)
        //   < 40   → unhealthy (red)
        return when {
            value >= 60 -> 0xFF00E676.toInt()  // green — healthy
            value >= 40 -> 0xFFFFD54F.toInt()  // yellow — moderate
            else -> 0xFFFF5252.toInt()         // red — unhealthy
        }
    }

    private fun renderUnipanelGatewayBadge(
        badge: android.widget.TextView?,
        status: com.TapLink.app.unipanel.HudStateBridge.GatewayStatus
    ) {
        val visible = status != com.TapLink.app.unipanel.HudStateBridge.GatewayStatus.HIDDEN
        val tint = when (status) {
            com.TapLink.app.unipanel.HudStateBridge.GatewayStatus.GOOD -> 0xFF00E676.toInt()
            com.TapLink.app.unipanel.HudStateBridge.GatewayStatus.BAD -> 0xFFFF5B5B.toInt()
            com.TapLink.app.unipanel.HudStateBridge.GatewayStatus.HIDDEN -> 0x00000000
        }
        // Phase 4k — the standalone status dot was removed; the "H"
        // badge alone now carries the green/red Hermes-reachable colour.
        badge?.visibility = if (visible) View.VISIBLE else View.GONE
        runCatching {
            badge?.backgroundTintList = android.content.res.ColorStateList.valueOf(tint)
        }
    }

    private fun renderUnipanelHeartbeat(
        state: com.TapLink.app.unipanel.HudStateBridge.State
    ) {
        val tv = findViewById<android.widget.TextView?>(R.id.unipanelHudHeartbeatText) ?: return
        val notification = state.notification?.trim().takeUnless { it.isNullOrBlank() }
        val bridgeMessage = state.heartbeatMessage?.trim().takeUnless { it.isNullOrBlank() }
        val text = notification ?: bridgeMessage
        // Nothing actually changed since the last APPLIED render — leave the
        // ticker alone so unrelated HUD updates (clock, AQI, badges) can't
        // restart the scroll or the clear timer.
        if (text == lastHeartbeatRenderedText) {
            return
        }

        // Uniform anti-flash cadence: apply at most ONE visible change — a new
        // message, a message swap, OR a hide — every UNIPANEL_HEARTBEAT_MIN_HOLD_MS.
        // A burst of rapidly-changing (or rapidly toggling on/off) heartbeat
        // states collapses to whatever the latest state is when the window
        // elapses. This is what stops the ticker flashing by too fast to read.
        // (The earlier version let clears bypass the hold, so message→null→
        //  message bursts still flashed.)
        val nowMs = SystemClock.uptimeMillis()
        val heldFor = nowMs - lastHeartbeatRenderedAtMs
        if (lastHeartbeatRenderedAtMs != 0L && heldFor < UNIPANEL_HEARTBEAT_MIN_HOLD_MS) {
            pendingHeartbeatRenderRunnable?.let { uiHandler.removeCallbacks(it) }
            val deferred = Runnable {
                pendingHeartbeatRenderRunnable = null
                renderUnipanelHeartbeat(com.TapLink.app.unipanel.HudStateBridge.current())
            }
            pendingHeartbeatRenderRunnable = deferred
            uiHandler.postDelayed(deferred, UNIPANEL_HEARTBEAT_MIN_HOLD_MS - heldFor)
            return
        }

        // Apply now — cancel any pending timers and record the apply time.
        pendingHeartbeatRenderRunnable?.let { uiHandler.removeCallbacks(it) }
        pendingHeartbeatRenderRunnable = null
        uiHandler.removeCallbacks(hideUnipanelHeartbeatRunnable)
        unipanelHeartbeatClearRunnable?.let { uiHandler.removeCallbacks(it) }
        unipanelHeartbeatClearRunnable = null
        lastHeartbeatRenderedText = text
        lastHeartbeatRenderedAtMs = nowMs

        if (text.isNullOrBlank()) {
            hideUnipanelHeartbeatRunnable.run()
            // Phase 4j — ticker just collapsed; if the camera preview
            // is up, pull it back toward the clock strip.
            repositionUnipanelCameraPreview()
            return
        }

        tv.text = "♥ $text"
        tv.visibility = View.VISIBLE
        tv.alpha = 1f
        tv.scrollX = 0
        unipanelHeartbeatScrollAnimator?.cancel()
        unipanelHeartbeatScrollAnimator = null
        val shouldScroll = notification != null || state.heartbeatShouldScroll
        if (shouldScroll) tv.post { startUnipanelHeartbeatScroll(tv) }
        // Phase 4j — ticker just appeared under the clock; push the
        // camera preview (if visible) down so it clears the new bar.
        repositionUnipanelCameraPreview()
        repositionUnipanelAssistantCard()
        val transient = notification != null || !state.heartbeatPersistent
        if (transient) {
            val clearRunnable = Runnable {
                val current = com.TapLink.app.unipanel.HudStateBridge.current()
                when {
                    notification != null && current.notification?.trim() == notification ->
                        com.TapLink.app.unipanel.HudStateBridge.update {
                            it.copy(notification = null)
                        }
                    notification == null &&
                        !current.heartbeatPersistent &&
                        current.heartbeatMessage?.trim() == bridgeMessage ->
                            com.TapLink.app.unipanel.HudStateBridge.update {
                                it.copy(heartbeatMessage = null)
                            }
                    else -> hideUnipanelHeartbeatRunnable.run()
                }
            }
            unipanelHeartbeatClearRunnable = clearRunnable
            uiHandler.postDelayed(clearRunnable, UNIPANEL_HEARTBEAT_DISPLAY_MS)
        }
    }

    private fun startUnipanelHeartbeatScroll(tv: android.widget.TextView) {
        if (tv.visibility != View.VISIBLE) return
        val innerWidth = tv.width - tv.paddingLeft - tv.paddingRight
        if (innerWidth <= 0) return
        val textWidth = tv.paint.measureText(tv.text?.toString().orEmpty())
        val scrollEnd = (textWidth - innerWidth).toInt()
        if (scrollEnd <= 0) {
            tv.scrollX = 0
            return
        }
        val pxPerSecond = (30f * resources.displayMetrics.density).coerceAtLeast(40f)
        unipanelHeartbeatScrollAnimator =
            android.animation.ValueAnimator.ofInt(0, scrollEnd).apply {
                duration = ((scrollEnd / pxPerSecond) * 1000f)
                    .toLong()
                    .coerceIn(1500L, 20_000L)
                startDelay = 600L
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { anim ->
                    tv.scrollX = anim.animatedValue as Int
                }
                start()
            }
    }

    /**
     * Phase 4j — drop the left-gutter camera preview frame to just
     * below the live HUD column. Anchors to the heartbeat ticker when
     * it's visible, otherwise the clock strip, so the preview always
     * clears whatever the HUD is currently showing instead of relying
     * on the old load-bearing 60dp XML margin (which collided with the
     * heartbeat once it moved under the clock). No-op while the frame
     * is hidden. The preview stays in the left gutter; the chat card
     * is centered, so the two never overlap horizontally.
     */
    private fun repositionUnipanelCameraPreview(forceBelowHeartbeat: Boolean = false) {
        val preview = findViewById<View?>(R.id.unipanelCameraPreviewFrame) ?: return
        if (preview.visibility != View.VISIBLE) return
        val heartbeat = findViewById<View?>(R.id.unipanelHudHeartbeatText)
        // Phase 4k.4 (Mars) — restore the two-stage placement that worked
        // best: while the heartbeat ticker is showing ("Camera streaming
        // to Gemini"), the preview sits just BELOW it; once the ticker
        // auto-clears, the preview rises to 50dp under the clock. The only
        // problem before was the lower stage (78dp) reaching down onto the
        // dashboard tiles, so the initial stage is nudged up to 70dp —
        // still clear of the heartbeat text (which ends ~69dp) but high
        // enough to clear the browser tiles below. The heartbeat row spans
        // ~50–72dp, so 70dp keeps the ticker readable directly above.
        // dp/layout-space margins are scale-correct under the SBS transform
        // (window-coordinate math from earlier builds was not).
        val density = resources.displayMetrics.density
        // Phase 4k.9 — when the camera first turns on, force the BELOW-
        // heartbeat stage (70dp) even if the "Camera streaming to Gemini"
        // ticker hasn't published yet. Otherwise, depending on event
        // order, the preview could momentarily land at 50dp — right on
        // top of the heartbeat text. The preview only rises to 50dp once
        // the ticker has actually cleared (renderUnipanelHeartbeat's empty
        // branch calls this with the default false).
        val heartbeatShown = forceBelowHeartbeat ||
            (heartbeat != null && heartbeat.visibility == View.VISIBLE)
        val topDp = if (heartbeatShown) 70f else 50f
        val newTop = (topDp * density).toInt()
        val lp = preview.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        if (lp.topMargin != newTop) {
            lp.topMargin = newTop
            preview.layoutParams = lp
        }
        repositionUnipanelAssistantCard()
    }

    private fun startUnipanelVisionDotObserver() {
        val dot = findViewById<View?>(R.id.unipanelVisionDot) ?: return
        val previewFrame = findViewById<View?>(R.id.unipanelCameraPreviewFrame)
        unipanelVisionDotSubscription?.runCatching { close() }
        unipanelVisionDotSubscription =
            com.TapLink.app.unipanel.CameraStateBridge.observe { on ->
                uiHandler.post {
                    dot.visibility = if (on) View.VISIBLE else View.GONE
                    // Phase 4g — the 96dp × 72dp burgundy preview
                    // frame follows the same on/off state. Its
                    // PreviewView is fed by CameraX inside the
                    // Service via setCameraPreviewSurfaceProvider
                    // (wired in startUnipanelCameraPreviewBinding).
                    previewFrame?.visibility = if (on) View.VISIBLE else View.GONE
                    // Mars: the camera ticker was removed, so on enable the
                    // preview goes straight to its FINAL position (50dp under
                    // the clock) — no below-ticker stage / bounce.
                    if (on) repositionUnipanelCameraPreview()
                    unipanelCameraOnState = on
                    updateMinimalIndicators()
                }
            }
    }

    /** Phase 4g — hand the PreviewView's SurfaceProvider to the
     *  Service so the next camera start binds a Preview use case
     *  and the user sees a live feed in the unipanel frame. */
    private fun startUnipanelCameraPreviewBinding() {
        val previewView = findViewById<androidx.camera.view.PreviewView?>(
            R.id.unipanelCameraPreviewView
        ) ?: return
        // The implementation mode default is "PERFORMANCE" which
        // uses a SurfaceView under the hood — best for low-latency
        // multimodal video. COMPATIBLE (TextureView-backed) would
        // also work but adds an extra GPU copy.
        previewView.implementationMode =
            androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
        // Pre-install on bind so a later toggleCamera() picks it up
        // even if voice/camera haven't activated yet. ServiceConnection
        // already runs us through onServiceConnected before this code,
        // but we guard with null in case the bind hasn't resolved.
        val api = voiceServiceApi
        if (api != null) {
            runCatching { api.setCameraPreviewSurfaceProvider(previewView.surfaceProvider) }
        }
    }

    /**
     * Phase 4k — pure view update for the avatar orb's glow halo.
     * Mirrors ChatPanelFragment.pushVoiceOscilloscope: red halo while
     * the user speaks, blue while Gemini speaks, hidden when idle. The
     * halo alpha tracks the oscilloscope level so it pulses with speech
     * loudness. The orb image itself stays put — only the halo carries
     * the voice-activity colour. Called on the UI thread by the bridge
     * subscriber.
     */
    private fun renderUnipanelVoiceOrb(
        state: com.TapLink.app.unipanel.HudStateBridge.State
    ) {
        val glow = findViewById<View?>(R.id.unipanelVoiceOrbGlow) ?: return
        // Phase 4k.10 — the avatar ring must make voice state UNMISTAKABLE
        // (Mars' repeated request). Three visually-distinct states, no
        // longer a washed-out soft radial that idle and thinking shared:
        //
        //   IDLE       → thin DIM steel ring (clearly "off / standby")
        //   LISTENING  → BOLD bright RED ring  (you are being heard)
        //   FOLLOW_UP  → BOLD bright RED ring  (still listening)
        //   THINKING   → BOLD bright GREEN ring (Gemini is talking back)
        //
        // Red vs green is the clearest possible contrast; the idle ring is
        // thin/dim so the lit active rings dominate. On top of the colour
        // swap the ring breathes — scaled up and brightened by the live
        // oscilloscope level — so an active state is obvious even in
        // peripheral vision on the glasses HUD.
        val phase = state.phase
        val idle = phase == com.TapLink.app.unipanel.HudStateBridge.VoicePhase.IDLE
        val listening =
            phase == com.TapLink.app.unipanel.HudStateBridge.VoicePhase.LISTENING ||
                phase == com.TapLink.app.unipanel.HudStateBridge.VoicePhase.FOLLOW_UP
        glow.setBackgroundResource(
            when {
                idle -> R.drawable.bg_unipanel_orb_ring_idle
                listening -> R.drawable.bg_unipanel_orb_ring_red
                else -> R.drawable.bg_unipanel_orb_ring_green
            }
        )
        val level = state.oscilloscopeLevel.coerceIn(0f, 1f)
        if (idle) {
            glow.alpha = 0.55f
            glow.scaleX = 1f
            glow.scaleY = 1f
        } else {
            // Active rings sit near-opaque and pulse outward with audio so
            // the lit ring visibly grows while you speak / Gemini replies.
            glow.alpha = (0.85f + level * 0.15f).coerceIn(0f, 1f)
            val scale = 1f + level * 0.22f
            glow.scaleX = scale
            glow.scaleY = scale
        }
    }

    /**
     * Pure function over a snapshot — assigns the three slots from
     * the tail of [cards] (newest at index 0). Called only on the
     * UI thread by [startUnipanelMiniCardObserver].
     */
    private fun renderUnipanelMiniCards(
        slots: Array<android.widget.TextView>,
        cards: List<com.TapLink.app.unipanel.ChatCardBridge.Card>
    ) {
        val n = cards.size
        for (i in slots.indices) {
            val tv = slots[i]
            val srcIndex = n - 1 - i
            if (srcIndex < 0) {
                tv.visibility = View.GONE
                continue
            }
            val card = cards[srcIndex]
            tv.text = card.text
            tv.setBackgroundResource(
                if (card.fromUser) R.drawable.bg_unipanel_mini_card_user
                else R.drawable.bg_unipanel_mini_card_assistant
            )
            tv.visibility = View.VISIBLE
        }
    }

    /**
     * Phase 2 unipanel overlay hit-test.
     *
     * The cursor-tap pipeline above bypasses normal Android view-tree
     * dispatch — it builds a known list of custom UI surfaces, hit-
     * tests each, and falls through to the WebView when nothing
     * matches. The unipanel overlay (FrameLayout @+id/unipanelOverlay)
     * was added in Step 2a but isn't on that list, so widgets inside
     * it never see taps even though they're visually on top of the
     * WebView.
     *
     * Step 2b.2 added interactive routing. Step 2c.2.1 (this) adds
     * the third state — inert visual surfaces. There are now three
     * categories of hit inside the overlay:
     *
     *   1. Empty transparent region   → fall through to WebView.
     *   2. Inert visual surface       → consume the tap, do nothing.
     *      (e.g. HUD strip, read-only chat-card placeholders)
     *   3. Interactive widget         → dispatch synthetic DOWN+UP.
     *
     * Without state (2), tapping on a chat card whose `clickable=false`
     * would fall through to whatever link/button the browser drew at
     * the same coordinates underneath — visually the card is opaque,
     * but logically the router sees nothing there. Codex caught this
     * the first time we shipped non-clickable cards in Step 2c.2.
     *
     * The signal we use to detect inert surfaces is "has a non-
     * transparent background drawable." That matches the natural
     * authoring idiom — if the layout draws a coloured pill/card
     * background, the designer wants it to read as solid, and taps
     * on it should not punch through to whatever's beneath. The
     * overlay container itself uses `@android:color/transparent`,
     * which we explicitly treat as still pass-through.
     *
     * Hit-test uses screen coordinates because the cursor pipeline
     * already converted [interactionX]/[interactionY] to absolute
     * screen space via the WebView's getLocationOnScreen offset.
     * `view.getLocationOnScreen()` returns the same coordinate
     * space, so a direct rect check works.
     */
    private fun dispatchUnipanelOverlayTouchIfHit(
        interactionX: Float,
        interactionY: Float
    ): Boolean {
        val hit = findUnipanelHit(interactionX, interactionY) ?: return false

        if (!hit.isInteractive) {
            DebugLog.d(
                "Unipanel",
                "Overlay consumed (inert) tap on ${hit.view.javaClass.simpleName} id=" +
                    "${try { resources.getResourceEntryName(hit.view.id) } catch (_: Exception) { "?" }} " +
                    "screen=($interactionX,$interactionY)"
            )
            return true
        }

        val targetLocation = IntArray(2)
        hit.view.getLocationOnScreen(targetLocation)
        val localX = interactionX - targetLocation[0]
        val localY = interactionY - targetLocation[1]
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, localX, localY, 0)
        val up = MotionEvent.obtain(now, now + 1L, MotionEvent.ACTION_UP, localX, localY, 0)
        try {
            hit.view.dispatchTouchEvent(down)
            hit.view.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        DebugLog.d(
            "Unipanel",
            "Overlay dispatched (interactive) to ${hit.view.javaClass.simpleName} id=" +
                "${try { resources.getResourceEntryName(hit.view.id) } catch (_: Exception) { "?" }} " +
                "screen=($interactionX,$interactionY) local=($localX,$localY)"
        )
        return true
    }

    private fun findUnipanelHit(screenX: Float, screenY: Float): UnipanelHit? {
        val overlay = findViewById<View?>(R.id.unipanelOverlay) ?: return null
        if (overlay.visibility != View.VISIBLE) return null
        return findUnipanelHitAt(overlay, screenX, screenY)
    }

    /**
     * Result of an overlay hit-test. [isInteractive] is true when the
     * resolved view is `clickable=true` (or has an OnClickListener,
     * which Android auto-flips to clickable). When false, the view is
     * an inert visual surface — consume the tap, do nothing.
     */
    private data class UnipanelHit(val view: View, val isInteractive: Boolean)

    /**
     * Depth-first search of [overlay]'s descendants for a view whose
     * on-screen bounds contain (x, y) AND that should consume the tap
     * (either clickable or a non-transparent surface). Walks children
     * in REVERSE order so visually-on-top siblings win when they
     * overlap (matches normal Android hit-test semantics).
     *
     * The [overlay] root itself is never returned — only descendants.
     * The container is supposed to be transparent everywhere it isn't
     * decorated by a child.
     */
    private fun findUnipanelHitAt(
        overlay: View,
        screenX: Float,
        screenY: Float
    ): UnipanelHit? {
        if (overlay !is android.view.ViewGroup) return null
        if (overlay.visibility != View.VISIBLE) return null
        for (i in overlay.childCount - 1 downTo 0) {
            val child = overlay.getChildAt(i) ?: continue
            val hit = findUnipanelHitDescendant(child, screenX, screenY)
            if (hit != null) return hit
        }
        return null
    }

    /**
     * Recursive helper for [findUnipanelHitAt]. Returns a hit if [root]
     * (or any of its descendants) is a valid claim target whose bounds
     * contain the cursor.
     */
    private fun findUnipanelHitDescendant(
        root: View,
        screenX: Float,
        screenY: Float
    ): UnipanelHit? {
        if (root.visibility != View.VISIBLE) return null
        // Recurse first. An INTERACTIVE descendant always wins (a real
        // button inside a container). But an INERT descendant must NOT
        // short-circuit a clickable ancestor: e.g. the voice orb is a
        // clickable FrameLayout whose glow child carries a background
        // drawable (an "inert surface"). Without this, the glow would
        // swallow the tap and the orb would never toggle voice. So we
        // remember the first inert descendant and only fall back to it
        // if no clickable view (descendant or ancestor) claims the tap.
        var inertDescendant: UnipanelHit? = null
        if (root is android.view.ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                val child = root.getChildAt(i) ?: continue
                val hit = findUnipanelHitDescendant(child, screenX, screenY)
                if (hit != null) {
                    if (hit.isInteractive) return hit
                    if (inertDescendant == null) inertDescendant = hit
                }
            }
        }
        val clickable = root.isClickable
        val surface = root.background?.let { !isTransparentBackground(it) } ?: false
        if (!clickable && !surface) return inertDescendant
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val left = loc[0].toFloat()
        val top = loc[1].toFloat()
        val right = left + root.width
        val bottom = top + root.height
        if (screenX < left || screenX >= right || screenY < top || screenY >= bottom) {
            return inertDescendant
        }
        // A clickable root beats any inert descendant; an inert root only
        // matters if nothing deeper already claimed the tap.
        if (clickable) return UnipanelHit(root, isInteractive = true)
        return inertDescendant ?: UnipanelHit(root, isInteractive = false)
    }

    /**
     * Returns true when the supplied background drawable resolves to
     * "no visible pixels" — i.e. a ColorDrawable with alpha 0 (which
     * is how `@android:color/transparent` decodes). Treated as still
     * pass-through so the overlay root, which uses that exact value
     * to stay invisible, doesn't accidentally swallow every tap.
     */
    private fun isTransparentBackground(bg: android.graphics.drawable.Drawable): Boolean {
        if (bg is android.graphics.drawable.ColorDrawable) {
            // The high byte is the alpha channel of the colour. A
            // fully-transparent colour means the drawable contributes
            // nothing visible.
            return ((bg.color ushr 24) and 0xFF) == 0
        }
        return false
    }

    /**
     * Current cursor position in absolute screen coordinates, using the
     * exact same anchored / non-anchored math as [dispatchTouchEventAtCursor].
     * Extracted so the single-tap path can hit-test the overlay even when
     * the cursor is hidden (idle-timed-out) without running the full
     * WebView click pipeline.
     */
    private fun currentCursorInteractionPoint(): Pair<Float, Float> {
        val groupLocation = IntArray(2)
        dualWebViewGroup.getLocationOnScreen(groupLocation)
        return if (isAnchored) {
            (320f + groupLocation[0]) to (240f + groupLocation[1])
        } else {
            val scale = dualWebViewGroup.uiScale
            val transX = dualWebViewGroup.leftEyeUIContainer.translationX
            val transY = dualWebViewGroup.leftEyeUIContainer.translationY
            val visualX = 320f + (lastCursorX - 320f) * scale + transX
            val visualY = 240f + (lastCursorY - 240f) * scale + transY
            (visualX + groupLocation[0]) to (visualY + groupLocation[1])
        }
    }

    private fun dispatchTouchEventAtCursor() {

        if (isSimulatingTouchEvent || cursorJustAppeared || isToggling) {
            return
        }

        // Don't let the enter key tap pass through to the webview when keyboard closes
        if (wasKeyboardDismissedByEnter) {
            wasKeyboardDismissedByEnter = false
            return
        }

        // Suppress any webview click immediately after keyboard dismissal (hide button).
        val now = SystemClock.uptimeMillis()
        if (now < suppressWebClickUntil) {
            return
        }

        val scale = dualWebViewGroup.uiScale
        val interactionX: Float
        val interactionY: Float
        val groupLocation = IntArray(2)
        dualWebViewGroup.getLocationOnScreen(groupLocation)

        if (isAnchored) {
            // In anchored mode, interaction center is always screen center of the eye
            interactionX = 320f + groupLocation[0]
            interactionY = 240f + groupLocation[1]
        } else {
            // In non-anchored mode, interaction follows the visual cursor scaled around (320, 240)
            // and translated
            val transX = dualWebViewGroup.leftEyeUIContainer.translationX
            val transY = dualWebViewGroup.leftEyeUIContainer.translationY

            val visualX = 320f + (lastCursorX - 320f) * scale + transX
            val visualY = 240f + (lastCursorY - 240f) * scale + transY

            interactionX = visualX + groupLocation[0]
            interactionY = visualY + groupLocation[1]
        }

        // Intercept touches for mask overlay buttons when screen is masked
        if (dualWebViewGroup.isScreenMasked()) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.dispatchMaskOverlayTouch(interactionX, interactionY)
            return
        }

        // Intercept touches for fullscreen overlay controls
        if (dualWebViewGroup.isFullScreenOverlayVisible()) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.dispatchFullScreenOverlayTouch(interactionX, interactionY)
            return
        }

        // Intercept touches for dialogs
        if (dualWebViewGroup.isDialogAction(interactionX, interactionY)) {
            val dialogContainer = dualWebViewGroup.dialogContainer
            val location = IntArray(2)
            dialogContainer.getLocationOnScreen(location)

            // Calculate local coordinates relative to dialog container
            val localX = (interactionX - location[0]) / scale
            val localY = (interactionY - location[1]) / scale

            // Dispatch DOWN
            val downEvent =
                    MotionEvent.obtain(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_DOWN,
                            localX,
                            localY,
                            0
                    )
            dialogContainer.dispatchTouchEvent(downEvent)
            downEvent.recycle()

            // Dispatch UP
            val upEvent =
                    MotionEvent.obtain(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_UP,
                            localX,
                            localY,
                            0
                    )
            dialogContainer.dispatchTouchEvent(upEvent)
            upEvent.recycle()
            suppressImmediateWebClickLeak()
            return
        }

        // Check if settings menu is visible first
        if (dualWebViewGroup.isSettingsVisible()) {
            val settingsMenuLocation = IntArray(2)
            dualWebViewGroup.getSettingsMenuLocation(settingsMenuLocation)
            val settingsMenuSize = dualWebViewGroup.getSettingsMenuSize()

            if (interactionX >= settingsMenuLocation[0] &&
                            interactionX <= settingsMenuLocation[0] + settingsMenuSize.first &&
                            interactionY >= settingsMenuLocation[1] &&
                            interactionY <= settingsMenuLocation[1] + settingsMenuSize.second
            ) {

                // Dispatch touch event to settings menu using screen coordinates
                suppressImmediateWebClickLeak()
                dualWebViewGroup.dispatchSettingsTouchEvent(interactionX, interactionY)
                return
            }
        }

        // Check for restore button click
        if (dualWebViewGroup.isPointInRestoreButton(interactionX, interactionY)) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.performRestoreButtonClick()
            return
        }

        if (dualWebViewGroup.isChatVisible()) {
            if (dualWebViewGroup.isPointInChat(interactionX, interactionY)) {
                suppressImmediateWebClickLeak()
                dualWebViewGroup.dispatchChatTouchEvent(interactionX, interactionY)
                return
            }
        }

        // Hit test for custom keyboard first so clicks never pass through it.
        if (isKeyboardVisible &&
                        wasKeyboardVisibleAtDown &&
                        dualWebViewGroup.isPointInKeyboard(interactionX, interactionY)
        ) {
            // Anchored mode needs explicit dispatch; non-anchored is handled by the view itself.
            if (isAnchored) {
                dualWebViewGroup.dispatchKeyboardTap(interactionX, interactionY)
            }
            suppressImmediateWebClickLeak()
            return
        }

        // Check for bookmarks interaction (prevent click propagation to webview)
        if (dualWebViewGroup.isBookmarksExpanded()) {
            if (dualWebViewGroup.isPointInBookmarks(interactionX, interactionY)) {
                // Handled by DualWebViewGroup.onTouchEvent - just don't dispatch to webview
                DebugLog.d("ClickDebug", "Click consumed by bookmarks window")
                return
            }
        }

        // If the tap started on the keyboard, never let it fall through to the WebView.
        if (wasTouchOnKeyboard) {
            DebugLog.d("ClickDebug", "Click consumed by keyboard")
            return
        }

        // Check for windows overview interaction
        if (dualWebViewGroup.isWindowsOverviewVisible()) {
            if (dualWebViewGroup.isPointInWindowsOverview(interactionX, interactionY)) {
                suppressImmediateWebClickLeak()
                dualWebViewGroup.performWindowsOverviewClick()
                return
            }
        }

        // Handle toggle/navigation bar clicks before scrollbars/web content
        val toggleHit =
                dualWebViewGroup.isToggleBarVisible() &&
                        dualWebViewGroup.isPointInToggleBar(interactionX, interactionY)
        val navHit =
                dualWebViewGroup.isNavBarVisible() &&
                        dualWebViewGroup.isPointInNavBar(interactionX, interactionY)
        if (toggleHit || navHit) {
            isSimulatingTouchEvent = false
            suppressImmediateWebClickLeak()
            dualWebViewGroup.handleNavigationClick(interactionX, interactionY)
            return
        }

        // Check for scrollbar interaction
        if (dualWebViewGroup.isPointInScrollbar(interactionX, interactionY)) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.dispatchScrollbarTouch(interactionX, interactionY)
            return
        }

        // ── Phase 2 unipanel overlay hit-test ──
        // The unipanel overlay sits above the WebView in z-order but
        // the cursor-tap pipeline above bypasses the normal Android
        // view tree — it routes taps directly to known custom UI
        // surfaces and otherwise falls through to the WebView. Without
        // this block, clickable widgets in unipanelOverlay never
        // receive taps (the cursor reaches the WebView path first).
        //
        // Three-state routing (see dispatchUnipanelOverlayTouchIfHit):
        //   1. Empty transparent region → fall through to WebView.
        //   2. Inert visual surface (HUD, read-only card) → consume.
        //   3. Interactive widget → dispatch synthetic DOWN+UP.
        // State (2) is what prevents cards from leaking taps to the
        // browser link underneath when they're visually opaque but
        // not clickable.
        if (dispatchUnipanelOverlayTouchIfHit(interactionX, interactionY)) {
            suppressImmediateWebClickLeak()
            return
        }

        // Phase 4k.8 — single tap on empty space TOGGLES the browser.
        // While collapsed to focus mode, a tap that falls through the
        // overlay (i.e. lands on empty space, not a HUD widget) brings
        // the browser back. (When the browser is visible, the symmetric
        // hide happens below once the WebView JS confirms the tap hit
        // empty page background — see the "taphud_empty" handler.)
        if (browserPanelHidden) {
            showBrowserPanel()
            return
        }

        val currentTime = SystemClock.uptimeMillis()
        if (currentTime - lastClickTime < MIN_CLICK_INTERVAL) {
            return
        }
        lastClickTime = currentTime

        // WebView click path
        isSimulatingTouchEvent = true
        try {
            val webViewLocation = IntArray(2)
            webView.getLocationOnScreen(webViewLocation)

            val translatedX = interactionX - webViewLocation[0]
            val translatedY = interactionY - webViewLocation[1]

            val adjustedX: Float
            val adjustedY: Float

            if (isAnchored) {
                val rotationRad =
                        Math.toRadians(dualWebViewGroup.leftEyeUIContainer.rotation.toDouble())
                val cos = Math.cos(rotationRad).toFloat()
                val sin = Math.sin(rotationRad).toFloat()
                val unscaledX = translatedX * cos + translatedY * sin
                val unscaledY = -translatedX * sin + translatedY * cos
                adjustedX = unscaledX / scale
                adjustedY = unscaledY / scale
            } else {
                adjustedX = translatedX / scale
                adjustedY = translatedY / scale
            }

            val eventTime = SystemClock.uptimeMillis()

            // Set simulation flag to true to prevent this event from being counted as a new gesture
            // by the OnTouchListener (which would trigger onDown and inaccurate tap counts).
            // It gets reset to false in the cleanup handler below (line 2842).
            isSimulatingTouchEvent = true

            // DOWN event
            val motionEventDown =
                    MotionEvent.obtain(
                                    eventTime,
                                    eventTime,
                                    MotionEvent.ACTION_DOWN,
                                    adjustedX,
                                    adjustedY,
                                    1 // pointer count
                            )
                            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            webView.dispatchTouchEvent(motionEventDown)

            webView.evaluateJavascript(
                    """
    (function() {
        var element = document.elementFromPoint($adjustedX, $adjustedY);

        // TapLink nav buttons: force-click if cursor lands on them.
        // This guarantees the button action fires regardless of touch chain.
        if (element) {
            var btn = (element.id === '__tl_view' || element.id === '__tl_prev' || element.id === '__tl_next') ? element
                    : element.closest ? element.closest('#__tl_nav button') : null;
            if (btn) {
                btn.click();
                console.log('[TapLink-YT] Force-clicked nav button: ' + btn.id);
                return 'tl_btn_' + btn.id;
            }
        }

        var targetUrl = null;

        function findTargetUrl(el) {
            if (!el) return null;
            if (el.href) return el.href;
            if (el.dataset && (el.dataset.url || el.dataset.articleUrl)) {
                return el.dataset.url || el.dataset.articleUrl;
            }
            var linkParent = el.closest('a');
            if (linkParent && linkParent.href) return linkParent.href;
            return null;
        }

        targetUrl = findTargetUrl(element);
        if (targetUrl && targetUrl.includes('news.google.com')) {
            // Instead of returning the URL, create and trigger a real navigation
            var a = document.createElement('a');
            a.href = targetUrl;
            a.style.display = 'none';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            return "clicked";  // Signal that we handled it
        }

        // Phase 4k — report taps that land on empty / background space
        // so the Kotlin side can collapse the browser to a HUD+chat-only
        // view. A tap is "interactive" when the hit element (or an
        // ancestor) is a real control: links, buttons, form fields, the
        // dashboard's role=button tiles, or media. Everything else
        // (page background, plain text, layout containers) is empty.
        var __thInteractive = element && element.closest && element.closest(
            'a, button, input, select, textarea, label, summary, details, ' +
            '[role=button], [role=link], [role=menuitem], [role=menuitemcheckbox], ' +
            '[role=menuitemradio], [role=tab], [role=option], [role=switch], ' +
            '[role=checkbox], [role=radio], [role=combobox], [role=slider], ' +
            '[role=spinbutton], [role=treeitem], [role=gridcell], ' +
            '[onclick], [tabindex], [contenteditable], [contenteditable=true], ' +
            '[data-href], [jsaction], [ng-click], img, video, audio, iframe'
        );
        // Many sites attach click handlers via addEventListener on plain
        // div/span elements (undetectable from JS), but they almost always
        // set cursor:pointer to signal clickability. Walk a few ancestors
        // and treat a pointer cursor as interactive so real taps reach the
        // page instead of being misread as empty space (which would hide
        // the browser AND swallow the click).
        if (!__thInteractive && element) {
            var __n = element, __hops = 0;
            while (__n && __n.nodeType === 1 && __hops < 5) {
                try {
                    if (window.getComputedStyle(__n).cursor === 'pointer') {
                        __thInteractive = __n; break;
                    }
                } catch (e) {}
                __n = __n.parentElement; __hops++;
            }
        }
        if (!__thInteractive) { return 'taphud_empty'; }
        return null;
    })();
"""
            ) { clickResult ->
                // Phase 4k — empty-space tap collapses the browser to the
                // HUD+chat-only focus view. evaluateJavascript hands back a
                // JSON-encoded string, so the sentinel arrives quoted.
                val emptySpaceTap = clickResult != null && clickResult.contains("taphud_empty")
                val tapLinkOverlayButton =
                        clickResult != null && clickResult.contains("tl_btn_")
                val handledAsMediaToggle =
                        emptySpaceTap && (hudRolledUp || isViewingYoutubeWatchPage())
                val shouldCancelSyntheticClick = handledAsMediaToggle || tapLinkOverlayButton
                if (emptySpaceTap) {
                    Handler(Looper.getMainLooper()).post {
                        // When the HUD/chat are rolled up, empty space becomes
                        // a media pause/unpause surface instead of collapsing
                        // the browser into a black focus state.
                        if (isViewingYoutubeWatchPage()) {
                            toggleYoutubeVideoPlayback()
                        } else if (hudRolledUp) {
                            dualWebViewGroup.toggleMediaPlayback()
                        } else {
                            hideBrowserPanel()
                        }
                    }
                }
                // Complete the touch sequence regardless of whether we found a special link
                Handler(Looper.getMainLooper())
                        .postDelayed(
                                {
                                    val motionEventUp =
                                            MotionEvent.obtain(
                                                            eventTime,
                                                            SystemClock.uptimeMillis(),
                                                            if (shouldCancelSyntheticClick) {
                                                                MotionEvent.ACTION_CANCEL
                                                            } else {
                                                                MotionEvent.ACTION_UP
                                                            },
                                                            adjustedX,
                                                            adjustedY,
                                                            1
                                                    )
                                                    .apply {
                                                        source = InputDevice.SOURCE_TOUCHSCREEN
                                                    }
                                    webView.dispatchTouchEvent(motionEventUp)

                                    // Clean up
                                    motionEventDown.recycle()
                                    motionEventUp.recycle()

                                    // Reset states and check keyboard
                                    Handler(Looper.getMainLooper())
                                            .postDelayed(
                                                    {
                                                        checkAndShowKeyboard(
                                                                adjustedX.toInt(),
                                                                adjustedY.toInt()
                                                        )
                                                        isSimulatingTouchEvent = false
                                                        cursorJustAppeared = false
                                                        isToggling = false
                                                    },
                                                    150
                                            )
                                },
                                16
                        )
            }
        } catch (e: Exception) {
            DebugLog.e("ClickDebug", "Error in dispatchTouchEventAtCursor: ${e.message}")
            e.printStackTrace()
            isSimulatingTouchEvent = false
        }
    }

    private fun checkAndShowKeyboard(adjustedX: Int, adjustedY: Int) {
        webView.evaluateJavascript(
                """
        (function() {
            var element = document.elementFromPoint($adjustedX, $adjustedY);
            var node = element;

            function isPopupTrigger(el) {
                return (
                    el.getAttribute('aria-haspopup') === 'true' ||
                    el.getAttribute('aria-expanded') === 'false' ||
                    el.hasAttribute('aria-controls') ||
                    el.tagName.toLowerCase() === 'select' ||
                    el.tagName === 'BUTTON' ||
                    el.getAttribute('role') === 'button' ||
                    el.getAttribute('role') === 'menu' ||
                    el.getAttribute('role') === 'menuitem' ||
                    el.classList.contains('dropdown-toggle') ||
                    /(menu|dropdown|popup|button|signout|logout)/i.test(el.className) ||
                    el.getAttribute('aria-label')?.toLowerCase().includes('sign out')
                );
            }

            function canAcceptTextInput(el) {
                if (!el) return false;

                // First check if this is a button/menu - should take precedence
                const isMenuOrButton = (
                    el.getAttribute('role') === 'button' ||
                    el.getAttribute('role') === 'menuitem' ||
                    el.getAttribute('aria-haspopup') === 'true' ||
                    el.getAttribute('aria-expanded') !== null ||
                    el.tagName === 'BUTTON' ||
                    (el.tagName === 'A' && el.getAttribute('role') === 'button')
                );

                if (isMenuOrButton) {
                    //console.log('Element is a button or menu control');
                    return false;
                }

                // Enhanced check for text cursor and focus state
                const activeElement = document.activeElement;
                if (activeElement && activeElement !== document.body) {
                    console.log('Active element state:', {
                        tagName: activeElement.tagName,
                        className: activeElement.className,
                        hasSelection: window.getSelection().toString().length > 0,
                        selectionRangeCount: window.getSelection().rangeCount,
                        isInput: activeElement instanceof HTMLInputElement,
                        isTextarea: activeElement instanceof HTMLTextAreaElement,
                        selectionStart:
                            activeElement instanceof HTMLInputElement ||
                            activeElement instanceof HTMLTextAreaElement ?
                            activeElement.selectionStart : null,
                        isFocusInElement: activeElement === el || activeElement.contains(el) || el.contains(activeElement)
                    });

                    // Check for any visible text selection
                    const selection = window.getSelection();
                    // Check if there's a real text cursor (not just any selection)
                    const hasVisibleCursor = selection && (
                        // Has actual text selection
                        selection.toString().length > 0 ||
                        // Or has a collapsed cursor (blinking text cursor) in an editable element
                        (selection.rangeCount > 0 &&
                         selection.getRangeAt(0).collapsed &&
                         (activeElement.isContentEditable ||
                          activeElement instanceof HTMLInputElement ||
                          activeElement instanceof HTMLTextAreaElement ||
                          // Also check if it's inside a custom editor
                          (activeElement.closest('[contenteditable="true"]') ||
                           activeElement.closest('[role="textbox"]'))))
                    );

                    if (hasVisibleCursor &&
                        (activeElement === el ||
                         activeElement.contains(el) ||
                         el.contains(activeElement))) {
                        //console.log('Found element with visible text cursor');
                        return true;
                    }
                }

                const capabilities = {
                    tagName: el.tagName,
                    className: el.className,
                    isContentEditable: el.isContentEditable,
                    role: el.getAttribute('role'),
                    inputType: el instanceof HTMLInputElement ? el.type : null,
                    isTextarea: el instanceof HTMLTextAreaElement,
                    hasTextboxRole: el.getAttribute('role') === 'textbox',
                    contentEditable: el.getAttribute('contenteditable'),
                    ariaMultiline: el.getAttribute('aria-multiline'),
                    hasSearchRole: el.getAttribute('role') === 'search'
                };
                //console.log('Text input capabilities:', JSON.stringify(capabilities, null, 2));

                if (
                    el.isContentEditable ||
                    el instanceof HTMLTextAreaElement ||
                    el.getAttribute('role') === 'textbox' ||
                    el.getAttribute('role') === 'searchbox' ||
                    el.getAttribute('role') === 'search' ||
                    el.getAttribute('contenteditable') === 'true' ||
                    el.getAttribute('aria-multiline') === 'true' ||
                    (el instanceof HTMLInputElement &&
                        ['text', 'email', 'password', 'search', 'tel', 'url', 'number'].includes(el.type)) ||
                    (el.tagName.toLowerCase().includes('editor') ||
                     el.tagName.toLowerCase().includes('composer') ||
                     el.tagName.toLowerCase().includes('search'))
                ) {
                    //console.log('Element directly supports text input');
        // If the element directly supports text input, check if it can gain focus:
        return canElementGainFocus(el);
                }



                return false;
            }

            function rememberTapLinkTarget(el) {
                try {
                    window.__taplinkLastEditable = el;
                    window.__taplinkLastEditableAt = Date.now();
                } catch (e) {}
            }

            function canElementGainFocus(el) {
                try {
                    if (!el) return false;
                    rememberTapLinkTarget(el);
                    if (typeof window.__taplinkResolveInputTarget === 'function') {
                        const resolved = window.__taplinkResolveInputTarget();
                        if (resolved) {
                            rememberTapLinkTarget(resolved);
                            return true;
                        }
                    }
                    if (typeof el.focus === 'function') {
                        try {
                            el.focus({ preventScroll: true });
                        } catch (err) {
                            el.focus();
                        }
                    }
                    const active = document.activeElement;
                    const isFocused = active === el || (active && (active.contains(el) || el.contains(active)));
                    if (isFocused) {
                        rememberTapLinkTarget(active || el);
                    }
                    return isFocused || document.activeElement === el;
                } catch (e) {
                    return false;
                }
            }


            // Check the element and its hierarchy for input capability
            node = element;
            while (node && node !== document.body) {
                if (canAcceptTextInput(node)) {
                    console.log('Input-capable element found:', node);
                    return 'input';
                }
                node = node.parentElement;
            }

            // Check active element with detailed logging
            const activeElement = document.activeElement;
            if (activeElement && activeElement !== document.body) {
                const activeElementInfo = {
                    tagName: activeElement.tagName,
                    className: activeElement.className,
                    isContentEditable: activeElement.isContentEditable,
                    role: activeElement.getAttribute('role'),
                    containsTarget: activeElement.contains(element),
                    isContainedByTarget: element.contains(activeElement)
                };
                //console.log('Active element details:', JSON.stringify(activeElementInfo, null, 2));

                if (activeElement === element ||
                    element.contains(activeElement) ||
                    activeElement.contains(element)) {

                    if (canAcceptTextInput(activeElement)) {
                        //console.log('Active element can accept text input');
                        return 'input';
                    }
                }
            }

            return 'regular';
        })();
    """
        ) { result ->
            DebugLog.d("InputDebug", "Element detection result after touch: $result")
            if (result?.contains("input") == true) {
                webView.evaluateJavascript(
                        """
                    (function() {
                        try {
                            if (typeof window.__taplinkResolveInputTarget === 'function') {
                                window.__taplinkResolveInputTarget();
                            }
                        } catch (e) {}
                    })();
                    """.trimIndent(),
                        null
                )
                Handler(Looper.getMainLooper()).post { showCustomKeyboard() }
            }
        }
    }

    private fun maybeShowKeyboardForMouseClick(rawScreenX: Float, rawScreenY: Float) {
        if (!::webView.isInitialized || !::dualWebViewGroup.isInitialized) return

        val webViewLocation = IntArray(2)
        webView.getLocationOnScreen(webViewLocation)

        val translatedX = rawScreenX - webViewLocation[0]
        val translatedY = rawScreenY - webViewLocation[1]
        if (translatedX < 0f ||
                        translatedY < 0f ||
                        translatedX > webView.width ||
                        translatedY > webView.height
        ) {
            return
        }

        val scale = dualWebViewGroup.uiScale
        val adjustedX: Float
        val adjustedY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(dualWebViewGroup.leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            val unscaledX = translatedX * cos + translatedY * sin
            val unscaledY = -translatedX * sin + translatedY * cos
            adjustedX = unscaledX / scale
            adjustedY = unscaledY / scale
        } else {
            adjustedX = translatedX / scale
            adjustedY = translatedY / scale
        }

        checkAndShowKeyboard(adjustedX.toInt(), adjustedY.toInt())
    }

    private fun mapMousePointForVirtualTap(rawScreenX: Float, rawScreenY: Float): Pair<Float, Float> {
        if (!isMouseTapMode) return rawScreenX to rawScreenY
        val fallbackEyeWidth = 640f
        if (!::dualWebViewGroup.isInitialized) {
            if (rawScreenX < fallbackEyeWidth) return rawScreenX to rawScreenY
            return (rawScreenX - fallbackEyeWidth) to rawScreenY
        }

        val groupLocation = IntArray(2)
        dualWebViewGroup.getLocationOnScreen(groupLocation)
        val groupLeft = groupLocation[0].toFloat()
        val groupWidth = dualWebViewGroup.width.toFloat().takeIf { it > 0f } ?: (fallbackEyeWidth * 2f)
        val eyeWidth = (groupWidth / 2f).coerceAtLeast(1f)

        val xWithinGroup = rawScreenX - groupLeft
        if (xWithinGroup < 0f || xWithinGroup >= groupWidth) {
            // Outside dual-eye surface: do not remap.
            return rawScreenX to rawScreenY
        }

        if (xWithinGroup < eyeWidth) {
            return rawScreenX to rawScreenY
        }

        return (rawScreenX - eyeWidth) to rawScreenY
    }

    private fun mapScreenPointToWebViewTouch(screenX: Float, screenY: Float): Pair<Float, Float>? {
        if (!::webView.isInitialized || !::dualWebViewGroup.isInitialized) return null
        val scale = dualWebViewGroup.uiScale
        val webViewLocation = IntArray(2)
        webView.getLocationOnScreen(webViewLocation)

        val translatedX = screenX - webViewLocation[0]
        val translatedY = screenY - webViewLocation[1]
        if (translatedX < 0f ||
                        translatedY < 0f ||
                        translatedX > webView.width ||
                        translatedY > webView.height
        ) {
            return null
        }

        val adjustedX: Float
        val adjustedY: Float
        if (isAnchored) {
            val rotationRad = Math.toRadians(dualWebViewGroup.leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            val unscaledX = translatedX * cos + translatedY * sin
            val unscaledY = -translatedX * sin + translatedY * cos
            adjustedX = unscaledX / scale
            adjustedY = unscaledY / scale
        } else {
            adjustedX = translatedX / scale
            adjustedY = translatedY / scale
        }

        return adjustedX to adjustedY
    }

    private fun dispatchWebTouchFromScreen(
            action: Int,
            screenX: Float,
            screenY: Float,
            eventTime: Long = SystemClock.uptimeMillis(),
            downTime: Long = mouseSwipeDownTime
    ): Boolean {
        val mapped = mapScreenPointToWebViewTouch(screenX, screenY) ?: return false
        val adjustedX = mapped.first
        val adjustedY = mapped.second

        val event =
                MotionEvent.obtain(
                                downTime,
                                eventTime,
                                action,
                                adjustedX,
                                adjustedY,
                                0
                        )
                        .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        isSimulatingTouchEvent = true
        try {
            webView.dispatchTouchEvent(event)
        } finally {
            isSimulatingTouchEvent = false
            event.recycle()
        }
        return true
    }

    private fun isPointOnCustomUi(screenX: Float, screenY: Float): Boolean {
        if (!::dualWebViewGroup.isInitialized) return false
        if (dualWebViewGroup.isScreenMasked()) return true
        if (dualWebViewGroup.isFullScreenOverlayVisible()) return true
        if (dualWebViewGroup.isDialogAction(screenX, screenY)) return true

        if (dualWebViewGroup.isSettingsVisible()) {
            val settingsMenuLocation = IntArray(2)
            dualWebViewGroup.getSettingsMenuLocation(settingsMenuLocation)
            val settingsMenuSize = dualWebViewGroup.getSettingsMenuSize()
            if (screenX >= settingsMenuLocation[0] &&
                            screenX <= settingsMenuLocation[0] + settingsMenuSize.first &&
                            screenY >= settingsMenuLocation[1] &&
                            screenY <= settingsMenuLocation[1] + settingsMenuSize.second
            ) {
                return true
            }
        }

        if (dualWebViewGroup.isPointInRestoreButton(screenX, screenY)) return true
        if (dualWebViewGroup.isChatVisible() && dualWebViewGroup.isPointInChat(screenX, screenY)) return true
        if (isKeyboardVisible && dualWebViewGroup.isPointInKeyboard(screenX, screenY)) return true
        if (dualWebViewGroup.isWindowsOverviewVisible() &&
                        dualWebViewGroup.isPointInWindowsOverview(screenX, screenY)
        ) {
            return true
        }
        if (dualWebViewGroup.isToggleBarVisible() && dualWebViewGroup.isPointInToggleBar(screenX, screenY)) {
            return true
        }
        if (dualWebViewGroup.isNavBarVisible() && dualWebViewGroup.isPointInNavBar(screenX, screenY)) {
            return true
        }
        if (dualWebViewGroup.isPointInScrollbar(screenX, screenY)) return true
        if (findUnipanelHit(screenX, screenY) != null) return true
        return false
    }

    private fun resolveMouseScreenPoint(ev: MotionEvent): Pair<Float, Float> {
        var screenX = ev.rawX
        var screenY = ev.rawY

        if (!screenX.isFinite() || !screenY.isFinite()) {
            val rootLoc = IntArray(2)
            window.decorView.getLocationOnScreen(rootLoc)
            screenX = ev.x + rootLoc[0]
            screenY = ev.y + rootLoc[1]
        }

        return screenX to screenY
    }

    private fun dispatchWebTapAtScreenCoordinates(screenX: Float, screenY: Float) {
        if (!::webView.isInitialized || !::dualWebViewGroup.isInitialized) return
        if (isSimulatingTouchEvent) return

        val scale = dualWebViewGroup.uiScale
        val webViewLocation = IntArray(2)
        webView.getLocationOnScreen(webViewLocation)

        val translatedX = screenX - webViewLocation[0]
        val translatedY = screenY - webViewLocation[1]
        if (translatedX < 0f ||
                        translatedY < 0f ||
                        translatedX > webView.width ||
                        translatedY > webView.height
        ) {
            return
        }

        val adjustedX: Float
        val adjustedY: Float
        if (isAnchored) {
            val rotationRad = Math.toRadians(dualWebViewGroup.leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            val unscaledX = translatedX * cos + translatedY * sin
            val unscaledY = -translatedX * sin + translatedY * cos
            adjustedX = unscaledX / scale
            adjustedY = unscaledY / scale
        } else {
            adjustedX = translatedX / scale
            adjustedY = translatedY / scale
        }

        val eventTime = SystemClock.uptimeMillis()
        isSimulatingTouchEvent = true
        try {
            val downEvent =
                    MotionEvent.obtain(
                                    eventTime,
                                    eventTime,
                                    MotionEvent.ACTION_DOWN,
                                    adjustedX,
                                    adjustedY,
                                    1
                            )
                            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            webView.dispatchTouchEvent(downEvent)
            downEvent.recycle()

            Handler(Looper.getMainLooper())
                    .postDelayed(
                            {
                                val upEvent =
                                        MotionEvent.obtain(
                                                        eventTime,
                                                        SystemClock.uptimeMillis(),
                                                        MotionEvent.ACTION_UP,
                                                        adjustedX,
                                                        adjustedY,
                                                        1
                                                )
                                                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                                webView.dispatchTouchEvent(upEvent)
                                upEvent.recycle()
                                checkAndShowKeyboard(adjustedX.toInt(), adjustedY.toInt())
                                isSimulatingTouchEvent = false
                            },
                            16
                    )
        } catch (e: Exception) {
            isSimulatingTouchEvent = false
            DebugLog.e("MouseTap", "Failed to dispatch virtual web tap: ${e.message}")
        }
    }

    private fun handleMouseClickForCustomUi(rawScreenX: Float, rawScreenY: Float): Boolean {
        if (!::dualWebViewGroup.isInitialized) return false

        val scale = dualWebViewGroup.uiScale

        if (dualWebViewGroup.isScreenMasked()) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.dispatchMaskOverlayTouch(rawScreenX, rawScreenY)
            return true
        }

        if (dualWebViewGroup.isFullScreenOverlayVisible()) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.dispatchFullScreenOverlayTouch(rawScreenX, rawScreenY)
            return true
        }

        if (dispatchUnipanelOverlayTouchIfHit(rawScreenX, rawScreenY)) {
            suppressImmediateWebClickLeak()
            return true
        }

        if (dualWebViewGroup.isDialogAction(rawScreenX, rawScreenY)) {
            val dialogContainer = dualWebViewGroup.dialogContainer
            val location = IntArray(2)
            dialogContainer.getLocationOnScreen(location)
            val localX = (rawScreenX - location[0]) / scale
            val localY = (rawScreenY - location[1]) / scale

            val downEvent =
                    MotionEvent.obtain(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_DOWN,
                            localX,
                            localY,
                            0
                    )
            dialogContainer.dispatchTouchEvent(downEvent)
            downEvent.recycle()

            val upEvent =
                    MotionEvent.obtain(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_UP,
                            localX,
                            localY,
                            0
                    )
            dialogContainer.dispatchTouchEvent(upEvent)
            upEvent.recycle()
            suppressImmediateWebClickLeak()
            return true
        }

        if (dualWebViewGroup.isSettingsVisible()) {
            val settingsMenuLocation = IntArray(2)
            dualWebViewGroup.getSettingsMenuLocation(settingsMenuLocation)
            val settingsMenuSize = dualWebViewGroup.getSettingsMenuSize()
            if (rawScreenX >= settingsMenuLocation[0] &&
                            rawScreenX <= settingsMenuLocation[0] + settingsMenuSize.first &&
                            rawScreenY >= settingsMenuLocation[1] &&
                            rawScreenY <= settingsMenuLocation[1] + settingsMenuSize.second
            ) {
                suppressImmediateWebClickLeak()
                dualWebViewGroup.dispatchSettingsTouchEvent(rawScreenX, rawScreenY)
                return true
            }
        }

        if (dualWebViewGroup.isPointInRestoreButton(rawScreenX, rawScreenY)) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.performRestoreButtonClick()
            return true
        }

        if (dualWebViewGroup.isChatVisible() && dualWebViewGroup.isPointInChat(rawScreenX, rawScreenY)) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.dispatchChatTouchEvent(rawScreenX, rawScreenY)
            return true
        }

        if (isKeyboardVisible && dualWebViewGroup.isPointInKeyboard(rawScreenX, rawScreenY)) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.dispatchKeyboardTap(rawScreenX, rawScreenY)
            return true
        }

        if (dualWebViewGroup.isWindowsOverviewVisible() &&
                        dualWebViewGroup.isPointInWindowsOverview(rawScreenX, rawScreenY)
        ) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.performWindowsOverviewClick()
            return true
        }

        val toggleHit =
                dualWebViewGroup.isToggleBarVisible() &&
                        dualWebViewGroup.isPointInToggleBar(rawScreenX, rawScreenY)
        val navHit =
                dualWebViewGroup.isNavBarVisible() &&
                        dualWebViewGroup.isPointInNavBar(rawScreenX, rawScreenY)
        if (toggleHit || navHit) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.handleNavigationClick(rawScreenX, rawScreenY)
            return true
        }

        if (dualWebViewGroup.isPointInScrollbar(rawScreenX, rawScreenY)) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.dispatchScrollbarTouch(rawScreenX, rawScreenY)
            return true
        }

        return false
    }

    private fun sendEnterToWebView() {
        if (dualWebViewGroup.isUrlEditing()) {
            sendEnterInLinkEditText()
            return
        }
        if (dualWebViewGroup.isChatVisible()) {
            dualWebViewGroup.sendEnterToChatInput()
            hideCustomKeyboard()
            return
        } else {
            webView.evaluateJavascript(
                    buildWebInputActionScript(
                            """
                function dispatchKeyEvent(element, type) {
                    var event = new KeyboardEvent(type, {
                        key: 'Enter',
                        code: 'Enter',
                        keyCode: 13,
                        which: 13,
                        bubbles: true,
                        cancelable: true,
                        composed: true
                    });
                    element.dispatchEvent(event);
                    return event.defaultPrevented;
                }

                var prevented = false;
                prevented = dispatchKeyEvent(el, 'keydown') || prevented;
                prevented = dispatchKeyEvent(el, 'keypress') || prevented;

                if (!prevented && (el instanceof HTMLTextAreaElement || el.isContentEditable)) {
                    var beforeInputEvent = createInputEvent('beforeinput', 'insertLineBreak', null);
                    el.dispatchEvent(beforeInputEvent);
                    if (!beforeInputEvent.defaultPrevented) {
                        if (el.isContentEditable) {
                            try {
                                if (document.execCommand) {
                                    document.execCommand('insertLineBreak', false);
                                }
                            } catch (e) {}
                        } else {
                            var originalValue = el.value || '';
                            var start = (typeof el.selectionStart === 'number') ? el.selectionStart : originalValue.length;
                            var end = (typeof el.selectionEnd === 'number') ? el.selectionEnd : start;
                            var newValue = originalValue.substring(0, start) + '\n' + originalValue.substring(end);
                            setNativeValue(el, newValue);
                            if (typeof el.setSelectionRange === 'function') {
                                el.setSelectionRange(start + 1, start + 1);
                            }
                            syncReactTracker(el, originalValue);
                            el.dispatchEvent(createInputEvent('input', 'insertLineBreak', null));
                        }
                    }
                }

                dispatchKeyEvent(el, 'keyup');
                el.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
                return true;
                """
                    )
            ) { result ->
                DebugLog.d("InputDebug", "Enter JavaScript result: $result")
                Handler(Looper.getMainLooper()).post { hideCustomKeyboard() }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        configureWebView(webView)
    }

    override fun onWindowCreated(webView: WebView) {
        configureWebView(webView)
        // Default URL loading is now handled in DualWebViewGroup.createNewWindow()
        // to avoid overriding restored state
    }

    override fun onWindowSwitched(webView: WebView) {
        // Update reference
        this.webView = webView
        // Keep BrowserFrameHolder pointed at the active window so the
        // browser_vision tool always screenshots whatever the user is
        // currently looking at, not a stale background window.
        com.TapLink.app.media.BrowserFrameHolder.attach(webView)

        // Ensure the correct touch listener is attached (though configureWebView likely did it)
        attachTouchListener(webView)
        applyForceDarkModeSetting(webView)
        syncActiveBrowserChrome(webView)
        if (isKeyboardVisible) syncKeyboardAwarePageState()

        // Persist the newly active window so reopen returns to the correct tab/page.

        // Persist the newly active window so reopen returns to the correct tab/page.
        persistActiveUrl("onWindowSwitched", webView.url ?: Constants.DEFAULT_URL, webView)
        syncTapRadioPlaybackUi()
    }

    private fun isForceDarkWebEnabled(): Boolean {
        return getSharedPreferences("TapLinkPrefs", MODE_PRIVATE)
                .getBoolean("forceDarkWebEnabled", true)
    }

    private fun applyForceDarkModeSetting(targetWebView: WebView, enabled: Boolean = isForceDarkWebEnabled()) {
        targetWebView.settings.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = enabled
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                forceDark =
                        if (enabled) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
            }
        }
    }

    fun setForceDarkWebEnabled(enabled: Boolean) {
        getSharedPreferences("TapLinkPrefs", MODE_PRIVATE)
                .edit()
                .putBoolean("forceDarkWebEnabled", enabled)
                .apply()

        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.getAllWebViews().forEach { applyForceDarkModeSetting(it, enabled) }
        } else if (::webView.isInitialized) {
            applyForceDarkModeSetting(webView, enabled)
        }
    }

    private fun attachTouchListener(targetWebView: WebView) {
        targetWebView.setOnTouchListener { _, event ->
            val isMouseEvent = isMousePointerEvent(event)
            // Allow simulated events to pass through immediately (used for scrolling)
            // CRITICAL: Check this FIRST to prevent infinite loops where simulated events
            // are fed back into gestureDetector, triggering more scrolls.
            if (isSimulatingTouchEvent) {
                return@setOnTouchListener false
            }

            if (isMouseTapMode && isMouseEvent) {
                return@setOnTouchListener false
            }

            // DO NOT call gestureDetector.onTouchEvent(event) here!
            // It is already called in MainActivity.dispatchTouchEvent().
            // Calling it again causes double-counting of taps (1 physical tap = 2 gesture taps).
            // We just use the result stored in isGestureHandled.

            // Logic to clear pending runnables on touch interaction
            if (event.action == MotionEvent.ACTION_DOWN ||
                            event.action == MotionEvent.ACTION_UP ||
                            event.action == MotionEvent.ACTION_CANCEL
            ) {
                pendingTouchRunnable?.let { pendingTouchHandler?.removeCallbacks(it) }
                pendingTouchRunnable = null
            }

            // --- BLOCKING LOGIC START ---

            if (isAnchored && isKeyboardVisible) {
                // Special case for anchored keyboard? (Original logic preserved)
                return@setOnTouchListener false
            }

            if (isKeyboardVisible) {
                // If keyboard is visible, block touches to WebView (prevent clicks behind keyboard)
                return@setOnTouchListener true
            }

            if (dualWebViewGroup.isSettingsVisible()) {
                // If settings are open, only block if cursor is visible?
                // logic matches original: return isCursorVisible
                return@setOnTouchListener isCursorVisible
            }

            if (dualWebViewGroup.isInScrollMode()) {
                // SCROLL MODE ENFORCEMENT:
                // Block ALL real touch events. The only way to interact is via
                // simulated scroll events (caught by isSimulatingTouchEvent check above)
                // or via gestures (caught by gestureDetector above).
                return@setOnTouchListener true
            }

            if (isCursorVisible && !isMouseEvent) {
                // If cursor is visible, we are in "mouse mode" - block direct touches.
                return@setOnTouchListener true
            }

            // --- BLOCKING LOGIC END ---

            // Otherwise, return handled state from GestureDetector
            // If GestureDetector consumed it (e.g., tap, long press), we consume.
            // If not, we fall through to false?
            // Actually original code returned 'handled' which was 'isGestureHandled'
            isGestureHandled
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        attachTouchListener(webView)

        // First check if speech recognition is available
        val speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val speechRecognitionAvailable =
                packageManager.resolveActivity(speechRecognizerIntent, 0) != null
        DebugLog.d("WebView", "Speech recognition available: $speechRecognitionAvailable")

        // Section 1: Basic WebView Configuration
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.addJavascriptInterface(WebAppInterface(this, webView), "GroqBridge")

        // Intercept taplink://chat URLs and media file URLs
        webView.webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        DebugLog.d("MainActivityClient", "Checking URL: $url")

                        if (url.startsWith("taplink://chat")) {
                            DebugLog.d("MainActivityClient", "Intercepted taplink://chat")
                            val uri = android.net.Uri.parse(url)
                            val msg = uri.getQueryParameter("msg")
                            val history = uri.getQueryParameter("history")

                            if (msg != null && view != null) {
                                val webInterface =
                                        com.TapLinkX3.app.WebAppInterface(this@MainActivity, view)
                                webInterface.chatWithGroq(msg, history ?: "[]")
                            }
                            return true
                        }
                        // Intercept media file links → open in TapInsight media player
                        if (view != null && interceptMediaUrl(view, url)) return true
                        return false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        DebugLog.d("MainActivityClient", "Checking URL (deprecated): $url")
                        if (url != null && url.startsWith("taplink://chat")) {
                            DebugLog.d(
                                    "MainActivityClient",
                                    "Intercepted taplink://chat (deprecated)"
                            )
                            val uri = android.net.Uri.parse(url)
                            val msg = uri.getQueryParameter("msg")
                            val history = uri.getQueryParameter("history")

                            if (msg != null && view != null) {
                                val webInterface =
                                        com.TapLinkX3.app.WebAppInterface(this@MainActivity, view)
                                webInterface.chatWithGroq(msg, history ?: "[]")
                            }
                            return true
                        }
                        if (url != null && view != null && interceptMediaUrl(view, url)) return true
                        return false
                    }
                }

        webView.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            setBackgroundColor(Color.BLACK)
            visibility = View.INVISIBLE
            overScrollMode = View.OVER_SCROLL_NEVER

            // Section 2: WebView Settings Configuration
            settings.apply {
                // JavaScript and Content Settings
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = false

                // Security and Access Settings — restrict file/content access to prevent
                // XSS attacks from reading local files via file:// or content:// URIs
                allowFileAccess = false
                allowContentAccess = false
                setGeolocationEnabled(true)

                // Display and Layout Settings
                @Suppress("DEPRECATION")
                defaultZoom = WebSettings.ZoomDensity.MEDIUM
                useWideViewPort = true
                loadWithOverviewMode = true
                layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                textZoom = 80

                // Disable Unnecessary Zoom Controls
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false

                // Multi-window Support
                setSupportMultipleWindows(false)

                // Handle Mixed Content
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                // Keep secure HTTPS navigation
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }

                // Store default UA for sites that require it (like Netflix)
                if (defaultUserAgent == null) {
                    defaultUserAgent = WebSettings.getDefaultUserAgent(this@MainActivity)
                }

                // Use the actual runtime WebView UA to avoid auth providers flagging spoofed clients.
                customUserAgent = defaultUserAgent
                if (!customUserAgent.isNullOrBlank()) {
                    settings.userAgentString = customUserAgent

                    // Pass runtime UA to DualWebViewGroup so mobile/desktop modes derive from it.
                    dualWebViewGroup.setMobileUserAgent(customUserAgent!!)
                }

                // Explicitly enable media
                setMediaPlaybackRequiresUserGesture(false)
            }
            applyForceDarkModeSetting(this)

            // Enable third-party cookies specifically for auth
            CookieManager.getInstance().apply {
                setAcceptThirdPartyCookies(webView, true)
                setAcceptCookie(true)
                acceptCookie()
            }

            // Section 3: Single WebViewClient Implementation
            webViewClient =
                    object : WebViewClient() {
                        private var lastValidUrl: String? = null

                        /**
                         * Accept the TapInsight companion server's self-signed cert, but
                         * only on the loopback interface (127.0.0.1 / localhost / ::1).
                         * The key lives in this app's private files dir, so anything
                         * reachable only on loopback is by definition served by us.
                         * Without this, dashboard navigations to
                         * https://127.0.0.1:19110/library (and fetch() calls from
                         * media_player.html) silently fail.
                         */
                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            val host = error?.url
                                ?.let { android.net.Uri.parse(it).host }
                                ?.lowercase()
                                .orEmpty()
                            val isLoopback =
                                host == "127.0.0.1" ||
                                host == "localhost" ||
                                host == "[::1]" ||
                                host == "::1"
                            if (isLoopback && handler != null) {
                                android.util.Log.i(
                                    "TapLink",
                                    "Accepting self-signed cert for loopback URL: ${error?.url}"
                                )
                                handler.proceed()
                            } else {
                                super.onReceivedSslError(view, handler, error)
                            }
                        }

                        /**
                         * Serve virtual media URLs (appassets.androidplatform.net/media/...)
                         * from the on-glasses Media/ folder so library_local.html and
                         * media_player.html can play local audio/video without a
                         * companion-app HTTP server. Everything else falls through.
                         */
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            val mediaResp = mediaFileInterceptor.handle(request)
                            if (mediaResp != null) return mediaResp
                            return super.shouldInterceptRequest(view, request)
                        }

                        /**
                         * Surface any WebView-level load errors so we can tell
                         * whether a hang on media_player.html is "interceptor
                         * never returned a response" vs "page ran but `<audio>`
                         * stalled". Production logs only — never swallowed.
                         */
                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            val url = request?.url?.toString().orEmpty()
                            if (url.contains("appassets.androidplatform.net")) {
                                val code = try { error?.errorCode } catch (_: Exception) { null }
                                val desc = try { error?.description?.toString() } catch (_: Exception) { null }
                                android.util.Log.w(
                                    "MediaFileInterceptor",
                                    "onReceivedError url=$url code=$code desc=$desc"
                                )
                            }
                            super.onReceivedError(view, request, error)
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: android.webkit.WebResourceResponse?
                        ) {
                            val url = request?.url?.toString().orEmpty()
                            if (url.contains("appassets.androidplatform.net")) {
                                android.util.Log.w(
                                    "MediaFileInterceptor",
                                    "onReceivedHttpError url=$url status=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase}"
                                )
                            }
                            super.onReceivedHttpError(view, request, errorResponse)
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            // Keep the JS-bridge trust gate in sync with the currently
                            // loaded page — checked on every fs-mutating call.
                            mediaBridgeUrlRef.set(url ?: "")
                            DebugLog.d("YouTubeAuto", "onPageStarted[2]: url=$url")
                            DebugLog.d("WebViewDebug", "Page started loading: $url")

                            if (closeChatOnNextPageStart) {
                                val now = SystemClock.uptimeMillis()
                                if (now > closeChatOnNextPageStartDeadlineMs) {
                                    closeChatOnNextPageStart = false
                                } else if (!url.isNullOrBlank() && !url.startsWith("about:blank")) {
                                    closeChatOnNextPageStart = false
                                    dualWebViewGroup.hideChat()
                                }
                            }

                            dualWebViewGroup.clearExternalScrollMetrics()
                            view?.let {
                                dualWebViewGroup.stabilizeWebViewViewportAfterNavigation(
                                    targetWebView = it,
                                    resetVerticalScroll = false
                                )
                            }

                            // Streaming Fix: Force default User Agent to ensure Widevine CDM works
                            val isStreaming = isStreamingSite(url)
                            if (isStreaming) {
                                if (view?.settings?.userAgentString != defaultUserAgent) {
                                    view?.settings?.userAgentString = defaultUserAgent
                                    DebugLog.d(
                                            "StreamingFix",
                                            "Switched to default User Agent for Streaming Site"
                                    )
                                }
                            } else {
                                // Force desktop UA for YouTube when autoplay is active
                                val isYouTubeAutoplay = !youtubeAutoplayQuery.isNullOrBlank() &&
                                    !youtubeAutoplayMode.isNullOrBlank() &&
                                    url != null &&
                                    (url.contains("youtube.com") || url.contains("youtu.be"))

                                val forceDesktopUa = if (isYouTubeAutoplay) {
                                    shouldUseDesktopUaForYouTube(url, youtubeAutoplayMode)
                                } else {
                                    dualWebViewGroup.isDesktopMode()
                                }

                                if (forceDesktopUa) {
                                    val desktopUA = dualWebViewGroup.getDesktopUserAgent()
                                    if (view?.settings?.userAgentString != desktopUA) {
                                        view?.settings?.userAgentString = desktopUA
                                    }
                                } else {
                                    if (view?.settings?.userAgentString != customUserAgent &&
                                                    customUserAgent != null
                                    ) {
                                        view?.settings?.userAgentString = customUserAgent
                                    }
                                }
                            }

                            // Show loading bar immediately
                            dualWebViewGroup.updateLoadingProgress(0)

                            if (url != null && !url.startsWith("about:blank")) {
                                lastValidUrl = url

                                // Persist the URL as soon as navigation starts so app relaunch
                                // returns to the newest page even if load doesn't finish.
                                persistActiveUrl("onPageStarted", url, view)

                                // Start observers early so scrollbars can appear before full load.
                                view?.let { dualWebViewGroup.injectPageObservers(it) }

                                // Inject location early so it's available before page JS runs
                                if (lastGpsLat != null && lastGpsLon != null) {
                                    dualWebViewGroup.injectLocation(lastGpsLat!!, lastGpsLon!!)
                                }
                            } else if (url?.startsWith("about:blank") == true &&
                                            lastValidUrl != null
                            ) {
                                // Skip about:blank recovery if we're intentionally
                                // navigating to about:blank for nuclear media cleanup
                                if (nuclearCleanupInProgress) {
                                    DebugLog.d("YouTubeAuto", "onPageStarted: about:blank during nuclear cleanup — NOT recovering to $lastValidUrl")
                                    lastValidUrl = null
                                } else {
                                    // Cancel about:blank load immediately
                                    view?.stopLoading()
                                    view?.loadUrl(lastValidUrl!!)
                                }
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            mediaBridgeUrlRef.set(url ?: "")
                            DebugLog.d("WebViewDebug", "Page finished loading: $url")

                            // Ensure loading bar is hidden when finished
                            dualWebViewGroup.updateLoadingProgress(100)

                            // Keep the latest URL hot without serializing full WebView history on every load.
                            url?.let { persistActiveUrl("onPageFinished", it, view) }
                            dualWebViewGroup.saveWindowMetadataState()

                            if (url != null && !url.startsWith("about:blank")) {
                                view?.visibility = View.VISIBLE
                                view?.let { syncActiveBrowserChrome(it) }

                                // Reset horizontal drift and restart scroll metric warmup. Some SPA/back
                                // paths leave the WebView scrolled sideways before JS observers report
                                // metrics, which makes the right scrollbar appear to disappear.
                                view?.let { wv ->
                                    dualWebViewGroup.stabilizeWebViewViewportAfterNavigation(
                                        targetWebView = wv,
                                        resetVerticalScroll = false
                                    )
                                }

                                // ── Dashboard ↔ SharedPreferences sync ──
                                // When the dashboard HTML loads, pull any data
                                // saved by the companion app into localStorage,
                                // and hook persistState to also write back.
                                if (url.contains("AR_Dashboard")) {
                                    injectDashboardSync(view)
                                    dualWebViewGroup.recenterViewportForDashboard(view)
                                }

                                // Re-apply saved font settings to new page
                                dualWebViewGroup.reapplyWebFontSettings()

                                // Inject last known location if available
                                if (lastGpsLat != null && lastGpsLon != null) {
                                    dualWebViewGroup.injectLocation(lastGpsLat!!, lastGpsLon!!)
                                }

                                // ── YouTube autoplay automation ──
                                val isYouTubePage = url.contains("youtube.com") || url.contains("youtu.be")
                                if (isYouTubePage) {
                                    view?.let { injectYouTubePlaylistAutomation(it, url) }
                                }

                                // Restore media listeners and scrollbar logic from DualWebViewGroup
                                view?.let { syncActiveBrowserChrome(it) }
                                dualWebViewGroup.refreshMaskedNowPlaying()
                                if (url.contains("radio.html", ignoreCase = true) ||
                                    url.contains("podcasts.html", ignoreCase = true)
                                ) {
                                    syncTapRadioPlaybackUi()
                                }

                                val viewportContent =
                                        if (dualWebViewGroup.isDesktopMode()) {
                                            "width=1280, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"
                                        } else {
                                            "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"
                                        }
                                view?.evaluateJavascript(
                                        """
                            (function() {
                                var viewport = document.querySelector('meta[name="viewport"]');
                                if (!viewport) {
                                    viewport = document.createElement('meta');
                                    viewport.name = 'viewport';
                                    document.head.appendChild(viewport);
                                }
                                viewport.content = '$viewportContent';
                            })();
                            """,
                                        null
                                )

                                // Inject media listeners with enhanced YouTube support
                                view?.evaluateJavascript(
                                        """
                            (function() {
                                console.log('[TapLink] Media detection script starting...');
                                let lastPlayingState = false;

                                function notifyMediaState(isPlaying) {
                                    if (lastPlayingState !== isPlaying) {
                                        console.log('[TapLink] Media state changed:', isPlaying);
                                        lastPlayingState = isPlaying;
                                        var bridge = window.GroqBridge || window.Android;
                                        if (bridge && typeof bridge.onMediaPlaying === 'function') {
                                            bridge.onMediaPlaying(isPlaying);
                                        } else {
                                            console.error('[TapLink] Media bridge not available!');
                                        }
                                    }
                                }

                                function checkMediaState() {
                                    // Check all video and audio elements
                                    const mediaElements = document.querySelectorAll('video, audio');
                                    let isAnyPlaying = false;

                                    mediaElements.forEach(media => {
                                        if (!media.paused && !media.ended && media.readyState > 2) {
                                            isAnyPlaying = true;
                                        }
                                    });

                                    notifyMediaState(isAnyPlaying);
                                    return isAnyPlaying;
                                }

                                let mediaCheckTimer = null;
                                function scheduleMediaCheck() {
                                    if (mediaCheckTimer !== null) return;
                                    mediaCheckTimer = setTimeout(() => {
                                        mediaCheckTimer = null;
                                        checkMediaState();
                                    }, 300);
                                }

                                function attachMediaListeners() {
                                    const mediaElements = document.querySelectorAll('video, audio');
                                    console.log('[TapLink] Found', mediaElements.length, 'media elements');

                                    mediaElements.forEach((media, index) => {
                                        if (media.dataset.taplinkListening) return;
                                        media.dataset.taplinkListening = 'true';

                                        console.log('[TapLink] Attaching listeners to media element', index, media.tagName);

                                        media.addEventListener('play', () => {
                                            console.log('[TapLink] Play event');
                                            notifyMediaState(true);
                                        });
                                        media.addEventListener('playing', () => {
                                            console.log('[TapLink] Playing event');
                                            notifyMediaState(true);
                                        });
                                        media.addEventListener('pause', () => {
                                            console.log('[TapLink] Pause event');
                                            scheduleMediaCheck();
                                        });
                                        media.addEventListener('ended', () => {
                                            console.log('[TapLink] Ended event');
                                            scheduleMediaCheck();
                                        });
                                    });
                                }

                                // Run initially
                                attachMediaListeners();
                                checkMediaState();
                                scheduleMediaCheck();

                                // Watch for new media elements (YouTube loads videos dynamically)
                                const observer = new MutationObserver((mutations) => {
                                    attachMediaListeners();
                                    scheduleMediaCheck();
                                });
                                observer.observe(document.body, { childList: true, subtree: true });

                                console.log('[TapLink] Media detection script initialized');
                            })();
                        """,
                                        null
                                )

                                // Auto-unmute YouTube (and similar) videos that start muted
                                // due to browser autoplay policies.  The script watches for
                                // <video> elements and unmutes them shortly after playback
                                // begins, simulating what the user would do by tapping the
                                // speaker icon.
                                if (url.contains("youtube.com") || url.contains("youtu.be")) {
                                    view?.evaluateJavascript(
                                            """
                                (function() {
                                    console.log('[TapLink] YouTube auto-unmute script starting...');
                                    var attempts = 0;
                                    function tryUnmute() {
                                        var videos = document.querySelectorAll('video');
                                        var unmuted = false;
                                        videos.forEach(function(v) {
                                            if (v.muted) {
                                                v.muted = false;
                                                console.log('[TapLink] Unmuted video element');
                                                unmuted = true;
                                            }
                                        });
                                        // Also try clicking YouTube's own unmute button as fallback
                                        if (!unmuted || videos.length === 0) {
                                            var muteBtn = document.querySelector('.ytp-mute-button');
                                            if (muteBtn) {
                                                var vol = muteBtn.getAttribute('data-title-no-tooltip') ||
                                                          muteBtn.getAttribute('title') || '';
                                                if (vol.toLowerCase().indexOf('unmute') >= 0 ||
                                                    vol.toLowerCase().indexOf('muted') >= 0) {
                                                    muteBtn.click();
                                                    console.log('[TapLink] Clicked YouTube unmute button');
                                                    unmuted = true;
                                                }
                                            }
                                        }
                                        attempts++;
                                        if (!unmuted && attempts < 15) {
                                            setTimeout(tryUnmute, 800);
                                        }
                                    }
                                    // YouTube loads the player dynamically; wait a moment
                                    setTimeout(tryUnmute, 1500);

                                    // Also watch for new video elements via MutationObserver
                                    var ytObserver = new MutationObserver(function() {
                                        var videos = document.querySelectorAll('video');
                                        videos.forEach(function(v) {
                                            if (v.muted && !v.dataset.taplinkUnmuted) {
                                                v.muted = false;
                                                v.dataset.taplinkUnmuted = 'true';
                                                console.log('[TapLink] MutationObserver unmuted video');
                                            }
                                        });
                                    });
                                    if (document.body) {
                                        ytObserver.observe(document.body, { childList: true, subtree: true });
                                    }
                                    console.log('[TapLink] YouTube auto-unmute script initialized');
                                })();
                                """,
                                            null
                                    )
                                }
                            }
                        }

                        override fun onRenderProcessGone(
                                view: WebView?,
                                detail: android.webkit.RenderProcessGoneDetail?
                        ): Boolean {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                if (detail?.didCrash() == true) {
                                    DebugLog.e("WebView", "Render process crashed!")
                                    dualWebViewGroup.showConfirmDialog(
                                            "The web page crashed. Reload?",
                                            { view?.reload() },
                                            { /* Do nothing */}
                                    )
                                } else {
                                    DebugLog.e("WebView", "Render process killed by system (OOM).")
                                    // If system killed it, we can just return true and let the OS
                                    // handle it,
                                    // or offer a reload.
                                }
                            }
                            return true // Prevent app crash
                        }

                        override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                        ): Boolean {
                            val uri = request?.url ?: return false
                            val url = uri.toString()

                            // Block about:blank navigations
                            if (url.startsWith("about:blank")) {
                                return true
                            }

                            val scheme = uri.scheme?.lowercase()

                            // Handle app intents
                            if (scheme == "intent" || scheme == "market") {
                                val fallbackUrl =
                                        url.substringAfter("fallback_url=", "")
                                                .substringBefore("#", "")
                                                .substringBefore("&", "")

                                if (fallbackUrl.isNotEmpty() &&
                                                (fallbackUrl.startsWith("http") ||
                                                        fallbackUrl.startsWith("https"))
                                ) {
                                    view?.loadUrl(fallbackUrl)
                                    return true
                                }
                                return true
                            }

                            // Let WebView handle schemes it natively understands
                            if (scheme == null ||
                                            scheme == "http" ||
                                            scheme == "https" ||
                                            scheme == "file" ||
                                            scheme == "about" ||
                                            scheme == "data" ||
                                            scheme == "blob" ||
                                            scheme == "javascript"
                            ) {
                                return false
                            }

                            // For app/deep-link schemes (e.g., TikTok snssdk1233://), try external
                            return try {
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(intent)
                                true
                            } catch (e: ActivityNotFoundException) {
                                DebugLog.w("WebView", "No handler for URL scheme: $scheme ($url)")
                                true
                            } catch (e: Exception) {
                                DebugLog.w("WebView", "Failed to open external URL: $url")
                                true
                            }
                        }
                    }
            // Add more detailed logging to track input field interactions
            webView.evaluateJavascript(
                    """
        (function() {
            document.addEventListener('focus', function(e) {
                console.log('Focus event:', {
                    target: e.target.tagName,
                    type: e.target.type,
                    isInput: e.target instanceof HTMLInputElement,
                    isTextArea: e.target instanceof HTMLTextAreaElement,
                    isContentEditable: e.target.isContentEditable
                });
            }, true);
        })();
    """,
                    null
            )

            // Consolidate WebChromeClient to handle permissions, file choosing, and custom views
            webChromeClient =
                    object : WebChromeClient() {
                        // From first client
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            dualWebViewGroup.updateLoadingProgress(newProgress)
                        }
                        override fun onReceivedTouchIconUrl(
                                view: WebView?,
                                url: String?,
                                precomposed: Boolean
                        ) {
                            DebugLog.d("WebViewDebug", "Received touch icon URL: $url")
                            super.onReceivedTouchIconUrl(view, url, precomposed)
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            super.onReceivedTitle(view, title)
                            dualWebViewGroup.refreshMaskedNowPlaying()
                        }

                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            DebugLog.d(
                                    "WebViewInput",
                                    "${consoleMessage.messageLevel()} [${consoleMessage.lineNumber()}]: ${consoleMessage.message()}"
                            )
                            return true
                        }

                        // Combined onPermissionRequest
                        override fun onPermissionRequest(request: PermissionRequest) {
                            DebugLog.d(
                                    "WebView",
                                    "Permission request: ${request.resources.joinToString()}"
                            )

                            val permissions = mutableListOf<String>()
                            val requiredAndroidPermissions = mutableListOf<String>()

                            request.resources.forEach { resource ->
                                when (resource) {
                                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                                        permissions.add(resource)
                                        requiredAndroidPermissions.add(
                                                android.Manifest.permission.RECORD_AUDIO
                                        )
                                        // Configure AR glasses microphone for voice assistant mode
                                        audioManager?.setParameters(
                                                "audio_source_record=voiceassistant"
                                        )
                                    }
                                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                                        permissions.add(resource)
                                        requiredAndroidPermissions.add(
                                                android.Manifest.permission.CAMERA
                                        )
                                    }
                                }
                            }

                            runOnUiThread {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    val notGrantedPermissions =
                                            requiredAndroidPermissions.filter {
                                                checkSelfPermission(it) !=
                                                        PackageManager.PERMISSION_GRANTED
                                            }

                                    if (notGrantedPermissions.isNotEmpty()) {
                                        pendingPermissionRequest = request
                                        requestPermissions(
                                                notGrantedPermissions.toTypedArray(),
                                                PERMISSIONS_REQUEST_CODE
                                        )
                                    } else {
                                        request.grant(permissions.toTypedArray())
                                    }
                                } else {
                                    request.grant(permissions.toTypedArray())
                                }
                            }
                        }

                        override fun onPermissionRequestCanceled(request: PermissionRequest) {
                            pendingPermissionRequest = null
                            // Reset audio source when permissions are cancelled
                            audioManager?.setParameters("audio_source_record=off")
                        }

                        override fun onGeolocationPermissionsShowPrompt(
                                origin: String,
                                callback: GeolocationPermissions.Callback
                        ) {
                            // Always grant permission for WebView content so our injected GPS logic
                            // takes over
                            noteGeolocationUse()
                            callback.invoke(origin, true, false)
                        }

                        // From second client
                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            if (view == null) {
                                callback?.onCustomViewHidden()
                                return
                            }
                            showFullScreenCustomView(view, callback)
                        }

                        override fun onHideCustomView() {
                            hideFullScreenCustomView()
                        }

                        // From first client
                        override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                        ): Boolean {
                            // Cancel any ongoing request
                            this@MainActivity.filePathCallback?.onReceiveValue(null)
                            this@MainActivity.filePathCallback = null

                            this@MainActivity.filePathCallback = filePathCallback

                            // Build an Intent array to include camera capture + file choose
                            val takePictureIntent = createCameraIntent()
                            val contentSelectionIntent =
                                    createContentSelectionIntent(fileChooserParams?.acceptTypes)

                            // Let user pick from either camera or existing files
                            val intentArray =
                                    if (takePictureIntent != null) arrayOf(takePictureIntent)
                                    else arrayOfNulls<Intent>(0)

                            // Create a chooser
                            val chooserIntent =
                                    Intent(Intent.ACTION_CHOOSER).apply {
                                        putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                                        putExtra(Intent.EXTRA_TITLE, "Image Chooser")
                                        putExtra(
                                                Intent.EXTRA_INITIAL_INTENTS,
                                                intentArray.filterNotNull().toTypedArray()
                                        )
                                    }

                            try {
                                @Suppress("DEPRECATION")
                                startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE)
                            } catch (e: ActivityNotFoundException) {
                                this@MainActivity.filePathCallback = null
                                return false
                            }
                            return true
                        }

                        // Custom Dialog Handling
                        override fun onJsAlert(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: android.webkit.JsResult?
                        ): Boolean {
                            dualWebViewGroup.showAlertDialog(message ?: "") { result?.confirm() }
                            return true
                        }

                        override fun onJsConfirm(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: android.webkit.JsResult?
                        ): Boolean {
                            dualWebViewGroup.showConfirmDialog(
                                    message ?: "",
                                    { result?.confirm() },
                                    { result?.cancel() }
                            )
                            return true
                        }

                        override fun onJsPrompt(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                defaultValue: String?,
                                result: android.webkit.JsPromptResult?
                        ): Boolean {
                            dualWebViewGroup.showPromptDialog(
                                    message ?: "",
                                    defaultValue,
                                    { text -> result?.confirm(text) },
                                    { result?.cancel() }
                            )
                            return true
                        }

                        override fun onJsBeforeUnload(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: android.webkit.JsResult?
                        ): Boolean {
                            dualWebViewGroup.showConfirmDialog(
                                    message ?: "Are you sure you want to leave this page?",
                                    { result?.confirm() },
                                    { result?.cancel() }
                            )
                            return true
                        }

                        override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: android.os.Message?
                        ): Boolean {
                            // Must provide a pristine WebView here; Chromium will navigate it.
                            val newWebView = dualWebViewGroup.createNewWindow(loadDefaultUrl = false)
                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                            if (transport != null) {
                                transport.webView = newWebView
                                resultMsg.sendToTarget()
                                return true
                            }
                            return false
                        }
                    }
        }

        // Section 5: Input Handling Configuration
        // Section 5: Input Handling Configuration
        disableDefaultKeyboard(webView)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        // Only restore session for a plain reopen. If TapClaw explicitly launched
        // a URL, that explicit request must win over any persisted browser state.
        if (webView == dualWebViewGroup.getWebView() && startupUrlOverride.isNullOrBlank()) {
            tryRestoreSession()
        }

        // Initialize AudioManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Additional WebView settings for media support
        webView.settings.apply {
            mediaPlaybackRequiresUserGesture = false
            domStorageEnabled = true
            javaScriptEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
        }

        logPermissionState() // Log initial permission state

        webView.addJavascriptInterface(WebAppInterface(this, webView), "GroqBridge")

        val androidIface = AndroidInterface(this, webView)
        ttsAndroidInterface = androidIface
        webView.addJavascriptInterface(androidIface, "AndroidInterface")

        // On-glasses Media Library: exposes MediaLibraryService directly to
        // the library_local.html / media_player.html asset pages as
        // `window.TapMedia`. No HTTP server, no companion-app round-trip —
        // the bridge reads/writes files directly in the app-private
        // Media/ folder. Untrusted pages (i.e. anything not served from
        // /android_asset/) see `{"error":"Not permitted from this page"}`.
        webView.addJavascriptInterface(mediaLibraryBridge, MediaLibraryBridge.JS_NAME)
        // Wire the async TTS pipeline's back-channel: the bridge runs synth
        // on a worker thread and calls this lambda when the audio URL is
        // ready. We post to the WebView's UI thread because evaluateJavascript
        // must be invoked from there.
        mediaLibraryBridge.jsEvaluator = { js ->
            webView.post { webView.evaluateJavascript(js, null) }
        }
        // photos_gallery.html → "Grant access to device photos" → bridge
        // → this lambda. We have to do the actual requestPermissions call
        // from the host Activity because that's the only context that has
        // the onRequestPermissionsResult delivery. The bridge marshals to
        // the main thread before invoking, so this can safely call
        // requestPermissions directly.
        mediaLibraryBridge.permissionRequester = {
            try {
                val needed = com.TapLink.app.media.DcimEnumerator
                    .requiredPermissions()
                    .filter { p ->
                        androidx.core.content.ContextCompat
                            .checkSelfPermission(this, p) !=
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                if (needed.isNotEmpty()) {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this, needed.toTypedArray(), MEDIA_PERMISSIONS_REQUEST_CODE
                    )
                }
            } catch (e: Exception) {
                DebugLog.w("MediaPerm", "requestMediaPermission launch failed: ${e.message}")
            }
        }
        // Add JavaScript interface for custom media handling if needed
        webView.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onMediaStart(type: String) {
                        when (type) {
                            "audio" ->
                                    audioManager?.setParameters(
                                            "audio_source_record=voiceassistant"
                                    )
                            "video" -> {
                                /* Handle camera initialization if needed */
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onMediaStop() {
                        audioManager?.setParameters("audio_source_record=off")
                    }
                },
                "AndroidMediaInterface"
        )
    }

    private fun tryRestoreSession() {
        // Before loading the initial page, try to restore the previous session
        DebugLog.d("YouTubeAuto", "tryRestoreSession: startupUrl=$startupUrlOverride query=$youtubeAutoplayQuery")
        DebugLog.d("WebViewDebug", "Attempting to restore previous session")

        try {
            dualWebViewGroup.updateBrowsingMode(dualWebViewGroup.isDesktopMode())
            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
            var savedState = prefs.getString(Constants.KEY_WEBVIEW_STATE, null)
            var lastUrl = prefs.getString(keyLastUrl, null)
            DebugLog.d("WebViewDebug", "Last saved URL: $lastUrl")

            val defaultDashboardUrl = Constants.DEFAULT_URL

            // Belt-and-suspenders: if the persisted session still points at a
            // TapRadio auto-play URL (radio.html/podcasts.html/spotify.html
            // carrying playurl=/autoplay=1/spotifyqueue=), refuse to restore
            // it — those query params cause radio.html to auto-start playback
            // on load, which is the exact ghost-station bug the user has
            // reported repeatedly. Wipe the prefs so we don't keep replaying
            // this on every launch.
            if (lastUrl != null && isRadioAutoplayUrl(lastUrl)) {
                DebugLog.w(
                    "WebViewDebug",
                    "Refusing to restore radio auto-play URL, forcing default dashboard"
                )
                try {
                    prefs.edit()
                        .putString(Constants.KEY_LAST_URL, defaultDashboardUrl)
                        .remove(Constants.KEY_WEBVIEW_STATE)
                        .commit()
                } catch (_: Exception) {}
                lastUrl = defaultDashboardUrl
                savedState = null
            }

            var restored = false

            if (!savedState.isNullOrBlank()) {
                try {
                    val data = Base64.decode(savedState, Base64.DEFAULT)
                    val parcel = Parcel.obtain()
                    parcel.unmarshall(data, 0, data.size)
                    parcel.setDataPosition(0)
                    val bundle = Bundle.CREATOR.createFromParcel(parcel)
                    parcel.recycle()
                    restored = webView.restoreState(bundle) != null
                    DebugLog.d("WebViewDebug", "WebView state restored: $restored")
                } catch (e: Exception) {
                    DebugLog.e("WebViewDebug", "Error restoring WebView state", e)
                }
            }

            if (!restored) {
                if (lastUrl != null && !lastUrl.startsWith("about:blank")) {
                    DebugLog.d("WebViewDebug", "Loading saved URL: $lastUrl")
                    webView.loadUrl(lastUrl)
                } else {
                    DebugLog.d("WebViewDebug", "No valid saved URL, loading default AR dashboard")
                    webView.loadUrl(defaultDashboardUrl)
                }
            } else {
                // Restored pages may skip onPageFinished; inject observers and refresh scrollbars.
                webView.post {
                    syncActiveBrowserChrome(webView, includeDelayedPasses = false)
                    syncTapRadioPlaybackUi()
                }
                webView.postDelayed(
                        {
                            syncActiveBrowserChrome(webView, includeDelayedPasses = false)
                            syncTapRadioPlaybackUi()
                        },
                        750
                )
            }
        } catch (e: Exception) {
            DebugLog.e("WebViewDebug", "Error restoring session", e)
            webView.loadUrl(Constants.DEFAULT_URL)
        }
    }

    private fun persistTapRadioPlaybackState(
        stationName: String?,
        genre: String?,
        playing: Boolean,
        kind: String? = null,
        url: String? = null,
        positionMs: Long = 0L,
        durationMs: Long = 0L,
        error: String? = null
    ) {
        try {
            val prefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
            val trimmedName = stationName?.trim().takeUnless { it.isNullOrBlank() }
            val trimmedGenre = genre?.trim().takeUnless { it.isNullOrBlank() }
            val trimmedKind = kind?.trim()?.lowercase(Locale.US).takeUnless { it.isNullOrBlank() }
            val trimmedUrl = url?.trim().takeUnless { it.isNullOrBlank() }
            val trimmedError = error?.trim().takeUnless { it.isNullOrBlank() }
            val hasIdentity =
                trimmedName != null || trimmedGenre != null || trimmedKind != null || trimmedUrl != null
            prefs.edit().apply {
                putBoolean("tapradio_now_playing_active", playing)
                putLong("tapradio_now_playing_updated_at", System.currentTimeMillis())
                if (!playing) {
                    remove("tapradio_now_playing_name")
                    remove("tapradio_now_playing_genre")
                    remove("tapradio_now_playing_kind")
                    remove("tapradio_now_playing_url")
                    remove("tapradio_now_playing_position_ms")
                    remove("tapradio_now_playing_duration_ms")
                    remove("tapradio_now_playing_error")
                } else if (hasIdentity) {
                    if (trimmedName != null) putString("tapradio_now_playing_name", trimmedName) else remove("tapradio_now_playing_name")
                    if (trimmedGenre != null) putString("tapradio_now_playing_genre", trimmedGenre) else remove("tapradio_now_playing_genre")
                    if (trimmedKind != null) putString("tapradio_now_playing_kind", trimmedKind) else remove("tapradio_now_playing_kind")
                    if (trimmedUrl != null) putString("tapradio_now_playing_url", trimmedUrl) else remove("tapradio_now_playing_url")
                    putLong("tapradio_now_playing_position_ms", positionMs.coerceAtLeast(0L))
                    putLong("tapradio_now_playing_duration_ms", durationMs.coerceAtLeast(0L))
                    if (trimmedError != null) putString("tapradio_now_playing_error", trimmedError) else remove("tapradio_now_playing_error")
                } else {
                    remove("tapradio_now_playing_name")
                    remove("tapradio_now_playing_genre")
                    remove("tapradio_now_playing_kind")
                    remove("tapradio_now_playing_url")
                    remove("tapradio_now_playing_position_ms")
                    remove("tapradio_now_playing_duration_ms")
                    remove("tapradio_now_playing_error")
                }
                apply()
            }
        } catch (e: Exception) {
            DebugLog.e("TapRadioNative", "Error saving radio playback state", e)
        }
    }

    private fun clearTapRadioPlaybackPrefs() {
        // commit() (synchronous) rather than apply(): the chat Activity's
        // onResume() reads these prefs via syncTapRadioHudStateFromPrefs().
        // When a double-tap exit races the async write, the chat screen can
        // show a ghost "Now Playing" HUD for the station we just stopped.
        // commit() eliminates the race at the cost of a short blocking write.
        getSharedPreferences("visionclaw_prefs", MODE_PRIVATE).edit()
            .putBoolean("tapradio_now_playing_active", false)
            .putLong("tapradio_now_playing_updated_at", System.currentTimeMillis())
            .remove("tapradio_now_playing_name")
            .remove("tapradio_now_playing_genre")
            .remove("tapradio_now_playing_kind")
            .remove("tapradio_now_playing_url")
            .remove("tapradio_now_playing_position_ms")
            .remove("tapradio_now_playing_duration_ms")
            .remove("tapradio_now_playing_error")
            .commit()
    }

    private fun buildNativeRadioPlaybackStateJson(): String {
        val player = nativeRadioPlayer
        return org.json.JSONObject().apply {
            put("available", true)
            put("playing", player?.isPlaying == true && !nativeRadioPreparing)
            put("preparing", nativeRadioPreparing)
            put("buffering", nativeRadioBuffering)
            put("stationName", nativeRadioStationName ?: "")
            put("genre", nativeRadioGenre ?: "")
            put("url", nativeRadioUrl ?: "")
            put("error", nativeRadioError ?: "")
            put("kind", nativeRadioKind ?: "radio")
            // Position/duration for seekable content (podcasts).
            // Live radio returns C.TIME_UNSET for duration (-1).
            val posMs = player?.currentPosition ?: 0L
            val durMs = player?.duration?.let {
                if (it == androidx.media3.common.C.TIME_UNSET) 0L else it
            } ?: 0L
            put("positionMs", posMs)
            put("durationMs", durMs)
            put("updatedAt", System.currentTimeMillis())
        }.toString()
    }

    private fun notifyNativeRadioStateChanged(scheduleDelayedRebroadcasts: Boolean = true) {
        if (!::dualWebViewGroup.isInitialized) return
        broadcastRadioStateToWebViews()
        if (!scheduleDelayedRebroadcasts) return
        // ── Delayed re-broadcast ──
        // When a new radio.html page is loading, the immediate broadcast may arrive
        // before the page's JavaScript context is ready (window.tapRadioNativePlaybackUpdate
        // doesn't exist yet). Re-broadcast after a delay to catch pages that just finished loading.
        uiHandler.postDelayed({ broadcastRadioStateToWebViews() }, 800L)
        uiHandler.postDelayed({ broadcastRadioStateToWebViews() }, 2000L)
    }

    private fun broadcastRadioStateToWebViews() {
        if (!::dualWebViewGroup.isInitialized) return
        val stateJson = buildNativeRadioPlaybackStateJson()
        val script =
            """
            (function() {
                if (window.tapRadioNativePlaybackUpdate) {
                    window.tapRadioNativePlaybackUpdate($stateJson);
                }
            })();
            """.trimIndent()
        // Broadcast to radio.html AND podcasts.html so the mini-player
        // on podcasts.html can show current playback status.
        val targetPages = setOf("radio.html", "podcasts.html")
        dualWebViewGroup.getAllWebViews().forEach { candidate ->
            val url = candidate.url.orEmpty()
            if (targetPages.none { url.contains(it, ignoreCase = true) }) return@forEach
            candidate.post { candidate.evaluateJavascript(script, null) }
        }
    }

    private fun requestNativeRadioAudioFocus(): Boolean {
        val am = audioManager ?: (getSystemService(AUDIO_SERVICE) as? AudioManager)?.also { audioManager = it }
        return try {
            am?.requestAudioFocus(
                nativeRadioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Exception) {
            DebugLog.w("TapRadioNative", "Audio focus request failed: ${e.message}")
            false
        }
    }

    private fun abandonNativeRadioAudioFocus() {
        try {
            audioManager?.abandonAudioFocus(nativeRadioFocusListener)
        } catch (_: Exception) {}
    }

    private fun releaseNativeRadioPlayer(clearMetadata: Boolean, abandonFocus: Boolean) {
        stopNativeRadioProgressTicker()
        val local = nativeRadioPlayer
        val orphan = staticNativeRadioPlayer
        try {
            local?.stop()
            local?.release()
        } catch (_: Exception) {}
        // If a different ExoPlayer instance was tracked in the static slot
        // (e.g. a previous play started in a now-garbage-collected Activity
        // and never cleaned up), stop/release that too before clearing the
        // static reference. Without this, double-tapping out of TapRadio can
        // leave a "zombie" stream playing from an earlier station.
        if (orphan != null && orphan !== local) {
            try {
                orphan.stop()
                orphan.release()
            } catch (_: Exception) {}
        }
        nativeRadioPlayer = null
        staticNativeRadioPlayer = null  // clear static ref to prevent orphaned cleanup from re-releasing
        nativeRadioPreparing = false
        nativeRadioBuffering = false
        if (clearMetadata) {
            nativeRadioUrl = null
            nativeRadioStationName = null
            nativeRadioGenre = null
            nativeRadioKind = null
            nativeRadioError = null
        }
        if (abandonFocus) {
            abandonNativeRadioAudioFocus()
        }
    }

    private fun shouldKeepNativeRadioProgressTickerRunning(): Boolean {
        val player = nativeRadioPlayer ?: return false
        if (!nativeRadioKind.equals("podcast", ignoreCase = true)) return false
        if (!nativeRadioError.isNullOrBlank()) return false
        return nativeRadioPreparing || nativeRadioBuffering || player.isPlaying
    }

    private fun startNativeRadioProgressTicker() {
        uiHandler.removeCallbacks(nativeRadioProgressTicker)
        if (shouldKeepNativeRadioProgressTickerRunning()) {
            uiHandler.postDelayed(nativeRadioProgressTicker, 1000L)
        }
    }

    private fun stopNativeRadioProgressTicker() {
        uiHandler.removeCallbacks(nativeRadioProgressTicker)
    }

    private fun applyNativeRadioPlaybackUiState(scheduleDelayedBroadcasts: Boolean = true) {
        val playing = nativeRadioPlayer?.isPlaying == true && !nativeRadioPreparing
        val positionMs = nativeRadioPlayer?.currentPosition ?: 0L
        val durationMs = nativeRadioPlayer?.duration?.let {
            if (it == androidx.media3.common.C.TIME_UNSET) 0L else it
        } ?: 0L
        val active = playing || nativeRadioPreparing || nativeRadioBuffering
        persistTapRadioPlaybackState(
            nativeRadioStationName,
            nativeRadioGenre,
            active,
            kind = nativeRadioKind,
            url = nativeRadioUrl,
            positionMs = positionMs,
            durationMs = durationMs,
            error = nativeRadioError
        )
        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.setNativeTapRadioPlaybackActive(active)
        }
        if (shouldKeepNativeRadioProgressTickerRunning()) {
            startNativeRadioProgressTicker()
        } else {
            stopNativeRadioProgressTicker()
        }
        notifyNativeRadioStateChanged(scheduleDelayedRebroadcasts = scheduleDelayedBroadcasts)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun playNativeRadioStream(
        url: String,
        stationName: String?,
        genre: String?,
        kind: String? = null
    ): String {
        val trimmedUrl = url.trim()
        val normalizedKind = kind?.trim()?.lowercase(Locale.US).takeUnless { it.isNullOrBlank() } ?: "radio"
        if (trimmedUrl.isBlank()) {
            nativeRadioError = "Missing stream URL"
            notifyNativeRadioStateChanged()
            return buildNativeRadioPlaybackStateJson()
        }

        val sameStream = trimmedUrl == nativeRadioUrl && nativeRadioPlayer != null
        nativeRadioKind = normalizedKind
        if (sameStream) {
            return resumeNativeRadioStream()
        }

        // ── Kill any orphaned player from a previous Activity instance ──
        // This is the final safety net: if onDestroy didn't clean up (system killed
        // the process) and onCreate missed it (shouldn't happen, but belt-and-suspenders),
        // stop the static player reference before creating a new one.
        val orphan = staticNativeRadioPlayer
        if (orphan != null && orphan !== nativeRadioPlayer) {
            try { orphan.stop(); orphan.release() } catch (_: Exception) {}
            staticNativeRadioPlayer = null
        }

        requestNativeRadioAudioFocus()
        releaseNativeRadioPlayer(clearMetadata = false, abandonFocus = false)

        nativeRadioUrl = trimmedUrl
        nativeRadioStationName = stationName?.trim().takeUnless { it.isNullOrBlank() }
        nativeRadioGenre = genre?.trim().takeUnless { it.isNullOrBlank() }
        nativeRadioPreparing = true
        nativeRadioBuffering = false
        nativeRadioError = null
        applyNativeRadioPlaybackUiState()

        try {
            // ExoPlayer with large buffers to eliminate rebuffer stutters on MP3 streams.
            // MediaPlayer's fixed ~336KB buffer drains in ~14s at 192kbps, causing audible gaps.
            // ExoPlayer buffers 60s minimum / 120s target, making stutters virtually impossible.
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */       60_000,   // 60s minimum before playback starts rebuffering
                    /* maxBufferMs = */       120_000,  // 120s max buffer
                    /* bufferForPlaybackMs = */ 2_500,   // start playback after 2.5s buffered
                    /* bufferForPlaybackAfterRebufferMs = */ 5_000  // after a rebuffer, wait for 5s
                )
                .build()
            val player = ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ false  // we manage focus ourselves
                )
                .setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
                .build()

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            nativeRadioPreparing = false
                            nativeRadioBuffering = false
                            nativeRadioError = null
                            applyNativeRadioPlaybackUiState()
                        }
                        Player.STATE_BUFFERING -> {
                            nativeRadioBuffering = true
                            applyNativeRadioPlaybackUiState()
                        }
                        Player.STATE_ENDED -> {
                            nativeRadioPreparing = false
                            nativeRadioBuffering = false
                            applyNativeRadioPlaybackUiState()
                        }
                        Player.STATE_IDLE -> { /* no-op */ }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    nativeRadioPreparing = false
                    nativeRadioBuffering = false
                    nativeRadioError = "Playback error: ${error.message}"
                    releaseNativeRadioPlayer(clearMetadata = false, abandonFocus = true)
                    applyNativeRadioPlaybackUiState()
                }
            })

            player.setMediaItem(MediaItem.fromUri(trimmedUrl))
            player.prepare()
            player.playWhenReady = true
            nativeRadioPlayer = player
            staticNativeRadioPlayer = player  // keep static ref for cross-instance cleanup
        } catch (e: Exception) {
            nativeRadioPreparing = false
            nativeRadioBuffering = false
            nativeRadioError = e.message ?: "Failed to start stream"
            releaseNativeRadioPlayer(clearMetadata = false, abandonFocus = true)
            applyNativeRadioPlaybackUiState()
        }

        return buildNativeRadioPlaybackStateJson()
    }

    private fun pauseNativeRadioStreamInternal(abandonFocus: Boolean): String {
        try {
            nativeRadioPlayer?.pause()
        } catch (_: Exception) {}
        nativeRadioPreparing = false
        nativeRadioBuffering = false
        if (abandonFocus) {
            abandonNativeRadioAudioFocus()
        }
        applyNativeRadioPlaybackUiState()
        return buildNativeRadioPlaybackStateJson()
    }

    private fun pauseNativeRadioStream(): String = pauseNativeRadioStreamInternal(abandonFocus = true)

    private fun resumeNativeRadioStream(): String {
        val player = nativeRadioPlayer
        if (player == null) {
            val url = nativeRadioUrl
            return if (!url.isNullOrBlank()) {
                playNativeRadioStream(url, nativeRadioStationName, nativeRadioGenre, nativeRadioKind)
            } else {
                buildNativeRadioPlaybackStateJson()
            }
        }
        requestNativeRadioAudioFocus()
        nativeRadioError = null
        try {
            if (!player.isPlaying) {
                player.play()
            }
        } catch (e: Exception) {
            nativeRadioError = e.message ?: "Failed to resume stream"
        }
        nativeRadioPreparing = false
        nativeRadioBuffering = false
        applyNativeRadioPlaybackUiState()
        return buildNativeRadioPlaybackStateJson()
    }

    private fun stopNativeRadioStream(): String {
        releaseNativeRadioPlayer(clearMetadata = true, abandonFocus = true)
        clearTapRadioPlaybackPrefs()
        applyNativeRadioPlaybackUiState()
        return buildNativeRadioPlaybackStateJson()
    }

    private fun getNativeRadioPlaybackState(): String = buildNativeRadioPlaybackStateJson()

    fun hasNativeRadioSession(): Boolean {
        return nativeRadioPlayer != null || !nativeRadioUrl.isNullOrBlank()
    }

    fun pauseNativeRadioFromToolbar(): String {
        return pauseNativeRadioStream()
    }

    fun resumeNativeRadioFromToolbar(): String {
        return resumeNativeRadioStream()
    }

    /**
     * Public entry point for the dim-mode fallback HUD. When the user
     * enters dim mode and nothing is currently playing, the HUD shows
     * the first favorite (or the first station in the active genre) as
     * a hint; a single tap lands here and starts that station via the
     * native ExoPlayer path so audio plays even though the browser is
     * still on a non-radio page. The radio.html UI will sync via the
     * `tapradio_now_playing_*` SharedPreferences keys it polls.
     */
    fun startTapRadioFromMaskFallback(url: String, stationName: String?, genre: String?): String {
        return playNativeRadioStream(url, stationName, genre, kind = "radio")
    }

    /**
     * Whether a Gemini-built YouTube queue ("TapLink playlist") is
     * currently active and has at least one entry remaining. Used by
     * the dim-mode swipe handlers to choose between queue advance
     * (next/prev within Gemini's pick list) and YouTube's autoplay-
     * up-next fallback.
     */
    fun hasActiveYoutubePlaylist(): Boolean =
        youtubePlaylist.isNotEmpty() || youtubeAutoplayQueue.size > 1

    /** Whether the current queue index has a NEXT entry to advance to. */
    fun hasNextYoutubePlaylistEntry(): Boolean =
        (youtubePlaylist.isNotEmpty() && (youtubePlaylistIndex + 1) < youtubePlaylist.size) ||
            (youtubeAutoplayQueue.size > 1 && (youtubeAutoplayQueueIndex + 1) < youtubeAutoplayQueue.size)

    /** Whether the current queue index has a PREVIOUS entry to step back to. */
    fun hasPrevYoutubePlaylistEntry(): Boolean =
        (youtubePlaylist.isNotEmpty() && youtubePlaylistIndex > 0) ||
            (youtubeAutoplayQueue.size > 1 && youtubeAutoplayQueueIndex > 0)

    private fun mediaAssetRelativePath(url: String): String? {
        val marker = "://appassets.androidplatform.net/media/"
        val idx = url.lowercase(Locale.US).indexOf(marker)
        if (idx < 0) return null
        val encoded = url.substring(idx + marker.length)
            .substringBefore('?')
            .substringBefore('#')
        return try {
            java.net.URLDecoder.decode(encoded, "UTF-8")
        } catch (_: Exception) {
            encoded
        }
    }

    private fun sanitizedLibraryFilename(url: String, title: String?, kind: String?): String {
        val cleanKind = kind?.trim()?.lowercase(Locale.US).orEmpty()
        val urlName = try {
            Uri.parse(url).lastPathSegment?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        } catch (_: Exception) {
            null
        }.orEmpty()
        val rawName = title?.trim()?.takeIf { it.isNotBlank() } ?: urlName.ifBlank { "tapradio-media" }
        val extFromUrl = url.substringBefore('?').substringBefore('#')
            .substringAfterLast('.', "")
            .takeIf { it.length in 2..5 }
            ?.lowercase(Locale.US)
        val extFromTitle = rawName.substringBefore('?').substringBefore('#')
            .substringAfterLast('.', "")
            .takeIf { it.length in 2..8 }
            ?.lowercase(Locale.US)
        val fallbackExt = when {
            cleanKind.contains("playlist") || cleanKind.contains("m3u") -> "m3u"
            cleanKind.contains("video") -> "mp4"
            cleanKind.contains("text") || cleanKind.contains("document") -> "txt"
            else -> "mp3"
        }
        val ext = extFromUrl ?: extFromTitle ?: fallbackExt
        val withoutExt = rawName.substringBeforeLast('.', rawName)
        val safeBase = withoutExt
            .replace(Regex("""[\\/:*?"<>|\r\n]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { "tapradio-media" }
            .take(96)
        return "$safeBase.$ext"
    }

    private fun mediaLibraryTargetForUrl(url: String, title: String?, kind: String?): Pair<File, String> {
        val filename = sanitizedLibraryFilename(url, title, kind)
        val folder = mediaLibraryBridge.service.defaultFolderForFilename(filename, kind)
        val relative = "$folder/$filename"
        val target = mediaLibraryBridge.service.resolveSafe(relative)
            ?: File(mediaLibraryBridge.service.mediaRoot, relative)
        return target to relative
    }

    private fun buildMediaSavedStateJson(url: String, title: String?, kind: String?): String {
        val existingRel = mediaAssetRelativePath(url)
        if (!existingRel.isNullOrBlank()) {
            val file = mediaLibraryBridge.service.resolveSafe(existingRel)
            val exists = file?.exists() == true
            return JSONObject()
                .put("saved", exists)
                .put("path", if (exists) existingRel else "")
                .toString()
        }
        val (target, relative) = mediaLibraryTargetForUrl(url, title, kind)
        return JSONObject()
            .put("saved", target.exists())
            .put("path", if (target.exists()) relative else "")
            .toString()
    }

    private fun fetchUrlTextForBridge(url: String): String {
        mediaAssetRelativePath(url)?.let { rel ->
            val file = mediaLibraryBridge.service.resolveSafe(rel)
            if (file != null && file.exists() && file.isFile) {
                return JSONObject().put("text", file.readText(Charsets.UTF_8)).toString()
            }
        }
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "TapInsight/1.0")
            val code = conn.responseCode
            val body = try {
                if (code in 200..299) {
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    (conn.errorStream ?: conn.inputStream)
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            } finally {
                conn.disconnect()
            }
            if (code in 200..299) {
                JSONObject().put("text", body).toString()
            } else {
                JSONObject().put("error", "HTTP $code").toString()
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "Fetch failed").toString()
        }
    }

    private fun saveMediaUrlToLibraryJson(url: String, title: String?, kind: String?): String {
        val existingRel = mediaAssetRelativePath(url)
        if (!existingRel.isNullOrBlank()) {
            val file = mediaLibraryBridge.service.resolveSafe(existingRel)
            return JSONObject()
                .put("status", if (file?.exists() == true) "exists" else "error")
                .put("path", existingRel)
                .apply {
                    if (file?.exists() != true) put("error", "Media Library file not found")
                }
                .toString()
        }
        return try {
            val (target, relative) = mediaLibraryTargetForUrl(url, title, kind)
            if (target.exists() && target.length() > 0L) {
                return JSONObject().put("status", "exists").put("path", relative).toString()
            }
            target.parentFile?.mkdirs()
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 12_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "TapInsight/1.0")
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try {
                    (conn.errorStream ?: conn.inputStream).bufferedReader(Charsets.UTF_8).use { it.readText().take(160) }
                } catch (_: Exception) { "" }
                conn.disconnect()
                return JSONObject().put("status", "error").put("error", "HTTP $code $err").toString()
            }
            val length = conn.contentLengthLong
            val isPlaylist = relative.lowercase(Locale.US).endsWith(".m3u") || relative.lowercase(Locale.US).endsWith(".m3u8")
            if (!isPlaylist && length <= 0L) {
                conn.disconnect()
                return JSONObject()
                    .put("status", "error")
                    .put("error", "This looks like a live stream, not a finite audio file. Add it as a TapRadio station instead.")
                    .toString()
            }
            val maxBytes = if (isPlaylist) 2L * 1024L * 1024L else 300L * 1024L * 1024L
            if (length > maxBytes) {
                conn.disconnect()
                return JSONObject().put("status", "error").put("error", "File is too large to save on glasses.").toString()
            }
            var copied = 0L
            conn.inputStream.use { input ->
                java.io.FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        copied += read
                        if (copied > maxBytes) throw IOException("File exceeded save limit")
                        output.write(buffer, 0, read)
                    }
                }
            }
            conn.disconnect()
            JSONObject()
                .put("status", "saved")
                .put("path", relative)
                .put("bytes", copied)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("status", "error").put("error", e.message ?: "Save failed").toString()
        }
    }

    private class WebAppInterface(
            private val activity: MainActivity,
            private val webView: WebView
    ) {
        @JavascriptInterface
        fun onInputFocus() {
            activity.runOnUiThread {
                // Double check that we're not already showing the keyboard
                if (!activity.isKeyboardVisible) {
                    activity.showCustomKeyboard()
                }
            }
        }

        @JavascriptInterface
        fun onMediaPlaying(isPlaying: Boolean) {
            activity.runOnUiThread {
                activity.dualWebViewGroup.setMediaStateForWebView(webView, isPlaying)
                if (isPlaying && activity.dualWebViewGroup.isActiveWebView(webView)) {
                    activity.dualWebViewGroup.pauseBackgroundMedia(webView)
                }
            }
        }

        @JavascriptInterface
        fun onMediaDetected(hasMedia: Boolean) {
            activity.runOnUiThread {
                if (!hasMedia && activity.dualWebViewGroup.isActiveWebView(webView)) {
                    activity.dualWebViewGroup.hideMediaControls()
                }
            }
        }

        /** Called by search-page JS with a JSON array of video IDs scraped
         *  from the search results (chronological order). */
        @JavascriptInterface
        fun setYouTubePlaylist(jsonIds: String) {
            try {
                val arr = org.json.JSONArray(jsonIds)
                val ids = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i, "").trim()
                    if (id.length == 11) ids.add(id)
                }
                activity.runOnUiThread {
                    val queueActive = activity.youtubeAutoplayQueue.size > 1
                    activity.youtubePlaylist = if (queueActive) ids.take(1) else ids
                    activity.youtubePlaylistIndex = 0
                    DebugLog.d(
                        "YouTubeAuto",
                        "Playlist set: ${activity.youtubePlaylist.size}/${ids.size} videos " +
                            "queueActive=$queueActive — ${activity.youtubePlaylist.take(5)}"
                    )
                }
            } catch (e: Exception) {
                DebugLog.d("YouTubeAuto", "Failed to parse playlist JSON: $e")
            }
        }

        /** Called by watch-page JS to enter a CSS-based "fullscreen" mode.
         *  Since Android WebView blocks all programmatic fullscreen requests
         *  (requires real user gesture), we instead:
         *  1. Inject CSS to hide everything except the video player and
         *     make it fill the entire viewport
         *  2. Enter Android immersive mode (hide system bars)
         *  This gives the same visual result as real fullscreen. */
        @JavascriptInterface
        fun enterCssFullscreen() {
            activity.runOnUiThread {
                try {
                    DebugLog.d("YouTubeAuto", "enterCssFullscreen called")

                    // CSS-only fullscreen: hide non-video elements, make video fill viewport.
                    // Buttons are injected separately by injectNavButtons().
                    val js = "(function(){" +
                        "try{" +
                        "if(document.getElementById('__taplink_fs_style'))return 'already';" +
                        "var s=document.createElement('style');" +
                        "s.id='__taplink_fs_style';" +
                        "s.textContent=" +
                        "'body>*:not(#player):not(#movie_player):not(.html5-video-player):not(ytd-player):not(#player-container-outer):not(#player-container-inner):not(#player-container):not(ytd-watch-flexy):not(#content):not(#page-manager):not(ytd-app):not(#columns):not(#primary):not(#primary-inner):not(#__tl_nav){display:none!important}'" +
                        "+'\\n#movie_player,.html5-video-player,video{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;background:#000!important;object-fit:contain!important}'" +
                        "+'\\nhtml,body{overflow:hidden!important;margin:0!important;padding:0!important;background:#000!important}'" +
                        "+'\\n#masthead-container,#guide,ytd-masthead,#secondary,#below,#comments,#related,#meta,#info,#owner{display:none!important}'" +
                        "+'\\nytd-watch-flexy{max-width:100vw!important}'" +
                        "+'\\nytd-watch-flexy[theater] #player-theater-container,#player-theater-container,#player-container-outer,#player-container-inner,#player-container,ytd-player,#ytd-player{width:100vw!important;height:100vh!important;max-height:100vh!important;position:fixed!important;top:0!important;left:0!important;z-index:999998!important}'" +
                        ";" +
                        "document.head.appendChild(s);" +
                        "console.log('[TapLink-YT] CSS fullscreen applied');" +
                        "return 'ok';" +
                        "}catch(err){console.log('[TapLink-YT] enterCssFs JS error: '+err);return 'error:'+err;}" +
                        "})()"
                    webView.evaluateJavascript(js) { result ->
                        DebugLog.d("YouTubeAuto", "CSS fullscreen result: $result")
                    }
                    runCatching { activity.dualWebViewGroup.setYoutubeCssFullModeActive(true) }

                    // Enter Android immersive mode
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility =
                        (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                    // Full = real full-screen browser: hide the app HUD overlay
                    // + the browser's own nav bars so only the video shows.
                    runCatching { activity.applyYoutubeFullscreenChrome(true) }
                    DebugLog.d("YouTubeAuto", "Entered CSS fullscreen + immersive mode")
                } catch (e: Exception) {
                    DebugLog.d("YouTubeAuto", "enterCssFullscreen failed: $e")
                }
            }
        }

        /** Injects persistent View Mode + Next buttons on any YouTube watch page.
         *  View Mode cycles: Full → Theater → Mini → Full...
         *  Full = our CSS fullscreen overlay (video fills viewport).
         *  Theater/Mini = YouTube's native modes (CSS overlay removed).
         *  Buttons go on document.body to survive YouTube DOM rebuilds.
         *  window.__tl_view_mode is preserved across re-injections. */
        @JavascriptInterface
        fun injectNavButtons() {
            activity.runOnUiThread {
                try {
                    DebugLog.d("YouTubeAuto", "injectNavButtons called")

                    val js = "(function(){" +
                        "try{" +
                        "if(document.getElementById('__tl_nav'))return 'already';" +
                        // Style
                        "if(!document.getElementById('__tl_nav_style')){" +
                        "var s=document.createElement('style');" +
                        "s.id='__tl_nav_style';" +
                        "s.textContent=" +
                        "'#__tl_nav{position:fixed;top:6px;right:12px;z-index:2000000;display:flex;flex-direction:column;align-items:flex-end;gap:6px;pointer-events:auto!important}'" +
                        "+'\\n#__tl_nav button{background:rgba(0,0,0,0.7);border:1px solid rgba(255,255,255,0.3);color:#fff;font-size:16px;padding:8px 14px;border-radius:8px;cursor:pointer;white-space:nowrap;pointer-events:auto!important}'" +
                        "+'\\n#__tl_nav button:active{background:rgba(255,255,255,0.3)}'" +
                        "+'\\n#__tl_nav .tl-mode{font-size:13px;padding:8px 10px}'" +
                        "+'\\n#__tl_nav .tl-skip{font-size:18px;font-weight:700;line-height:1;padding:8px 0;min-width:44px;text-align:center}';" +
                        "document.head.appendChild(s);" +
                        "}" +
                        // Nav container on document.body
                        "var nav=document.createElement('div');" +
                        "nav.id='__tl_nav';" +
                        //
                        // === View Mode button ===
                        // 0=Full(CSS), 1=Theater(YT native), 2=Mini(YT native)
                        //
                        "var bView=document.createElement('button');" +
                        "bView.id='__tl_view';" +
                        "bView.className='tl-mode';" +
                        "var labels=['Full','Theater','Mini'];" +
                        // Deterministic, CSS-driven view modes. YouTube's native
                        // theater / miniplayer buttons do NOT reliably toggle
                        // inside this WebView (the .click() is a no-op), which is
                        // why the display never changed and the label snapped
                        // back. All three modes are now applied with OUR OWN
                        // injected <style> (the same proven technique as Full):
                        //   Full    = video fills the viewport (+ immersive)
                        //   Theater = video pinned as a wide top banner, page
                        //             (description/comments) scrollable below
                        //   Mini    = small floating player, page scrolls behind
                        // Switching is instant and the detected mode always
                        // matches what we applied, so the label can't revert.
                        "window.__tlTheaterCss='html,body{overflow-x:hidden!important;overflow-y:auto!important;width:100vw!important;max-width:100vw!important;margin:0!important;padding:0!important;background:#0f0f0f!important}#player-container-outer,#player-container-inner,#player-container,ytd-player,#ytd-player,#movie_player,.html5-video-player{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:30vh!important;max-height:30vh!important;z-index:999998!important;background:#000!important;transform:none!important}#movie_player .html5-video-container,.html5-video-player .html5-video-container{position:absolute!important;top:0!important;left:0!important;width:100%!important;height:100%!important;transform:none!important}#movie_player video,.html5-video-player video{position:absolute!important;top:0!important;left:0!important;width:100%!important;height:100%!important;object-fit:contain!important;background:#000!important;transform:none!important}#masthead-container,ytd-masthead,#guide,#secondary,#related,ytd-watch-next-secondary-results-renderer,ytd-compact-video-renderer,yt-related-chip-cloud-renderer{display:none!important}ytd-watch-flexy,#columns,#primary,#primary-inner{display:block!important;width:100vw!important;max-width:100vw!important;margin:0!important;padding:0!important;box-sizing:border-box!important;transform:none!important}ytd-watch-flexy #player,#player-theater-container{height:0!important;min-height:0!important;max-height:0!important;margin:0!important;padding:0!important;overflow:visible!important}ytd-watch-flexy #primary{padding-top:calc(30vh + 4px)!important}ytd-watch-metadata,#above-the-fold,#info,#meta,ytd-watch-metadata #title{margin-top:0!important;padding-top:0!important}ytd-merch-shelf-renderer,#ticket-shelf,#donation-shelf,#clarify-box,#offer-module{display:none!important}#below,#meta,#info,#comments,ytd-comments{display:block!important;position:relative!important;z-index:1!important;width:calc(100vw - 24px)!important;max-width:calc(100vw - 24px)!important;margin:0 12px!important;padding:0!important;box-sizing:border-box!important;clear:both!important;transform:none!important}';" +
                        "window.__tlMiniCss='html,body{overflow-x:hidden!important;overflow-y:auto!important;width:100vw!important;max-width:100vw!important;background:#0f0f0f!important}#player-container-outer,#player-container-inner,#player-container,ytd-player,#ytd-player,#movie_player,.html5-video-player{position:fixed!important;top:auto!important;left:auto!important;bottom:10px!important;right:10px!important;width:42vw!important;height:24vw!important;max-width:42vw!important;max-height:24vw!important;z-index:2147483646!important;background:#000!important;border:1px solid #333!important;border-radius:6px!important;overflow:hidden!important;box-shadow:0 2px 12px rgba(0,0,0,0.6)!important;transform:none!important}#movie_player .html5-video-container,.html5-video-player .html5-video-container{position:absolute!important;top:0!important;left:0!important;width:100%!important;height:100%!important;transform:none!important}#movie_player video,.html5-video-player video{position:absolute!important;top:0!important;left:0!important;width:100%!important;height:100%!important;object-fit:contain!important;background:#000!important;transform:none!important}ytd-watch-flexy #player{height:0!important;min-height:0!important;max-height:0!important;margin:0!important;padding:0!important;overflow:visible!important}#columns,#primary,#primary-inner,#below{margin:0!important;padding:0!important;max-width:100vw!important;width:100vw!important;box-sizing:border-box!important;transform:none!important}';" +
                        "window.__tlClearViewStyles=function(){['__taplink_fs_style','__tl_theater_style','__tl_mini_style'].forEach(function(id){var el=document.getElementById(id);if(el&&el.parentNode)el.parentNode.removeChild(el);});};" +
                        "window.__tlInject=function(id,css){var el=document.getElementById(id);if(!el){el=document.createElement('style');el.id=id;document.head.appendChild(el);}el.textContent=css;};" +
                        // Read-only detection: which of OUR style elements is live.
                        "window.__tlDetectMode=function(){try{if(document.getElementById('__tl_theater_style'))return 1;if(document.getElementById('__tl_mini_style'))return 2;if(document.getElementById('__taplink_fs_style'))return 0;return 0;}catch(e){return 0;}};" +
                        // Apply a mode by swapping the single active <style>.
                        "window.__tlApplyMode=function(target){try{window.__tlClearViewStyles();if(target===1){try{window.GroqBridge.exitImmersiveMode();}catch(x){}window.__tlInject('__tl_theater_style',window.__tlTheaterCss);}else if(target===2){try{window.GroqBridge.exitImmersiveMode();}catch(x){}window.__tlInject('__tl_mini_style',window.__tlMiniCss);}else{try{window.GroqBridge.enterCssFullscreen();}catch(x){}}}catch(e){console.log('[TapLink-YT] applyMode err:'+e);}};" +
                        // Initial label from the live state.
                        "window.__tl_view_mode=window.__tlDetectMode();" +
                        "bView.textContent=labels[window.__tl_view_mode||0];" +
                        "bView.addEventListener('click',function(e){" +
                        "e.stopPropagation();e.preventDefault();" +
                        "var now=Date.now();" +
                        "if(window.__tl_last_view_click&&now-window.__tl_last_view_click<600)return;" +
                        "window.__tl_last_view_click=now;" +
                        "var cur=window.__tlDetectMode();" +
                        // Cycle in the requested order: Full -> Mini -> Theater.
                        "var order=[0,2,1];" +
                        "var oi=order.indexOf(cur);if(oi<0)oi=0;" +
                        "var next=order[(oi+1)%order.length];" +
                        "console.log('[TapLink-YT] View: '+labels[cur]+' -> '+labels[next]);" +
                        "window.__tlApplyMode(next);" +
                        "window.__tl_view_mode=next;" +
                        "bView.textContent=labels[next];" +
                        "console.log('[TapLink-YT] View mode set to: '+labels[next]);" +
                        "});" +
                        //
                        // === Explicit prev/next buttons ===
                        // Swipe-to-skip is intentionally disabled; the user
                        // gets deterministic skip controls stacked under
                        // the Full/Theater/Mini view-mode button.
                        //
                        "function tlStop(ev){ev.stopPropagation();ev.preventDefault();}" +
                        "var bPrev=document.createElement('button');" +
                        "bPrev.id='__tl_prev';" +
                        "bPrev.className='tl-skip';" +
                        "bPrev.textContent='<';" +
                        "bPrev.setAttribute('aria-label','Previous video');" +
                        "bPrev.addEventListener('click',function(e){" +
                        "tlStop(e);" +
                        "var now=Date.now();" +
                        "if(window.__tl_last_prev_click&&now-window.__tl_last_prev_click<650)return;" +
                        "window.__tl_last_prev_click=now;" +
                        "try{window.GroqBridge.playPrevInPlaylist();}catch(x){}" +
                        "});" +
                        "var bNext=document.createElement('button');" +
                        "bNext.id='__tl_next';" +
                        "bNext.className='tl-skip';" +
                        "bNext.textContent='>';" +
                        "bNext.setAttribute('aria-label','Next video');" +
                        "bNext.addEventListener('click',function(e){" +
                        "tlStop(e);" +
                        "var now=Date.now();" +
                        "if(window.__tl_last_next_click&&now-window.__tl_last_next_click<650)return;" +
                        "window.__tl_last_next_click=now;" +
                        "try{window.GroqBridge.playNextInPlaylist();}catch(x){}" +
                        "});" +
                        // === Append to body + watchdog ===
                        //
                        "nav.appendChild(bView);" +
                        "nav.appendChild(bPrev);" +
                        "nav.appendChild(bNext);" +
                        "document.body.appendChild(nav);" +
                        // Watchdog: re-inject if YouTube removes buttons
                        "if(window.__tl_nav_watchdog)clearInterval(window.__tl_nav_watchdog);" +
                        "window.__tl_nav_watchdog=setInterval(function(){" +
                        "if(!document.getElementById('__tl_nav')){" +
                        "clearInterval(window.__tl_nav_watchdog);" +
                        "console.log('[TapLink-YT] Nav buttons lost, re-injecting');" +
                        "try{window.GroqBridge.injectNavButtons();}catch(x){}" +
                        "}" +
                        "},2000);" +
                        "console.log('[TapLink-YT] Nav button injected on body (View:'+labels[window.__tl_view_mode||0]+')');" +
                        "return 'ok';" +
                        "}catch(err){console.log('[TapLink-YT] injectNav error: '+err);return 'error:'+err;}" +
                        "})()"
                    webView.evaluateJavascript(js) { result ->
                        DebugLog.d("YouTubeAuto", "injectNavButtons result: $result")
                    }
                } catch (e: Exception) {
                    DebugLog.d("YouTubeAuto", "injectNavButtons failed: $e")
                }
            }
        }

        /** Exits Android immersive mode (called when leaving CSS fullscreen view). */
        @JavascriptInterface
        fun exitImmersiveMode() {
            activity.runOnUiThread {
                try {
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility =
                        (View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                    runCatching { activity.dualWebViewGroup.setYoutubeCssFullModeActive(false) }
                    runCatching { activity.dualWebViewGroup.restoreScrollBarsAfterFullscreen() }
                    // Theater/Mini: bring the app HUD + browser nav bars back.
                    runCatching { activity.applyYoutubeFullscreenChrome(false) }
                    DebugLog.d("YouTubeAuto", "Exited immersive mode")
                } catch (e: Exception) {
                    DebugLog.d("YouTubeAuto", "exitImmersiveMode failed: $e")
                }
            }
        }

        private fun buildQueuedYouTubeSearchUrl(query: String, mode: String): String {
            val searchPhrase =
                if (mode == "music" && !Regex("(?i)\\b(?:music|songs?|audio|track)\\b").containsMatchIn(query)) {
                    "$query music"
                } else {
                    query
                }
            val encoded = java.net.URLEncoder.encode(searchPhrase, "UTF-8")
            return "https://www.youtube.com/results?search_query=$encoded&taplink_autoplay=$mode"
        }

        private fun navigateRequestedYouTubeQueue(delta: Int): Boolean {
            val queue = activity.youtubeAutoplayQueue
            if (queue.size <= 1) return false
            val nextQueueIndex = activity.youtubeAutoplayQueueIndex + delta
            if (nextQueueIndex !in queue.indices) return false

            activity.youtubeAutoplayQueueIndex = nextQueueIndex
            val nextQuery = queue[nextQueueIndex]
            val mode = activity.youtubeAutoplayMode?.takeIf { it.isNotBlank() } ?: "video"
            activity.youtubeAutoplayQuery = nextQuery
            activity.youtubePlaylist = emptyList()
            activity.youtubePlaylistIndex = 0
            activity.lastYouTubeInjectionUrl = null
            activity.dualWebViewGroup.scheduleMaskedTrackChangeRefresh()

            val nextUrl = buildQueuedYouTubeSearchUrl(nextQuery, mode)
            val direction = if (delta > 0) "next" else "previous"
            DebugLog.d(
                "YouTubeAuto",
                "Navigating requested $direction queue item " +
                    "[$nextQueueIndex/${queue.size}]: $nextQuery"
            )
            val jsNavigate = """
                (function(){
                    try {
                        window.__taplink_yt_injected = false;
                        window.__taplink_watch_injected = false;
                        window.__taplink_playback_started = false;
                        var old = document.getElementById('__tl_nav');
                        if (old) old.remove();
                    } catch(e) {}
                    try {
                        window.location.href = ${org.json.JSONObject.quote(nextUrl)};
                        console.log('[TapLink-YT] Navigating requested queue item: ${org.json.JSONObject.quote(nextQuery)}');
                        return 'queue-nav';
                    } catch(err) {
                        console.log('[TapLink-YT] Requested queue navigation error: ' + err);
                        return 'error:' + err;
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(jsNavigate) { result ->
                DebugLog.d("YouTubeAuto", "  → requested queue navigation result=$result")
            }
            return true
        }

        /** Called by watch-page JS when the current video ends or user clicks
         *  the hijacked "next" button. Navigates to the next TapLink playlist
         *  entry using a normal YouTube watch-page transition so the title and
         *  surrounding watch metadata update with the video. */
        @JavascriptInterface
        fun playNextInPlaylist() {
            activity.runOnUiThread {
                val pl = activity.youtubePlaylist
                val nextIdx = activity.youtubePlaylistIndex + 1
                if (nextIdx < pl.size) {
                    activity.youtubePlaylistIndex = nextIdx
                    val nextId = pl[nextIdx]
                    DebugLog.d("YouTubeAuto", "Playing next [$nextIdx/${pl.size}]: $nextId")
                    activity.dualWebViewGroup.scheduleMaskedTrackChangeRefresh()

                    val nextUrl = "https://www.youtube.com/watch?v=$nextId&autoplay=1&cc_load_policy=1"
                    val jsNavigateNext = """
                        (function(){
                            try {
                                window.__taplink_yt_injected = false;
                                window.__taplink_watch_injected = false;
                                window.__taplink_playback_started = false;
                                var old = document.getElementById('__tl_nav');
                                if (old) old.remove();
                            } catch(e) {}
                            try {
                                window.location.href = ${org.json.JSONObject.quote(nextUrl)};
                                console.log('[TapLink-YT] Navigating next via watch URL: $nextId');
                                return 'nav';
                            } catch(err) {
                                console.log('[TapLink-YT] Next navigation error: ' + err);
                                return 'error:' + err;
                            }
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(jsNavigateNext) { result ->
                        DebugLog.d("YouTubeAuto", "  → next navigation result=$result")
                    }
                } else if (navigateRequestedYouTubeQueue(delta = 1)) {
                    // Moved to the next user-requested search item.
                } else {
                    DebugLog.d("YouTubeAuto", "Playlist finished (${pl.size} videos)")
                }
            }
        }

        /** Go back one video in the playlist. If already at the first video,
         *  just restart from the beginning. */
        @JavascriptInterface
        fun playPrevInPlaylist() {
            activity.runOnUiThread {
                val pl = activity.youtubePlaylist
                val prevIdx = activity.youtubePlaylistIndex - 1
                if (prevIdx >= 0 && prevIdx < pl.size) {
                    activity.youtubePlaylistIndex = prevIdx
                    val prevId = pl[prevIdx]
                    DebugLog.d("YouTubeAuto", "Playing prev [$prevIdx/${pl.size}]: $prevId")
                    activity.dualWebViewGroup.scheduleMaskedTrackChangeRefresh()

                    val prevUrl = "https://www.youtube.com/watch?v=$prevId&autoplay=1&cc_load_policy=1"
                    val jsNavigatePrev = """
                        (function(){
                            try {
                                window.__taplink_yt_injected = false;
                                window.__taplink_watch_injected = false;
                                window.__taplink_playback_started = false;
                                var old = document.getElementById('__tl_nav');
                                if (old) old.remove();
                            } catch(e) {}
                            try {
                                window.location.href = ${org.json.JSONObject.quote(prevUrl)};
                                console.log('[TapLink-YT] Navigating prev via watch URL: $prevId');
                                return 'nav';
                            } catch(err) {
                                console.log('[TapLink-YT] Prev navigation error: ' + err);
                                return 'error:' + err;
                            }
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(jsNavigatePrev) { result ->
                        DebugLog.d("YouTubeAuto", "  → prev navigation result=$result")
                    }
                } else if (navigateRequestedYouTubeQueue(delta = -1)) {
                    // Moved to the previous user-requested search item.
                } else {
                    DebugLog.d("YouTubeAuto", "Already at first video, restarting")
                    val v = "javascript:void(document.querySelector('video').currentTime=0)"
                    webView.loadUrl(v)
                }
            }
        }
    }

    // ── Media file interception for TapInsight media player ──
    private val MEDIA_TEXT_EXTS = setOf("txt","md","log","csv","json","xml","html","htm","rtf","ini","cfg","conf","yaml","yml","toml")
    private val MEDIA_AUDIO_EXTS = setOf("mp3","wav","ogg","m4a","aac","flac","wma","opus")
    private val MEDIA_VIDEO_EXTS = setOf("mp4","webm","mkv","avi","mov","m4v","ogv","3gp")

    private fun prepareExclusiveMediaPlayerPlayback() {
        runCatching { stopNativeRadioStream() }
        runCatching { stopCloudTts() }
        if (::dualWebViewGroup.isInitialized) {
            runCatching { dualWebViewGroup.pauseAllWindowsMedia() }
            runCatching { dualWebViewGroup.clearTrackedMediaPlayback() }
        }
    }

    /**
     * Checks if [url] points to a recognized media file and opens it in the
     * built-in media_player.html asset. Returns true if intercepted.
     *
     * For text files: downloads content in background, then loads media player with
     * content passed via `window._textContent` injection (avoids CORS from file:// origin).
     * For audio/video: loads media player directly — `<audio>/<video>` elements aren't
     * subject to CORS restrictions so remote URLs work.
     */
    private fun interceptMediaUrl(view: WebView, url: String): Boolean {
        // Never treat our own asset pages as "media" — they ARE the media
        // player UI. Navigating to /assets/library_local.html should load
        // the page, not open it in a text reader just because the URL ends
        // in .html.
        val lower = url.lowercase()
        if (lower.startsWith("file:///android_asset/") ||
            lower.startsWith("https://appassets.androidplatform.net/assets/") ||
            lower.startsWith("http://appassets.androidplatform.net/assets/")
        ) return false

        // Strip query string and fragment for extension detection
        val path = url.split("?")[0].split("#")[0]
        val ext = path.substringAfterLast('.', "").lowercase()
        val mediaType = when {
            MEDIA_TEXT_EXTS.contains(ext) -> "text"
            MEDIA_AUDIO_EXTS.contains(ext) -> "audio"
            MEDIA_VIDEO_EXTS.contains(ext) -> "video"
            else -> return false
        }
        prepareExclusiveMediaPlayerPlayback()
        val title = try {
            java.net.URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
        } catch (_: Exception) { path.substringAfterLast('/') }
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")

        // Read media player preferences from companion app settings
        val vcPrefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val voiceName = vcPrefs.getString("media_tts_voice", "") ?: ""
        val underline = vcPrefs.getBoolean("media_tts_underline", true)
        val mediaParams = buildString {
            if (voiceName.isNotBlank()) append("&voice=${java.net.URLEncoder.encode(voiceName, "UTF-8")}")
            if (!underline) append("&underline=false")
        }

        if (mediaType == "text") {
            // Download text content in background to avoid CORS
            DebugLog.d("MediaPlayer", "Intercepted text ($ext): $url — fetching content")
            Thread {
                try {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    conn.disconnect()
                    // Escape for JavaScript injection
                    val escaped = text
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("<", "\\x3c")
                        .replace(">", "\\x3e")
                    runOnUiThread {
                        val playerUrl = "file:///android_asset/media_player.html?type=text&title=$encodedTitle$mediaParams"
                        view.loadUrl(playerUrl)
                        // Inject content after page loads via a tiny delay
                        view.postDelayed({
                            view.evaluateJavascript("window._textContent='$escaped';", null)
                        }, 300)
                    }
                } catch (e: Exception) {
                    DebugLog.e("MediaPlayer", "Failed to fetch text: ${e.message}")
                    runOnUiThread {
                        // Fall back to loading the URL directly
                        val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
                        view.loadUrl("file:///android_asset/media_player.html?url=$encodedUrl&type=text&title=$encodedTitle$mediaParams")
                    }
                }
            }.start()
            return true
        }

        // Audio & Video — direct URL works via <audio>/<video> elements
        val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
        val srtParam = if (mediaType == "video") {
            val srtUrl = path.substringBeforeLast('.') + ".srt"
            "&srt=" + java.net.URLEncoder.encode(srtUrl, "UTF-8")
        } else ""
        val playerUrl = "file:///android_asset/media_player.html?url=$encodedUrl&type=$mediaType&title=$encodedTitle$srtParam$mediaParams"
        DebugLog.d("MediaPlayer", "Intercepted $mediaType ($ext): $url")
        view.loadUrl(playerUrl)
        return true
    }

    private fun createCameraIntent(): Intent? {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (cameraIntent.resolveActivity(packageManager) == null) {
            // No camera activity on device
            return null
        }
        if (cameraPermissionGranted) {
            initializeCamera()
        }
        // Create a file/URI to store the image
        val imageFile = createTempImageFile() ?: return null
        cameraImageUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", imageFile)
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
        return cameraIntent
    }

    private fun createContentSelectionIntent(acceptTypes: Array<String>?): Intent {
        val mimeTypes = acceptTypes?.filter { it.isNotEmpty() }?.toTypedArray() ?: arrayOf("*/*")
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }
    }

    // Example for creating a temp file
    private fun createTempImageFile(): File? {
        return try {
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            File.createTempFile("tmp_image_", ".jpg", storageDir)
        } catch (e: IOException) {
            DebugLog.e("FileChooser", "Cannot create temp file", e)
            null
        }
    }

    fun getWebViewVersion(): String? {
        return try {
            // Try Google’s webview package first
            val pInfo = packageManager.getPackageInfo("com.google.android.webview", 0)
            pInfo.versionName // e.g. "114.0.5735.196"
        } catch (e: PackageManager.NameNotFoundException) {
            // Fallback: older AOSP webview, or it may be missing
            try {
                val pInfo = packageManager.getPackageInfo("com.android.webview", 0)
                pInfo.versionName // e.g. "97.0.4692.87"
            } catch (e2: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun showFullScreenCustomView(
            view: View,
            callback: WebChromeClient.CustomViewCallback?
    ) {
        if (fullScreenCustomView != null) {
            callback?.onCustomViewHidden()
            return
        }

        fullScreenCustomView = view
        customViewCallback = callback
        originalSystemUiVisibility = window.decorView.systemUiVisibility
        originalOrientation = requestedOrientation

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        dualWebViewGroup.showFullScreenOverlay(view)
        cursorLeftView.visibility = View.GONE
        cursorRightView.visibility = View.GONE
    }

    @Suppress("DEPRECATION")
    internal fun hideFullScreenCustomView() {
        if (fullScreenCustomView == null) {
            return
        }

        // If the fullscreen view is the native QR scanner, clear scanner state on exit.
        if (nativeQrScannerView != null || isQrScanInProgress) {
            nativeQrScannerView?.pause()
            nativeQrScannerView = null
            pendingNativeQrStart = false
            isQrScanInProgress = false
            qrScanCallbackWebView = null
            dualWebViewGroup.setSuppressFullscreenMediaControls(false)
        }

        dualWebViewGroup.hideFullScreenOverlay()
        runCatching { dualWebViewGroup.restoreScrollBarsAfterFullscreen() }
        fullScreenCustomView = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(
                    android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars()
            )
            window.insetsController?.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                    (View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
        requestedOrientation = originalOrientation
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cursorLeftView.visibility = if (isCursorVisible) View.VISIBLE else View.GONE
        cursorRightView.visibility = if (isCursorVisible) View.VISIBLE else View.GONE

        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    private fun startNativeQrScanner(sourceWebView: WebView) {
        if (isQrScanInProgress) {
            val quotedMessage = JSONObject.quote("A scan is already in progress.")
            sourceWebView.evaluateJavascript("window.__taplinkOnNativeQrError($quotedMessage);", null)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            qrScanCallbackWebView = sourceWebView
            pendingNativeQrStart = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
            return
        }

        qrScanCallbackWebView = sourceWebView
        isQrScanInProgress = true
        pendingNativeQrStart = false

        val scannerContainer =
                FrameLayout(this).apply {
                    setBackgroundColor(Color.BLACK)
                    layoutParams =
                            FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                            )
                }

        val scannerView =
                DecoratedBarcodeView(this).apply {
                    layoutParams =
                            FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                            )
                    barcodeView.decoderFactory =
                            DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                    setStatusText("Point at a QR code")
                }

        scannerView.barcodeView.addStateListener(
                object : CameraPreview.StateListener {
                    override fun previewSized() {}

                    override fun previewStarted() {
                        applyDefaultQrZoom(scannerView)
                    }

                    override fun previewStopped() {}

                    override fun cameraError(error: Exception) {}

                    override fun cameraClosed() {}
                }
        )

        scannerContainer.addView(scannerView)
        nativeQrScannerView = scannerView

        dualWebViewGroup.setSuppressFullscreenMediaControls(true)
        showFullScreenCustomView(scannerContainer, null)

        scannerView.decodeContinuous(
                object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult?) {
                        val value = result?.text?.trim().orEmpty()
                        if (value.isEmpty() || !isQrScanInProgress) {
                            return
                        }

                        runOnUiThread {
                            if (!isQrScanInProgress) {
                                return@runOnUiThread
                            }
                            isQrScanInProgress = false
                            stopNativeQrScannerOverlay()
                            dispatchNativeQrResult(value)
                        }
                    }

                    override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {
                        // No-op.
                    }
                }
        )
        scannerView.resume()
    }

    private fun applyDefaultQrZoom(scannerView: DecoratedBarcodeView) {
        @Suppress("DEPRECATION")
        scannerView.changeCameraParameters { parameters: Camera.Parameters ->
            try {
                CameraConfigurationUtils.setZoom(parameters, defaultQrZoomRatio)
            } catch (e: Exception) {
                DebugLog.w("QRScanner", "Unable to apply default camera zoom: ${e.message}")
            }
            parameters
        }
    }

    private fun dispatchNativeQrResult(contents: String) {
        val targetWebView = qrScanCallbackWebView ?: webView
        val quotedContents = JSONObject.quote(contents)
        targetWebView.evaluateJavascript("window.__taplinkOnNativeQrResult($quotedContents);", null)
        qrScanCallbackWebView = null
    }

    private fun dispatchNativeQrError(message: String, target: WebView? = qrScanCallbackWebView) {
        val targetWebView = target ?: webView
        val quotedMessage = JSONObject.quote(message)
        targetWebView.evaluateJavascript("window.__taplinkOnNativeQrError($quotedMessage);", null)
        pendingNativeQrStart = false
        isQrScanInProgress = false
        qrScanCallbackWebView = null
    }

    private fun stopNativeQrScannerOverlay() {
        nativeQrScannerView?.pause()
        nativeQrScannerView = null
        dualWebViewGroup.setSuppressFullscreenMediaControls(false)
        if (fullScreenCustomView != null) {
            hideFullScreenCustomView()
        }
    }

    private fun stopNativeQrScannerSession() {
        if (nativeQrScannerView == null && !isQrScanInProgress && !pendingNativeQrStart) {
            return
        }
        pendingNativeQrStart = false
        isQrScanInProgress = false
        qrScanCallbackWebView = null
        stopNativeQrScannerOverlay()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                if (filePathCallback != null) {
                    var results: Array<Uri>? = null

                    // Check if response is from Camera (data is null/empty but cameraImageUri is
                    // set)
                    // or from File Picker (data has URI)
                    if (data == null || data.data == null) {
                        // If cameraImageUri is populated, use it
                        if (cameraImageUri != null) {
                            results = arrayOf(cameraImageUri!!)
                        }
                    } else {
                        // File picker result
                        data.dataString?.let { results = arrayOf(Uri.parse(it)) }
                    }

                    filePathCallback?.onReceiveValue(results)
                    filePathCallback = null
                }
            } else {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
            }
        }

        if (requestCode == CAMERA_REQUEST_CODE && resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            data?.extras?.get("data")?.let { imageBitmap ->
                // Convert bitmap to base64
                val base64Image = convertBitmapToBase64(imageBitmap as Bitmap)

                // Send the image back to Google Search
                webView.evaluateJavascript(
                        """
                (function() {
                    // Find Google's image search input
                    var input = document.querySelector('input[type="file"][name="image_url"]');
                    if (!input) {
                        input = document.createElement('input');
                        input.type = 'file';
                        input.name = 'image_url';
                        document.body.appendChild(input);
                    }

                    // Create a File object from base64
                    fetch('data:image/jpeg;base64,$base64Image')
                        .then(res => res.blob())
                        .then(blob => {
                            const file = new File([blob], "image.jpg", { type: 'image/jpeg' });

                            // Create a FileList object
                            const dataTransfer = new DataTransfer();
                            dataTransfer.items.add(file);

                            // Set the file and dispatch change event
                            input.files = dataTransfer.files;
                            input.dispatchEvent(new Event('change', { bubbles: true }));
                        });
                })();
            """,
                        null
                )
            }
        }
    }

    private fun convertBitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val imageBytes = outputStream.toByteArray()
        return Base64.encodeToString(imageBytes, Base64.DEFAULT)
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissionsToRequest = mutableListOf<String>()

            // Check both permissions
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
            }
            if (checkSelfPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
            }
            // Location: the unipanel launcher must request this itself. In
            // Activity mode visionclaw asked for it; here that Activity never
            // runs, so without this the device never gets the GPS permission
            // and the companion "Test Location" + HUD air-quality feed fail.
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                            PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                permissionsToRequest.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            }

            if (permissionsToRequest.isNotEmpty()) {
                requestPermissions(permissionsToRequest.toTypedArray(), PERMISSIONS_REQUEST_CODE)
            }
        }
    }

    private fun logPermissionState() {
        val cameraPermission =
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
        val micPermission =
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)

        DebugLog.d(
                "PermissionDebug",
                """
        Permission State:
        Camera: ${if (cameraPermission == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}
        Microphone: ${if (micPermission == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}
    """.trimIndent()
        )
    }

    private fun disableDefaultKeyboard(targetWebView: WebView) {
        try {
            val method =
                    WebView::class.java.getMethod("setShowSoftInputOnFocus", Boolean::class.java)
            method.invoke(targetWebView, false)
        } catch (e: Exception) {
            // Fallback for older Android versions
            targetWebView.evaluateJavascript(
                    """
            document.addEventListener('focus', function(e) {
                if (e.target.tagName === 'INPUT' ||
                    e.target.tagName === 'TEXTAREA' ||
                    e.target.isContentEditable) {
                    try {
                        if (window.GroqBridge && typeof window.GroqBridge.onInputFocus === 'function') {
                            window.GroqBridge.onInputFocus();
                        }
                    } catch (err) {}
                }
            }, true);
        """,
                    null
            )
        }
    }

    // ── Google Cloud TTS state ────────────────────────────────────────────
    private var cloudTtsPlayer: android.media.MediaPlayer? = null
    private var cloudTtsSentences: List<String> = emptyList()
    private var cloudTtsCurrentSentence = 0
    @Volatile private var cloudTtsPlaying = false

    // ── Browser Agent ──────────────────────────────────────────────────

    private var browserAgentSession: BrowserAgentSession? = null
    private var agentTimeoutHandler: android.os.Handler? = null
    private var agentAudioRecord: android.media.AudioRecord? = null
    @Volatile private var agentAudioStreaming = false

    override fun onAnchorTogglePressed() {
        toggleAnchor()
    }

    private fun startBrowserAgent() {
        // Resolve Gemini API key — same chain as VisionClaw
        val vcPrefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val apiKey = vcPrefs.getString("gemini_api_key", "")?.trim().orEmpty()
        if (apiKey.isBlank()) {
            dualWebViewGroup.showToast("Set Gemini API key in companion app")
            return
        }

        // Resolve Live model: user override > default
        val modelOverride = vcPrefs.getString("gemini_model_override", "")?.trim().orEmpty()
        val liveModel = if (modelOverride.isNotBlank() &&
            (modelOverride.contains("live", ignoreCase = true) ||
             modelOverride.contains("native-audio", ignoreCase = true))) {
            modelOverride
        } else {
            "gemini-3.1-flash-live-preview"  // default
        }

        dualWebViewGroup.setAgentGlowActive(true)
        dualWebViewGroup.showToast("Agent: $liveModel")

        val session = BrowserAgentSession(apiKey, liveModel, object : BrowserAgentSession.AgentListener {
            override fun onAgentReady() {
                runOnUiThread {
                    dualWebViewGroup.showToast("Agent connected!")
                    // Send initial screenshot so Gemini can see the page
                    val currentUrl = webView.url ?: "about:blank"
                    dualWebViewGroup.captureWebViewScreenshot()?.let { bmp ->
                        browserAgentSession?.sendScreenshot(bmp)
                        bmp.recycle()
                    }
                    // Send an initial text prompt so Gemini responds even if
                    // audio has issues — this guarantees at least one response.
                    browserAgentSession?.sendClientText(
                        "I just activated the browser agent. I'm currently viewing: $currentUrl. " +
                        "I can see the page in the screenshot I just sent. " +
                        "Say a short greeting and tell me you're ready for my voice command."
                    )
                    // Start streaming microphone audio
                    startAgentAudioCapture()
                }
            }

            override fun onAgentAction(action: BrowserAgentSession.AgentAction): String {
                // Execute on UI thread — no longer blocks WebSocket thread since
                // actions are dispatched from the main thread via text parsing.
                runOnUiThread {
                    try {
                        val r = executeBrowserAction(action)
                        DebugLog.d("BrowserAgent", "Action result: $r")
                        // Send a fresh screenshot after the page renders
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            dualWebViewGroup.captureWebViewScreenshot()?.let { bmp ->
                                browserAgentSession?.sendScreenshot(bmp)
                                bmp.recycle()
                            }
                        }, 500)
                    } catch (e: Exception) {
                        DebugLog.e("BrowserAgent", "Action error: ${e.message}")
                    }
                }
                return "OK"
            }

            override fun onAgentSpeech(text: String) {
                DebugLog.d("BrowserAgent", "Speech received: ${text.take(80)}")
                runOnUiThread {
                    dualWebViewGroup.showToast(text.take(120))
                }
            }

            override fun onAgentAudio(mimeType: String, data: ByteArray) {
                DebugLog.d("BrowserAgent", "Audio received: mime=$mimeType size=${data.size}")
                // Play audio through the device speaker
                playAgentAudio(mimeType, data)
            }

            override fun onAgentFinished(reason: String) {
                DebugLog.d("BrowserAgent", "Finished: $reason")
                runOnUiThread {
                    stopBrowserAgent(reason)
                }
            }

            override fun onAgentError(message: String) {
                DebugLog.e("BrowserAgent", "Error: $message")
                runOnUiThread {
                    dualWebViewGroup.showToast("Agent error: $message")
                    stopBrowserAgent(message)
                }
            }

            override fun onAgentStatus(status: String) {
                DebugLog.d("BrowserAgent", "Status: $status")
                runOnUiThread {
                    dualWebViewGroup.showToast("Agent: $status")
                }
            }

            override fun onAgentDiagnostic(log: String) {
                // Write diagnostic log to a file the user can read
                try {
                    val dir = getExternalFilesDir(null)
                        ?: android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS)
                    val file = java.io.File(dir, "browser_agent_diag.txt")
                    file.writeText(log)
                    DebugLog.d("BrowserAgent", "Diagnostic log written to: ${file.absolutePath}")
                    runOnUiThread {
                        dualWebViewGroup.showToast("Log: ${file.absolutePath}")
                    }
                } catch (e: Exception) {
                    DebugLog.e("BrowserAgent", "Failed to write diag log: ${e.message}")
                }
            }
        })

        browserAgentSession = session
        session.start()

        // Start timeout checker
        agentTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = object : Runnable {
            override fun run() {
                val s = browserAgentSession ?: return
                if (s.isActive) {
                    if (!s.checkTimeouts()) {
                        agentTimeoutHandler?.postDelayed(this, 5000)
                    }
                }
            }
        }
        agentTimeoutHandler?.postDelayed(timeoutRunnable, 5000)
    }

    private fun stopBrowserAgent(reason: String) {
        agentAudioStreaming = false
        agentAudioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        agentAudioRecord = null

        browserAgentSession?.stop()
        browserAgentSession = null
        agentTimeoutHandler?.removeCallbacksAndMessages(null)
        agentTimeoutHandler = null

        dualWebViewGroup.setAgentGlowActive(false)
        dualWebViewGroup.showToast("Agent: $reason")
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startAgentAudioCapture() {
        if (agentAudioStreaming) return
        agentAudioStreaming = true
        val sampleRate = 16000
        val bufferSize = android.media.AudioRecord.getMinBufferSize(
            sampleRate,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        DebugLog.d("BrowserAgent", "AudioRecord bufferSize=$bufferSize")
        if (bufferSize <= 0) {
            DebugLog.e("BrowserAgent", "AudioRecord.getMinBufferSize returned $bufferSize — mic unavailable?")
            runOnUiThread { dualWebViewGroup.showToast("Mic unavailable") }
            agentAudioStreaming = false
            return
        }
        val recorder = try {
            android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
        } catch (e: SecurityException) {
            DebugLog.e("BrowserAgent", "RECORD_AUDIO permission denied", e)
            runOnUiThread { dualWebViewGroup.showToast("Mic permission denied") }
            agentAudioStreaming = false
            return
        }
        if (recorder.state != android.media.AudioRecord.STATE_INITIALIZED) {
            DebugLog.e("BrowserAgent", "AudioRecord failed to initialize, state=${recorder.state}")
            runOnUiThread { dualWebViewGroup.showToast("Mic init failed") }
            recorder.release()
            agentAudioStreaming = false
            return
        }
        agentAudioRecord = recorder
        recorder.startRecording()
        DebugLog.d("BrowserAgent", "Mic capture started at ${sampleRate}Hz")

        Thread {
            var chunkCount = 0
            val buffer = ByteArray(bufferSize)
            while (agentAudioStreaming && browserAgentSession?.isActive == true) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    browserAgentSession?.sendAudio(buffer.copyOf(read))
                    chunkCount++
                    if (chunkCount % 50 == 0) {
                        DebugLog.d("BrowserAgent", "Audio chunks sent: $chunkCount")
                    }
                }
            }
            DebugLog.d("BrowserAgent", "Mic capture stopped after $chunkCount chunks")
        }.start()
    }

    /** Play Gemini's audio response (PCM or opus). */
    private var agentAudioTrack: android.media.AudioTrack? = null

    private fun playAgentAudio(mimeType: String, data: ByteArray) {
        if (mimeType.contains("pcm", ignoreCase = true)) {
            // Raw PCM — play directly via AudioTrack
            if (agentAudioTrack == null) {
                val sampleRate = if (mimeType.contains("24000")) 24000 else 16000
                agentAudioTrack = android.media.AudioTrack.Builder()
                    .setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setAudioFormat(android.media.AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(data.size * 4)
                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                    .build()
                agentAudioTrack?.play()
            }
            agentAudioTrack?.write(data, 0, data.size)
        }
    }

    /**
     * Execute a browser action requested by the Gemini agent.
     * Called on the UI thread.
     */
    private fun executeBrowserAction(action: BrowserAgentSession.AgentAction): String {
        val args = action.args
        return when (action.tool) {
            "navigate_url" -> {
                val url = args.optString("url", "")
                if (url.isBlank()) return "Error: no URL provided"
                val formatted = formatUrl(url)
                webView.loadUrl(formatted)
                "Navigating to $formatted"
            }
            "click_element" -> {
                val selector = args.optString("selector", "")
                val x = args.optString("x", "").toDoubleOrNull() ?: args.optDouble("x", Double.NaN)
                val y = args.optString("y", "").toDoubleOrNull() ?: args.optDouble("y", Double.NaN)
                if (selector.isNotBlank()) {
                    // Click via JavaScript using CSS selector
                    webView.evaluateJavascript("""
                        (function() {
                            var el = document.querySelector('$selector');
                            if (!el) return 'Element not found: $selector';
                            el.scrollIntoView({block:'center'});
                            el.click();
                            return 'Clicked ' + el.tagName + (el.textContent || '').substring(0, 50);
                        })();
                    """.trimIndent(), null)
                    "Clicked element: $selector"
                } else if (!x.isNaN() && !y.isNaN()) {
                    // Click via simulated touch event at coordinates
                    webView.evaluateJavascript("""
                        (function() {
                            var el = document.elementFromPoint($x, $y);
                            if (!el) return 'No element at ($x, $y)';
                            el.click();
                            return 'Clicked ' + el.tagName + ' at ($x,$y): ' + (el.textContent || '').substring(0, 50);
                        })();
                    """.trimIndent(), null)
                    "Clicked at ($x, $y)"
                } else {
                    "Error: provide either selector or x,y coordinates"
                }
            }
            "type_text" -> {
                val text = args.optString("text", "")
                val selector = args.optString("selector", "")
                val clear = args.optString("clear", "").equals("true", ignoreCase = true) || args.optBoolean("clear", false)
                val submit = args.optString("submit", "").equals("true", ignoreCase = true) || args.optBoolean("submit", false)
                val escapedText = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
                val selectorJs = if (selector.isNotBlank()) {
                    "var el = document.querySelector('$selector'); if(el) { el.focus(); el.scrollIntoView({block:'center'}); }"
                } else {
                    "var el = document.activeElement;"
                }
                val clearJs = if (clear) "if(el) { el.value = ''; }" else ""
                val submitJs = if (submit) {
                    """
                    if(el && el.form) { el.form.submit(); }
                    else if(el) {
                        var ev = new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true});
                        el.dispatchEvent(ev);
                    }
                    """
                } else ""
                webView.evaluateJavascript("""
                    (function() {
                        $selectorJs
                        $clearJs
                        if(el) {
                            // Use input event for React/modern frameworks
                            var nativeInputValueSetter = Object.getOwnPropertyDescriptor(
                                window.HTMLInputElement.prototype, 'value')?.set
                                || Object.getOwnPropertyDescriptor(
                                window.HTMLTextAreaElement.prototype, 'value')?.set;
                            if(nativeInputValueSetter) {
                                nativeInputValueSetter.call(el, (el.value || '') + '$escapedText');
                            } else {
                                el.value = (el.value || '') + '$escapedText';
                            }
                            el.dispatchEvent(new Event('input', {bubbles:true}));
                            el.dispatchEvent(new Event('change', {bubbles:true}));
                            $submitJs
                            return 'Typed into ' + el.tagName;
                        }
                        return 'No element focused';
                    })();
                """.trimIndent(), null)
                "Typed '${text.take(30)}'" + if (submit) " and submitted" else ""
            }
            "scroll_page" -> {
                val direction = args.optString("direction", "down")
                val amount = args.optString("amount", "").toDoubleOrNull() ?: args.optDouble("amount", 0.5)
                val pixels = (webView.height * amount).toInt()
                val scrollY = if (direction == "up") -pixels else pixels
                webView.evaluateJavascript(
                    "window.scrollBy(0, $scrollY);", null
                )
                "Scrolled $direction by ${(amount * 100).toInt()}%"
            }
            "read_page_text" -> {
                // NOTE: We can't use future.get() here because executeBrowserAction
                // runs on the UI thread and evaluateJavascript's callback also runs
                // on the UI thread — that would be a deadlock. Instead, return a
                // synchronous snapshot using the page info we already have access to.
                val title = webView.title ?: ""
                val url = webView.url ?: ""
                // Kick off an async JS extraction for body text and return what we
                // can synchronously. The JS callback will be lost (no way to wait)
                // but the title + URL are the most useful parts for navigation.
                var bodyText = ""
                webView.evaluateJavascript("""
                    (function() {
                        var text = (document.body && document.body.innerText) || '';
                        if (text.length > 4000) text = text.substring(0, 4000) + '... [truncated]';
                        return text;
                    })();
                """.trimIndent(), null)
                // Return what we have synchronously — title and URL are most important
                // for the agent to decide next actions. We include a note that body
                // text may arrive in the next screenshot context.
                "{\"title\": \"${title.replace("\"", "\\\"")}\", \"url\": \"${url.replace("\"", "\\\"")}\", \"text\": \"Page text is visible in the screenshot. Title and URL provided for navigation context.\"}"
            }
            "go_back" -> {
                if (webView.canGoBack()) {
                    webView.goBack()
                    uiHandler.postDelayed({ syncNativeRadioToolbarState(scheduleDelayedBroadcasts = false) }, 150L)
                    uiHandler.postDelayed({ syncActiveBrowserChrome(webView, includeDelayedPasses = false) }, 200L)
                    uiHandler.postDelayed({ syncTapRadioPlaybackUi() }, 350L)
                    uiHandler.postDelayed({ syncTapRadioPlaybackUi() }, 1200L)
                    "Navigated back"
                } else {
                    "No previous page in history"
                }
            }
            "go_forward" -> {
                if (webView.canGoForward()) {
                    webView.goForward()
                    uiHandler.postDelayed({ syncNativeRadioToolbarState(scheduleDelayedBroadcasts = false) }, 150L)
                    uiHandler.postDelayed({ syncActiveBrowserChrome(webView, includeDelayedPasses = false) }, 200L)
                    uiHandler.postDelayed({ syncTapRadioPlaybackUi() }, 350L)
                    uiHandler.postDelayed({ syncTapRadioPlaybackUi() }, 1200L)
                    "Navigated forward"
                } else {
                    "No forward page in history"
                }
            }
            else -> "Unknown action: ${action.tool}"
        }
    }

    // ── Legacy onTtsTogglePressed (kept for reference) ───────────────

    private fun legacyOnTtsTogglePressed() {
        // Toggle: if already speaking, stop
        if (cloudTtsPlaying) {
            stopCloudTts()
            dualWebViewGroup.showToast("TTS stopped")
            return
        }

        // Check API key
        val vcPrefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val apiKey = vcPrefs.getString("cloud_tts_api_key", "") ?: ""
        if (apiKey.isBlank()) {
            dualWebViewGroup.showToast("Set Cloud TTS API key in companion app")
            return
        }

        val currentUrl = webView.url ?: ""

        // ── Google Drive: download the raw file via export URL ──
        val driveFileIdRegex = Regex("""drive\.google\.com/file/d/([^/]+)""")
        val driveMatch = driveFileIdRegex.find(currentUrl)
        if (driveMatch != null) {
            val fileId = driveMatch.groupValues[1]
            DebugLog.d("TTS", "Google Drive file detected: id=$fileId")
            val cookies = android.webkit.CookieManager.getInstance().getCookie("https://drive.google.com") ?: ""
            dualWebViewGroup.showToast("Reading document...")
            Thread {
                try {
                    val exportUrl = "https://drive.google.com/uc?export=download&id=$fileId"
                    val conn = java.net.URL(exportUrl).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("Cookie", cookies)
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    conn.disconnect()
                    if (text.isNotBlank() && text.length >= 10) {
                        runOnUiThread { startCloudTts(text.trim()) }
                        return@Thread
                    }
                } catch (e: Exception) {
                    DebugLog.e("TTS", "Google Drive download failed", e)
                }
                runOnUiThread { extractTextForCloudTts() }
            }.start()
            return
        }

        // ── Normal path: extract text via JS ──
        extractTextForCloudTts()
    }

    /** Reference to the AndroidInterface attached to webView, for setting pending text. */
    private var ttsAndroidInterface: AndroidInterface? = null

    /**
     * Extract text from the current page via JavaScript, then start Cloud TTS.
     */
    private fun extractTextForCloudTts() {
        webView.evaluateJavascript(
                """
            (function() {
                function cleanText(s) {
                    return (s || '').replace(/ /g, ' ').replace(/\s{3,}/g, '  ').trim();
                }
                function candidateText(el) {
                    if (!el) return '';
                    return cleanText(el.innerText || el.textContent || '');
                }
                function chooseLongest(list) {
                    var best = '';
                    for (var i = 0; i < list.length; i++) {
                        var t = cleanText(list[i]);
                        if (t.length > best.length) best = t;
                    }
                    return best;
                }
                function grabText(doc) {
                    // Prefer visible modal/preview/document surfaces first.
                    var prioritySelectors = [
                        '[role="dialog"] [role="document"]',
                        '[role="dialog"] [data-target="doc"]',
                        '[role="dialog"] pre',
                        '[role="dialog"] article',
                        '[role="dialog"] .doc-container',
                        '[role="dialog"] [contenteditable="true"]',
                        '.modal-dialog pre',
                        '.modal-dialog article',
                        '.modal-dialog .doc-container',
                        '.ReactModal__Content pre',
                        '.ReactModal__Content article',
                        '.drive-viewer-text-layer',
                        '.ndfHFb-c4YZDc-Wrql6b',
                        '.ndfHFb-c4YZDc-aTv5jf',
                        'pre',
                        'article',
                        '.doc-container',
                        '[contenteditable="true"]',
                        '[role="main"]',
                        'main'
                    ];
                    for (var s = 0; s < prioritySelectors.length; s++) {
                        var nodes = doc.querySelectorAll(prioritySelectors[s]);
                        if (nodes && nodes.length) {
                            var texts = [];
                            for (var n = 0; n < nodes.length; n++) {
                                var cs = doc.defaultView && doc.defaultView.getComputedStyle ? doc.defaultView.getComputedStyle(nodes[n]) : null;
                                if (cs && (cs.display === 'none' || cs.visibility === 'hidden')) continue;
                                texts.push(candidateText(nodes[n]));
                            }
                            var picked = chooseLongest(texts);
                            if (picked.length >= 40) return picked;
                        }
                    }
                    return candidateText(doc.body);
                }
                var text = '';
                try { text = grabText(document); } catch(e) {}
                if ((!text || text.trim().length < 40) && document.querySelectorAll('iframe').length > 0) {
                    var frames = document.querySelectorAll('iframe');
                    for (var i = 0; i < frames.length; i++) {
                        try {
                            var fdoc = frames[i].contentDocument || frames[i].contentWindow.document;
                            if (fdoc && fdoc.body) {
                                var ft = grabText(fdoc);
                                if (ft && ft.trim().length > text.trim().length) text = ft;
                            }
                        } catch(e) {}
                    }
                }
                return (text || '').trim();
            })();
            """) { result ->
            val cleaned = result
                ?.removeSurrounding("\"")
                ?.replace("\\n", "\n")
                ?.replace("\\t", "\t")
                ?.replace("\\\"", "\"")
                ?.replace("\\'", "'")
                ?.replace("\\\\", "\\")
                ?.trim()
                ?: ""
            if (cleaned.length < 10 || cleaned == "null") {
                dualWebViewGroup.showToast("No readable text found on this page")
            } else {
                DebugLog.d("TTS", "Extracted ${cleaned.length} chars for Cloud TTS")
                startCloudTts(cleaned)
            }
        }
    }

    /**
     * Split text into chunks (~4000 chars max, breaking at sentence boundaries)
     * and start synthesizing + playing via Google Cloud TTS API.
     * Audio is played natively via MediaPlayer — no WebView audio needed.
     */
    private fun startCloudTts(text: String) {
        val vcPrefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val apiKey = vcPrefs.getString("cloud_tts_api_key", "")?.trim().orEmpty()
        val configuredVoiceName = vcPrefs.getString("cloud_tts_voice_name", "")?.trim().orEmpty()
        val voiceName = configuredVoiceName.ifBlank { "en-US-Standard-A" }
        val configuredLanguage = vcPrefs.getString("cloud_tts_language", "")?.trim().orEmpty()
        val language = configuredLanguage.ifBlank {
            Regex("""^[a-z]{2,3}-[A-Z]{2}""")
                .find(voiceName)
                ?.value
                ?: "en-US"
        }

        if (apiKey.isBlank()) {
            dualWebViewGroup.showToast("Set Cloud TTS API key in companion app")
            return
        }

        // Split text into chunks at sentence boundaries (~4000 chars each, Cloud TTS limit is 5000)
        cloudTtsSentences = splitTextIntoChunks(text, 4000)
        cloudTtsCurrentSentence = 0
        cloudTtsPlaying = true

        DebugLog.d("TTS", "Starting Cloud TTS: ${text.length} chars, ${cloudTtsSentences.size} chunks, voice=$voiceName")
        dualWebViewGroup.showToast("Speaking...")

        // Start synthesizing and playing the first chunk
        synthesizeAndPlayChunk(0, apiKey, voiceName, language)
    }

    /**
     * Split text into chunks of maxLen chars, breaking at sentence boundaries.
     */
    private fun splitTextIntoChunks(text: String, maxLen: Int): List<String> {
        val chunks = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxLen) {
                chunks.add(remaining)
                break
            }
            var splitAt = -1
            for (i in maxLen downTo maxLen / 2) {
                val ch = remaining[i]
                if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                    splitAt = i + 1
                    break
                }
            }
            if (splitAt == -1) splitAt = maxLen
            chunks.add(remaining.substring(0, splitAt))
            remaining = remaining.substring(splitAt).trimStart()
        }
        return chunks
    }

    /**
     * Call Google Cloud TTS REST API to synthesize one chunk, then play the
     * resulting MP3 via MediaPlayer. On completion, auto-advances to next chunk.
     */
    private fun synthesizeAndPlayChunk(
        chunkIndex: Int,
        apiKey: String,
        voiceName: String,
        language: String
    ) {
        if (chunkIndex >= cloudTtsSentences.size || !cloudTtsPlaying) {
            cloudTtsPlaying = false
            return
        }
        cloudTtsCurrentSentence = chunkIndex
        val chunkText = cloudTtsSentences[chunkIndex]

        Thread {
            try {
                val url = java.net.URL("https://texttospeech.googleapis.com/v1/text:synthesize?key=$apiKey")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000

                // Build voice name: if user specified one use it, otherwise construct default
                val effectiveVoiceName = if (voiceName.isNotBlank()) voiceName
                    else "${language}-Standard-A"

                DebugLog.d("TTS", "Chunk $chunkIndex: voice=$effectiveVoiceName lang=$language text=${chunkText.length} chars")

                val json = org.json.JSONObject().apply {
                    put("input", org.json.JSONObject().put("text", chunkText))
                    put("voice", org.json.JSONObject().apply {
                        put("languageCode", language)
                        put("name", effectiveVoiceName)
                    })
                    put("audioConfig", org.json.JSONObject().apply {
                        put("audioEncoding", "MP3")
                    })
                }

                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    DebugLog.e("TTS", "Cloud TTS API error $responseCode: $errorBody")
                    val msg = when (responseCode) {
                        400 -> {
                            // Parse the error detail from the response
                            val detail = try {
                                val errJson = org.json.JSONObject(errorBody)
                                errJson.optJSONObject("error")?.optString("message", "") ?: ""
                            } catch (_: Exception) { "" }
                            if (detail.isNotBlank()) "TTS 400: ${detail.take(80)}"
                            else "TTS bad request — check voice/language settings"
                        }
                        403 -> "TTS API not enabled — enable it in Google Cloud Console"
                        401 -> "Invalid API key — check companion app settings"
                        429 -> "TTS rate limit — try again shortly"
                        else -> "TTS API error: $responseCode"
                    }
                    runOnUiThread { dualWebViewGroup.showToast(msg) }
                    cloudTtsPlaying = false
                    conn.disconnect()
                    return@Thread
                }

                val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val responseJson = org.json.JSONObject(responseBody)
                val audioBase64 = responseJson.getString("audioContent")
                val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)

                // Write to temp file and play via MediaPlayer
                val tempFile = java.io.File(cacheDir, "tts_chunk_$chunkIndex.mp3")
                tempFile.writeBytes(audioBytes)

                runOnUiThread {
                    playTtsAudioFile(tempFile, chunkIndex, apiKey, voiceName, language)
                }

            } catch (e: Exception) {
                DebugLog.e("TTS", "Cloud TTS synthesis failed for chunk $chunkIndex", e)
                runOnUiThread {
                    dualWebViewGroup.showToast("TTS error: ${e.message?.take(50)}")
                }
                cloudTtsPlaying = false
            }
        }.start()
    }

    /**
     * Play an MP3 file via MediaPlayer, then auto-advance to next chunk on completion.
     */
    private fun playTtsAudioFile(
        file: java.io.File,
        chunkIndex: Int,
        apiKey: String,
        voiceName: String,
        language: String
    ) {
        cloudTtsPlayer?.release()
        cloudTtsPlayer = android.media.MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                file.delete()
                if (cloudTtsPlaying) {
                    synthesizeAndPlayChunk(chunkIndex + 1, apiKey, voiceName, language)
                }
            }
            setOnErrorListener { _, what, extra ->
                DebugLog.e("TTS", "MediaPlayer error: what=$what extra=$extra")
                file.delete()
                cloudTtsPlaying = false
                true
            }
            prepare()
            start()
        }
    }

    /**
     * Stop Cloud TTS playback.
     */
    private fun stopCloudTts() {
        cloudTtsPlaying = false
        cloudTtsPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {}
            it.release()
        }
        cloudTtsPlayer = null
    }

    // ── Legacy media_player.html text reader (kept for MediaInterface/DualWebViewGroup) ──

    fun openTextReaderDirect(title: String) {
        val vcPrefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val voiceName = vcPrefs.getString("media_tts_voice", "") ?: ""
        val underline = vcPrefs.getBoolean("media_tts_underline", true)
        val encodedTitle = java.net.URLEncoder.encode(title.take(200), "UTF-8")
        val mediaParams = buildString {
            if (voiceName.isNotBlank()) append("&voice=${java.net.URLEncoder.encode(voiceName, "UTF-8")}")
            if (!underline) append("&underline=false")
        }
        val playerUrl = "file:///android_asset/media_player.html?type=text&title=$encodedTitle$mediaParams"
        webView.loadUrl(playerUrl)
    }

    // Add this method to handle permission results
    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            CAMERA_PERMISSION_CODE -> {
                cameraPermissionGranted =
                        grantResults.isNotEmpty() &&
                                grantResults[0] == PackageManager.PERMISSION_GRANTED

                if (!cameraPermissionGranted) {
                    DebugLog.e("Camera", "Camera permission denied")
                    // Inform user that camera features won't work
                    webView.evaluateJavascript(
                            "alert('Camera permission is required for image search');",
                            null
                    )
                    if (pendingNativeQrStart) {
                        dispatchNativeQrError("Camera permission denied.")
                    }
                } else if (pendingNativeQrStart) {
                    val targetWebView = qrScanCallbackWebView ?: webView
                    startNativeQrScanner(targetWebView)
                }
            }
            MEDIA_PERMISSIONS_REQUEST_CODE -> {
                val allGranted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                DebugLog.d("MediaPerm",
                    "MEDIA_PERMISSIONS_REQUEST_CODE result: allGranted=$allGranted, " +
                        "perms=${permissions.joinToString()}")
                // The photos gallery polls hasMediaPermission on load, so
                // a reload is the cleanest way to refresh its state. If
                // the user is currently on photos_gallery.html, this will
                // re-render the grid with DCIM entries included; if they
                // navigated away, the bridge state simply reflects the
                // new grant for next time. We only reload if we're on a
                // gallery URL — reloading the dashboard mid-conversation
                // would be a regression.
                try {
                    val current = webView.url.orEmpty()
                    if (current.contains("photos_gallery.html", ignoreCase = true)) {
                        webView.post { webView.reload() }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun loadInitialPage() {
        DebugLog.d("WebViewDebug", "loadInitialPage called")
        // Load Google directly without the intermediate blank page
        webView.loadUrl(Constants.DEFAULT_URL)
    }

    fun showCustomKeyboard() {
        DebugLog.d("KeyboardDebug", "1. Starting showCustomKeyboard")

        if (isKeyboardVisible &&
                        keyboardView?.visibility == View.VISIBLE &&
                        dualWebViewGroup.keyboardContainer.visibility == View.VISIBLE
        ) {
            DebugLog.d("KeyboardDebug", "Keyboard already visible; ignoring show request")
            return
        }

        DebugLog.d("KeyboardDebug", "2. Preserving active WebView DOM focus")

        if (wasKeyboardDismissedByEnter) {
            wasKeyboardDismissedByEnter = false
            if (!dualWebViewGroup.isUrlEditing()) {
                return
            }
        }

        // Ensure keyboard view exists and is properly configured
        if (keyboardView == null) {
            DebugLog.d("KeyboardDebug", "3. Creating new keyboard view")
            keyboardView =
                    CustomKeyboardView(this).apply {
                        layoutParams =
                                FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.WRAP_CONTENT,
                                        Gravity.BOTTOM
                                )
                        setOnKeyboardActionListener(this@MainActivity)
                        DebugLog.d("KeyboardDebug", "Keyboard created with visibility: $visibility")
                    }
        }

        // Hide info bars when keyboard shows
        dualWebViewGroup.hideInfoBars()

        keyboardView?.let { keyboard ->
            // Log state before setting keyboard
            DebugLog.d(
                    "KeyboardDebug",
                    """
            Before setKeyboard:
            Keyboard visibility: ${keyboard.visibility}
            Container visibility: ${dualWebViewGroup.keyboardContainer.visibility}
            isKeyboardVisible: $isKeyboardVisible
        """.trimIndent()
            )

            // Force visibility BEFORE setting keyboard
            keyboard.visibility = View.VISIBLE
            dualWebViewGroup.keyboardContainer.visibility = View.VISIBLE

            dualWebViewGroup.setKeyboard(keyboard)

            // Log state after setting keyboard
            DebugLog.d(
                    "KeyboardDebug",
                    """
            After setKeyboard:
            Keyboard visibility: ${keyboard.visibility}
            Container visibility: ${dualWebViewGroup.keyboardContainer.visibility}
            isKeyboardVisible: $isKeyboardVisible
        """.trimIndent()
            )
            isKeyboardVisible = true

            // Ensure keyboard is on top of dialogs
            dualWebViewGroup.keyboardContainer.elevation = 3000f
            dualWebViewGroup.keyboardContainer.bringToFront()
        }

        isKeyboardVisible = true

        dualWebViewGroup.post {
            dualWebViewGroup.updateScrollBarsVisibility()
            dualWebViewGroup.requestLayout()
            dualWebViewGroup.invalidate()
            refreshCursor()
            syncKeyboardAwarePageState()
        }
    }

    private fun toggleAnchor() {

        isAnchored = !isAnchored

        // Save anchored mode state
        getSharedPreferences(prefsName, MODE_PRIVATE)
                .edit()
                .putBoolean(Constants.KEY_IS_ANCHORED, isAnchored)
                .apply()

        hideCustomKeyboard()
        DebugLog.d(
                "AnchorDebug",
                """
        Anchor toggled:
        isAnchored: $isAnchored
        isKeyboardVisible: $isKeyboardVisible
        keyboardView null?: ${keyboardView == null}
    """.trimIndent()
        )
        if (isAnchored) {
            // Move cursor to center of left screen
            centerCursor()

            // Initialize sensor handling with reset velocity tracking
            smoothedDeltaX = 0f
            smoothedDeltaY = 0f
            smoothedRollDeg = 0f
            lastFrameTime = 0L

            sensorEventListener = createSensorEventListener()
            rotationSensor?.let { sensor ->
                // Use UI rate for good responsiveness with power savings (smoothing handles jitter)
                sensorManager.registerListener(
                        sensorEventListener,
                        sensor,
                        SensorManager.SENSOR_DELAY_UI
                )
            }
            dualWebViewGroup.startAnchoring()
        } else {
            // CRITICAL: Set isAnchored to false FIRST to stop any pending sensor callbacks
            // (The sensor listener checks this flag before applying updates)

            // Stop sensor updates immediately
            sensorManager.unregisterListener(sensorEventListener)

            // Wait a tiny bit for any in-flight Choreographer callbacks to complete
            // before resetting positions
            Handler(Looper.getMainLooper())
                    .postDelayed(
                            {
                                // Now reset view positions after sensor updates have stopped
                                dualWebViewGroup.stopAnchoring()

                                // Restore cursor position
                                refreshCursor()
                            },
                            50
                    ) // Small delay to ensure pending frames are processed
        }
    }

    private fun scheduleCursorUpdate() {
        if (!pendingCursorUpdate) {
            pendingCursorUpdate = true
            uiHandler.postDelayed(
                    {
                        pendingCursorUpdate = false
                        refreshCursor()
                    },
                    8
            )
        }
    }

    /**
     * Updates the smoothing factors based on user preference slider (0-100) 0 = Fastest/least
     * smooth (high factor values = more responsive) 100 = Slowest/most smooth (low factor values =
     * very smooth) 80 = Default balanced setting
     */
    private fun updateSmoothnessFactors(level: Int) {
        smoothnessLevel = level.coerceIn(0, 100)

        // Map 0-100 to smoothing factors with INVERTED non-linear scaling
        // Higher slider values = LOWER factors = MORE smoothing
        // Lower slider values = HIGHER factors = LESS smoothing (more responsive)

        // Quaternion SLERP: 0.40 (fast/left) to 0.02 (very smooth/right)
        // Inverting: 100 - level gives us the inverse
        // Range expanded to compensate for SENSOR_DELAY_UI timing
        val invertedLevel = 100 - smoothnessLevel
        anchorSmoothingFactor = 0.02f + (invertedLevel / 100f) * 0.38f

        // Velocity smoothing: 0.55 (fast/left) to 0.05 (very smooth/right)
        velocitySmoothing = 0.05f + (invertedLevel / 100f) * 0.50f

        DebugLog.d(
                "SmoothnessDebug",
                """
            Smoothness updated:
            Level: $smoothnessLevel (0=fast, 100=smooth)
            Inverted: $invertedLevel
            Quaternion SLERP: $anchorSmoothingFactor
            Velocity Damping: $velocitySmoothing
        """.trimIndent()
        )
    }

    /** Public function called from DualWebViewGroup when user adjusts smoothness slider */
    fun updateAnchorSmoothness(level: Int) {
        updateSmoothnessFactors(level)
        // Preference is already saved by DualWebViewGroup, just update the factors
    }

    private fun createSensorEventListener(): SensorEventListener {
        return object : SensorEventListener {
            var initialQuaternion: FloatArray? = null
            var smoothedQuaternion: FloatArray? = null

            override fun onSensorChanged(event: SensorEvent) {
                if (!isAnchored || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

                // Frame rate limiting to prevent excessive updates
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - lastFrameTime < MIN_FRAME_INTERVAL_MS) return
                lastFrameTime = currentTime

                val qx = event.values[0]
                val qy = event.values[1]
                val qz = event.values[2]
                val qw = event.values[3]
                val currentQuaternion = floatArrayOf(qw, qx, qy, qz)

                // Initialize smoothed quaternion if needed
                if (smoothedQuaternion == null) {
                    smoothedQuaternion = currentQuaternion.clone()
                } else {
                    // Apply smoothing (SLERP) using dynamic factor
                    smoothedQuaternion =
                            quaternionSlerp(
                                    smoothedQuaternion!!,
                                    currentQuaternion,
                                    anchorSmoothingFactor
                            )
                }

                // Use the smoothed quaternion for calculations
                val activeQuaternion = smoothedQuaternion!!

                // Reset initial quaternion if requested
                if (shouldResetInitialQuaternion || initialQuaternion == null) {
                    initialQuaternion = activeQuaternion.clone()
                    shouldResetInitialQuaternion = false
                    // Reset velocity smoothing
                    smoothedDeltaX = 0f
                    smoothedDeltaY = 0f
                    smoothedRollDeg = 0f
                    return
                }

                val initialQuaternionInv = quaternionInverse(initialQuaternion!!)
                val relativeQuaternion = quaternionMultiply(initialQuaternionInv, activeQuaternion)

                val euler = quaternionToEuler(relativeQuaternion) // [roll, pitch, yaw]
                val rollRad = euler[2] // or [2], etc., depends on your system
                val rollDeg = Math.toDegrees(rollRad.toDouble()).toFloat()

                val deltaX = relativeQuaternion[1] * TRANSLATION_SCALE
                val deltaY = relativeQuaternion[2] * TRANSLATION_SCALE

                // Apply velocity smoothing (double exponential smoothing) using dynamic factor
                smoothedDeltaX =
                        smoothedDeltaX * (1f - velocitySmoothing) + deltaX * velocitySmoothing
                smoothedDeltaY =
                        smoothedDeltaY * (1f - velocitySmoothing) + deltaY * velocitySmoothing
                smoothedRollDeg =
                        smoothedRollDeg * (1f - velocitySmoothing) + rollDeg * velocitySmoothing

                // Use Choreographer to sync with display vsync for buttery smooth updates
                Choreographer.getInstance().postFrameCallback {
                    // Double-check isAnchored before applying update (prevents race conditions)
                    if (isAnchored) {
                        dualWebViewGroup.updateLeftEyePosition(
                                smoothedDeltaX,
                                smoothedDeltaY,
                                smoothedRollDeg
                        )
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    fun quaternionToEuler(q: FloatArray): FloatArray {
        val w = q[0]
        val x = q[1]
        val y = q[2]
        val z = q[3]

        // roll (x-axis rotation)
        val sinrCosp = 2f * (w * x + y * z)
        val cosrCosp = 1f - 2f * (x * x + y * y)
        val roll = atan2(sinrCosp, cosrCosp)

        // pitch (y-axis rotation)
        val sinp = 2f * (w * y - z * x)
        val pitch =
                if (abs(sinp) >= 1f) {
                    // Use 90 degrees if out of range
                    PI.toFloat() / 2f * if (sinp > 0f) 1f else -1f
                } else {
                    asin(sinp)
                }

        // yaw (z-axis rotation)
        val sinyCosp = 2f * (w * z + x * y)
        val cosyCosp = 1f - 2f * (y * y + z * z)
        val yaw = atan2(sinyCosp, cosyCosp)

        return floatArrayOf(roll, pitch, yaw)
    }

    /**
     * Toggles the cursor visibility.
     *
     * The optional [forceHide] and [forceShow] parameters make sure that callers can explicitly
     * request a desired state instead of relying on the current value. All state changes happen
     * within a synchronized block to avoid race conditions when rapid tap gestures or delayed
     * callbacks attempt to toggle the cursor simultaneously.
     */
    private fun toggleCursorVisibility(forceHide: Boolean = false, forceShow: Boolean = false) {
        DebugLog.d(
                "DoubleTapDebug",
                """
            Toggle Cursor Visibility:
            Force Hide: $forceHide
            Force Show: $forceShow
            Previous Visible: $isCursorVisible
            isSimulating: $isSimulatingTouchEvent
            cursorJustAppeared: $cursorJustAppeared
            isToggling: $isToggling
        """.trimIndent()
        )

        synchronized(cursorToggleLock) {
            if (isToggling) return
            isToggling = true

            try {
                val previouslyVisible = isCursorVisible
                val targetVisibility =
                        when {
                            forceHide -> false
                            forceShow -> true
                            else -> !previouslyVisible
                        }

                // Early exit if already in the desired state (but still reset isToggling in
                // finally)
                if (targetVisibility == previouslyVisible) {
                    return
                }

                isCursorVisible = targetVisibility

                // Synchronise scroll mode with the cursor visibility state.
                dualWebViewGroup.setScrollMode(!isCursorVisible)

                if (isCursorVisible) {
                    cancelActiveTouchScrollGesture()

                    if (!isAnchored) {
                        lastCursorX = lastKnownCursorX
                        lastCursorY = lastKnownCursorY
                    } else {
                        lastCursorX = 320f
                        lastCursorY = 240f
                    }

                    cursorJustAppeared = true
                    // Block interactions briefly to prevent stale taps from firing as the cursor
                    // reappears.
                    // Note: removed isSimulatingTouchEvent = true as it causes OnTouchListener to
                    // ALLOW events through
                    Handler(Looper.getMainLooper()).postDelayed({ cursorJustAppeared = false }, 300)
                } else {
                    lastKnownCursorX = lastCursorX
                    lastKnownCursorY = lastCursorY

                    val webViewLocation = IntArray(2)
                    webView.getLocationOnScreen(webViewLocation)
                    lastKnownWebViewX = lastCursorX - webViewLocation[0]
                    lastKnownWebViewY = lastCursorY - webViewLocation[1]

                    webView.evaluateJavascript("window.toggleTouchEvents(false);", null)
                }

                refreshCursor()
            } finally {
                isToggling = false
            }
        }
    }

    override fun onCursorPositionChanged(x: Float, y: Float, isVisible: Boolean) {
        val scale = dualWebViewGroup.uiScale
        // Dim mode is a deliberate "minimal" surface — only the
        // metadata / battery / time TextViews render. The cursor would
        // otherwise reappear on the next cursor-input tick after
        // handleMaskToggle's initial hide. Force it off as long as
        // we're masked, regardless of input state.
        val maskActive = ::dualWebViewGroup.isInitialized && dualWebViewGroup.isScreenMasked()
        val shouldRenderCursor = isVisible && !isMouseTapMode && !maskActive

        // Calculate visual position scaled around center (320, 240) and translated (only in
        // non-anchored mode)
        val transX = if (isAnchored) 0f else dualWebViewGroup.leftEyeUIContainer.translationX
        val transY = if (isAnchored) 0f else dualWebViewGroup.leftEyeUIContainer.translationY

        val visualX = 320f + (x - 320f) * scale + transX
        val visualY = 240f + (y - 240f) * scale + transY

        // Logic to prevent "wrapping":
        // 1. Left cursor should ONLY be visible if it is within the left screen bounds (< 640)
        // 2. Right cursor (which is at visualX + 640) should ONLY be visible if visualX >= 0 (so
        // final x >= 640)
        // Note: We use a small buffer (e.g. -20 to 660) if we want to allow partial cursor
        // visibility at edges,
        // but strictly preventing wrapping means keeping it to the 640 boundary.

        val showLeft = shouldRenderCursor && visualX < 640f
        val showRight = shouldRenderCursor && visualX >= 0f

        // Left screen cursor - pivot at top-left so scaling happens from cursor tip
        cursorLeftView.pivotX = 0f
        cursorLeftView.pivotY = 0f
        cursorLeftView.x = visualX
        cursorLeftView.y = visualY
        cursorLeftView.scaleX = scale
        cursorLeftView.scaleY = scale
        cursorLeftView.visibility = if (showLeft) View.VISIBLE else View.GONE

        // Right screen cursor, offset by 640 pixels to appear on the right screen
        cursorRightView.pivotX = 0f
        cursorRightView.pivotY = 0f
        cursorRightView.x = visualX + 640
        cursorRightView.y = visualY
        cursorRightView.scaleX = scale
        cursorRightView.scaleY = scale
        cursorRightView.visibility = if (showRight) View.VISIBLE else View.GONE

        // Force layout and redraw for both cursors to ensure visibility
        cursorLeftView.requestLayout()
        cursorRightView.requestLayout()
        cursorLeftView.invalidate()
        cursorRightView.invalidate()
    }

    override fun onKeyPressed(key: String) {
        DebugLog.d("LinkEditing", "onKeyPressed called with: $key")
        val editFieldVisible = dualWebViewGroup.urlEditText.visibility == View.VISIBLE

        when {
            dualWebViewGroup.isBookmarksExpanded() && !editFieldVisible -> {
                dualWebViewGroup.getBookmarksView().handleKeyboardInput(key)
            }
            editFieldVisible -> {
                // Handle any edit field input (URL or bookmark)
                val currentText = dualWebViewGroup.getCurrentLinkText()
                val cursorPosition = dualWebViewGroup.urlEditText.selectionStart

                // Insert the key at cursor position
                val newText = StringBuilder(currentText).insert(cursorPosition, key).toString()

                // Set text and move cursor after inserted character
                dualWebViewGroup.setLinkText(newText, cursorPosition + 1)
            }
            dualWebViewGroup.getDialogInput() != null -> {
                val input = dualWebViewGroup.getDialogInput()!!
                val currentText = input.text.toString()
                val cursorPosition = input.selectionStart
                val newText = StringBuilder(currentText).insert(cursorPosition, key).toString()
                input.setText(newText)
                input.setSelection(cursorPosition + 1)
            }
            else -> {
                sendCharacterToWebView(key)
            }
        }
    }

    private fun handleUserInteraction() {
        if (isCursorVisible && !isKeyboardVisible) {}
    }

    override fun onBackspacePressed() {
        DebugLog.d("LinkEditing", "onBackspacePressed called")
        val editFieldVisible = dualWebViewGroup.urlEditText.visibility == View.VISIBLE

        when {
            dualWebViewGroup.isBookmarksExpanded() && !editFieldVisible -> {
                dualWebViewGroup.getBookmarksView().handleKeyboardInput("backspace")
            }
            editFieldVisible -> {
                val currentText = dualWebViewGroup.getCurrentLinkText()
                val cursorPosition = dualWebViewGroup.urlEditText.selectionStart

                if (cursorPosition > 0) {
                    // Delete character before cursor
                    val newText =
                            StringBuilder(currentText).deleteCharAt(cursorPosition - 1).toString()

                    // Set text and move cursor to position before deleted character
                    dualWebViewGroup.setLinkText(newText, cursorPosition - 1)
                }
            }
            dualWebViewGroup.getDialogInput() != null -> {
                val input = dualWebViewGroup.getDialogInput()!!
                val currentText = input.text.toString()
                val cursorPosition = input.selectionStart
                if (cursorPosition > 0) {
                    val newText =
                            StringBuilder(currentText).deleteCharAt(cursorPosition - 1).toString()
                    input.setText(newText)
                    input.setSelection(cursorPosition - 1)
                }
            }
            else -> {
                sendBackspaceToWebView()
            }
        }
    }

    override fun onEnterPressed() {
        isKeyboardVisible = false // if enter is pressed keyboard is no longer visible
        if (isUrlEditing) {

            isUrlEditing = false
            dualWebViewGroup.toggleIsUrlEditing(isUrlEditing)
        }

        wasKeyboardDismissedByEnter = true
        when {

            // If bookmarks are visible and being edited, handle bookmark updates
            dualWebViewGroup.isBookmarksExpanded() -> {
                dualWebViewGroup.getBookmarksView().onEnterPressed()
                hideCustomKeyboard()
            }
            dualWebViewGroup.getDialogInput() != null -> {
                // If in dialog input, enter might mean confirm, or just hide keyboard?
                // Usually OK button handles the confirm.
                // Let's just hide keyboard for now or do nothing.
                hideCustomKeyboard()
            }
            // Otherwise handle regular keyboard input
            else -> {
                sendEnterToWebView()
            }
        }
    }

    override fun onHideKeyboard() {
        suppressWebClickUntil = SystemClock.uptimeMillis() + 250
        if (dualWebViewGroup.isBookmarkEditing()) {
            dualWebViewGroup.hideBookmarkEditing()
        }
        hideCustomKeyboard()
    }

    override fun onRefreshPressed() {
        val currentUrl = webView.url
        webView.evaluateJavascript(
                """
            (function() {
                const injectedStyles = document.querySelectorAll('style[data-injected="true"]');
                injectedStyles.forEach(style => style.remove());

                let viewport = document.querySelector('meta[name="viewport"]');
                if (viewport) {
                    viewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                }
            })();
        """,
                null
        )

        Handler(Looper.getMainLooper())
                .postDelayed(
                        {
                            if (currentUrl != null) {
                                webView.loadUrl(currentUrl)
                            } else {
                                webView.loadUrl(Constants.DEFAULT_URL)
                            }
                        },
                        50
                )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Right-arm (cyttsp5_mt) physical-tap KEY double-tap → full Gemini exit.
        // Checked first so a double-tap can't be swallowed by the WebView /
        // focused view. Only consumes the event when it actually fires the exit.
        if (consumedByRightArmKeyGeminiExitDoubleTap(event)) {
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    dualWebViewGroup.toggleMediaPlayback()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    dualWebViewGroup.playMedia()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    dualWebViewGroup.pauseMedia()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // ── Dim-mode short-circuit ─────────────────────────────────
        // While the mask overlay is up, ALL touch events route through
        // a single dedicated GestureDetector that owns the two
        // documented gestures: single-tap (play/pause) and double-tap
        // (exit dim mode). Horizontal flings are swallowed there so
        // arm swipes cannot misfire as media skip commands. Putting
        // this check FIRST is intentional:
        // it sits in front of the cyttsp6 (temple) early-return below,
        // which would otherwise consume temple-arm events before our
        // handler sees them, AND in front of every gestureDetector /
        // overlay-listener / webview-dispatch path that competed
        // for events in earlier iterations.
        if (::dualWebViewGroup.isInitialized && dualWebViewGroup.isScreenMasked()) {
            try {
                maskedGestureDetector.onTouchEvent(ev)
            } catch (e: Exception) {
                DebugLog.w("MaskGesture", "maskedGestureDetector.onTouchEvent failed: ${e.message}")
            }
            return true
        }

        val deviceName = ev.device?.name ?: InputDevice.getDevice(ev.deviceId)?.name
        val isMainTouchpadEvent = deviceName?.contains("cyttsp5_mt", ignoreCase = true) == true
        if (isMainTouchpadEvent) {
            ensureMouseTapModeDisabled()
        }

        // Temple arm input should only be used for mode-toggle double taps.
        // The temple controller is cyttsp6_mt (NOT cyttsp5_mt which is the main touchpad).
        // Match cyttsp6 specifically but allow suffix variants for hardware revisions.
        val templeDeviceName = ev.device?.name ?: ""
        if (templeDeviceName.contains("cyttsp6", ignoreCase = true)) {
            if (consumedByRightArmGeminiExitDoubleTap(ev)) {
                return true
            }
            // Phase 4g — left-arm SHORT TAP → voiceServiceApi.toggleCamera().
            // Ported from visionclaw MainActivity.consumedByLeftArmTap. The
            // short-tap check runs BEFORE the temple double-tap detector
            // because a double-tap inherently contains a single tap; we
            // forward to the double-tap detector only when this UP doesn't
            // qualify as a standalone short-tap (moved too far / held too
            // long / no voice session). Same gesture model as the Hermes
            // branch — tap the left arm to flip the camera.
            if (consumedByLeftArmTap(ev)) {
                return true
            }
            templeDoubleTapDetector.onTouchEvent(ev)
            return true
        }

        autoEnterMouseModeForMudraInput(ev)

        val isMouseEvent = isMousePointerEvent(ev)

        // Track state at start of touch to prevent double-dispatch issues
        if (ev.action == MotionEvent.ACTION_DOWN) {
            wasTouchOnBookmarks = false
            wasTouchOnKeyboard = false
            wasKeyboardVisibleAtDown = isKeyboardVisible

            if (::dualWebViewGroup.isInitialized) {
                // In anchored mode, use the eye center (look-to-click) for coordinate checks
                // In non-anchored mode, use the raw touch coordinates
                val checkX: Float
                val checkY: Float

                if (isAnchored) {
                    val groupLocation = IntArray(2)
                    dualWebViewGroup.getLocationOnScreen(groupLocation)
                    checkX = 320f + groupLocation[0]
                    checkY = 240f + groupLocation[1]
                } else {
                    // In non-anchored mode, check if the CURSOR (not the touch) is over the
                    // bookmarks
                    // This matches the logic in dispatchTouchEventAtCursor
                    val scale = dualWebViewGroup.uiScale
                    val transX = dualWebViewGroup.leftEyeUIContainer.translationX
                    val transY = dualWebViewGroup.leftEyeUIContainer.translationY

                    val visualX = 320f + (lastCursorX - 320f) * scale + transX
                    val visualY = 240f + (lastCursorY - 240f) * scale + transY

                    val groupLocation = IntArray(2)
                    dualWebViewGroup.getLocationOnScreen(groupLocation)

                    checkX = visualX + groupLocation[0]
                    checkY = visualY + groupLocation[1]
                }

                if (dualWebViewGroup.isPointInBookmarks(checkX, checkY)) {
                    wasTouchOnBookmarks = true
                }
                if (dualWebViewGroup.isPointInKeyboard(checkX, checkY)) {
                    wasTouchOnKeyboard = true
                }
            }
        }

        // Dim-mode short-circuit. While the mask overlay is up, the
        // ONLY gestures that should fire are the three the maskOverlay
        // listener handles (swipe-left / swipe-right / double-tap).
        // Letting gestureDetector also process events here causes two
        // problems: (1) the global double-tap callback fires
        // performDoubleTapBackNavigation in parallel with our overlay's
        // double-tap detection, and (2) gestureDetector's onScroll
        // intercepts ACTION_MOVE for any drag, swallowing the events
        // before our listener can classify a horizontal swipe. Easiest
        // fix: skip gestureDetector entirely when masked, let events
        // flow naturally to the maskOverlay's setOnTouchListener.
        val maskActive = ::dualWebViewGroup.isInitialized && dualWebViewGroup.isScreenMasked()
        // Let gestureDetector see the event for global gestures (like double-tap back)
        // regardless of whether a child view consumes it.
        if (!isMouseEvent && !maskActive) {
            isDispatchingTouchEvent = true
            try {
                isGestureHandled = gestureDetector.onTouchEvent(ev)
            } finally {
                isDispatchingTouchEvent = false
            }
        } else if (maskActive) {
            // Dim mode active — gestureDetector deliberately bypassed.
            isGestureHandled = false
        } else {
            val mousePoint = resolveMouseScreenPoint(ev)
            val rawX = mousePoint.first
            val rawY = mousePoint.second
            val mappedPoint = mapMousePointForVirtualTap(rawX, rawY)
            val mappedX = mappedPoint.first
            val mappedY = mappedPoint.second
            val usedRightEyeMapping = isMouseTapMode && mappedX != rawX

            lastMouseRawX = rawX
            lastMouseRawY = rawY
            lastMouseMappedX = mappedX
            lastMouseMappedY = mappedY

            val useMouseForGestures = !isMouseTapMode
            if (useMouseForGestures) {
                val gestureAction =
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_BUTTON_PRESS -> MotionEvent.ACTION_DOWN
                            MotionEvent.ACTION_BUTTON_RELEASE -> MotionEvent.ACTION_UP
                            else -> ev.actionMasked
                        }

                val shouldSendToGestureDetector =
                        gestureAction == MotionEvent.ACTION_DOWN ||
                                gestureAction == MotionEvent.ACTION_MOVE ||
                                gestureAction == MotionEvent.ACTION_UP ||
                                gestureAction == MotionEvent.ACTION_CANCEL

                isDispatchingTouchEvent = true
                try {
                    if (shouldSendToGestureDetector) {
                        val injectDownBeforeUp =
                                gestureAction == MotionEvent.ACTION_UP && !mouseGestureActive

                        if (injectDownBeforeUp) {
                            val syntheticDownTime =
                                    if (ev.downTime > 0L && ev.downTime <= ev.eventTime) ev.downTime
                                    else ev.eventTime
                            val syntheticDown =
                                    MotionEvent.obtain(
                                            syntheticDownTime,
                                            syntheticDownTime,
                                            MotionEvent.ACTION_DOWN,
                                            mappedX,
                                            mappedY,
                                            ev.metaState
                                    )
                            syntheticDown.source = InputDevice.SOURCE_TOUCHSCREEN
                            try {
                                gestureDetector.onTouchEvent(syntheticDown)
                            } finally {
                                syntheticDown.recycle()
                            }
                            mouseGestureDownTime = syntheticDownTime
                            mouseGestureActive = true
                        }

                        if (gestureAction == MotionEvent.ACTION_DOWN) {
                            mouseGestureDownTime = ev.eventTime
                            mouseGestureActive = true
                        }

                        val gestureDownTime =
                                if (gestureAction == MotionEvent.ACTION_DOWN) ev.eventTime
                                else if (mouseGestureActive) mouseGestureDownTime
                                else ev.downTime

                        val gestureEvent =
                                MotionEvent.obtain(
                                        gestureDownTime,
                                        ev.eventTime,
                                        gestureAction,
                                        mappedX,
                                        mappedY,
                                        ev.metaState
                                )
                        gestureEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                        try {
                            isGestureHandled = gestureDetector.onTouchEvent(gestureEvent)
                        } finally {
                            gestureEvent.recycle()
                        }

                        if (gestureAction == MotionEvent.ACTION_UP ||
                                        gestureAction == MotionEvent.ACTION_CANCEL
                        ) {
                            mouseGestureActive = false
                        }
                    } else {
                        isGestureHandled = false
                    }
                } finally {
                    isDispatchingTouchEvent = false
                }
            } else {
                isGestureHandled = false
            }

            when (ev.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_MOVE -> {
                    if (::dualWebViewGroup.isInitialized) {
                        dualWebViewGroup.updatePointerHover(mappedX, mappedY)
                    }
                }
                MotionEvent.ACTION_HOVER_EXIT,
                MotionEvent.ACTION_CANCEL -> {
                    if (::dualWebViewGroup.isInitialized) {
                        dualWebViewGroup.clearPointerHover()
                    }
                }
            }

            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                            ev.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
            ) {
                if (!useMouseForGestures) {
                    val dragSlop = 10f
                    val movedSinceDown =
                            kotlin.math.abs(mappedX - mouseSwipeStartX) >= dragSlop ||
                                    kotlin.math.abs(mappedY - mouseSwipeStartY) >= dragSlop
                    val longPressLike =
                            mouseSwipeTracking &&
                                    (ev.eventTime - mouseSwipeDownTime) >= 120L &&
                                    !mouseSwipeStartedOnCustomUi
                    val dragLikeRelease = movedSinceDown || longPressLike

                    if (mouseSwipeDownDispatched) {
                        dispatchWebTouchFromScreen(
                                MotionEvent.ACTION_CANCEL,
                                mappedX,
                                mappedY,
                                ev.eventTime
                        )
                        mouseSwipeTracking = false
                        mouseSwipeStartedOnCustomUi = false
                        mouseSwipeDownDispatched = false
                        return true
                    }

                    if (dragLikeRelease && !mouseSwipeStartedOnCustomUi) {
                        mouseSwipeTracking = false
                        mouseSwipeStartedOnCustomUi = false
                        mouseSwipeDownDispatched = false
                        return true
                    }

                    if (handleMouseClickForCustomUi(mappedX, mappedY)) {
                        mouseSwipeTracking = false
                        mouseSwipeStartedOnCustomUi = false
                        mouseSwipeDownDispatched = false
                        return true
                    }
                    if (usedRightEyeMapping) {
                        dispatchWebTapAtScreenCoordinates(mappedX, mappedY)
                        mouseSwipeTracking = false
                        mouseSwipeStartedOnCustomUi = false
                        mouseSwipeDownDispatched = false
                        return true
                    }
                    maybeShowKeyboardForMouseClick(mappedX, mappedY)
                    mouseSwipeTracking = false
                    mouseSwipeStartedOnCustomUi = false
                    mouseSwipeDownDispatched = false
                }
            }

            if (!useMouseForGestures) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_BUTTON_PRESS -> {
                        mouseSwipeTracking = true
                        mouseSwipeStartedOnCustomUi = isPointOnCustomUi(mappedX, mappedY)
                        mouseSwipeDownDispatched = false
                        mouseSwipeStartX = mappedX
                        mouseSwipeStartY = mappedY
                        mouseSwipeLastX = mappedX
                        mouseSwipeLastY = mappedY
                        mouseSwipeDownTime = ev.eventTime
                    }
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_HOVER_MOVE -> {
                        if (mouseSwipeTracking && !mouseSwipeStartedOnCustomUi) {
                            val dragSlop = 10f
                            val movedEnough =
                                    kotlin.math.abs(mappedX - mouseSwipeStartX) >= dragSlop ||
                                            kotlin.math.abs(mappedY - mouseSwipeStartY) >= dragSlop

                            if (!mouseSwipeDownDispatched && movedEnough) {
                                mouseSwipeDownDispatched =
                                        dispatchWebTouchFromScreen(
                                                MotionEvent.ACTION_DOWN,
                                                mouseSwipeStartX,
                                                mouseSwipeStartY,
                                                mouseSwipeDownTime,
                                                mouseSwipeDownTime
                                        )
                            }

                            if (mouseSwipeDownDispatched) {
                                dispatchWebTouchFromScreen(
                                        MotionEvent.ACTION_MOVE,
                                        mappedX,
                                        mappedY,
                                        ev.eventTime,
                                        mouseSwipeDownTime
                                )
                                mouseSwipeLastX = mappedX
                                mouseSwipeLastY = mappedY
                                return true
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (mouseSwipeDownDispatched) {
                            dispatchWebTouchFromScreen(
                                    MotionEvent.ACTION_CANCEL,
                                    mouseSwipeLastX,
                                    mouseSwipeLastY,
                                    ev.eventTime,
                                    mouseSwipeDownTime
                            )
                        }
                        mouseSwipeTracking = false
                        mouseSwipeStartedOnCustomUi = false
                        mouseSwipeDownDispatched = false
                    }
                }
            }
        }

        // Reset idle timer on any touch to restore full refresh rate
        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.noteUserInteraction()
        }

        // Ghost-tap prevention: the gesture detector already processed cursor
        // movement and tap detection above. Letting raw touchpad events also
        // reach the Android view tree causes double-dispatch ("ghost taps")
        // that trigger unexpected scrolling, mask mode, etc.
        //
        // EXCEPTION: when the keyboard or bookmarks are visible, the view tree
        // MUST receive events so DualWebViewGroup.onInterceptTouchEvent →
        // onTouchEvent → performFocusedTap / handleAnchoredTap can fire.
        if (!isMouseEvent && isMainTouchpadEvent) {
            val needsViewTree = isKeyboardVisible ||
                (::dualWebViewGroup.isInitialized && (
                    dualWebViewGroup.isBookmarksExpanded() ||
                    dualWebViewGroup.isChatVisible() ||
                    dualWebViewGroup.getDialogInput() != null))
            if (!needsViewTree) {
                return true
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        autoEnterMouseModeForMudraInput(ev)

        if (isMousePointerEvent(ev) && ::dualWebViewGroup.isInitialized) {
            val mousePoint = resolveMouseScreenPoint(ev)
            val rawX = mousePoint.first
            val rawY = mousePoint.second
            val mappedPoint = mapMousePointForVirtualTap(rawX, rawY)
            val mappedX = mappedPoint.first
            val mappedY = mappedPoint.second

            lastMouseRawX = rawX
            lastMouseRawY = rawY
            lastMouseMappedX = mappedX
            lastMouseMappedY = mappedY

            when (ev.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_MOVE -> {
                    dualWebViewGroup.updatePointerHover(mappedX, mappedY)
                }
                MotionEvent.ACTION_HOVER_EXIT,
                MotionEvent.ACTION_CANCEL -> {
                    dualWebViewGroup.clearPointerHover()
                }
            }
        }

        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        autoEnterMouseModeForMudraInput(event)

        DebugLog.d(
                "RingInput",
                """
        Touch Event:
        Action: ${event.action}
        Source: ${event.source}
        Device: ${event.device?.name}
        ButtonState: ${event.buttonState}
        Pressure: ${event.pressure}
        Size: ${event.size}
        EventTime: ${event.eventTime}
        DownTime: ${event.downTime}
        Duration: ${event.eventTime - event.downTime}ms
    """.trimIndent()
        )

        // Use the result captured in dispatchTouchEvent to avoid calling it twice
        val handled = isGestureHandled

        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            // triple click menu fling logic was here
        }

        // If gesture detector handled it, consume the event
        return handled || super.onTouchEvent(event)
    }

    // In the implementation of NavigationListener
    override fun onHomePressed() {
        val homeUrl = dualWebViewGroup.getBookmarksView().getHomeUrl()
        webView.loadUrl(homeUrl)
    }

    // In the implementation of NavigationListener
    override fun onSettingsPressed() {
        DebugLog.d("Navigation", "Settings pressed")
        dualWebViewGroup.showSettings()
    }

    // Add the navigation interface implementations
    override fun onNavigationBackPressed() {
        val historyList = webView.copyBackForwardList()
        val canGoBack = webView.canGoBack()

        DebugLog.d(
                "NavigationDebug",
                """
            Back pressed:
            Current URL: ${webView.url}
            Can go back: $canGoBack
            History size: ${historyList.size}
        """.trimIndent()
        )

        if (!canGoBack) {
            DebugLog.d("NavigationDebug", "No history entry available for goBack()")
            return
        }

        dualWebViewGroup.updateLoadingProgress(0)

        if (historyList.size > 1) {
            historyList.getItemAtIndex(historyList.size - 2).url.also {
                DebugLog.d("NavigationDebug", "Attempting to go back to: $it")
            }
        } else {
            DebugLog.d("NavigationDebug", "History stack did not expose a previous URL")
        }

        // First, stop all JavaScript execution and ongoing loads
        webView.evaluateJavascript("window.stop();", null)
        webView.stopLoading()

        // Clear all JavaScript intervals and timeouts
        webView.evaluateJavascript(
                """
                (function() {
                    // Clear all intervals and timeouts
                    const highestId = window.setInterval(() => {}, 0);
                    for (let i = highestId; i >= 0; i--) {
                        window.clearInterval(i);
                        window.clearTimeout(i);
                    }

                    // Clear onbeforeunload which some sites use to trap users
                    window.onbeforeunload = null;

                    // Force clear any alert/confirm/prompt dialogs
                    window.alert = function(){};
                    window.confirm = function(){return true;};
                    window.prompt = function(){return '';};
                })();
            """.trimIndent(),
                null
        )

        // Keep JavaScript enabled and go back
        webView.goBack()
        dualWebViewGroup.clearExternalScrollMetrics()
        dualWebViewGroup.stabilizeWebViewViewportAfterNavigation(
            targetWebView = webView,
            resetVerticalScroll = false
        )
        uiHandler.postDelayed({ syncNativeRadioToolbarState(scheduleDelayedBroadcasts = false) }, 150L)
        uiHandler.postDelayed({ syncActiveBrowserChrome(webView, includeDelayedPasses = false) }, 200L)
        uiHandler.postDelayed({
            dualWebViewGroup.stabilizeWebViewViewportAfterNavigation(
                targetWebView = webView,
                resetVerticalScroll = false
            )
        }, 550L)
        uiHandler.postDelayed({
            dualWebViewGroup.stabilizeWebViewViewportAfterNavigation(
                targetWebView = webView,
                resetVerticalScroll = false
            )
        }, 1400L)
        uiHandler.postDelayed({ syncTapRadioPlaybackUi() }, 350L)
        uiHandler.postDelayed({ syncTapRadioPlaybackUi() }, 1200L)
        webView.invalidate()
        dualWebViewGroup.invalidate()
    }

    override fun onQuitPressed() {
        finish()
    }

    override fun onDestroy() {
        if (activeInstanceRef?.get() === this) {
            activeInstanceRef = null
        }
        // Clear the BrowserFrameHolder so visionclaw-side tools see
        // "no browser available" instead of trying to capture a
        // destroyed view. The WeakReference would clear itself
        // eventually but explicit detach avoids a small flake window
        // where a captureBase64Jpeg call could find a half-torn view.
        try {
            com.TapLink.app.media.BrowserFrameHolder.detach(webView)
        } catch (_: Exception) {}
        // Phase 2 Step 2c.3: drop our ChatCardBridge listener so a
        // recreated Activity instance doesn't double-subscribe and
        // a stale Activity reference doesn't keep getting fired.
        try {
            unipanelChatCardSubscription?.close()
        } catch (_: Exception) {}
        unipanelChatCardSubscription = null
        try {
            browserCommandSubscription?.close()
        } catch (_: Exception) {}
        browserCommandSubscription = null
        // Phase 2 Step 2c.4: same pattern for the camera chip
        // subscription.
        try {
            unipanelCameraChipSubscription?.close()
        } catch (_: Exception) {}
        unipanelCameraChipSubscription = null
        // Unipanel v2 Phase 6: drop the voice pill bridge subscription
        // so a recreated Activity doesn't double-listen and drift.
        try {
            unipanelVoicePillSubscription?.close()
        } catch (_: Exception) {}
        unipanelVoicePillSubscription = null
        // Phase 4d (Mars revision) — drop the vision dot subscription.
        try {
            unipanelVisionDotSubscription?.close()
        } catch (_: Exception) {}
        unipanelVisionDotSubscription = null
        // Phase 4h — drop the AI status badge subscription.
        try {
            unipanelHudAiBadgeSubscription?.close()
        } catch (_: Exception) {}
        unipanelHudAiBadgeSubscription = null
        try {
            unipanelHudStateSubscription?.close()
        } catch (_: Exception) {}
        unipanelHudStateSubscription = null
        unipanelHeartbeatScrollAnimator?.cancel()
        unipanelHeartbeatScrollAnimator = null
        uiHandler.removeCallbacks(hideUnipanelHeartbeatRunnable)
        unipanelHeartbeatClearRunnable?.let { uiHandler.removeCallbacks(it) }
        unipanelHeartbeatClearRunnable = null
        uiHandler.removeCallbacks(hideUnipanelAssistantCardRunnable)
        pendingRightArmSingleTapAction?.let { uiHandler.removeCallbacks(it) }
        pendingRightArmSingleTapAction = null
        // Phase 4b: unregister the battery receiver so a config change
        // / Activity recreate doesn't leak it.
        try {
            unipanelHudBatteryReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
        unipanelHudBatteryReceiver = null
        // Phase 4f: unregister the network callback so a recreated
        // Activity doesn't accumulate dead listeners.
        try {
            unipanelHudNetworkCallback?.let { cb ->
                (getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager)?.unregisterNetworkCallback(cb)
            }
        } catch (_: Exception) {}
        unipanelHudNetworkCallback = null
        // Unipanel v2 Phase 3: drop the voice Service binding so an
        // Activity-recreate doesn't leak a ServiceConnection or
        // double-bind on the next onCreate.
        stopVoiceServiceBinding()
        // ── Release native radio ExoPlayer ──
        // Critical: without this, the ExoPlayer continues playing audio in the
        // background after the Activity is destroyed (holds WAKE_MODE_LOCAL lock).
        // On AR glasses with limited RAM, the system frequently destroys TapBrowser
        // when the user switches back to VisionClaw, creating orphaned players.
        releaseNativeRadioPlayer(clearMetadata = true, abandonFocus = true)
        // Release browser agent resources
        stopBrowserAgent("Activity destroyed")
        agentAudioTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        agentAudioTrack = null
        super.onDestroy()
        // Cancel all pending handler callbacks to prevent activity leaks
        uiHandler.removeCallbacksAndMessages(null)
        gpsHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        stopCloudTts()
        cameraDevice?.close()
        imageReader?.close()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        sensorManager.unregisterListener(sensorEventListener)
        stopGpsUpdates()
        // Release DualWebViewGroup resources
        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.cleanupResources()
        }
    }

    private fun prepareForIncomingYouTubeAutoplayInternal() {
        if (!::dualWebViewGroup.isInitialized || !::webView.isInitialized) return

        DebugLog.d("YouTubeAuto", "prepareForIncomingYouTubeAutoplayInternal: suspending existing YouTube playback before handoff")
        dualWebViewGroup.pauseYouTubeMediaAcrossAllWindows()

        val currentUrl = webView.url.orEmpty()
        val isCurrentYouTube =
                currentUrl.contains("youtube.com", ignoreCase = true) ||
                        currentUrl.contains("youtu.be", ignoreCase = true)
        if (!isCurrentYouTube) return

        try {
            webView.stopLoading()
        } catch (_: Exception) {}

        webView.evaluateJavascript(
                """
                (function() {
                    try {
                        document.querySelectorAll('video, audio').forEach(function(el) {
                            try {
                                el.pause();
                                el.autoplay = false;
                                el.muted = true;
                                el.currentTime = 0;
                                el.removeAttribute('src');
                                el.load();
                            } catch (inner) {}
                        });
                    } catch (outer) {}
                    try {
                        var tid = window.setTimeout(function(){}, 0);
                        while (tid--) clearTimeout(tid);
                        var iid = window.setInterval(function(){}, 0);
                        while (iid--) clearInterval(iid);
                    } catch (timers) {}
                    window.__taplink_yt_injected = false;
                    window.__taplink_watch_injected = false;
                    window.__taplink_playback_started = false;
                })();
                """.trimIndent(),
                null
        )
    }

    override fun onStop() {
        super.onStop()

        // Save a full window snapshot only when we are actually stopping.
        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.saveAllWindowsState(forceSync = true)
        }

        // Persist active state on stop as a final snapshot.
        persistActiveWebViewState("onStop", webView)
        stopGpsUpdates()
    }

    private fun persistActiveWebViewState(reason: String, activeView: WebView? = webView) {
        if (!::dualWebViewGroup.isInitialized) {
            return
        }

        val targetView = activeView ?: return
        if (!dualWebViewGroup.isActiveWebView(targetView)) {
            return
        }

        val currentUrl = targetView.url
        if (currentUrl.isNullOrBlank() || currentUrl.startsWith("about:blank")) {
            return
        }

        DebugLog.d("WebViewDebug", "Persisting active state ($reason): $currentUrl")

        getSharedPreferences(prefsName, MODE_PRIVATE)
                .edit()
                .putString(keyLastUrl, currentUrl)
                .apply()
        lastUrl = currentUrl

        try {
            val webViewState = Bundle()
            targetView.saveState(webViewState)

            val parcel = Parcel.obtain()
            webViewState.writeToParcel(parcel, 0)
            val serializedState = Base64.encodeToString(parcel.marshall(), Base64.DEFAULT)
            parcel.recycle()

            getSharedPreferences(prefsName, MODE_PRIVATE).edit {
                putString(Constants.KEY_WEBVIEW_STATE, serializedState)
            }

            DebugLog.d("WebViewDebug", "WebView state persisted successfully ($reason)")
        } catch (e: Exception) {
            DebugLog.e("WebViewDebug", "Error persisting WebView state ($reason)", e)
        }
    }

    private fun persistActiveUrl(reason: String, url: String, activeView: WebView? = webView) {
        if (!::dualWebViewGroup.isInitialized) {
            return
        }

        val targetView = activeView ?: return
        if (!dualWebViewGroup.isActiveWebView(targetView)) {
            return
        }

        if (url.startsWith("about:blank")) {
            return
        }

        DebugLog.d("WebViewDebug", "Persisting last URL ($reason): $url")
        getSharedPreferences(prefsName, MODE_PRIVATE).edit().putString(keyLastUrl, url).apply()
        lastUrl = url
    }

    // Add JavaScript interface to reset capturing state
    class AndroidInterface(private val activity: MainActivity, private val webView: WebView) {

        // ── Pending text for the media player pull-based approach ──
        // openTextReader stores the text here; media_player.html pulls it
        // via getPendingText() once it has fully loaded — no race conditions.
        @Volatile var pendingReaderText: String? = null
            private set
        @Volatile var pendingReaderTitle: String? = null
            private set

        fun setPendingText(text: String, title: String) {
            pendingReaderText = text
            pendingReaderTitle = title
        }

        @JavascriptInterface
        fun getPendingText(): String {
            val text = pendingReaderText ?: ""
            pendingReaderText = null   // consume once
            pendingReaderTitle = null
            return text
        }

        @JavascriptInterface
        fun onScrollMetrics(
                rangeX: Double,
                extentX: Double,
                offsetX: Double,
                rangeY: Double,
                extentY: Double,
                offsetY: Double
        ) {
            if (!activity.dualWebViewGroup.isActiveWebView(webView)) {
                return
            }
            activity.runOnUiThread {
                activity.dualWebViewGroup.updateExternalScrollMetrics(
                        rangeX.toInt(),
                        extentX.toInt(),
                        offsetX.toInt(),
                        rangeY.toInt(),
                        extentY.toInt(),
                        offsetY.toInt()
                )
            }
        }

        @JavascriptInterface
        fun onCaptureComplete() {
            activity.runOnUiThread { activity.isCapturing = false }
        }

        @JavascriptInterface
        fun startNativeQrScanner() {
            activity.runOnUiThread { activity.startNativeQrScanner(webView) }
        }

        @JavascriptInterface
        fun stopNativeQrScanner() {
            activity.runOnUiThread { activity.stopNativeQrScannerSession() }
        }

        /**
         * Called from the dashboard JS when the user edits links.
         * Writes the full dashboard JSON to SharedPreferences so the
         * companion app can read/write the same data.
         */
        @JavascriptInterface
        fun saveDashboardData(json: String) {
            try {
                val prefs = activity.getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
                prefs.edit().putString("dashboard_data", json).apply()
                DebugLog.d("AndroidInterface", "Dashboard data saved to SharedPreferences (${json.length} chars)")
            } catch (e: Exception) {
                DebugLog.e("AndroidInterface", "Error saving dashboard data", e)
            }
        }

        /**
         * Returns saved dashboard JSON from SharedPreferences (written by
         * the companion app's Dashboard editor), or empty string if none.
         */
        @JavascriptInterface
        fun getDashboardData(): String {
            return try {
                val prefs = activity.getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
                prefs.getString("dashboard_data", "") ?: ""
            } catch (e: Exception) {
                DebugLog.e("AndroidInterface", "Error reading dashboard data", e)
                ""
            }
        }

        /**
         * Returns saved TapRadio stations JSON from SharedPreferences
         * (written by the companion app's TapRadio editor).
         */
        @JavascriptInterface
        fun getRadioStations(): String {
            return try {
                val prefs = activity.getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
                prefs.getString("tapradio_stations", "") ?: ""
            } catch (e: Exception) {
                DebugLog.e("AndroidInterface", "Error reading radio stations", e)
                ""
            }
        }

        /**
         * Saves TapRadio stations JSON to SharedPreferences so the
         * companion app and glasses player share the same station list.
         */
        @JavascriptInterface
        fun saveRadioStations(json: String) {
            try {
                val prefs = activity.getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
                prefs.edit().putString("tapradio_stations", json).apply()
                DebugLog.d("AndroidInterface", "Radio stations saved to SharedPreferences (${json.length} chars)")
            } catch (e: Exception) {
                DebugLog.e("AndroidInterface", "Error saving radio stations", e)
            }
        }

        /**
         * Persists the currently-selected TapRadio genre tab so the
         * dim-mode HUD can fall back to "first station in this genre"
         * when nothing is actively playing. Called by radio.html on
         * page init and on every genre-tab tap.
         */
        @JavascriptInterface
        fun saveRadioActiveGenre(genre: String?) {
            try {
                val cleaned = genre?.trim().orEmpty()
                val prefs = activity.getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
                prefs.edit().putString("tapradio_active_genre", cleaned).apply()
            } catch (e: Exception) {
                DebugLog.e("AndroidInterface", "Error saving active genre", e)
            }
        }

        /**
         * Persists the actual TapRadio playback state so the chat HUD can
         * reflect what is truly playing when the user returns from TapBrowser.
         */
        @JavascriptInterface
        fun saveRadioPlaybackState(stationName: String?, genre: String?, playing: Boolean) {
            activity.persistTapRadioPlaybackState(stationName, genre, playing)
        }

        @JavascriptInterface
        fun getRadioPlaybackState(): String {
            return try {
                val prefs = activity.getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
                org.json.JSONObject().apply {
                    put("playing", prefs.getBoolean("tapradio_now_playing_active", false))
                    put("stationName", prefs.getString("tapradio_now_playing_name", "") ?: "")
                    put("genre", prefs.getString("tapradio_now_playing_genre", "") ?: "")
                    put("kind", prefs.getString("tapradio_now_playing_kind", "") ?: "")
                    put("url", prefs.getString("tapradio_now_playing_url", "") ?: "")
                    put("positionMs", prefs.getLong("tapradio_now_playing_position_ms", 0L))
                    put("durationMs", prefs.getLong("tapradio_now_playing_duration_ms", 0L))
                    put("error", prefs.getString("tapradio_now_playing_error", "") ?: "")
                    put("updatedAt", prefs.getLong("tapradio_now_playing_updated_at", 0L))
                }.toString()
            } catch (e: Exception) {
                DebugLog.e("AndroidInterface", "Error reading radio playback state", e)
                "{\"playing\":false}"
            }
        }

        // ── Media player exit ──────────────────────────────────────────
        @JavascriptInterface
        fun exitMediaPlayer() {
            DebugLog.d("AndroidInterface", "exitMediaPlayer called from media_player.html")
            activity.runOnUiThread {
                // Navigate back in WebView history (returns to the page that opened the file)
                val wv = activity.findViewById<WebView>(android.R.id.content)
                    ?: activity.window?.decorView?.rootView?.findViewWithTag<WebView>("mainWebView")
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    activity.onBackPressed()
                }
            }
        }

        // ── Open text reader from any page ────────────────────────────────
        @JavascriptInterface
        fun openTextReader(text: String, title: String) {
            DebugLog.d("AndroidInterface", "openTextReader: ${text.length} chars, title=$title")
            if (text.isBlank()) return
            // Store text so media_player.html can pull it via getPendingText()
            // once it has fully loaded — no race conditions.
            setPendingText(text, title)
            activity.runOnUiThread {
                activity.openTextReaderDirect(title)
            }
        }

        // ── Native radio bridge (ExoPlayer) ──────────────────────────────
        // These methods are called from radio.html JavaScript to use the
        // native ExoPlayer-backed radio player instead of the HTML5 <audio>
        // element, providing much larger configurable buffers and
        // eliminating periodic rebuffer stutters.

        /** Helper: run a block on the UI thread and wait for its String result. */
        private fun runOnUiBlocking(block: () -> String): String {
            val latch = java.util.concurrent.CountDownLatch(1)
            var result = ""
            activity.runOnUiThread {
                try { result = block() } finally { latch.countDown() }
            }
            try { latch.await(3, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            return result.ifEmpty { activity.buildNativeRadioPlaybackStateJson() }
        }

        @JavascriptInterface
        fun playNativeRadioStream(url: String, stationName: String?, genre: String?): String {
            return runOnUiBlocking { activity.playNativeRadioStream(url, stationName, genre, kind = "radio") }
        }

        /** Extended play with kind parameter — "podcast" enables seek bar. */
        @JavascriptInterface
        fun playNativeRadioStreamEx(url: String, stationName: String?, genre: String?, kind: String?): String {
            return runOnUiBlocking {
                activity.playNativeRadioStream(url, stationName, genre, kind = kind)
            }
        }

        @JavascriptInterface
        fun pauseNativeRadioStream(): String {
            return runOnUiBlocking { activity.pauseNativeRadioStream() }
        }

        @JavascriptInterface
        fun resumeNativeRadioStream(): String {
            return runOnUiBlocking { activity.resumeNativeRadioStream() }
        }

        @JavascriptInterface
        fun stopNativeRadioStream(): String {
            return runOnUiBlocking { activity.stopNativeRadioStream() }
        }

        @JavascriptInterface
        fun getNativeRadioPlaybackState(): String {
            return activity.buildNativeRadioPlaybackStateJson()
        }

        /** Seek to a position in the current stream (podcasts only). */
        @JavascriptInterface
        fun seekNativeRadioStream(positionMs: Long): String {
            return runOnUiBlocking {
                try {
                    activity.nativeRadioPlayer?.seekTo(positionMs)
                } catch (e: Exception) {
                    DebugLog.w("TapRadioNative", "seekTo failed: ${e.message}")
                }
                activity.buildNativeRadioPlaybackStateJson()
            }
        }

        @JavascriptInterface
        fun fetchUrlText(url: String): String {
            return activity.fetchUrlTextForBridge(url)
        }

        @JavascriptInterface
        fun isMediaUrlSavedToLibrary(url: String, title: String?, kind: String?): String {
            return activity.buildMediaSavedStateJson(url, title, kind)
        }

        @JavascriptInterface
        fun saveMediaUrlToLibrary(url: String, title: String?, kind: String?): String {
            return activity.saveMediaUrlToLibraryJson(url, title, kind)
        }

        // ── Spotify user-OAuth token refresh ──────────────────────────────
        // Called from spotify.html (both reactively on 401 and proactively
        // ~5 min before expiry) to exchange the stored refresh_token for a
        // fresh access_token.  The app module's SpotifyTool has its own
        // refresh path for Gemini tool calls, but that runs in a separate
        // flow and cannot push its tokens into the WebView synchronously.
        // This bridge keeps the tokens in sync without a cross-process hop —
        // both live in the SAME applicationId (com.rayneo.visionclaw), so we
        // read/write the same SharedPreferences file ("visionclaw_prefs")
        // that AppPreferences uses in the app module.
        //
        // Returns JSON of the form:
        //   {"access_token":"BQ...","expires_at_ms":1713123456789}
        // On any failure (no stored refresh_token, network error, HTTP 400
        // from Spotify's /api/token), returns an empty JSON object "{}" so
        // the JS caller can surface a user-visible "re-connect" prompt.
        @JavascriptInterface
        fun refreshSpotifyAccessToken(): String {
            return try {
                val prefs = activity.getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
                val existing = (prefs.getString("spotify_access_token", "") ?: "").trim()
                val expiryMs = prefs.getLong("spotify_access_token_expiry_ms", 0L)
                val now = System.currentTimeMillis()

                // Fast path — existing token still has > 30s of life left.
                if (existing.isNotEmpty() && now < expiryMs - 30_000L) {
                    return org.json.JSONObject().apply {
                        put("access_token", existing)
                        put("expires_at_ms", expiryMs)
                    }.toString()
                }

                val refresh = (prefs.getString("spotify_refresh_token", "") ?: "").trim()
                if (refresh.isEmpty()) {
                    DebugLog.w("AndroidInterface", "refreshSpotifyAccessToken: no refresh_token stored")
                    return "{}"
                }
                val clientId = (prefs.getString("spotify_client_id", "") ?: "").trim()
                if (clientId.isEmpty()) {
                    DebugLog.w("AndroidInterface", "refreshSpotifyAccessToken: no client_id stored")
                    return "{}"
                }
                val clientSecret = (prefs.getString("spotify_client_secret", "") ?: "").trim()

                // Build application/x-www-form-urlencoded body.
                val form = buildString {
                    append("grant_type=refresh_token")
                    append("&refresh_token=").append(java.net.URLEncoder.encode(refresh, "UTF-8"))
                    append("&client_id=").append(java.net.URLEncoder.encode(clientId, "UTF-8"))
                }

                val conn = java.net.URL("https://accounts.spotify.com/api/token")
                    .openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                if (clientSecret.isNotEmpty()) {
                    val basic = android.util.Base64.encodeToString(
                        "$clientId:$clientSecret".toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                    conn.setRequestProperty("Authorization", "Basic $basic")
                }
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val body = try {
                    if (code in 200..299) {
                        conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } else {
                        (conn.errorStream ?: conn.inputStream)
                            .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }
                } finally {
                    conn.disconnect()
                }

                if (code !in 200..299) {
                    DebugLog.w("AndroidInterface",
                        "refreshSpotifyAccessToken: HTTP $code body=${body.take(200)}")
                    // 400 invalid_grant means the refresh_token itself is revoked —
                    // wipe the stored tokens so the companion app's "Connect Spotify"
                    // flow can re-prompt for authorization.
                    if (code == 400 && body.contains("invalid_grant")) {
                        prefs.edit()
                            .remove("spotify_access_token")
                            .remove("spotify_access_token_expiry_ms")
                            .remove("spotify_refresh_token")
                            .apply()
                    }
                    return "{}"
                }

                val json = org.json.JSONObject(body)
                val newAccess = json.optString("access_token").trim()
                val expiresIn = json.optLong("expires_in", 3_600L)
                val newRefresh = json.optString("refresh_token").trim()
                if (newAccess.isEmpty()) {
                    DebugLog.w("AndroidInterface", "refreshSpotifyAccessToken: empty access_token in response")
                    return "{}"
                }

                // Subtract 60s so downstream callers always see a token with
                // at least a minute of life left — matches AppPreferences logic.
                val newExpiryMs = System.currentTimeMillis() + (expiresIn - 60L) * 1000L
                prefs.edit().apply {
                    putString("spotify_access_token", newAccess)
                    putLong("spotify_access_token_expiry_ms", newExpiryMs)
                    if (newRefresh.isNotEmpty()) putString("spotify_refresh_token", newRefresh)
                }.apply()

                DebugLog.d("AndroidInterface",
                    "refreshSpotifyAccessToken: ok, expires in ${expiresIn}s")
                org.json.JSONObject().apply {
                    put("access_token", newAccess)
                    put("expires_at_ms", newExpiryMs)
                }.toString()
            } catch (e: Exception) {
                DebugLog.e("AndroidInterface", "refreshSpotifyAccessToken exception", e)
                "{}"
            }
        }
    }
}
