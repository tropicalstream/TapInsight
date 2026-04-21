package com.TapLinkX3.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.PowerManager
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.AttributeSet
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.TapLink.app.media.MediaFileInterceptor
import com.TapLink.app.media.MediaLibraryBridge
import java.util.concurrent.atomic.AtomicReference
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import kotlin.math.pow
import org.json.JSONObject

@SuppressLint("ClickableViewAccessibility")
class DualWebViewGroup
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        ViewGroup(context, attrs, defStyleAttr) {

    // Custom WebView to expose protected scroll methods
    private inner class InternalWebView(context: Context) : WebView(context) {
        fun getHorizontalScrollRange() = super.computeHorizontalScrollRange()
        fun getHorizontalScrollExtent() = super.computeHorizontalScrollExtent()
        fun getHorizontalScrollOffset() = super.computeHorizontalScrollOffset()
        fun getVerticalScrollRange() = super.computeVerticalScrollRange()
        fun getVerticalScrollExtent() = super.computeVerticalScrollExtent()
        fun getVerticalScrollOffset() = super.computeVerticalScrollOffset()
    }

    private val PREFS_NAME = "TapLinkPrefs"
    private val KEY_WINDOWS_STATE = "saved_windows_state"
    private val KEY_BROWSER_SHOW_SYSTEM_INFO = "browser_show_system_info"
    private val sharedConfigPrefs =
            context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
    private val sharedConfigListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_BROWSER_SHOW_SYSTEM_INFO) {
                    post {
                        updateSystemInfoBarVisibility()
                        requestLayout()
                        invalidate()
                    }
                }
            }

    private data class BrowserWindow(
            val id: String = java.util.UUID.randomUUID().toString(),
            val webView: InternalWebView,
            var thumbnail: Bitmap? = null,
            var title: String = "New Tab"
    )

    private data class ScrollMetrics(
            val rangeX: Int,
            val extentX: Int,
            val offsetX: Int,
            val rangeY: Int,
            val extentY: Int,
            val offsetY: Int
    )

    private data class ExternalScrollMetrics(
            val rangeX: Int,
            val extentX: Int,
            val offsetX: Int,
            val rangeY: Int,
            val extentY: Int,
            val offsetY: Int,
            val timestamp: Long
    )

    private val windows = java.util.concurrent.CopyOnWriteArrayList<BrowserWindow>()
    private var activeWindowId: String? = null

    interface WindowCallback {
        fun onWindowCreated(webView: WebView)
        fun onWindowSwitched(webView: WebView)
    }

    var windowCallback: WindowCallback? = null

    private var webView: InternalWebView

    val webViewsContainer: FrameLayout =
            FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }

    // REFACTORED: rightEyeView no longer needed - single viewport mode
    // BinocularSbsLayout now handles the binocular SBS rendering
    private val rightEyeView: SurfaceView = SurfaceView(context)

    val dialogContainer: FrameLayout =
            FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(640, 480) // Full left eye size
                setBackgroundColor(Color.parseColor("#CC000000")) // Semi-transparent black
                visibility = View.GONE
                isClickable = true
                isFocusable = true
                elevation = 2000f
            }
    private var customKeyboard: CustomKeyboardView? = null
    private var bitmap: Bitmap? = null

    private var velocityTracker: android.view.VelocityTracker? = null
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshInterval = 16L // ~60fps for smooth mirroring
    private val maskedRefreshIntervalMs = 100L // ~10fps while the screen is masked
    private var lastCaptureTime = 0L
    private var lastScrollBarCheckTime = 0L
    private val scrollBarVisibilityThrottleMs = 50L
    private val MIN_CAPTURE_INTERVAL = 16L // Cap at ~60fps
    private var lastCursorUpdateTime = 0L
    private val CURSOR_UPDATE_INTERVAL = 16L // 60fps cap for cursor updates
    private var lastScrollBarInteractionTime = 0L
    private val scrollBarHoldMs = 1200L
    private var lastHorzScrollableAt = 0L
    private var lastVertScrollableAt = 0L
    private var externalScrollMetrics: ExternalScrollMetrics? = null
    private val externalScrollMetricsStaleMs = 600000L // 10 minutes
    private var isMediaPlaying = false
    private var lastMediaPlayingAt = 0L
    private var lastMediaInteractionTime = 0L
    private val mediaScrollFreezeMs = 1500L
    private val mediaStateByWindowId = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val mediaLastPlayedAtByWindowId = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var nativeTapRadioPlaying = false
    private var nativeTapRadioLastActiveAt = 0L
    private val youtubeMediaTeardownScript =
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
            })();
            """.trimIndent()

    // Idle detection for power saving
    private var lastUserInteractionTime = 0L
    private val idleThresholdMs = 5000L // 5 seconds before considered idle
    private val idleRefreshIntervalMs = 100L // ~10fps when idle

    private lateinit var leftSystemInfoView: SystemInfoView

    lateinit var leftNavigationBar: View
    private val navBarHeightPx = 32.dp()
    private val toggleBarWidthPx = 32.dp()
    private val toggleButtonSizePx = toggleBarWidthPx

    val keyboardContainer: FrameLayout =
            FrameLayout(context).apply {
                val containerWidth = 640 - toggleBarWidthPx
                layoutParams =
                        FrameLayout.LayoutParams(
                                        containerWidth,
                                        FrameLayout.LayoutParams.WRAP_CONTENT
                                )
                                .apply {
                                    leftMargin = toggleBarWidthPx
                                    gravity = Gravity.TOP or Gravity.START
                                }
                setBackgroundColor(Color.TRANSPARENT)
                visibility = View.GONE
            }
    private val buttonFeedbackDuration = 200L
    var lastCursorX = 0f
    var lastCursorY = 0f

    private var anchoredGestureActive = false
    private var anchoredTarget = 0 // 0: None, 1: Keyboard, 2: Bookmarks, 3: Menu
    private var anchoredTouchStartX = 0f
    private var anchoredTouchStartY = 0f
    private var lastAnchoredY = 0f
    private var isAnchoredDrag = false
    private val ANCHORED_TOUCH_SLOP = 10f

    lateinit var leftToggleBar: View
    var progressBar: android.widget.ProgressBar =
            android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
                    .apply {
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 4)
                        progressDrawable.setTint(Color.BLUE)
                        max = 100
                        visibility = View.GONE
                        elevation = 200f // Ensure it's above other views
                    }
    private var btnShowNavBars: ImageButton =
            ImageButton(context).apply {
                layoutParams =
                        FrameLayout.LayoutParams(toggleButtonSizePx, toggleButtonSizePx).apply {
                            gravity = Gravity.BOTTOM or Gravity.END
                            rightMargin = 8
                            bottomMargin = 8
                        }
                setImageResource(R.drawable.ic_visibility_on)
                setBackgroundColor(Color.BLACK)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(8, 8, 8, 8)
                alpha = 1.0f
                visibility = View.GONE
                elevation = 2000f
                setOnClickListener {
                    setScrollMode(false)
                    setNavBarsHidden(false)
                }
            }

    @Volatile private var isRefreshing = false
    private val refreshLock = Any()

    private var isDesktopMode = false
    private var currentWebZoom = 1.0f
    private var isHoveringModeToggle = false
    private var isHoveringDashboardToggle = false
    private var isHoveringBookmarksMenu = false

    private lateinit var leftBookmarksView: BookmarksView
    private lateinit var chatView: ChatView

    var navigationListener: NavigationListener? = null
    var linkEditingListener: LinkEditingListener? = null

    private var isBookmarkEditing = false

    private var mobileUserAgent: String
    private var desktopUserAgent: String = ""

    private val verticalScrollFraction = 0.25f // Scroll vertically by 25% of the viewport per tap

    private var isHoveringZoomIn = false
    private var isHoveringZoomOut = false
    private var isHoveringWindowsToggle = false
    private var windowsButton: FontIconView? = null

    private var fullScreenTapDetector: GestureDetector =
            GestureDetector(
                    context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDown(e: MotionEvent): Boolean {
                            // Always accept the initial down event so we can track the full gesture
                            return fullScreenOverlayContainer.visibility == View.VISIBLE
                        }

                        override fun onSingleTapUp(e: MotionEvent): Boolean {
                            // Controls visibility is now managed by dispatchFullScreenOverlayTouch
                            // in MainActivity, which handles button hit-testing first.
                            // Just consume the event here to prevent propagation.
                            return fullScreenOverlayContainer.visibility == View.VISIBLE
                        }
                    }
            )

    var isAnchored = false
        set(value) {
            field = value
            updateRefreshRate()
        }
    private var isHoveringAnchorToggle = false

    private val bitmapLock = Any()
    private var settingsMenu: View? = null
    private var isSettingsVisible = false

    interface DualWebViewGroupListener {
        fun onCursorPositionChanged(x: Float, y: Float, isVisible: Boolean)
    }

    interface MaskToggleListener {
        fun onMaskTogglePressed()
    }

    interface AnchorToggleListener {
        fun onAnchorTogglePressed()
    }

    interface FullscreenListener {
        fun onEnterFullscreen()
        fun onExitFullscreen()
    }

    var fullscreenListener: FullscreenListener? = null

    private var hideProgressBarRunnable: Runnable? = null

    fun updateLoadingProgress(progress: Int) {

        post {
            // Cancel any pending hide action whenever we get an update
            hideProgressBarRunnable?.let { removeCallbacks(it) }
            hideProgressBarRunnable = null

            if (progress < 100) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = progress
                progressBar.bringToFront()
                requestLayout() // Force layout update to position progress bar correctly
            } else {
                progressBar.progress = 100
                // Delay hiding to ensure user sees 100%
                hideProgressBarRunnable = Runnable { progressBar.visibility = View.GONE }
                postDelayed(hideProgressBarRunnable!!, 500)
            }
        }
    }

    private data class NavButton(
            val left: FontIconView,
            val right: FontIconView,
            var isHovered: Boolean = false
    )

    private fun FontIconView.configureToggleButton(iconRes: Int) {
        visibility = View.VISIBLE
        setText(iconRes)
        setBackgroundResource(R.drawable.nav_button_background)
        gravity = android.view.Gravity.CENTER
        setPadding(8, 8, 8, 8)
        alpha = 1.0f
        elevation = 2f
        stateListAnimator = null
    }

    private fun clearNavigationButtonStates() {
        navButtons.values.forEach { navButton ->
            navButton.isHovered = false
            navButton.left.isHovered = false
            navButton.right.isHovered = false
        }
    }

    // Properties for link editing
    lateinit var urlEditText: EditText
    private val urlFieldMinHeight = 56.dp()

    private var leftEditField: EditText
    private var rightEditField: EditText
    private var _isUrlEditing = false

    // Keyboard listener interface
    interface KeyboardListener {
        fun onShowKeyboard()
        fun onHideKeyboard()
    }

    var keyboardListener: KeyboardListener? = null
        set(value) {
            field = value
            if (::chatView.isInitialized) {
                chatView.keyboardListener = value
            }
        }

    var micListener: ChatView.MicListener? = null
        set(value) {
            field = value
            if (::chatView.isInitialized) {
                chatView.micListener = value
            }
        }

    private var navButtons: Map<String, NavButton>

    var listener: DualWebViewGroupListener? = null
    var maskToggleListener: MaskToggleListener? = null

    val leftEyeUIContainer =
            FrameLayout(context).apply {
                clipChildren = true
                clipToOutline = true

                setBackgroundColor(Color.TRANSPARENT) // Make sure background is transparent
            }

    fun isActiveWebView(webView: WebView): Boolean {
        return this.webView == webView
    }

    fun setChatMicActive(active: Boolean) {
        if (::chatView.isInitialized) {
            chatView.setMicActive(active)
        }
    }

    fun insertVoiceToChatInput(text: String) {
        if (::chatView.isInitialized) {
            chatView.insertVoiceText(text)
        }
    }

    fun pauseBackgroundMedia(sourceWebView: WebView) {
        windows.forEach { win ->
            if (win.webView != sourceWebView) {
                // Pause all media elements
                win.webView.evaluateJavascript(
                        "document.querySelectorAll('video, audio').forEach(function(e) { e.pause(); });",
                        null
                )
            }
        }
    }

    /**
     * Pause HTML5 <video> and <audio> elements across EVERY window, regardless
     * of the current URL. Used on explicit user navigation away from TapBrowser
     * (e.g. double-tap-return-to-chat) so background media can't keep playing
     * after the user has clearly switched away. YouTube-specific teardown lives
     * in [pauseYouTubeMediaAcrossAllWindows]; this is the non-YouTube sweep.
     */
    fun pauseAllWindowsMedia() {
        windows.forEach { win ->
            try {
                win.webView.post {
                    win.webView.evaluateJavascript(
                        "try { document.querySelectorAll('video, audio').forEach(function(e) { " +
                            "try { e.pause(); e.muted = true; } catch (err) {} " +
                            "}); } catch (err) {}",
                        null
                    )
                }
            } catch (_: Exception) {}
        }
    }

    fun pauseYouTubeMediaAcrossAllWindows(resetTracking: Boolean = true) {
        windows.forEach { win ->
            val url = win.webView.url.orEmpty()
            if (!url.contains("youtube.com", ignoreCase = true) &&
                            !url.contains("youtu.be", ignoreCase = true)
            ) {
                return@forEach
            }

            try {
                win.webView.stopLoading()
            } catch (_: Exception) {}

            win.webView.post { win.webView.evaluateJavascript(youtubeMediaTeardownScript, null) }
            mediaStateByWindowId[win.id] = false
        }

        if (resetTracking) {
            updateMediaState(anyTrackedMediaPlaying())
        }
    }

    private val fullScreenOverlayContainer =
            FrameLayout(context).apply {
                clipChildren = true
                clipToOutline = true // Ensure clipping to bounds
                setBackgroundColor(Color.BLACK)
                visibility = View.GONE
                isClickable = true
                isFocusable = true
            }

    // UI scale factor (0.5 to 1.0) - controlled by screen size slider
    var uiScale = 1.0f

    private val fullScreenHiddenViews: List<View> by lazy {
        listOf(
                webViewsContainer,
                leftToggleBar,
                leftNavigationBar,
                keyboardContainer,
                leftSystemInfoView,
                urlEditText
        )
    }

    private val previousFullScreenVisibility = mutableMapOf<View, Int>()

    val leftEyeClipParent =
            FrameLayout(context).apply {
                // Force it to be exactly 640px wide and match height (or some fixed height).
                // Using MATCH_PARENT for height is common if you want the full vertical space.
                layoutParams = FrameLayout.LayoutParams(640, FrameLayout.LayoutParams.MATCH_PARENT)

                // Ensure that children are clipped to our bounds
                clipToPadding = true
                clipChildren = true
                setBackgroundColor(Color.BLACK) // Set background to ensure proper rendering
            }

    fun updateUiScale(scale: Float) {
        uiScale = scale

        // Set pivot point to center (320, 240) so scaling happens around the center
        leftEyeUIContainer.pivotX = 320f
        leftEyeUIContainer.pivotY = 240f
        leftEyeUIContainer.scaleX = scale
        leftEyeUIContainer.scaleY = scale

        fullScreenOverlayContainer.pivotX = 320f
        fullScreenOverlayContainer.pivotY = 240f
        fullScreenOverlayContainer.scaleX = scale
        fullScreenOverlayContainer.scaleY = scale

        // Ensure parent is not scaled so it acts as a fixed window
        leftEyeClipParent.scaleX = 1f
        leftEyeClipParent.scaleY = 1f

        updateUiTranslation()

        // Update scroll bar visibility based on scale and anchor mode
        updateScrollBarsVisibility()

        // Notify listener to refresh cursor scale visually
        listener?.onCursorPositionChanged(lastCursorX, lastCursorY, true)

        requestLayout()
        invalidate()
    }

    private fun updateUiTranslation() {
        if (isAnchored) {
            leftEyeUIContainer.translationX = 0f
            leftEyeUIContainer.translationY = 0f
            fullScreenOverlayContainer.translationX = 0f
            fullScreenOverlayContainer.translationY = 0f
            return
        }

        // Calculate max allowed translation based on current scale
        val maxTransX = 320f * (1f - uiScale)
        val maxTransY = 240f * (1f - uiScale)

        // Get saved progress (default 50)
        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        val xProgress = prefs.getInt("uiTransXProgress", 50)
        val yProgress = prefs.getInt("uiTransYProgress", 50)

        // Calculate translation
        val transX = ((xProgress - 50) / 50f) * maxTransX
        val transY = ((yProgress - 50) / 50f) * maxTransY

        leftEyeUIContainer.translationX = transX
        leftEyeUIContainer.translationY = transY

        fullScreenOverlayContainer.translationX = transX
        fullScreenOverlayContainer.translationY = transY

        // Update scroll bar thumb positions
        updateScrollBarThumbs(xProgress, yProgress)
        applyScrollbarTransform()
    }

    fun recenterViewportForDashboard(targetWebView: WebView? = webView) {
        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("uiTransXProgress", 50)
                .putInt("uiTransYProgress", 50)
                .apply()
        updateUiTranslation()

        targetWebView?.post {
            try {
                targetWebView.scrollTo(0, 0)
            } catch (_: Exception) {}
            try {
                targetWebView.evaluateJavascript(
                        """
                        (function() {
                            try {
                                window.scrollTo(0, 0);
                                if (document.documentElement) {
                                    document.documentElement.scrollLeft = 0;
                                    document.documentElement.scrollTop = 0;
                                }
                                if (document.body) {
                                    document.body.scrollLeft = 0;
                                    document.body.scrollTop = 0;
                                }
                            } catch (e) {}
                        })();
                        """.trimIndent(),
                        null
                )
            } catch (_: Exception) {}
        }
    }

    private fun isWebViewScrollEnabled(): Boolean {
        // Always return true to ensure scrollbars ONLY scroll the WebView content
        // and never move the screen position (viewport panning).
        return true
    }

    private fun scrollPageHorizontal(delta: Int) {
        if (isWebViewScrollEnabled()) {
            // Scroll the WebView content
            val scrollAmount = delta * 15 // Increase sensitivity
            val metrics = resolveScrollMetrics(SystemClock.uptimeMillis())
            if (shouldUseJsScroll(metrics)) {
                scrollWebViewByJs(
                        left = scrollAmount,
                        top = null,
                        smooth = false,
                        useScrollTo = false
                )
            } else {
                webView.scrollBy(scrollAmount, 0)
            }
            updateScrollBarThumbs(0, 0) // Update thumbs immediately
        } else {
            // Pan the viewport
            val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            val currentProgress = prefs.getInt("uiTransXProgress", 50)
            val newProgress = (currentProgress + delta).coerceIn(0, 100)

            prefs.edit().putInt("uiTransXProgress", newProgress).apply()
            updateUiTranslation()
        }
    }

    private fun scrollPageVertical(delta: Int) {
        if (isWebViewScrollEnabled()) {
            // Scroll the WebView content
            val scrollAmount = delta * 15 // Increase sensitivity
            val metrics = resolveScrollMetrics(SystemClock.uptimeMillis())
            if (shouldUseJsScroll(metrics)) {
                scrollWebViewByJs(
                        left = null,
                        top = scrollAmount,
                        smooth = false,
                        useScrollTo = false
                )
            } else {
                webView.scrollBy(0, scrollAmount)
            }
            updateScrollBarThumbs(0, 0) // Update thumbs immediately
        } else {
            // Pan the viewport
            val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            val currentProgress = prefs.getInt("uiTransYProgress", 50)
            val newProgress = (currentProgress + delta).coerceIn(0, 100)

            prefs.edit().putInt("uiTransYProgress", newProgress).apply()
            updateUiTranslation()
        }
    }

    private fun shouldFreezeScrollBars(): Boolean {
        val now = SystemClock.uptimeMillis()
        return isMediaPlaying || (now - lastMediaPlayingAt < mediaScrollFreezeMs)
    }

    private fun updateScrollBarThumbs(xProgress: Int, yProgress: Int) {
        val now = SystemClock.uptimeMillis()
        // Guard against updates during or shortly after scrollbar interaction to prevent bouncing
        if (now - lastScrollBarInteractionTime < 250L) return

        if (isWebViewScrollEnabled()) {
            val metrics = resolveScrollMetrics(now)
            // Update Horizontal Thumb based on WebView scroll
            val hTrackContainer = horizontalScrollBar.getChildAt(1) as? FrameLayout
            val hTrackWidth =
                    when {
                        hTrackContainer != null && hTrackContainer.width > 0 ->
                                hTrackContainer.width
                        hTrackContainer != null && hTrackContainer.measuredWidth > 0 ->
                                hTrackContainer.measuredWidth
                        horizontalScrollBar.width > 0 -> {
                            val leftBtnWidth = horizontalScrollBar.getChildAt(0)?.width ?: 0
                            val rightBtnWidth = horizontalScrollBar.getChildAt(2)?.width ?: 0
                            (horizontalScrollBar.width - leftBtnWidth - rightBtnWidth)
                                    .coerceAtLeast(0)
                        }
                        else -> 0
                    }
            if (hTrackWidth > 0) {
                val thumbWidth = 60
                val maxMargin = hTrackWidth - thumbWidth
                // Calculate ratio: scrollX / (contentWidth - viewportWidth)
                // Since we can't easily get full content width without computeHorizontalScrollRange
                // (protected),
                // we'll rely on an approximation or need to subclass WebView.
                // For now, let's try using the standard range approximation if possible, or just
                // skip if we can't get it.
                // Actually, we can use computeHorizontalScrollRange via reflection or just use
                // scrollX/ArbitraryLargeNumber if needed,
                // but simpler is to use `webView.scrollX` relative to estimated width.
                // Let's defer exact horizontal proportion calculation or use a safe fallback.

                // Using standard view methods available on WebView (which is a View)
                val range = metrics.rangeX
                val extent = metrics.extentX
                val offset = metrics.offsetX

                if (range > extent) {
                    val maxScroll = range - extent
                    val ratio = offset.coerceIn(0, maxScroll).toFloat() / maxScroll
                    val hMargin = (ratio * maxMargin).toInt().coerceIn(0, maxMargin)
                    hScrollThumb.translationX = hMargin.toFloat()
                    hScrollThumb.invalidate()
                }
            }

            // Update Vertical Thumb based on WebView scroll
            val vTrackContainer = verticalScrollBar.getChildAt(1) as? FrameLayout
            val vTrackHeight =
                    when {
                        vTrackContainer != null && vTrackContainer.height > 0 ->
                                vTrackContainer.height
                        vTrackContainer != null && vTrackContainer.measuredHeight > 0 ->
                                vTrackContainer.measuredHeight
                        verticalScrollBar.height > 0 -> {
                            val topBtnHeight = verticalScrollBar.getChildAt(0)?.height ?: 0
                            val bottomBtnHeight = verticalScrollBar.getChildAt(2)?.height ?: 0
                            (verticalScrollBar.height - topBtnHeight - bottomBtnHeight)
                                    .coerceAtLeast(0)
                        }
                        else -> 0
                    }
            if (vTrackHeight > 0) {
                val thumbHeight = 60
                val maxMargin = vTrackHeight - thumbHeight

                val range = metrics.rangeY
                val extent = metrics.extentY
                val offset = metrics.offsetY

                if (range > extent) {
                    val maxScroll = range - extent
                    val ratio = offset.coerceIn(0, maxScroll).toFloat() / maxScroll
                    val vMargin = (ratio * maxMargin).toInt().coerceIn(0, maxMargin)
                    vScrollThumb.translationY = vMargin.toFloat()
                    vScrollThumb.invalidate()
                }
            }
        } else {
            // Existing logic for non-anchored (viewport pan)
            // Update horizontal thumb position
            val hTrackContainer = horizontalScrollBar.getChildAt(1) as? FrameLayout
            val hTrackWidth =
                    when {
                        hTrackContainer != null && hTrackContainer.width > 0 ->
                                hTrackContainer.width
                        hTrackContainer != null && hTrackContainer.measuredWidth > 0 ->
                                hTrackContainer.measuredWidth
                        horizontalScrollBar.width > 0 -> {
                            val leftBtnWidth = horizontalScrollBar.getChildAt(0)?.width ?: 0
                            val rightBtnWidth = horizontalScrollBar.getChildAt(2)?.width ?: 0
                            (horizontalScrollBar.width - leftBtnWidth - rightBtnWidth)
                                    .coerceAtLeast(0)
                        }
                        else -> 0
                    }
            if (hTrackWidth > 0) {
                val thumbWidth = 60
                val maxMargin = hTrackWidth - thumbWidth
                val hMargin = (xProgress / 100f * maxMargin).toInt()
                hScrollThumb.translationX = hMargin.toFloat()
                hScrollThumb.invalidate()
            }

            // Update vertical thumb position
            val vTrackContainer = verticalScrollBar.getChildAt(1) as? FrameLayout
            val vTrackHeight =
                    when {
                        vTrackContainer != null && vTrackContainer.height > 0 ->
                                vTrackContainer.height
                        vTrackContainer != null && vTrackContainer.measuredHeight > 0 ->
                                vTrackContainer.measuredHeight
                        verticalScrollBar.height > 0 -> {
                            val topBtnHeight = verticalScrollBar.getChildAt(0)?.height ?: 0
                            val bottomBtnHeight = verticalScrollBar.getChildAt(2)?.height ?: 0
                            (verticalScrollBar.height - topBtnHeight - bottomBtnHeight)
                                    .coerceAtLeast(0)
                        }
                        else -> 0
                    }
            if (vTrackHeight > 0) {
                val thumbHeight = 60
                val maxMargin = vTrackHeight - thumbHeight
                val vMargin = (yProgress / 100f * maxMargin).toInt()
                vScrollThumb.translationY = vMargin.toFloat()
                vScrollThumb.invalidate()
            }
        }
    }

    fun updateScrollBarsVisibility() {
        // DebugLog.d("ScrollDebug", "updateScrollBarsVisibility called. isAnchored=$isAnchored,
        // isInScrollMode=$isInScrollMode, uiScale=$uiScale")
        val now = SystemClock.uptimeMillis()
        // Check freeze state but don't return early - we need to update layout
        val isFrozen = shouldFreezeScrollBars() && !isInteractingWithScrollBar

        // Determine mode-specific base constraints
        val isScrollModeActive = isInScrollMode || isNavBarsHidden

        // Base dimensions
        val containerWidth = 640
        val baseLeftMargin = if (isScrollModeActive) 0 else toggleBarWidthPx
        val rawBottomMargin = if (isScrollModeActive) 0 else navBarHeightPx
        val keyboardVisible = keyboardContainer.visibility == View.VISIBLE
        if (keyboardVisible) {
            val keyboardWidth = 640 - toggleBarWidthPx
            keyboardContainer.measure(
                    MeasureSpec.makeMeasureSpec(keyboardWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
        val keyboardHeight =
                if (keyboardVisible) {
                    val measured = keyboardContainer.measuredHeight
                    if (measured > 0) measured else 160
                } else {
                    0
                }
        val baseBottomMargin = if (keyboardVisible) 0 else rawBottomMargin

        // If anchored, scrollbars are always hidden
        if (isAnchored) {
            lastHorzScrollableAt = 0L
            lastVertScrollableAt = 0L
            horizontalScrollBar.visibility = View.GONE
            verticalScrollBar.visibility = View.GONE

            (webViewsContainer.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
                var targetWidth: Int
                var targetHeight: Int
                if (isScrollModeActive) {
                    targetWidth = containerWidth
                    targetHeight = (480 - keyboardHeight).coerceAtLeast(0)
                } else {
                    targetWidth = containerWidth - baseLeftMargin
                    targetHeight = (480 - baseBottomMargin - keyboardHeight).coerceAtLeast(0)
                }

                var changed = false
                if (p.width != targetWidth) changed = true
                if (p.height != targetHeight) changed = true
                if (p.leftMargin != baseLeftMargin) changed = true
                if (p.rightMargin != 0) changed = true
                if (p.bottomMargin != baseBottomMargin) changed = true

                if (changed) {
                    p.width = targetWidth
                    p.height = targetHeight
                    p.leftMargin = baseLeftMargin
                    p.rightMargin = 0
                    p.bottomMargin = baseBottomMargin
                    webViewsContainer.layoutParams = p
                    webViewsContainer.requestLayout()
                    webViewsContainer.invalidate()
                }
            }
            return
        }

        // Hide scrollbars entirely on AR nav map pages (full-viewport 3D map)
        val currentUrl = webView.url ?: ""
        if (currentUrl.contains("ar_nav.html")) {
            horizontalScrollBar.visibility = View.GONE
            verticalScrollBar.visibility = View.GONE
            (webViewsContainer.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
                val targetWidth = if (isScrollModeActive) containerWidth else containerWidth - baseLeftMargin
                val targetHeight = (480 - baseBottomMargin - keyboardHeight).coerceAtLeast(0)
                if (p.width != targetWidth || p.height != targetHeight || p.rightMargin != 0) {
                    p.width = targetWidth
                    p.height = targetHeight
                    p.leftMargin = baseLeftMargin
                    p.rightMargin = 0
                    p.bottomMargin = baseBottomMargin
                    webViewsContainer.layoutParams = p
                    webViewsContainer.requestLayout()
                }
            }
            return
        }

        // Always check WebView scrollability since we disabled viewport panning
        val metrics = resolveScrollMetrics(now)
        val webHRange = metrics.rangeX
        val webHExtent = metrics.extentX
        val webVRange = metrics.rangeY
        val webVExtent = metrics.extentY
        val scrollDeltaThreshold = 1
        val webHDelta = webHRange - webHExtent
        val webVDelta = webVRange - webVExtent
        val showHorzRaw = webHDelta > scrollDeltaThreshold
        val showVertRaw = webVDelta > scrollDeltaThreshold
        if (showHorzRaw) {
            lastHorzScrollableAt = now
        }
        if (showVertRaw) {
            lastVertScrollableAt = now
        }
        val showHorz = showHorzRaw || (now - lastHorzScrollableAt < scrollBarHoldMs)
        val showVert = showVertRaw || (now - lastVertScrollableAt < scrollBarHoldMs)

        if (!isFrozen) {
            horizontalScrollBar.apply {
                visibility = if (showHorz) View.VISIBLE else View.INVISIBLE
                isClickable = showHorz
                isFocusable = false
            }

            verticalScrollBar.apply {
                visibility = if (showVert) View.VISIBLE else View.INVISIBLE
                isClickable = showVert
                isFocusable = false
            }
        }

        // Apply layout adjustments
        (webViewsContainer.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
            // Keep WebView sizing stable to avoid layout churn (prevents media pauses/flicker).
            val rightMarginShift = if (verticalScrollBar.visibility == View.VISIBLE) 20 else 0
            val bottomMarginShift = if (horizontalScrollBar.visibility == View.VISIBLE) 20 else 0

            var targetWidth: Int
            var targetHeight: Int
            var targetLeftMargin: Int
            var targetBottomMargin: Int
            var targetRightMargin: Int

            if (isScrollModeActive) {
                // Scroll Mode: 640 total width
                targetWidth = 640 - rightMarginShift
                targetHeight = (480 - bottomMarginShift - keyboardHeight).coerceAtLeast(0)
                targetLeftMargin = 0
                targetRightMargin = rightMarginShift
                targetBottomMargin = bottomMarginShift
            } else {
                // Normal Mode:
                // Width: 640 total - toggle bar - margin
                targetWidth = (640 - baseLeftMargin) - rightMarginShift

                // Height: 480 total - nav bar - margin
                // We must be explicit here so onMeasure picks it up
                targetHeight =
                        (480 - baseBottomMargin - bottomMarginShift - keyboardHeight).coerceAtLeast(
                                0
                        )

                targetLeftMargin = baseLeftMargin
                targetRightMargin = rightMarginShift
                targetBottomMargin = baseBottomMargin + bottomMarginShift
            }

            var changed = false
            if (p.width != targetWidth) changed = true
            if (p.height != targetHeight) changed = true
            if (p.leftMargin != targetLeftMargin) changed = true
            if (p.rightMargin != targetRightMargin) changed = true
            if (p.bottomMargin != targetBottomMargin) changed = true

            if (changed) {
                p.width = targetWidth
                p.height = targetHeight
                p.leftMargin = targetLeftMargin
                p.rightMargin = targetRightMargin
                p.bottomMargin = targetBottomMargin

                webViewsContainer.layoutParams = p
                // Force layout update on WebView itself to ensure it resizes
                webView.requestLayout()
                webViewsContainer.requestLayout()
                webViewsContainer.invalidate()
            }
        }

        // Remove unconditional requestLayout/invalidate here
        // webView.requestLayout()
        // webView.invalidate()

        if (horizontalScrollBar.visibility == View.VISIBLE ||
                        verticalScrollBar.visibility == View.VISIBLE
        ) {
            updateScrollBarThumbs(0, 0)
        }
    }

    fun updateExternalScrollMetrics(
            rangeX: Int,
            extentX: Int,
            offsetX: Int,
            rangeY: Int,
            extentY: Int,
            offsetY: Int
    ) {
        val now = SystemClock.uptimeMillis()
        externalScrollMetrics =
                ExternalScrollMetrics(
                        rangeX = rangeX.coerceAtLeast(0),
                        extentX = extentX.coerceAtLeast(0),
                        offsetX = offsetX.coerceAtLeast(0),
                        rangeY = rangeY.coerceAtLeast(0),
                        extentY = extentY.coerceAtLeast(0),
                        offsetY = offsetY.coerceAtLeast(0),
                        timestamp = now
                )

        if (!isAnchored && now - lastScrollBarCheckTime > scrollBarVisibilityThrottleMs) {
            updateScrollBarsVisibility()
            lastScrollBarCheckTime = now
        } else if (now - lastScrollBarInteractionTime >= 250L) {
            // Only update thumb position if not recently interacting with scrollbar
            updateScrollBarThumbs(0, 0)
        }
    }

    fun clearExternalScrollMetrics() {
        externalScrollMetrics = null
    }

    private fun resolveScrollMetrics(now: Long): ScrollMetrics {
        val webRangeX = webView.getHorizontalScrollRange()
        val webExtentX = webView.getHorizontalScrollExtent()
        val webOffsetX = webView.getHorizontalScrollOffset()
        val webRangeY = webView.getVerticalScrollRange()
        val webExtentY = webView.getVerticalScrollExtent()
        val webOffsetY = webView.getVerticalScrollOffset()

        val external =
                externalScrollMetrics?.takeIf { now - it.timestamp <= externalScrollMetricsStaleMs }
        if (external == null) {
            return ScrollMetrics(
                    rangeX = webRangeX,
                    extentX = webExtentX,
                    offsetX = webOffsetX,
                    rangeY = webRangeY,
                    extentY = webExtentY,
                    offsetY = webOffsetY
            )
        }

        // Use external metrics whenever available so nested scrollers can suppress bars correctly.
        val useExternalH = external.extentX > 0
        val useExternalV = external.extentY > 0
        return ScrollMetrics(
                rangeX = if (useExternalH) external.rangeX else webRangeX,
                extentX = if (useExternalH) external.extentX else webExtentX,
                offsetX = if (useExternalH) external.offsetX else webOffsetX,
                rangeY = if (useExternalV) external.rangeY else webRangeY,
                extentY = if (useExternalV) external.extentY else webExtentY,
                offsetY = if (useExternalV) external.offsetY else webOffsetY
        )
    }

    private fun shouldUseJsScroll(metrics: ScrollMetrics): Boolean {
        val now = SystemClock.uptimeMillis()
        val external =
                externalScrollMetrics?.takeIf { now - it.timestamp <= externalScrollMetricsStaleMs }
        return external != null &&
                (metrics.rangeX > metrics.extentX || metrics.rangeY > metrics.extentY)
    }

    private fun scrollWebViewByJs(left: Int?, top: Int?, smooth: Boolean, useScrollTo: Boolean) {
        val leftValue = left?.toString() ?: "undefined"
        val topValue = top?.toString() ?: "undefined"
        val behavior = if (smooth) "'smooth'" else "'auto'"
        val useScrollToJs = if (useScrollTo) "true" else "false"
        webView.evaluateJavascript(
                """
            (function() {
                var leftVal = $leftValue;
                var topVal = $topValue;
                var behavior = $behavior;
                var useScrollTo = $useScrollToJs;

                function isNumber(v) {
                    return typeof v === 'number' && !isNaN(v);
                }

                function scrollWindow() {
                    if (useScrollTo && typeof window.scrollTo === 'function') {
                        window.scrollTo({
                            left: isNumber(leftVal) ? leftVal : window.scrollX,
                            top: isNumber(topVal) ? topVal : window.scrollY,
                            behavior: behavior
                        });
                    } else if (!useScrollTo && typeof window.scrollBy === 'function') {
                        window.scrollBy({
                            left: isNumber(leftVal) ? leftVal : 0,
                            top: isNumber(topVal) ? topVal : 0,
                            behavior: behavior
                        });
                    } else if (typeof window.scrollTo === 'function') {
                        window.scrollTo({
                            left: isNumber(leftVal) ? leftVal : window.scrollX,
                            top: isNumber(topVal) ? topVal : window.scrollY,
                            behavior: behavior
                        });
                    }
                }

                function scrollElement(el) {
                    if (!el) {
                        scrollWindow();
                        return;
                    }
                    var hasScrollTo = typeof el.scrollTo === 'function';
                    var hasScrollBy = typeof el.scrollBy === 'function';
                    if (useScrollTo && hasScrollTo) {
                        el.scrollTo({
                            left: isNumber(leftVal) ? leftVal : el.scrollLeft,
                            top: isNumber(topVal) ? topVal : el.scrollTop,
                            behavior: behavior
                        });
                        return;
                    }
                    if (!useScrollTo && hasScrollBy) {
                        el.scrollBy({
                            left: isNumber(leftVal) ? leftVal : 0,
                            top: isNumber(topVal) ? topVal : 0,
                            behavior: behavior
                        });
                        return;
                    }

                    var targetLeft = isNumber(leftVal) ? leftVal : el.scrollLeft;
                    var targetTop = isNumber(topVal) ? topVal : el.scrollTop;
                    if (!useScrollTo) {
                        targetLeft = el.scrollLeft + (isNumber(leftVal) ? leftVal : 0);
                        targetTop = el.scrollTop + (isNumber(topVal) ? topVal : 0);
                    }
                    el.scrollLeft = targetLeft;
                    el.scrollTop = targetTop;
                }

                var target = window.__taplinkScrollTarget;
                var root = document.scrollingElement || document.documentElement || document.body;
                var isRoot = !target || target === root || target === document.documentElement || target === document.body;
                if (!isRoot && target && target.isConnected !== false) {
                    scrollElement(target);
                } else {
                    scrollWindow();
                }
            })();
        """,
                null
        )
    }

    private fun applyScrollbarTransform() {
        val scale = if (uiScale <= 0f) 1f else 1f / uiScale
        val transX = -leftEyeUIContainer.translationX
        val transY = -leftEyeUIContainer.translationY
        listOf(horizontalScrollBar, verticalScrollBar).forEach { bar ->
            bar.pivotX = 0f
            bar.pivotY = 0f
            bar.scaleX = scale
            bar.scaleY = scale
            bar.translationX = transX
            bar.translationY = transY
        }
    }

    private fun updateHorizontalScroll(percent: Float) {
        if (isWebViewScrollEnabled()) {
            val metrics = resolveScrollMetrics(SystemClock.uptimeMillis())
            val range = metrics.rangeX
            val extent = metrics.extentX
            if (range > extent) {
                val targetX = percent * (range - extent)
                if (shouldUseJsScroll(metrics)) {
                    scrollWebViewByJs(
                            left = targetX.toInt(),
                            top = null,
                            smooth = false,
                            useScrollTo = true
                    )
                } else {
                    webView.scrollTo(targetX.toInt(), webView.scrollY)
                }
            }
        } else {
            val newProgress = (percent * 100).toInt()
            val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("uiTransXProgress", newProgress).apply()
            updateUiTranslation()
        }
    }

    private fun updateVerticalScroll(percent: Float) {
        if (isWebViewScrollEnabled()) {
            val metrics = resolveScrollMetrics(SystemClock.uptimeMillis())
            val range = metrics.rangeY
            val extent = metrics.extentY
            if (range > extent) {
                val targetY = percent * (range - extent)
                if (shouldUseJsScroll(metrics)) {
                    scrollWebViewByJs(
                            left = null,
                            top = targetY.toInt(),
                            smooth = false,
                            useScrollTo = true
                    )
                } else {
                    webView.scrollTo(webView.scrollX, targetY.toInt())
                }
            }
        } else {
            val newProgress = (percent * 100).toInt()
            val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("uiTransYProgress", newProgress).apply()
            updateUiTranslation()
        }
    }

    // Function to update the cursor positions and visibility
    fun updateCursorPosition(x: Float, y: Float, isVisible: Boolean) {
        val currentTime = System.currentTimeMillis()
        lastCursorX = x
        lastCursorY = y

        if (!isAttachedToWindow) {
            return
        }

        if (currentTime - lastCursorUpdateTime >= CURSOR_UPDATE_INTERVAL) {
            if (isVisible) {
                // Convert cursor from container-local to screen coordinates
                val containerLocation = IntArray(2)
                getLocationOnScreen(containerLocation)

                // Account for UI scale and translation when calculating screen position
                // Visual cursor is scaled around (320, 240) and then translated (only in
                // non-anchored mode)
                val transX = if (isAnchored) 0f else leftEyeUIContainer.translationX
                val transY = if (isAnchored) 0f else leftEyeUIContainer.translationY

                val visualX = 320f + (x - 320f) * uiScale + transX
                val visualY = 240f + (y - 240f) * uiScale + transY

                val screenX = visualX + containerLocation[0]
                val screenY = visualY + containerLocation[1]

                // Pass screen coordinates - buttons also use screen coordinates
                updateButtonHoverStates(screenX, screenY)
            }
            listener?.onCursorPositionChanged(x, y, isVisible)
            lastCursorUpdateTime = currentTime
        }
    }

    private fun refreshHoverAtCurrentCursor() {
        if (!isAttachedToWindow) return

        val containerLocation = IntArray(2)
        getLocationOnScreen(containerLocation)

        val transX = if (isAnchored) 0f else leftEyeUIContainer.translationX
        val transY = if (isAnchored) 0f else leftEyeUIContainer.translationY

        val visualX = 320f + (lastCursorX - 320f) * uiScale + transX
        val visualY = 240f + (lastCursorY - 240f) * uiScale + transY

        val screenX = visualX + containerLocation[0]
        val screenY = visualY + containerLocation[1]

        updateButtonHoverStates(screenX, screenY)
    }

    fun updatePointerHover(screenX: Float, screenY: Float) {
        if (!isAttachedToWindow) return
        updateButtonHoverStates(screenX, screenY)
    }

    fun clearPointerHover() {
        if (!isAttachedToWindow) return
        clearAllHoverStates()
    }

    private var isScreenMasked = false
    private var isHostPaused = false
    private var isHoveringMaskToggle = false
    // WakeLock keeps CPU awake while screen is masked (projector off) so audio doesn't skip.
    // Without this, Android Doze will periodically sleep the CPU causing ~10s audio stutters.
    private val maskWakeLock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapInsight:MaskAudioPlayback")
    private val pausedMediaWakeLock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapInsight:PausedMediaPlayback")
    private val mediaWifiLock: WifiManager.WifiLock? =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.run {
            @Suppress("DEPRECATION")
            createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TapInsight:StreamingAudio")
        }
    private var maskOverlay: FrameLayout =
            FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                visibility = View.GONE
                layoutParams = LayoutParams(640, LayoutParams.MATCH_PARENT) // Left eye width only
                elevation = 1000f // Put it above everything except cursors
                isClickable = true
                isFocusable = true

                // Consume all touch events to prevent propagation to navbar/webview behind
                // and route taps into mask overlay controls.
                //
                // Tap model: on ACTION_DOWN, if the finger lands directly on the
                // Visualizer toggle button (small 52×52 target), fire immediately
                // — the glasses' input path would otherwise require drifting the
                // cursor before ACTION_UP registered. For everything else we still
                // wait for ACTION_UP with a generous slop+duration so any natural
                // tap qualifies.
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            maskOverlayTouchDownX = event.rawX
                            maskOverlayTouchDownY = event.rawY
                            maskOverlayTouchDownTime = SystemClock.uptimeMillis()
                            // Immediate fire for the tiny Visualizer toggle pill.
                            if (isTouchOnVisualizerToggle(event.rawX, event.rawY)) {
                                dispatchMaskOverlayTouch(event.rawX, event.rawY)
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            val dx = event.rawX - maskOverlayTouchDownX
                            val dy = event.rawY - maskOverlayTouchDownY
                            val distSq = dx * dx + dy * dy
                            val duration = SystemClock.uptimeMillis() - maskOverlayTouchDownTime
                            val isTap = distSq <= (maskOverlayTapSlopPx * maskOverlayTapSlopPx) && duration <= maskOverlayTapMaxDurationMs
                            if (isTap) {
                                dispatchMaskOverlayTouch(event.rawX, event.rawY)
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            // Reset touch state when parent cancels the touch sequence
                            maskOverlayTouchDownTime = 0L
                        }
                    }
                    true
                }
            }

    // Mask mode UI elements
    private lateinit var maskMediaControlsContainer: LinearLayout
    private lateinit var btnMaskPrevTrack: FontIconView // Skip to previous song
    private lateinit var btnMaskPrev: FontIconView // 10s back
    private lateinit var btnMaskPlay: FontIconView
    private lateinit var btnMaskPause: FontIconView
    private lateinit var btnMaskNext: FontIconView // 10s forward
    private lateinit var btnMaskNextTrack: FontIconView // Skip to next song
    private lateinit var btnMaskUnmask: ImageButton
    private lateinit var maskNowPlayingText: TextView
    private var lastMaskedDomTitle: String? = null
    private var lastMaskedDomTitleUrl: String? = null
    private var lastMaskedDomTitleAt: Long = 0L
    private val maskedDomTitleFreshMs = 15000L
    private val maskNowPlayingPeriodicRefresh: Runnable = object : Runnable {
        override fun run() {
            if (!isScreenMasked) return
            refreshMaskedNowPlayingFromJs()
            refreshMaskedNowPlaying()
            postDelayed(this, 5000L)
        }
    }
    private lateinit var btnVisualizerToggle: SpectrumVizButton
    private lateinit var maskVisualizerView: AudioVisualizerView
    private var isVisualizerVisible = false
    private var audioVisualizer: Visualizer? = null
    // Double-buffer for thread-safe FFT data: audio thread writes to back buffer,
    // UI thread reads from front buffer. References swapped atomically.
    @Volatile private var fftFrontBuffer = FloatArray(32)
    private var fftBackBuffer = FloatArray(32)
    private val fftMagnitudes: FloatArray get() = fftFrontBuffer  // read alias for UI thread
    private var lastVisualizerToggleTime = 0L
    private var lastVisualizerThemeTapTime = 0L
    private var maskOverlayTouchDownX = 0f
    private var maskOverlayTouchDownY = 0f
    private var maskOverlayTouchDownTime = 0L
    // Tap slop — generous enough that small cursor drift during a press
    // does not disqualify the tap on AR glasses input.
    private val maskOverlayTapSlopPx = 60f
    // Lightweight dedup for mask overlay touch dispatch — prevents the same physical tap
    // from being processed twice when multiple code paths fire within the same input cycle.
    // Short enough that intentional rapid taps (e.g. theme cycling, toggle on/off)
    // still register but long enough to swallow the immediate double-fire from
    // a paired ACTION_DOWN / ACTION_UP hitting the same button.
    private var lastMaskOverlayDispatchTime = 0L
    private val MASK_OVERLAY_DISPATCH_DEBOUNCE_MS = 140L
    // Allow slower taps — up to ~700ms — since the AR glasses' input pipeline
    // can add noticeable latency between ACTION_DOWN and ACTION_UP.
    private val maskOverlayTapMaxDurationMs = 700L

    // Fullscreen Mode UI elements
    private lateinit var fullScreenControlsContainer: FrameLayout
    private lateinit var fullScreenMediaControls: LinearLayout
    private var suppressFullscreenMediaControls = false
    private lateinit var btnFsPrevTrack: FontIconView
    private lateinit var btnFsPrev: FontIconView
    private lateinit var btnFsPlayPause: FontIconView // Single toggle button
    private var isFsPlaying: Boolean = false // Track play state
    private lateinit var btnFsNext: FontIconView
    private lateinit var btnFsNextTrack: FontIconView
    private lateinit var btnFsExit: FontIconView

    var anchorToggleListener: AnchorToggleListener? = null

    // Add properties to track translations
    private var _translationX = 0f
    private var _translationY = 0f
    private var _rotationZ = 0f

    private var isInScrollMode = false
    private var isNavBarsHidden = false // Tracks nav bar visibility independent of scroll mode
    private var settingsScrim: View? = null

    // Scroll bar containers for non-anchored mode
    private var horizontalScrollBar: LinearLayout
    private var verticalScrollBar: LinearLayout
    private var hScrollThumb: View
    private var vScrollThumb: View
    private var isInteractingWithScrollBar = false

    private var windowsOverviewContainer: android.widget.ScrollView? = null
    private var hoveredWindowsOverviewItem: View? = null

    fun showWindowsOverview() {
        DebugLog.d(
                "WindowsOverview",
                "showWindowsOverview called, windowsOverviewContainer=${windowsOverviewContainer != null}"
        )
        if (windowsOverviewContainer == null) {
            createWindowsOverviewUI()
            DebugLog.d("WindowsOverview", "Created windows overview UI")
        }

        // Populate container with current windows
        val container = windowsOverviewContainer?.getChildAt(0) as? LinearLayout
        if (container == null) {
            Log.e(
                    "WindowsOverview",
                    "Container is null! windowsOverviewContainer has ${windowsOverviewContainer?.childCount ?: 0} children"
            )
            return
        }
        DebugLog.d(
                "WindowsOverview",
                "Container found, clearing views. Windows count: ${windows.size}"
        )
        container.removeAllViews()
        hoveredWindowsOverviewItem = null

        // Add "Add Window" button at the top - shorter with label
        val addButton =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams =
                            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 50)
                                    .apply { bottomMargin = 12 }
                    // Create StateListDrawable for hover feedback
                    val normalBg =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#2A5298"))
                                cornerRadius = 12f
                            }
                    val hoveredBg =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#3A72C8"))
                                cornerRadius = 12f
                                setStroke(2, Color.parseColor("#6BAAFF"))
                            }
                    val pressedBg =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#4A82D8"))
                                cornerRadius = 12f
                            }
                    background =
                            android.graphics.drawable.StateListDrawable().apply {
                                addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
                                addState(intArrayOf(android.R.attr.state_hovered), hoveredBg)
                                addState(intArrayOf(), normalBg)
                            }
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { createNewWindow() }
                }

        val addIcon =
                FontIconView(context).apply {
                    setText(R.string.fa_plus)
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { rightMargin = 12 }
                }
        val addLabel =
                TextView(context).apply {
                    text = "Open New Tab"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                }
        addButton.addView(addIcon)
        addButton.addView(addLabel)
        container.addView(addButton)
        DebugLog.d("WindowsOverview", "Added 'Add Window' button")

        // Calculate item dimensions for 3-column grid with stretching
        // Container width = 608 - 32 (padding) = 576
        val itemMargin = 8
        val columnsPerRow = 3
        val itemHeight = 120 // Fixed height for items

        // Create rows for 3-column grid
        var currentRow: LinearLayout? = null
        windows.forEachIndexed { index, win ->
            DebugLog.d("WindowsOverview", "Adding window item: ${win.id}, title: ${win.title}")

            // Create new row every 3 items
            if (index % columnsPerRow == 0) {
                currentRow =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams =
                                    LinearLayout.LayoutParams(
                                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                            )
                                            .apply { bottomMargin = itemMargin }
                        }
                container.addView(currentRow)
            }

            // Calculate position in current row
            val positionInRow = index % columnsPerRow

            val item =
                    FrameLayout(context).apply {
                        // Use weight=1 so items stretch to fill available width
                        layoutParams =
                                LinearLayout.LayoutParams(0, itemHeight, 1f).apply {
                                    marginStart = if (positionInRow == 0) 0 else itemMargin / 2
                                    marginEnd =
                                            if (positionInRow == columnsPerRow - 1) 0
                                            else itemMargin / 2
                                }
                        background =
                                android.graphics.drawable.StateListDrawable().apply {
                                    val isActive = win.id == activeWindowId

                                    // Colors
                                    val normalBgColor =
                                            if (isActive) Color.parseColor("#444444")
                                            else Color.parseColor("#252525")
                                    val hoverBgColor =
                                            if (isActive) Color.parseColor("#555555")
                                            else Color.parseColor("#353535")
                                    val normalStrokeColor =
                                            if (isActive) Color.parseColor("#4488FF")
                                            else Color.parseColor("#404040")
                                    val hoverStrokeColor =
                                            if (isActive) Color.parseColor("#4488FF")
                                            else
                                                    Color.parseColor(
                                                            "#505050"
                                                    ) // Lighter stroke on hover for inactive

                                    val hoveredDrawable =
                                            GradientDrawable().apply {
                                                setColor(hoverBgColor)
                                                setStroke(2, hoverStrokeColor)
                                                cornerRadius = 12f
                                            }

                                    val normalDrawable =
                                            GradientDrawable().apply {
                                                setColor(normalBgColor)
                                                setStroke(2, normalStrokeColor)
                                                cornerRadius = 12f
                                            }

                                    addState(
                                            intArrayOf(android.R.attr.state_hovered),
                                            hoveredDrawable
                                    )
                                    addState(intArrayOf(), normalDrawable)
                                }
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { switchToWindow(win.id) }
                    }

            // Thumbnail (Placeholder or actual bitmap)
            val thumbView =
                    ImageView(context).apply {
                        if (win.thumbnail != null) {
                            setImageBitmap(win.thumbnail)
                        } else {
                            setBackgroundColor(Color.parseColor("#1A1A1A"))
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                FrameLayout.LayoutParams.MATCH_PARENT
                                        )
                                        .apply { setMargins(3, 3, 3, 3) }
                        alpha = 0.6f
                    }
            item.addView(thumbView)

            // Title - smaller text, truncated
            val titleView =
                    TextView(context).apply {
                        text = win.title.take(20) + if (win.title.length > 20) "..." else ""
                        textSize = 10f
                        setTextColor(Color.WHITE)
                        maxLines = 2
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                FrameLayout.LayoutParams.WRAP_CONTENT
                                        )
                                        .apply {
                                            gravity = Gravity.BOTTOM
                                            setMargins(6, 0, 6, 6)
                                        }
                        setShadowLayer(3f, 0f, 0f, Color.BLACK)
                    }
            item.addView(titleView)

            // Delete button - smaller
            val deleteBtn =
                    FontIconView(context).apply {
                        setText(R.string.fa_xmark)
                        textSize = 12f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        background =
                                GradientDrawable().apply {
                                    setColor(Color.parseColor("#CC333333"))
                                    shape = GradientDrawable.OVAL
                                }
                        layoutParams =
                                FrameLayout.LayoutParams(28, 28).apply {
                                    gravity = Gravity.TOP or Gravity.END
                                    topMargin = 4
                                    rightMargin = 4
                                }
                        setOnClickListener { closeWindow(win.id) }
                    }
            item.addView(deleteBtn)

            currentRow?.addView(item)
        }

        DebugLog.d(
                "WindowsOverview",
                "Setting container visible, total items: ${container.childCount}"
        )
        windowsOverviewContainer?.visibility = View.VISIBLE
        webView.visibility = View.GONE

        // Force the container to the front by removing and re-adding at the end
        // Preserve the layout params
        // Force the container to the front using bringToFront() instead of remove/add
        // which can cause layout state loss
        val params =
                windowsOverviewContainer?.layoutParams as? FrameLayout.LayoutParams
                        ?: FrameLayout.LayoutParams(640 - toggleBarWidthPx, 480 - navBarHeightPx)
                                .apply {
                                    leftMargin = toggleBarWidthPx
                                    // Explicitly set Gravity to avoid any ambiguity
                                    gravity = Gravity.TOP or Gravity.START
                                }

        // Ensure params are applied
        windowsOverviewContainer?.layoutParams = params

        windowsOverviewContainer?.bringToFront()

        requestLayout()
        invalidate()

        // Log layout info after layout pass
        windowsOverviewContainer?.post {
            val woc = windowsOverviewContainer
            DebugLog.d(
                    "WindowsOverview",
                    "Post-layout: width=${woc?.width}, height=${woc?.height}, " +
                            "x=${woc?.x}, y=${woc?.y}, visibility=${woc?.visibility}, " +
                            "layoutParams=${woc?.layoutParams?.width}x${woc?.layoutParams?.height}"
            )
            refreshHoverAtCurrentCursor()
        }
    }

    fun hideWindowsOverview() {
        windowsOverviewContainer?.visibility = View.GONE
        webView.visibility = View.VISIBLE
        requestLayout()
        invalidate()
    }

    private fun createWindowsOverviewUI() {
        DebugLog.d("WindowsOverview", "createWindowsOverviewUI called")
        // Use explicit dimensions since MATCH_PARENT wasn't resolving
        val containerWidth = 640 - toggleBarWidthPx // 608
        val containerHeight = 480 - navBarHeightPx // 448

        windowsOverviewContainer =
                android.widget.ScrollView(context).apply {
                    layoutParams =
                            FrameLayout.LayoutParams(containerWidth, containerHeight).apply {
                                leftMargin = toggleBarWidthPx
                                gravity = Gravity.TOP or Gravity.START
                            }
                    setBackgroundColor(Color.parseColor("#101010"))
                    visibility = View.GONE
                    elevation = 1500f
                    isFillViewport = true // Ensure content fills the viewport
                }
        DebugLog.d("WindowsOverview", "Container created: ${containerWidth}x${containerHeight}")

        val content =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    // Use ViewGroup.LayoutParams for ScrollView children
                    layoutParams =
                            ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                    setPadding(16, 16, 16, 16)
                    setBackgroundColor(Color.parseColor("#101010")) // Match parent background
                }

        windowsOverviewContainer?.addView(content)
        leftEyeUIContainer.addView(windowsOverviewContainer)
        DebugLog.d(
                "WindowsOverview",
                "UI created: ScrollView has ${windowsOverviewContainer?.childCount} children, added to leftEyeUIContainer (${leftEyeUIContainer.childCount} children)"
        )
    }

    fun toggleWindowMode() {
        if (windowsOverviewContainer?.visibility == View.VISIBLE) {
            hideWindowsOverview()
        } else {
            // Capture thumbnail of current window before showing overview
            val currentWin = windows.find { it.id == activeWindowId }
            if (currentWin != null) {
                try {
                    // Simple capture of the webview drawing cache or similar
                    // Using drawing cache is deprecated but works for simple needs, or
                    // PixelCopy/draw
                    // Here we'll use a simple draw to canvas if possible
                    val w = webView.width
                    val h = webView.height
                    if (w > 0 && h > 0) {
                        val bmp = Bitmap.createBitmap(w / 4, h / 4, Bitmap.Config.RGB_565)
                        val c = Canvas(bmp)
                        c.scale(0.25f, 0.25f)
                        webView.draw(c)
                        currentWin.thumbnail = bmp
                    }
                } catch (e: Exception) {
                    Log.e("Windows", "Failed to capture thumbnail", e)
                }
                currentWin.title = webView.title ?: "Tab"
            }
            showWindowsOverview()
        }
    }

    fun createNewWindow(loadDefaultUrl: Boolean = true): WebView {
        val newWebView = InternalWebView(context)
        configureWebView(newWebView)
        applyBrowsingModeToWebView(newWebView, isDesktopMode)
        // Popup windows supplied via WebViewTransport must be pristine (not pre-navigated).
        if (loadDefaultUrl) {
            newWebView.loadUrl(Constants.DEFAULT_URL)
        }
        val newWindow = BrowserWindow(webView = newWebView, title = "New Tab")

        // Add to container but invisible
        newWebView.visibility = View.INVISIBLE
        webViewsContainer.addView(
                newWebView,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        )

        // Notify MainActivity to configure the new WebView (clients, settings, etc.)
        windowCallback?.onWindowCreated(newWebView)

        windows.add(newWindow)
        switchToWindow(newWindow.id)
        saveAllWindowsState()
        return newWebView
    }

    fun resetToSingleWindow(loadDefaultUrl: Boolean = false): WebView {
        windows.toList().forEach { win ->
            try {
                win.webView.stopLoading()
            } catch (_: Exception) {}
            try {
                webViewsContainer.removeView(win.webView)
            } catch (_: Exception) {}
            try {
                win.webView.destroy()
            } catch (_: Exception) {}
            win.thumbnail?.recycle()
        }

        windows.clear()
        mediaStateByWindowId.clear()
        mediaLastPlayedAtByWindowId.clear()
        activeWindowId = null
        isMediaPlaying = false
        hideMediaControls()
        webViewsContainer.removeAllViews()

        val freshWebView = InternalWebView(context)
        configureWebView(freshWebView)
        applyBrowsingModeToWebView(freshWebView, isDesktopMode)
        if (loadDefaultUrl) {
            freshWebView.loadUrl(Constants.DEFAULT_URL)
        }

        val freshWindow = BrowserWindow(webView = freshWebView, title = "New Tab")
        freshWebView.visibility = View.VISIBLE
        webViewsContainer.addView(
                freshWebView,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        )
        windowCallback?.onWindowCreated(freshWebView)

        windows.add(freshWindow)
        activeWindowId = freshWindow.id
        webView = freshWebView
        updateScrollBarsVisibility()
        windowCallback?.onWindowSwitched(freshWebView)
        freshWebView.post { injectPageObservers(freshWebView) }
        startRefreshing()
        hideWindowsOverview()
        saveAllWindowsState()
        return freshWebView
    }

    fun switchToWindow(id: String) {
        val targetWindow = windows.find { it.id == id } ?: return

        if (activeWindowId == id) {
            // Already active, just hide overview if visible
            hideWindowsOverview()
            return
        }

        // Pause the old WebView to free CPU/network resources while inactive
        val oldWebView = webView
        oldWebView.visibility = View.INVISIBLE
        oldWebView.onPause()
        oldWebView.pauseTimers()

        // Switch active window
        activeWindowId = id
        webView = targetWindow.webView

        // Resume the new WebView and bring to front
        webView.resumeTimers()
        webView.onResume()
        webView.visibility = View.VISIBLE
        webView.bringToFront()

        // Ensure settings are applied (zoom, font size, etc.) which might be instance specific if
        // not global
        // MainActivity's setup should handle most, but we might need to re-apply UI specific things
        updateScrollBarsVisibility()

        // Notify callback
        windowCallback?.onWindowSwitched(webView)

        // Ensure observers exist for restored pages where onPageFinished may not fire.
        webView.post { injectPageObservers(webView) }
        // Ensure refresh loop is running (it might have died if previous webview was detached)
        startRefreshing()

        hideWindowsOverview()
        saveAllWindowsState()
    }

    fun closeWindow(id: String) {
        val windowToRemove = windows.find { it.id == id } ?: return

        // Don't close the last window, or create a new one if we do
        val wasActive = activeWindowId == id

        windows.remove(windowToRemove)
        mediaStateByWindowId.remove(id)
        mediaLastPlayedAtByWindowId.remove(id)
        webViewsContainer.removeView(windowToRemove.webView)
        windowToRemove.webView.destroy()
        windowToRemove.thumbnail?.recycle()

        if (windows.isEmpty()) {
            createNewWindow()
        } else if (wasActive) {
            // Switch to the last window in the list
            switchToWindow(windows.last().id)
            // If overview was open, refresh it
            if (windowsOverviewContainer?.visibility == View.VISIBLE) {
                showWindowsOverview()
            }
        } else {
            // If overview was open, refresh it
            if (windowsOverviewContainer?.visibility == View.VISIBLE) {
                showWindowsOverview()
            }
        }
        saveAllWindowsState()
        if (mediaStateByWindowId.isNotEmpty() || nativeTapRadioPlaying) {
            updateMediaState(anyTrackedMediaPlaying())
        } else {
            isMediaPlaying = false
            hideMediaControls()
        }
    }

    fun saveWindowMetadataState(forceSync: Boolean = false) {
        saveAllWindowsState(forceSync = forceSync, includeWebViewState = false)
    }

    fun saveAllWindowsState(forceSync: Boolean = false, includeWebViewState: Boolean = true) {
        try {
            val root = org.json.JSONObject()
            root.put("activeId", activeWindowId)
            root.put("isDesktopMode", isDesktopMode)

            val windowsArray = org.json.JSONArray()
            val maxStateSize = 500_000 // 500KB per window max

            windows.forEach { win ->
                // Update title from WebView if available
                if (!win.webView.title.isNullOrEmpty()) {
                    win.title = win.webView.title!!
                }

                val winObj = org.json.JSONObject()
                winObj.put("id", win.id)
                winObj.put("title", win.title)
                winObj.put("url", win.webView.url ?: "")

                if (includeWebViewState) {
                    // Save full WebView state (history, etc) - with size limit
                    try {
                        val state = Bundle()
                        win.webView.saveState(state)
                        val parcel = Parcel.obtain()
                        state.writeToParcel(parcel, 0)
                        val bytes = parcel.marshall()
                        parcel.recycle()

                        // Only save state if under size limit
                        if (bytes.size < maxStateSize) {
                            val stateString = Base64.encodeToString(bytes, Base64.DEFAULT)
                            winObj.put("state", stateString)
                        } else {
                            Log.w(
                                    "Persistence",
                                    "Window ${win.id} state too large (${bytes.size} bytes), skipping state save"
                            )
                            // Don't save state, just URL - will reload on restore
                        }
                    } catch (e: Exception) {
                        Log.e("Persistence", "Error saving state for window ${win.id}", e)
                        // Continue without state for this window
                    }
                }

                windowsArray.put(winObj)
            }
            root.put("windows", windowsArray)

            // Final size check before saving
            val jsonString = root.toString()
            if (jsonString.length > 5_000_000) { // 5MB total limit
                Log.e(
                        "Persistence",
                        "Total state size too large (${jsonString.length} chars), clearing old state"
                )
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .remove(KEY_WINDOWS_STATE)
                        .apply()
                return
            }

            val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_WINDOWS_STATE, jsonString)
            if (forceSync) {
                editor.commit()
            } else {
                editor.apply()
            }

            DebugLog.d(
                    "Persistence",
                    "Saved ${windows.size} windows with${if (includeWebViewState) "" else "out"} bundles (${jsonString.length} chars)"
            )
        } catch (e: Exception) {
            Log.e("Persistence", "Error saving window state", e)
        }
    }

    fun restoreState() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_WINDOWS_STATE, null)

            if (jsonString.isNullOrEmpty()) {
                if (windows.isEmpty()) {
                    createNewWindow()
                }
                return
            }

            val root = org.json.JSONObject(jsonString)
            val savedActiveId = if (root.has("activeId")) root.getString("activeId") else null
            val restoredDesktopMode = root.optBoolean("isDesktopMode", isDesktopMode)
            isDesktopMode = restoredDesktopMode
            prefs.edit().putBoolean("isDesktopMode", isDesktopMode).apply()
            val windowsArray = root.optJSONArray("windows")

            if (windowsArray != null && windowsArray.length() > 0) {
                // Clear existing windows (default one)
                windows.toList().forEach {
                    it.webView.destroy()
                    it.thumbnail?.recycle()
                }
                windows.clear()

                webViewsContainer.removeAllViews()

                for (i in 0 until windowsArray.length()) {
                    val winObj = windowsArray.getJSONObject(i)
                    val id = winObj.getString("id")
                    val title = winObj.getString("title")
                    val url = winObj.getString("url")
                    val stateString = winObj.optString("state", "")

                    val newWebView = InternalWebView(context)
                    configureWebView(newWebView)
                    applyBrowsingModeToWebView(newWebView, isDesktopMode)
                    // Important: notify MainActivity to attach its logic
                    windowCallback?.onWindowCreated(newWebView)

                    var restored = false
                    if (stateString.isNotEmpty()) {
                        try {
                            val bytes = Base64.decode(stateString, Base64.DEFAULT)
                            val parcel = Parcel.obtain()
                            parcel.unmarshall(bytes, 0, bytes.size)
                            parcel.setDataPosition(0)
                            val state = Bundle()
                            state.readFromParcel(parcel)
                            parcel.recycle()
                            // Restore state returns the WebBackForwardList but we don't need it
                            // explicitly
                            newWebView.restoreState(state)
                            restored = true
                        } catch (e: Exception) {
                            Log.e("Persistence", "Failed to restore webview bundle", e)
                        }
                    }

                    if (!restored) {
                        if (url.isNotEmpty()) {
                            newWebView.loadUrl(url)
                        } else {
                            newWebView.loadUrl(Constants.DEFAULT_URL)
                        }
                    }

                    val win = BrowserWindow(id = id, webView = newWebView, title = title)
                    windows.add(win)

                    // Add to container
                    newWebView.visibility = View.INVISIBLE
                    webViewsContainer.addView(
                            newWebView,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }

                if (windows.isNotEmpty()) {
                    val targetId =
                            if (savedActiveId != null && windows.any { it.id == savedActiveId }) {
                                savedActiveId
                            } else {
                                windows.last().id
                            }
                    switchToWindow(targetId)
                    syncBrowsingModeUi()
                } else {
                    // Fallback if parsing failed
                    createNewWindow()
                }
            } else if (windows.isEmpty()) {
                createNewWindow()
            }
        } catch (e: Exception) {
            Log.e("Persistence", "Error restoring window state", e)
            // Restore default if failed
            if (windows.isEmpty()) createNewWindow()
        }
    }

    private fun configureWebView(webView: WebView) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        @Suppress("DEPRECATION") // Suppress for extensive database usage
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false

        webView.addJavascriptInterface(MediaInterface(this, webView), "MediaInterface")

        // On-glasses Media Library JS bridge. Per-WebView urlRef so each
        // tab's trust gate tracks that tab's current URL — the bridge is
        // called from JS on a background thread and can't touch WebView
        // state, so we stash the last-known URL here from the lifecycle
        // callbacks below. MediaFileInterceptor shares the bridge's
        // MediaLibraryService instance so /media/… URL resolution goes
        // through the exact same safe-path logic as the JS bridge.
        val mediaBridgeUrlRef = AtomicReference("")
        // Gemini 3.1 TTS client for on-glasses text-to-speech. Reads the API
        // key out of "visionclaw_prefs" / "gemini_api_key" so it matches
        // whatever the companion app has saved. Passed into the bridge so
        // MediaLibraryBridge.speakText() has a synth to delegate to.
        val ttsClient = com.TapLink.app.media.GlassesTtsClient(
            apiKeyProvider = { com.TapLink.app.media.resolveGlassesGeminiKey(context) }
        )
        val mediaLibraryBridge = MediaLibraryBridge(context, mediaBridgeUrlRef, ttsClient)
        val mediaFileInterceptor = MediaFileInterceptor(context, mediaLibraryBridge.service)
        webView.addJavascriptInterface(mediaLibraryBridge, MediaLibraryBridge.JS_NAME)
        // Async TTS back-channel: wraps evaluateJavascript in webView.post so
        // the worker-thread synth can safely post completion events back into
        // JS without touching the WebView from the wrong thread.
        mediaLibraryBridge.jsEvaluator = { js ->
            webView.post { webView.evaluateJavascript(js, null) }
        }

        // Keep WebAppInterface for referencing context/logic if needed, but primary comms via URL
        // scheme
        // Enable Native Bridge for Chat
        // GroqBridge removed

        webView.webViewClient =
                object : android.webkit.WebViewClient() {
                    /**
                     * Intercept requests to the virtual media host
                     * (appassets.androidplatform.net/media/...) and serve
                     * directly from the on-glasses Media/ folder with
                     * Range-request support. Everything else flows through
                     * normal WebView networking.
                     */
                    override fun shouldInterceptRequest(
                        view: android.webkit.WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val resp = mediaFileInterceptor.handle(request)
                        if (resp != null) return resp
                        return super.shouldInterceptRequest(view, request)
                    }

                    /**
                     * Log any load failure on our virtual host so we can tell
                     * whether a spinner hang is "interceptor never responded"
                     * vs "page loaded but the audio tag stalled".
                     */
                    override fun onReceivedError(
                        view: android.webkit.WebView?,
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
                        view: android.webkit.WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
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

                    override fun shouldOverrideUrlLoading(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        DebugLog.d("GroqUrl", "Checking URL: $url")

                        if (url.startsWith("taplink://chat")) {
                            DebugLog.d("GroqUrl", "Intercepted taplink://chat")
                            val uri = android.net.Uri.parse(url)
                            val msg = uri.getQueryParameter("msg")
                            val history = uri.getQueryParameter("history")

                            if (msg != null && view != null) {
                                // Use the top-level WebAppInterface class we created
                                WebAppInterface(context, view).chatWithGroq(msg, history ?: "[]")
                            }
                            return true
                        }
                        // Intercept media file links → open in TapInsight media player
                        if (view != null && interceptMediaUrl(view, url)) return true
                        return false
                    }

                    override fun onPageStarted(
                            view: android.webkit.WebView?,
                            url: String?,
                            favicon: Bitmap?
                    ) {
                        super.onPageStarted(view, url, favicon)
                        // Tell the JS-bridge trust gate which page we're on.
                        mediaBridgeUrlRef.set(url ?: "")
                        clearExternalScrollMetrics()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(
                            view: android.webkit.WebView?,
                            url: String?
                    ): Boolean {
                        DebugLog.d("GroqUrl", "Checking URL (deprecated): $url")
                        if (url != null && url.startsWith("taplink://chat")) {
                            DebugLog.d("GroqUrl", "Intercepted taplink://chat (deprecated)")
                            val uri = android.net.Uri.parse(url)
                            val msg = uri.getQueryParameter("msg")
                            val history = uri.getQueryParameter("history")

                            if (msg != null && view != null) {
                                WebAppInterface(context, view).chatWithGroq(msg, history ?: "[]")
                            }
                            return true
                        }
                        if (url != null && view != null && interceptMediaUrl(view, url)) return true
                        return false
                    }

                    /**
                     * Trust the TapInsight companion server's self-signed certificate
                     * *only* when the URL is on the loopback interface (127.0.0.1,
                     * [::1] or localhost).  Without this override, top-level navigations
                     * from the dashboard to https://127.0.0.1:19110/library — and fetch()
                     * calls from media_player.html — fail with a blank screen because
                     * the WebView rejects the cert.  The key is generated inside this
                     * app's private files dir, so anything reachable only on loopback
                     * is by definition served by us.
                     */
                    override fun onReceivedSslError(
                        view: android.webkit.WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?
                    ) {
                        val host = error?.url?.let { android.net.Uri.parse(it).host }?.lowercase() ?: ""
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

                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        mediaBridgeUrlRef.set(url ?: "")
                        try {
                            view?.let { injectPageObservers(it) }
                            updateScrollBarsVisibility()

                            // Record the URL the WebView is actually showing.
                            // This is the ground-truth URL that TapClaw tools
                            // need when the user later says "email me this
                            // video" — Gemini cannot see this page, so it
                            // would otherwise invent a URL from training
                            // memory.  We record YouTube watch, search, and
                            // asset pages; LastUrlBridge classifies the URL
                            // and skips obvious duplicates / blank pages.
                            LastUrlBridge.record(
                                context = view?.context,
                                url = url,
                                title = view?.title
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("TapLink", "Error in onPageFinished", e)
                        }
                    }
                }

        webView.apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            layoutParams = LayoutParams(640, LayoutParams.MATCH_PARENT)
            setOnTouchListener { _, _ -> keyboardContainer.visibility == View.VISIBLE }
            setOnLongClickListener { true }

            setOnScrollChangeListener { _, _, _, _, _ ->
                if (isWebViewScrollEnabled()) {
                    updateScrollBarThumbs(0, 0)
                    val now = System.currentTimeMillis()
                    if (now - lastScrollBarCheckTime > scrollBarVisibilityThrottleMs) {
                        updateScrollBarsVisibility()
                        lastScrollBarCheckTime = now
                    }
                }
            }
        }
    }

    init {
        // Initialize the first WebView
        // Initial WebView configuration
        val initialWebView = InternalWebView(context)
        webView = initialWebView
        configureWebView(webView) // Local basic config
        mobileUserAgent = webView.settings.userAgentString
        desktopUserAgent = buildDesktopUserAgentFromMobile(mobileUserAgent)

        // CRITICAL FIX: Do NOT add the initial webview to the container or windows list yet.
        // This prevents the "Dashboard flash" on startup.
        // The container starts empty.
        // restoreState() will either:
        // 1. Restore saved windows (and set active one)
        // 2. Or call createNewWindow() calls which will add a window and load the default URL.

        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        isDesktopMode = prefs.getBoolean("isDesktopMode", false)
        currentWebZoom = prefs.getFloat("webZoomLevel", 1.0f)
        updateBrowsingMode(isDesktopMode)

        // Set the background of the entire DualWebViewGroup to black
        setBackgroundColor(Color.BLACK)

        // Ensure the left eye (Activity Window) uses the same pixel format as the right eye
        // (SurfaceView)
        // This ensures consistent color saturation between both eyes.
        (context as? Activity)?.window?.setFormat(PixelFormat.RGBA_8888)

        fullScreenOverlayContainer.setOnTouchListener { _, event ->
            if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
                fullScreenTapDetector.onTouchEvent(event)
                true
            } else {
                false
            }
        }

        // Initial WebView configuration moved to configureWebView() and MainActivity

        // Configure SurfaceView for right eye mirroring
        rightEyeView.apply {
            isClickable = false
            layoutParams = LayoutParams(640, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            holder.setFormat(PixelFormat.RGBA_8888)
            holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            setupBitmap(width, height)
                            startRefreshing()
                        }

                        override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                        ) {
                            setupBitmap(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            synchronized(bitmapLock) {
                                val currentBitmap = bitmap
                                bitmap = null // Set to null first
                                currentBitmap?.let { bmp ->
                                    if (!bmp.isRecycled) {
                                        bmp.recycle()
                                    }
                                }
                            }
                            stopRefreshing()
                        }
                    }
            )
        }

        // Initialize keyboard containers
        keyboardContainer.apply {
            visibility = View.GONE
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                // DebugLog.d("KeyboardDebug", "leftKeyboardContainer clicked")
            }
            setOnTouchListener { _, _ ->
                // DebugLog.d("KeyboardDebug", "leftKeyboardContainer received touch event:
                // ${event.action}")
                true
            }
        }

        // Initialize navigation bars
        leftNavigationBar =
                LayoutInflater.from(context).inflate(R.layout.navigation_bar, this, false).apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, navBarHeightPx)
                    setBackgroundColor(Color.parseColor("#202020"))
                    visibility = View.VISIBLE
                    setPadding(16, 0, 16, 0)
                }

        // Initialize navigation buttons
        navButtons =
                mapOf(
                        "back" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnBack),
                                        right = leftNavigationBar.findViewById(R.id.btnBack)
                                ),
                        "forward" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnForward),
                                        right = leftNavigationBar.findViewById(R.id.btnForward)
                                ),
                        "home" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnHome),
                                        right = leftNavigationBar.findViewById(R.id.btnHome)
                                ),
                        "link" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnLink),
                                        right = leftNavigationBar.findViewById(R.id.btnLink)
                                ),
                        "settings" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnSettings),
                                        right = leftNavigationBar.findViewById(R.id.btnSettings)
                                ),
                        "refresh" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnRefresh),
                                        right = leftNavigationBar.findViewById(R.id.btnRefresh)
                                ),
                        "hide" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnHide),
                                        right = leftNavigationBar.findViewById(R.id.btnHide)
                                ),
                        "quit" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnQuit),
                                        right = leftNavigationBar.findViewById(R.id.btnQuit)
                                ),
                        "chat" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnChat),
                                        right = leftNavigationBar.findViewById(R.id.btnChat)
                                )
                )

        // Initialize all buttons with same base properties
        navButtons.values.forEach { navButton ->
            navButton.left.apply {
                visibility = View.VISIBLE
                isClickable = true
                isFocusable = true
            }
            navButton.right.apply {
                visibility = View.VISIBLE
                isClickable = true
                isFocusable = true
            }
        }

        // Ensure physical pointer clicks (mouse/touch) work on all nav buttons, not only via
        // cursor hit-testing.
        navButtons.forEach { (key, navButton) ->
            navButton.left.setOnClickListener { triggerNavigationAction(key, navButton) }
            if (navButton.right !== navButton.left) {
                navButton.right.setOnClickListener { triggerNavigationAction(key, navButton) }
            }
        }

        // Initialize left toggle bar
        leftToggleBar =
                LayoutInflater.from(context).inflate(R.layout.toggle_bar, this, false).apply {
                    layoutParams = LayoutParams(toggleBarWidthPx, 480 - navBarHeightPx)
                    setBackgroundColor(Color.parseColor("#202020"))
                    visibility = View.VISIBLE
                    clipToOutline = true // Add this
                    clipChildren = true // Add this
                    isClickable = true // Add this
                    isFocusable = true // Add this
                }

        // DebugLog.d("ViewDebug", "Toggle bar initialized with hash: ${leftToggleBar.hashCode()}")

        setupMaskOverlayUI()
        setupFullScreenControlsUI()

        // Set background styles - use gradient drawables for modern look
        setBackgroundColor(Color.BLACK)
        leftNavigationBar.background =
                ContextCompat.getDrawable(context, R.drawable.nav_bar_background)
        leftToggleBar.background =
                ContextCompat.getDrawable(context, R.drawable.toggle_bar_background)

        // Set up the toggle buttons with explicit configurations
        leftToggleBar.findViewById<FontIconView>(R.id.btnModeToggle).apply {
            configureToggleButton(R.string.fa_mobile_screen)
        }

        leftToggleBar.findViewById<FontIconView>(R.id.btnYouTube).apply {
            configureToggleButton(R.string.fa_glasses)
        }

        leftToggleBar.findViewById<FontIconView>(R.id.btnBookmarks).apply {
            visibility = View.VISIBLE
            setText(R.string.fa_bookmark)
            setBackgroundResource(R.drawable.nav_button_background)
            gravity = android.view.Gravity.CENTER
            setPadding(8, 8, 8, 8)
            alpha = 1.0f
            elevation = 2f
            stateListAnimator = null
        }

        // Initialize URL EditTexts
        urlEditText = setupUrlEditText(true)

        // Bring urlEditTextLeft to front
        urlEditText.bringToFront()

        // Disable text handles for both EditTexts
        disableTextHandles(urlEditText)

        //

        // Initialize the edit fields
        leftEditField =
                EditText(context).apply {
                    layoutParams =
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    setBackgroundColor(Color.parseColor("#303030"))
                    setTextColor(Color.WHITE)
                    visibility = View.GONE
                    setPadding(16, 12, 16, 12)

                    // Style the edit field
                    background =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#303030"))
                                setStroke(2, Color.parseColor("#404040"))
                                cornerRadius = 8f
                            }
                }

        rightEditField =
                EditText(context).apply {
                    // Same styling as leftEditField
                    layoutParams =
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    setBackgroundColor(Color.parseColor("#303030"))
                    setTextColor(Color.WHITE)
                    visibility = View.GONE
                    setPadding(16, 12, 16, 12)
                    background =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#303030"))
                                setStroke(2, Color.parseColor("#404040"))
                                cornerRadius = 8f
                            }
                }

        // Add edit fields to view hierarchy
        addView(leftEditField)
        addView(rightEditField)

        leftSystemInfoView =
                SystemInfoView(context).apply {
                    layoutParams =
                            LayoutParams(
                                            200, // Fixed initial width, will be adjusted after
                                            // measure
                                            24
                                    )
                                    .apply { gravity = Gravity.TOP or Gravity.END }
                    elevation = 900f
                    visibility = View.VISIBLE // Explicitly set visibility
                }

        viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                }
        )

        // Make sure they're above other elements
        leftSystemInfoView.bringToFront()

        post {
            // Ensure bookmarks views are always on top when added to view hierarchy
            if (::leftBookmarksView.isInitialized) {}
            if (::chatView.isInitialized) {
                chatView.bringToFront()
            }
        }

        // Set up the container hierarchy
        leftEyeClipParent.addView(leftEyeUIContainer)
        leftEyeClipParent.addView(
                fullScreenOverlayContainer
        ) // Add to clip parent for proper clipping

        // Add views to UI container
        leftEyeUIContainer.apply {
            // Add views in the correct z-order
            // Add webViewsContainer with correct position
            addView(
                    webViewsContainer,
                    FrameLayout.LayoutParams(640 - toggleBarWidthPx, LayoutParams.MATCH_PARENT)
                            .apply {
                                leftMargin = toggleBarWidthPx // Position after toggle bar
                                bottomMargin = navBarHeightPx // Account for nav bar
                                gravity = Gravity.TOP or Gravity.START
                            }
            )
            addView(leftToggleBar)
            // DebugLog.d("ViewDebug", "Toggle bar added to UI container with hash:
            // ${leftToggleBar.hashCode()}")

            addView(leftNavigationBar.apply { elevation = 101f })
            addView(btnShowNavBars) // Add show nav bars button
            addView(progressBar) // Add progress bar
            addView(keyboardContainer)
            addView(dialogContainer)
            addView(leftSystemInfoView)
            addView(urlEditText)
            addView(
                    maskOverlay
            ) // Add mask overlay for proper mirroring to both eyes // Add mask overlay for proper
            // mirroring to both eyes

            // Initialize ChatView here
            chatView =
                    ChatView(context).apply {
                        layoutParams =
                                FrameLayout.LayoutParams(560, 420)
                                        .apply { // Slightly smaller than full window
                                            gravity = Gravity.CENTER
                                        }
                        visibility = View.GONE
                        elevation = 2000f // High elevation
                        keyboardListener = this@DualWebViewGroup.keyboardListener
                    }
            addView(chatView)
            chatView.disableSystemKeyboard()

            // Setup listener for Chat button
            leftNavigationBar.findViewById<View>(R.id.btnChat)?.setOnClickListener { toggleChat() }
            postDelayed(
                    {
                        initializeToggleButtons()
                        requestLayout()
                        invalidate()
                    },
                    100
            )

            post {
                leftSystemInfoView.measure(
                        MeasureSpec.makeMeasureSpec(640, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(24, MeasureSpec.EXACTLY)
                )
                updateSystemInfoBarVisibility()
                leftSystemInfoView.requestLayout()
                leftSystemInfoView.invalidate()
            }

            // Make sure container is visible and properly layered
            visibility = View.VISIBLE
            elevation = 100f // Keep it above webview
        }

        // After other view initializations

        // Add the clip parent to the main view
        addView(leftEyeClipParent)
        // REFACTORED: rightEyeView no longer added - single viewport mode
        // addView(rightEyeView) // Keep right eye view separate
        // maskOverlay now added to leftEyeUIContainer above for proper mirroring

        // Create horizontal scroll bar
        horizontalScrollBar =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundColor(Color.TRANSPARENT) // Transparent background
                    visibility = View.GONE
                    elevation = 150f
                    isClickable = true // Prevent click propagation
                    isFocusable = false
                    isFocusableInTouchMode = false

                    // Left arrow button
                    // Left arrow button
                    val btnLeft =
                            FontIconView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 20)
                                setText(R.string.fa_arrow_left)
                                setBackgroundResource(R.drawable.scroll_button_background)
                                gravity = Gravity.CENTER
                                textSize = 10f
                                setPadding(0, 0, 0, 0)
                            }
                    addView(btnLeft)

                    // Track container with thumb
                    val trackContainer =
                            FrameLayout(context).apply {
                                layoutParams = LinearLayout.LayoutParams(0, 20, 1f)
                                setBackgroundColor(Color.parseColor("#303030"))
                            }
                    hScrollThumb =
                            View(context).apply {
                                layoutParams =
                                        FrameLayout.LayoutParams(60, 16).apply {
                                            gravity = Gravity.CENTER_VERTICAL
                                            leftMargin = 0
                                        }
                                setBackgroundResource(R.drawable.scroll_button_background)
                            }
                    trackContainer.addView(hScrollThumb)
                    addView(trackContainer)

                    // Right arrow button
                    // Right arrow button
                    val btnRight =
                            FontIconView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 20)
                                setText(R.string.fa_arrow_right)
                                setBackgroundResource(R.drawable.scroll_button_background)
                                gravity = Gravity.CENTER
                                textSize = 10f
                                setPadding(0, 0, 0, 0)
                            }
                    addView(btnRight)

                    // Click handlers
                    btnLeft.setOnClickListener { scrollPageHorizontal(-10) }
                    btnRight.setOnClickListener { scrollPageHorizontal(10) }
                    trackContainer.setOnTouchListener { v, event ->
                        val fullWidth = v.width
                        val thumbWidth = hScrollThumb.width
                        val trackableWidth = fullWidth - thumbWidth

                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                isInteractingWithScrollBar = true
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                v.parent.requestDisallowInterceptTouchEvent(true)
                                // Immediate jump on touch down
                                val clickX = event.x
                                val clickLeft = clickX - thumbWidth / 2
                                val percent = (clickLeft / trackableWidth).coerceIn(0f, 1f)
                                updateHorizontalScroll(percent)

                                // Optimistic visual update
                                val hMargin = (percent * trackableWidth).toInt()
                                hScrollThumb.translationX = hMargin.toFloat()
                                hScrollThumb.invalidate()
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                val clickX = event.x
                                val clickLeft = clickX - thumbWidth / 2
                                val percent = (clickLeft / trackableWidth).coerceIn(0f, 1f)
                                updateHorizontalScroll(percent)

                                // Optimistic visual update
                                val hMargin = (percent * trackableWidth).toInt()
                                hScrollThumb.translationX = hMargin.toFloat()
                                hScrollThumb.invalidate()
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                isInteractingWithScrollBar = false
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                v.parent.requestDisallowInterceptTouchEvent(false)
                                updateScrollBarThumbs(0, 0)
                                true
                            }
                            else -> false
                        }
                    }
                }

        // Create vertical scroll bar
        verticalScrollBar =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.TRANSPARENT) // Transparent background
                    visibility = View.GONE
                    elevation = 150f
                    isClickable = true // Prevent click propagation
                    isFocusable = false
                    isFocusableInTouchMode = false

                    // Up arrow button
                    // Up arrow button
                    val btnUp =
                            FontIconView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 20)
                                setText(R.string.fa_arrow_up)
                                setBackgroundResource(R.drawable.scroll_button_background)
                                gravity = Gravity.CENTER
                                textSize = 10f
                                setPadding(0, 0, 0, 0)
                            }
                    addView(btnUp)

                    // Track container with thumb
                    val trackContainer =
                            FrameLayout(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 0, 1f)
                                setBackgroundColor(Color.parseColor("#303030"))
                            }
                    vScrollThumb =
                            View(context).apply {
                                layoutParams =
                                        FrameLayout.LayoutParams(16, 60).apply {
                                            gravity = Gravity.CENTER_HORIZONTAL
                                            topMargin = 0
                                        }
                                setBackgroundResource(R.drawable.scroll_button_background)
                            }
                    trackContainer.addView(vScrollThumb)
                    addView(trackContainer)

                    // Down arrow button
                    // Down arrow button
                    val btnDown =
                            FontIconView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 20)
                                setText(R.string.fa_arrow_down)
                                setBackgroundResource(R.drawable.scroll_button_background)
                                gravity = Gravity.CENTER
                                textSize = 10f
                                setPadding(0, 0, 0, 0)
                            }
                    addView(btnDown)

                    // Click handlers
                    btnUp.setOnClickListener { scrollPageVertical(-10) }
                    btnDown.setOnClickListener { scrollPageVertical(10) }
                    trackContainer.setOnTouchListener { v, event ->
                        val fullHeight = v.height
                        val thumbHeight = vScrollThumb.height
                        val trackableHeight = fullHeight - thumbHeight

                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                isInteractingWithScrollBar = true
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                v.parent.requestDisallowInterceptTouchEvent(true)
                                // Immediate jump on touch down
                                val clickY = event.y
                                val clickTop = clickY - thumbHeight / 2
                                val percent = (clickTop / trackableHeight).coerceIn(0f, 1f)

                                DebugLog.d(
                                        "ScrollDebug",
                                        "Vertical Down: y=$clickY, height=$fullHeight, percent=$percent"
                                )

                                updateVerticalScroll(percent)

                                // Optimistic visual update
                                val vMargin = (percent * trackableHeight).toInt()
                                vScrollThumb.translationY = vMargin.toFloat()
                                vScrollThumb.invalidate()
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                val clickY = event.y
                                val clickTop = clickY - thumbHeight / 2
                                val percent = (clickTop / trackableHeight).coerceIn(0f, 1f)

                                // DebugLog.d("ScrollDebug", "Vertical Move: y=$clickY,
                                // percent=$percent")

                                updateVerticalScroll(percent)

                                // Optimistic visual update
                                val vMargin = (percent * trackableHeight).toInt()
                                vScrollThumb.translationY = vMargin.toFloat()
                                vScrollThumb.invalidate()
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                isInteractingWithScrollBar = false
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                v.parent.requestDisallowInterceptTouchEvent(false)
                                updateScrollBarThumbs(0, 0)
                                true
                            }
                            else -> false
                        }
                    }
                }

        // Add scroll bars to UI container
        leftEyeUIContainer.addView(
                horizontalScrollBar,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 20).apply {
                    gravity = Gravity.BOTTOM
                    leftMargin = toggleBarWidthPx
                    rightMargin = 20 // Prevent overlap with vertical scroll bar
                    bottomMargin = navBarHeightPx // Sit on top of the nav bar
                }
        )
        leftEyeUIContainer.addView(
                verticalScrollBar,
                FrameLayout.LayoutParams(20, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.END
                    bottomMargin = navBarHeightPx // End at the nav bar
                }
        )

        // Load and apply saved UI scale after view hierarchy is ready
        post {
            val savedScaleProgress =
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .getInt("uiScaleProgress", 100)
            val savedScale = 0.35f + (savedScaleProgress / 100f) * 0.65f
            updateUiScale(savedScale)
        }
    }

    // Track fullscreen toggles for debugging
    private var fullscreenEntryCount = 0
    private var lastFullscreenViewHashCode = 0

    fun showFullScreenOverlay(view: View) {
        fullscreenEntryCount++
        val viewHashCode = view.hashCode()
        // val isSameView = viewHashCode == lastFullscreenViewHashCode
        lastFullscreenViewHashCode = viewHashCode

        // Remove from current parent if any
        if (view.parent is ViewGroup) {
            // DebugLog.d("FullscreenDebug", "  Removing view from parent: ${(view.parent as
            // ViewGroup).javaClass.simpleName}")
            (view.parent as ViewGroup).removeView(view)
        }

        // Clear any existing children
        if (fullScreenOverlayContainer.childCount > 0) {
            // DebugLog.d("FullscreenDebug", "  Clearing ${fullScreenOverlayContainer.childCount}
            // existing children from container")
            fullScreenOverlayContainer.removeAllViews()
        }

        // Add the new view
        fullScreenOverlayContainer.addView(
                view,
                FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        )

        // Add the full screen controls overlay
        if (::fullScreenControlsContainer.isInitialized) {
            // Remove from parent if it was already added (defensive)
            (fullScreenControlsContainer.parent as? ViewGroup)?.removeView(
                    fullScreenControlsContainer
            )

            fullScreenOverlayContainer.addView(fullScreenControlsContainer)
            fullScreenControlsContainer.visibility = View.VISIBLE
            if (::fullScreenMediaControls.isInitialized) {
                fullScreenMediaControls.visibility =
                        if (suppressFullscreenMediaControls) View.GONE else View.VISIBLE
            }
            fullScreenControlsContainer.bringToFront()
        }

        // DebugLog.d("FullscreenDebug", "  View added. Container child count:
        // ${fullScreenOverlayContainer.childCount}")

        previousFullScreenVisibility.clear()
        DebugLog.d("FullscreenDebug", "Hiding ${fullScreenHiddenViews.size} UI elements")
        fullScreenHiddenViews.forEach { target ->

            // DebugLog.d("FullscreenDebug", "  Hiding $name (was ${if (target.visibility ==
            // View.VISIBLE) "VISIBLE" else "GONE/INVISIBLE"})")
            previousFullScreenVisibility[target] = target.visibility
            // Use GONE for everything to maximize power saving (remove from layout)
            target.visibility = View.GONE
        }

        fullScreenOverlayContainer.visibility = View.VISIBLE
        fullScreenOverlayContainer.elevation = 2000f
        fullScreenOverlayContainer.bringToFront()

        // Force refresh to ensure the fullscreen content is captured
        post {
            fullScreenOverlayContainer.invalidate()
            fullScreenOverlayContainer.requestLayout()
            startRefreshing()
            // DebugLog.d("FullscreenDebug", "  Post-show refresh triggered")
        }

        // DebugLog.d("FullscreenDebug", "About to call hideSystemUI()")
        hideSystemUI()

        // Power saving: reduce refresh rate and notify listener
        fullscreenListener?.onEnterFullscreen()
        updateRefreshRate()
    }

    fun hideFullScreenOverlay() {

        // Get reference to the view being removed for logging
        val removedView =
                if (fullScreenOverlayContainer.childCount > 0) {
                    fullScreenOverlayContainer.getChildAt(0)
                } else null

        if (removedView != null) {
            // DebugLog.d("FullscreenDebug", "  Removing view: ${removedView.javaClass.simpleName},
            // hashCode: ${removedView.hashCode()}")
        }

        fullScreenOverlayContainer.removeAllViews()

        // Use INVISIBLE instead of GONE to keep the container surface attached
        // This may help prevent surface corruption on second fullscreen entry
        fullScreenOverlayContainer.visibility = View.INVISIBLE
        fullScreenOverlayContainer.elevation = 0f

        previousFullScreenVisibility.forEach { (target, visibility) ->

            // DebugLog.d("FullscreenDebug", "  Restoring $name to ${if (visibility == View.VISIBLE)
            // "VISIBLE" else "GONE/INVISIBLE"}")
            target.visibility = visibility
        }
        previousFullScreenVisibility.clear()

        // Force WebView to redraw
        webView.invalidate()
        webView.requestLayout()

        // Force the entire UI container to relayout and redraw
        leftEyeUIContainer.invalidate()
        leftEyeUIContainer.requestLayout()

        // Also refresh the parent to ensure proper alignment
        leftEyeClipParent.invalidate()
        leftEyeClipParent.requestLayout()

        // Force a full view hierarchy refresh
        this.invalidate()
        this.requestLayout()

        // Restart the mirroring refresh with a slight delay to let layout complete
        postDelayed(
                {
                    // Reset capture throttling so next capture runs immediately
                    lastCaptureTime = 0L

                    // Force bitmap recreation on next capture
                    synchronized(bitmapLock) {
                        bitmap?.recycle()
                        bitmap = null
                    }

                    startRefreshing()
                    // DebugLog.d("FullscreenDebug", "  Post-hide refresh triggered")
                },
                300
        ) // Small delay to let layout settle

        hideSystemUI()

        // Restore normal refresh rate and notify listener
        fullscreenListener?.onExitFullscreen()
        updateRefreshRate()

        // DebugLog.d("FullscreenDebug", "hideFullScreenOverlay complete")
    }

    private fun updateRefreshRate() {
        val isFullscreen = fullScreenOverlayContainer.visibility == View.VISIBLE
        val now = System.currentTimeMillis()
        val isIdle = (now - lastUserInteractionTime) > idleThresholdMs

        // With BinocularSbsLayout handling SBS rendering directly (no PixelCopy),
        // the refresh loop only drives scrollbar checks and cursor blink.
        // Lower rates save CPU/GPU for audio decoding and reduce thermal throttling.
        //
        // 1. Screen masked: 10fps (100ms) - minimal updates
        // 2. Scrolling: 60fps (16ms) - smooth scroll bar tracking
        // 3. Idle and not playing media: 10fps (100ms)
        // 4. Media playing (audio/video): 4fps (250ms) — scrollbars rarely change,
        //    and freeing the main-thread + GPU eliminates audio-thread starvation
        // 5. Anchored browsing: 30fps (33ms) - responsive scroll bars
        // 6. Default: 30fps (33ms)
        refreshInterval =
                when {
                    isScreenMasked -> maskedRefreshIntervalMs
                    isInScrollMode -> 16L
                    isIdle && !isMediaPlaying -> idleRefreshIntervalMs
                    isMediaPlaying -> 250L
                    isAnchored && !isFullscreen -> 33L
                    else -> 33L
                }
    }

    /** Call this from touch handlers to reset idle timer */
    fun noteUserInteraction() {
        lastUserInteractionTime = System.currentTimeMillis()
        // If we were in idle mode, restore normal refresh rate
        updateRefreshRate()
    }

    private fun hideSystemUI() {
        val activity =
                context as? Activity
                        ?: run {
                            Log.w(
                                    "FullscreenDebug",
                                    "Cannot hide system UI - context is not an Activity"
                            )
                            return
                        }

        post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ (API 30+) - Use WindowInsetsController
                    // CRITICAL: Must set decorFitsSystemWindows to false first
                    @Suppress("DEPRECATION") activity.window.setDecorFitsSystemWindows(false)

                    activity.window.insetsController?.let { controller ->
                        controller.hide(
                                android.view.WindowInsets.Type.statusBars() or
                                        android.view.WindowInsets.Type.navigationBars()
                        )
                        controller.systemBarsBehavior =
                                android.view.WindowInsetsController
                                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        DebugLog.d("FullscreenDebug", "System UI hidden (API 30+)")
                    }
                            ?: Log.w("FullscreenDebug", "WindowInsetsController is null!")
                } else {
                    // Older Android versions - Use deprecated flags
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility =
                            (View.SYSTEM_UI_FLAG_FULLSCREEN or
                                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                    DebugLog.d("FullscreenDebug", "System UI hidden (legacy API)")
                }
            } catch (e: Exception) {
                Log.e("FullscreenDebug", "Error hiding system UI", e)
            }
        }
    }

    private fun showSystemUI() {
        val activity =
                context as? Activity
                        ?: run {
                            Log.w(
                                    "FullscreenDebug",
                                    "Cannot show system UI - context is not an Activity"
                            )
                            return
                        }

        post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ (API 30+) - Use WindowInsetsController
                    // Restore decorFitsSystemWindows
                    @Suppress("DEPRECATION") activity.window.setDecorFitsSystemWindows(false)

                    activity.window.insetsController?.show(
                            android.view.WindowInsets.Type.statusBars() or
                                    android.view.WindowInsets.Type.navigationBars()
                    )
                    DebugLog.d("FullscreenDebug", "System UI shown (API 30+)")
                } else {
                    // Older Android versions - Clear flags
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility =
                            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                    DebugLog.d("FullscreenDebug", "System UI shown (legacy API)")
                }
            } catch (e: Exception) {
                Log.e("FullscreenDebug", "Error showing system UI", e)
            }
        }
    }

    fun maskScreen() {
        if (isScreenMasked) return  // Idempotent: prevent duplicate handler accumulation
        isScreenMasked = true
        maskOverlay.visibility = View.VISIBLE
        maskOverlay.bringToFront()
        // Hide both cursor views
        leftToggleBar.findViewById<FontIconView>(R.id.btnMask)?.setText(R.string.fa_eye_slash)
        keepScreenOn = true
        updatePlaybackWakeLocks()
        refreshMaskedNowPlaying()
        refreshMaskedNowPlayingFromJs()
        // Start periodic now-playing refresh (every 5s) — remove first to guarantee single handler
        removeCallbacks(maskNowPlayingPeriodicRefresh)
        postDelayed(maskNowPlayingPeriodicRefresh, 5000L)
        updateRefreshRate()
    }

    fun unmaskScreen() {
        isScreenMasked = false
        updatePlaybackWakeLocks()
        removeCallbacks(maskNowPlayingPeriodicRefresh)
        lastMaskedDomTitle = null
        lastMaskedDomTitleUrl = null
        lastMaskedDomTitleAt = 0L
        maskOverlay.visibility = View.GONE
        if (::maskNowPlayingText.isInitialized) {
            maskNowPlayingText.visibility = View.GONE
        }
        if (isVisualizerVisible) hideVisualizer()
        // Let MainActivity handle cursor visibility restoration - cursors will be shown
        // if they were visible before masking through updateCursorPosition call
        leftToggleBar.findViewById<FontIconView>(R.id.btnMask)?.setText(R.string.fa_eye)
        keepScreenOn = false
        updateRefreshRate()
    }

    fun isScreenMasked() = isScreenMasked

    fun setHostPaused(paused: Boolean) {
        if (isHostPaused == paused) return
        isHostPaused = paused
        updatePlaybackWakeLocks()
    }

    fun isFullScreenOverlayVisible() = fullScreenOverlayContainer.visibility == View.VISIBLE

    /** Hit-test a raw screen coordinate against the Visualizer toggle button.
     *  Used by the parent's ACTION_DOWN path so a plain tap fires immediately
     *  instead of waiting for tap classification at ACTION_UP. */
    private fun isTouchOnVisualizerToggle(screenX: Float, screenY: Float): Boolean {
        if (!::btnVisualizerToggle.isInitialized) return false
        if (btnVisualizerToggle.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        btnVisualizerToggle.getLocationOnScreen(loc)
        val scale = uiScale
        val pad = 24f * scale
        val w = btnVisualizerToggle.width * scale
        val h = btnVisualizerToggle.height * scale
        return screenX >= loc[0] - pad && screenX <= loc[0] + w + pad &&
               screenY >= loc[1] - pad && screenY <= loc[1] + h + pad
    }

    fun dispatchMaskOverlayTouch(screenX: Float, screenY: Float) {
        // Global debounce: prevent the same physical tap from triggering this method
        // multiple times via different code paths (maskOverlay listener + MainActivity handlers)
        val now = SystemClock.uptimeMillis()
        if (now - lastMaskOverlayDispatchTime < MASK_OVERLAY_DISPATCH_DEBOUNCE_MS) return
        lastMaskOverlayDispatchTime = now

        val location = IntArray(2)
        maskOverlay.getLocationOnScreen(location)
        val scale = uiScale

        // Convert to local coordinates relative to mask overlay
        // val localX = screenX - location[0]
        // val localY = screenY - location[1]

        // DebugLog.d("MediaControls", "dispatchMaskOverlayTouch at local ($localX, $localY), scale:
        // $scale")

        // Check unmask button hit (account for scale in button dimensions)
        val unmaskLocation = IntArray(2)
        btnMaskUnmask.getLocationOnScreen(unmaskLocation)
        val unmaskWidth = btnMaskUnmask.width * scale
        val unmaskHeight = btnMaskUnmask.height * scale
        if (screenX >= unmaskLocation[0] &&
                        screenX <= unmaskLocation[0] + unmaskWidth &&
                        screenY >= unmaskLocation[1] &&
                        screenY <= unmaskLocation[1] + unmaskHeight
        ) {
            // DebugLog.d("MediaControls", "Unmask button pressed")
            unmaskScreen()
            return
        }

        // Check visualizer toggle button
        if (::btnVisualizerToggle.isInitialized && btnVisualizerToggle.visibility == View.VISIBLE) {
            val vizBtnLoc = IntArray(2)
            btnVisualizerToggle.getLocationOnScreen(vizBtnLoc)
            val vizW = btnVisualizerToggle.width * scale
            val vizH = btnVisualizerToggle.height * scale
            // Expand hit area for small 52×52 button — the glasses' cursor is
            // imprecise, so a generous slop keeps taps reliable.
            val pad = 24f * scale
            if (screenX >= vizBtnLoc[0] - pad &&
                screenX <= vizBtnLoc[0] + vizW + pad &&
                screenY >= vizBtnLoc[1] - pad &&
                screenY <= vizBtnLoc[1] + vizH + pad
            ) {
                btnVisualizerToggle.performClick()
                return
            }
        }

        // Tap on the visualizer itself → cycle themes (debounced 400ms)
        if (::maskVisualizerView.isInitialized && maskVisualizerView.visibility == View.VISIBLE) {
            val vizLoc = IntArray(2)
            maskVisualizerView.getLocationOnScreen(vizLoc)
            val vizW = maskVisualizerView.width * scale
            val vizH = maskVisualizerView.height * scale
            if (screenX >= vizLoc[0] &&
                screenX <= vizLoc[0] + vizW &&
                screenY >= vizLoc[1] &&
                screenY <= vizLoc[1] + vizH
            ) {
                val now = SystemClock.uptimeMillis()
                if (now - lastVisualizerThemeTapTime < 500) return  // debounce theme taps (prevent multi-fire from touch+mouse)
                lastVisualizerThemeTapTime = now
                maskVisualizerView.cycleThemeOrWrap()
                updateVisualizerButtonColor()
                return
            }
        }

        // Check media control buttons
        if (maskMediaControlsContainer.visibility == View.VISIBLE) {
            val controlsLocation = IntArray(2)
            maskMediaControlsContainer.getLocationOnScreen(controlsLocation)

            // Iterate through children (the media buttons)
            for (i in 0 until maskMediaControlsContainer.childCount) {
                val button = maskMediaControlsContainer.getChildAt(i)
                if (button.visibility != View.VISIBLE) continue

                val btnLocation = IntArray(2)
                button.getLocationOnScreen(btnLocation)
                val btnWidth = button.width * scale
                val btnHeight = button.height * scale

                if (screenX >= btnLocation[0] &&
                                screenX <= btnLocation[0] + btnWidth &&
                                screenY >= btnLocation[1] &&
                                screenY <= btnLocation[1] + btnHeight
                ) {
                    // DebugLog.d("MediaControls", "Media button $i pressed")
                    button.performClick()
                    return
                }
            }
        }

        // DebugLog.d("MediaControls", "Touch on mask overlay but not on any button")
    }

    fun dispatchFullScreenOverlayTouch(screenX: Float, screenY: Float) {
        val scale = uiScale
        DebugLog.d("FullscreenTouch", "Touch at screen ($screenX, $screenY), scale: $scale")

        // Check controls container if visible
        if (::fullScreenControlsContainer.isInitialized &&
                        fullScreenControlsContainer.visibility == View.VISIBLE
        ) {
            DebugLog.d("FullscreenTouch", "Controls container is visible")

            // Check exit button
            if (::btnFsExit.isInitialized && btnFsExit.visibility == View.VISIBLE) {
                val btnLocation = IntArray(2)
                btnFsExit.getLocationOnScreen(btnLocation)
                val btnWidth = btnFsExit.width * scale
                val btnHeight = btnFsExit.height * scale
                DebugLog.d(
                        "FullscreenTouch",
                        "Exit button: loc=(${btnLocation[0]}, ${btnLocation[1]}), size=($btnWidth, $btnHeight), raw=(${btnFsExit.width}, ${btnFsExit.height})"
                )
                if (screenX >= btnLocation[0] &&
                                screenX <= btnLocation[0] + btnWidth &&
                                screenY >= btnLocation[1] &&
                                screenY <= btnLocation[1] + btnHeight
                ) {
                    DebugLog.d("FullscreenTouch", "Exit button HIT!")
                    btnFsExit.performClick()
                    return
                }
            }

            // Check media control buttons
            if (::fullScreenMediaControls.isInitialized &&
                            fullScreenMediaControls.visibility == View.VISIBLE
            ) {
                DebugLog.d(
                        "FullscreenTouch",
                        "Media controls visible with ${fullScreenMediaControls.childCount} children"
                )
                for (i in 0 until fullScreenMediaControls.childCount) {
                    val button = fullScreenMediaControls.getChildAt(i)
                    if (button.visibility != View.VISIBLE) continue

                    val btnLocation = IntArray(2)
                    button.getLocationOnScreen(btnLocation)
                    val btnWidth = button.width * scale
                    val btnHeight = button.height * scale
                    DebugLog.d(
                            "FullscreenTouch",
                            "Button $i: loc=(${btnLocation[0]}, ${btnLocation[1]}), size=($btnWidth, $btnHeight)"
                    )

                    if (screenX >= btnLocation[0] &&
                                    screenX <= btnLocation[0] + btnWidth &&
                                    screenY >= btnLocation[1] &&
                                    screenY <= btnLocation[1] + btnHeight
                    ) {
                        DebugLog.d("FullscreenTouch", "Button $i HIT!")
                        button.performClick()
                        return
                    }
                }
            }
        } else {
            DebugLog.d("FullscreenTouch", "Controls container NOT visible or not initialized")
        }

        // If no button hit, toggle controls visibility
        DebugLog.d("FullscreenTouch", "No button hit, toggling controls visibility")
        if (::fullScreenControlsContainer.isInitialized) {
            if (fullScreenControlsContainer.visibility == View.VISIBLE) {
                fullScreenControlsContainer.visibility = View.GONE
            } else {
                fullScreenControlsContainer.visibility = View.VISIBLE
                fullScreenControlsContainer.bringToFront()
            }
        }
    }

    private fun drawBitmapToSurface() {
        // REFACTORED: No-op in single viewport mode
        // BinocularSbsLayout handles the rendering - no mirroring needed
    }

    fun getCurrentLinkText(): String {
        return urlEditText.text.toString()
    }

    fun toggleIsUrlEditing(isEditing: Boolean) {
        _isUrlEditing = isEditing
        // DebugLog.d("LinkEditing", "DualWebViewGroup isUrlEditing toggled to: $isEditing")
    }

    fun setLinkText(text: String, newCursorPosition: Int = -1) {
        urlEditText.setText(text)

        // If no specific cursor position requested, maintain current position
        val cursorPos =
                if (newCursorPosition >= 0) {
                    // Ensure requested position doesn't exceed text length
                    minOf(newCursorPosition, text.length)
                } else {
                    // Keep current cursor position but ensure it's valid
                    minOf(urlEditText.selectionStart, text.length)
                }

        urlEditText.setSelection(cursorPos)
    }

    fun adjustViewportAndFields(adjustment: Float) {
        // Apply adjustment to all elements
        // translationY = adjustment // Don't move the entire group, just children
        webView.translationY = adjustment
        urlEditText.translationY = adjustment
        dialogContainer.translationY = adjustment

        if (::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
            // Ensure bookmarks view stays above keyboard
            leftBookmarksView.translationY = adjustment

            // Get the current edit field from bookmarks view
            val editField = leftBookmarksView.getCurrentEditField()
            editField?.translationY = adjustment
        }
    }

    fun getCurrentUrlEditField(): EditText? {
        return if (_isUrlEditing) urlEditText else null
    }

    fun animateViewportAdjustment() {
        webView.animate().setDuration(200).translationY(webView.translationY).start()
    }

    // Method to show link editing UI
    fun showLinkEditing() {
        if (!_isUrlEditing) {
            _isUrlEditing = true

            val currentUrl = webView.url ?: ""
            urlEditText.apply {
                text.clear()
                append(currentUrl)
                visibility = View.VISIBLE
                requestFocus()
                setSelection(text.length)
                bringToFront()
            }

            keyboardListener?.onShowKeyboard()
        }
    }

    fun isUrlEditing(): Boolean {
        // DebugLog.d("LinkEditing", "isUrlEditing check, value: $isUrlEditing")
        return _isUrlEditing
    }

    fun isBookmarksExpanded(): Boolean {
        return leftBookmarksView.visibility == View.VISIBLE
    }

    private fun toggleChat() {
        if (chatView.visibility == View.VISIBLE) {
            chatView.visibility = View.GONE
        } else {
            pauseYouTubeMediaAcrossAllWindows()
            chatView.visibility = View.VISIBLE
            chatView.bringToFront()
            maybePromptForGroqApiKey()
        }
        post {
            requestLayout()
            invalidate()
        }
    }

    fun hideChat() {
        if (!::chatView.isInitialized || chatView.visibility != View.VISIBLE) return
        chatView.visibility = View.GONE
        post {
            requestLayout()
            invalidate()
        }
    }

    private fun maybePromptForGroqApiKey() {
        if (dialogContainer.visibility == View.VISIBLE) return
        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        val currentKey = prefs.getString("groq_api_key", null)?.trim()
        if (!currentKey.isNullOrBlank()) return

        showPromptDialog(
                "Enter Groq API Key",
                currentKey,
                { key ->
                    val trimmed = key.trim()
                    if (trimmed.isBlank()) {
                        showToast("API Key Required")
                        post { maybePromptForGroqApiKey() }
                        return@showPromptDialog
                    }
                    prefs.edit().putString("groq_api_key", trimmed).apply()
                    showToast("API Key Saved")
                    keyboardListener?.onHideKeyboard()
                },
                { showToast("API Key Required") }
        )
    }

    private fun toggleBookmarks() {
        leftBookmarksView.toggle()

        if (leftBookmarksView.visibility == View.VISIBLE) {
            leftBookmarksView.bringToFront()
            leftBookmarksView.elevation = 1000f

            // Force immediate refresh to ensure mirroring
            post {
                invalidate()
                startRefreshing()
            }
        }

        // Request layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    fun handleBookmarkTap(): Boolean {
        if (leftBookmarksView.visibility != View.VISIBLE) {
            // DebugLog.d("BookmarksDebug", "No tap handling - bookmarks not visible")
            return false
        }

        // Let BookmarksView handle the tap
        val handled = leftBookmarksView.handleTap()
        if (handled) {
            // Force refresh to update the mirrored view
            startRefreshing()
        }
        return handled
    }

    fun handleBookmarkDoubleTap(): Boolean {
        return if (leftBookmarksView.visibility == View.VISIBLE) {
            // DebugLog.d("BookmarksDebug", "handleBookmarkDoubleTap() called.
            // leftVisibility=${leftBookmarksView.visibility}")
            val handled = leftBookmarksView.handleDoubleTap()
            if (handled) {
                leftBookmarksView.logStackTrace(
                        "BookmarksDebug",
                        "handleBookmarkDoubleTap(): double tap handled"
                )
                // Force refresh to update the mirrored view
                startRefreshing()
            }
            handled
        } else false
    }

    fun getBookmarksView(): BookmarksView {
        return leftBookmarksView
    }

    // Provide WebView access
    @SuppressLint("SetJavaScriptEnabled")
    fun getWebView(): WebView {
        return webView.apply {
            val settings = this.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            @Suppress("DEPRECATION") run { settings.databaseEnabled = true }
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false

            // Clean up legacy JS interface - we use URL scheme now
            // addJavascriptInterface(WebAppInterface(context, this), "Android")

            // Set User Agent

            // Set User Agent
            // settings.userAgentString = desktopUserAgent // Default to Desktop
        }
    }

    private fun setupUrlEditText(isRight: Boolean = false): EditText {
        return EditText(context).apply {
            layoutParams =
                    FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    Gravity.TOP
                            )
                            .apply {
                                leftMargin = toggleBarWidthPx // Single margin for left side
                            }
            setBackgroundColor(Color.parseColor("#202020"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(32, 12, 32, 12)
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = urlFieldMinHeight
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = true
            highlightColor = Color.parseColor("#404040")

            // Set hardware acceleration for better cursor rendering
            setLayerType(
                    View.LAYER_TYPE_HARDWARE,
                    Paint().apply {
                        color = Color.WHITE // Set cursor color to white
                    }
            )

            // Set hardware acceleration for better cursor rendering
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // Make both EditTexts share focus state
            setOnFocusChangeListener { _, hasFocus ->
                if (isRight && hasFocus) {
                    urlEditText.requestFocus()
                }
            }

            // Add text change listener to sync content
            addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                                s: CharSequence?,
                                start: Int,
                                count: Int,
                                after: Int
                        ) {}
                        override fun onTextChanged(
                                s: CharSequence?,
                                start: Int,
                                before: Int,
                                count: Int
                        ) {}
                        override fun afterTextChanged(s: Editable?) {}
                    }
            )
        }
    }

    // Set up the bitmap for capturing content
    private fun setupBitmap(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        synchronized(bitmapLock) {
            try {
                bitmap?.let { oldBitmap ->
                    if (!oldBitmap.isRecycled) {
                        oldBitmap.recycle()
                    }
                }
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (e: Exception) {
                Log.e("DualWebViewGroup", "Error creating bitmap", e)
                bitmap = null
            }
        }
    }

    fun updateLeftEyePosition(xOffset: Float, yOffset: Float, rotationDeg: Float) {

        // Store the translations
        _translationX = yOffset
        _translationY = xOffset

        // If you also want to store rotation in a field:
        _rotationZ = rotationDeg

        leftEyeUIContainer.translationX = yOffset
        leftEyeUIContainer.translationY = xOffset
        leftEyeUIContainer.rotation = rotationDeg

        // Only apply same transformations to full screen overlay when it's actually visible
        // This prevents the video from being positioned incorrectly when fullscreen is activated
        if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
            fullScreenOverlayContainer.translationX = yOffset
            fullScreenOverlayContainer.translationY = xOffset
            fullScreenOverlayContainer.rotation = rotationDeg
        } else {
            // Keep at zero when not visible to ensure clean state
            fullScreenOverlayContainer.translationX = 0f
            fullScreenOverlayContainer.translationY = 0f
            fullScreenOverlayContainer.rotation = 0f
        }

        // Pass the fixed screen cursor position to hover detection
        // In anchored mode, the cursor is visually fixed at the center (320, 240)
        val containerLocation = IntArray(2)
        getLocationOnScreen(containerLocation)
        val screenX = 320f + containerLocation[0]
        val screenY = 240f + containerLocation[1]

        updateButtonHoverStates(screenX, screenY)

        // Ensure visual cursor scale/visibility is refreshed in anchored mode
        listener?.onCursorPositionChanged(320f, 240f, true)

        // Only do expensive operations occasionally, not every frame
        // The Choreographer already ensures smooth vsync timing
        if (!isRefreshing) {
            post { startRefreshing() }
        }
    }

    // Capture and mirror content to left SurfaceView
    private fun captureLeftEyeContent() {
        if (!isRefreshing) {
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCaptureTime < MIN_CAPTURE_INTERVAL) {
            return
        }
        lastCaptureTime = currentTime

        try {
            // Check scrollbar visibility periodically (once per second)
            // Skip if in fullscreen mode to save power
            val isFullScreen = fullScreenOverlayContainer.visibility == View.VISIBLE

            if (!isFullScreen && currentTime - lastScrollBarCheckTime > 1000) {
                if (!shouldFreezeScrollBars()) {
                    updateScrollBarsVisibility()
                }
                lastScrollBarCheckTime = currentTime
            }

            // Force cursor refresh if editing - skip in fullscreen
            if (!isFullScreen && _isUrlEditing && urlEditText.isFocused) {
                urlEditText.invalidate()
            }

            // NOTE: PixelCopy + drawBitmapToSurface() removed — BinocularSbsLayout now
            // renders the SBS output directly from the view hierarchy, making the old
            // capture-to-bitmap-then-draw pipeline dead code. Removing it frees ~60 GPU
            // PixelCopy ops/sec and eliminates bitmapLock contention that was starving
            // the audio decoder thread on the X3 Pro (manifesting as periodic stutters).
        } catch (e: Exception) {
            Log.e("MirrorDebug", "Error in refresh tick", e)
            stopRefreshing()
        }
    }

    fun onKeyboardHidden() {
        // Reset views when keyboard is hidden
        post {
            requestLayout()
            invalidate()

            // Force bitmap recreation with new dimensions
            // setupBitmap(webView.width, height - 48)

            // Ensure mirroring is updated
            startRefreshing()
        }
    }

    fun syncKeyboardStates() {
        customKeyboard?.let { Kb ->

            // Force update of the keyboard
            Kb.post {
                Kb.invalidate()
                Kb.requestLayout()
                keyboardContainer.invalidate()
                keyboardContainer.requestLayout()
            }
        }
    }

    // Refresh handling
    private var refreshCount = 0
    private var lastRefreshLogTime = 0L

    private val refreshRunnable =
            object : Runnable {
                override fun run() {
                    refreshCount++

                    // Log every 2 seconds to avoid spam
                    val now = System.currentTimeMillis()
                    if (now - lastRefreshLogTime > 2000) {
                        // DebugLog.d("MirrorDebug", "RefreshLoop running, count=$refreshCount,
                        // isRefreshing=$isRefreshing,
                        // webViewAttached=${webView.isAttachedToWindow},
                        // fsOverlayVisible=${fullScreenOverlayContainer.visibility ==
                        // View.VISIBLE}")
                        lastRefreshLogTime = now
                    }

                    if (isRefreshing) {
                        if (webView.isAttachedToWindow) {
                            captureLeftEyeContent()
                        }
                        refreshHandler.postDelayed(this, refreshInterval)
                    } else {
                        Log.w("MirrorDebug", "RefreshLoop STOPPING! isRefreshing=$isRefreshing")
                        // No need to call stopRefreshing() here as we just stop posting callbacks
                    }
                }
            }

    fun startRefreshing() {
        // REFACTORED: No-op in single viewport mode
        // BinocularSbsLayout handles the rendering - no mirroring needed
    }

    fun stopRefreshing() {
        // REFACTORED: No-op in single viewport mode
        // BinocularSbsLayout handles the rendering - no mirroring needed
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {

        // BinocularSbsLayout gives us the logical viewport size; use actual measured dimensions.
        val eyeWidth = r - l
        val eyeHeight = b - t
        val halfWidth = eyeWidth

        val toggleBarWidth = toggleBarWidthPx
        val navBarHeight = navBarHeightPx

        // Ensure toggle bar is measured correctly
        leftToggleBar.measure(
                MeasureSpec.makeMeasureSpec(toggleBarWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(eyeHeight - navBarHeight, MeasureSpec.EXACTLY)
        )
        if (!isInScrollMode && !isNavBarsHidden) {
            if (leftToggleBar.visibility != View.VISIBLE) {
                leftToggleBar.visibility = View.VISIBLE
            }
        }

        // Ensure navigation bar is measured correctly
        leftNavigationBar.measure(
                MeasureSpec.makeMeasureSpec(halfWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(navBarHeight, MeasureSpec.EXACTLY)
        )

        // Force a layout pass on the container if needed
        if (leftToggleBar.measuredWidth == 0) {
            leftEyeUIContainer.requestLayout()
        }

        val height = b - t
        // Use actual measured height of keyboard if visible, otherwise default
        val keyboardHeight =
                if (keyboardContainer.measuredHeight > 0) keyboardContainer.measuredHeight else 160
        // Keyboard width is same regardless of mode (matches original keyboard size)
        val keyboardWidth = halfWidth - toggleBarWidth

        // Position the WebView differently based on scroll mode
        // Shrink the WebView when keyboard is visible so content isn't blocked
        val isKeyboardVisible = keyboardContainer.visibility == View.VISIBLE

        val horizontalReserve = if (horizontalScrollBar.visibility == View.VISIBLE) 20 else 0

        if (isInScrollMode || isNavBarsHidden) {
            val keyboardLimit =
                    if (isKeyboardVisible) {
                        eyeHeight - keyboardHeight // Shrink to fit above keyboard
                    } else {
                        480
                    }
            // Respect proper measurement which accounts for margins (scrollbars)
            val measuredBottom = 0 + webViewsContainer.measuredHeight
            val adjustedKeyboardLimit = (keyboardLimit - horizontalReserve).coerceAtLeast(0)

            webViewsContainer.layout(
                    0, // No left margin in scroll mode
                    0,
                    0 + webViewsContainer.measuredWidth, // Full width minus margins
                    minOf(adjustedKeyboardLimit, measuredBottom)
            )
        } else {
            val navBarTop = eyeHeight - navBarHeight

            val keyboardLimit =
                    if (isKeyboardVisible) {
                        minOf(navBarTop, eyeHeight - keyboardHeight) // Shrink to fit above keyboard
                    } else {
                        navBarTop // Default bottom for 30px nav bar
                    }
            // Respect proper measurement which accounts for margins (scrollbars)
            val measuredBottom = 0 + webViewsContainer.measuredHeight
            val adjustedKeyboardLimit = (keyboardLimit - horizontalReserve).coerceAtLeast(0)

            webViewsContainer.layout(
                    toggleBarWidth, // Account for toggle bar
                    0,
                    toggleBarWidth +
                            webViewsContainer.measuredWidth, // Standard width + toggle bar offset
                    minOf(adjustedKeyboardLimit, measuredBottom)
            )
        }

        // Calculate available content height based on keyboard visibility
        val contentHeight =
                if (keyboardContainer.visibility == View.VISIBLE) {
                    eyeHeight - keyboardHeight
                } else {
                    eyeHeight - navBarHeight
                }

        // Layout the clip parent - hardcoded 640x480
        leftEyeClipParent.layout(
                0, // After toggle bar
                0,
                eyeWidth, // Fixed width for left eye
                eyeHeight
        )

        fullScreenOverlayContainer.layout(
                0, // Relative to leftEyeClipParent
                0,
                halfWidth, // 640px width (matches clip parent)
                eyeHeight
        )

        // REFACTORED: rightEyeView layout no longer needed - single viewport mode
        // rightEyeView.layout(eyeWidth, 0, eyeWidth * 2, eyeHeight)

        // Layout toggle bar - height is eyeHeight minus navBarHeight
        leftToggleBar.layout(0, 0, toggleBarWidth, eyeHeight - navBarHeight)
        //            DebugLog.d("ToggleBarDebug", """
        //        Toggle Bar Layout:
        //        Visibility: ${leftToggleBar.visibility}
        //        Width: $toggleBarWidth
        //        Height: 596
        //        Background: ${leftToggleBar.background}
        //        Parent: ${leftToggleBar.parent?.javaClass?.simpleName}
        //    """.trimIndent())

        val keyboardY = eyeHeight - keyboardHeight
        keyboardContainer.layout(
                toggleBarWidth,
                keyboardY,
                toggleBarWidth + keyboardWidth,
                eyeHeight
        )

        // Position ProgressBar - at bottom in scroll mode, above nav bar otherwise
        val progressBarHeight = 4
        if (isInScrollMode) {
            // In scroll mode, position at very bottom, full width
            progressBar.measure(
                    MeasureSpec.makeMeasureSpec(halfWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(progressBarHeight, MeasureSpec.EXACTLY)
            )
            if (progressBar.visibility == View.VISIBLE) {
                val pbY = eyeHeight - progressBarHeight
                progressBar.layout(0, pbY, halfWidth, eyeHeight)
                progressBar.bringToFront()
            } else {
                progressBar.layout(0, 0, 0, 0)
            }
        } else {
            // Normal mode - position above navigation bar
            progressBar.measure(
                    MeasureSpec.makeMeasureSpec(halfWidth - toggleBarWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(progressBarHeight, MeasureSpec.EXACTLY)
            )
            if (progressBar.visibility == View.VISIBLE) {
                val pbY = eyeHeight - navBarHeight - progressBarHeight
                progressBar.layout(toggleBarWidth, pbY, halfWidth, pbY + progressBarHeight)
            } else {
                progressBar.layout(0, 0, 0, 0)
            }
        }

        // Hide navigation bars
        leftNavigationBar.visibility = View.GONE

        if (keyboardContainer.visibility == View.VISIBLE) {
            // Position keyboards at the bottom
            // In scroll mode, center keyboard (no toggle bar offset)
            val kbLeft =
                    if (isInScrollMode) {
                        (halfWidth - keyboardWidth) / 2 // Center in left half
                    } else {
                        toggleBarWidth
                    }
            keyboardContainer.layout(kbLeft, keyboardY, kbLeft + keyboardWidth, eyeHeight)

            // Hide navigation bars
            leftNavigationBar.visibility = View.GONE

            // Position bookmarks menu if visible
            if (::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
                val bookmarksHeight = leftBookmarksView.measuredHeight
                val isEditingAnywhere = _isUrlEditing || leftBookmarksView.isEditing()
                val bookmarksY =
                        if (isEditingAnywhere) {
                            40 // Below URL edit field area / top of screen
                        } else {
                            keyboardY - bookmarksHeight
                        }

                // Constrain bottom to keyboardY to avoid overlapping with keyboard
                val bookmarksBottom =
                        if (isEditingAnywhere) {
                            minOf(bookmarksY + bookmarksHeight, keyboardY)
                        } else {
                            bookmarksY + bookmarksHeight
                        }

                leftBookmarksView.layout(
                        toggleBarWidth,
                        bookmarksY,
                        toggleBarWidth + 480,
                        bookmarksBottom
                )

                leftBookmarksView.bringToFront()
            }

            // Handle edit fields for both URL and bookmark editing
            if (_isUrlEditing || isBookmarkEditing) {
                val editFieldHeight = maxOf(urlFieldMinHeight, urlEditText.measuredHeight)
                val editFieldLeft = keyboardContainer.left.takeIf { it > 0 } ?: toggleBarWidth
                val editFieldRight =
                        keyboardContainer.right.takeIf { it > editFieldLeft }
                                ?: (editFieldLeft + keyboardWidth)

                // Position left edit field only
                urlEditText.apply {
                    layout(editFieldLeft, 0, editFieldRight, editFieldHeight)
                    translationY = (keyboardY - editFieldHeight).toFloat()
                    visibility = View.VISIBLE
                    elevation = 1001f
                }
            }

            // Ensure keyboard containers are on top but below edit fields
            keyboardContainer.elevation = 1000f
        } else {
            // DebugLog.d("EditFieldDebug", "Skipping edit field positioning - conditions not met")

            // Hide keyboard containers
            keyboardContainer.layout(
                    toggleBarWidth,
                    eyeHeight,
                    toggleBarWidth + keyboardWidth,
                    eyeHeight + keyboardHeight
            )

            // Position bookmarks when keyboard is not visible
            if (::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
                leftBookmarksView.layout(
                        toggleBarWidth,
                        30,
                        toggleBarWidth + 480,
                        eyeHeight - navBarHeight
                )
            }

            // Show navigation bar only in normal mode (hide in scroll mode to avoid overlap)
            if (isInScrollMode) {
                leftNavigationBar.visibility = View.GONE
                leftNavigationBar.layout(0, 0, 0, 0)
            } else {
                leftNavigationBar.visibility = View.VISIBLE
                leftNavigationBar.layout(0, eyeHeight - navBarHeight, halfWidth, eyeHeight)
            }
        }

        // Update bitmap capture when layout changes
        if (changed) {
            post {
                setupBitmap(webView.width, contentHeight)
                startRefreshing()
            }
        }

        // Hide system info bar when disabled or while nav/scroll overlays are hidden.
        updateSystemInfoBarVisibility()
        if (leftSystemInfoView.visibility == View.VISIBLE) {
            // Calculate system info bar position
            val infoBarHeight = 24
            val infoBarY = eyeHeight - navBarHeight - infoBarHeight // Position above nav bar

            // First measure the info views to get their width
            leftSystemInfoView.measure(
                    MeasureSpec.makeMeasureSpec(320, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(infoBarHeight, MeasureSpec.EXACTLY)
            )

            val infoBarWidth = leftSystemInfoView.measuredWidth
            val leftX =
                    (halfWidth - infoBarWidth) / 2 +
                            toggleBarWidth // Center in left half, account for toggle bar

            // Position the info bars
            leftSystemInfoView.layout(
                    leftX,
                    infoBarY,
                    leftX + infoBarWidth,
                    infoBarY + infoBarHeight
            )
        }

        // Position Dialog Container (Center it in the left view)
        if (dialogContainer.visibility != View.GONE) {
            val dialogWidth = 500

            // Measure the dialog container first if needed
            dialogContainer.measure(
                    MeasureSpec.makeMeasureSpec(dialogWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(eyeHeight, MeasureSpec.AT_MOST)
            )

            val measuredH = dialogContainer.measuredHeight

            val dialogLeft = toggleBarWidth + (keyboardWidth - dialogWidth) / 2

            // Calculate available vertical space, respecting the keyboard if it is visible
            val availableHeight =
                    if (keyboardContainer.visibility == View.VISIBLE) {
                        eyeHeight - keyboardHeight
                    } else {
                        eyeHeight
                    }
            // Center the dialog within the available space
            val dialogTop = (availableHeight - measuredH) / 2

            dialogContainer.layout(
                    dialogLeft,
                    dialogTop,
                    dialogLeft + dialogWidth,
                    dialogTop + measuredH
            )
            dialogContainer.elevation = 2000f
            dialogContainer.bringToFront()
        }

        // Layout maskOverlay to cover left eye only (will be mirrored to right eye)
        maskOverlay.layout(0, 0, halfWidth, height)

        // Layout the unhide button when in scroll mode
        if (isInScrollMode && btnShowNavBars.visibility == View.VISIBLE) {
            val btnSize = 40
            val btnRight = halfWidth - 8 // 8px margin from right
            val btnBottom = height - 8 // 8px margin from bottom
            btnShowNavBars.layout(btnRight - btnSize, btnBottom - btnSize, btnRight, btnBottom)
            btnShowNavBars.bringToFront()
        }

        // Layout scroll bars for non-anchored mode
        // Eye button size is 40px with 8px margin from bottom/right, so reserve 48px for it
        val eyeButtonSpace =
                if (isInScrollMode && btnShowNavBars.visibility == View.VISIBLE) 48 else 0

        if (horizontalScrollBar.visibility == View.VISIBLE) {
            val hScrollHeight = 20
            val navBarTop =
                    if (leftNavigationBar.visibility == View.VISIBLE) eyeHeight - navBarHeight
                    else eyeHeight
            val hScrollY =
                    if (isInScrollMode) eyeHeight - hScrollHeight
                    else navBarTop - hScrollHeight // Sit right above nav bar

            val leftInset =
                    if (leftToggleBar.visibility == View.VISIBLE) {
                        leftToggleBar.measuredWidth.takeIf { it > 0 } ?: toggleBarWidth
                    } else {
                        0
                    }
            val scrollLeft = leftInset
            var scrollWidth =
                    if (isInScrollMode) halfWidth - leftInset - eyeButtonSpace
                    else halfWidth - leftInset

            // Prevent overlap with vertical scrollbar if visible
            if (verticalScrollBar.visibility == View.VISIBLE) {
                scrollWidth -= 20 // Subtract width of vertical scrollbar
            }

            horizontalScrollBar.measure(
                    MeasureSpec.makeMeasureSpec(scrollWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(hScrollHeight, MeasureSpec.EXACTLY)
            )
            horizontalScrollBar.layout(
                    scrollLeft,
                    hScrollY,
                    scrollLeft + scrollWidth,
                    hScrollY + hScrollHeight
            )
        }

        if (verticalScrollBar.visibility == View.VISIBLE) {
            val vScrollWidth = 20
            val vScrollRight = halfWidth // Align to right edge
            val vScrollTop = 0 // Start from top

            // In scroll mode, stop above eye button. Normal mode, stop at nav bar.
            val vScrollBottom =
                    if (isInScrollMode) eyeHeight - eyeButtonSpace else eyeHeight - navBarHeight
            val vScrollHeight = vScrollBottom - vScrollTop

            verticalScrollBar.measure(
                    MeasureSpec.makeMeasureSpec(vScrollWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(vScrollHeight, MeasureSpec.EXACTLY)
            )
            verticalScrollBar.layout(
                    vScrollRight - vScrollWidth,
                    vScrollTop,
                    vScrollRight,
                    vScrollTop + vScrollHeight
            )
        }

        // Layout the UI container to cover just the left half
        leftEyeUIContainer.layout(0, 0, halfWidth, height)

        if (::chatView.isInitialized &&
                        chatView.visibility == View.VISIBLE &&
                        keyboardContainer.visibility == View.VISIBLE
        ) {
            val chatMargin = 8.dp()
            val availableHeight = (eyeHeight - keyboardHeight - chatMargin).coerceAtLeast(0)
            val baseWidth =
                    chatView.layoutParams.width.takeIf { it > 0 }
                            ?: chatView.measuredWidth.takeIf { it > 0 } ?: 560
            val baseHeight =
                    chatView.layoutParams.height.takeIf { it > 0 }
                            ?: chatView.measuredHeight.takeIf { it > 0 } ?: 420
            val targetWidth = baseWidth.coerceAtMost(halfWidth)
            val targetHeight = baseHeight.coerceAtMost(availableHeight)

            if (targetHeight > 0) {
                chatView.measure(
                        MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
                )
                val left = (halfWidth - targetWidth) / 2
                val bottom = eyeHeight - keyboardHeight - chatMargin
                val top = bottom - chatView.measuredHeight
                chatView.layout(left, top, left + targetWidth, bottom)
            }
        }
    }

    private var sharedConfigListenerRegistered = false

    fun cleanupResources() {
        if (maskWakeLock.isHeld) maskWakeLock.release()
        if (pausedMediaWakeLock.isHeld) pausedMediaWakeLock.release()
        try {
            if (mediaWifiLock?.isHeld == true) mediaWifiLock.release()
        } catch (_: Exception) {}
        if (sharedConfigListenerRegistered) {
            sharedConfigPrefs.unregisterOnSharedPreferenceChangeListener(sharedConfigListener)
            sharedConfigListenerRegistered = false
        }
        stopRefreshing()
        releaseAudioCapture()
        synchronized(bitmapLock) {
            bitmap?.let { currentBitmap ->
                if (!currentBitmap.isRecycled) {
                    currentBitmap.recycle()
                }
            }
            bitmap = null
        }
        System.gc() // Request garbage collection
    }

    fun getCurrentEditText(): String {
        return urlEditText.text.toString()
    }

    fun hideLinkEditing() {
        _isUrlEditing = false
        isBookmarkEditing = false

        urlEditText.apply {
            clearFocus()
            visibility = View.GONE
            elevation = 0f
        }

        post {
            startRefreshing()
            requestLayout()
            invalidate()
        }
    }

    private fun EditText.setOnSelectionChangedListener(listener: (Int, Int) -> Unit) {
        try {
            val field = TextView::class.java.getDeclaredField("mEditor")
            field.isAccessible = true
            val editor = field.get(this)

            val listenerField = editor.javaClass.getDeclaredField("mSelectionChangedListener")
            listenerField.isAccessible = true
            listenerField.set(
                    editor,
                    object : Any() {
                        fun onSelectionChanged(selStart: Int, selEnd: Int) {
                            listener(selStart, selEnd)
                        }
                    }
            )
        } catch (e: Exception) {
            Log.e("DualWebViewGroup", "Error setting selection listener", e)
        }
    }

    fun showInfoBars() {
        updateSystemInfoBarVisibility()
    }

    fun hideInfoBars() {
        leftSystemInfoView.visibility = View.GONE
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()

    private fun isBrowserSystemInfoEnabled(): Boolean =
            sharedConfigPrefs.getBoolean(KEY_BROWSER_SHOW_SYSTEM_INFO, true)

    private fun updateSystemInfoBarVisibility() {
        leftSystemInfoView.visibility =
                if (!isBrowserSystemInfoEnabled() || isInScrollMode || isNavBarsHidden) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
    }

    // Add keyboard mirror handling
    fun setKeyboard(originalKeyboard: CustomKeyboardView) {
        // DebugLog.d("KeyboardDebug", "setKeyboard called with keyboard:
        // ${originalKeyboard.hashCode()}")

        // Clear container
        keyboardContainer.removeAllViews()

        // Clear animations
        keyboardContainer.clearAnimation()
        webView.clearAnimation()
        // REFACTORED: rightEyeView no longer used - single viewport mode
        // rightEyeView.clearAnimation()

        // Reset translations
        keyboardContainer.translationY = 0f
        webView.translationY = 0f
        // REFACTORED: rightEyeView no longer used - single viewport mode
        // rightEyeView.translationY = 0f

        // Set keyboard
        customKeyboard = originalKeyboard
        customKeyboard?.setAnchoredMode(isAnchored)
        keyboardContainer.addView(
                originalKeyboard,
                FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { gravity = Gravity.BOTTOM }
        )

        // Explicitly set visibility based on keyboard's current state
        val visibility =
                if (originalKeyboard.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        keyboardContainer.visibility = visibility

        // Hide navigation bars when keyboard is visible
        if (visibility == View.VISIBLE) {
            leftNavigationBar.visibility = View.GONE
        }

        // Force layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // Force redraw of toggle buttons
        leftToggleBar.findViewById<View>(R.id.btnModeToggle)?.invalidate()
    }

    private fun getCursorInContainerCoords(): Pair<Float, Float> {
        // Calculate the actual screen position of the cursor first
        val containerLocation = IntArray(2)
        getLocationOnScreen(containerLocation)

        val transX = if (isAnchored) 0f else leftEyeUIContainer.translationX
        val transY = if (isAnchored) 0f else leftEyeUIContainer.translationY

        val visualX = 320f + (lastCursorX - 320f) * uiScale + transX
        val visualY = 240f + (lastCursorY - 240f) * uiScale + transY

        val screenX = visualX + containerLocation[0]
        val screenY = visualY + containerLocation[1]

        return computeAnchoredCoordinates(screenX, screenY)
    }

    private fun computeAnchoredKeyboardCoordinates(): Pair<Float, Float>? {
        val keyboard = keyboardContainer
        if (keyboard.width == 0 || keyboard.height == 0) {
            // DebugLog.d("TouchDebug", "computeAnchoredKeyboardCoordinates: keyboard not laid out")
            return null
        }

        val (adjustedX, adjustedY) = getCursorInContainerCoords()

        val keyboardLocation = IntArray(2)
        keyboard.getLocationOnScreen(keyboardLocation)
        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)
        val localXContainer = adjustedX - keyboard.x
        val localYContainer = adjustedY - keyboard.y

        val kbView = customKeyboard ?: return null

        val localX = localXContainer - kbView.x
        val localY = localYContainer - kbView.y

        return Pair(localX, localY)
    }

    private fun computeAnchoredCoordinates(screenX: Float, screenY: Float): Pair<Float, Float> {
        val parent = leftEyeUIContainer.parent as View
        val parentLocation = IntArray(2)
        parent.getLocationOnScreen(parentLocation)

        val relativeX = screenX - parentLocation[0]
        val relativeY = screenY - parentLocation[1]

        val points = floatArrayOf(relativeX, relativeY)

        val inverse = android.graphics.Matrix()
        leftEyeUIContainer.matrix.invert(inverse)
        inverse.mapPoints(points)

        return Pair(points[0], points[1])
    }

    private fun isTouchOnView(view: View, x: Float, y: Float): Boolean {
        return view.visibility == View.VISIBLE &&
                x >= view.left &&
                x <= view.right &&
                y >= view.top &&
                y <= view.bottom
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Let windows overview handle its own touch events
        if (windowsOverviewContainer?.visibility == View.VISIBLE) {
            return false // Don't intercept, let children handle touches
        }

        if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
            // Allow interactions with menus that are on top
            if (::leftBookmarksView.isInitialized && isTouchOnView(leftBookmarksView, ev.x, ev.y)) {
                return false
            }

            fullScreenTapDetector.onTouchEvent(ev)
            return true
        }

        // Skip anchored gesture handling when in scroll mode - touches should go directly to
        // WebView
        if (isAnchored && !isInScrollMode) {
            var isOverTarget = false
            val (cursorX, cursorY) = getCursorInContainerCoords()

            // Check Keyboard
            if (keyboardContainer.visibility == View.VISIBLE) {
                val localCoords = computeAnchoredKeyboardCoordinates()
                if (localCoords != null) {
                    val (localX, localY) = localCoords
                    if (localX >= 0 &&
                                    localX <= keyboardContainer.width &&
                                    localY >= 0 &&
                                    localY <= keyboardContainer.height
                    ) {
                        isOverTarget = true
                        anchoredTarget = 1
                    }
                }
            }

            // Check Bookmarks (if not already over keyboard)
            if (!isOverTarget &&
                            ::leftBookmarksView.isInitialized &&
                            leftBookmarksView.visibility == View.VISIBLE
            ) {
                if (cursorX >= leftBookmarksView.left &&
                                cursorX <= leftBookmarksView.right &&
                                cursorY >= leftBookmarksView.top &&
                                cursorY <= leftBookmarksView.bottom
                ) {
                    isOverTarget = true
                    anchoredTarget = 2
                    // DebugLog.d("TouchDebug", "Intercepting anchored tap for bookmarks")
                }
            }

            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    anchoredGestureActive = isOverTarget
                    if (anchoredGestureActive) {
                        anchoredTouchStartX = cursorX
                        anchoredTouchStartY = cursorY
                        lastAnchoredY = cursorY
                        isAnchoredDrag = false
                        // DebugLog.d("TouchDebug", "Intercepting anchored ACTION_DOWN
                        // target=$anchoredTarget")
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (anchoredGestureActive) return true
                    if (isOverTarget) {
                        anchoredGestureActive = true
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (anchoredGestureActive || isOverTarget) {
                        // DebugLog.d("TouchDebug", "Intercepting anchored ACTION_UP")
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (anchoredGestureActive) {
                        anchoredGestureActive = false
                        anchoredTarget = 0
                        return true
                    }
                }
            }
            return false
        }

        // Non-anchored keyboard handling
        if (keyboardContainer.visibility == View.VISIBLE && !isAnchored) {
            return true
        }

        // Non-anchored bookmarks handling
        if (::leftBookmarksView.isInitialized &&
                        leftBookmarksView.visibility == View.VISIBLE &&
                        !isAnchored
        ) {
            return true
        }

        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        // BinocularSbsLayout already gives us half the screen; use full given width.
        val halfWidth = widthSize
        val navBarHeight = navBarHeightPx
        val toggleBarWidth = toggleBarWidthPx
        val keyboardWidth = halfWidth - toggleBarWidth

        // Measure keyboard container first to get its actual height
        keyboardContainer.measure(
                MeasureSpec.makeMeasureSpec(keyboardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )

        val keyboardHeight =
                if (keyboardContainer.measuredHeight > 0) keyboardContainer.measuredHeight else 160

        val contentHeight =
                if (keyboardContainer.visibility == View.VISIBLE) {
                    heightSize - keyboardHeight
                } else {
                    heightSize - navBarHeight
                }

        // Measure WebView with different dimensions based on scroll mode
        // FIX: Respect the LayoutParams set by updateScrollBarsVisibility
        val lp = webViewsContainer.layoutParams

        if (isInScrollMode || isNavBarsHidden) {
            val targetWidth = if (lp != null && lp.width > 0) lp.width else 640
            val targetHeight = if (lp != null && lp.height > 0) lp.height else 480

            webViewsContainer.measure(
                    MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
            )
        } else {
            // Normal Mode
            // Use layout params if available (set by updateScrollBarsVisibility)
            // Default fallback: 640 - toggle bar = width, content height
            val targetWidth = if (lp != null && lp.width > 0) lp.width else (640 - toggleBarWidth)

            // For height in normal mode, we used MATCH_PARENT in updateScrollBarsVisibility
            // usually,
            // but sometimes explicit. If MATCH_PARENT (-1), we use the calculated contentHeight.
            val targetHeight =
                    if (lp != null && lp.height > 0) lp.height
                    else if (contentHeight > 0) contentHeight else 440

            webViewsContainer.measure(
                    MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
            )
        }

        // REFACTORED: rightEyeView measuring no longer needed - single viewport mode
        // rightEyeView.measure(
        //         MeasureSpec.makeMeasureSpec(halfWidth - toggleBarWidth, MeasureSpec.EXACTLY),
        //         MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY)
        // )

        leftNavigationBar.measure(
                MeasureSpec.makeMeasureSpec(halfWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(navBarHeight, MeasureSpec.EXACTLY)
        )

        // keyboardContainer is already measured above, but we can measure it again with EXACTLY if
        // we want to enforce constraints,
        // but UNSPECIFIED allowed it to size itself. Let's stick to the measurement we did.

        fullScreenOverlayContainer.measure(
                MeasureSpec.makeMeasureSpec(640, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        maskOverlay.measure(
                MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        // Measure leftEyeUIContainer and its children
        leftEyeUIContainer.measure(
                MeasureSpec.makeMeasureSpec(640, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        // Measure windowsOverviewContainer if visible
        windowsOverviewContainer?.let { woc ->
            if (woc.visibility == View.VISIBLE) {
                val containerWidth = 640 - toggleBarWidthPx
                val containerHeight = heightSize - navBarHeightPx
                woc.measure(
                        MeasureSpec.makeMeasureSpec(containerWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(containerHeight, MeasureSpec.EXACTLY)
                )
            }
        }

        // Measure leftEyeClipParent
        leftEyeClipParent.measure(
                MeasureSpec.makeMeasureSpec(640, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        setMeasuredDimension(widthSize, heightSize)
    }

    // at class top
    private var downWhen = 0L
    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val kbVisible = (keyboardContainer.visibility == View.VISIBLE)

        if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
            fullScreenTapDetector.onTouchEvent(event)
            return true
        }

        // Skip anchored gesture handling when in scroll mode - touches should go directly to
        // WebView
        if (isAnchored && !isInScrollMode) {
            // Track velocity for anchored interactions (bookmarks scroll, etc.)
            if (velocityTracker == null) {
                velocityTracker = android.view.VelocityTracker.obtain()
            }
            velocityTracker?.addMovement(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (anchoredGestureActive) return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (anchoredGestureActive) {
                        val (cursorX, cursorY) = getCursorInContainerCoords()

                        // Check for drag threshold
                        if (!isAnchoredDrag) {
                            val dx = kotlin.math.abs(cursorX - anchoredTouchStartX)
                            val dy = kotlin.math.abs(cursorY - anchoredTouchStartY)
                            if (dx > ANCHORED_TOUCH_SLOP || dy > ANCHORED_TOUCH_SLOP) {
                                isAnchoredDrag = true
                            }
                        }

                        if (isAnchoredDrag && anchoredTarget == 2) { // Bookmarks
                            val deltaY = lastAnchoredY - cursorY
                            if (::leftBookmarksView.isInitialized &&
                                            leftBookmarksView.visibility == View.VISIBLE
                            ) {
                                leftBookmarksView.handleAnchoredSwipe(deltaY)
                            }
                        }

                        lastAnchoredY = cursorY
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val wasTracking = anchoredGestureActive
                    anchoredGestureActive = false

                    if (wasTracking) {
                        if (!isAnchoredDrag) {
                            val (cursorX, cursorY) = getCursorInContainerCoords()

                            // Dispatch tap based on target determined at ACTION_DOWN
                            when (anchoredTarget) {
                                1 -> { // Keyboard
                                    // Managed by MainActivity dispatchKeyboardTap
                                }
                                2 -> { // Bookmarks
                                    if (::leftBookmarksView.isInitialized &&
                                                    leftBookmarksView.visibility == View.VISIBLE
                                    ) {
                                        // DebugLog.d("TouchDebug", "Dispatching anchored tap to
                                        // bookmarks")
                                        leftBookmarksView.handleAnchoredTap(
                                                cursorX - leftBookmarksView.left,
                                                cursorY - leftBookmarksView.top
                                        )
                                    }
                                }
                            }
                        } else if (anchoredTarget == 2) {
                            // Anchored Fling for Bookmarks
                            velocityTracker?.computeCurrentVelocity(1000)
                            val velocityY = velocityTracker?.yVelocity ?: 0f
                            // Pass raw velocityY.
                            handleAnchoredFling(velocityY)
                        }
                    }

                    anchoredTarget = 0
                    velocityTracker?.recycle()
                    velocityTracker = null
                    if (wasTracking) return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    if (anchoredGestureActive) {
                        anchoredGestureActive = false
                        anchoredTarget = 0
                        return true
                    }
                }
            }
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downWhen = event.eventTime
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dur = event.eventTime - downWhen
                val travelX = kotlin.math.abs(event.x - downX)
                val travelY = kotlin.math.abs(event.y - downY)
                val wasTap = dur < 300 && travelX < 8 && travelY < 8

                if (wasTap && !isAnchored) {
                    postDelayed({ updateScrollBarsVisibility() }, 500)
                }

                // Handle non-anchored tap for keyboard
                if (kbVisible && !isAnchored && wasTap) {
                    customKeyboard?.performFocusedTap()
                    return true
                }

                // Handle non-anchored tap for bookmarks
                if (::leftBookmarksView.isInitialized &&
                                leftBookmarksView.visibility == View.VISIBLE &&
                                !isAnchored &&
                                wasTap
                ) {
                    leftBookmarksView.performFocusedTap()
                    return true
                }
            }
        }

        // Let the keyboard keep handling movement (your current behavior)
        if (kbVisible && !isAnchored) {
            return customKeyboard?.dispatchTouchEvent(event) == true
        }

        // Let the bookmarks view handle movement in non-anchored mode
        if (::leftBookmarksView.isInitialized &&
                        leftBookmarksView.visibility == View.VISIBLE &&
                        !isAnchored
        ) {
            leftBookmarksView.handleDrag(event.x, event.action)
            return true
        }

        return super.onTouchEvent(event)
    }

    fun getKeyboardLocation(location: IntArray) {
        keyboardContainer.getLocationOnScreen(location)
    }

    fun getLogicalKeyboardLocation(location: IntArray) {
        location[0] = keyboardContainer.left
        location[1] = keyboardContainer.top
    }

    fun isPointInBookmarks(screenX: Float, screenY: Float): Boolean {
        if (!::leftBookmarksView.isInitialized || leftBookmarksView.visibility != View.VISIBLE)
                return false

        val bookmarksLocation = IntArray(2)
        leftBookmarksView.getLocationOnScreen(bookmarksLocation)

        return screenX >= bookmarksLocation[0] &&
                screenX <= bookmarksLocation[0] + leftBookmarksView.width &&
                screenY >= bookmarksLocation[1] &&
                screenY <= bookmarksLocation[1] + leftBookmarksView.height
    }

    fun isChatVisible(): Boolean {
        return ::chatView.isInitialized && chatView.visibility == View.VISIBLE
    }

    fun sendTextToChatInput(text: String) {
        if (!isChatVisible()) return
        chatView.sendTextToFocusedInput(text)
    }

    fun sendBackspaceToChatInput() {
        if (!isChatVisible()) return
        chatView.sendBackspaceToFocusedInput()
    }

    fun sendEnterToChatInput() {
        if (!isChatVisible()) return
        chatView.sendEnterToFocusedInput()
    }

    fun isPointInChat(screenX: Float, screenY: Float): Boolean {
        if (!isChatVisible()) return false

        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)

        val translatedX = screenX - uiLocation[0]
        val translatedY = screenY - uiLocation[1]

        val localX: Float
        val localY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            localX = (translatedX * cos + translatedY * sin) / uiScale
            localY = (-translatedX * sin + translatedY * cos) / uiScale
        } else {
            localX = translatedX / uiScale
            localY = translatedY / uiScale
        }

        return localX >= chatView.left &&
                localX <= chatView.right &&
                localY >= chatView.top &&
                localY <= chatView.bottom
    }

    fun dispatchChatTouchEvent(screenX: Float, screenY: Float) {
        if (!isChatVisible()) return

        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)

        val translatedX = screenX - uiLocation[0]
        val translatedY = screenY - uiLocation[1]

        val localX: Float
        val localY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            localX = (translatedX * cos + translatedY * sin) / uiScale
            localY = (-translatedX * sin + translatedY * cos) / uiScale
        } else {
            localX = translatedX / uiScale
            localY = translatedY / uiScale
        }

        val finalX = localX - chatView.left
        val finalY = localY - chatView.top
        chatView.handleAnchoredTap(finalX, finalY)
    }

    fun isPointInKeyboard(screenX: Float, screenY: Float): Boolean {
        if (keyboardContainer.visibility != View.VISIBLE) return false
        val kbView = customKeyboard ?: return false
        if (kbView.visibility != View.VISIBLE) return false

        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)

        val translatedX = screenX - uiLocation[0]
        val translatedY = screenY - uiLocation[1]

        val localX: Float
        val localY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            localX = (translatedX * cos + translatedY * sin) / uiScale
            localY = (-translatedX * sin + translatedY * cos) / uiScale
        } else {
            localX = translatedX / uiScale
            localY = translatedY / uiScale
        }

        return localX >= keyboardContainer.left &&
                localX <= keyboardContainer.right &&
                localY >= keyboardContainer.top &&
                localY <= keyboardContainer.bottom
    }

    fun getKeyboardSize(): Pair<Int, Int> {
        return Pair(keyboardContainer.width, keyboardContainer.height)
    }

    // Called from MainActivity when the cursor is over the keyboard
    // Called from MainActivity to dispatch a tap to the custom keyboard
    fun dispatchKeyboardTap(screenX: Float, screenY: Float) {
        val kbView = customKeyboard ?: return
        if (kbView.visibility != View.VISIBLE) return

        val groupLocation = IntArray(2)
        getLocationOnScreen(groupLocation)

        // Translate screen coordinates to be relative to the UI container's screen origin
        // Note: keyboardContainer is a child of leftEyeUIContainer
        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)

        val translatedX = screenX - uiLocation[0]
        val translatedY = screenY - uiLocation[1]

        val localX: Float
        val localY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()

            // Interaction is already scaled in MainActivity for non-anchored,
            // but in anchored mode screen coordinates are absolute.
            // However, the UI inside the container is logical.
            localX = (translatedX * cos + translatedY * sin) / uiScale
            localY = (-translatedX * sin + translatedY * cos) / uiScale
        } else {
            localX = translatedX / uiScale
            localY = translatedY / uiScale
        }

        // Subtract keyboard's logical position within the container
        val finalX = localX - keyboardContainer.left
        val finalY = localY - keyboardContainer.top

        // DebugLog.d("KeyboardDebug", "Keyboard tap: screen($screenX, $screenY) -> local($finalX,
        // $finalY)")
        kbView.handleAnchoredTap(finalX, finalY)
    }

    fun isDesktopMode(): Boolean {
        return isDesktopMode
    }

    fun setMobileUserAgent(ua: String) {
        mobileUserAgent = ua
        desktopUserAgent = buildDesktopUserAgentFromMobile(ua)
    }

    fun getDesktopUserAgent(): String {
        return desktopUserAgent
    }

    private fun buildDesktopUserAgentFromMobile(mobileUa: String): String {
        val chromeVersion = Regex("""Chrome/([0-9.]+)""").find(mobileUa)?.groupValues?.get(1)
        if (!chromeVersion.isNullOrBlank()) {
            // Use real runtime Chrome version so desktop mode is plausible, not hardcoded/fake.
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/$chromeVersion Safari/537.36"
        }

        // Fallback: keep runtime UA and strip obvious embedded/mobile markers.
        return mobileUa.replace(Regex(""";\s*wv\b"""), "")
                .replace(Regex("""\sVersion/\d+(\.\d+)*"""), "")
                .replace(Regex("""\sMobile\b"""), "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
    }

    fun updateBrowsingMode(isDesktop: Boolean) {
        // DebugLog.d("ModeToggle", "Updating browsing mode to: ${if (isDesktop) "desktop" else
        // "mobile"}")

        isDesktopMode = isDesktop

        // Step 1: Update WebView settings (user agent)
        // If on Netflix, we preserve the current UA (which should be the system default) to prevent
        // DRM errors.
        applyBrowsingModeToWebView(webView, isDesktop)

        // Step 2: Update viewport using JavaScript without forcing a complete reload
        val viewportContent =
                if (isDesktop) "width=1280, initial-scale=0.8"
                else "width=600, initial-scale=1.0, maximum-scale=1.0"

        webView.post {
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

            // Step 3: Soft reload the page by re-navigating to the current URL
            val currentUrl = webView.url
            if (currentUrl != null && currentUrl != "about:blank") {
                // Use loadUrl to "soft reload" and keep browsing history
                webView.loadUrl("javascript:window.location.href = window.location.href")
            }
        }

        // Update toggle button icons
        syncBrowsingModeUi()
    }

    private fun applyBrowsingModeToWebView(targetWebView: WebView, isDesktop: Boolean) {
        val isNetflix = targetWebView.url?.contains("netflix.com") == true
        if (isNetflix) return

        targetWebView.settings.apply {
            userAgentString =
                    if (isDesktop) {
                        desktopUserAgent
                    } else {
                        mobileUserAgent
                    }
            loadWithOverviewMode = true
            useWideViewPort = true
        }
    }

    private fun syncBrowsingModeUi() {
        webView.post {
            val leftButton = leftToggleBar.findViewById<FontIconView>(R.id.btnModeToggle)
            leftButton?.text =
                    context.getString(
                            if (isDesktopMode) R.string.fa_desktop else R.string.fa_mobile_screen
                    )
        }
    }

    private fun loadARDashboard() {
        webView.loadUrl(Constants.DEFAULT_URL)
    }

    // Method to disable text handles
    @SuppressLint("DiscouragedPrivateApi")
    private fun disableTextHandles(editText: EditText) {
        // Don’t allow long-press to start selection
        editText.isLongClickable = false
        editText.setOnLongClickListener { true }

        // Don’t allow selection mode (copy/paste toolbar)
        editText.setTextIsSelectable(false)

        // Block the selection action mode
        editText.customSelectionActionModeCallback =
                object : android.view.ActionMode.Callback {
                    override fun onCreateActionMode(
                            mode: android.view.ActionMode,
                            menu: android.view.Menu
                    ) = false
                    override fun onPrepareActionMode(
                            mode: android.view.ActionMode,
                            menu: android.view.Menu
                    ) = false
                    override fun onActionItemClicked(
                            mode: android.view.ActionMode,
                            item: android.view.MenuItem
                    ) = false
                    override fun onDestroyActionMode(mode: android.view.ActionMode) {}
                }

        // Block the insertion/caret handle action mode (API 23+)
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            editText.customInsertionActionModeCallback =
                    object : android.view.ActionMode.Callback {
                        override fun onCreateActionMode(
                                mode: android.view.ActionMode,
                                menu: android.view.Menu
                        ) = false
                        override fun onPrepareActionMode(
                                mode: android.view.ActionMode,
                                menu: android.view.Menu
                        ) = false
                        override fun onActionItemClicked(
                                mode: android.view.ActionMode,
                                item: android.view.MenuItem
                        ) = false
                        override fun onDestroyActionMode(mode: android.view.ActionMode) {}
                    }
        }

        // Optional: consume double-tap/long-press gestures that can trigger selection on some OEM
        // skins
        editText.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.eventTime - ev.downTime > 0) {
                // Let simple taps through; block long-press-ish starts if needed
                false
            } else {
                false
            }
        }
    }

    // In DualWebViewGroup.kt
    fun showEditField(initialText: String) {
        urlEditText.apply {
            text.clear()
            append(initialText)
            visibility = View.VISIBLE
            requestFocus()
            setSelection(text.length)
            bringToFront()
            // Add logging to verify state
        }
        // Make sure we're in edit mode
        isBookmarkEditing = true
        keyboardListener?.onShowKeyboard()

        // Force layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    private fun showButtonClickFeedback(button: View) {
        button.isPressed = true
        // DebugLog.d("buttonFeedbackDebug", "button feedback shown")
        Handler(Looper.getMainLooper())
                .postDelayed({ button.isPressed = false }, buttonFeedbackDuration)
    }

    private fun handleLeftMenuAction(buttonId: Int) {
        if (buttonId != R.id.btnAnchor) {
            keyboardListener?.onHideKeyboard()
        }

        val button = leftToggleBar.findViewById<View>(buttonId)

        when (buttonId) {
            R.id.btnModeToggle -> {
                button?.let { showButtonClickFeedback(it) }
                isDesktopMode = !isDesktopMode

                // Save preference
                context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("isDesktopMode", isDesktopMode)
                        .apply()

                updateBrowsingMode(isDesktopMode)
            }
            R.id.btnYouTube -> {
                button?.let { showButtonClickFeedback(it) }
                loadARDashboard()
            }
            R.id.btnBookmarks -> {
                button?.let { showButtonClickFeedback(it) }
                toggleBookmarks()
            }
            R.id.btnZoomOut -> {
                button?.let { showButtonClickFeedback(it) }
                handleZoomButtonClick("out")
            }
            R.id.btnZoomIn -> {
                button?.let { showButtonClickFeedback(it) }
                handleZoomButtonClick("in")
            }
            R.id.btnMask -> {
                button?.let { showButtonClickFeedback(it) }
                maskToggleListener?.onMaskTogglePressed()
            }
            R.id.btnAnchor -> {
                button?.let { showButtonClickFeedback(it) }
                anchorToggleListener?.onAnchorTogglePressed()
            }
        }
    }

    fun hideBookmarkEditing() {
        isBookmarkEditing = false
        urlEditText.apply {
            visibility = View.GONE
            text.clear()
        }

        // Force layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    fun isBookmarkEditing(): Boolean {
        return isBookmarkEditing
    }

    // Add this method to handle cursor hovering
    private fun updateButtonHoverStates(screenX: Float, screenY: Float) {
        // Clear all states initially
        clearAllHoverStates()

        if (::chatView.isInitialized && chatView.visibility == View.VISIBLE) {
            val uiLocation = IntArray(2)
            leftEyeUIContainer.getLocationOnScreen(uiLocation)

            val translatedX = screenX - uiLocation[0]
            val translatedY = screenY - uiLocation[1]

            val localX: Float
            val localY: Float

            if (isAnchored) {
                val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
                val cos = Math.cos(rotationRad).toFloat()
                val sin = Math.sin(rotationRad).toFloat()
                localX = (translatedX * cos + translatedY * sin) / uiScale
                localY = (-translatedX * sin + translatedY * cos) / uiScale
            } else {
                localX = translatedX / uiScale
                localY = translatedY / uiScale
            }

            val chatLocalX = localX - chatView.left
            val chatLocalY = localY - chatView.top

            if (chatView.updateHoverLocal(chatLocalX, chatLocalY)) {
                customKeyboard?.updateHover(-1f, -1f)
                return
            }
        }

        // Check bottom navigation bar buttons ONLY if nav bar is visible
        if (leftNavigationBar.visibility == View.VISIBLE) {
            navButtons.forEach { (_, navButton) ->
                if (isOver(navButton.left, screenX, screenY)) {
                    navButton.isHovered = true
                    navButton.left.isHovered = true
                    navButton.right.isHovered = true
                    customKeyboard?.clearHover() // Clear keyboard hover
                    return // Found the hovered button, stop checking
                }
            }
        }

        // Check left toggle bar buttons
        val toggleBarButtons =
                listOf(
                        Triple(R.id.btnModeToggle, "ModeToggle") { isHoveringModeToggle = true },
                        Triple(R.id.btnYouTube, "Dashboard") { isHoveringDashboardToggle = true },
                        Triple(R.id.btnBookmarks, "Bookmarks") { isHoveringBookmarksMenu = true },
                        Triple(R.id.btnZoomOut, "ZoomOut") { isHoveringZoomOut = true },
                        Triple(R.id.btnZoomIn, "ZoomIn") { isHoveringZoomIn = true },
                        Triple(R.id.btnMask, "Mask") { isHoveringMaskToggle = true },
                        Triple(R.id.btnAnchor, "Anchor") { isHoveringAnchorToggle = true }
                )

        for ((buttonId, _, setHoverFlag) in toggleBarButtons) {
            val button = leftToggleBar.findViewById<View>(buttonId)
            if (isOver(button, screenX, screenY)) {
                button?.isHovered = true
                setHoverFlag()
                clearNavigationButtonStates()
                // DebugLog.d("HoverDebug", "Hovering over toggle button: $name")
                customKeyboard?.updateHover(-1f, -1f) // Clear keyboard hover
                return // Found the hovered button, stop checking
            }
        }

        // Check Windows button separately (programmatically created, no resource ID)
        windowsButton?.let { btn ->
            if (isOver(btn, screenX, screenY)) {
                btn.isHovered = true
                isHoveringWindowsToggle = true
                clearNavigationButtonStates()
                customKeyboard?.updateHover(-1f, -1f)
                return
            }
        }

        // Check settings window elements if visible
        if (isSettingsVisible) {
            settingsMenu?.let { menu ->
                val settingsElements =
                        listOf(
                                R.id.volumeSeekBar,
                                R.id.brightnessSeekBar,
                                R.id.btnToggleForceDark,
                                R.id.smoothnessSeekBar,
                                R.id.screenSizeSeekBar,
                                R.id.btnResetScreenSize,
                                R.id.fontSizeSeekBar,
                                R.id.btnResetFontSize,
                                R.id.btnResetWebpageZoom,
                                R.id.colorWheelView,
                                R.id.btnResetTextColor,
                                R.id.horizontalPosSeekBar,
                                R.id.verticalPosSeekBar,
                                R.id.btnResetPosition,
                                R.id.btnHelp,
                                R.id.btnCloseSettings,
                                R.id.btnGroqApiKey
                        )
                for (id in settingsElements) {
                    val view = menu.findViewById<View>(id)
                    if (isOver(view, screenX, screenY)) {
                        view?.isHovered = true
                        // DebugLog.d("HoverDebug", "Hovering over settings element: $id")
                        customKeyboard?.updateHover(-1f, -1f) // Clear keyboard hover
                        return // Found the hovered element, stop checking
                    }
                }
            }
        }

        // Check active dialog buttons if visible
        if (dialogContainer.visibility == View.VISIBLE) {
            val dialogView = dialogContainer.getChildAt(0) as? ViewGroup
            dialogView?.let { viewGroup ->
                // Dialog structure: Title(0), Message(1), optional Input(2), ButtonContainer(last)
                val btnContainer = viewGroup.getChildAt(viewGroup.childCount - 1) as? ViewGroup
                btnContainer?.let { container ->
                    for (i in 0 until container.childCount) {
                        val button = container.getChildAt(i)
                        if (isOver(button, screenX, screenY)) {
                            button.isHovered = true
                            // DebugLog.d("HoverDebug", "Hovering over dialog button: $i")
                            customKeyboard?.updateHover(-1f, -1f) // Clear keyboard hover
                            return
                        }
                    }
                }
            }
        }

        // Check windows overview if visible
        if (windowsOverviewContainer?.visibility == View.VISIBLE) {
            val woc = windowsOverviewContainer ?: return

            // Use anchored coordinates if needed
            if (isAnchored) {
                val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)

                // Perform hit testing relative to leftEyeUIContainer
                // woc is a child of leftEyeUIContainer
                val wocLeft = woc.left + woc.translationX
                val wocTop = woc.top + woc.translationY

                if (localX >= wocLeft &&
                                localX <= wocLeft + woc.width &&
                                localY >= wocTop &&
                                localY <= wocTop + woc.height
                ) {

                    val container = woc.getChildAt(0) as? LinearLayout ?: return
                    // Container is inside ScrollView woc
                    // local in woc
                    val xInWoc = localX - wocLeft + woc.scrollX
                    val yInWoc = localY - wocTop + woc.scrollY

                    val containerLeft = container.left + container.translationX
                    val containerTop = container.top + container.translationY

                    val xInContainer = xInWoc - containerLeft
                    val yInContainer = yInWoc - containerTop

                    // Helper for checking children
                    for (i in 0 until container.childCount) {
                        val child = container.getChildAt(i)
                        if (xInContainer >= child.left &&
                                        xInContainer <= child.right &&
                                        yInContainer >= child.top &&
                                        yInContainer <= child.bottom
                        ) {

                            if (i == 0) { // Add Button
                                child.isHovered = true
                                hoveredWindowsOverviewItem = child
                                customKeyboard?.updateHover(-1f, -1f)
                                return
                            }

                            // Rows
                            if (child is ViewGroup) {
                                val xInChild = xInContainer - child.left
                                val yInChild = yInContainer - child.top

                                for (j in 0 until child.childCount) {
                                    val item = child.getChildAt(j)
                                    if (xInChild >= item.left &&
                                                    xInChild <= item.right &&
                                                    yInChild >= item.top &&
                                                    yInChild <= item.bottom
                                    ) {

                                        // Check for delete button first (FontIconView child)
                                        if (item is ViewGroup) {
                                            val xInItem = xInChild - item.left
                                            val yInItem = yInChild - item.top
                                            for (k in 0 until item.childCount) {
                                                val itemChild = item.getChildAt(k)
                                                if (itemChild is FontIconView &&
                                                                xInItem >= itemChild.left &&
                                                                xInItem <= itemChild.right &&
                                                                yInItem >= itemChild.top &&
                                                                yInItem <= itemChild.bottom
                                                ) {
                                                    itemChild.isHovered = true
                                                    hoveredWindowsOverviewItem = itemChild
                                                    customKeyboard?.updateHover(-1f, -1f)
                                                    return
                                                }
                                            }
                                        }

                                        // Set hover on the whole item
                                        item.isHovered = true
                                        hoveredWindowsOverviewItem = item
                                        customKeyboard?.updateHover(-1f, -1f)
                                        return
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Non-anchored mode: Use the isOver function with getGlobalVisibleRect
                val container = woc.getChildAt(0) as? LinearLayout ?: return

                // Check all children (add button + rows of window items)
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)

                    // First child (i == 0) is the Add Button
                    if (i == 0 && isOver(child, screenX, screenY)) {
                        child.isHovered = true
                        hoveredWindowsOverviewItem = child
                        customKeyboard?.updateHover(-1f, -1f)
                        return
                    }

                    // Other children are rows containing window items
                    if (child is ViewGroup) {
                        for (j in 0 until child.childCount) {
                            val windowItem = child.getChildAt(j)

                            // Check for delete button first (it's a FontIconView child of the
                            // window item)
                            if (windowItem is ViewGroup) {
                                for (k in 0 until windowItem.childCount) {
                                    val itemChild = windowItem.getChildAt(k)
                                    // Delete button is a FontIconView with the X icon
                                    if (itemChild is FontIconView &&
                                                    isOver(itemChild, screenX, screenY)
                                    ) {
                                        itemChild.isHovered = true
                                        hoveredWindowsOverviewItem = itemChild
                                        customKeyboard?.updateHover(-1f, -1f)
                                        return
                                    }
                                }
                            }

                            // Then check the whole window item
                            if (isOver(windowItem, screenX, screenY)) {
                                windowItem.isHovered = true
                                hoveredWindowsOverviewItem = windowItem
                                customKeyboard?.updateHover(-1f, -1f)
                                return
                            }
                        }
                    }
                }
            }
        }

        // Check bookmarks view if visible
        if (isBookmarksExpanded()) {
            val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)

            val finalX = localX - leftBookmarksView.left
            val finalY = localY - leftBookmarksView.top

            if (leftBookmarksView.updateHover(finalX, finalY)) {
                customKeyboard?.updateHoverScreen(-1f, -1f, 1f) // Clear keyboard hover
                return
            }
        }

        // Check scrollbars if visible (UI scale < 0.99f or forced visible)
        if (horizontalScrollBar.visibility == View.VISIBLE) {
            val location = IntArray(2)
            horizontalScrollBar.getLocationOnScreen(location)
            if (screenX >= location[0] &&
                            screenX <= location[0] + horizontalScrollBar.width &&
                            screenY >= location[1] &&
                            screenY <= location[1] + horizontalScrollBar.height
            ) {

                // Check children (arrows and track)
                for (i in 0 until horizontalScrollBar.childCount) {
                    val child = horizontalScrollBar.getChildAt(i)
                    if (isOver(child, screenX, screenY)) {
                        child.isHovered = true
                        child.isActivated = true

                        // If we are over the track container, check the thumb specifically
                        if (child == horizontalScrollBar.getChildAt(1)) {
                            if (isOver(hScrollThumb, screenX, screenY)) {
                                hScrollThumb.isHovered = true
                                hScrollThumb.isActivated = true
                            }
                        }
                    }
                }
                customKeyboard?.updateHover(-1f, -1f)
                return
            }
        }

        if (verticalScrollBar.visibility == View.VISIBLE) {
            val location = IntArray(2)
            verticalScrollBar.getLocationOnScreen(location)
            if (screenX >= location[0] &&
                            screenX <= location[0] + verticalScrollBar.width &&
                            screenY >= location[1] &&
                            screenY <= location[1] + verticalScrollBar.height
            ) {

                // Check children (arrows and track)
                for (i in 0 until verticalScrollBar.childCount) {
                    val child = verticalScrollBar.getChildAt(i)
                    if (isOver(child, screenX, screenY)) {
                        child.isHovered = true
                        child.isActivated = true

                        // If we are over the track container, check the thumb specifically
                        if (child == verticalScrollBar.getChildAt(1)) {
                            if (isOver(vScrollThumb, screenX, screenY)) {
                                vScrollThumb.isHovered = true
                                vScrollThumb.isActivated = true
                            }
                        }
                    }
                }
                customKeyboard?.updateHover(-1f, -1f)
                return
            }
        }

        // Check keyboard elements if visible
        if (keyboardContainer.visibility == View.VISIBLE) {
            val kbView = customKeyboard
            if (kbView != null && kbView.visibility == View.VISIBLE) {
                val uiLocation = IntArray(2)
                leftEyeUIContainer.getLocationOnScreen(uiLocation)

                // Use screen coordinates for keyboard hit testing to avoid drift
                // Pass raw screenX/screenY and let CustomKeyboardView check against actual screen
                // positions
                kbView.updateHoverScreen(screenX, screenY, uiScale)

                // We don't return here because updateHoverScreen will internally check if a key was
                // hit.
                // However, we should check if a key WAS hit to know if we should "consume" the
                // hover event.
                // For now, if the keyboard is visible, we let it process.
                return // Stop checking after keyboard processing
            }
        }
    }

    // Helper function to clear all hover states
    private fun clearAllHoverStates() {
        // Clear toggle button states
        isHoveringModeToggle = false
        isHoveringDashboardToggle = false
        isHoveringBookmarksMenu = false
        isHoveringZoomIn = false
        isHoveringZoomOut = false

        isHoveringMaskToggle = false
        isHoveringAnchorToggle = false
        isHoveringWindowsToggle = false

        // Clear windows overview hover
        hoveredWindowsOverviewItem?.isHovered = false
        hoveredWindowsOverviewItem = null

        // Clear visual hover states
        listOf(
                        R.id.btnModeToggle,
                        R.id.btnYouTube,
                        R.id.btnBookmarks,
                        R.id.btnZoomIn,
                        R.id.btnZoomOut,
                        R.id.btnMask,
                        R.id.btnAnchor
                )
                .forEach { id -> leftToggleBar.findViewById<View>(id)?.isHovered = false }

        // Clear Windows button hover state (programmatically created)
        windowsButton?.isHovered = false

        // Clear settings hover states
        if (isSettingsVisible) {
            settingsMenu?.let { menu ->
                val settingsElements =
                        listOf(
                                R.id.volumeSeekBar,
                                R.id.brightnessSeekBar,
                                R.id.btnToggleForceDark,
                                R.id.smoothnessSeekBar,
                                R.id.screenSizeSeekBar,
                                R.id.btnResetScreenSize,
                                R.id.fontSizeSeekBar,
                                R.id.btnResetFontSize,
                                R.id.btnResetWebpageZoom,
                                R.id.colorWheelView,
                                R.id.btnResetTextColor,
                                R.id.horizontalPosSeekBar,
                                R.id.verticalPosSeekBar,
                                R.id.btnResetPosition,
                                R.id.btnHelp,
                                R.id.btnCloseSettings,
                                R.id.btnGroqApiKey
                        )
                for (id in settingsElements) {
                    menu.findViewById<View>(id)?.isHovered = false
                }
            }
        }

        // Clear dialog button states
        if (dialogContainer.visibility == View.VISIBLE) {
            val dialogView = dialogContainer.getChildAt(0) as? ViewGroup
            dialogView?.let { viewGroup ->
                val btnContainer = viewGroup.getChildAt(viewGroup.childCount - 1) as? ViewGroup
                btnContainer?.let { container ->
                    for (i in 0 until container.childCount) {
                        container.getChildAt(i).isHovered = false
                    }
                }
            }
        }

        // Clear navigation button states
        clearNavigationButtonStates()

        if (::chatView.isInitialized) {
            chatView.clearHover()
        }

        // Clear keyboard hover
        customKeyboard?.updateHoverScreen(-1f, -1f, 1f)

        // Clear scroll bar hover states
        if (horizontalScrollBar.visibility == View.VISIBLE) {
            for (i in 0 until horizontalScrollBar.childCount) {
                horizontalScrollBar.getChildAt(i).isHovered = false
                horizontalScrollBar.getChildAt(i).isActivated = false
            }
            hScrollThumb.isHovered = false
            hScrollThumb.isActivated = false
        }
        if (verticalScrollBar.visibility == View.VISIBLE) {
            for (i in 0 until verticalScrollBar.childCount) {
                verticalScrollBar.getChildAt(i).isHovered = false
                verticalScrollBar.getChildAt(i).isActivated = false
            }
            vScrollThumb.isHovered = false
            vScrollThumb.isActivated = false
        }
    }

    // Helper method to check if a point is within any visible scrollbar
    fun isPointInScrollbar(screenX: Float, screenY: Float): Boolean {
        return isOver(horizontalScrollBar, screenX, screenY) ||
                isOver(verticalScrollBar, screenX, screenY)
    }

    // Dispatch touch/click to the appropriate scrollbar element
    fun dispatchScrollbarTouch(screenX: Float, screenY: Float) {
        fun getLocalPoint(container: ViewGroup): Pair<Float, Float>? {
            if (container.visibility != View.VISIBLE) return null
            val rect = android.graphics.Rect()
            if (!container.getGlobalVisibleRect(rect)) return null
            if (screenX < rect.left ||
                            screenX > rect.right ||
                            screenY < rect.top ||
                            screenY > rect.bottom
            ) {
                return null
            }
            val scaleX = if (container.scaleX == 0f) 1f else container.scaleX
            val scaleY = if (container.scaleY == 0f) 1f else container.scaleY
            val localX = (screenX - rect.left) / scaleX
            val localY = (screenY - rect.top) / scaleY
            return localX to localY
        }

        fun dispatchToContainer(container: ViewGroup) {
            val localPoint = getLocalPoint(container) ?: return
            val localX = localPoint.first
            val localY = localPoint.second
            // Check which child is hit
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (localX >= child.left &&
                                localX <= child.right &&
                                localY >= child.top &&
                                localY <= child.bottom
                ) {

                    if (child.hasOnClickListeners()) {
                        child.performClick()
                    } else {
                        // For track/thumb, we need to simulate touch events
                        // The track listener reacts to ACTION_UP
                        val childLocalX = localX - child.left
                        val childLocalY = localY - child.top

                        val downEvent =
                                MotionEvent.obtain(
                                        SystemClock.uptimeMillis(),
                                        SystemClock.uptimeMillis(),
                                        MotionEvent.ACTION_DOWN,
                                        childLocalX,
                                        childLocalY,
                                        0
                                )
                        child.dispatchTouchEvent(downEvent)
                        downEvent.recycle()

                        val upEvent =
                                MotionEvent.obtain(
                                        SystemClock.uptimeMillis(),
                                        SystemClock.uptimeMillis(),
                                        MotionEvent.ACTION_UP,
                                        childLocalX,
                                        childLocalY,
                                        0
                                )
                        child.dispatchTouchEvent(upEvent)
                        upEvent.recycle()
                    }
                    return
                }
            }
        }

        if (isOver(horizontalScrollBar, screenX, screenY)) {
            dispatchToContainer(horizontalScrollBar)
            return
        }

        if (isOver(verticalScrollBar, screenX, screenY)) {
            dispatchToContainer(verticalScrollBar)
            return
        }
    }

    fun isNavBarVisible(): Boolean {
        // Check both visibility AND scroll mode - in scroll mode, bars are hidden even during fade
        // animation
        return !isInScrollMode && leftNavigationBar.visibility == View.VISIBLE
    }

    fun isToggleBarVisible(): Boolean {
        return leftToggleBar.visibility == View.VISIBLE
    }

    private fun isPointInToggleBarButton(screenX: Float, screenY: Float): Boolean {
        if (leftToggleBar.visibility != View.VISIBLE) return false
        val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)
        val buttonIds = listOf(
            R.id.btnModeToggle,
            R.id.btnYouTube,
            R.id.btnBookmarks,
            R.id.btnZoomOut,
            R.id.btnZoomIn,
            R.id.btnMask,
            R.id.btnAnchor
        )
        if (buttonIds.any { id -> isPointInChild(localX, localY, leftToggleBar, leftToggleBar.findViewById(id)) }) {
            return true
        }
        return windowsButton?.let { isPointInChild(localX, localY, leftToggleBar, it) } == true
    }

    fun isPointInToggleBar(screenX: Float, screenY: Float): Boolean {
        return isPointInToggleBarButton(screenX, screenY)
    }

    fun isPointInNavBar(screenX: Float, screenY: Float): Boolean {
        if (leftNavigationBar.visibility != View.VISIBLE) return false
        val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)
        return navButtons.entries.any { isPointInChild(localX, localY, leftNavigationBar, it.value.left) }
    }

    private fun isPointInView(containerX: Float, containerY: Float, view: View?): Boolean {
        if (view == null || view.visibility != View.VISIBLE) return false
        return containerX >= view.left &&
                containerX <= view.right &&
                containerY >= view.top &&
                containerY <= view.bottom
    }

    private fun isPointInChild(
            containerX: Float,
            containerY: Float,
            parent: View,
            child: View?
    ): Boolean {
        if (child == null || parent.visibility != View.VISIBLE || child.visibility != View.VISIBLE)
                return false
        val localX = containerX - parent.left
        val localY = containerY - parent.top
        return localX >= child.left &&
                localX <= child.right &&
                localY >= child.top &&
                localY <= child.bottom
    }

    fun isPointInRestoreButton(x: Float, y: Float): Boolean {
        if (btnShowNavBars.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        btnShowNavBars.getLocationOnScreen(loc)
        return x >= loc[0] &&
                x <= loc[0] + (btnShowNavBars.width * uiScale) &&
                y >= loc[1] &&
                y <= loc[1] + (btnShowNavBars.height * uiScale)
    }

    fun performRestoreButtonClick() {
        if (btnShowNavBars.visibility == View.VISIBLE) {
            btnShowNavBars.performClick()
        }
    }

    fun isWindowsOverviewVisible(): Boolean {
        return windowsOverviewContainer?.visibility == View.VISIBLE
    }

    fun isPointInWindowsOverview(x: Float, y: Float): Boolean {
        val woc = windowsOverviewContainer ?: return false
        if (woc.visibility != View.VISIBLE) return false

        val loc = IntArray(2)
        woc.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + woc.width && y >= loc[1] && y <= loc[1] + woc.height
    }

    fun performWindowsOverviewClick() {
        val current = hoveredWindowsOverviewItem
        if (current == null || !current.isAttachedToWindow || !current.isHovered) {
            refreshHoverAtCurrentCursor()
        }

        val item = hoveredWindowsOverviewItem ?: return
        if (!item.isAttachedToWindow || !item.isHovered) return

        showButtonClickFeedback(item)
        item.performClick()
    }

    private fun isOver(button: View?, screenX: Float, screenY: Float): Boolean {
        if (button == null || button.visibility != View.VISIBLE) return false

        // Use getGlobalVisibleRect for accurate screen bounds detection
        val rect = android.graphics.Rect()
        if (!button.getGlobalVisibleRect(rect)) return false

        return screenX >= rect.left &&
                screenX <= rect.right &&
                screenY >= rect.top &&
                screenY <= rect.bottom
    }

    fun handleNavigationClick(screenX: Float, screenY: Float) {
        if (isInScrollMode) return

        val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)

        if (isSettingsVisible && settingsMenu != null) {
            if (isPointInView(localX, localY, settingsMenu)) {
                dispatchSettingsTouchEvent(screenX, screenY)
                return
            }
        }

        if (leftToggleBar.visibility == View.VISIBLE) {
            val toggleBarButtons =
                    listOf(
                            R.id.btnModeToggle,
                            R.id.btnYouTube,
                            R.id.btnBookmarks,
                            R.id.btnZoomOut,
                            R.id.btnZoomIn,
                            R.id.btnMask,
                            R.id.btnAnchor
                    )

            for (buttonId in toggleBarButtons) {
                val button = leftToggleBar.findViewById<View>(buttonId)
                if (isPointInChild(localX, localY, leftToggleBar, button)) {
                    handleLeftMenuAction(buttonId)
                    return
                }
            }

            windowsButton?.let { btn ->
                if (isPointInChild(localX, localY, leftToggleBar, btn)) {
                    showButtonClickFeedback(btn)
                    toggleWindowMode()
                    return
                }
            }
        }

        if (leftNavigationBar.visibility == View.VISIBLE) {
            navButtons.entries.firstOrNull {
                    isPointInChild(localX, localY, leftNavigationBar, it.value.left)
                }?.let { (key, button) ->
                    triggerNavigationAction(key, button)
                }
        }
    }

    private fun triggerNavigationAction(key: String, button: NavButton) {
        keyboardListener?.onHideKeyboard()
        showButtonClickFeedback(button.left)
        showButtonClickFeedback(button.right)
        if (key == "hide") {
            setNavBarsHidden(true) // Hide nav bars but keep cursor visible
            return
        }
        if (key == "chat") {
            toggleChat()
            return
        }
        navigationListener?.let { listener ->
            when (key) {
                "back" -> listener.onNavigationBackPressed()
                "forward" -> listener.onNavigationForwardPressed()
                "home" -> listener.onHomePressed()
                "link" -> listener.onHyperlinkPressed()
                "settings" -> listener.onSettingsPressed()
                "refresh" -> listener.onRefreshPressed()
                "quit" -> listener.onQuitPressed()
            }
        }
    }

    fun resetPositions() {
        // Reset translations
        _translationX = 0f
        _translationY = 0f
        _rotationZ = 0f

        // Reset translations on views
        leftEyeUIContainer.translationX = 0f
        leftEyeUIContainer.translationY = 0f
        leftEyeUIContainer.rotation = 0f

        // Reset translations on views
        leftEyeClipParent.translationX = 0f
        leftEyeClipParent.translationY = 0f
        leftEyeClipParent.rotation = 0f

        // Also reset fullscreen overlay
        fullScreenOverlayContainer.translationX = 0f
        fullScreenOverlayContainer.translationY = 0f
        fullScreenOverlayContainer.rotation = 0f

        postDelayed(
                {
                    startRefreshing()
                    requestLayout()
                    invalidate()
                },
                100
        )
    }

    private fun handleZoomButtonClick(direction: String) {
        // For ar_nav.html: delegate to the 3D map's own zoom handler
        webView.evaluateJavascript(
                """
        (function() {
            if (window.__arNavZoom) { window.__arNavZoom('$direction'); return; }
            document.body.style.zoom = "${if (direction == "in") currentWebZoom * 1.1f else currentWebZoom * 0.9f}";
        })();
    """,
                null
        )

        // Only update CSS zoom tracking for non-AR pages
        val url = webView.url ?: ""
        if (!url.contains("ar_nav.html")) {
            val zoomFactor = if (direction == "in") 1.1f else 0.9f
            currentWebZoom *= zoomFactor
            context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putFloat("webZoomLevel", currentWebZoom)
                    .apply()
        }

        postDelayed(
                {
                    updateScrollBarsVisibility()
                    lastScrollBarCheckTime = System.currentTimeMillis()
                },
                100
        )
    }

    fun refreshBothBookmarks() {
        // Refresh left bookmarks view
        leftBookmarksView.refreshBookmarks()
        leftBookmarksView.visibility = View.VISIBLE
        leftBookmarksView.bringToFront()
        leftBookmarksView.measure(
                MeasureSpec.makeMeasureSpec(420, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        leftBookmarksView.layout(
                leftBookmarksView.left,
                leftBookmarksView.top,
                leftBookmarksView.left + 480,
                leftBookmarksView.top + leftBookmarksView.measuredHeight
        )

        // Force a layout update
        leftBookmarksView.post {
            leftBookmarksView.requestLayout()
            leftBookmarksView.invalidate()
            // Ensure the mirroring is updated
            startRefreshing()
        }
    }

    // In DualWebViewGroup.kt, add these methods:
    fun startAnchoring() {
        isAnchored = true
        webView.visibility = View.VISIBLE
        // REFACTORED: rightEyeView no longer used - single viewport mode
        // rightEyeView.visibility = View.VISIBLE
        startRefreshing()

        // Update scrollbars immediately
        updateScrollBarsVisibility()

        // Update keyboard behavior
        customKeyboard?.setAnchoredMode(true)

        // Update bookmarks view mode
        if (::leftBookmarksView.isInitialized) {
            leftBookmarksView.setAnchoredMode(true)
        }

        // Use solid anchor icon when anchored
        leftToggleBar.findViewById<FontIconView>(R.id.btnAnchor)?.text =
                context.getString(R.string.fa_anchor)
    }

    fun stopAnchoring() {
        isAnchored = false
        resetPositions()

        // Update scrollbars immediately
        updateScrollBarsVisibility()

        // Update keyboard behavior
        customKeyboard?.setAnchoredMode(false)

        // Update bookmarks view mode
        if (::leftBookmarksView.isInitialized) {
            leftBookmarksView.setAnchoredMode(false)
        }

        // Use barred anchor icon when not anchored
        leftToggleBar.findViewById<FontIconView>(R.id.btnAnchor)?.text =
                context.getString(R.string.fa_anchor_circle_xmark)
        webView.visibility = View.VISIBLE
        // REFACTORED: rightEyeView no longer used - single viewport mode
        // rightEyeView.visibility = View.VISIBLE

        updateUiTranslation()

        post {
            startRefreshing()
            invalidate()
        }
    }

    fun setBookmarksView(bookmarksView: BookmarksView) {
        this.leftBookmarksView =
                bookmarksView.apply {
                    val params =
                            MarginLayoutParams(420, LayoutParams.WRAP_CONTENT).apply {
                                leftMargin = toggleBarWidthPx // After toggle bar
                                topMargin = 10 // Move higher up
                            }
                    layoutParams = params
                    elevation = 1000f
                    visibility = View.GONE
                }

        // Remove existing view if present
        (leftBookmarksView.parent as? ViewGroup)?.removeView(leftBookmarksView)

        // Add view to hierarchy
        leftEyeUIContainer.addView(leftBookmarksView)
        leftBookmarksView.bringToFront()

        // Request layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    fun handleAnchoredFling(velocity: Float) {
        if (isBookmarksExpanded()) {
            // No-op for bookmarks in anchored mode (pagination used)
        } else {
            // Forward to general handleFling which handles WebView scroll
            handleFling(velocity)
        }
    }

    fun handleFling(velocityX: Float) {
        // DebugLog.d("Fling Debug", "Fling handled by DualWebViewGroup")

        // First check if bookmarks are visible (Non-Anchored Mode legacy behavior)
        if (leftBookmarksView.visibility == View.VISIBLE && !isAnchored) {
            // DebugLog.d("DualWebViewGroup", "Delegating fling to bookmarks: velocity=$velocityX")

            // Determine direction based on velocity and delegate to both views
            val isForward = velocityX > 0

            // Update both left and right bookmark views to maintain synchronization
            leftBookmarksView.handleFling(isForward)

            // Force layout update to ensure visual sync between views
            post {
                requestLayout()
                invalidate()
            }
            return
        }

        // If bookmarks aren't visible, handle normal scrolling behavior
        // Slow down the velocity for smoother scrolling
        val slowedVelocity = velocityX * 0.15f

        // Handle vertical scrolling
        webView.evaluateJavascript(
                """
            (function() {
                window.scrollBy({
                    top: ${(-slowedVelocity).toInt()},
                    behavior: 'smooth'
                });
            })();
        """,
                null
        )

        // Provide a native scroll backup only if JS execution fails or is slow?
        // Actually, since we want to avoid double-scroll bouncing, relying on JS scrollBy is safer
        // with 'smooth' behavior.
        // However, if we remove this, we rely solely on JS.
        // Let's remove the unconditional native backup to prevent fighting/overshoot.
    }

    private fun initializeToggleButtons() {
        DebugLog.d(
                "ViewDebug",
                """
    Toggle bar parent: ${leftToggleBar.parent?.javaClass?.simpleName}
    Toggle bar children: ${(leftToggleBar as? ViewGroup)?.childCount ?: "Not a ViewGroup"}
    UI Container children count: ${leftEyeUIContainer.childCount}
    UI Container children:
    ${(0 until leftEyeUIContainer.childCount).joinToString("n") { index ->
            val child = leftEyeUIContainer.getChildAt(index)
            "Child $index: ${child.javaClass.simpleName} (${child.hashCode()})"+
                    "n    Location: (${child.x}, ${child.y})"+
                    "n    Size: ${child.width}x${child.height}"+
                    "n    Translation: (${child.translationX}, ${child.translationY})"
        }}
""".trimIndent()
        )

        // Get references to all buttons
        val leftModeToggleButton = leftToggleBar.findViewById<FontIconView>(R.id.btnModeToggle)
        val leftDashboardButton = leftToggleBar.findViewById<FontIconView>(R.id.btnYouTube)
        val leftBookmarksButton = leftToggleBar.findViewById<FontIconView>(R.id.btnBookmarks)
        val leftZoomInButton = leftToggleBar.findViewById<FontIconView>(R.id.btnZoomIn)
        val leftZoomOutButton = leftToggleBar.findViewById<FontIconView>(R.id.btnZoomOut)
        val leftMaskButton = leftToggleBar.findViewById<FontIconView>(R.id.btnMask)
        val leftAnchorButton = leftToggleBar.findViewById<FontIconView>(R.id.btnAnchor)

        // Create Windows button programmatically since it's not in XML
        val leftWindowsButton =
                FontIconView(context).apply {
                    id = View.generateViewId() // Generate an ID for the view
                    tag = "btnWindows" // Tag for identification
                    configureToggleButton(R.string.fa_window_restore)
                }
        windowsButton = leftWindowsButton // Store reference for hover/click handling

        // Insert it into toggle bar - we need to add it to the layout
        if (leftToggleBar is ViewGroup) {
            // Find where to insert - maybe after dashboard button?
            // Actually, toggle_bar.xml is a LinearLayout (based on usage).
            // We can just addView. But we need to insert it in the correct order visually.
            // XML has: Mode, YouTube, Bookmarks, ZoomOut, ZoomIn, Mask, Anchor.
            // Let's put Windows button after Bookmarks.
            val index = (leftToggleBar as ViewGroup).indexOfChild(leftBookmarksButton) + 1
            if (index > 0) {
                (leftToggleBar as ViewGroup).addView(leftWindowsButton, index)
            } else {
                (leftToggleBar as ViewGroup).addView(leftWindowsButton)
            }
        }

        // Calculate positioning constants
        val iconPadding = 4.dp()
        val orderedButtons =
                listOf(
                        leftModeToggleButton,
                        leftDashboardButton,
                        leftBookmarksButton,
                        leftWindowsButton,
                        leftZoomOutButton,
                        leftZoomInButton,
                        leftMaskButton,
                        leftAnchorButton
                )

        orderedButtons.forEach { button ->
            try {
                button?.apply {
                    layoutParams =
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                    visibility = View.VISIBLE
                    background =
                            ContextCompat.getDrawable(context, R.drawable.nav_button_background)
                    // Icon already set via XML text attribute
                    if (id == R.id.btnAnchor) {
                        text = if (isAnchored) context.getString(R.string.fa_anchor)
                               else context.getString(R.string.fa_anchor_circle_xmark)
                    }
                    setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                    elevation = 4f
                    alpha = 1f
                    isEnabled = true
                    setOnTouchListener { v, _ ->
                        val location = IntArray(2)
                        v.getLocationOnScreen(location)
                        val parentLocation = IntArray(2)
                        leftToggleBar.getLocationOnScreen(parentLocation)

                        false
                    }
                }
            } catch (e: Exception) {
                Log.e("ToggleButton", "Error configuring button", e)
            }
        }

        mapOf(
                        leftModeToggleButton to R.id.btnModeToggle,
                        leftDashboardButton to R.id.btnYouTube,
                        leftBookmarksButton to R.id.btnBookmarks,
                        leftZoomOutButton to R.id.btnZoomOut,
                        leftZoomInButton to R.id.btnZoomIn,
                        leftMaskButton to R.id.btnMask,
                        leftAnchorButton to R.id.btnAnchor
                )
                .forEach { (button, id) -> button?.setOnClickListener { handleLeftMenuAction(id) } }

        leftWindowsButton.setOnClickListener {
            showButtonClickFeedback(leftWindowsButton)
            toggleWindowMode()
        }
    }

    // ── Browser Agent button glow ────────────────────────────────────

    private var agentGlowAnimator: android.animation.ObjectAnimator? = null

    /**
     * Pulse the agent button with a cyan glow while the agent session is
     * active (Gemini is listening / working). Call [setAgentGlowActive](false)
     * to stop.
     */
    fun setAgentGlowActive(active: Boolean) {
        val btn = leftToggleBar.findViewById<FontIconView>(R.id.btnAnchor) ?: return
        agentGlowAnimator?.cancel()
        agentGlowAnimator = null
        if (active) {
            btn.setTextColor(android.graphics.Color.parseColor("#00E5FF")) // cyan
            val animator = android.animation.ObjectAnimator.ofFloat(btn, "alpha", 1f, 0.35f).apply {
                duration = 800
                repeatMode = android.animation.ObjectAnimator.REVERSE
                repeatCount = android.animation.ObjectAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
            agentGlowAnimator = animator
        } else {
            btn.alpha = 1f
            btn.setTextColor(android.graphics.Color.WHITE)
        }
    }

    /**
     * Capture a JPEG screenshot of the primary WebView for the browser agent.
     * Returns null if capture fails.
     */
    fun captureWebViewScreenshot(): android.graphics.Bitmap? {
        return try {
            val bmp = android.graphics.Bitmap.createBitmap(
                webView.width, webView.height,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bmp)
            webView.draw(canvas)
            bmp
        } catch (e: Exception) {
            Log.e("BrowserAgent", "Screenshot capture failed: ${e.message}", e)
            null
        }
    }

    fun isSettingsVisible(): Boolean {
        return isSettingsVisible
    }

    fun hideSettings() {
        if (isSettingsVisible) {
            isSettingsVisible = false
            settingsMenu?.visibility = View.GONE
            settingsScrim?.visibility = View.GONE
        }
    }

    // Reset all overlay UI state - call on app startup
    fun resetUiState() {
        isSettingsVisible = false
        settingsMenu?.visibility = View.GONE
        settingsScrim?.visibility = View.GONE
        if (::leftBookmarksView.isInitialized) {
            leftBookmarksView.visibility = View.GONE
        }
    }

    fun showSettings() {
        // DebugLog.d("SettingsDebug", "showSettings() called, isSettingsVisible:
        // $isSettingsVisible")

        if (settingsMenu == null) {
            settingsMenu =
                    LayoutInflater.from(context)
                            .inflate(R.layout.settings_layout, null, false)
                            .apply {
                                isClickable = false
                                isFocusable = false
                                elevation = 1001f // Even higher elevation than scrim
                            }

            // Add click handler for close button
            settingsMenu?.findViewById<View>(R.id.btnCloseSettings)?.setOnClickListener {
                // DebugLog.d("SettingsDebug", "Close button clicked")
                isSettingsVisible = false
                settingsMenu?.visibility = View.GONE
                settingsScrim?.visibility = View.GONE
                startRefreshing()
            }

            // Add click handler for help button
            settingsMenu?.findViewById<ImageButton>(R.id.btnHelp)?.setOnClickListener {
                // DebugLog.d("SettingsDebug", "Help button clicked")
                showHelpDialog()
            }

            val layoutParams =
                    FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT
                            )
                            .apply { gravity = Gravity.CENTER }

            leftEyeUIContainer.addView(settingsMenu, layoutParams)
            settingsMenu?.elevation = 1001f

            // DebugLog.d("SettingsDebug", "Menu added with height:
            // ${settingsMenu?.measuredHeight}")
        }

        // Only initialize seekbars when we are about to SHOW settings (not when closing)
        if (!isSettingsVisible) {
            settingsMenu?.let { menu ->
                // Initialize volume seekbar
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val volumeSeekBar = menu.findViewById<SeekBar>(R.id.volumeSeekBar)
                volumeSeekBar?.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volumeSeekBar?.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

                // Initialize brightness seekbar
                val brightnessSeekBar = menu.findViewById<SeekBar>(R.id.brightnessSeekBar)
                brightnessSeekBar?.max = 100
                val currentBrightness =
                        (context as? Activity)?.window?.attributes?.screenBrightness ?: 0.5f
                brightnessSeekBar?.progress = (currentBrightness * 100).toInt()
                val forceDarkButton = menu.findViewById<Button>(R.id.btnToggleForceDark)
                val forceDarkEnabled =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getBoolean("forceDarkWebEnabled", true)
                forceDarkButton?.text =
                        if (forceDarkEnabled) "Force Dark: On" else "Force Dark: Off"

                // Initialize smoothness seekbar from saved preference
                val smoothnessSeekBar = menu.findViewById<SeekBar>(R.id.smoothnessSeekBar)
                val savedSmoothness =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getInt("anchorSmoothness", 40)
                smoothnessSeekBar?.progress = savedSmoothness

                // Initialize screen size seekbar (just update the UI, don't apply scale)
                val screenSizeSeekBar = menu.findViewById<SeekBar>(R.id.screenSizeSeekBar)
                val savedScaleProgress =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getInt("uiScaleProgress", 100)
                screenSizeSeekBar?.progress = savedScaleProgress

                // Calculate scale for position slider visibility check only
                val currentScale = 0.25f + (savedScaleProgress / 100f) * 0.75f

                // Initialize position sliders
                val showPosSliders = !isAnchored && currentScale < 0.99f
                val visibility = if (showPosSliders) View.VISIBLE else View.GONE

                menu.findViewById<View>(R.id.settingsPositionLayout)?.visibility = visibility

                menu.findViewById<SeekBar>(R.id.horizontalPosSeekBar)?.apply {
                    progress =
                            context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                    .getInt("uiTransXProgress", 50)
                }

                menu.findViewById<SeekBar>(R.id.verticalPosSeekBar)?.apply {
                    progress =
                            context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                    .getInt("uiTransYProgress", 50)
                }

                // Initialize font size seekbar (50% = 50, 100% = 100, 200% = 200, slider is 0-150
                // mapping to 50-200%)
                val fontSizeSeekBar = menu.findViewById<SeekBar>(R.id.fontSizeSeekBar)
                val savedFontSize =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getInt("webFontSize", 50) // Default 50 = 100%
                fontSizeSeekBar?.progress = savedFontSize

                // Initialize color buttons with visual background indicators
                // Initialize color wheel with saved color
                menu.findViewById<ColorWheelView>(R.id.colorWheelView)?.apply {
                    val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    val savedTextColor = getEffectiveWebTextColor(prefs)
                    try {
                        setColor(Color.parseColor(savedTextColor))
                    } catch (e: Exception) {
                        setColor(Color.WHITE)
                    }
                }

                // Apply saved font settings
                val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                val savedTextColor = getEffectiveWebTextColor(prefs)
                applyWebFontSettings(savedFontSize, savedTextColor)

                // Initialize cursor sensitivity seekbar
                val sensitivitySeekBar = menu.findViewById<SeekBar>(R.id.cursorSensitivitySeekBar)
                // Default 50 corresponds to 50%
                val savedSensitivity =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getInt("cursorSensitivity", 50)
                sensitivitySeekBar?.progress = savedSensitivity
            }
        }

        // Toggle visibility state
        isSettingsVisible = !isSettingsVisible

        settingsMenu?.visibility = if (isSettingsVisible) View.VISIBLE else View.GONE
        settingsScrim?.visibility = if (isSettingsVisible) View.VISIBLE else View.GONE

        if (isSettingsVisible) {
            settingsScrim?.bringToFront()
            settingsMenu?.bringToFront()

            // Keep the force immediate layout code
            settingsMenu?.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            settingsMenu?.layout(
                    settingsMenu?.left ?: 0,
                    settingsMenu?.top ?: 0,
                    (settingsMenu?.left ?: 0) + (settingsMenu?.measuredWidth ?: 0),
                    (settingsMenu?.top ?: 0) + (settingsMenu?.measuredHeight ?: 0)
            )
        }

        startRefreshing()
        post {
            requestLayout()
            invalidate()
        }
    }

    fun getSettingsMenuLocation(location: IntArray) {
        settingsMenu?.getLocationOnScreen(location)
    }

    fun getSettingsMenuSize(): Pair<Int, Int> {
        return Pair(settingsMenu?.width ?: 0, settingsMenu?.height ?: 0)
    }

    fun dispatchSettingsTouchEvent(x: Float, y: Float) {
        settingsMenu?.let { menu ->
            // Get locations of all interactive elements
            val volumeSeekBar = menu.findViewById<SeekBar>(R.id.volumeSeekBar)
            val brightnessSeekBar = menu.findViewById<SeekBar>(R.id.brightnessSeekBar)
            val forceDarkButton = menu.findViewById<Button>(R.id.btnToggleForceDark)
            val smoothnessSeekBar = menu.findViewById<SeekBar>(R.id.smoothnessSeekBar)
            val screenSizeSeekBar = menu.findViewById<SeekBar>(R.id.screenSizeSeekBar)
            val horizontalPosSeekBar = menu.findViewById<SeekBar>(R.id.horizontalPosSeekBar)
            val verticalPosSeekBar = menu.findViewById<SeekBar>(R.id.verticalPosSeekBar)
            val closeButton = menu.findViewById<View>(R.id.btnCloseSettings)
            val helpButton = menu.findViewById<ImageButton>(R.id.btnHelp)
            val resetButton = menu.findViewById<Button>(R.id.btnResetPosition)
            val resetScreenSizeButton = menu.findViewById<Button>(R.id.btnResetScreenSize)
            val fontSizeSeekBar = menu.findViewById<SeekBar>(R.id.fontSizeSeekBar)
            val colorWheelView = menu.findViewById<ColorWheelView>(R.id.colorWheelView)
            val resetTextColorButton = menu.findViewById<Button>(R.id.btnResetTextColor)
            val groqKeyButton = menu.findViewById<Button>(R.id.btnGroqApiKey)

            fun getRect(view: View?): Rect? {
                if (view == null || view.visibility != View.VISIBLE) return null
                val rect = Rect()
                return if (view.getGlobalVisibleRect(rect)) rect else null
            }

            fun contains(rect: Rect?, slopPx: Int): Boolean {
                if (rect == null) return false
                return x >= rect.left - slopPx &&
                        x <= rect.right + slopPx &&
                        y >= rect.top - slopPx &&
                        y <= rect.bottom + slopPx
            }

            val menuSlop = (2f * uiScale).roundToInt()
            val sliderSlop = (1f * uiScale).roundToInt()
            val buttonSlop = (3f * uiScale).roundToInt()

            if (contains(getRect(menu), menuSlop)) {
                // Check if click is on volume seekbar
                val volumeRect = getRect(volumeSeekBar)
                if (volumeSeekBar != null && contains(volumeRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - volumeRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, volumeSeekBar.width.toFloat()) /
                                    volumeSeekBar.width
                    val newProgress = (percentage * volumeSeekBar.max).toInt()

                    // Update volume
                    volumeSeekBar.progress = newProgress
                    (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).apply {
                        setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                newProgress,
                                AudioManager.FLAG_SHOW_UI
                        )
                    }

                    // **Play system sound for feedback**
                    playSystemSound(context)

                    // Visual feedback
                    volumeSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ volumeSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on brightness seekbar
                val brightnessRect = getRect(brightnessSeekBar)
                if (brightnessSeekBar != null && contains(brightnessRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - brightnessRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, brightnessSeekBar.width.toFloat()) /
                                    brightnessSeekBar.width
                    val newProgress = (percentage * brightnessSeekBar.max).toInt()

                    // Update brightness
                    brightnessSeekBar.progress = newProgress
                    (context as? Activity)?.window?.attributes =
                            (context as Activity).window.attributes.apply {
                                screenBrightness = newProgress / 100f
                            }

                    // Visual feedback
                    brightnessSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ brightnessSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on force dark toggle button
                val forceDarkRect = getRect(forceDarkButton)
                if (forceDarkButton != null && contains(forceDarkRect, buttonSlop)) {
                    val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    val currentlyEnabled = prefs.getBoolean("forceDarkWebEnabled", true)
                    val newEnabled = !currentlyEnabled
                    (context as? MainActivity)?.setForceDarkWebEnabled(newEnabled)
                    forceDarkButton.text = if (newEnabled) "Force Dark: On" else "Force Dark: Off"

                    forceDarkButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ forceDarkButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on smoothness seekbar
                val smoothnessRect = getRect(smoothnessSeekBar)
                if (smoothnessSeekBar != null && contains(smoothnessRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - smoothnessRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, smoothnessSeekBar.width.toFloat()) /
                                    smoothnessSeekBar.width
                    val newProgress = (percentage * smoothnessSeekBar.max).toInt()

                    // Update smoothness
                    smoothnessSeekBar.progress = newProgress

                    // Save preference and notify MainActivity
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("anchorSmoothness", newProgress)
                            .apply()

                    // Call MainActivity to update smoothness
                    (context as? MainActivity)?.updateAnchorSmoothness(newProgress)

                    // Visual feedback
                    smoothnessSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ smoothnessSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on cursor sensitivity seekbar
                val sensitivitySeekBar = menu.findViewById<SeekBar>(R.id.cursorSensitivitySeekBar)
                val sensitivityRect = getRect(sensitivitySeekBar)

                if (sensitivitySeekBar != null && contains(sensitivityRect, sliderSlop)) {
                    // Calculate relative position on seekbar
                    val relativeX = (x - sensitivityRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, sensitivitySeekBar.width.toFloat()) /
                                    sensitivitySeekBar.width
                    val newProgress = (percentage * sensitivitySeekBar.max).toInt()

                    // Update sensitivity and save preference
                    sensitivitySeekBar.progress = newProgress
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("cursorSensitivity", newProgress)
                            .apply()

                    // Notify MainActivity
                    (context as? MainActivity)?.updateCursorSensitivity(newProgress)

                    // Visual feedback
                    sensitivitySeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ sensitivitySeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on reset sensitivity button
                val resetSensitivityButton =
                        menu.findViewById<Button>(R.id.btnResetCursorSensitivity)
                val resetSensitivityRect = getRect(resetSensitivityButton)

                if (resetSensitivityButton != null && contains(resetSensitivityRect, buttonSlop)) {
                    // Reset to 50%
                    // val sensitivitySeekBar =
                    //        menu.findViewById<SeekBar>(R.id.cursorSensitivitySeekBar)
                    sensitivitySeekBar?.progress = 50

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("cursorSensitivity", 50)
                            .apply()

                    (context as? MainActivity)?.updateCursorSensitivity(50)

                    // Visual feedback
                    resetSensitivityButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetSensitivityButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on screen size seekbar
                val screenSizeRect = getRect(screenSizeSeekBar)
                if (screenSizeSeekBar != null && contains(screenSizeRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - screenSizeRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, screenSizeSeekBar.width.toFloat()) /
                                    screenSizeSeekBar.width
                    var newProgress = (percentage * screenSizeSeekBar.max).toInt()

                    // Snap to 100% when close (>= 95%)
                    if (newProgress >= 95) {
                        newProgress = 100
                    }

                    // Update screen size
                    screenSizeSeekBar.progress = newProgress

                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiScaleProgress", newProgress)
                            .apply()

                    // Apply scale: 35% (0.35) to 100% (1.0)
                    val scale = 0.35f + (newProgress / 100f) * 0.65f
                    updateUiScale(scale)

                    // Update visibility of position sliders
                    val showPosSliders = !isAnchored && scale < 0.99f
                    val posLayout = menu.findViewById<View>(R.id.settingsPositionLayout)
                    val newVisibility = if (showPosSliders) View.VISIBLE else View.GONE

                    if (posLayout?.visibility != newVisibility) {
                        posLayout?.visibility = newVisibility

                        // Force complete remeasure with UNSPECIFIED to allow width changes
                        menu.measure(
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                        )
                        menu.layout(
                                menu.left,
                                menu.top,
                                menu.left + menu.measuredWidth,
                                menu.top + menu.measuredHeight
                        )

                        // Invalidate to redraw
                        menu.invalidate()

                        // Also request layout on parent to ensure proper positioning
                        (menu.parent as? View)?.requestLayout()
                    }

                    // Recalculate translation based on new scale
                    updateUiTranslation()

                    // Visual feedback
                    screenSizeSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ screenSizeSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on Groq API Key button
                val groqKeyRect = getRect(groqKeyButton)
                if (groqKeyButton != null && contains(groqKeyRect, buttonSlop)) {

                    // Visual feedback
                    groqKeyButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed(
                                    {
                                        groqKeyButton.isPressed = false
                                        // Show dialog
                                        (context as? MainActivity)?.showGroqKeyDialog()
                                    },
                                    100
                            )
                    return
                }

                // Check if click is on reset screen size button
                val resetScreenSizeRect = getRect(resetScreenSizeButton)
                if (resetScreenSizeButton != null && contains(resetScreenSizeRect, buttonSlop)) {

                    // Reset screen size to 100%
                    screenSizeSeekBar?.progress = 100

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiScaleProgress", 100)
                            .putInt("uiTransXProgress", 50)
                            .putInt("uiTransYProgress", 50)
                            .apply()

                    // Apply full scale
                    updateUiScale(1.0f)

                    // Hide position sliders and remeasure
                    val posLayout = menu.findViewById<View>(R.id.settingsPositionLayout)
                    if (posLayout?.visibility != View.GONE) {
                        posLayout?.visibility = View.GONE

                        // Force complete remeasure with UNSPECIFIED to allow width changes
                        menu.measure(
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                        )
                        menu.layout(
                                menu.left,
                                menu.top,
                                menu.left + menu.measuredWidth,
                                menu.top + menu.measuredHeight
                        )

                        // Invalidate to redraw
                        menu.invalidate()

                        // Also request layout on parent to ensure proper positioning
                        (menu.parent as? View)?.requestLayout()
                    }

                    // Reset position to center
                    horizontalPosSeekBar?.progress = 50
                    verticalPosSeekBar?.progress = 50
                    updateUiTranslation()

                    // Visual feedback
                    resetScreenSizeButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetScreenSizeButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on horizontal pos seekbar
                val horizontalPosRect = getRect(horizontalPosSeekBar)
                if (horizontalPosSeekBar != null &&
                                horizontalPosSeekBar.visibility == View.VISIBLE &&
                                contains(horizontalPosRect, sliderSlop)
                ) {

                    val relativeX = (x - horizontalPosRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, horizontalPosSeekBar.width.toFloat()) /
                                    horizontalPosSeekBar.width
                    val newProgress = (percentage * horizontalPosSeekBar.max).toInt()

                    horizontalPosSeekBar.progress = newProgress

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiTransXProgress", newProgress)
                            .apply()

                    updateUiTranslation()

                    horizontalPosSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ horizontalPosSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on vertical pos seekbar
                val verticalPosRect = getRect(verticalPosSeekBar)
                if (verticalPosSeekBar != null &&
                                verticalPosSeekBar.visibility == View.VISIBLE &&
                                contains(verticalPosRect, sliderSlop)
                ) {

                    val relativeX = (x - verticalPosRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, verticalPosSeekBar.width.toFloat()) /
                                    verticalPosSeekBar.width
                    val newProgress = (percentage * verticalPosSeekBar.max).toInt()

                    verticalPosSeekBar.progress = newProgress

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiTransYProgress", newProgress)
                            .apply()

                    updateUiTranslation()

                    verticalPosSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ verticalPosSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on reset button
                val resetRect = getRect(resetButton)
                if (resetButton != null &&
                                resetButton.visibility == View.VISIBLE &&
                                contains(resetRect, buttonSlop)
                ) {

                    // Reset position progress to 50 (center)
                    horizontalPosSeekBar?.progress = 50
                    verticalPosSeekBar?.progress = 50

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiTransXProgress", 50)
                            .putInt("uiTransYProgress", 50)
                            .apply()

                    updateUiTranslation()

                    // Visual feedback
                    resetButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on help button
                val helpRect = getRect(helpButton)
                if (helpButton != null && contains(helpRect, buttonSlop)) {

                    // Visual feedback
                    helpButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed(
                                    {
                                        helpButton.isPressed = false
                                        // Show help dialog
                                        showHelpDialog()
                                    },
                                    100
                            )
                    return
                }

                // Check if click is on Reset Zoom button
                val resetZoomButton = menu.findViewById<Button>(R.id.btnResetFontSize)
                val resetZoomRect = getRect(resetZoomButton)

                if (resetZoomButton != null && contains(resetZoomRect, buttonSlop)) {

                    // Reset font size to 100% (progress 50)
                    fontSizeSeekBar?.progress = 50

                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("webFontSize", 50)
                            .apply()

                    // Apply to WebView
                    val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    val savedTextColor = getEffectiveWebTextColor(prefs)
                    applyWebFontSettings(50, savedTextColor)

                    // Visual feedback
                    resetZoomButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetZoomButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on Reset Webpage Zoom button
                val resetWebpageZoomButton = menu.findViewById<Button>(R.id.btnResetWebpageZoom)
                val resetWebpageZoomRect = getRect(resetWebpageZoomButton)

                if (resetWebpageZoomButton != null && contains(resetWebpageZoomRect, buttonSlop)) {

                    // Reset webpage zoom to 1.0
                    currentWebZoom = 1.0f

                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putFloat("webZoomLevel", currentWebZoom)
                            .apply()

                    webView.evaluateJavascript(
                            """
                        (function() {
                            document.body.style.zoom = "$currentWebZoom";
                        })();
                    """,
                            null
                    )

                    postDelayed(
                            {
                                updateScrollBarsVisibility()
                                lastScrollBarCheckTime = System.currentTimeMillis()
                            },
                            100
                    )

                    // Visual feedback
                    resetWebpageZoomButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetWebpageZoomButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on font size seekbar
                val fontSizeRect = getRect(fontSizeSeekBar)
                if (fontSizeSeekBar != null && contains(fontSizeRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - fontSizeRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, fontSizeSeekBar.width.toFloat()) /
                                    fontSizeSeekBar.width
                    val newProgress = (percentage * fontSizeSeekBar.max).toInt()

                    // Update font size
                    fontSizeSeekBar.progress = newProgress

                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("webFontSize", newProgress)
                            .apply()

                    // Apply to WebView
                    val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    val savedTextColor = getEffectiveWebTextColor(prefs)
                    applyWebFontSettings(newProgress, savedTextColor)

                    // Visual feedback
                    fontSizeSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ fontSizeSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on color wheel
                val colorWheelRect = getRect(colorWheelView)
                if (colorWheelView != null && contains(colorWheelRect, sliderSlop)) {

                    // Calculate relative position
                    val relativeX = (x - colorWheelRect!!.left) / uiScale
                    val relativeY = (y - colorWheelRect.top) / uiScale

                    val selectedColor =
                            colorWheelView.calculateColorFromCoordinates(relativeX, relativeY)

                    // Update visual indicator
                    colorWheelView.setColor(selectedColor)

                    // Apply color
                    val hexColor = String.format("#%06X", (0xFFFFFF and selectedColor))
                    applyTextColor(hexColor)

                    // Visual feedback
                    colorWheelView.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ colorWheelView.isPressed = false }, 100)
                    return
                }

                // Check if click is on reset text color button
                val resetTextColorRect = getRect(resetTextColorButton)
                if (resetTextColorButton != null && contains(resetTextColorRect, buttonSlop)) {

                    // Reset color to white
                    colorWheelView?.setColor(Color.WHITE)
                    // Reset color to white visually
                    colorWheelView?.setColor(Color.WHITE)
                    applyTextColor(null)

                    // Visual feedback
                    resetTextColorButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetTextColorButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on close button
                val closeRect = getRect(closeButton)
                if (closeButton != null && contains(closeRect, buttonSlop)) {

                    // Visual feedback
                    closeButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed(
                                    {
                                        closeButton.isPressed = false
                                        // Close settings
                                        isSettingsVisible = false
                                        settingsMenu?.visibility = View.GONE
                                        settingsScrim?.visibility = View.GONE
                                        startRefreshing()
                                    },
                                    100
                            )
                    return
                }
            } else {
                return
            }
        }
    }

    fun playSystemSound(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK) // Play a standard click sound
    }

    /**
     * Apply font size and text color settings to the WebView via JavaScript injection.
     * @param fontSizeProgress Slider progress (0-150) which maps to 50%-200% font size
     * @param textColor Optional hex color string (e.g., "#FFFFFF")
     */
    private fun applyWebFontSettings(fontSizeProgress: Int, textColor: String?) {
        // Map progress 0-150 to font size 50%-200%
        val fontSizePercent = 50 + fontSizeProgress

        val colorCss =
                if (textColor != null) {
                    "body, body *, p, span, div, h1, h2, h3, h4, h5, h6, a, li, td, th { color: $textColor !important; }"
                } else {
                    ""
                }

        val js =
                """
            (function() {
                var styleId = 'taplink-font-settings';
                var existingStyle = document.getElementById(styleId);
                if (existingStyle) {
                    existingStyle.remove();
                }
                var style = document.createElement('style');
                style.id = styleId;
                style.textContent = 'html { font-size: ${fontSizePercent}% !important; } $colorCss';
                document.head.appendChild(style);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)

        // Update system info bar color
        if (textColor != null) {
            try {
                leftSystemInfoView.setTextColor(Color.parseColor(textColor))
            } catch (e: Exception) {
                Log.e("DualWebViewGroup", "Error updating system info color", e)
            }
        }
    }

    fun getAllWebViews(): List<WebView> {
        return windows.map { it.webView }
    }

    private fun getEffectiveWebTextColor(prefs: android.content.SharedPreferences): String? {
        val overrideEnabled = prefs.getBoolean("webTextColorOverrideEnabled", false)
        return if (overrideEnabled) prefs.getString("webTextColor", null) else null
    }

    /**
     * Apply text color to webpage AND custom UI, then save preference.
     * @param colorHex Hex color string (e.g., "#FFFFFF")
     */
    private fun applyTextColor(colorHex: String?) {
        // Save preference
        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                .edit()
                .putString("webTextColor", colorHex)
                .putBoolean("webTextColorOverrideEnabled", colorHex != null)
                .apply()

        // Update Custom UI (Settings & Keyboard)
        updateCustomUiColor(colorHex)

        // Get current font size and apply both settings to WebView
        val fontSizeProgress =
                context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .getInt("webFontSize", 50)
        applyWebFontSettings(fontSizeProgress, colorHex)
    }

    /** Update the color of Custom UI elements (Settings Menu and Keyboard) */
    private fun updateCustomUiColor(colorHex: String?) {
        val color =
                if (colorHex != null) {
                    try {
                        Color.parseColor(colorHex)
                    } catch (e: Exception) {
                        Color.WHITE
                    }
                } else {
                    Color.WHITE
                }

        // 1. Update Keyboard
        customKeyboard?.setCustomTextColor(color)

        // 2. Update Settings Menu (Recursively find TextViews/Buttons)
        settingsMenu?.let { menu -> updateViewColorsRecursively(menu, color) }

        // 3. Update Navigation Bar
        if (::leftNavigationBar.isInitialized) {
            updateViewColorsRecursively(leftNavigationBar, color)
        }

        // 4. Update Toggle Bar
        if (::leftToggleBar.isInitialized) {
            updateViewColorsRecursively(leftToggleBar, color)
        }
    }

    private fun updateViewColorsRecursively(view: View, color: Int) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                updateViewColorsRecursively(view.getChildAt(i), color)
            }
        } else if (view is TextView) {
            // Apply to TextViews and Buttons (Button is subclass of TextView)
            // But verify it's not one of our special icon views if they shouldn't change
            // (FontIconView IS a TextView, so it will get colored too, which is likely desired)
            view.setTextColor(color)
        }
    }

    /** Re-apply saved font settings to the WebView. Called when a new page loads. */
    fun reapplyWebFontSettings() {
        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        val fontSizeProgress = prefs.getInt("webFontSize", 50)
        val textColor = getEffectiveWebTextColor(prefs)

        applyWebFontSettings(fontSizeProgress, textColor)

        // Also ensure UI is synced (though this is mostly for initial load)
        updateCustomUiColor(textColor)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sharedConfigPrefs.registerOnSharedPreferenceChangeListener(sharedConfigListener)
        sharedConfigListenerRegistered = true
        updateSystemInfoBarVisibility()
        startRefreshing()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (sharedConfigListenerRegistered) {
            sharedConfigPrefs.unregisterOnSharedPreferenceChangeListener(sharedConfigListener)
            sharedConfigListenerRegistered = false
        }
        stopRefreshing()
        releaseAudioCapture()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            startRefreshing()
        } else {
            stopRefreshing()
        }
    }

    fun isInScrollMode(): Boolean {
        return isInScrollMode
    }

    fun setScrollMode(enabled: Boolean) {
        DebugLog.d(
                "NavBarDebug",
                "setScrollMode: enabled=$enabled, current=$isInScrollMode, navHidden=$isNavBarsHidden"
        )

        if (isInScrollMode == enabled) return
        isInScrollMode = enabled

        if (enabled) {

            leftToggleBar.isClickable = false
            leftNavigationBar.isClickable = false
            updateSystemInfoBarVisibility()

            // Then animate menus away
            leftToggleBar
                    .animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { leftToggleBar.visibility = View.GONE }
                    .start()

            leftNavigationBar
                    .animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { leftNavigationBar.visibility = View.GONE }
                    .start()

            // Show force-show button
            btnShowNavBars.visibility = View.VISIBLE
            btnShowNavBars.bringToFront()
            btnShowNavBars.alpha = 0f
            btnShowNavBars.animate().alpha(1.0f).setDuration(200).start()
            btnShowNavBars.requestLayout()
        } else {
            // Only restore UI if the other mode (isNavBarsHidden) is NOT active
            if (!isNavBarsHidden) {
                // Re-enable touch interception and show system info bar
                leftToggleBar.isClickable = true
                leftNavigationBar.isClickable = true
                updateSystemInfoBarVisibility()

                // Then show menus with animation
                leftToggleBar.visibility = View.VISIBLE
                leftToggleBar.alpha = 0f
                leftToggleBar.animate().alpha(1f).setDuration(200).start()

                leftNavigationBar.visibility = View.VISIBLE
                leftNavigationBar.alpha = 0f
                leftNavigationBar.animate().alpha(1f).setDuration(200).start()

                // Hide force-show button
                btnShowNavBars
                        .animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction { btnShowNavBars.visibility = View.GONE }
                        .start()
            }
        }

        // Update scrollbars and layout
        updateScrollBarsVisibility()

        // Force layout update
        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    /**
     * Hides or shows the navigation bars without affecting scroll mode. When hidden, cursor remains
     * visible and movable (unlike scroll mode).
     */
    fun setNavBarsHidden(hidden: Boolean) {
        if (isNavBarsHidden == hidden) return
        isNavBarsHidden = hidden

        if (hidden) {
            // Immediately disable touch interception before animating
            leftToggleBar.isClickable = false
            leftNavigationBar.isClickable = false
            updateSystemInfoBarVisibility()

            // Then animate menus away
            leftToggleBar
                    .animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { leftToggleBar.visibility = View.GONE }
                    .start()

            leftNavigationBar
                    .animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { leftNavigationBar.visibility = View.GONE }
                    .start()

            // Show force-show button
            btnShowNavBars.visibility = View.VISIBLE
            btnShowNavBars.bringToFront()
            btnShowNavBars.alpha = 0f
            btnShowNavBars.animate().alpha(1.0f).setDuration(200).start()
            btnShowNavBars.requestLayout()
        } else {
            // Only restore UI if the other mode (isInScrollMode) is NOT active
            if (!isInScrollMode) {
                // Re-enable touch interception and show system info bar
                leftToggleBar.isClickable = true
                leftNavigationBar.isClickable = true
                updateSystemInfoBarVisibility()

                // Then show menus with animation
                leftToggleBar.visibility = View.VISIBLE
                leftToggleBar.alpha = 0f
                leftToggleBar.animate().alpha(1f).setDuration(200).start()

                leftNavigationBar.visibility = View.VISIBLE
                leftNavigationBar.alpha = 0f
                leftNavigationBar.animate().alpha(1f).setDuration(200).start()

                // Hide force-show button
                btnShowNavBars
                        .animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction { btnShowNavBars.visibility = View.GONE }
                        .start()
            }
        }

        // Update scrollbars and layout
        updateScrollBarsVisibility()

        // Force layout update
        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    fun isNavBarsHidden(): Boolean {
        return isNavBarsHidden
    }

    // Custom Dialog Logic
    fun showAlertDialog(message: String, onConfirm: () -> Unit) {
        showDialog("Alert", message, false, null, { _ -> onConfirm() }, null)
    }

    fun showConfirmDialog(message: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
        showDialog("Confirm", message, false, null, { _ -> onConfirm() }, onCancel)
    }

    fun showPromptDialog(
            message: String,
            defaultValue: String?,
            onConfirm: (String) -> Unit,
            onCancel: () -> Unit
    ) {
        showDialog(
                "Prompt",
                message,
                true,
                defaultValue,
                { text -> onConfirm(text ?: "") },
                onCancel
        )
    }

    fun showHelpDialog(page: Int = 1) {
        val (title, message, hasNext, hasPrev) =
                when (page) {
                    1 ->
                            Quadruple(
                                    "Features: Touch & Menu",
                                    """
                TOUCH GESTURES:
                • Single Tap: Click links, buttons, and focus fields.
                • Double Tap: Go back to the previous page.
                
                TRIPLE TAP (Anchored Mode):
                • Re-centers the screen.
                """.trimIndent(),
                                    true,
                                    false
                            )
                    2 ->
                            Quadruple(
                                    "Features: Screen Modes",
                                    """
                ANCHORED MODE (Anchor Icon):
                • Screen stays fixed in space relative to the world.
                • Smoothness: Controls how rigidly the screen follows tracking.
                
                NON-ANCHORED MODE (Crossed Anchor):
                • Screen is "locked" to your head movement.
                • Screen Position: Shift the display H/V when UI Scale < 100%.
                """.trimIndent(),
                                    true,
                                    true
                            )
                    3 ->
                            Quadruple(
                                    "Features: Display & Tools",
                                    """
                SCROLL MODE (Full Screen Icon):
                • Hides UI for an immersive browsing experience.
                • Restore UI: Tap the transparent "Show" button.
                
                UTILITIES:
                • Volume & Brightness Sliders.
                • Force Dark: Toggle dark rendering for supported webpages.
                • UI Scale: Adjust the global interface size.
                • Web Zoom (+/-): Content zoom level.
                • QR Scanner: Open Dashboard (glasses icon) and tap QR Scanner.

                VOICE / STT:
                • Uses device speech-to-text when supported.
                • Start STT, speak clearly, and text is inserted into the active field.
                • Use scrcpy keyboard to paste your API key into the prompt field.
                """.trimIndent(),
                                    true,
                                    true
                            )
                    4 ->
                            Quadruple(
                                    "Features: Blank Screen Mode",
                                    """
                BLANK SCREEN MODE (Eye Toggle):
                • Blacks out display while media continues playing but allows media controls.
                • Perfect for listening to audio/podcasts.
                • Note: Disables anchored mode while active.
                
                MEDIA CONTROLS (shown when media is playing):
                • Play/Pause: Toggle media playback.
                • Skip Back/Forward: Jump 10 seconds.
                • Unmask (Eye Icon): Exit blank screen mode.
                """.trimIndent(),
                                    true,
                                    true
                            )
                    5 ->
                            Quadruple(
                                    "TapLink AI",
                                    """
                TAPLINK AI (Chat Icon):
                • Open/close with the Chat button on the bottom bar.
                • Requires a Groq API Key (Settings -> Enter Groq API Key).
                • Ask questions or use Summarize to recap the current webpage.
                • Summarize works only when a normal webpage is open.
                • Speak replies: Toggle in chat to read assistant responses aloud.
                """.trimIndent(),
                                    false,
                                    true
                            )
                    else -> return
                }

        val footerButtons = mutableListOf<View>()

        if (hasPrev) {
            footerButtons.add(
                    Button(context).apply {
                        text = "Back"
                        textSize = 14f
                        setTextColor(Color.parseColor("#AAAAAA"))
                        setBackgroundColor(Color.TRANSPARENT)
                        setOnClickListener { showHelpDialog(page - 1) }
                    }
            )
        }

        if (hasNext) {
            footerButtons.add(
                    Button(context).apply {
                        text = "Next"
                        textSize = 14f
                        setTextColor(Color.parseColor("#4488FF"))
                        setBackgroundColor(Color.TRANSPARENT)
                        setOnClickListener { showHelpDialog(page + 1) }
                    }
            )
        }

        showDialog(
                title = title,
                message = message,
                hasInput = false,
                confirmLabel = "Close",
                dismissOnAnyClick = true,
                additionalButtons = footerButtons
        )
    }

    private data class Quadruple<A, B, C, D>(
            val first: A,
            val second: B,
            val third: C,
            val fourth: D
    )

    private fun showDialog(
            title: String,
            message: String,
            hasInput: Boolean,
            defaultValue: String? = null,
            onConfirm: ((String?) -> Unit)? = null,
            onCancel: (() -> Unit)? = null,
            confirmLabel: String? = "OK",
            dismissOnAnyClick: Boolean = false,
            additionalButtons: List<View> = emptyList()
    ) {
        dialogContainer.removeAllViews()

        // Hide keyboard container initially to avoid overlapping, though we might show it again if
        // input is focused
        keyboardContainer.visibility = View.GONE

        val padding = 16.dp()
        val dialogView =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                            FrameLayout.LayoutParams(
                                            500, // Fixed width for consistent look
                                            FrameLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { gravity = Gravity.CENTER }
                    setPadding(padding, padding, padding, padding)
                    background =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#202020"))
                                setStroke(2, Color.parseColor("#404040"))
                                cornerRadius = 16f
                            }
                    elevation = 100f
                    isClickable = true
                    isFocusable = true
                }

        // Title
        val titleView =
                TextView(context).apply {
                    text = title
                    textSize = 20f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { bottomMargin = 16.dp() }
                }
        dialogView.addView(titleView)

        // Message
        val messageView =
                TextView(context).apply {
                    text = message
                    textSize = 16f
                    setTextColor(Color.parseColor("#DDDDDD"))
                    maxLines = 15
                    isVerticalScrollBarEnabled = true
                    movementMethod = ScrollingMovementMethod.getInstance()
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { bottomMargin = 24.dp() }
                }
        dialogView.addView(messageView)

        var inputField: EditText? = null
        if (hasInput) {
            inputField =
                    EditText(context).apply {
                        setText(defaultValue ?: "")
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        setPadding(16, 16, 16, 16)
                        background =
                                GradientDrawable().apply {
                                    setColor(Color.parseColor("#303030"))
                                    cornerRadius = 8f
                                }
                        layoutParams =
                                LinearLayout.LayoutParams(
                                                LinearLayout.LayoutParams.MATCH_PARENT,
                                                LinearLayout.LayoutParams.WRAP_CONTENT
                                        )
                                        .apply { bottomMargin = 24.dp() }

                        // Important: Show custom keyboard on focus
                        setOnFocusChangeListener { _, hasFocus ->
                            if (hasFocus) {
                                keyboardListener?.onShowKeyboard()
                            }
                        }

                        // Allow our custom keyboard to input text here
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setSingleLine()
                    }
            dialogView.addView(inputField)
        }

        // Buttons
        val buttonContainer =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    layoutParams =
                            LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                }

        if (onCancel != null) {
            val cancelButton =
                    Button(context).apply {
                        text = "Cancel"
                        textSize = 16f
                        setTextColor(Color.parseColor("#AAAAAA"))
                        background =
                                ContextCompat.getDrawable(context, R.drawable.nav_button_background)
                        setPadding(24.dp(), 12.dp(), 24.dp(), 12.dp())
                        minWidth = 64.dp()
                        minHeight = 48.dp()
                        setOnClickListener {
                            onCancel()
                            hideDialog()
                        }
                    }
            buttonContainer.addView(cancelButton)
        }

        additionalButtons.forEach { button ->
            if (button is Button) {
                button.background =
                        ContextCompat.getDrawable(context, R.drawable.nav_button_background)
            }
            buttonContainer.addView(button)
        }

        if (confirmLabel != null) {
            val confirmButton =
                    Button(context).apply {
                        text = confirmLabel
                        textSize = 16f
                        setTextColor(Color.parseColor("#4488FF"))
                        background =
                                ContextCompat.getDrawable(context, R.drawable.nav_button_background)
                        setPadding(24.dp(), 12.dp(), 24.dp(), 12.dp())
                        minWidth = 64.dp()
                        minHeight = 48.dp()
                        setOnClickListener {
                            onConfirm?.invoke(inputField?.text?.toString())
                            hideDialog()
                        }
                    }
            buttonContainer.addView(confirmButton)
        }

        dialogView.addView(buttonContainer)
        dialogContainer.addView(dialogView)
        dialogContainer.visibility = View.VISIBLE
        dialogContainer.bringToFront()
        if (dismissOnAnyClick) {
            dialogContainer.setOnClickListener { hideDialog() }
            // DON'T set listener on dialogView, so clicks inside don't dismiss
        }

        // Ensure rendering updates
        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    fun hideDialog() {
        dialogContainer.visibility = View.GONE
        dialogContainer.removeAllViews()
        // Determine whether to show keyboard container again
        if (customKeyboard?.visibility == View.VISIBLE) {
            keyboardContainer.visibility = View.VISIBLE
        }

        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    private var toastHandler: Handler? = Handler(Looper.getMainLooper())
    private var toastRunnable: Runnable? = null

    /**
     * Shows a toast message that renders in both eyes.
     * @param message The message to display
     * @param durationMs How long to show the toast (default 2000ms)
     */
    fun showToast(message: String, durationMs: Long = 2000L) {
        // DebugLog.d("Toast", "showToast called with message: $message")
        // Ensure we're on the UI thread
        post {
            // DebugLog.d("Toast", "Inside post block, creating toast view")
            // Cancel any existing toast
            toastRunnable?.let { toastHandler?.removeCallbacks(it) }
            dialogContainer.removeAllViews()

            val padding = 16.dp()
            val toastView =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                                FrameLayout.LayoutParams.WRAP_CONTENT
                                        )
                                        .apply {
                                            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                                            bottomMargin = 64.dp()
                                        }
                        setPadding(padding * 2, padding, padding * 2, padding)
                        background =
                                GradientDrawable().apply {
                                    setColor(Color.parseColor("#E0303030")) // Semi-transparent dark
                                    cornerRadius = 24f
                                }
                        elevation = 100f
                    }

            val messageView =
                    TextView(context).apply {
                        text = message
                        textSize = 16f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                    }
            toastView.addView(messageView)

            // Use a transparent scrim for toast (unlike dialogs which block interaction)
            dialogContainer.setBackgroundColor(Color.TRANSPARENT)
            dialogContainer.addView(toastView)
            dialogContainer.visibility = View.VISIBLE
            dialogContainer.bringToFront()
            dialogContainer.isClickable = false // Allow clicks to pass through

            // DebugLog.d("Toast", "Toast view added, dialogContainer visible:
            // ${dialogContainer.visibility == View.VISIBLE}, child count:
            // ${dialogContainer.childCount}")

            // Ensure rendering updates
            requestLayout()
            invalidate()
            startRefreshing()

            // Auto-dismiss after duration
            toastRunnable = Runnable { hideToast() }
            toastHandler?.postDelayed(toastRunnable!!, durationMs)
        }
    }

    private fun hideToast() {
        dialogContainer.visibility = View.GONE
        dialogContainer.removeAllViews()
        // Restore dialog container background for future dialogs
        dialogContainer.setBackgroundColor(Color.parseColor("#CC000000"))
        dialogContainer.isClickable = true

        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    // Helper method to get the current dialog input if any
    fun getDialogInput(): EditText? {
        if (dialogContainer.visibility != View.VISIBLE) return null
        val dialogView = dialogContainer.getChildAt(0) as? ViewGroup ?: return null
        // Scan for EditText
        for (i in 0 until dialogView.childCount) {
            val child = dialogView.getChildAt(i)
            if (child is EditText) return child
        }
        return null
    }

    fun isDialogAction(x: Float, y: Float): Boolean {
        if (dialogContainer.visibility != View.VISIBLE || !dialogContainer.isClickable) return false
        val loc = IntArray(2)
        dialogContainer.getLocationOnScreen(loc)
        return x >= loc[0] &&
                x <= loc[0] + (dialogContainer.width * uiScale) &&
                y >= loc[1] &&
                y <= loc[1] + (dialogContainer.height * uiScale)
    }

    private fun setupMaskOverlayUI() {

        maskNowPlayingText =
                TextView(context).apply {
                    setTextColor(Color.argb(128, 255, 255, 255))
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    visibility = View.GONE
                    alpha = 0.5f
                }
        val nowPlayingParams =
                FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT
                        )
                        .apply {
                            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                            leftMargin = 56
                            rightMargin = 56
                            bottomMargin = 54
                        }
        maskOverlay.addView(maskNowPlayingText, nowPlayingParams)

        // Unmask button (Bottom Right)
        btnMaskUnmask =
                ImageButton(context).apply {
                    setImageResource(R.drawable.ic_visibility_on)
                    setBackgroundColor(Color.TRANSPARENT)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(8, 8, 8, 8)
                    alpha = 0.5f
                    setOnClickListener { unmaskScreen() }
                }
        val unmaskParams =
                FrameLayout.LayoutParams(40, 40).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    rightMargin = 8
                    bottomMargin = 8
                }
        maskOverlay.addView(btnMaskUnmask, unmaskParams)

        // Media Controls Container (Bottom Center)
        maskMediaControlsContainer =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.TRANSPARENT)
                    alpha = 0.5f
                    visibility = View.GONE // Hidden by default until media detected
                }
        val controlsParams =
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, 40).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = 8
                }
        maskOverlay.addView(maskMediaControlsContainer, controlsParams)

        // Controls - Order: Prev Track, 10s Back, Play, Pause, 10s Forward, Next Track
        btnMaskPrevTrack =
                createMediaButton(R.string.fa_backward_step) {
                    // Try to click previous track button (works on YouTube, Spotify, etc.)
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            """
                (function() {
                    // Try common previous track selectors
                    var prevBtn = document.querySelector('.ytp-prev-button') ||
                                  document.querySelector('[aria-label*="previous" i]') ||
                                  document.querySelector('[title*="previous" i]') ||
                                  document.querySelector('button[data-testid="control-button-skip-back"]');
                    if (prevBtn) { prevBtn.click(); return; }
                    // Fallback: Skip to beginning
                    var media = document.querySelector('video, audio');
                    if (media) media.currentTime = 0;
                })();
            """.trimIndent(),
                            "(function(){ if(window.prevStation){ window.prevStation(); } })();"
                    )
                    scheduleTrackChangeRefresh()
                }
        btnMaskPrev =
                createMediaButton(R.string.fa_backward) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            "document.querySelector('video, audio').currentTime -= 10;",
                            "(function(){ if(window.prevStation){ window.prevStation(); } })();"
                    )
                }
        btnMaskPlay =
                createMediaButton(R.string.fa_play) {
                    playMedia()
                    // Immediately update button visibility for responsive UI
                    lastMediaInteractionTime = SystemClock.uptimeMillis()
                    btnMaskPlay.visibility = View.GONE
                    btnMaskPause.visibility = View.VISIBLE
                    maskMediaControlsContainer.requestLayout()
                }
        btnMaskPause =
                createMediaButton(R.string.fa_pause) {
                    pauseMedia()
                    // Immediately update button visibility for responsive UI
                    lastMediaInteractionTime = SystemClock.uptimeMillis()
                    btnMaskPause.visibility = View.GONE
                    btnMaskPlay.visibility = View.VISIBLE
                    maskMediaControlsContainer.requestLayout()
                }
        btnMaskNext =
                createMediaButton(R.string.fa_forward) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            "document.querySelector('video, audio').currentTime += 10;",
                            "(function(){ if(window.nextStation){ window.nextStation(); } })();"
                    )
                }
        btnMaskNextTrack =
                createMediaButton(R.string.fa_forward_step) {
                    // Advance to the next track. Programmatic .click() on
                    // .ytp-next-button often leaves YouTube's SPA chrome
                    // (title, description) stale, so we prefer navigating
                    // directly to the Up Next URL, which forces a clean
                    // yt-navigate cycle.
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            """
                (function() {
                    var beforeUrl = window.location.href;
                    var forceReloadIfUrlAdvanced = function(delayMs) {
                        // YouTube's SPA sometimes swaps the video stream via
                        // pushState but leaves the title/description/metadata
                        // chrome stale. If the URL has advanced (new ?v= or
                        // list index) but YouTube hasn't fully re-rendered,
                        // force a real navigation to the new URL so every
                        // piece of metadata refreshes in lockstep.
                        setTimeout(function() {
                            try {
                                var now = window.location.href;
                                if (now !== beforeUrl) {
                                    window.location.replace(now);
                                }
                            } catch(e) {}
                        }, delayMs);
                    };

                    // ── Strategy 1: Direct SPA navigation to Up Next video ──
                    // Covers autoplay rail, playlist panels, and YouTube Mix
                    // side panels. Navigating via window.location guarantees
                    // the page chrome re-renders.
                    var upLink =
                        // Autoplay "Up Next" block (single video, autoplay on)
                        document.querySelector('ytd-compact-autoplay-renderer a#thumbnail[href*="/watch"]') ||
                        // Playlist/Mix panel: track immediately after the
                        // currently-selected one (sibling-next selector).
                        document.querySelector('ytd-playlist-panel-video-renderer[selected] + ytd-playlist-panel-video-renderer a[href*="/watch"]') ||
                        document.querySelector('ytd-playlist-panel-video-renderer[selected] ~ ytd-playlist-panel-video-renderer a[href*="/watch"]') ||
                        // Related rail (single-video without autoplay on)
                        document.querySelector('#related ytd-compact-video-renderer a#thumbnail[href*="/watch"]') ||
                        document.querySelector('ytd-compact-video-renderer a#thumbnail[href*="/watch"]') ||
                        // Mobile layout
                        document.querySelector('ytm-compact-autoplay-renderer a[href*="/watch"]');
                    if (upLink && upLink.href) {
                        window.location.href = upLink.href;
                        return;
                    }

                    // ── Strategy 2: Dispatch a real MouseEvent on .ytp-next-button ──
                    var nextBtn =
                        document.querySelector('.ytp-next-button:not([aria-disabled="true"])') ||
                        document.querySelector('.ytp-next-button') ||
                        document.querySelector('button[aria-label^="Next" i]') ||
                        document.querySelector('button[data-testid="control-button-skip-forward"]');
                    if (nextBtn) {
                        try {
                            nextBtn.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window}));
                        } catch(e) {
                            nextBtn.click();
                        }
                        // Catch the case where YouTube's SPA advances the
                        // URL but leaves the page chrome stale.
                        forceReloadIfUrlAdvanced(1500);
                        return;
                    }

                    // ── Strategy 3: Fire 'ended' so YouTube's autoplay takes over ──
                    var media = document.querySelector('video, audio');
                    if (media && isFinite(media.duration)) {
                        try {
                            media.currentTime = Math.max(0, media.duration - 0.05);
                            media.dispatchEvent(new Event('ended', {bubbles:true}));
                        } catch(e) {}
                        forceReloadIfUrlAdvanced(2500);
                    }
                })();
            """.trimIndent(),
                            "(function(){ if(window.nextStation){ window.nextStation(); } })();"
                    )
                    // YouTube SPA navigations take several seconds to update the title.
                    // Schedule aggressive delayed refreshes to catch the new video name.
                    scheduleTrackChangeRefresh()
                }

        btnMaskPause.visibility = View.GONE // Initially show Play

        maskMediaControlsContainer.addView(btnMaskPrevTrack)
        maskMediaControlsContainer.addView(btnMaskPrev)
        maskMediaControlsContainer.addView(btnMaskPlay)
        maskMediaControlsContainer.addView(btnMaskPause)
        maskMediaControlsContainer.addView(btnMaskNext)
        maskMediaControlsContainer.addView(btnMaskNextTrack)

        // ── Audio Visualizer ──
        maskVisualizerView = AudioVisualizerView(context).apply {
            visibility = View.GONE
            alpha = 0.6f
        }
        val vizParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 180
        ).apply {
            gravity = Gravity.CENTER
            leftMargin = 40
            rightMargin = 40
        }
        maskOverlay.addView(maskVisualizerView, vizParams)

        // Toggle button — placed inline with the media-controls toolbar, as
        // the right-most child. Custom-drawn spectrum-bars-with-mirror icon
        // that matches the reference art (purple/magenta EQ silhouette with
        // a soft reflection below).
        btnVisualizerToggle = SpectrumVizButton(context).apply {
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(40, 40).apply {
                leftMargin = 4
                rightMargin = 4
            }
            setOnClickListener {
                val now = SystemClock.uptimeMillis()
                if (now - lastVisualizerToggleTime < 200) return@setOnClickListener
                lastVisualizerToggleTime = now
                if (!isVisualizerVisible) {
                    showVisualizer()
                } else {
                    hideVisualizer()
                }
            }
        }
        updateVisualizerButtonColor()
        maskMediaControlsContainer.addView(btnVisualizerToggle)
    }

    private fun showVisualizer() {
        isVisualizerVisible = true
        maskVisualizerView.visibility = View.VISIBLE
        maskVisualizerView.bringToFront()
        maskVisualizerView.startAnimating()
        startAudioCapture()
        // Bring controls and text back to front
        maskMediaControlsContainer.bringToFront()
        maskNowPlayingText.bringToFront()
        btnVisualizerToggle.bringToFront()
        btnVisualizerToggle.alpha = 1.0f
        updateVisualizerButtonColor()
    }

    private fun hideVisualizer() {
        isVisualizerVisible = false
        stopAudioCapture()
        maskVisualizerView.stopAnimating()
        maskVisualizerView.visibility = View.GONE
        btnVisualizerToggle.alpha = 0.5f
        updateVisualizerButtonColor()
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        // Reuse existing Visualizer if already created — releasing and recreating
        // Visualizer(0) disrupts the audio pipeline and can stop TapRadio playback.
        val existing = audioVisualizer
        if (existing != null) {
            try {
                if (!existing.enabled) existing.enabled = true
                Log.d("AudioViz", "Visualizer re-enabled (reused existing instance)")
                return
            } catch (e: Exception) {
                // Existing instance is dead, release and recreate
                Log.w("AudioViz", "Existing visualizer unusable, recreating: ${e.message}")
                try { existing.release() } catch (_: Exception) {}
                audioVisualizer = null
            }
        }
        try {
            // Session 0 = mix of all audio output
            val viz = Visualizer(0)
            viz.captureSize = Visualizer.getCaptureSizeRange()[1]  // max for best resolution
            viz.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null) return
                    val buckets = fftMagnitudes.size
                    val fftSize = fft.size / 2  // real/imag pairs
                    val capture = FloatArray(buckets)
                    for (i in 0 until buckets) {
                        val startFrac = (i.toFloat() / buckets.toFloat()).toDouble().pow(1.55).toFloat()
                        val endFrac = ((i + 1).toFloat() / buckets.toFloat()).toDouble().pow(1.55).toFloat()
                        val start = (startFrac * fftSize).toInt().coerceIn(1, maxOf(1, fftSize - 1))
                        val end = (endFrac * fftSize).toInt().coerceIn(start + 1, fftSize)
                        var peakDb = 0f
                        var avgDb = 0f
                        var samples = 0
                        for (bin in start until end) {
                            val reIdx = bin * 2
                            val imIdx = reIdx + 1
                            if (imIdx >= fft.size) break
                            val re = fft[reIdx].toFloat()
                            val im = fft[imIdx].toFloat()
                            val magnitude = kotlin.math.sqrt(re * re + im * im)
                            val db = (20f * kotlin.math.log10(1f + magnitude)).coerceAtLeast(0f)
                            if (db > peakDb) peakDb = db
                            avgDb += db
                            samples++
                        }
                        val meanDb = if (samples > 0) avgDb / samples else 0f
                        val pos = i.toFloat() / (buckets - 1).coerceAtLeast(1)
                        val mixedDb = peakDb * 0.58f + meanDb * 0.42f
                        val normalized = (mixedDb / 44f).coerceIn(0f, 1f)
                        val eqWeight = when {
                            pos < 0.16f -> 0.48f + pos * 0.55f
                            pos < 0.42f -> 0.68f + (pos - 0.16f) * 1.0f
                            pos < 0.76f -> 0.94f + (pos - 0.42f) * 0.95f
                            else -> 1.26f + (pos - 0.76f) * 1.55f
                        }
                        val compressed = normalized.toDouble().pow(0.72).toFloat()
                        capture[i] = (compressed * eqWeight).coerceIn(0f, 1f)
                    }
                    // Write to back buffer, then atomically swap with front buffer
                    for (i in 0 until buckets) {
                        val leftFar = capture[maxOf(0, i - 2)]
                        val left = capture[maxOf(0, i - 1)]
                        val center = capture[i]
                        val right = capture[minOf(buckets - 1, i + 1)]
                        val rightFar = capture[minOf(buckets - 1, i + 2)]
                        val pos = i.toFloat() / (buckets - 1).coerceAtLeast(1)
                        val smoothed = leftFar * 0.08f + left * 0.18f + center * 0.38f + right * 0.22f + rightFar * 0.14f
                        val stereoBalance = when {
                            pos < 0.2f -> 0.72f
                            pos < 0.55f -> 0.82f + (pos - 0.2f) * 0.38f
                            else -> 0.95f + (pos - 0.55f) * 0.55f
                        }
                        fftBackBuffer[i] = (smoothed * stereoBalance).coerceIn(0f, 1f)
                    }
                    // Atomic swap: UI thread sees complete frame or previous frame, never partial
                    val tmp = fftFrontBuffer
                    fftFrontBuffer = fftBackBuffer
                    fftBackBuffer = tmp
                }
            }, Visualizer.getMaxCaptureRate(), false, true)  // waveform=false, fft=true
            viz.enabled = true
            audioVisualizer = viz
            Log.d("AudioViz", "Visualizer capture started, size=${viz.captureSize}")
        } catch (e: Exception) {
            Log.e("AudioViz", "Failed to start Visualizer: ${e.message}")
            // Fall back to random data mode — fftMagnitudes will stay at 0
        }
    }

    private fun stopAudioCapture() {
        // Only disable — don't release. Releasing Visualizer(0) and recreating it
        // disrupts the audio pipeline and can stop TapRadio/media playback.
        try {
            audioVisualizer?.enabled = false
        } catch (_: Exception) {}
        // Zero out both buffers so bars drop to silence
        fftFrontBuffer.fill(0f)
        fftBackBuffer.fill(0f)
    }

    /** Fully release the Visualizer (call on destroy/cleanup only). */
    fun releaseAudioCapture() {
        try {
            audioVisualizer?.enabled = false
            audioVisualizer?.release()
        } catch (_: Exception) {}
        audioVisualizer = null
        fftFrontBuffer.fill(0f)
        fftBackBuffer.fill(0f)
    }

    private fun updatePlaybackWakeLocks() {
        val shouldHoldMaskWakeLock = isScreenMasked
        if (shouldHoldMaskWakeLock) {
            if (!maskWakeLock.isHeld) maskWakeLock.acquire()
        } else if (maskWakeLock.isHeld) {
            maskWakeLock.release()
        }

        // Streaming radio/video needs a steady CPU + Wi-Fi path on these glasses.
        // The device log shows real AudioTrack underruns, so hold playback resources
        // for any active media session, not only when the host Activity is paused.
        val shouldHoldPlaybackWakeLock = isMediaPlaying
        if (shouldHoldPlaybackWakeLock) {
            if (!pausedMediaWakeLock.isHeld) pausedMediaWakeLock.acquire()
            try {
                if (mediaWifiLock?.isHeld == false) mediaWifiLock.acquire()
            } catch (_: Exception) {}
        } else {
            if (pausedMediaWakeLock.isHeld) pausedMediaWakeLock.release()
            try {
                if (mediaWifiLock?.isHeld == true) mediaWifiLock.release()
            } catch (_: Exception) {}
        }
    }

    /** Refresh the visualizer button's visual state. The button itself draws
     *  its own art; here we just flip the active flag and nudge alpha so the
     *  idle state reads as dimmer than the active state.
     */
    private fun updateVisualizerButtonColor() {
        if (!::btnVisualizerToggle.isInitialized) return
        btnVisualizerToggle.isSelected = isVisualizerVisible
        btnVisualizerToggle.setActive(isVisualizerVisible)
        btnVisualizerToggle.alpha = if (isVisualizerVisible) 1.0f else 0.78f
    }

    /**
     * Custom-drawn "spectrum" button that matches the reference art.
     *
     *   • Vertical magenta→violet gradient bars
     *   • Heights follow a wave envelope (sum-of-sines) giving the classic
     *     4-5 peak spectrum shape
     *   • Mirrored reflection below the midline, fading to transparent
     *   • Transparent background so it sits inline with the media toolbar
     *
     * Active vs idle is communicated by alpha and bar brightness.
     */
    inner class SpectrumVizButton(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var active = false

        fun setActive(a: Boolean) {
            if (active == a) return
            active = a
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            // ─── Layout ───
            val barCount = 20
            val padX = w * 0.08f
            val usableW = w - padX * 2f
            val gap = 1.5f
            val barW = (usableW - gap * (barCount - 1)) / barCount

            // Midline sits slightly below center so the main bars get more vertical room.
            val midY = h * 0.55f
            val topRoom = midY - h * 0.08f         // room above midline for main bars
            val bottomRoom = h - midY - h * 0.05f  // room below midline for reflection

            // ─── Wave envelope: sum of sines produces the 4-5 peak shape ───
            // f(x) where x ∈ [0,1]: sin(x·2π) + 0.55·sin(x·5π) + 0.25·sin(x·8π)
            // normalised into [0.12, 1.0]
            val envelope = FloatArray(barCount)
            var envMin = Float.MAX_VALUE
            var envMax = -Float.MAX_VALUE
            for (i in 0 until barCount) {
                val x = i.toFloat() / (barCount - 1).toFloat()
                val v = kotlin.math.sin(x * 2.0 * Math.PI).toFloat() +
                        0.55f * kotlin.math.sin(x * 5.0 * Math.PI).toFloat() +
                        0.25f * kotlin.math.sin(x * 8.0 * Math.PI).toFloat()
                envelope[i] = v
                if (v < envMin) envMin = v
                if (v > envMax) envMax = v
            }
            val span = (envMax - envMin).coerceAtLeast(0.0001f)
            for (i in 0 until barCount) {
                envelope[i] = 0.15f + 0.85f * ((envelope[i] - envMin) / span)
            }

            // ─── Main bars (above midline) ───
            // Neon palette: electric cyan top, hot-pink midsection, laser
            // magenta bottom — reads as a glowing EQ silhouette against any
            // toolbar background.
            val topColor    = android.graphics.Color.parseColor("#00FFF0") // electric cyan
            val midColor    = android.graphics.Color.parseColor("#FF3CEC") // hot magenta
            val bottomColor = android.graphics.Color.parseColor("#FF1E8C") // laser pink
            val dimFactor = if (active) 1.0f else 0.90f

            val corner = barW * 0.45f

            // Soft outer glow behind the main bars to sell the neon feel
            p.style = Paint.Style.FILL
            for (i in 0 until barCount) {
                val left = padX + i * (barW + gap)
                val right = left + barW
                val barH = envelope[i] * topRoom
                val top = midY - barH
                val bottom = midY
                p.color = applyAlphaToColor(
                    android.graphics.Color.parseColor("#FF59E8"),
                    (95 * dimFactor).toInt()
                )
                p.setShadowLayer(barW * 0.9f, 0f, 0f,
                    applyAlphaToColor(
                        android.graphics.Color.parseColor("#FF59E8"),
                        (200 * dimFactor).toInt()
                    )
                )
                canvas.drawRoundRect(
                    android.graphics.RectF(left, top, right, bottom),
                    corner, corner, p
                )
            }
            p.setShadowLayer(0f, 0f, 0f, 0)

            // Bright bars on top of the glow
            for (i in 0 until barCount) {
                val left = padX + i * (barW + gap)
                val right = left + barW
                val barH = envelope[i] * topRoom
                val top = midY - barH
                val bottom = midY

                p.shader = android.graphics.LinearGradient(
                    left, top, left, bottom,
                    intArrayOf(
                        applyAlphaToColor(topColor,    (255 * dimFactor).toInt()),
                        applyAlphaToColor(midColor,    (255 * dimFactor).toInt()),
                        applyAlphaToColor(bottomColor, (255 * dimFactor).toInt())
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    android.graphics.Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(
                    android.graphics.RectF(left, top, right, bottom),
                    corner, corner, p
                )
                p.shader = null

                // Bright highlight bead at the very top of each bar
                val beadH = (barH * 0.10f).coerceAtLeast(1.5f)
                p.color = applyAlphaToColor(
                    android.graphics.Color.parseColor("#FFFFFF"),
                    (220 * dimFactor).toInt()
                )
                canvas.drawRoundRect(
                    android.graphics.RectF(left, top, right, top + beadH),
                    corner, corner, p
                )

                // ── Mirror reflection below midline ──
                // Height proportional to the main bar, but clamped to bottomRoom
                val reflH = (barH * 0.75f).coerceAtMost(bottomRoom)
                val reflTop = midY
                val reflBottom = midY + reflH

                p.shader = android.graphics.LinearGradient(
                    left, reflTop, left, reflBottom,
                    intArrayOf(
                        applyAlphaToColor(bottomColor, (170 * dimFactor).toInt()),
                        applyAlphaToColor(midColor,    (60  * dimFactor).toInt()),
                        applyAlphaToColor(topColor, 0)
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    android.graphics.Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(
                    android.graphics.RectF(left, reflTop, right, reflBottom),
                    corner, corner, p
                )
                p.shader = null
            }
        }

        private fun applyAlphaToColor(color: Int, alpha: Int): Int {
            val a = alpha.coerceIn(0, 255)
            return (a shl 24) or (color and 0x00FFFFFF)
        }
    }

    /**
     * Animated audio visualizer view drawn on Canvas.
     * Themes cycle on long-press of the toggle button.
     */
    inner class AudioVisualizerView(context: Context) : View(context) {

        // Theme constants (enum class not allowed inside inner class)
        // Breathing-meditation theme is now the default (index 0). The 8-bit
        // graphic-equalizer theme has been removed. Three classic Winamp-style
        // themes are appended at the end.
        val THEME_BREATHE = 0
        val THEME_WAVE = 1
        val THEME_PULSE_RING = 2
        val THEME_MEDITATIVE = 3
        val THEME_TRON = 4
        val THEME_CLOSE_ENCOUNTERS = 5
        val THEME_FRACTAL = 6
        val THEME_WINAMP_BARS = 7        // Classic yellow→red bar spectrum w/ peak caps
        val THEME_WINAMP_SCOPE = 8       // Green oscilloscope waveform on black
        val THEME_WINAMP_STARFIELD = 9   // vis_nsfs-style warp-speed starfield
        private val THEME_COUNT = 10

        // Breathing timer state
        private var breathCycleMs = 0L          // elapsed ms in current cycle
        private var breathInhaleMs = 4000L      // 4s inhale
        private var breathHoldMs = 1000L        // 1s hold
        private var breathExhaleMs = 6000L      // 6s exhale
        private var breathPauseMs = 1000L       // 1s pause
        private val breathTotalMs get() = breathInhaleMs + breathHoldMs + breathExhaleMs + breathPauseMs
        private var lastBreathFrameTime = 0L

        var currentTheme = THEME_BREATHE
            private set
        private val barCount = 32
        private val barHeights = FloatArray(barCount)
        private val targetHeights = FloatArray(barCount)
        private val velocities = FloatArray(barCount)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var animating = false
        private val random = java.util.Random()
        private var frameCount = 0L
        private val wavePath = android.graphics.Path()

        // Peak-hold buffers used by the Tron light-wall EQ columns.
        // Sized lazily in drawTron to match the column count it chooses, so we
        // don't hard-code a magic number in two places.
        private var tronPeakL = FloatArray(24)
        private var tronPeakR = FloatArray(24)

        // Persistent scratch state for the 3D-fractal theme.
        // These are allocated once so the per-frame onDraw never churns the GC.
        private val fractalStarField = FloatArray(128) { (it * 17.31f) % 1f }
        private val fractalIfsPts = FloatArray(180)         // chaos-game trail
        private var fractalIfsInit = false
        private val fractalRotSeed = FloatArray(8) { (it * 41.9f) % 1f }

        // Persistent state for the Atomic (pulse-ring) visualizer.
        // 10 electrons distributed across 5 shells. Each has a home shell,
        // a current angular position, a spin direction, and a jump phase
        // that animates transient "quantum leaps" on audio spikes.
        private val atomElectronCount = 10
        private val atomShellCount = 5
        private val atomElectronShell       = IntArray(atomElectronCount)
        private val atomElectronHomeShell   = IntArray(atomElectronCount)
        private val atomElectronAngle       = FloatArray(atomElectronCount)
        private val atomElectronSpeed       = FloatArray(atomElectronCount)
        private val atomElectronJumpPhase   = FloatArray(atomElectronCount)
        private val atomElectronTargetShell = IntArray(atomElectronCount)
        private val atomElectronFlash       = FloatArray(atomElectronCount)
        private val atomBandPrev            = FloatArray(atomShellCount)
        private val atomShellTilt           = FloatArray(atomShellCount)
        private var atomInit = false

        // Persistent state for the Tron Recognizer + Tank actors.
        // X position rides across the grid; pieces respond to audio.
        private var tronRecogX = -0.25f   // -0.25..1.25 in normalized X
        private var tronTankX  = 1.15f

        // Persistent state for the Meditative theme.
        // ── Drifting nebula stars (slow parallax backdrop) ──
        private val medStarCount = 60
        private val medStarX    = FloatArray(medStarCount) { (it * 73.13f) % 1f }
        private val medStarY    = FloatArray(medStarCount) { (it * 19.77f) % 1f }
        private val medStarSize = FloatArray(medStarCount) { 0.25f + (it * 13.9f) % 0.75f }
        private val medStarTwinkle = FloatArray(medStarCount) { (it * 7.31f) % 1f }
        // ── Particle history trails: ring buffer of last N positions per particle ──
        private val medParticleCount = 18
        private val medTrailLen = 10
        private val medTrailX = FloatArray(medParticleCount * medTrailLen)
        private val medTrailY = FloatArray(medParticleCount * medTrailLen)
        private var medTrailHead = 0
        private var medTrailInit = false
        // ── Bass-spike ray burst: transient energy that decays per frame ──
        private var medBurstEnergy = 0f
        private var medBurstRot = 0f
        private var medPrevBass = 0f
        // ── Mandala petal rotation (accumulates slowly with mid-band) ──
        private var medMandalaRot = 0f

        // ── Winamp-style themes: persistent state ──────────────────────────
        // Classic-bars peak caps (falling white dots over each spectrum column)
        private val winampBarPeak = FloatArray(barCount)
        private val winampBarHold = IntArray(barCount)
        // Scope: rolling oscilloscope history — each entry is a [-1,1] sample
        private val winampScopeLen = 128
        private val winampScope = FloatArray(winampScopeLen)
        private var winampScopeHead = 0
        // Starfield (vis_nsfs-style): 3D stars that fly toward the viewer
        private val winampStarN = 160
        private val winampStarX = FloatArray(winampStarN)
        private val winampStarY = FloatArray(winampStarN)
        private val winampStarZ = FloatArray(winampStarN)
        private var winampStarsInit = false
        private val winampRand = java.util.Random(0xC0FFEE)

        // Theme color palettes
        private val neonColors = intArrayOf(
            Color.parseColor("#FF00FF"), Color.parseColor("#00FFFF"),
            Color.parseColor("#FF006E"), Color.parseColor("#00FF88"),
            Color.parseColor("#8B5CF6"), Color.parseColor("#06B6D4")
        )
        private val spectrumColors = intArrayOf(
            Color.parseColor("#FF0000"), Color.parseColor("#FF7700"),
            Color.parseColor("#FFFF00"), Color.parseColor("#00FF00"),
            Color.parseColor("#0077FF"), Color.parseColor("#8800FF")
        )

        fun cycleTheme() {
            currentTheme = (currentTheme + 1) % THEME_COUNT
            invalidate()
        }

        private fun applyTheme(theme: Int) {
            currentTheme = ((theme % THEME_COUNT) + THEME_COUNT) % THEME_COUNT
            frameCount = 0L
            breathCycleMs = 0L
            lastBreathFrameTime = 0L
            wavePath.reset()
            paint.reset()
            paint.isAntiAlias = true
            paint.setShadowLayer(0f, 0f, 0f, 0)
            invalidate()
        }

        /** Advance theme sequentially and wrap back to the first theme without hiding. */
        fun cycleThemeOrWrap(): Boolean {
            applyTheme(currentTheme + 1)
            updateVisualizerButtonColor()
            return false
        }

        fun startAnimating() {
            animating = true
            applyTheme(currentTheme)
            postInvalidateOnAnimation()
        }

        fun stopAnimating() {
            animating = false
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!animating) return

            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            paint.reset()
            paint.isAntiAlias = true
            paint.setShadowLayer(0f, 0f, 0f, 0)
            paint.shader = null
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = android.graphics.Typeface.DEFAULT
            wavePath.reset()

            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            // Pull real FFT magnitudes from the audio capture
            frameCount++
            for (i in 0 until barCount) {
                targetHeights[i] = fftMagnitudes[i]
            }
            // Smooth interpolation — fast attack, slower decay for punchy response
            for (i in 0 until barCount) {
                val target = targetHeights[i]
                if (target > barHeights[i]) {
                    // Fast attack: snap up quickly to new peaks
                    barHeights[i] = barHeights[i] * 0.3f + target * 0.7f
                } else {
                    // Slower decay: smooth falloff for visual appeal
                    barHeights[i] = barHeights[i] * 0.75f + target * 0.25f
                }
                barHeights[i] = barHeights[i].coerceIn(0.0f, 1f)
            }

            when (currentTheme) {
                THEME_BREATHE -> drawBreathe(canvas, w, h)
                THEME_WAVE -> drawWave(canvas, w, h)
                THEME_PULSE_RING -> drawPulseRing(canvas, w, h)
                THEME_MEDITATIVE -> drawMeditative(canvas, w, h)
                THEME_TRON -> drawTron(canvas, w, h)
                THEME_CLOSE_ENCOUNTERS -> drawCloseEncounters(canvas, w, h)
                THEME_FRACTAL -> drawFractal(canvas, w, h)
                THEME_WINAMP_BARS -> drawWinampBars(canvas, w, h)
                THEME_WINAMP_SCOPE -> drawWinampScope(canvas, w, h)
                THEME_WINAMP_STARFIELD -> drawWinampStarfield(canvas, w, h)
            }

            if (animating) postInvalidateOnAnimation()
        }

        private fun bandEnergy(startInclusive: Int, endInclusive: Int): Float {
            val safeStart = startInclusive.coerceIn(0, barCount - 1)
            val safeEnd = endInclusive.coerceIn(safeStart, barCount - 1)
            var total = 0f
            var count = 0
            for (i in safeStart..safeEnd) {
                total += barHeights[i]
                count++
            }
            return if (count > 0) total / count else 0f
        }

        private fun remapVisualizerPosition(pos: Float): Float {
            val clamped = pos.coerceIn(0f, 1f)
            val curved = clamped.toDouble().pow(1.18).toFloat()
            return (curved * 0.78f + clamped * 0.22f).coerceIn(0f, 1f)
        }

        private fun bandWindow(centerPos: Float, radius: Int = 2): Float {
            val center = (centerPos.coerceIn(0f, 1f) * (barCount - 1)).toInt().coerceIn(0, barCount - 1)
            var total = 0f
            var weightSum = 0f
            for (offset in -radius..radius) {
                val idx = (center + offset).coerceIn(0, barCount - 1)
                val weight = 1f / (1f + kotlin.math.abs(offset).toFloat())
                total += barHeights[idx] * weight
                weightSum += weight
            }
            return if (weightSum > 0f) total / weightSum else 0f
        }

        private fun measuredAudioAt(pos: Float, lowCut: Float = 0.82f, highBoost: Float = 1.22f): Float {
            val remapped = remapVisualizerPosition(pos)
            val wide = bandWindow(remapped, 3)
            val tight = bandWindow(remapped, 1)
            val blended = (wide * 0.45f + tight * 0.55f).coerceIn(0f, 1f)
            val tonalWeight = when {
                remapped < 0.18f -> lowCut
                remapped < 0.52f -> 0.9f + (remapped - 0.18f) * 0.55f
                else -> 1.02f + (remapped - 0.52f) * ((highBoost - 1.02f) / 0.48f)
            }
            val compressed = blended.toDouble().pow(0.82).toFloat()
            return (compressed * tonalWeight).coerceIn(0f, 1f)
        }

        private fun measuredYOffset(sample: Float, amplitude: Float, floor: Float = 0.28f): Float {
            return (sample - floor) * amplitude
        }

        /**
         * Classical Orchestra Theme — Musicians on a warm concert stage.
         *
         * ALL figure geometry uses a unit scale `u` derived from canvas
         * height so proportions stay correct regardless of aspect ratio.
         * When audio is present, every musician visibly plays their
         * instrument — bow strokes, finger movement, body sway — all
         * proportional to their frequency band's energy.
         * When silent, every musician holds still at rest position.
         */
        private fun drawWave(canvas: Canvas, w: Float, h: Float) {
            val avgLevel = bandEnergy(0, barCount - 1)
            val bass = bandEnergy(0, 6)
            val lowMid = bandEnergy(7, 14)
            val highMid = bandEnergy(15, 23)
            val treble = bandEnergy(24, 31)
            val active = avgLevel > 0.04f
            val motion = if (active) avgLevel.coerceIn(0f, 1f) else 0f

            // Unit scale: all musician geometry derives from this.
            // Large scale fills the glasses frame edge-to-edge.
            val u = h * 0.035f
            val stageTop = h * 0.42f
            val stageCenterY = h * 0.62f

            // Time bases for musical motion (doubled speed: ~1.5s and ~2.4s cycles)
            val tSlow = frameCount * 0.044f
            val tMed  = frameCount * 0.070f

            // ── Background ──
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    Color.parseColor("#06090F"),
                    Color.parseColor("#0E1722"),
                    Color.parseColor("#14100C")
                ),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // Stage floor — edge-to-edge for baroque close-up
            paint.color = Color.argb(78, 200, 165, 85)
            canvas.drawOval(w * 0.02f, stageTop, w * 0.98f, h * 1.05f, paint)
            paint.color = Color.argb(140, 24, 16, 8)
            canvas.drawRect(0f, stageTop + 2f, w, h, paint)
            paint.style = Paint.Style.STROKE
            paint.color = Color.argb(90, 240, 200, 130)
            paint.strokeWidth = 1.2f
            canvas.drawLine(w * 0.02f, stageTop, w * 0.98f, stageTop, paint)

            // Stage spots — spread across full width
            paint.style = Paint.Style.FILL
            val sA = (30 + motion * 40f).toInt().coerceIn(30, 75)
            paint.color = Color.argb(sA, 255, 225, 160)
            canvas.drawCircle(w * 0.18f, h * 0.08f, 4f + treble * 2.5f, paint)
            canvas.drawCircle(w * 0.50f, h * 0.04f, 5f + highMid * 3f, paint)
            canvas.drawCircle(w * 0.82f, h * 0.08f, 4f + lowMid * 2.5f, paint)

            // Light cones — wider to cover full stage
            if (motion > 0.05f) {
                paint.color = Color.argb((motion * 18f).toInt().coerceIn(0, 22), 255, 220, 150)
                val cone = android.graphics.Path()
                for (spot in floatArrayOf(0.18f, 0.50f, 0.82f)) {
                    cone.reset()
                    cone.moveTo(w * spot, h * 0.06f)
                    cone.lineTo(w * (spot - 0.08f), stageTop)
                    cone.lineTo(w * (spot + 0.08f), stageTop)
                    cone.close()
                    canvas.drawPath(cone, paint)
                }
            }

            // ═══ Drawing helpers ═══

            // Diverse skin tone palette
            val skinTones = intArrayOf(
                Color.parseColor("#FCDEC0"),  // light
                Color.parseColor("#C68642"),  // medium brown
                Color.parseColor("#8D5524"),  // dark brown
                Color.parseColor("#E0AC69"),  // golden
                Color.parseColor("#503335"),  // deep brown
                Color.parseColor("#D4A574"),  // olive
                Color.parseColor("#A0522D")   // sienna
            )

            fun drawHead(hx: Float, hy: Float, r: Float, skinColor: Int = skinTones[0]) {
                paint.style = Paint.Style.FILL
                paint.color = skinColor
                canvas.drawCircle(hx, hy, r, paint)
                paint.color = Color.argb(45, 20, 12, 8)
                canvas.drawCircle(hx, hy + r * 0.18f, r * 0.82f, paint)
            }

            fun drawTorso(bx: Float, top: Float, bw: Float, bh: Float, color: Int) {
                paint.style = Paint.Style.FILL
                paint.color = color
                canvas.drawRoundRect(bx - bw / 2f, top, bx + bw / 2f, top + bh, bw * 0.28f, bw * 0.28f, paint)
            }

            fun drawLimb(sx: Float, sy: Float, ex: Float, ey: Float, hx: Float, hy: Float, width: Float, color: Int) {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = width
                paint.color = color
                canvas.drawLine(sx, sy, ex, ey, paint)
                canvas.drawLine(ex, ey, hx, hy, paint)
            }

            // ═══ Musicians ═══
            val headR = u * 1.6f
            val bodyW = u * 2.8f
            val bodyH = u * 8f
            val armW = u * 0.18f + 1.2f  // arm stroke width

            fun drawViolinist(cx: Float, cy: Float, energy: Float, dress: Int, skinColor: Int, seed: Float) {
                val e = if (active) energy.coerceIn(0f, 1f) else 0f
                val torsoTop = cy - u * 5f
                val shoulderY = cy - u * 2.4f
                val shoulderL = cx - u * 1.2f
                val shoulderR = cx + u * 1.2f

                // Gentle sway tied to energy
                val sway = kotlin.math.sin((tSlow + seed).toDouble()).toFloat() * e * u * 0.5f
                drawHead(cx + sway * 0.3f, torsoTop - headR * 1.1f, headR, skinColor)
                drawTorso(cx + sway * 0.12f, torsoTop, bodyW, bodyH, dress)

                // Violin body tucked under chin, left side
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#A96A2A")
                val vl = cx - u * 0.5f
                val vt = cy - u * 1.8f
                canvas.drawOval(vl, vt, vl + u * 2.8f, vt + u * 2.2f, paint)
                // Strings
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.4f
                paint.color = Color.parseColor("#DDD0B0")
                canvas.drawLine(vl + u * 0.7f, vt + u * 0.3f, vl + u * 2.1f, vt + u * 1.9f, paint)

                // Left arm: neck hand — slight vibrato shift
                val vibrato = kotlin.math.sin((tMed * 2.4f + seed * 3f).toDouble()).toFloat() * e * u * 0.4f
                drawLimb(
                    shoulderL, shoulderY,
                    cx - u * 0.2f, cy - u * 1.4f,
                    cx + u * 0.8f + vibrato * 0.3f, cy - u * 1.6f - vibrato,
                    armW, skinColor
                )

                // Right arm: BOW arm — clear sweeping motion
                // The bow hand moves in an arc; bow angle rotates with it.
                val bowPhase = kotlin.math.sin((tMed + seed * 1.7f).toDouble()).toFloat()
                val bowSwing = bowPhase * e * (u * 1.2f + e * u * 2.5f) // visible motion
                val elbowR_x = shoulderR + u * 0.8f
                val elbowR_y = cy - u * 0.6f + bowSwing * 0.3f
                val handR_x = cx + u * 2f
                val handR_y = cy + u * 0.2f + bowSwing * 0.5f
                drawLimb(shoulderR, shoulderY, elbowR_x, elbowR_y, handR_x, handR_y, armW, skinColor)

                // Bow stick
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 1.2f
                paint.color = Color.parseColor("#E0D0B0")
                val bowTip_x = cx - u * 1.2f
                val bowTip_y = cy - u * 1.2f + bowSwing * 0.4f
                canvas.drawLine(handR_x, handR_y, bowTip_x, bowTip_y, paint)

            }

            fun drawCellist(cx: Float, cy: Float, energy: Float, dress: Int, skinColor: Int, seed: Float) {
                val e = if (active) energy.coerceIn(0f, 1f) else 0f
                val torsoTop = cy - u * 5.5f
                val shoulderY = cy - u * 2.8f
                val shoulderL = cx - u * 1.2f
                val shoulderR = cx + u * 1.2f

                val sway = kotlin.math.sin((tSlow * 0.8f + seed).toDouble()).toFloat() * e * u * 0.4f
                drawHead(cx + sway * 0.25f, torsoTop - headR * 1.1f, headR, skinColor)
                drawTorso(cx + sway * 0.1f, torsoTop, bodyW * 1.05f, bodyH * 0.95f, dress)

                // Cello body — between knees
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#8E5524")
                canvas.drawRoundRect(
                    cx - u * 1.6f, cy - u * 1.2f,
                    cx + u * 1.6f, cy + u * 4.5f,
                    u * 0.8f, u * 0.8f, paint
                )
                // Neck
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f
                paint.color = Color.parseColor("#7A4820")
                canvas.drawLine(cx, cy - u * 3.8f, cx, cy + u * 5f, paint)

                // Left arm: fingering the neck — vibrato motion
                val vib = kotlin.math.sin((tMed * 2f + seed * 2.5f).toDouble()).toFloat() * e * u * 0.5f
                drawLimb(
                    shoulderL, shoulderY,
                    cx - u * 0.3f, cy - u * 0.5f,
                    cx + u * 0.2f, cy + u * 0.6f - vib,
                    armW, skinColor
                )

                // Right arm: bow across strings — visible sweep
                val bowPhase = kotlin.math.sin((tMed * 0.9f + seed * 2.1f).toDouble()).toFloat()
                val bowSwing = bowPhase * e * (u * 1f + e * u * 2f)
                val eR_x = shoulderR + u * 1f
                val eR_y = cy - u * 0.3f + bowSwing * 0.25f
                val hR_x = cx + u * 1.5f
                val hR_y = cy + u * 0.8f + bowSwing * 0.4f
                drawLimb(shoulderR, shoulderY, eR_x, eR_y, hR_x, hR_y, armW, skinColor)

                // Bow
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 1.2f
                paint.color = Color.parseColor("#DDD0B5")
                canvas.drawLine(
                    cx - u * 1.8f, cy + u * 0.5f + bowSwing * 0.3f,
                    cx + u * 2.2f, cy - u * 0.4f - bowSwing * 0.25f,
                    paint
                )
            }

            fun drawWoodwind(cx: Float, cy: Float, energy: Float, dress: Int, skinColor: Int, seed: Float) {
                val e = if (active) energy.coerceIn(0f, 1f) else 0f
                val torsoTop = cy - u * 4.5f
                val shoulderY = cy - u * 2.2f
                val shoulderL = cx - u * 1.1f
                val shoulderR = cx + u * 1.1f

                // Slight rhythmic lean
                val lean = kotlin.math.sin((tSlow * 1.1f + seed).toDouble()).toFloat() * e * u * 0.3f
                drawHead(cx + lean * 0.2f, torsoTop - headR * 1.12f, headR, skinColor)
                drawTorso(cx + lean * 0.1f, torsoTop, bodyW * 0.95f, bodyH * 0.9f, dress)

                // Clarinet: angled down from mouth
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = u * 0.3f
                paint.color = Color.parseColor("#1A1A1A")
                val clTop_x = cx + u * 0.1f
                val clTop_y = cy - u * 2.8f
                val clBot_x = cx + u * 0.6f
                val clBot_y = cy + u * 2.2f
                canvas.drawLine(clTop_x, clTop_y, clBot_x, clBot_y, paint)
                // Keys
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#C8A848")
                for (k in 1..4) {
                    val t = k / 5f
                    val kx = clTop_x + (clBot_x - clTop_x) * t + u * 0.15f
                    val ky = clTop_y + (clBot_y - clTop_y) * t
                    canvas.drawCircle(kx, ky, u * 0.12f, paint)
                }

                // Finger movement: both hands flex on keys, reactive to treble
                val fingerPhase = kotlin.math.sin((tMed * 1.6f + seed * 1.3f).toDouble()).toFloat()
                val fingerMove = fingerPhase * e * u * 0.6f

                // Left hand: upper half of instrument
                drawLimb(
                    shoulderL, shoulderY,
                    cx - u * 0.5f, cy - u * 1.2f + fingerMove * 0.2f,
                    cx + u * 0.15f, cy - u * 1.5f + fingerMove,
                    armW, skinColor
                )
                // Right hand: lower half
                drawLimb(
                    shoulderR, shoulderY,
                    cx + u * 0.6f, cy - u * 0.4f - fingerMove * 0.15f,
                    cx + u * 0.4f, cy + u * 0.5f - fingerMove * 0.5f,
                    armW, skinColor
                )
            }

            fun drawHornPlayer(cx: Float, cy: Float, energy: Float, dress: Int, skinColor: Int, seed: Float) {
                val e = if (active) energy.coerceIn(0f, 1f) else 0f
                val torsoTop = cy - u * 4.5f
                val shoulderY = cy - u * 2.2f
                val shoulderL = cx - u * 1.1f
                val shoulderR = cx + u * 1.1f
                val headY = torsoTop - headR * 1.1f
                val mouthY = headY + headR * 0.5f  // mouth is lower half of head

                drawHead(cx, headY, headR, skinColor)
                drawTorso(cx, torsoTop, bodyW * 0.97f, bodyH * 0.9f, dress)

                // French horn: mouthpiece at face, bell at lap held by right hand
                val bellCx = cx + u * 1.8f
                val bellCy = cy + u * 0.2f
                val bellR = u * 1.8f + e * u * 0.4f

                // Tubing: from mouth → down to bell (drawn first, behind arms)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = u * 0.22f
                paint.color = Color.parseColor("#D4A84C")
                val tubePath = android.graphics.Path()
                tubePath.moveTo(cx + u * 0.6f, mouthY)  // mouthpiece at face
                tubePath.quadTo(cx + u * 1.8f, cy - u * 1.5f, bellCx - bellR * 0.3f, bellCy - u * 0.5f)
                canvas.drawPath(tubePath, paint)

                // Mouthpiece nub at face
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#E0C050")
                canvas.drawCircle(cx + u * 0.6f, mouthY, u * 0.18f, paint)

                // Bell
                paint.color = Color.parseColor("#C9952E")
                canvas.drawCircle(bellCx, bellCy, bellR, paint)
                // Dark inner bell
                paint.color = Color.parseColor("#3A2A10")
                canvas.drawCircle(bellCx + u * 0.2f, bellCy, bellR * 0.6f, paint)

                // Left arm: valve hand on tubing — visible finger action
                val valvePhase = kotlin.math.sin((tMed * 1.8f + seed * 1.4f).toDouble()).toFloat()
                val valveMove = valvePhase * e * u * 0.5f
                drawLimb(
                    shoulderL, shoulderY,
                    cx + u * 0.3f, cy - u * 0.8f + valveMove * 0.2f,
                    cx + u * 0.8f, cy - u * 0.5f + valveMove,
                    armW, skinColor
                )
                // Right arm: supports bell from below
                drawLimb(
                    shoulderR, shoulderY,
                    cx + u * 1.4f, cy - u * 0.4f,
                    bellCx - u * 0.3f, bellCy + u * 0.8f,
                    armW, skinColor
                )
            }

            fun drawConductor(cx: Float, cy: Float, skinColor: Int) {
                val e = motion
                val torsoTop = cy - u * 5.5f
                val shoulderY = cy - u * 2.8f
                val shoulderL = cx - u * 1.3f
                val shoulderR = cx + u * 1.3f

                val sway = kotlin.math.sin((tSlow * 0.7f).toDouble()).toFloat() * e * u * 0.4f
                drawHead(cx + sway * 0.3f, torsoTop - headR * 1.15f, headR * 1.06f, skinColor)
                drawTorso(cx + sway * 0.12f, torsoTop, bodyW * 1.1f, bodyH * 1.05f, Color.parseColor("#1A1F2A"))

                // Conducting gesture: smooth figure-8 that grows with volume
                val bx = kotlin.math.sin((tSlow * 1.1f).toDouble()).toFloat() * e * (u * 0.8f + e * u * 2.5f)
                val by = kotlin.math.cos((tSlow * 2.2f).toDouble()).toFloat() * e * (u * 0.5f + e * u * 1.8f)

                // Left arm: cue hand
                drawLimb(
                    shoulderL, shoulderY,
                    shoulderL - u * 1f - bx * 0.2f, shoulderY + u * 1.2f - by * 0.25f,
                    shoulderL - u * 1.8f - bx * 0.35f, shoulderY + u * 2.5f - by * 0.4f,
                    armW * 1.05f, skinColor
                )

                // Right arm: baton
                val bhX = shoulderR + u * 1.5f + bx * 0.4f
                val bhY = shoulderY + u * 0.8f - by * 0.5f
                drawLimb(
                    shoulderR, shoulderY,
                    shoulderR + u * 0.8f + bx * 0.2f, shoulderY + u * 0.4f - by * 0.3f,
                    bhX, bhY,
                    armW * 1.05f, skinColor
                )
                // Baton stick
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 1f
                paint.color = Color.parseColor("#FFF3D6")
                canvas.drawLine(bhX, bhY, bhX + u * 2f, bhY - u * 1.5f - by * 0.3f, paint)
            }

            // ═══ Place musicians — baroque close seating, edge-to-edge ═══
            // 7 musicians packed tightly across the full frame width
            // Slight Y offsets give depth (front/back row feel)
            drawViolinist(w * 0.06f, stageCenterY + u * 0.3f, highMid, Color.parseColor("#24324A"), skinTones[0], 0f)
            drawViolinist(w * 0.19f, stageCenterY + u * 1.0f, treble, Color.parseColor("#2D1F34"), skinTones[3], 1.3f)
            drawCellist(w * 0.33f, stageCenterY + u * 1.8f, bass, Color.parseColor("#2A2434"), skinTones[5], 0.7f)
            drawConductor(w * 0.50f, stageCenterY - u * 0.4f, skinTones[1])
            drawWoodwind(w * 0.67f, stageCenterY + u * 0.6f, treble, Color.parseColor("#213247"), skinTones[4], 2.1f)
            drawHornPlayer(w * 0.81f, stageCenterY + u * 1.4f, lowMid, Color.parseColor("#342521"), skinTones[6], 3.4f)
            drawCellist(w * 0.94f, stageCenterY + u * 2.0f, bass * 0.8f + lowMid * 0.2f, Color.parseColor("#2B1F22"), skinTones[2], 4.0f)
        }

        /**
         * Atomic Structure visualizer (formerly "pulse ring").
         *
         * The scene is a tilted Bohr-style atom:
         *   • Nucleus: cluster of protons (red) + neutrons (gray), jittering
         *     with bass energy; surrounded by a soft halo.
         *   • 5 concentric electron shells (1s, 2s, 2p, 3s, 3p) drawn as
         *     tilted ellipses so the atom feels three-dimensional.
         *   • 10 electrons orbit the shells. Each has a "home shell" and
         *     persistent angular velocity.
         *
         * Audio response:
         *   • Each of the 5 shells is mapped to a frequency band. When a band
         *     spikes above its recent baseline, one of its electrons is
         *     triggered to perform a quantum leap — it animates from its
         *     home shell to an excited shell and falls back, emitting a
         *     bright expanding "photon" flash at the jump point.
         *   • Shell tilt drifts slowly; drift speed scales with overall level.
         *   • Nucleus wobble amplitude scales with bass.
         */
        private fun drawPulseRing(canvas: Canvas, w: Float, h: Float) {
            val cx = w / 2f
            val cy = h / 2f
            val maxR = kotlin.math.min(w, h) * 0.45f
            val t = frameCount * 0.03f

            val avgLevel = bandEnergy(0, barCount - 1)
            val bass     = bandEnergy(0, 5)
            val lowMid   = bandEnergy(6, 12)
            val mid      = bandEnergy(13, 19)
            val highMid  = bandEnergy(20, 25)
            val treble   = bandEnergy(26, 31)
            val bandEnergies = floatArrayOf(bass, lowMid, mid, highMid, treble)

            // Lazy one-time init of electron/shell state.
            if (!atomInit) {
                // Distribute 10 electrons across 5 shells: 1,2,2,2,3 (Aufbau-ish).
                val layout = intArrayOf(0, 1, 1, 2, 2, 2, 3, 3, 4, 4)
                for (i in 0 until atomElectronCount) {
                    atomElectronHomeShell[i] = layout[i]
                    atomElectronShell[i]     = layout[i]
                    atomElectronAngle[i]     = (i * 0.6283f) % 6.2832f
                    // Alternate direction so shells don't all spin the same way.
                    val dir = if (i % 2 == 0) 1f else -1f
                    atomElectronSpeed[i]     = dir * (0.6f + ((i * 13) % 7) * 0.08f)
                    atomElectronJumpPhase[i] = 0f
                    atomElectronTargetShell[i] = layout[i]
                    atomElectronFlash[i]     = 0f
                }
                for (s in 0 until atomShellCount) {
                    atomShellTilt[s] = 0.32f + s * 0.13f + (s * 31.7f) % 0.5f
                }
                atomInit = true
            }

            // Slow drift of shell tilt so the atom rotates in 3D subtly.
            for (s in 0 until atomShellCount) {
                atomShellTilt[s] += 0.002f + avgLevel * 0.006f
            }

            // Detect per-band spikes and trigger quantum leaps.
            for (b in 0 until atomShellCount) {
                val cur = bandEnergies[b]
                val prev = atomBandPrev[b]
                // A "spike" = sudden rise above a threshold
                if (cur > 0.22f && (cur - prev) > 0.10f) {
                    // Find a resting electron whose home shell matches the
                    // firing band; if none found, pick any available.
                    var chosen = -1
                    for (i in 0 until atomElectronCount) {
                        if (atomElectronJumpPhase[i] <= 0f && atomElectronHomeShell[i] == b) {
                            chosen = i; break
                        }
                    }
                    if (chosen < 0) {
                        for (i in 0 until atomElectronCount) {
                            if (atomElectronJumpPhase[i] <= 0f) { chosen = i; break }
                        }
                    }
                    if (chosen >= 0) {
                        atomElectronTargetShell[chosen] =
                            (atomElectronHomeShell[chosen] + 1 + (cur * 2f).toInt())
                                .coerceAtMost(atomShellCount - 1)
                        atomElectronJumpPhase[chosen] = 0.001f  // starts the jump
                        atomElectronFlash[chosen]     = 1.0f    // ignite photon
                    }
                }
                // Smooth the baseline so subsequent spikes need to exceed it
                atomBandPrev[b] = prev * 0.80f + cur * 0.20f
            }

            // ── Deep-space radial background ─────────────────────────────
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.RadialGradient(
                cx, cy, maxR * 1.6f,
                intArrayOf(
                    Color.parseColor("#0A0820"),
                    Color.parseColor("#04030F"),
                    Color.parseColor("#000000")
                ),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // ── Shell palette (cool→warm, innermost→outermost) ──────────
            val shellColors = intArrayOf(
                Color.parseColor("#FF4080"),  // 1s — magenta/red
                Color.parseColor("#FFD028"),  // 2s — gold
                Color.parseColor("#6CE0FF"),  // 2p — cyan
                Color.parseColor("#A880FF"),  // 3s — lavender
                Color.parseColor("#70FFB8")   // 3p — mint
            )
            val shellLabels = arrayOf("1s", "2s", "2p", "3s", "3p")
            val shellRadii = FloatArray(atomShellCount) { s ->
                maxR * (0.22f + s * 0.13f)
            }

            // ── Electron shells as tilted ellipses ──────────────────────
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.1f
            for (s in 0 until atomShellCount) {
                val r = shellRadii[s]
                val tilt = atomShellTilt[s]
                val yScale = (0.42f + 0.4f * kotlin.math.cos(tilt.toDouble()).toFloat())
                    .coerceIn(0.28f, 0.95f)
                val rect = android.graphics.RectF(
                    cx - r, cy - r * yScale,
                    cx + r, cy + r * yScale
                )
                paint.color = shellColors[s]
                paint.alpha = (40 + bandEnergies[s] * 90).toInt().coerceIn(40, 180)
                paint.setShadowLayer(4f + bandEnergies[s] * 8f, 0f, 0f, shellColors[s])
                canvas.drawOval(rect, paint)
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── Nucleus: protons (red) and neutrons (grey) cluster ──────
            val nucleusR = maxR * (0.10f + bass * 0.05f)
            // Soft halo glow
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.RadialGradient(
                cx, cy, nucleusR * 3.2f,
                intArrayOf(
                    Color.argb((120 + bass * 100).toInt().coerceAtMost(220), 255, 180, 80),
                    Color.argb(40, 200, 40, 20),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, nucleusR * 3.2f, paint)
            paint.shader = null

            val nucleonCount = 11
            for (n in 0 until nucleonCount) {
                val na = (n * 2.399f + t * 0.4f)
                val wobble = bass * 0.3f + 0.1f
                val rr = nucleusR * (0.25f + ((n * 17) % 10) / 14f)
                val nx = cx + kotlin.math.cos(na.toDouble()).toFloat() * rr * (1f + wobble * kotlin.math.sin((t * 3f + n).toDouble()).toFloat())
                val ny = cy + kotlin.math.sin(na.toDouble()).toFloat() * rr * (1f + wobble * kotlin.math.cos((t * 3f + n).toDouble()).toFloat())
                val isProton = (n % 2 == 0)
                val color = if (isProton) Color.parseColor("#FF3040") else Color.parseColor("#C8C8D0")
                paint.color = color
                paint.alpha = (220 + bass * 35).toInt().coerceAtMost(255)
                paint.setShadowLayer(5f + bass * 4f, 0f, 0f, color)
                canvas.drawCircle(nx, ny, nucleusR * 0.28f, paint)
                // Specular highlight
                paint.color = Color.WHITE
                paint.alpha = 140
                paint.setShadowLayer(0f, 0f, 0f, 0)
                canvas.drawCircle(nx - nucleusR * 0.09f, ny - nucleusR * 0.09f, nucleusR * 0.08f, paint)
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // Atomic symbol at center of nucleus
            paint.style = Paint.Style.FILL
            paint.typeface = android.graphics.Typeface.MONOSPACE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = nucleusR * 0.55f
            paint.color = Color.parseColor("#FFE090")
            paint.alpha = (110 + avgLevel * 120).toInt().coerceIn(110, 230)
            paint.setShadowLayer(4f, 0f, 0f, Color.parseColor("#FFB040"))
            canvas.drawText("Ne", cx, cy - nucleusR * 1.6f, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.textAlign = Paint.Align.LEFT

            // ── Electrons: advance angles, update jump phases, render ──
            for (i in 0 until atomElectronCount) {
                // Speed scales subtly with overall level so the atom
                // "spins up" during loud passages.
                val baseSpeed = atomElectronSpeed[i]
                val dAng = baseSpeed * (0.03f + avgLevel * 0.06f)
                atomElectronAngle[i] = (atomElectronAngle[i] + dAng) % 6.2832f

                // Advance jump phase if leaping (0→1 outbound, 1→2 return)
                var jumpT = atomElectronJumpPhase[i]
                val home = atomElectronHomeShell[i]
                val tgt  = atomElectronTargetShell[i]
                var renderShell = home.toFloat()
                if (jumpT > 0f) {
                    jumpT += 0.045f + avgLevel * 0.05f
                    if (jumpT >= 2f) {
                        jumpT = 0f
                    }
                    atomElectronJumpPhase[i] = jumpT
                    // Interpolate shell index: 0→1 out, 1→2 return
                    val p = if (jumpT < 1f) jumpT else (2f - jumpT)
                    // Smooth cosine easing for a natural leap
                    val eased = (1f - kotlin.math.cos((p * Math.PI).toFloat())) * 0.5f
                    renderShell = home + (tgt - home) * eased
                }
                val shellIdxLow  = renderShell.toInt().coerceIn(0, atomShellCount - 1)
                val shellIdxHigh = (shellIdxLow + 1).coerceAtMost(atomShellCount - 1)
                val blend = (renderShell - shellIdxLow).coerceIn(0f, 1f)
                val rLow  = shellRadii[shellIdxLow]
                val rHigh = shellRadii[shellIdxHigh]
                val eR    = rLow + (rHigh - rLow) * blend
                val tilt  = atomShellTilt[shellIdxLow] * (1f - blend) +
                            atomShellTilt[shellIdxHigh] * blend
                val yScale = (0.42f + 0.4f * kotlin.math.cos(tilt.toDouble()).toFloat())
                    .coerceIn(0.28f, 0.95f)

                val ang = atomElectronAngle[i]
                val ex = cx + kotlin.math.cos(ang.toDouble()).toFloat() * eR
                val ey = cy + kotlin.math.sin(ang.toDouble()).toFloat() * eR * yScale

                // Electron color chosen from the home shell's palette.
                val eColor = shellColors[home]

                // Short trailing arc behind the electron
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                val trailLen = 0.45f
                val arcRect = android.graphics.RectF(
                    cx - eR, cy - eR * yScale,
                    cx + eR, cy + eR * yScale
                )
                val startDeg = Math.toDegrees(ang.toDouble()).toFloat() - 180f * trailLen * 0.5f * kotlin.math.sign(baseSpeed)
                val sweepDeg = -180f * trailLen * kotlin.math.sign(baseSpeed)
                paint.color = eColor
                paint.alpha = (50 + avgLevel * 70).toInt().coerceIn(50, 170)
                paint.strokeWidth = 1.8f + avgLevel * 2.2f
                paint.setShadowLayer(4f, 0f, 0f, eColor)
                canvas.drawArc(arcRect, startDeg, sweepDeg, false, paint)
                paint.setShadowLayer(0f, 0f, 0f, 0)

                // Electron body
                paint.style = Paint.Style.FILL
                val pulse = if (jumpT > 0f) 1.7f else 1f
                val er = 3.2f + bandEnergies[home] * 3.0f
                paint.color = eColor
                paint.alpha = 255
                paint.setShadowLayer(8f + bandEnergies[home] * 10f, 0f, 0f, eColor)
                canvas.drawCircle(ex, ey, er * pulse, paint)
                // White-hot core
                paint.color = Color.WHITE
                paint.alpha = 230
                paint.setShadowLayer(3f, 0f, 0f, eColor)
                canvas.drawCircle(ex, ey, er * 0.4f, paint)
                paint.setShadowLayer(0f, 0f, 0f, 0)

                // Photon emission flash — expanding bright ring at jump start,
                // fades quickly into oblivion.
                val flash = atomElectronFlash[i]
                if (flash > 0.02f) {
                    val photonR = (1f - flash) * (shellRadii[atomShellCount - 1] * 0.35f)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.6f + flash * 2.5f
                    paint.color = Color.WHITE
                    paint.alpha = (flash * 230).toInt().coerceIn(0, 230)
                    paint.setShadowLayer(6f + flash * 10f, 0f, 0f, eColor)
                    canvas.drawCircle(ex, ey, 3f + photonR, paint)
                    // Outer ring in shell color
                    paint.color = eColor
                    paint.alpha = (flash * 180).toInt().coerceIn(0, 200)
                    canvas.drawCircle(ex, ey, 3f + photonR * 1.8f, paint)
                    paint.setShadowLayer(0f, 0f, 0f, 0)
                    atomElectronFlash[i] = flash * 0.86f
                }
            }

            // ── Shell labels on the right edge (dim legend) ─────────────
            paint.style = Paint.Style.FILL
            paint.typeface = android.graphics.Typeface.MONOSPACE
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = maxR * 0.055f
            for (s in 0 until atomShellCount) {
                paint.color = shellColors[s]
                paint.alpha = (80 + bandEnergies[s] * 170).toInt().coerceIn(80, 240)
                paint.setShadowLayer(3f, 0f, 0f, shellColors[s])
                canvas.drawText(shellLabels[s], cx + shellRadii[s] + 4f, cy + paint.textSize * 0.35f, paint)
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.textAlign = Paint.Align.LEFT

            // ── HUD ─────────────────────────────────────────────────────
            paint.typeface = android.graphics.Typeface.MONOSPACE
            paint.textSize = h * 0.028f
            paint.color = Color.parseColor("#6CE0FF")
            paint.alpha = (90 + avgLevel * 120).toInt().coerceIn(90, 220)
            paint.setShadowLayer(4f, 0f, 0f, Color.parseColor("#6CE0FF"))
            canvas.drawText("ATOMIC · QUANTUM LEAP", 6f, h * 0.045f, paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.color = Color.parseColor("#FFD028")
            canvas.drawText("Z=10 · ν %04d".format(frameCount % 10000), w - 6f, h * 0.045f, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.textAlign = Paint.Align.LEFT
        }
        /**
         * Meditative theme — enhanced.
         *
         * A calm, layered space: a deep nebula backdrop with drifting twinkling
         * stars, a slowly-rotating 12-petal mandala, nine breathing concentric
         * rings with gentle depth parallax, audio-reactive particle trails that
         * leave glowing tracers, a bloom-aura orb at the center, and a radial
         * ray burst that flashes on strong bass transients. Indigo / lavender /
         * soft gold palette throughout — every addition stays soft so the
         * overall feel remains meditative rather than energetic.
         */
        private fun drawMeditative(canvas: Canvas, w: Float, h: Float) {
            val cx = w / 2f
            val cy = h / 2f
            val maxR = minOf(w, h) * 0.48f
            val breathPhase = (frameCount % 180) / 180f
            val breath = (kotlin.math.sin(breathPhase * Math.PI * 2).toFloat() + 1f) / 2f
            val slowBreath = (kotlin.math.sin((frameCount % 360) / 360f * Math.PI * 2).toFloat() + 1f) / 2f
            val avgLevel = bandEnergy(0, barCount - 1)
            val bass = bandEnergy(0, 6)
            val lowMid = bandEnergy(4, 12)
            val mid = bandEnergy(8, 18)
            val treble = bandEnergy(20, 31)

            // ── Bass-burst detection: on a strong rising edge, seed a gentle
            //    radial ray burst. Decays smoothly so it blooms and fades.
            val bassRise = (bass - medPrevBass).coerceAtLeast(0f)
            if (bassRise > 0.06f && medBurstEnergy < bass * 0.9f) {
                medBurstEnergy = (medBurstEnergy + bassRise * 1.6f).coerceAtMost(1f)
                medBurstRot += 6f + mid * 14f
            }
            medBurstEnergy = (medBurstEnergy - 0.018f).coerceAtLeast(0f)
            medPrevBass = bass
            medMandalaRot = (medMandalaRot + 0.10f + mid * 0.55f) % 360f

            // ════════════════════════════════════════════════════════════════
            //  1. NEBULA BACKDROP — deep radial gradient with drifting stars
            // ════════════════════════════════════════════════════════════════
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.RadialGradient(
                cx, cy, maxR * 1.55f,
                intArrayOf(
                    Color.argb(60, 60, 35, 110),    // indigo center haze
                    Color.argb(30, 30, 20, 70),
                    Color.argb(0, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // Drifting starfield — parallax with very slow drift, twinkle with treble.
            for (i in 0 until medStarCount) {
                val driftSpeed = 0.00010f + (i % 7) * 0.00006f
                medStarX[i] = ((medStarX[i] + driftSpeed) % 1f)
                val twinkle = (kotlin.math.sin(((frameCount + i * 13L) % 220) / 220f * Math.PI * 2).toFloat() + 1f) / 2f
                val sx = medStarX[i] * w
                val sy = medStarY[i] * h
                val s = medStarSize[i] * (1.1f + twinkle * 1.2f + treble * 1.6f)
                val baseAlpha = (60 + twinkle * 120 + treble * 60).toInt().coerceIn(40, 220)
                paint.color = when (i % 4) {
                    0 -> Color.parseColor("#E6E6FA")  // lavender
                    1 -> Color.parseColor("#B794F4")  // violet
                    2 -> Color.parseColor("#FFD9A8")  // warm gold
                    else -> Color.parseColor("#87CEEB") // pale sky
                }
                paint.alpha = baseAlpha
                canvas.drawCircle(sx, sy, s, paint)
            }

            // ════════════════════════════════════════════════════════════════
            //  2. MANDALA PETALS — 12 soft petals rotating behind the rings
            // ════════════════════════════════════════════════════════════════
            paint.style = Paint.Style.STROKE
            val petalCount = 12
            val petalR = maxR * (0.86f + slowBreath * 0.04f)
            for (p in 0 until petalCount) {
                val a = (medMandalaRot + p * (360f / petalCount)) * Math.PI.toFloat() / 180f
                val ex = cx + kotlin.math.cos(a) * petalR
                val ey = cy + kotlin.math.sin(a) * petalR
                val bandIdx = (p * (barCount - 1) / petalCount).coerceIn(0, barCount - 1)
                val petalAudio = barHeights[bandIdx].coerceIn(0f, 1f)
                paint.strokeWidth = 1.0f + petalAudio * 2.2f + slowBreath * 0.8f
                paint.color = blendColors(
                    Color.parseColor("#4B0082"),     // indigo
                    Color.parseColor("#FFD700"),     // soft gold
                    (petalAudio * 0.55f + slowBreath * 0.2f).coerceIn(0f, 1f)
                )
                paint.alpha = (35 + petalAudio * 130 + slowBreath * 25).toInt().coerceIn(30, 180)
                canvas.drawLine(cx, cy, ex, ey, paint)

                // Soft petal tip bloom
                paint.style = Paint.Style.FILL
                paint.alpha = (paint.alpha * 0.55f).toInt().coerceIn(20, 130)
                canvas.drawCircle(ex, ey, 2.2f + petalAudio * 5f, paint)
                paint.style = Paint.Style.STROKE
            }

            // ════════════════════════════════════════════════════════════════
            //  3. CONCENTRIC BREATHING RINGS — 9 layers with depth parallax
            // ════════════════════════════════════════════════════════════════
            paint.style = Paint.Style.STROKE
            val ringCount = 9
            for (r in 0 until ringCount) {
                val baseR = maxR * (0.12f + r * 0.094f)
                val phase = (frameCount % 180 + r * 20) / 180f
                val ringBreath = (kotlin.math.sin(phase * Math.PI * 2).toFloat() + 1f) / 2f
                val ringAudio = measuredAudioAt((0.10f + r.toFloat() / ringCount * 0.78f).coerceIn(0f, 1f), lowCut = 0.9f, highBoost = 1.18f)
                val radius = baseR + ringBreath * maxR * 0.05f + ringAudio * maxR * 0.11f
                paint.strokeWidth = 1.2f + ringBreath * 1.1f + ringAudio * 3.0f
                val alpha = (40 + ringBreath * 45 + ringAudio * 95).toInt().coerceIn(40, 220)
                val color = blendColors(
                    Color.parseColor("#4B0082"),
                    Color.parseColor("#B794F4"),
                    (ringBreath * 0.45f + ringAudio * 0.55f).coerceIn(0f, 1f)
                )
                paint.color = color
                paint.alpha = alpha
                canvas.drawCircle(cx, cy, radius, paint)

                // Inner soft echo — ghost ring just inside, half alpha, no audio boost.
                if (r % 2 == 0) {
                    paint.strokeWidth = 0.8f + ringBreath * 0.6f
                    paint.alpha = (alpha * 0.35f).toInt().coerceIn(20, 110)
                    canvas.drawCircle(cx, cy, radius - 3f - ringBreath * 2f, paint)
                }
            }

            // ════════════════════════════════════════════════════════════════
            //  4. BASS-BURST RAYS — radial beams that bloom on strong bass
            // ════════════════════════════════════════════════════════════════
            if (medBurstEnergy > 0.02f) {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                val rayCount = 14
                val rayLen = maxR * (0.28f + medBurstEnergy * 0.72f)
                for (k in 0 until rayCount) {
                    val a = (medBurstRot + k * (360f / rayCount)) * Math.PI.toFloat() / 180f
                    val x0 = cx + kotlin.math.cos(a) * (maxR * 0.08f)
                    val y0 = cy + kotlin.math.sin(a) * (maxR * 0.08f)
                    val x1 = cx + kotlin.math.cos(a) * rayLen
                    val y1 = cy + kotlin.math.sin(a) * rayLen
                    paint.strokeWidth = 1.4f + medBurstEnergy * 3.8f
                    paint.color = if (k % 3 == 0) Color.parseColor("#FFD700") else Color.parseColor("#DDA0DD")
                    paint.alpha = (medBurstEnergy * 190).toInt().coerceIn(0, 200)
                    canvas.drawLine(x0, y0, x1, y1, paint)
                }
                paint.strokeCap = Paint.Cap.BUTT
            }

            // ════════════════════════════════════════════════════════════════
            //  5. PARTICLE TRAILS — fading history ring for each particle
            // ════════════════════════════════════════════════════════════════
            val particleColors = intArrayOf(
                Color.parseColor("#7B68EE"),  // medium slate blue
                Color.parseColor("#DDA0DD"),  // plum / lavender
                Color.parseColor("#FFD700"),  // soft gold
                Color.parseColor("#E6E6FA"),  // lavender mist
                Color.parseColor("#87CEEB")   // sky blue
            )

            // Initialize trails on first draw so they don't snap to center.
            if (!medTrailInit) {
                for (i in 0 until medParticleCount) {
                    for (t in 0 until medTrailLen) {
                        medTrailX[i * medTrailLen + t] = cx
                        medTrailY[i * medTrailLen + t] = cy
                    }
                }
                medTrailInit = true
            }

            // Advance ring buffer head.
            medTrailHead = (medTrailHead + 1) % medTrailLen

            paint.style = Paint.Style.FILL
            for (i in 0 until medParticleCount) {
                val sample = measuredAudioAt(i.toFloat() / (medParticleCount - 1).coerceAtLeast(1), lowCut = 0.9f, highBoost = 1.2f)
                val angle = (i * 20f + frameCount * (0.14f + sample * 0.52f)) % 360f
                val dist = maxR * (0.14f + sample * 0.70f) + breath * maxR * 0.05f
                val rad = Math.toRadians(angle.toDouble())
                val px = cx + dist * kotlin.math.cos(rad).toFloat()
                val py = cy + dist * kotlin.math.sin(rad).toFloat()

                // Store new position into ring-buffer head slot.
                medTrailX[i * medTrailLen + medTrailHead] = px
                medTrailY[i * medTrailLen + medTrailHead] = py

                val col = particleColors[i % particleColors.size]

                // Draw fading trail from oldest (tail) to newest (head).
                for (t in 0 until medTrailLen) {
                    val slot = (medTrailHead - t + medTrailLen) % medTrailLen
                    val tx = medTrailX[i * medTrailLen + slot]
                    val ty = medTrailY[i * medTrailLen + slot]
                    val ageFactor = 1f - t.toFloat() / medTrailLen
                    val size = (1.2f + sample * 5.8f) * ageFactor
                    if (size < 0.4f) continue
                    paint.color = col
                    paint.alpha = ((35 + sample * 140) * ageFactor * ageFactor).toInt().coerceIn(0, 200)
                    canvas.drawCircle(tx, ty, size, paint)
                }

                // Head particle: crisp core + soft glow halo
                val headSize = 2.2f + sample * 6.8f
                paint.color = col
                paint.alpha = (90 + sample * 160).toInt().coerceIn(70, 240)
                canvas.drawCircle(px, py, headSize, paint)
                paint.alpha = (paint.alpha * 0.28f).toInt().coerceIn(20, 100)
                canvas.drawCircle(px, py, headSize * 3.0f, paint)
            }

            // ════════════════════════════════════════════════════════════════
            //  6. CENTRAL ORB — multi-layer bloom aura
            // ════════════════════════════════════════════════════════════════
            paint.style = Paint.Style.FILL
            val orbR = 7f + breath * 4.5f + avgLevel * 12f
            // Outer haze — large soft violet bloom
            paint.color = Color.parseColor("#7B68EE")
            paint.alpha = (35 + slowBreath * 30 + bass * 60).toInt().coerceIn(30, 150)
            canvas.drawCircle(cx, cy, orbR * (5.0f + bass * 1.2f), paint)
            // Mid aura — plum
            paint.color = Color.parseColor("#DDA0DD")
            paint.alpha = (85 + breath * 50 + mid * 70).toInt().coerceIn(80, 220)
            canvas.drawCircle(cx, cy, orbR * (2.6f + bass * 0.45f), paint)
            // Soft gold inner ring for warmth
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f + avgLevel * 2.2f
            paint.color = Color.parseColor("#FFD700")
            paint.alpha = (90 + avgLevel * 120 + medBurstEnergy * 120).toInt().coerceIn(80, 230)
            canvas.drawCircle(cx, cy, orbR * (1.7f + mid * 0.35f), paint)
            // Crisp bright core
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#FFD9FF")
            paint.alpha = (180 + avgLevel * 55 + treble * 20).toInt().coerceIn(170, 255)
            canvas.drawCircle(cx, cy, orbR * (1f + treble * 0.14f), paint)

            // ════════════════════════════════════════════════════════════════
            //  7. GENTLE VIGNETTE — keeps edges soft so the viz feels calm
            // ════════════════════════════════════════════════════════════════
            paint.shader = android.graphics.RadialGradient(
                cx, cy, kotlin.math.max(w, h) * 0.62f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(110, 0, 0, 0)),
                floatArrayOf(0f, 0.60f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        /**
         * TRON (1982) Theme — Retro neon grid with light cycle wall made of
         * spectrum-analyzer bricks, Bit companion as tweeter visualizer, and
         * a Recognizer hovering in the background. Authentic 80s film aesthetic
         * with cyan/orange neon glow on pure black.
         */
        /**
         * TRON (1982) Theme — The Grid comes alive with music.
         *
         * Immersive neon world: perspective grid floor, two light cycles
         * racing and leaving spectrum-analyzer trail walls, a Recognizer
         * patrolling overhead with bass-reactive searchlight, Tron programs
         * throwing identity discs that orbit with treble energy, and Bit
         * companion morphing between YES/NO states.
         *
         * Every visual element is driven by audio frequency bands.
         * When silent, the Grid goes dark — only dim outlines remain.
         */
        private fun drawTron(canvas: Canvas, w: Float, h: Float) {
            val cx = w / 2f
            val cy = h / 2f
            val t = frameCount * 0.035f
            val bass = bandEnergy(0, 5)
            val lowMid = bandEnergy(6, 13)
            val highMid = bandEnergy(14, 22)
            val treble = bandEnergy(23, 31)
            val avg = bandEnergy(0, barCount - 1)
            val active = avg > 0.03f

            val cyan = Color.parseColor("#00DFFF")
            val cyanDim = Color.parseColor("#004466")
            val orange = Color.parseColor("#FF6A00")
            val orangeDim = Color.parseColor("#662A00")
            val white = Color.WHITE

            // ── Void background with subtle bass pulse ──
            paint.style = Paint.Style.FILL
            val bgPulse = if (active) (bass * 12f).toInt().coerceIn(0, 15) else 0
            paint.color = Color.rgb(bgPulse, bgPulse / 2, bgPulse)
            canvas.drawRect(0f, 0f, w, h, paint)

            // ── Horizon glow: warm cyan band on the horizon line that pulses
            //    with overall energy. Sells the "inside the Grid" feel.
            val horizon = h * 0.38f
            paint.shader = android.graphics.LinearGradient(
                0f, horizon - h * 0.10f, 0f, horizon + h * 0.02f,
                intArrayOf(Color.TRANSPARENT, Color.argb(24, 0, 190, 255), Color.argb(110, 0, 220, 255)),
                floatArrayOf(0f, 0.7f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, horizon - h * 0.12f, w, horizon + 2f, paint)
            paint.shader = null

            // Bright horizon rim line — bass-reactive.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f + bass * 1.8f
            paint.color = cyan
            paint.alpha = (180 + bass * 75).toInt().coerceIn(180, 255)
            paint.setShadowLayer(10f + bass * 8f, 0f, 0f, cyan)
            canvas.drawLine(0f, horizon, w, horizon, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // Distant city-skyline hint — thin vertical beams below horizon
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.8f
            for (s in 0 until 11) {
                val bx = (w * 0.08f) + s * (w * 0.084f)
                val bh = (h * 0.015f) + (((s * 37) % 5) / 4f) * h * 0.022f
                paint.color = cyan
                paint.alpha = (45 + bass * 80).toInt().coerceIn(45, 180)
                paint.setShadowLayer(2f, 0f, 0f, cyan)
                canvas.drawLine(bx, horizon, bx, horizon - bh, paint)
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── PERSPECTIVE GRID FLOOR ──
            // Grid lines pulse with bass — the floor itself becomes a visualizer
            val vanishX = cx
            paint.style = Paint.Style.STROKE

            // Horizontal grid lines with bass-reactive wave distortion
            val hLines = 16
            for (i in 1..hLines) {
                val prog = i.toFloat() / hLines
                val yBase = horizon + (h - horizon) * prog
                val squeeze = prog  // 0 at horizon, 1 at bottom
                val halfW = w * 0.58f * squeeze
                val leftX = vanishX - halfW
                val rightX = vanishX + halfW

                // Bass makes grid lines wave
                val wave = if (active) bass * 3f * kotlin.math.sin((prog * 8f + t * 1.2f).toDouble()).toFloat() else 0f
                paint.strokeWidth = 0.5f + prog * 0.8f
                paint.color = cyan
                paint.alpha = (10 + 50 * prog * (0.3f + avg * 0.7f)).toInt().coerceIn(0, 80)
                paint.setShadowLayer(2f * prog + bass * 3f * prog, 0f, 0f, cyanDim)
                canvas.drawLine(leftX, yBase + wave, rightX, yBase - wave * 0.5f, paint)
            }

            // Vertical converging lines — treble energy makes them brighter
            val vLines = 18
            for (i in -vLines / 2..vLines / 2) {
                val botX = cx + i * w * 0.072f
                val brightness = (1f - kotlin.math.abs(i.toFloat()) / (vLines / 2f))
                paint.strokeWidth = 0.4f + brightness * 0.4f
                paint.color = cyan
                paint.alpha = (8 + 25 * brightness * (0.2f + treble * 0.8f)).toInt().coerceIn(0, 55)
                paint.setShadowLayer(1f, 0f, 0f, cyanDim)
                canvas.drawLine(vanishX + i * 0.8f, horizon, botX, h, paint)
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── MCP CONE — rotating wall of colored blocks (Tron arcade game) ──
            // A conical shield of colored bricks that rotate slowly. Each brick
            // is an audio bin — lit bricks pulse with frequency energy, creating
            // a Breakout-style spectrum visualizer shaped like the MCP cone.
            val mcpCx = cx + w * 0.18f
            val mcpTopY = h * 0.02f
            val mcpBotY = h * 0.38f
            val mcpH = mcpBotY - mcpTopY
            val mcpRows = 10
            val mcpColsPerRow = 12
            val mcpRotation = t * 0.4f  // slow rotation

            // MCP face glow at apex (the humanoid face in the cone)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#FF2200")
            paint.alpha = (40 + avg * 80).toInt().coerceIn(40, 120)
            paint.setShadowLayer(12f + avg * 10f, 0f, 0f, Color.parseColor("#FF2200"))
            canvas.drawCircle(mcpCx, mcpTopY + mcpH * 0.08f, h * 0.035f + avg * h * 0.015f, paint)

            // Draw the cone blocks — rows get wider toward the bottom (cone shape)
            val mcpBlockColors = intArrayOf(
                Color.parseColor("#FF0040"), Color.parseColor("#FF6600"),
                Color.parseColor("#FFCC00"), Color.parseColor("#00FF66"),
                Color.parseColor("#00CCFF"), Color.parseColor("#6644FF"),
                Color.parseColor("#FF00CC"), Color.parseColor("#FF4400"),
                Color.parseColor("#44FFAA"), Color.parseColor("#FF8800")
            )

            for (row in 0 until mcpRows) {
                val rowProg = (row + 1f) / mcpRows
                val rowY = mcpTopY + mcpH * rowProg
                val rowHalfW = w * 0.04f + w * 0.22f * rowProg  // widens toward bottom
                val brickH = mcpH / mcpRows * 0.85f
                val brickW = (rowHalfW * 2f) / mcpColsPerRow * 0.9f

                for (col in 0 until mcpColsPerRow) {
                    // Rotate column index for spinning effect
                    val rotatedCol = ((col + (mcpRotation * mcpColsPerRow / (2f * Math.PI.toFloat())).toInt()) % mcpColsPerRow + mcpColsPerRow) % mcpColsPerRow
                    val colProg = (col.toFloat() / mcpColsPerRow) - 0.5f
                    val bx = mcpCx + colProg * rowHalfW * 2f

                    // Map to audio bin
                    val binIdx = ((row * mcpColsPerRow + rotatedCol) * barCount / (mcpRows * mcpColsPerRow)).coerceIn(0, barCount - 1)
                    val energy = barHeights[binIdx]

                    if (energy > 0.08f) {
                        // Lit block — color from palette, brightness from energy
                        paint.style = Paint.Style.FILL
                        paint.color = mcpBlockColors[row % mcpBlockColors.size]
                        paint.alpha = (80 + energy * 175).toInt().coerceIn(80, 255)
                        paint.setShadowLayer(2f + energy * 5f, 0f, 0f, mcpBlockColors[row % mcpBlockColors.size])
                        canvas.drawRect(bx, rowY - brickH, bx + brickW, rowY, paint)
                    } else {
                        // Dim outline block
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 0.4f
                        paint.color = Color.parseColor("#220808")
                        paint.alpha = 30
                        paint.setShadowLayer(0f, 0f, 0f, 0)
                        canvas.drawRect(bx, rowY - brickH, bx + brickW, rowY, paint)
                    }
                }
            }

            // Red glow base of MCP cone
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#FF2200")
            paint.alpha = (15 + bass * 30).toInt().coerceIn(15, 45)
            paint.setShadowLayer(8f, 0f, 0f, Color.parseColor("#FF2200"))
            canvas.drawRect(mcpCx - w * 0.24f, mcpBotY, mcpCx + w * 0.24f, mcpBotY + 2f, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ══════════════════════════════════════════════════════════════
            // RECOGNIZER — the U-shaped flying enforcer patrol craft
            // Scans the grid from above, bobbing and tracking to the music.
            // Horizontal position advances each frame; speed scales with
            // high-mid energy. Bass makes it bob deeper. Treble spikes fire
            // its downward scanning beam onto the grid floor.
            // ══════════════════════════════════════════════════════════════
            tronRecogX += 0.003f + highMid * 0.010f
            if (tronRecogX > 1.25f) tronRecogX = -0.25f
            val recogCx = w * tronRecogX
            val recogCy = h * 0.18f +
                kotlin.math.sin((t * 1.1f).toDouble()).toFloat() * h * 0.02f +
                bass * h * 0.018f
            val recogW = w * 0.22f
            val recogH = h * 0.075f
            val legW = recogW * 0.18f
            val legH = recogH * 0.90f
            val bridgeW = recogW * 0.56f
            val bridgeTopY = recogCy - recogH * 0.55f

            // Dark hull fill — left leg, right leg, cross-bar, raised bridge
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#08080C")
            paint.setShadowLayer(0f, 0f, 0f, 0)
            val recogLeft = android.graphics.Path()
            recogLeft.moveTo(recogCx - recogW * 0.5f, recogCy - recogH * 0.10f)
            recogLeft.lineTo(recogCx - recogW * 0.5f + legW, recogCy - recogH * 0.10f)
            recogLeft.lineTo(recogCx - recogW * 0.5f + legW, recogCy + legH * 0.55f)
            recogLeft.lineTo(recogCx - recogW * 0.5f, recogCy + legH * 0.55f)
            recogLeft.close()
            canvas.drawPath(recogLeft, paint)
            val recogRight = android.graphics.Path()
            recogRight.moveTo(recogCx + recogW * 0.5f - legW, recogCy - recogH * 0.10f)
            recogRight.lineTo(recogCx + recogW * 0.5f, recogCy - recogH * 0.10f)
            recogRight.lineTo(recogCx + recogW * 0.5f, recogCy + legH * 0.55f)
            recogRight.lineTo(recogCx + recogW * 0.5f - legW, recogCy + legH * 0.55f)
            recogRight.close()
            canvas.drawPath(recogRight, paint)
            // Cross-bar (bottom of the U)
            canvas.drawRect(
                recogCx - recogW * 0.5f, recogCy - recogH * 0.10f,
                recogCx + recogW * 0.5f, recogCy + recogH * 0.05f, paint
            )
            // Raised central command bridge
            canvas.drawRect(
                recogCx - bridgeW * 0.5f, bridgeTopY,
                recogCx + bridgeW * 0.5f, recogCy - recogH * 0.10f + 1f, paint
            )

            // Orange/red neon edge light — the iconic Recognizer glow
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.4f
            paint.color = orange
            paint.alpha = (200 + avg * 55).toInt().coerceIn(200, 255)
            paint.setShadowLayer(6f + bass * 4f, 0f, 0f, orange)
            canvas.drawPath(recogLeft, paint)
            canvas.drawPath(recogRight, paint)
            canvas.drawRect(
                recogCx - bridgeW * 0.5f, bridgeTopY,
                recogCx + bridgeW * 0.5f, recogCy - recogH * 0.10f + 1f, paint
            )

            // Vertical circuit stripes on each leg
            paint.strokeWidth = 0.8f
            paint.alpha = (140 + avg * 80).toInt().coerceIn(140, 230)
            for (s in 0 until 3) {
                val sxL = recogCx - recogW * 0.5f + legW * (0.3f + s * 0.2f)
                canvas.drawLine(sxL, recogCy - recogH * 0.05f, sxL, recogCy + legH * 0.50f, paint)
                val sxR = recogCx + recogW * 0.5f - legW + legW * (0.3f + s * 0.2f)
                canvas.drawLine(sxR, recogCy - recogH * 0.05f, sxR, recogCy + legH * 0.50f, paint)
            }

            // Central cyclops eye — bright scanning lamp
            paint.style = Paint.Style.FILL
            paint.color = white
            paint.alpha = (220 + treble * 35).toInt().coerceIn(220, 255)
            paint.setShadowLayer(12f + treble * 6f, 0f, 0f, orange)
            canvas.drawCircle(recogCx, recogCy - recogH * 0.04f,
                recogH * 0.10f + treble * 2f, paint)

            // Treble scan beam — downward cone when treble spikes
            if (treble > 0.30f) {
                paint.shader = android.graphics.LinearGradient(
                    recogCx, recogCy + recogH * 0.05f,
                    recogCx, horizon,
                    intArrayOf(
                        Color.argb((220 * treble).toInt().coerceIn(60, 220), 255, 180, 60),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 1f),
                    android.graphics.Shader.TileMode.CLAMP
                )
                paint.style = Paint.Style.FILL
                paint.alpha = 255
                val beam = android.graphics.Path()
                beam.moveTo(recogCx - recogH * 0.06f, recogCy + recogH * 0.05f)
                beam.lineTo(recogCx + recogH * 0.06f, recogCy + recogH * 0.05f)
                beam.lineTo(recogCx + recogW * 0.22f, horizon)
                beam.lineTo(recogCx - recogW * 0.22f, horizon)
                beam.close()
                canvas.drawPath(beam, paint)
                paint.shader = null
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ══════════════════════════════════════════════════════════════
            // TRON TANK — heavy blocky combat vehicle rolling the grid floor
            // Geometric stack: tread base + trapezoid hull + pyramid turret
            // + dual barrel. Rolls leftward; speed scales with low-mid
            // energy. Treads blink to bass. Cannon muzzle-flashes on treble.
            // ══════════════════════════════════════════════════════════════
            tronTankX -= 0.0025f + lowMid * 0.008f
            if (tronTankX < -0.25f) tronTankX = 1.25f
            val tankCx = w * tronTankX
            val tankCy = horizon + (h - horizon) * 0.42f
            val tankW = w * 0.16f
            val tankH = h * 0.08f

            // Soft ground shadow
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(130, 0, 4, 10)
            paint.setShadowLayer(0f, 0f, 0f, 0)
            canvas.drawOval(
                tankCx - tankW * 0.60f, tankCy + tankH * 0.30f,
                tankCx + tankW * 0.60f, tankCy + tankH * 0.48f, paint
            )

            // Tread base — long dark rectangle
            paint.color = Color.parseColor("#05060A")
            canvas.drawRect(
                tankCx - tankW * 0.52f, tankCy + tankH * 0.15f,
                tankCx + tankW * 0.52f, tankCy + tankH * 0.35f, paint
            )
            // Tread detail — vertical segment lines (pulse with bass)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.7f
            paint.color = cyan
            paint.alpha = (90 + bass * 100).toInt().coerceIn(90, 220)
            paint.setShadowLayer(3f + bass * 3f, 0f, 0f, cyan)
            for (seg in 0 until 9) {
                val sx = tankCx - tankW * 0.48f + seg * (tankW * 0.96f / 8f)
                canvas.drawLine(sx, tankCy + tankH * 0.17f, sx, tankCy + tankH * 0.33f, paint)
            }

            // Hull body — trapezoid
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#0A0B12")
            paint.setShadowLayer(0f, 0f, 0f, 0)
            val hull = android.graphics.Path()
            hull.moveTo(tankCx - tankW * 0.50f, tankCy + tankH * 0.15f)
            hull.lineTo(tankCx - tankW * 0.36f, tankCy - tankH * 0.10f)
            hull.lineTo(tankCx + tankW * 0.36f, tankCy - tankH * 0.10f)
            hull.lineTo(tankCx + tankW * 0.50f, tankCy + tankH * 0.15f)
            hull.close()
            canvas.drawPath(hull, paint)
            // Hull neon outline — pulses with low-mid
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.3f
            paint.color = cyan
            paint.alpha = (180 + lowMid * 75).toInt().coerceIn(180, 255)
            paint.setShadowLayer(5f + lowMid * 4f, 0f, 0f, cyan)
            canvas.drawPath(hull, paint)
            // Mid-hull circuit line
            canvas.drawLine(
                tankCx - tankW * 0.42f, tankCy + tankH * 0.02f,
                tankCx + tankW * 0.42f, tankCy + tankH * 0.02f, paint
            )

            // Pyramid turret
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#0B0C14")
            paint.setShadowLayer(0f, 0f, 0f, 0)
            val turret = android.graphics.Path()
            turret.moveTo(tankCx - tankW * 0.20f, tankCy - tankH * 0.10f)
            turret.lineTo(tankCx, tankCy - tankH * 0.42f)
            turret.lineTo(tankCx + tankW * 0.20f, tankCy - tankH * 0.10f)
            turret.close()
            canvas.drawPath(turret, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            paint.color = cyan
            paint.alpha = (190 + highMid * 65).toInt().coerceIn(190, 255)
            paint.setShadowLayer(5f + highMid * 3f, 0f, 0f, cyan)
            canvas.drawPath(turret, paint)

            // Cannon barrel — horizontal from turret, forward-facing (left, because tank rolls left)
            val barrelY = tankCy - tankH * 0.22f
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#0A0A12")
            paint.setShadowLayer(0f, 0f, 0f, 0)
            canvas.drawRect(
                tankCx - tankW * 0.55f, barrelY - tankH * 0.035f,
                tankCx + tankW * 0.06f, barrelY + tankH * 0.035f, paint
            )
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.0f
            paint.color = cyan
            paint.alpha = (180 + avg * 70).toInt().coerceIn(180, 250)
            paint.setShadowLayer(4f, 0f, 0f, cyan)
            canvas.drawRect(
                tankCx - tankW * 0.55f, barrelY - tankH * 0.035f,
                tankCx + tankW * 0.06f, barrelY + tankH * 0.035f, paint
            )

            // Cannon muzzle-flash on treble spikes
            if (treble > 0.35f) {
                paint.style = Paint.Style.FILL
                paint.color = white
                paint.alpha = (255 * (treble - 0.10f)).toInt().coerceIn(160, 255)
                paint.setShadowLayer(18f + treble * 10f, 0f, 0f, orange)
                canvas.drawCircle(
                    tankCx - tankW * 0.55f, barrelY,
                    tankH * 0.09f + treble * tankH * 0.06f, paint
                )
                // Short tracer streak
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 2.0f + treble * 2f
                paint.color = orange
                paint.alpha = (200 + treble * 55).toInt().coerceIn(200, 255)
                paint.setShadowLayer(8f, 0f, 0f, orange)
                canvas.drawLine(
                    tankCx - tankW * 0.56f, barrelY,
                    tankCx - tankW * 0.86f, barrelY - tankH * 0.05f, paint
                )
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── LIGHT CYCLES — two cycles racing, leaving spectrum walls ──

            // Cyan cycle (left side) — position scrolls with time
            val cycle1X = (w * 0.15f + ((t * 12f) % (w * 0.35f))).coerceIn(w * 0.05f, w * 0.48f)
            val cycle1Y = h * 0.78f
            val cycleH = h * 0.045f

            fun drawLightCycle(px: Float, py: Float, color: Int, dimColor: Int, facing: Float) {
                val cw = cycleH * 2.2f
                val ch = cycleH
                // Cycle body — sleek wedge
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#0A0A14")
                val cp = android.graphics.Path()
                cp.moveTo(px + cw * 0.6f * facing, py)
                cp.lineTo(px + cw * 0.1f * facing, py - ch * 0.7f)
                cp.lineTo(px - cw * 0.5f * facing, py - ch * 0.3f)
                cp.lineTo(px - cw * 0.5f * facing, py + ch * 0.15f)
                cp.close()
                canvas.drawPath(cp, paint)
                // Neon circuit lines on cycle
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f
                paint.color = color
                paint.alpha = (160 + avg * 80).toInt().coerceIn(160, 240)
                paint.setShadowLayer(5f, 0f, 0f, color)
                canvas.drawPath(cp, paint)
                // Wheel glow
                paint.style = Paint.Style.FILL
                paint.color = color
                paint.alpha = (180 + avg * 75).toInt().coerceIn(180, 255)
                canvas.drawCircle(px + cw * 0.35f * facing, py, ch * 0.2f, paint)
                canvas.drawCircle(px - cw * 0.35f * facing, py, ch * 0.18f, paint)
                paint.setShadowLayer(0f, 0f, 0f, 0)
            }

            drawLightCycle(cycle1X, cycle1Y, cyan, cyanDim, 1f)

            // Orange cycle (right side, going opposite direction)
            val cycle2X = (w * 0.85f - ((t * 10f) % (w * 0.35f))).coerceIn(w * 0.52f, w * 0.95f)
            val cycle2Y = h * 0.82f
            drawLightCycle(cycle2X, cycle2Y, orange, orangeDim, -1f)

            // ══════════════════════════════════════════════════════════════
            // LIGHT-CYCLE LIGHT WALLS — true graphic-equalizer
            // ══════════════════════════════════════════════════════════════
            // Each wall is a real column-style EQ:
            //   • vertical column per frequency bin (not per-brick rows)
            //   • smooth neon gradient (wall color at base → white at peak)
            //   • sliding "peak cap" that falls back under gravity
            //   • mirrored reflection onto the grid floor
            //   • brighter floor trail line with a chasing bright spot
            // The effect is unmistakably a floating ribbon of light
            // streaming out of the light cycle — exactly like the film,
            // but now visibly "pumping" with the music.
            val wallCols = 24
            val trailH = h * 0.30f
            val trailBot = cycle1Y + cycleH * 0.15f
            val trailTop = trailBot - trailH
            val trailLeft = w * 0.02f
            val trailRight = cycle1X - cycleH

            // Lazily-init peak-hold arrays on first use
            if (tronPeakL.size != wallCols) {
                tronPeakL = FloatArray(wallCols)
                tronPeakR = FloatArray(wallCols)
            }

            fun drawEqColumn(
                bx: Float, bw: Float, baseY: Float, topY: Float,
                energy: Float, peak: Float, wallColor: Int, wallMid: Int,
                reflect: Boolean
            ) {
                val wallH = baseY - topY
                val colH = energy * wallH
                val cx = bx + bw * 0.5f
                // ─ Base dim silhouette (always visible)
                paint.style = Paint.Style.FILL
                paint.color = wallColor
                paint.alpha = 24
                paint.setShadowLayer(0f, 0f, 0f, 0)
                canvas.drawRect(bx + 0.3f, topY, bx + bw - 0.3f, baseY, paint)

                // ─ Lit column with vertical gradient
                if (colH > 0.6f) {
                    val topOfCol = baseY - colH
                    paint.shader = android.graphics.LinearGradient(
                        cx, baseY, cx, topOfCol,
                        intArrayOf(wallColor, wallMid, Color.WHITE),
                        floatArrayOf(0f, 0.65f, 1f),
                        android.graphics.Shader.TileMode.CLAMP
                    )
                    paint.alpha = (150 + energy * 105).toInt().coerceIn(150, 255)
                    paint.setShadowLayer(3f + energy * 6f, 0f, 0f, wallColor)
                    canvas.drawRect(bx + 0.3f, topOfCol, bx + bw - 0.3f, baseY, paint)
                    paint.shader = null
                }

                // ─ Peak cap (falls slowly)
                val peakY = baseY - peak * wallH
                if (peak > 0.03f) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.WHITE
                    paint.alpha = (170 + peak * 85).toInt().coerceIn(170, 255)
                    paint.setShadowLayer(5f, 0f, 0f, wallColor)
                    canvas.drawRect(bx + 0.3f, peakY - 1.4f, bx + bw - 0.3f, peakY + 0.4f, paint)
                }

                // ─ Reflection on the "floor" (vertical squash, fading)
                if (reflect && colH > 0.6f) {
                    val reflectH = colH * 0.45f
                    paint.shader = android.graphics.LinearGradient(
                        cx, baseY, cx, baseY + reflectH,
                        intArrayOf(wallColor, Color.TRANSPARENT),
                        floatArrayOf(0f, 1f),
                        android.graphics.Shader.TileMode.CLAMP
                    )
                    paint.alpha = (70 + energy * 70).toInt().coerceIn(70, 160)
                    paint.setShadowLayer(0f, 0f, 0f, 0)
                    canvas.drawRect(bx + 0.3f, baseY + 0.5f, bx + bw - 0.3f, baseY + reflectH, paint)
                    paint.shader = null
                }
            }

            // ─── Cyan wall behind the cyan cycle
            if (trailRight > trailLeft + 5f) {
                val colW = (trailRight - trailLeft) / wallCols
                for (col in 0 until wallCols) {
                    val binIdx = (col * barCount / wallCols).coerceIn(0, barCount - 1)
                    val energy = barHeights[binIdx]
                    // Peak-hold: attack fast, decay slow (classic EQ behaviour)
                    tronPeakL[col] = if (energy > tronPeakL[col]) energy
                        else (tronPeakL[col] - 0.012f).coerceAtLeast(0f)
                    val bx = trailLeft + col * colW
                    drawEqColumn(
                        bx, colW, trailBot, trailTop,
                        energy, tronPeakL[col],
                        cyan, Color.parseColor("#55EEFF"),
                        reflect = true
                    )
                }
            }

            // ─── Orange wall behind the orange cycle
            val trailBot2 = cycle2Y + cycleH * 0.15f
            val trailTop2 = trailBot2 - trailH * 0.9f
            val trailLeft2 = cycle2X + cycleH
            val trailRight2 = w * 0.98f
            if (trailRight2 > trailLeft2 + 5f) {
                val colW2 = (trailRight2 - trailLeft2) / wallCols
                for (col in 0 until wallCols) {
                    val binIdx = ((wallCols - 1 - col) * barCount / wallCols).coerceIn(0, barCount - 1)
                    val energy = barHeights[binIdx]
                    tronPeakR[col] = if (energy > tronPeakR[col]) energy
                        else (tronPeakR[col] - 0.012f).coerceAtLeast(0f)
                    val bx = trailLeft2 + col * colW2
                    drawEqColumn(
                        bx, colW2, trailBot2, trailTop2,
                        energy, tronPeakR[col],
                        orange, Color.parseColor("#FFB855"),
                        reflect = true
                    )
                }
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)
            paint.shader = null

            // ─── Base floor trails — thin hot line with a chasing bright spot
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 2.2f
            paint.color = cyan
            paint.alpha = (140 + avg * 110).toInt().coerceIn(140, 250)
            paint.setShadowLayer(8f, 0f, 0f, cyan)
            canvas.drawLine(trailLeft, trailBot, cycle1X - cycleH * 0.5f, trailBot, paint)
            paint.color = orange
            paint.setShadowLayer(8f, 0f, 0f, orange)
            canvas.drawLine(cycle2X + cycleH * 0.5f, trailBot2, trailRight2, trailBot2, paint)

            // Chasing bright spot — runs along the trail in sync with the cycle
            paint.style = Paint.Style.FILL
            val chaseL = trailLeft + ((t * 60f) % (cycle1X - trailLeft).coerceAtLeast(1f))
            paint.color = white
            paint.alpha = (200 + avg * 55).toInt().coerceIn(200, 255)
            paint.setShadowLayer(10f, 0f, 0f, cyan)
            canvas.drawCircle(chaseL, trailBot, 2.2f + avg * 2f, paint)
            val chaseR = trailRight2 - ((t * 55f) % (trailRight2 - cycle2X).coerceAtLeast(1f))
            paint.setShadowLayer(10f, 0f, 0f, orange)
            canvas.drawCircle(chaseR, trailBot2, 2.2f + avg * 2f, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── TRON PROGRAMS with IDENTITY DISCS (throw & return) ──
            // Discs fly out toward the opponent and boomerang back.
            // Throw distance scales with energy; disc spins as it travels.

            fun drawProgram(px: Float, py: Float, color: Int, throwDir: Float,
                            throwPhase: Float, discEnergy: Float) {
                val u = h * 0.012f
                val headY = py - u * 8f
                // Helmet
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#0A0A14")
                canvas.drawCircle(px, headY, u * 2f, paint)
                // Visor glow
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f
                paint.color = color
                paint.alpha = (120 + discEnergy * 120).toInt().coerceIn(120, 240)
                paint.setShadowLayer(4f, 0f, 0f, color)
                canvas.drawArc(
                    px - u * 1.8f, headY - u * 0.8f,
                    px + u * 1.8f, headY + u * 1f,
                    200f, 140f, false, paint
                )
                // Body
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#080812")
                val body = android.graphics.Path()
                body.moveTo(px, headY + u * 1.5f)
                body.lineTo(px - u * 2.5f, py - u * 2f)
                body.lineTo(px - u * 2f, py + u * 2f)
                body.lineTo(px + u * 2f, py + u * 2f)
                body.lineTo(px + u * 2.5f, py - u * 2f)
                body.close()
                canvas.drawPath(body, paint)
                // Circuit lines
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.9f
                paint.color = color
                paint.alpha = (60 + discEnergy * 100).toInt().coerceIn(60, 180)
                paint.setShadowLayer(3f, 0f, 0f, color)
                canvas.drawPath(body, paint)
                canvas.drawLine(px, headY + u * 2f, px, py + u * 1f, paint)
                canvas.drawLine(px - u * 1.5f, py - u * 1.5f, px + u * 1.5f, py - u * 1.5f, paint)

                // ── Identity disc: throw and return boomerang ──
                // throwPhase cycles 0→2π. 0→π = flying out, π→2π = returning.
                // Use a sine curve so the disc smoothly arcs outward and back.
                val phase = throwPhase % (2f * Math.PI.toFloat())
                val outFraction = kotlin.math.sin(phase.toDouble()).toFloat().coerceIn(0f, 1f)
                // Max throw distance scales with energy
                val maxDist = w * 0.18f + discEnergy * w * 0.12f
                val throwDist = outFraction * maxDist
                // Disc arcs upward in parabola as it flies
                val arcHeight = outFraction * (1f - outFraction) * h * 0.15f

                val discX = px + throwDir * throwDist
                val discY = py - u * 4f - arcHeight
                val discR = u * 1.4f + discEnergy * u * 0.6f
                val discSpin = t * 12f  // fast spin

                // NOTE: Per design the Tron identity discs intentionally have
                // NO trailing light — only the crisp spinning ring itself. The
                // trail path that used to be drawn here has been removed.

                // ── Identity disc: crisp glowing ring, no trail ──
                // Pure Tron disc: dark metal rim, hot neon edge-light, dim
                // interior with a spinning cross-hair insignia.
                // Outer halo (contained, non-trailing)
                paint.style = Paint.Style.FILL
                paint.color = color
                paint.alpha = (35 + discEnergy * 55).toInt().coerceIn(35, 95)
                paint.setShadowLayer(discR * 2.2f, 0f, 0f, color)
                canvas.drawCircle(discX, discY, discR * 1.55f, paint)

                // Dark metal rim (outer bezel)
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#0B0D14")
                paint.alpha = 230
                paint.setShadowLayer(0f, 0f, 0f, 0)
                canvas.drawCircle(discX, discY, discR, paint)

                // Hot neon edge ring — this is the iconic bright circle
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = kotlin.math.max(1.2f, discR * 0.14f)
                paint.color = color
                paint.alpha = (220 + discEnergy * 35).toInt().coerceIn(220, 255)
                paint.setShadowLayer(8f + discEnergy * 6f, 0f, 0f, color)
                canvas.drawCircle(discX, discY, discR * 0.92f, paint)

                // Inner ring (dim highlight arc)
                paint.strokeWidth = 0.8f
                paint.color = color
                paint.alpha = (110 + discEnergy * 80).toInt().coerceIn(110, 200)
                paint.setShadowLayer(2.5f, 0f, 0f, color)
                canvas.drawCircle(discX, discY, discR * 0.58f, paint)

                // Spinning cross-hair insignia — keeps the disc feeling "alive"
                paint.strokeWidth = 0.8f
                paint.color = white
                paint.alpha = (150 + discEnergy * 80).toInt().coerceIn(150, 230)
                paint.setShadowLayer(3f, 0f, 0f, color)
                val cs = kotlin.math.cos(discSpin.toDouble()).toFloat()
                val sn = kotlin.math.sin(discSpin.toDouble()).toFloat()
                canvas.drawLine(discX - cs * discR * 0.55f, discY - sn * discR * 0.55f,
                    discX + cs * discR * 0.55f, discY + sn * discR * 0.55f, paint)
                canvas.drawLine(discX + sn * discR * 0.55f, discY - cs * discR * 0.55f,
                    discX - sn * discR * 0.55f, discY + cs * discR * 0.55f, paint)

                // Central bright pip
                paint.style = Paint.Style.FILL
                paint.color = white
                paint.alpha = (210 + discEnergy * 45).toInt().coerceIn(210, 255)
                paint.setShadowLayer(5f, 0f, 0f, color)
                canvas.drawCircle(discX, discY, discR * 0.11f, paint)

                paint.setShadowLayer(0f, 0f, 0f, 0)
            }

            // Cyan program — throws disc rightward toward orange
            val prog1Phase = t * 1.8f + highMid * 2f
            drawProgram(cx - w * 0.14f, h * 0.58f, cyan, 1f, prog1Phase, highMid)

            // Orange program — throws disc leftward toward cyan
            val prog2Phase = t * 1.5f + lowMid * 2f + Math.PI.toFloat() // offset so throws alternate
            drawProgram(cx + w * 0.14f, h * 0.60f, orange, -1f, prog2Phase, lowMid)

            // ── BIT COMPANION ──
            // Floats near top-left, morphs YES(spiky yellow)/NO(angular red)/neutral(cyan)
            val bitCx = w * 0.08f
            val bitCy = h * 0.20f
            val bitBaseR = h * 0.05f
            val bitR = bitBaseR * (0.7f + treble * 0.8f)
            val bitBob = kotlin.math.sin((t * 1.5f).toDouble()).toFloat() * h * 0.012f
            val bitSpin = t * 2.5f

            val bitColor = when {
                treble > 0.55f -> Color.parseColor("#FFEE00") // YES — excited
                treble < 0.12f -> Color.parseColor("#FF2200") // NO — quiet
                else -> cyan
            }
            val vertices = when {
                treble > 0.55f -> 12  // spiky star
                treble < 0.12f -> 4   // angular diamond
                else -> 8             // octagon
            }
            val spike = if (treble > 0.55f) 0.6f else if (treble < 0.12f) 0.3f else 0.1f

            // Glow
            paint.style = Paint.Style.FILL
            paint.color = bitColor
            paint.alpha = (20 + treble * 50).toInt().coerceIn(20, 70)
            paint.setShadowLayer(bitR * 1.8f, 0f, 0f, bitColor)
            canvas.drawCircle(bitCx, bitCy + bitBob, bitR * 1.8f, paint)

            // Body
            val bitPath = android.graphics.Path()
            for (v in 0 until vertices) {
                val angle = (v.toFloat() / vertices) * Math.PI.toFloat() * 2f + bitSpin
                val isOuter = v % 2 == 0
                val vr = if (isOuter) bitR * (1f + spike) else bitR * (0.6f + spike * 0.2f)
                val vx = bitCx + kotlin.math.cos(angle.toDouble()).toFloat() * vr
                val vy = bitCy + bitBob + kotlin.math.sin(angle.toDouble()).toFloat() * vr
                if (v == 0) bitPath.moveTo(vx, vy) else bitPath.lineTo(vx, vy)
            }
            bitPath.close()

            paint.style = Paint.Style.FILL
            paint.color = bitColor
            paint.alpha = (100 + treble * 140).toInt().coerceIn(100, 240)
            paint.setShadowLayer(5f, 0f, 0f, bitColor)
            canvas.drawPath(bitPath, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            paint.color = white
            paint.alpha = (160 + treble * 80).toInt().coerceIn(160, 240)
            canvas.drawPath(bitPath, paint)

            // Bit eye
            paint.style = Paint.Style.FILL
            paint.color = white
            paint.alpha = (200 + treble * 55).toInt().coerceIn(200, 255)
            canvas.drawCircle(bitCx, bitCy + bitBob, bitBaseR * 0.18f, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── CRT scanlines — subtle, never dominant ──
            // Evenly-spaced 2px dark bands across the whole frame, darkened
            // further by overall energy so the lines "breathe" with the track.
            paint.style = Paint.Style.FILL
            paint.shader = null
            val scanAlpha = (20 + avg * 18f).toInt().coerceIn(20, 48)
            paint.color = Color.argb(scanAlpha, 0, 0, 0)
            var sy = 0f
            while (sy < h) {
                canvas.drawRect(0f, sy, w, sy + 1f, paint)
                sy += 3f
            }

            // ── Corner vignette — gentle darkening toward edges ──
            paint.shader = android.graphics.RadialGradient(
                cx, cy, kotlin.math.max(w, h) * 0.62f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(120, 0, 0, 8)),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // ── HUD overlay ──
            paint.style = Paint.Style.FILL
            paint.typeface = android.graphics.Typeface.MONOSPACE
            paint.textSize = h * 0.028f
            paint.textAlign = Paint.Align.LEFT
            paint.color = cyan
            paint.alpha = (60 + avg * 80).toInt().coerceIn(60, 150)
            paint.setShadowLayer(4f, 0f, 0f, cyan)
            canvas.drawText("END OF LINE", 4f, h - 4f, paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.color = orange
            paint.alpha = (60 + avg * 80).toInt().coerceIn(60, 150)
            paint.setShadowLayer(4f, 0f, 0f, orange)
            canvas.drawText("GRID %04d".format((frameCount % 10000).toInt()), w - 4f, h - 4f, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)
            paint.typeface = android.graphics.Typeface.DEFAULT
        }

        private fun drawBreathe(canvas: Canvas, w: Float, h: Float) {
            val cx = w / 2f
            val cy = h / 2f
            val maxR = minOf(w, h) * 0.45f

            // Advance breathing timer
            val now = System.currentTimeMillis()
            if (lastBreathFrameTime > 0) {
                breathCycleMs = (breathCycleMs + (now - lastBreathFrameTime)) % breathTotalMs
            }
            lastBreathFrameTime = now

            // Determine phase and progress (0..1) within that phase
            val elapsed = breathCycleMs
            val (phase, progress) = when {
                elapsed < breathInhaleMs -> "INHALE" to (elapsed.toFloat() / breathInhaleMs)
                elapsed < breathInhaleMs + breathHoldMs -> "HOLD" to ((elapsed - breathInhaleMs).toFloat() / breathHoldMs)
                elapsed < breathInhaleMs + breathHoldMs + breathExhaleMs -> "EXHALE" to ((elapsed - breathInhaleMs - breathHoldMs).toFloat() / breathExhaleMs)
                else -> "REST" to ((elapsed - breathInhaleMs - breathHoldMs - breathExhaleMs).toFloat() / breathPauseMs)
            }

            // Circle size based on breath phase — expands on inhale, shrinks on exhale
            val breathSize = when (phase) {
                "INHALE" -> 0.3f + progress * 0.7f       // grows 0.3 → 1.0
                "HOLD"   -> 1.0f                          // full
                "EXHALE" -> 1.0f - progress * 0.7f        // shrinks 1.0 → 0.3
                else     -> 0.3f                           // resting small
            }

            // Use average audio level from barHeights to tint the color
            val avgLevel = barHeights.average().toFloat().coerceIn(0f, 1f)

            // Color palette: quiet → deep teal, loud → warm amber/coral
            val quietColor = Color.parseColor("#2E8B8B")   // teal
            val midColor = Color.parseColor("#5B9EA6")      // lighter teal
            val warmColor = Color.parseColor("#E8A87C")     // peach
            val hotColor = Color.parseColor("#D4726A")       // coral
            val baseColor = when {
                avgLevel < 0.33f -> blendColors(quietColor, midColor, avgLevel / 0.33f)
                avgLevel < 0.66f -> blendColors(midColor, warmColor, (avgLevel - 0.33f) / 0.33f)
                else -> blendColors(warmColor, hotColor, (avgLevel - 0.66f) / 0.34f)
            }

            // Outer soft glow
            val radius = maxR * breathSize
            paint.style = Paint.Style.FILL
            paint.color = baseColor
            paint.alpha = (40 + avgLevel * 50).toInt()
            paint.setShadowLayer(8f, 0f, 0f, baseColor)
            canvas.drawCircle(cx, cy, radius * 1.4f, paint)

            // Main breathing circle
            paint.alpha = (85 + avgLevel * 85).toInt()
            paint.setShadowLayer(6f, 0f, 0f, baseColor)
            canvas.drawCircle(cx, cy, radius, paint)

            // Inner bright core
            val coreColor = blendColors(baseColor, Color.WHITE, 0.45f)
            paint.color = coreColor
            paint.alpha = (130 + avgLevel * 110).toInt().coerceAtMost(240)
            paint.setShadowLayer(4f, 0f, 0f, Color.WHITE)
            canvas.drawCircle(cx, cy, radius * 0.5f, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // Breathing guide text
            val label = when (phase) {
                "INHALE" -> "breathe in"
                "HOLD"   -> "hold"
                "EXHALE" -> "breathe out"
                else     -> "rest"
            }
            paint.color = Color.WHITE
            paint.alpha = 200
            paint.textSize = 14f * (w / 400f).coerceIn(0.8f, 1.5f)
            paint.textAlign = Paint.Align.CENTER
            paint.style = Paint.Style.FILL
            paint.setShadowLayer(4f, 0f, 0f, baseColor)
            canvas.drawText(label, cx, cy + radius + paint.textSize * 1.6f, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // Subtle ring pulse that follows the breath
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f + avgLevel * 2f
            paint.color = baseColor
            paint.alpha = (40 + breathSize * 60).toInt()
            canvas.drawCircle(cx, cy, radius * 1.15f, paint)
        }

        /**
         * Close Encounters of the Third Kind theme — Massive mothership
         * hovering overhead with individually-illuminated light panels that
         * flash in different colors, reacting to audio frequencies. Inspired
         * by the "dueling tones" climax at Devils Tower where the mothership
         * communicates through musical tones paired with colored lights.
         * Each panel is its own spectrum bin, flashing independently.
         */
        // Per-panel random state for Close Encounters (deterministic per panel index)
        private val cePanelHues = FloatArray(72) { (it * 137.508f) % 360f }
        private val cePanelPhases = FloatArray(72) { (it * 73.13f) % 1f }
        private val cePanelSpeeds = FloatArray(72) { 0.4f + (it * 31.37f % 1f) * 1.6f }

        private fun drawCloseEncounters(canvas: Canvas, w: Float, h: Float) {
            // ══════════════════════════════════════════════════════════════
            //  Close Encounters of the Third Kind — redesigned to match the
            //  iconic climax frame: massive saucer silhouette filling the
            //  top of the frame, blue star-dotted canopy above, bright
            //  red/orange core glowing at the ship's center, a horizontal
            //  band of rectangular window lights in the underside, and a
            //  rotating ring of rim lights along the lower edge that flash
            //  through the five CE3K tone colors in reaction to audio.
            //
            //  Five-note tone → color mapping follows Spielberg's canonical
            //  on-set assignment:  RE-RED, MI-YELLOW, DO↑-PURPLE,
            //                      DO↓-GREEN, SOL-ORANGE.
            //
            //  Below the ship: rocky canyon silhouette, crowd of scientist
            //  silhouettes watching from the landing pad, converging light
            //  strips on the ground, and tall flood towers on either side.
            val cx = w / 2f
            val t = frameCount * 0.018f
            val bassEnergy      = bandEnergy(0, 5)    // → drives the RED tone
            val lowMidEnergy    = bandEnergy(6, 11)   // → drives YELLOW
            val midEnergy       = bandEnergy(12, 18)  // → drives PURPLE
            val highMidEnergy   = bandEnergy(19, 24)  // → drives GREEN
            val trebleEnergy    = bandEnergy(25, 31)  // → drives ORANGE
            val avgLevel        = bandEnergy(0, barCount - 1)
            val active          = avgLevel > 0.03f

            // ── Sky gradient: deep indigo top, night-canyon browns at bottom ──
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    Color.parseColor("#0A0728"),
                    Color.parseColor("#05031A"),
                    Color.parseColor("#0A0608")
                ),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // ── Bright RED/ORANGE CORE glow (the ship's central sun) ──
            // This is the dominant light source in the reference image. It
            // spills across the entire upper half. Pulses with bass + overall.
            val coreX  = cx
            val coreY  = h * 0.30f
            val coreR  = h * (0.28f + bassEnergy * 0.06f + avgLevel * 0.04f)
            paint.shader = android.graphics.RadialGradient(
                coreX, coreY, coreR,
                intArrayOf(
                    Color.argb(240, 255, 240, 200),
                    Color.argb((210 + bassEnergy * 45).toInt().coerceAtMost(255), 255, 120, 40),
                    Color.argb(150, 200, 40, 20),
                    Color.argb(40, 80, 20, 40),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.18f, 0.45f, 0.72f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h * 0.70f, paint)
            paint.shader = null

            // ── Blue starry canopy/dome overhead (matches reference image) ──
            // A lattice of tiny blue dots forming an arch above the ship,
            // denser near the top-center, fading away at the edges.
            val canopyCx = cx
            val canopyCy = h * 0.05f
            val canopyRx = w * 0.70f
            val canopyRy = h * 0.42f
            for (s in 0 until 220) {
                // Deterministic pseudo-random positions
                val seed  = (s * 0.6180339f) % 1f
                val seed2 = ((s * 1.61803f) + 0.37f) % 1f
                val ang = (seed * Math.PI.toFloat())  // 0..π → upper arc
                val r = 0.55f + seed2 * 0.45f
                val px = canopyCx + kotlin.math.cos(ang.toDouble()).toFloat() * canopyRx * r * (1f - (seed * 0.1f))
                val py = canopyCy + kotlin.math.sin(ang.toDouble()).toFloat() * canopyRy * r
                if (py > h * 0.45f) continue  // keep stars above the ship
                // Twinkle — pace modulated by mid-highs
                val twPhase = (t * (0.8f + seed * 2.3f) + seed2 * 6.28f) % 6.2832f
                val tw = 0.35f + 0.65f * (0.5f + 0.5f * kotlin.math.sin(twPhase.toDouble()).toFloat())
                paint.style = Paint.Style.FILL
                paint.color = when (s % 6) {
                    0 -> Color.parseColor("#BFDFFF")
                    1 -> Color.parseColor("#80B4FF")
                    2 -> Color.parseColor("#A0C8FF")
                    3 -> Color.parseColor("#6096FF")
                    4 -> Color.WHITE
                    else -> Color.parseColor("#50A0FF")
                }
                paint.alpha = (60 + tw * 170 + highMidEnergy * 40).toInt().coerceIn(60, 230)
                paint.setShadowLayer(1.6f + tw * 1.8f, 0f, 0f, paint.color)
                canvas.drawCircle(px, py, 0.7f + tw * 1.2f + (if (s % 19 == 0) 1.2f else 0f), paint)
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── Rocky canyon silhouette on the ground (not Devils Tower) ──
            // Jagged mesa walls left and right, distant hills in the middle.
            val groundY = h * 0.72f
            val canyonPath = android.graphics.Path()
            canyonPath.moveTo(0f, h)
            canyonPath.lineTo(0f, groundY - h * 0.05f)
            canyonPath.lineTo(w * 0.05f, groundY - h * 0.09f)
            canyonPath.lineTo(w * 0.09f, groundY - h * 0.06f)
            canyonPath.lineTo(w * 0.14f, groundY - h * 0.11f)
            canyonPath.lineTo(w * 0.19f, groundY - h * 0.03f)
            canyonPath.lineTo(w * 0.24f, groundY - h * 0.05f)
            canyonPath.lineTo(w * 0.30f, groundY - h * 0.02f)
            canyonPath.lineTo(w * 0.42f, groundY - h * 0.01f)
            canyonPath.lineTo(w * 0.55f, groundY - h * 0.02f)
            canyonPath.lineTo(w * 0.70f, groundY - h * 0.01f)
            canyonPath.lineTo(w * 0.77f, groundY - h * 0.04f)
            canyonPath.lineTo(w * 0.82f, groundY - h * 0.02f)
            canyonPath.lineTo(w * 0.88f, groundY - h * 0.10f)
            canyonPath.lineTo(w * 0.93f, groundY - h * 0.06f)
            canyonPath.lineTo(w * 0.97f, groundY - h * 0.09f)
            canyonPath.lineTo(w, groundY - h * 0.05f)
            canyonPath.lineTo(w, h)
            canyonPath.close()
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#0A0806")
            paint.alpha = 255
            canvas.drawPath(canyonPath, paint)

            // Warm underbelly glow at the canyon rim (light from the ship
            // spilling down onto the rocks)
            paint.shader = android.graphics.LinearGradient(
                0f, groundY - h * 0.12f, 0f, groundY + h * 0.05f,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb((50 + avgLevel * 100).toInt().coerceIn(50, 160), 255, 120, 40),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, groundY - h * 0.12f, w, groundY + h * 0.05f, paint)
            paint.shader = null

            // ── Mothership silhouette (wide saucer bottom, dome on top) ──
            val shipBob = kotlin.math.sin((t * 0.7f).toDouble()).toFloat() * h * 0.005f
            val shipCx = cx
            val shipCy = h * 0.30f + shipBob
            val shipW = w * 0.92f
            val shipH = h * 0.26f
            val shipBottom = shipCy + shipH * 0.5f

            // Lower hull — wide flattened ellipse (silhouette)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#070710")
            val hullRect = android.graphics.RectF(
                shipCx - shipW / 2f, shipCy - shipH * 0.10f,
                shipCx + shipW / 2f, shipCy + shipH * 0.50f
            )
            canvas.drawOval(hullRect, paint)

            // Upper dome — dark semi-circular cap above hull
            paint.color = Color.parseColor("#060610")
            val domeRect = android.graphics.RectF(
                shipCx - shipW * 0.38f, shipCy - shipH * 0.90f,
                shipCx + shipW * 0.38f, shipCy + shipH * 0.05f
            )
            canvas.drawOval(domeRect, paint)

            // Thin rim highlight around the dome (cooler blue backlight)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.1f
            paint.color = Color.parseColor("#5080FF")
            paint.alpha = (60 + avgLevel * 80).toInt().coerceIn(60, 180)
            paint.setShadowLayer(5f, 0f, 0f, Color.parseColor("#6090FF"))
            canvas.drawOval(domeRect, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // Subtle rim highlight along the lower hull
            paint.strokeWidth = 1.0f
            paint.color = Color.parseColor("#FFB070")
            paint.alpha = (70 + avgLevel * 100).toInt().coerceIn(70, 220)
            paint.setShadowLayer(8f, 0f, 0f, Color.parseColor("#FF8040"))
            canvas.drawOval(hullRect, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── Horizontal WINDOW-LIGHT BAND (the bright strip of rectangles) ──
            // This is the signature horizontal band of glowing rectangles
            // across the underside of the ship in the reference image. Each
            // rectangle is one audio bin — they sequence left-to-right and
            // pulse with their bin's energy.
            val bandCy = shipCy + shipH * 0.18f
            val bandCount = 14
            val bandSpan = shipW * 0.68f
            val bandCellW = bandSpan / bandCount
            val bandH = shipH * 0.11f
            val bandStartX = shipCx - bandSpan / 2f
            for (b in 0 until bandCount) {
                val binIdx = (b * barCount / bandCount).coerceIn(0, barCount - 1)
                val energy = barHeights[binIdx]
                val bx = bandStartX + b * bandCellW
                val rect = android.graphics.RectF(
                    bx + bandCellW * 0.10f, bandCy - bandH * 0.5f,
                    bx + bandCellW * 0.90f, bandCy + bandH * 0.5f
                )
                // Dim base (always visible)
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#FFC060")
                paint.alpha = (110 + energy * 110).toInt().coerceIn(110, 230)
                paint.setShadowLayer(6f + energy * 8f, 0f, 0f, Color.parseColor("#FF7020"))
                canvas.drawRoundRect(rect, bandH * 0.15f, bandH * 0.15f, paint)
                // Lit overlay — white hot center when this bin fires
                if (energy > 0.12f) {
                    paint.color = Color.parseColor("#FFFFE0")
                    paint.alpha = (energy * 240).toInt().coerceIn(60, 240)
                    paint.setShadowLayer(5f, 0f, 0f, Color.parseColor("#FFE0A0"))
                    canvas.drawRoundRect(
                        rect.left + bandCellW * 0.08f, bandCy - bandH * 0.22f,
                        rect.right - bandCellW * 0.08f, bandCy + bandH * 0.22f,
                        bandH * 0.1f, bandH * 0.1f, paint
                    )
                }
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── ROTATING rim lights cycling through the 5 CE3K tone colors ──
            // A ring of ~36 round lights around the lower rim. The ring
            // spins continuously; lights shift through the 5-tone color
            // palette and flash brighter when their mapped tone fires.
            //
            // We identify which tone is currently dominant and make lights
            // at that color burn white-hot.
            val toneColors = intArrayOf(
                Color.parseColor("#FF2020"),  // RE  — RED
                Color.parseColor("#FFD60A"),  // MI  — YELLOW
                Color.parseColor("#A050FF"),  // DO↑ — PURPLE
                Color.parseColor("#22E655"),  // DO↓ — GREEN
                Color.parseColor("#FF7A1F")   // SOL — ORANGE
            )
            val toneNames = arrayOf("RE", "MI", "DO↑", "DO↓", "SOL")
            val toneEnergies = floatArrayOf(
                bassEnergy, lowMidEnergy, midEnergy, highMidEnergy, trebleEnergy
            )
            var brightestPanel = -1
            var brightestValue = 0f
            for (i in 0 until 5) {
                if (toneEnergies[i] > brightestValue) {
                    brightestValue = toneEnergies[i]; brightestPanel = i
                }
            }

            // Rotation speed scales with overall energy
            val rimCount = 40
            val rimRotation = t * (0.35f + avgLevel * 0.9f)
            val rimCx = shipCx
            val rimCy = shipCy + shipH * 0.40f
            val rimRx = shipW * 0.44f
            val rimRy = shipH * 0.22f
            paint.style = Paint.Style.FILL
            for (i in 0 until rimCount) {
                val ang = (i.toFloat() / rimCount) * 6.2832f + rimRotation
                val px = rimCx + kotlin.math.cos(ang.toDouble()).toFloat() * rimRx
                val py = rimCy + kotlin.math.sin(ang.toDouble()).toFloat() * rimRy
                // Only draw the front half (bottom arc facing us)
                if (kotlin.math.sin(ang.toDouble()).toFloat() < -0.05f) continue

                // Which tone owns this lamp at this moment? Cycles through
                // the 5 tones as the ring rotates.
                val slotPhase = ((i.toFloat() / rimCount) + t * 0.18f) % 1f
                val toneIdx = (slotPhase * 5f).toInt() % 5
                val en = toneEnergies[toneIdx]
                val col = toneColors[toneIdx]

                // Depth-based fade (lamps near the back are dimmer)
                val depth = (kotlin.math.sin(ang.toDouble()).toFloat() + 0.3f).coerceIn(0f, 1f)
                val r = 2.4f + depth * 2.0f + en * 2.0f
                paint.color = col
                paint.alpha = (130 + depth * 100 + en * 35).toInt().coerceIn(130, 255)
                paint.setShadowLayer(5f + en * 9f, 0f, 0f, col)
                canvas.drawCircle(px, py, r, paint)
                // White-hot center when the mapped tone fires
                if (en > 0.20f) {
                    paint.color = Color.WHITE
                    paint.alpha = (en * 255).toInt().coerceIn(0, 255)
                    paint.setShadowLayer(4f, 0f, 0f, col)
                    canvas.drawCircle(px, py, r * 0.45f, paint)
                }
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // Bright rim-line arc under the ship — gets brighter with any tone firing
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 1.6f + brightestValue * 2.5f
            paint.color = Color.parseColor("#FFE0A0")
            paint.alpha = (120 + avgLevel * 120).toInt().coerceIn(120, 240)
            paint.setShadowLayer(8f + avgLevel * 8f, 0f, 0f, Color.parseColor("#FF9050"))
            canvas.drawArc(
                android.graphics.RectF(
                    rimCx - rimRx, rimCy - rimRy,
                    rimCx + rimRx, rimCy + rimRy
                ),
                10f, 160f, false, paint
            )
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── Oil-refinery tiny twinkle lights peppered on the underside ──
            paint.style = Paint.Style.FILL
            for (i in 0 until 60) {
                val ang = (i * 137.508f + t * 3f) * (Math.PI.toFloat() / 180f)
                val rr = 0.35f + ((i * 17) % 5) / 5f * 0.55f
                val px = shipCx + kotlin.math.cos(ang.toDouble()).toFloat() * shipW * 0.38f * rr
                val py = shipCy + shipH * 0.25f + kotlin.math.sin(ang.toDouble()).toFloat() * shipH * 0.10f * rr
                val spd = cePanelSpeeds[i]
                val ph  = cePanelPhases[i]
                val tw  = (0.3f + 0.7f * ((kotlin.math.sin((t * spd * 3.3f + ph * 6.28f).toDouble()).toFloat() + 1f) / 2f))
                paint.color = if (i % 5 == 0) Color.parseColor("#FFE8A0") else Color.parseColor("#FFF4D0")
                paint.alpha = (40 + tw * 160 + avgLevel * 30).toInt().coerceIn(40, 230)
                paint.setShadowLayer(2f + tw * 2f, 0f, 0f, paint.color)
                canvas.drawCircle(px, py, 0.7f + tw * 0.8f, paint)
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── Ground: converging landing-strip lights ──────────────────
            // Thin perspective-converging stripes on the pad, plus strips
            // of inlaid lights along each side.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.2f
            paint.color = Color.parseColor("#C0D0FF")
            paint.alpha = 150
            paint.setShadowLayer(6f, 0f, 0f, Color.parseColor("#90B0FF"))
            // Two converging strips
            canvas.drawLine(w * 0.20f, h, w * 0.44f, groundY + h * 0.04f, paint)
            canvas.drawLine(w * 0.80f, h, w * 0.56f, groundY + h * 0.04f, paint)
            // Central strip
            paint.color = Color.parseColor("#FFE0A0")
            paint.alpha = (120 + avgLevel * 110).toInt().coerceIn(120, 240)
            paint.setShadowLayer(8f + avgLevel * 6f, 0f, 0f, Color.parseColor("#FF9050"))
            canvas.drawLine(cx, h, cx, groundY + h * 0.04f, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // Ground puddle of warm light directly under the ship
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.RadialGradient(
                cx, groundY + h * 0.01f, w * 0.45f,
                intArrayOf(
                    Color.argb((140 + avgLevel * 70).toInt().coerceAtMost(220), 255, 150, 60),
                    Color.argb(60, 200, 80, 30),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.45f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, groundY - h * 0.02f, w, h, paint)
            paint.shader = null

            // ── Flood light towers on each side ──────────────────────────
            fun drawFloodTower(tx: Float) {
                val topY = groundY - h * 0.18f
                val botY = h
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.4f
                paint.color = Color.parseColor("#1C1E28")
                paint.alpha = 220
                paint.setShadowLayer(0f, 0f, 0f, 0)
                canvas.drawLine(tx, botY, tx, topY, paint)
                // Cross brace
                canvas.drawLine(tx - w * 0.02f, groundY - h * 0.06f, tx + w * 0.02f, groundY - h * 0.10f, paint)
                canvas.drawLine(tx - w * 0.02f, groundY - h * 0.10f, tx + w * 0.02f, groundY - h * 0.06f, paint)
                // Top lamp bar — multiple lights
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#EEF4FF")
                paint.alpha = (180 + avgLevel * 75).toInt().coerceAtMost(255)
                paint.setShadowLayer(10f + avgLevel * 8f, 0f, 0f, Color.parseColor("#FFFFFF"))
                for (l in 0 until 4) {
                    val lx = tx - w * 0.018f + l * w * 0.012f
                    canvas.drawCircle(lx, topY, 2.0f + avgLevel * 1.2f, paint)
                }
                paint.setShadowLayer(0f, 0f, 0f, 0)
            }
            drawFloodTower(w * 0.08f)
            drawFloodTower(w * 0.92f)

            // ── Crowd of silhouetted scientists watching from the pad ────
            // Simple head+shoulders shapes spread along the ground line.
            val crowdY = h * 0.88f
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#020204")
            paint.alpha = 255
            val crowdPath = android.graphics.Path()
            for (p in 0 until 28) {
                val px = w * 0.12f + (p.toFloat() / 27f) * w * 0.76f + ((p * 31) % 7 - 3) * 2.5f
                val py = crowdY + ((p * 13) % 5) * 2f
                val bodyH = h * 0.08f + ((p * 19) % 6) * h * 0.005f
                val headR = h * 0.014f
                // Body as rounded rectangle
                crowdPath.addRoundRect(
                    android.graphics.RectF(
                        px - headR * 1.4f, py - bodyH,
                        px + headR * 1.4f, h
                    ),
                    headR * 0.6f, headR * 0.6f,
                    android.graphics.Path.Direction.CW
                )
                crowdPath.addCircle(px, py - bodyH - headR * 0.4f, headR, android.graphics.Path.Direction.CW)
            }
            canvas.drawPath(crowdPath, paint)

            // Edge light rimming silhouettes (backlight from the ship)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = Color.parseColor("#FF9040")
            paint.alpha = (90 + avgLevel * 110).toInt().coerceIn(90, 220)
            paint.setShadowLayer(3f, 0f, 0f, Color.parseColor("#FF6020"))
            canvas.drawPath(crowdPath, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── Rotating beacon scanning from ship center ───────────────
            val scanAngle = (t * 1.8f * 57.2958f)   // degrees
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 2.2f + bassEnergy * 2.5f
            val scanRect = android.graphics.RectF(
                coreX - coreR * 0.45f, coreY - coreR * 0.45f,
                coreX + coreR * 0.45f, coreY + coreR * 0.45f
            )
            for (beam in 0 until 4) {
                val ba = scanAngle * (if (beam % 2 == 0) 1f else -1f) + beam * 90f
                paint.color = Color.WHITE
                paint.alpha = (70 + avgLevel * 110).toInt().coerceIn(70, 210)
                paint.setShadowLayer(10f, 0f, 0f, Color.parseColor("#FFFFDD"))
                canvas.drawArc(scanRect, ba, 30f, false, paint)
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── Tone callout ─────────────────────────────────────────────
            if (brightestPanel >= 0 && brightestValue > 0.25f) {
                val name = toneNames[brightestPanel]
                val col  = toneColors[brightestPanel]
                paint.style = Paint.Style.FILL
                paint.typeface = android.graphics.Typeface.MONOSPACE
                paint.textSize = h * 0.058f
                paint.textAlign = Paint.Align.CENTER
                paint.color = col
                paint.alpha = (100 + brightestValue * 155).toInt().coerceIn(100, 255)
                paint.setShadowLayer(10f, 0f, 0f, col)
                canvas.drawText(name, shipCx, h * 0.66f, paint)
                paint.setShadowLayer(0f, 0f, 0f, 0)
                paint.textAlign = Paint.Align.LEFT
                paint.typeface = android.graphics.Typeface.DEFAULT
            }

            // ── HUD text ─────────────────────────────────────────────────
            paint.style = Paint.Style.FILL
            paint.typeface = android.graphics.Typeface.MONOSPACE
            paint.textSize = h * 0.030f
            paint.textAlign = Paint.Align.LEFT
            paint.color = Color.parseColor("#FFB040")
            paint.alpha = (75 + avgLevel * 60).toInt().coerceIn(75, 170)
            paint.setShadowLayer(4f, 0f, 0f, Color.parseColor("#FF7020"))
            canvas.drawText("CE3K  ·  TONE LINK", 6f, h * 0.045f, paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.color = Color.parseColor("#FFD860")
            canvas.drawText("SEQ %05d".format(frameCount % 100000), w - 6f, h * 0.045f, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.textAlign = Paint.Align.LEFT

            // Suppress unused-variable warning in release builds
            if (active && brightestPanel < -1) { }
            // Touch variables to keep the compiler quiet if sections are trimmed
            @Suppress("UNUSED_VARIABLE") val _sb = shipBob + shipBottom
        }

        /**
         * 3D Fractal Core — RayNeo-inspired holographic fractal visualizer.
         *
         * Inspired by the RayNeo X3 AR developer showcase aesthetic: deep-space
         * backdrop, crisp cyan/magenta/gold accents, and volumetric particles
         * that give the illusion of stereoscopic depth on the glasses.
         *
         * Composition:
         *   • Deep-space radial backdrop with 128 twinkling stars.
         *   • A rotating 3D Menger-sponge-style cube rendered via simple
         *     perspective projection of 8 vertices; edges glow with bass.
         *   • Inner "sub-cube" spins the opposite direction on mid energy.
         *   • 6-fold kaleidoscopic chaos-game IFS particle swarm feeds from
         *     `fractalIfsPts`; treble drives spray velocity.
         *   • 32-band radial spectrum ring orbits the cube.
         *   • AR-style scanlines + corner vignette finish the look.
         *
         * Audio mapping:
         *   - bass    → cube pulse / scale / edge glow brightness
         *   - lowMid  → outer frequency ring amplitude
         *   - mid     → cube rotation speed, sub-cube counter-rotation
         *   - highMid → kaleidoscope arm density
         *   - treble  → particle spray velocity + starfield twinkle
         */
        private fun drawFractal(canvas: Canvas, w: Float, h: Float) {
            val cx = w / 2f
            val cy = h / 2f
            val t = frameCount * 0.018f
            val bass       = bandEnergy(0, 5)
            val lowMid     = bandEnergy(6, 12)
            val mid        = bandEnergy(13, 20)
            val highMid    = bandEnergy(21, 26)
            val treble     = bandEnergy(27, 31)
            val avgLevel   = bandEnergy(0, barCount - 1)
            val active     = avgLevel > 0.03f

            // RayNeo palette.
            val colCyan     = Color.parseColor("#00DFFF")
            val colMagenta  = Color.parseColor("#FF40CC")
            val colGold     = Color.parseColor("#FFD860")
            val colLavender = Color.parseColor("#C8A2FF")

            // ── 1. Deep-space backdrop ───────────────────────────────────
            paint.reset()
            paint.isAntiAlias = true
            paint.shader = android.graphics.RadialGradient(
                cx, cy, kotlin.math.max(w, h) * 0.75f,
                intArrayOf(
                    Color.parseColor("#141030"),
                    Color.parseColor("#080620"),
                    Color.parseColor("#01000A")
                ),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // ── 2. Twinkling star field ─────────────────────────────────
            paint.style = Paint.Style.FILL
            for (i in 0 until 128) {
                val seed = fractalStarField[i]
                val sx = ((seed * 10007f) % 1f) * w
                val sy = ((seed * 2729f + i * 37.1f) % 1f) * h
                val phase = (t * (0.4f + seed * 1.3f) + seed * 6.28f) % 6.28318f
                val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * Math.sin(phase.toDouble()).toFloat())
                val r = 0.45f + seed * 1.4f + treble * 1.1f
                val a = (60 + twinkle * 180 + treble * 40).toInt().coerceIn(0, 255)
                paint.color = when (i % 5) {
                    0 -> colCyan
                    1 -> colLavender
                    2 -> colGold
                    3 -> Color.WHITE
                    else -> colMagenta
                }
                paint.alpha = a
                canvas.drawCircle(sx, sy, r, paint)
            }
            paint.alpha = 255

            // Occasional streak across the field driven by a sharp treble spike.
            if (treble > 0.55f && (frameCount % 3L) == 0L) {
                paint.color = Color.argb((180 * treble).toInt().coerceAtMost(220), 255, 255, 255)
                paint.strokeWidth = 1.3f
                val sx = (fractalStarField[(frameCount.toInt() % 128)]) * w
                val sy = (fractalStarField[((frameCount.toInt() + 41) % 128)]) * h * 0.6f
                canvas.drawLine(sx, sy, sx + 70f, sy + 18f, paint)
            }

            // ── 3. Central 3D rotating Menger-style cube ────────────────
            // Audio-reactive scale pulse
            val pulse = 1f + bass * 0.18f + lowMid * 0.05f
            val baseScale = (kotlin.math.min(w, h) * 0.17f) * pulse
            val rotX = (t * (0.45f + mid * 0.6f) + fractalRotSeed[0] * 0.2f).toDouble()
            val rotY = (t * (0.63f + mid * 0.4f) + fractalRotSeed[1] * 0.2f).toDouble()
            val rotZ = (t * 0.20f).toDouble()

            val cosX = Math.cos(rotX).toFloat(); val sinX = Math.sin(rotX).toFloat()
            val cosY = Math.cos(rotY).toFloat(); val sinY = Math.sin(rotY).toFloat()
            val cosZ = Math.cos(rotZ).toFloat(); val sinZ = Math.sin(rotZ).toFloat()

            // Project a 3D point (x,y,z ∈ [-1,1]) to 2D screen space.
            // Returns a FloatArray of [sx, sy, depth] — depth used for z-sort fade.
            fun project(x: Float, y: Float, z: Float, scale: Float): FloatArray {
                // Rotate around X
                var yy = y * cosX - z * sinX
                var zz = y * sinX + z * cosX
                // Rotate around Y
                var xx = x * cosY + zz * sinY
                zz = -x * sinY + zz * cosY
                // Rotate around Z
                val xr = xx * cosZ - yy * sinZ
                val yr = xx * sinZ + yy * cosZ
                // Simple perspective
                val persp = 2.2f / (2.2f + zz)
                return floatArrayOf(cx + xr * scale * persp, cy + yr * scale * persp, zz)
            }

            // Cube vertices
            val verts = arrayOf(
                floatArrayOf(-1f, -1f, -1f), floatArrayOf( 1f, -1f, -1f),
                floatArrayOf( 1f,  1f, -1f), floatArrayOf(-1f,  1f, -1f),
                floatArrayOf(-1f, -1f,  1f), floatArrayOf( 1f, -1f,  1f),
                floatArrayOf( 1f,  1f,  1f), floatArrayOf(-1f,  1f,  1f)
            )
            // 12 cube edges (as vertex index pairs)
            val edges = arrayOf(
                intArrayOf(0,1), intArrayOf(1,2), intArrayOf(2,3), intArrayOf(3,0),
                intArrayOf(4,5), intArrayOf(5,6), intArrayOf(6,7), intArrayOf(7,4),
                intArrayOf(0,4), intArrayOf(1,5), intArrayOf(2,6), intArrayOf(3,7)
            )

            val projected = Array(8) { project(verts[it][0], verts[it][1], verts[it][2], baseScale) }

            // Outer cube edges — cyan glow (depth-faded)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 2.2f + bass * 3.5f
            for (e in edges) {
                val a = projected[e[0]]
                val b = projected[e[1]]
                val avgZ = (a[2] + b[2]) * 0.5f
                // avgZ in roughly [-1..1] — closer (negative) edges are brighter
                val depthT = (1f - (avgZ + 1f) * 0.5f).coerceIn(0f, 1f)
                val alpha = (90 + depthT * 140 + bass * 40).toInt().coerceIn(0, 255)
                paint.color = colCyan
                paint.alpha = alpha
                paint.setShadowLayer(8f + bass * 10f, 0f, 0f, colCyan)
                canvas.drawLine(a[0], a[1], b[0], b[1], paint)
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // Vertex pips — magenta
            paint.style = Paint.Style.FILL
            for (p in projected) {
                val depthT = (1f - (p[2] + 1f) * 0.5f).coerceIn(0f, 1f)
                val r = 2.5f + depthT * 2.5f + bass * 3f
                paint.color = colMagenta
                paint.alpha = (140 + depthT * 115).toInt().coerceIn(0, 255)
                paint.setShadowLayer(6f, 0f, 0f, colMagenta)
                canvas.drawCircle(p[0], p[1], r, paint)
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── 4. Inner counter-rotating sub-cube (Menger recursion hint) ─
            val subCos = Math.cos(-rotX * 1.7).toFloat()
            val subSin = Math.sin(-rotX * 1.7).toFloat()
            val subCos2 = Math.cos(-rotY * 1.3).toFloat()
            val subSin2 = Math.sin(-rotY * 1.3).toFloat()
            val subScale = baseScale * (0.42f + mid * 0.10f)
            val subProj = Array(8) { i ->
                val x = verts[i][0]; val y = verts[i][1]; val z = verts[i][2]
                // Separate transform for counter-rotation
                var yy = y * subCos - z * subSin
                var zz = y * subSin + z * subCos
                var xx = x * subCos2 + zz * subSin2
                zz = -x * subSin2 + zz * subCos2
                val persp = 2.2f / (2.2f + zz)
                floatArrayOf(cx + xx * subScale * persp, cy + yy * subScale * persp, zz)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f + mid * 2.4f
            for (e in edges) {
                val a = subProj[e[0]]; val b = subProj[e[1]]
                val depthT = (1f - ((a[2] + b[2]) * 0.5f + 1f) * 0.5f).coerceIn(0f, 1f)
                paint.color = colGold
                paint.alpha = (80 + depthT * 130 + mid * 30).toInt().coerceIn(0, 255)
                paint.setShadowLayer(5f, 0f, 0f, colGold)
                canvas.drawLine(a[0], a[1], b[0], b[1], paint)
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── 5. Kaleidoscopic chaos-game IFS particle swarm ──────────
            // Lazily seed the chaos game trail on the first active frame.
            if (!fractalIfsInit) {
                for (i in fractalIfsPts.indices step 2) {
                    fractalIfsPts[i] = 0f
                    fractalIfsPts[i + 1] = 0f
                }
                fractalIfsInit = true
            }

            // Advance chaos-game points toward randomly chosen attractors,
            // modulated by audio. Each step: p = 0.5 * (p + attractor).
            val attractorCount = 5
            val attractorR = 0.82f + highMid * 0.35f
            val iterations = 22 + (treble * 40f).toInt()
            val ifsRotSpeed = t * (0.35f + treble * 0.9f)
            repeat(iterations) {
                val idx = (random.nextInt(fractalIfsPts.size / 2)) * 2
                val a = (random.nextInt(attractorCount)).toFloat() * (6.28318f / attractorCount) + ifsRotSpeed
                val ax = Math.cos(a.toDouble()).toFloat() * attractorR
                val ay = Math.sin(a.toDouble()).toFloat() * attractorR
                fractalIfsPts[idx]     = (fractalIfsPts[idx] + ax) * 0.5f
                fractalIfsPts[idx + 1] = (fractalIfsPts[idx + 1] + ay) * 0.5f
            }

            // Render with 6-fold kaleidoscopic symmetry
            val kaleidoArms = 6
            val swarmScale = baseScale * 2.4f
            paint.style = Paint.Style.FILL
            for (arm in 0 until kaleidoArms) {
                val rot = (arm * (6.28318f / kaleidoArms) + t * 0.22f).toDouble()
                val kCos = Math.cos(rot).toFloat()
                val kSin = Math.sin(rot).toFloat()
                for (i in fractalIfsPts.indices step 2) {
                    val px = fractalIfsPts[i]
                    val py = fractalIfsPts[i + 1]
                    val sx = cx + (px * kCos - py * kSin) * swarmScale
                    val sy = cy + (px * kSin + py * kCos) * swarmScale
                    val dist = kotlin.math.sqrt((px * px + py * py).toDouble()).toFloat()
                    // Color drift with distance
                    val c = when ((i / 2 + arm) % 4) {
                        0 -> colCyan
                        1 -> colMagenta
                        2 -> colGold
                        else -> colLavender
                    }
                    paint.color = c
                    paint.alpha = (90 + highMid * 100 + dist * 60).toInt().coerceIn(40, 230)
                    val pr = 1.2f + highMid * 1.8f + bass * 1.4f
                    canvas.drawCircle(sx, sy, pr, paint)
                }
            }

            // ── 6. Radial 32-band spectrum ring around the cube ─────────
            val ringInnerR = baseScale * 1.55f
            val ringOuterBase = baseScale * 1.70f
            val ringOuterAmp = baseScale * 0.95f
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            for (i in 0 until barCount) {
                val fraction = i.toFloat() / (barCount - 1).coerceAtLeast(1).toFloat()
                val ang = (fraction * 6.28318f + t * 0.12f).toDouble()
                val level = barHeights[i]
                val outerR = ringOuterBase + level * ringOuterAmp * (0.6f + lowMid * 0.8f)
                val x0 = cx + Math.cos(ang).toFloat() * ringInnerR
                val y0 = cy + Math.sin(ang).toFloat() * ringInnerR
                val x1 = cx + Math.cos(ang).toFloat() * outerR
                val y1 = cy + Math.sin(ang).toFloat() * outerR
                // Color ramp across the ring
                paint.color = when {
                    fraction < 0.25f -> colMagenta
                    fraction < 0.5f  -> colCyan
                    fraction < 0.75f -> colLavender
                    else             -> colGold
                }
                paint.alpha = (140 + level * 110).toInt().coerceIn(0, 255)
                paint.strokeWidth = 2.0f + level * 3.2f
                paint.setShadowLayer(4f + level * 6f, 0f, 0f, paint.color)
                canvas.drawLine(x0, y0, x1, y1, paint)
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // Outer ring stroke — thin circle guide
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.0f
            paint.color = colCyan
            paint.alpha = (40 + avgLevel * 70).toInt().coerceIn(0, 180)
            canvas.drawCircle(cx, cy, ringInnerR, paint)
            paint.alpha = (25 + avgLevel * 50).toInt().coerceIn(0, 140)
            canvas.drawCircle(cx, cy, ringOuterBase + ringOuterAmp * 0.6f, paint)

            // ── 7. Central beating core (bass-driven) ───────────────────
            paint.style = Paint.Style.FILL
            val coreR = baseScale * 0.14f + bass * baseScale * 0.22f
            paint.shader = android.graphics.RadialGradient(
                cx, cy, coreR * 2.2f,
                intArrayOf(
                    Color.argb((220 + bass * 35).toInt().coerceAtMost(255), 255, 255, 255),
                    colCyan,
                    Color.argb(0, 0, 223, 255)
                ),
                floatArrayOf(0f, 0.45f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, coreR * 2.2f, paint)
            paint.shader = null

            // ── 8. AR scanline overlay ──────────────────────────────────
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            val scanAlpha = (14 + avgLevel * 22).toInt().coerceIn(0, 60)
            paint.color = Color.argb(scanAlpha, 180, 220, 255)
            var y = 0f
            while (y < h) {
                canvas.drawLine(0f, y, w, y, paint)
                y += 3f
            }

            // Corner vignette
            paint.shader = android.graphics.RadialGradient(
                cx, cy, kotlin.math.max(w, h) * 0.55f,
                intArrayOf(Color.TRANSPARENT, Color.argb(140, 0, 0, 0)),
                floatArrayOf(0.65f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            paint.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // ── 9. HUD text ─────────────────────────────────────────────
            paint.style = Paint.Style.FILL
            paint.typeface = android.graphics.Typeface.MONOSPACE
            paint.textSize = h * 0.030f
            paint.textAlign = Paint.Align.LEFT
            paint.color = colCyan
            paint.alpha = (90 + avgLevel * 90).toInt().coerceIn(90, 220)
            paint.setShadowLayer(4f, 0f, 0f, colCyan)
            canvas.drawText("RAYNEO  ·  FRACTAL CORE", 6f, h * 0.045f, paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.color = colGold
            canvas.drawText("ψ %04d".format(frameCount % 10000), w - 6f, h * 0.045f, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.textAlign = Paint.Align.LEFT

            // Suppress unused-variable warning in release builds
            if (active && kaleidoArms < -1) { }
        }

        private fun blendColors(c1: Int, c2: Int, ratio: Float): Int {
            val inv = 1f - ratio
            val r = (Color.red(c1) * inv + Color.red(c2) * ratio).toInt()
            val g = (Color.green(c1) * inv + Color.green(c2) * ratio).toInt()
            val b = (Color.blue(c1) * inv + Color.blue(c2) * ratio).toInt()
            return Color.rgb(r, g, b)
        }

        // ════════════════════════════════════════════════════════════════════
        // Winamp Classic — bar-spectrum visualizer
        //
        // Inspired by Winamp 2's default bar spectrum analyzer: discrete
        // LED-style cells stacked vertically, each bar coloured by height
        // (green at the bottom → yellow in the middle → red at the top),
        // with a white peak cap that drops slowly after a short hold.
        // ════════════════════════════════════════════════════════════════════
        private fun drawWinampBars(canvas: Canvas, w: Float, h: Float) {
            // Black backdrop — authentic Winamp look.
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#050505")
            canvas.drawRect(0f, 0f, w, h, paint)

            val cols = barCount                           // 32 bars
            val cellsPerCol = 22                          // vertical LED count
            val cellGap = 1.0f
            val padX = w * 0.04f
            val padTop = h * 0.06f
            val padBot = h * 0.08f
            val areaW = w - padX * 2f
            val areaH = h - padTop - padBot
            val colGap = 2f
            val barW = (areaW - colGap * (cols - 1)) / cols
            val cellH = (areaH - cellGap * (cellsPerCol - 1)) / cellsPerCol

            // Colour ramp: green → chartreuse → yellow → orange → red.
            val cellColor = IntArray(cellsPerCol) { idx ->
                val t = idx.toFloat() / (cellsPerCol - 1)
                when {
                    t < 0.35f -> blendColors(
                        Color.parseColor("#00E24A"),
                        Color.parseColor("#B8F000"),
                        t / 0.35f
                    )
                    t < 0.70f -> blendColors(
                        Color.parseColor("#B8F000"),
                        Color.parseColor("#FFA200"),
                        (t - 0.35f) / 0.35f
                    )
                    else -> blendColors(
                        Color.parseColor("#FFA200"),
                        Color.parseColor("#FF2B2B"),
                        (t - 0.70f) / 0.30f
                    )
                }
            }

            for (c in 0 until cols) {
                val energy = barHeights[c].coerceIn(0f, 1f)
                val litCount = (energy * cellsPerCol).toInt().coerceIn(0, cellsPerCol)

                // Bar column x range
                val x0 = padX + c * (barW + colGap)
                val x1 = x0 + barW

                // Draw each LED cell bottom-up
                for (cell in 0 until litCount) {
                    val y1 = h - padBot - cell * (cellH + cellGap)
                    val y0 = y1 - cellH
                    // Flip index so bright red is on top regardless of draw order
                    paint.color = cellColor[cell]
                    canvas.drawRect(x0, y0, x1, y1, paint)
                }

                // Update / draw peak-hold cap
                val peak = winampBarPeak[c]
                val held = winampBarHold[c]
                val targetPeak = energy
                if (targetPeak > peak) {
                    winampBarPeak[c] = targetPeak
                    winampBarHold[c] = 12
                } else if (held > 0) {
                    winampBarHold[c] = held - 1
                } else {
                    winampBarPeak[c] = (peak - 0.012f).coerceAtLeast(0f)
                }
                val peakIdx = (winampBarPeak[c] * cellsPerCol).toInt().coerceIn(0, cellsPerCol - 1)
                if (winampBarPeak[c] > 0.04f && peakIdx >= litCount) {
                    val py1 = h - padBot - peakIdx * (cellH + cellGap)
                    val py0 = py1 - cellH
                    paint.color = Color.parseColor("#FFFFFF")
                    canvas.drawRect(x0, py0, x1, py1, paint)
                }
            }
        }

        // ════════════════════════════════════════════════════════════════════
        // Winamp Classic — oscilloscope visualizer
        //
        // The classic thin green scope line. A rolling buffer captures the
        // mid-frequency FFT energy frame by frame and the waveform is drawn
        // across the viewport, with a subtle glow beneath.
        // ════════════════════════════════════════════════════════════════════
        private fun drawWinampScope(canvas: Canvas, w: Float, h: Float) {
            // Pure black backdrop
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#030303")
            canvas.drawRect(0f, 0f, w, h, paint)

            // Build a pseudo-waveform sample from the current FFT: low
            // bands push the line up, high bands give it detail.
            val mid = bandEnergy(0, barCount - 1)
            val treble = bandEnergy(20, barCount - 1)
            val t = frameCount * 0.25f
            val jitter = (kotlin.math.sin(t.toDouble()) + 0.4 *
                kotlin.math.sin((t * 2.3).toDouble())).toFloat()
            val sample = ((mid * 1.3f - 0.35f) +
                (treble * 0.6f) * jitter).coerceIn(-1f, 1f)
            winampScope[winampScopeHead] = sample
            winampScopeHead = (winampScopeHead + 1) % winampScopeLen

            // Faint horizontal centerline
            paint.color = Color.argb(40, 0, 255, 80)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, paint)

            // Waveform path
            val centerY = h * 0.5f
            val amp = h * 0.38f
            val path = android.graphics.Path()
            for (i in 0 until winampScopeLen) {
                val readIdx = (winampScopeHead + i) % winampScopeLen
                val s = winampScope[readIdx]
                val x = w * i.toFloat() / (winampScopeLen - 1)
                val y = centerY - s * amp
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            // Soft glow beneath the line
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.argb(90, 0, 255, 80)
            paint.strokeWidth = 6f
            paint.setShadowLayer(10f, 0f, 0f, Color.argb(200, 0, 255, 80))
            canvas.drawPath(path, paint)

            // Crisp main line
            paint.setShadowLayer(0f, 0f, 0f, 0)
            paint.color = Color.parseColor("#00FF55")
            paint.strokeWidth = 2.2f
            canvas.drawPath(path, paint)
        }

        // ════════════════════════════════════════════════════════════════════
        // Winamp Classic — starfield visualizer (vis_nsfs vibe)
        //
        // A warp-speed starfield in 3D: each star has persistent (x, y, z)
        // and flies toward the camera each frame. Bass energy accelerates
        // the warp and pushes new stars to a farther depth on respawn,
        // giving the iconic "punch-through" pulse on beats.
        // ════════════════════════════════════════════════════════════════════
        private fun drawWinampStarfield(canvas: Canvas, w: Float, h: Float) {
            if (!winampStarsInit) {
                for (i in 0 until winampStarN) {
                    winampStarX[i] = (winampRand.nextFloat() * 2f - 1f) * 1.6f
                    winampStarY[i] = (winampRand.nextFloat() * 2f - 1f) * 1.6f
                    winampStarZ[i] = 0.05f + winampRand.nextFloat() * 1.95f
                }
                winampStarsInit = true
            }

            // Black space
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#000000")
            canvas.drawRect(0f, 0f, w, h, paint)

            val bass = bandEnergy(0, 5)
            val mid = bandEnergy(6, 18)
            val speed = 0.008f + bass * 0.060f + mid * 0.012f
            val cx = w * 0.5f
            val cy = h * 0.5f
            val focal = (kotlin.math.min(w, h) * 0.75f)

            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            for (i in 0 until winampStarN) {
                val oldZ = winampStarZ[i]
                var newZ = oldZ - speed
                if (newZ <= 0.01f) {
                    // Respawn far behind the camera
                    winampStarX[i] = (winampRand.nextFloat() * 2f - 1f) * 1.6f
                    winampStarY[i] = (winampRand.nextFloat() * 2f - 1f) * 1.6f
                    newZ = 2.0f
                }
                winampStarZ[i] = newZ

                val x = winampStarX[i]
                val y = winampStarY[i]

                // Screen projection
                val sxOld = cx + (x / oldZ) * focal
                val syOld = cy + (y / oldZ) * focal
                val sxNew = cx + (x / newZ) * focal
                val syNew = cy + (y / newZ) * focal

                if (sxNew < -4f || sxNew > w + 4f || syNew < -4f || syNew > h + 4f) continue

                val depth = (1f - (newZ / 2f)).coerceIn(0f, 1f)
                val brightness = (120 + (135 * depth)).toInt().coerceIn(0, 255)
                val thickness = (0.8f + depth * 2.8f)

                // Trail line from old to new position
                paint.strokeWidth = thickness
                paint.color = Color.argb(brightness, 255, 255, 255)
                canvas.drawLine(sxOld, syOld, sxNew, syNew, paint)

                // Bright point at the front of the streak
                if (depth > 0.55f) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb(255, 255, 255, 255)
                    canvas.drawCircle(sxNew, syNew, thickness * 0.5f, paint)
                    paint.style = Paint.Style.STROKE
                }
            }

            // Bass-driven radial flash when a beat hits
            if (bass > 0.55f) {
                paint.style = Paint.Style.FILL
                paint.shader = android.graphics.RadialGradient(
                    cx, cy, kotlin.math.min(w, h) * 0.45f,
                    intArrayOf(
                        Color.argb(((bass - 0.55f) * 300f).toInt().coerceIn(0, 200), 110, 180, 255),
                        Color.argb(0, 0, 0, 0)
                    ),
                    floatArrayOf(0f, 1f),
                    android.graphics.Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, paint)
                paint.shader = null
            }
        }
    }

    private fun setupFullScreenControlsUI() {
        // Container for controls (Bottom bar)
        fullScreenControlsContainer =
                FrameLayout(context).apply {
                    layoutParams =
                            FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                            FrameLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { gravity = Gravity.BOTTOM }
                    // No background - just floating buttons
                    setPadding(0, 0, 0, 0)
                    visibility = View.GONE // Hidden by default
                    isClickable = true // Consume clicks
                }

        // Media Controls Container (Center)
        fullScreenMediaControls =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams =
                            FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.WRAP_CONTENT,
                                            FrameLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { gravity = Gravity.CENTER }
                }
        fullScreenControlsContainer.addView(fullScreenMediaControls)

        // Exit Button (Right)
        btnFsExit =
                FontIconView(context).apply {
                    setText(R.string.fa_compress)
                    setTextColor(Color.WHITE)
                    textSize = 24f
                    setPadding(16, 16, 16, 16)
                    layoutParams =
                            FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.WRAP_CONTENT,
                                            FrameLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
                    setOnClickListener {
                        (context as? AppCompatActivity)?.onBackPressedDispatcher?.onBackPressed()
                    }
                }
        fullScreenControlsContainer.addView(btnFsExit)

        // Create Media Buttons (reusing logic from mask controls)
        btnFsPrevTrack =
                createMediaButton(R.string.fa_backward_step) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            """
                (function() {
                    var prevBtn = document.querySelector('.ytp-prev-button') ||
                                  document.querySelector('[aria-label*="previous" i]') ||
                                  document.querySelector('[title*="previous" i]') ||
                                  document.querySelector('button[data-testid="control-button-skip-back"]');
                    if (prevBtn) { prevBtn.click(); return; }
                    var media = document.querySelector('video, audio');
                    if (media) media.currentTime = 0;
                })();
                """.trimIndent(),
                            "(function(){ if(window.prevStation){ window.prevStation(); } })();"
                    )
                }

        btnFsPrev =
                createMediaButton(R.string.fa_backward) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            "document.querySelector('video, audio').currentTime -= 10;",
                            "(function(){ if(window.prevStation){ window.prevStation(); } })();"
                    )
                }

        // Single Play/Pause toggle button
        btnFsPlayPause =
                createMediaButton(R.string.fa_play) {
                    if (isFsPlaying) {
                        // Currently playing, so pause
                        DebugLog.d("FullscreenTouch", "Pause clicked, switching to play icon")
                        val targetWebView = getMediaControlWebView()
                        evaluateMediaControlCommand(
                                targetWebView,
                                "document.querySelector('video, audio').pause();",
                                "(function(){ if(window.tapRadioNativePausePlayback){ window.tapRadioNativePausePlayback(); return; } if(window.togglePlay){ window.togglePlay(); } })();"
                        )
                        btnFsPlayPause.setText(R.string.fa_play)
                        isFsPlaying = false
                    } else {
                        // Currently paused, so play
                        DebugLog.d("FullscreenTouch", "Play clicked, switching to pause icon")
                        val targetWebView = getMediaControlWebView()
                        evaluateMediaControlCommand(
                                targetWebView,
                                "document.querySelector('video, audio').play();",
                                "(function(){ if(window.tapRadioNativeResumePlayback){ window.tapRadioNativeResumePlayback(); return; } if(window.togglePlay){ window.togglePlay(); } })();"
                        )
                        btnFsPlayPause.setText(R.string.fa_pause)
                        isFsPlaying = true
                    }
                }

        // Sync initial state with actual media state
        if (isMediaPlaying) {
            btnFsPlayPause.setText(R.string.fa_pause)
            isFsPlaying = true
        }

        btnFsNext =
                createMediaButton(R.string.fa_forward) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            "document.querySelector('video, audio').currentTime += 10;",
                            "(function(){ if(window.nextStation){ window.nextStation(); } })();"
                    )
                }

        btnFsNextTrack =
                createMediaButton(R.string.fa_forward_step) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            """
                (function() {
                    var beforeUrl = window.location.href;
                    var forceReloadIfUrlAdvanced = function(delayMs) {
                        setTimeout(function() {
                            try {
                                var now = window.location.href;
                                if (now !== beforeUrl) {
                                    window.location.replace(now);
                                }
                            } catch(e) {}
                        }, delayMs);
                    };

                    // ── Strategy 1: Direct SPA navigation to Up Next video ──
                    var upLink =
                        document.querySelector('ytd-compact-autoplay-renderer a#thumbnail[href*="/watch"]') ||
                        document.querySelector('ytd-playlist-panel-video-renderer[selected] + ytd-playlist-panel-video-renderer a[href*="/watch"]') ||
                        document.querySelector('ytd-playlist-panel-video-renderer[selected] ~ ytd-playlist-panel-video-renderer a[href*="/watch"]') ||
                        document.querySelector('#related ytd-compact-video-renderer a#thumbnail[href*="/watch"]') ||
                        document.querySelector('ytd-compact-video-renderer a#thumbnail[href*="/watch"]') ||
                        document.querySelector('ytm-compact-autoplay-renderer a[href*="/watch"]');
                    if (upLink && upLink.href) {
                        window.location.href = upLink.href;
                        return;
                    }

                    // ── Strategy 2: Dispatch a real MouseEvent on .ytp-next-button ──
                    var nextBtn =
                        document.querySelector('.ytp-next-button:not([aria-disabled="true"])') ||
                        document.querySelector('.ytp-next-button') ||
                        document.querySelector('button[aria-label^="Next" i]') ||
                        document.querySelector('button[data-testid="control-button-skip-forward"]');
                    if (nextBtn) {
                        try {
                            nextBtn.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window}));
                        } catch(e) {
                            nextBtn.click();
                        }
                        forceReloadIfUrlAdvanced(1500);
                        return;
                    }

                    // ── Strategy 3: Fire 'ended' so YouTube's autoplay takes over ──
                    var media = document.querySelector('video, audio');
                    if (media && isFinite(media.duration)) {
                        try {
                            media.currentTime = Math.max(0, media.duration - 0.05);
                            media.dispatchEvent(new Event('ended', {bubbles:true}));
                        } catch(e) {}
                        forceReloadIfUrlAdvanced(2500);
                    }
                })();
                """.trimIndent(),
                            "(function(){ if(window.nextStation){ window.nextStation(); } })();"
                    )
                    // Same aggressive refresh schedule used by the masked-mode
                    // next-track button so the overlay title keeps pace with
                    // the YouTube SPA navigation.
                    scheduleTrackChangeRefresh()
                }

        fullScreenMediaControls.addView(btnFsPrevTrack)
        fullScreenMediaControls.addView(btnFsPrev)
        fullScreenMediaControls.addView(btnFsPlayPause)
        fullScreenMediaControls.addView(btnFsNext)
        fullScreenMediaControls.addView(btnFsNextTrack)
    }

    private fun createMediaButton(iconRes: Int, onClick: () -> Unit): FontIconView {
        return FontIconView(context).apply {
            setText(iconRes)
            setBackgroundResource(R.drawable.nav_button_background)
            setTextColor(Color.WHITE)
            alpha = 0.5f
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            layoutParams =
                    LinearLayout.LayoutParams(40, 40).apply {
                        leftMargin = 4
                        rightMargin = 4
                    }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onClick()
                scheduleMaskedNowPlayingRefresh()
            }
        }
    }

    fun updateMediaState(isPlaying: Boolean) {
        // DebugLog.d("MediaControls", "updateMediaState called: isPlaying=$isPlaying,
        // isScreenMasked=$isScreenMasked")

        // Ignore updates shortly after manual interaction to prevent race conditions
        if (SystemClock.uptimeMillis() - lastMediaInteractionTime < 500) {
            return
        }

        isMediaPlaying = isPlaying
        if (isPlaying) {
            lastMediaPlayingAt = SystemClock.uptimeMillis()
        }
        updatePlaybackWakeLocks()
        post {
            if (isPlaying) {
                // DebugLog.d("MediaControls", "Setting to playing state")
                btnMaskPlay.visibility = View.GONE
                btnMaskPause.visibility = View.VISIBLE
                maskMediaControlsContainer.visibility = View.VISIBLE
                // DebugLog.d("MediaControls", "Controls container visibility:
                // ${maskMediaControlsContainer.visibility}, parent:
                // ${maskMediaControlsContainer.parent}")
            } else {
                // DebugLog.d("MediaControls", "Setting to paused state")
                btnMaskPlay.visibility = View.VISIBLE
                btnMaskPause.visibility = View.GONE
                // Keep controls visible if we know media exists
                maskMediaControlsContainer.visibility = View.VISIBLE
                // DebugLog.d("MediaControls", "Controls container visibility:
                // ${maskMediaControlsContainer.visibility}")
            }

            refreshMaskedNowPlaying()

            // Update full screen controls as well
            if (::btnFsPlayPause.isInitialized && !suppressFullscreenMediaControls) {
                if (isPlaying) {
                    btnFsPlayPause.setText(R.string.fa_pause)
                    isFsPlaying = true
                } else {
                    btnFsPlayPause.setText(R.string.fa_play)
                    isFsPlaying = false
                }
            }
        }
    }

    fun isMediaPlaying(): Boolean {
        return isMediaPlaying
    }

    fun toggleMediaPlayback() {
        if (isMediaPlaying) {
            pauseMedia()
        } else {
            playMedia()
        }
    }

    private fun isTapRadioWebView(targetWebView: WebView): Boolean {
        val url = targetWebView.url.orEmpty()
        return url.contains("radio.html", ignoreCase = true) ||
            url.contains("podcasts.html", ignoreCase = true)
    }

    private fun anyTrackedMediaPlaying(): Boolean {
        return nativeTapRadioPlaying || mediaStateByWindowId.values.any { it }
    }

    fun setMediaStateForWebView(sourceWebView: WebView, isPlaying: Boolean) {
        handleMediaStateChanged(sourceWebView, isPlaying)
    }

    fun setNativeTapRadioPlaybackActive(isPlaying: Boolean) {
        nativeTapRadioPlaying = isPlaying
        if (isPlaying) {
            nativeTapRadioLastActiveAt = SystemClock.uptimeMillis()
            lastMediaPlayingAt = SystemClock.uptimeMillis()
        }
        updateMediaState(anyTrackedMediaPlaying())
        refreshMaskedNowPlaying()
    }

    private fun resolveHostingActivity(): MainActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is MainActivity) return current
            current = current.baseContext
        }
        return current as? MainActivity
    }

    private fun hasNativeTapRadioSession(): Boolean {
        return resolveHostingActivity()?.hasNativeRadioSession() == true
    }

    private fun shouldRouteMediaControlsToNativeTapRadio(): Boolean {
        if (!hasNativeTapRadioSession()) return false
        if (nativeTapRadioPlaying) return true
        val latestWebMediaAt = mediaLastPlayedAtByWindowId.values.maxOrNull() ?: 0L
        return nativeTapRadioLastActiveAt >= latestWebMediaAt
    }

    private fun dispatchPlayMediaCommand() {
        if (!mediaStateByWindowId.values.any { it } && shouldRouteMediaControlsToNativeTapRadio()) {
            resolveHostingActivity()?.resumeNativeRadioFromToolbar()
            updateMediaState(true)
            return
        }
        val targetWebView = getMediaControlWebView()
        evaluateMediaControlCommand(
                targetWebView,
                "var m = document.querySelector('video, audio'); if (m) m.play();",
                "(function(){ if(window.tapRadioNativeResumePlayback){ window.tapRadioNativeResumePlayback(); return; } if(window.togglePlay){ window.togglePlay(); } })();"
        )
        updateMediaState(true)
    }

    private fun dispatchPauseMediaCommand() {
        if (nativeTapRadioPlaying) {
            resolveHostingActivity()?.pauseNativeRadioFromToolbar()
            updateMediaState(false)
            return
        }
        val targetWebView = getMediaControlWebView()
        evaluateMediaControlCommand(
                targetWebView,
                "var m = document.querySelector('video, audio'); if (m) m.pause();",
                "(function(){ if(window.tapRadioNativePausePlayback){ window.tapRadioNativePausePlayback(); return; } if(window.togglePlay){ window.togglePlay(); } })();"
        )
        updateMediaState(false)
    }

    private fun evaluateMediaControlCommand(targetWebView: WebView, fallbackJs: String, tapRadioJs: String? = null) {
        val script = if (tapRadioJs != null && isTapRadioWebView(targetWebView)) tapRadioJs else fallbackJs
        targetWebView.evaluateJavascript(script, null)
    }

    fun playMedia() {
        dispatchPlayMediaCommand()
    }

    fun pauseMedia() {
        dispatchPauseMediaCommand()
    }

    fun hideMediaControls() {
        post { maskMediaControlsContainer.visibility = View.GONE }
    }

    fun setSuppressFullscreenMediaControls(suppress: Boolean) {
        suppressFullscreenMediaControls = suppress
        post {
            if (::fullScreenMediaControls.isInitialized) {
                fullScreenMediaControls.visibility = if (suppress) View.GONE else View.VISIBLE
            }
        }
    }

    private fun handleMediaStateChanged(sourceWebView: WebView, isPlaying: Boolean) {
        val windowId = windows.firstOrNull { it.webView == sourceWebView }?.id
        if (windowId == null) {
            if (isPlaying) {
                lastMediaPlayingAt = SystemClock.uptimeMillis()
            }
            updateMediaState(isPlaying || nativeTapRadioPlaying)
            return
        }

        mediaStateByWindowId[windowId] = isPlaying
        if (isPlaying) {
            mediaLastPlayedAtByWindowId[windowId] = SystemClock.uptimeMillis()
        }

        val anyPlaying = anyTrackedMediaPlaying()
        updateMediaState(anyPlaying)
        refreshMaskedNowPlaying()
    }

    private fun getMediaControlWebView(): WebView {
        val playingIds = mediaStateByWindowId.filterValues { it }.keys
        val targetId =
                if (playingIds.isNotEmpty()) {
                    playingIds.maxByOrNull { mediaLastPlayedAtByWindowId[it] ?: 0L }
                } else {
                    mediaLastPlayedAtByWindowId.keys.maxByOrNull {
                        mediaLastPlayedAtByWindowId[it] ?: 0L
                    }
                }

        return windows.firstOrNull { it.id == targetId }?.webView ?: webView
    }

    fun refreshMaskedNowPlaying() {
        if (!::maskNowPlayingText.isInitialized) return
        post {
            val label = resolveMaskedNowPlayingLabel()
            if (!isScreenMasked || label.isNullOrBlank()) {
                maskNowPlayingText.visibility = View.GONE
            } else {
                maskNowPlayingText.text = label
                maskNowPlayingText.visibility = View.VISIBLE
                maskNowPlayingText.bringToFront()
            }
        }
    }

    private fun scheduleMaskedNowPlayingRefresh() {
        val delays = longArrayOf(120L, 500L, 1200L)
        refreshMaskedNowPlaying()
        delays.forEach { delayMs ->
            postDelayed({ refreshMaskedNowPlaying() }, delayMs)
        }
    }

    /**
     * After a track skip (next/prev), YouTube SPA navigations take several
     * seconds to update document.title.  We probe at multiple intervals and
     * also pull the title directly from the DOM which updates faster.
     *
     * The cached DOM title is invalidated immediately so the overlay stops
     * showing the OLD video's name while the new page is loading — otherwise
     * getFreshMaskedDomTitle would keep returning the stale cache value for
     * up to maskedDomTitleFreshMs.
     */
    private fun scheduleTrackChangeRefresh() {
        // Immediately drop the cached title so the overlay can't keep
        // displaying the previous video's name during the SPA transition.
        lastMaskedDomTitle = null
        lastMaskedDomTitleUrl = null
        lastMaskedDomTitleAt = 0L
        refreshMaskedNowPlaying()

        val delays = longArrayOf(300L, 800L, 1500L, 2500L, 4000L, 6000L, 8500L, 12000L)
        delays.forEach { delayMs ->
            postDelayed({
                refreshMaskedNowPlayingFromJs()
                refreshMaskedNowPlaying()
            }, delayMs)
        }
    }

    fun scheduleMaskedTrackChangeRefresh() {
        scheduleTrackChangeRefresh()
    }


    private fun getFreshMaskedDomTitle(currentUrl: String? = null): String? {
        val title = lastMaskedDomTitle?.trim().orEmpty()
        if (title.isBlank()) return null
        if (SystemClock.uptimeMillis() - lastMaskedDomTitleAt > maskedDomTitleFreshMs) return null
        if (!currentUrl.isNullOrBlank()) {
            val cachedUrl = lastMaskedDomTitleUrl.orEmpty()
            val sameYoutubeFamily =
                (currentUrl.contains("youtube.com", ignoreCase = true) || currentUrl.contains("youtu.be", ignoreCase = true)) &&
                (cachedUrl.contains("youtube.com", ignoreCase = true) || cachedUrl.contains("youtu.be", ignoreCase = true))
            if (!sameYoutubeFamily) return null
        }
        return title
    }

    /**
     * Use JS to extract the video title directly from the DOM.  On YouTube
     * the element `yt-formatted-string.ytd-watch-metadata` or the
     * `<title>` tag updates before `WebView.getTitle()` reflects it.
     */
    private fun refreshMaskedNowPlayingFromJs() {
        if (!isScreenMasked || !::maskNowPlayingText.isInitialized) return
        val webView = try { getMediaControlWebView() } catch (_: Exception) { return }
        val url = webView.url.orEmpty()
        if (!url.contains("youtube.com", true) && !url.contains("youtu.be", true)) return
        webView.evaluateJavascript(
            """
            (function() {
                var el = document.querySelector('yt-formatted-string.style-scope.ytd-watch-metadata') ||
                         document.querySelector('#info-contents yt-formatted-string') ||
                         document.querySelector('h1.title yt-formatted-string') ||
                         document.querySelector('title');
                if (!el) return '';
                var t = (el.textContent || el.innerText || '').trim();
                t = t.replace(/ - YouTube${'$'}/, '').replace(/ - YouTube Music${'$'}/, '').trim();
                if (!t || /^youtube$/i.test(t) || /^youtube music$/i.test(t)) return '';
                return t;
            })();
            """.trimIndent()
        ) { result ->
            val title = result?.trim('"', ' ') ?: return@evaluateJavascript
            if (title.isNotBlank() && title != "null") {
                post {
                    if (isScreenMasked) {
                        lastMaskedDomTitle = title
                        lastMaskedDomTitleUrl = url
                        lastMaskedDomTitleAt = SystemClock.uptimeMillis()
                        maskNowPlayingText.text = title
                        maskNowPlayingText.visibility = View.VISIBLE
                        maskNowPlayingText.bringToFront()
                    }
                }
            }
        }
    }

    private fun resolveMaskedNowPlayingLabel(): String? {
        val prefs = context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
        val radioPlaying = prefs.getBoolean("tapradio_now_playing_active", false)
        val stationName = prefs.getString("tapradio_now_playing_name", "")?.trim().orEmpty()

        // During track transitions isMediaPlaying can briefly be false;
        // also check recency of last playback so we don't blank the label.
        val recentlyPlaying = isMediaPlaying ||
            (SystemClock.uptimeMillis() - lastMediaPlayingAt < 8000)
        val mediaWebView = try { getMediaControlWebView() } catch (_: Exception) { null }
        val currentUrl = mediaWebView?.url.orEmpty()
        val isYoutube = currentUrl.contains("youtube.com", ignoreCase = true) ||
            currentUrl.contains("youtu.be", ignoreCase = true)
        if (isYoutube) {
            getFreshMaskedDomTitle(currentUrl)?.let { return it }
        }
        val shouldPreferTapRadioLabel =
            stationName.isNotBlank() &&
                !isYoutube &&
                (radioPlaying || hasNativeTapRadioSession())
        if (shouldPreferTapRadioLabel) {
            return stationName
        }
        if (!recentlyPlaying || mediaWebView == null) return null
        if (isYoutube) {
            val rawTitle = mediaWebView.title?.trim().orEmpty()
            val cleaned = rawTitle
                .removeSuffix(" - YouTube")
                .removeSuffix(" - YouTube Music")
                .trim()
            return cleaned.takeIf {
                it.isNotBlank() &&
                    !it.equals("YouTube", ignoreCase = true) &&
                    !it.equals("YouTube Music", ignoreCase = true)
            }
        }
        if (shouldPreferTapRadioLabel) {
            return stationName
        }
        return getFreshMaskedDomTitle(currentUrl)
    }

    fun injectLocation(latitude: Double, longitude: Double) {
        val script =
                """
            (function() {
                // Store the position globally so it persists
                window.__injectedPosition = {
                    coords: {
                        latitude: $latitude,
                        longitude: $longitude,
                        accuracy: 5.0,
                        altitude: null,
                        altitudeAccuracy: null,
                        heading: null,
                        speed: null
                    },
                    timestamp: new Date().getTime()
                };

                // Initialize watcher registry if not present
                if (!window.__geoWatchers) window.__geoWatchers = {};
                if (!window.__geoNextWatchId) window.__geoNextWatchId = 1;

                // Notify all registered watchPosition callbacks with updated position
                var watchers = window.__geoWatchers;
                for (var id in watchers) {
                    if (watchers.hasOwnProperty(id) && typeof watchers[id] === 'function') {
                        try { watchers[id](window.__injectedPosition); } catch(e) {
                            console.warn('[TapLink] watcher ' + id + ' error:', e);
                        }
                    }
                }

                // Only set up the mock geolocation API once
                if (window.__geoMockInstalled) {
                    console.log("[TapLink] Location updated: " + $latitude + ", " + $longitude + " (watchers: " + Object.keys(watchers).length + ")");
                    return;
                }
                window.__geoMockInstalled = true;

                // 1. Mock Permissions API to always return 'granted'
                if (navigator.permissions) {
                    var originalQuery = navigator.permissions.query.bind(navigator.permissions);
                    navigator.permissions.query = function(parameters) {
                        if (parameters.name === 'geolocation') {
                            return Promise.resolve({ state: 'granted', onchange: null });
                        }
                        return originalQuery(parameters);
                    };
                }

                // 2. Override Geolocation API using defineProperty for robustness
                var mockGeolocation = {
                    getCurrentPosition: function(success, error, options) {
                        setTimeout(function() {
                            if (window.__injectedPosition) {
                                success(window.__injectedPosition);
                            } else if (error) {
                                error({code: 2, message: 'Position unavailable'});
                            }
                        }, 10);
                    },
                    watchPosition: function(success, error, options) {
                        var watchId = window.__geoNextWatchId++;
                        window.__geoWatchers[watchId] = success;
                        // Fire immediately with current position
                        setTimeout(function() {
                            if (window.__injectedPosition) {
                                success(window.__injectedPosition);
                            }
                        }, 10);
                        return watchId;
                    },
                    clearWatch: function(id) {
                        delete window.__geoWatchers[id];
                    }
                };

                try {
                    Object.defineProperty(navigator, 'geolocation', {
                        value: mockGeolocation,
                        writable: false,
                        configurable: true
                    });
                } catch (e) {
                    // Fallback if defineProperty fails
                    navigator.geolocation.getCurrentPosition = mockGeolocation.getCurrentPosition;
                    navigator.geolocation.watchPosition = mockGeolocation.watchPosition;
                    navigator.geolocation.clearWatch = mockGeolocation.clearWatch;
                }

                console.log("[TapLink] Location mock installed + injected: " + $latitude + ", " + $longitude);
            })();
        """.trimIndent()

        post { webView.evaluateJavascript(script, null) }
    }

    fun injectPageObservers(targetWebView: WebView) {
        val script =
                """
            (function() {
                if (window.__taplinkReportScroll) {
                    window.__taplinkReportScroll();
                    if (window.__taplinkWarmupScroll) {
                        window.__taplinkWarmupScroll();
                    }
                    return;
                }

                function initTaplinkObservers() {
                    if (window.__observersInjected) return;
                    if (!document.body) {
                        setTimeout(initTaplinkObservers, 50);
                        return;
                    }
                    window.__observersInjected = true;

                    // --- Media Listeners ---
                    function attachMediaListeners(media) {
                        if (media.__listenersAttached) return;
                        media.__listenersAttached = true;
                        
                        const updateState = () => {
                            const allMedia = document.querySelectorAll('video, audio');
                            let anyPlaying = false;
                            for(let i=0; i<allMedia.length; i++) {
                                if(!allMedia[i].paused && !allMedia[i].ended && allMedia[i].readyState > 2) {
                                    anyPlaying = true;
                                    break;
                                }
                            }
                            if (window.MediaInterface) {
                                 window.MediaInterface.onMediaStateChanged(anyPlaying);
                            }
                        };

                        media.addEventListener('play', updateState);
                        media.addEventListener('pause', updateState);
                        media.addEventListener('ended', updateState);
                    }

                    const existingMedia = document.querySelectorAll('video, audio');
                    existingMedia.forEach(attachMediaListeners);

                    // --- Scroll Detection ---
                    let lastScrollTime = 0;
                    let lastScanTime = 0;
                    let cachedScroller = null;
                    let rescanRequested = false;
                    const SCAN_INTERVAL_MS = 1200;
                    const SCROLL_MIN_SIZE = 80;
                    const trackedScrollers = typeof WeakSet !== 'undefined' ? new WeakSet() : new Set();

                    function isRootScrollable(el) {
                        if (!el) return false;
                        return (el.scrollHeight - el.clientHeight) > 1 || (el.scrollWidth - el.clientWidth) > 1;
                    }

                    function isScrollable(el) {
                        if (!el || el.nodeType !== 1 || !el.getBoundingClientRect) return false;
                        const style = window.getComputedStyle(el);
                        const overflowY = style.overflowY;
                        const overflowX = style.overflowX;
                        const scrollY = (overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay') &&
                            (el.scrollHeight - el.clientHeight) > 1;
                        const scrollX = (overflowX === 'auto' || overflowX === 'scroll' || overflowX === 'overlay') &&
                            (el.scrollWidth - el.clientWidth) > 1;
                        if (!(scrollY || scrollX)) return false;
                        return (el.clientHeight > SCROLL_MIN_SIZE || el.clientWidth > SCROLL_MIN_SIZE);
                    }

                    function ensureScrollListener(el) {
                        if (!el || trackedScrollers.has(el)) return;
                        trackedScrollers.add(el);
                        el.addEventListener('scroll', reportScroll, { passive: true });
                    }

                    function collectScrollableElements(root, out) {
                        if (!root || !root.querySelectorAll) return;
                        const elements = root.querySelectorAll('*');
                        for (let i = 0; i < elements.length; i++) {
                            const el = elements[i];
                            if (isScrollable(el)) out.push(el);
                            if (el.shadowRoot) {
                                collectScrollableElements(el.shadowRoot, out);
                            }
                        }
                    }

                    function pickBestScroller(candidates) {
                        let best = null;
                        let bestScore = -1;
                        for (let i = 0; i < candidates.length; i++) {
                            const el = candidates[i];
                            if (!el || !el.getBoundingClientRect) continue;
                            const rect = el.getBoundingClientRect();
                            const width = Math.max(0, Math.min(rect.width, window.innerWidth));
                            const height = Math.max(0, Math.min(rect.height, window.innerHeight));
                            const score = width * height;
                            if (score > bestScore) {
                                bestScore = score;
                                best = el;
                            }
                        }
                        return best;
                    }

                    function findScrollableElement(forceScan) {
                        const now = Date.now();
                        if (cachedScroller && cachedScroller.isConnected === false) {
                            cachedScroller = null;
                        }
                        if (cachedScroller && !isScrollable(cachedScroller) && !isRootScrollable(cachedScroller)) {
                            cachedScroller = null;
                        }

                        const shouldScan = forceScan || !cachedScroller || (now - lastScanTime) >= SCAN_INTERVAL_MS;
                        if (!shouldScan && cachedScroller) {
                            return cachedScroller;
                        }

                        const candidates = [];
                        const rootScroller = document.scrollingElement || document.documentElement || document.body;
                        if (rootScroller && isRootScrollable(rootScroller)) {
                            candidates.push(rootScroller);
                        }

                        collectScrollableElements(document, candidates);

                        const deduped = [];
                        const seen = new Set();
                        for (let i = 0; i < candidates.length; i++) {
                            const el = candidates[i];
                            if (el && !seen.has(el)) {
                                seen.add(el);
                                deduped.push(el);
                            }
                        }

                        for (let i = 0; i < deduped.length; i++) {
                            ensureScrollListener(deduped[i]);
                        }

                        cachedScroller = pickBestScroller(deduped);
                        lastScanTime = now;
                        rescanRequested = false;
                        return cachedScroller;
                    }

                    function pickScrollerFromEvent(event) {
                        if (!event) return null;
                        if (event.composedPath) {
                            const path = event.composedPath();
                            for (let i = 0; i < path.length; i++) {
                                const node = path[i];
                                if (node && node.nodeType === 1) {
                                    const el = node;
                                    if (isScrollable(el) || isRootScrollable(el)) {
                                        ensureScrollListener(el);
                                        return el;
                                    }
                                }
                            }
                        }

                        if (event.target && event.target.nodeType === 1) {
                            const tgt = event.target;
                            if (isScrollable(tgt) || isRootScrollable(tgt)) {
                                ensureScrollListener(tgt);
                                return tgt;
                            }
                        }

                        return null;
                    }

                    function reportScroll(event) {
                        const now = Date.now();
                        // Basic throttle/debounce
                        if (now - lastScrollTime < 16) return;
                        lastScrollTime = now;

                        let scroller = pickScrollerFromEvent(event);
                        if (!scroller) {
                            scroller = findScrollableElement(rescanRequested);
                        }

                        if (!scroller) return;

                        window.__taplinkScrollTarget = scroller;

                        const rootScroller = document.scrollingElement || document.documentElement || document.body;

                        // If it's the root, use window metrics
                        let range, extent, offset, hRange, hExtent, hOffset;

                        if (scroller === rootScroller || scroller === document.documentElement || scroller === document.body) {
                            const docEl = document.documentElement;
                            range = docEl.scrollHeight;
                            extent = window.innerHeight;
                            offset = window.scrollY;

                            hRange = docEl.scrollWidth;
                            hExtent = window.innerWidth;
                            hOffset = window.scrollX;
                        } else {
                            range = scroller.scrollHeight;
                            extent = scroller.clientHeight;
                            offset = scroller.scrollTop;

                            hRange = scroller.scrollWidth;
                            hExtent = scroller.clientWidth;
                            hOffset = scroller.scrollLeft;
                        }

                        if (window.MediaInterface) {
                            window.MediaInterface.updateScrollMetrics(
                                Math.round(hRange),
                                Math.round(hExtent),
                                Math.round(hOffset),
                                Math.round(range),
                                Math.round(extent),
                                Math.round(offset)
                            );
                        }
                    }

                    function warmupScrollReports() {
                        if (window.__taplinkWarmupActive) return;
                        window.__taplinkWarmupActive = true;
                        const delays = [0, 120, 300, 600, 1000, 1500, 2000];
                        for (let i = 0; i < delays.length; i++) {
                            const delay = delays[i];
                            setTimeout(function() {
                                rescanRequested = true;
                                reportScroll();
                                if (i === delays.length - 1) {
                                    window.__taplinkWarmupActive = false;
                                }
                            }, delay);
                        }
                    }

                    window.__taplinkReportScroll = reportScroll;
                    window.__taplinkWarmupScroll = warmupScrollReports;

                    let reportTimer = null;
                    function scheduleReport() {
                        if (reportTimer !== null) return;
                        reportTimer = setTimeout(function() {
                            reportTimer = null;
                            reportScroll();
                        }, 250);
                    }

                    // Global capture listeners for scrollable activity
                    window.addEventListener('scroll', scheduleReport, { capture: true, passive: true });
                    window.addEventListener('wheel', scheduleReport, { capture: true, passive: true });
                    window.addEventListener('touchmove', scheduleReport, { capture: true, passive: true });

                    // Also check on resize
                    window.addEventListener('resize', scheduleReport);
                    document.addEventListener('DOMContentLoaded', scheduleReport, { passive: true });

                    // --- Mutation Observer ---
                    const observer = new MutationObserver(function(mutations) {
                        mutations.forEach(function(mutation) {
                            mutation.addedNodes.forEach(function(node) {
                                if (node.nodeName === 'VIDEO' || node.nodeName === 'AUDIO') {
                                    attachMediaListeners(node);
                                } else if (node.querySelectorAll) {
                                    node.querySelectorAll('video, audio').forEach(attachMediaListeners);
                                }
                            });
                        });
                        // Also re-check scroll on mutations
                        rescanRequested = true;
                        scheduleReport();
                    });
                    
                    observer.observe(document.body, { childList: true, subtree: true });

                    // Immediate metrics after setup
                    reportScroll();
                    warmupScrollReports();
                }

                initTaplinkObservers();
            })();
        """.trimIndent()

        targetWebView.evaluateJavascript(script, null)
    }

    // ── Media file interception for TapInsight media player ──
    companion object {
        private val MEDIA_TEXT_EXTS = setOf("txt","md","log","csv","json","xml","html","htm","rtf","ini","cfg","conf","yaml","yml","toml")
        private val MEDIA_AUDIO_EXTS = setOf("mp3","wav","ogg","m4a","aac","flac","wma","opus")
        private val MEDIA_VIDEO_EXTS = setOf("mp4","webm","mkv","avi","mov","m4v","ogv","3gp")
    }

    private fun interceptMediaUrl(view: android.webkit.WebView, url: String): Boolean {
        // Never treat our own asset pages as "media" — they ARE the media
        // player UI. Navigating to /assets/library_local.html should load
        // the page, not open it in a text reader just because the URL ends
        // in .html.
        val lower = url.lowercase()
        if (lower.startsWith("file:///android_asset/") ||
            lower.startsWith("https://appassets.androidplatform.net/assets/") ||
            lower.startsWith("http://appassets.androidplatform.net/assets/")
        ) return false

        val path = url.split("?")[0].split("#")[0]
        val ext = path.substringAfterLast('.', "").lowercase()
        val mediaType = when {
            MEDIA_TEXT_EXTS.contains(ext) -> "text"
            MEDIA_AUDIO_EXTS.contains(ext) -> "audio"
            MEDIA_VIDEO_EXTS.contains(ext) -> "video"
            else -> return false
        }
        val title = try {
            java.net.URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
        } catch (_: Exception) { path.substringAfterLast('/') }
        val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val srtUrl = if (mediaType == "video") {
            java.net.URLEncoder.encode(path.substringBeforeLast('.') + ".srt", "UTF-8")
        } else ""

        // Read media player preferences from companion app settings
        val vcPrefs = context.getSharedPreferences("visionclaw_prefs", android.content.Context.MODE_PRIVATE)
        val voiceName = vcPrefs.getString("media_tts_voice", "") ?: ""
        val underline = vcPrefs.getBoolean("media_tts_underline", true)
        val mediaParams = buildString {
            if (voiceName.isNotBlank()) append("&voice=${java.net.URLEncoder.encode(voiceName, "UTF-8")}")
            if (!underline) append("&underline=false")
        }

        val playerUrl = "file:///android_asset/media_player.html" +
            "?url=$encodedUrl&type=$mediaType&title=$encodedTitle" +
            (if (srtUrl.isNotBlank()) "&srt=$srtUrl" else "") +
            mediaParams
        DebugLog.d("MediaPlayer", "DualWebView intercepted $mediaType ($ext): $url")
        view.loadUrl(playerUrl)
        return true
    }

    private class MediaInterface(
            private val parent: DualWebViewGroup,
            private val sourceWebView: WebView
    ) {
        @android.webkit.JavascriptInterface
        fun onMediaStateChanged(isPlaying: Boolean) {
            // Run on UI thread to update UI
            parent.post { parent.handleMediaStateChanged(sourceWebView, isPlaying) }
        }

        @android.webkit.JavascriptInterface
        fun updateScrollMetrics(
                rangeX: Int,
                extentX: Int,
                offsetX: Int,
                rangeY: Int,
                extentY: Int,
                offsetY: Int
        ) {
            parent.post {
                parent.updateExternalScrollMetrics(
                        rangeX,
                        extentX,
                        offsetX,
                        rangeY,
                        extentY,
                        offsetY
                )
            }
        }

        // ── Pending text for the pull-based media player approach ──
        @Volatile private var pendingReaderText: String? = null

        @android.webkit.JavascriptInterface
        fun getPendingText(): String {
            val text = pendingReaderText ?: ""
            pendingReaderText = null
            return text
        }

        @android.webkit.JavascriptInterface
        fun openTextReader(text: String, title: String) {
            android.util.Log.d("MediaInterface", "openTextReader: ${text.length} chars, title=$title")
            if (text.isBlank()) return
            pendingReaderText = text
            parent.post {
                val vcPrefs = parent.context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
                val voiceName = vcPrefs.getString("media_tts_voice", "") ?: ""
                val underline = vcPrefs.getBoolean("media_tts_underline", true)
                val encodedTitle = java.net.URLEncoder.encode(title.take(200), "UTF-8")
                val mediaParams = buildString {
                    if (voiceName.isNotBlank()) append("&voice=${java.net.URLEncoder.encode(voiceName, "UTF-8")}")
                    if (!underline) append("&underline=false")
                }
                val playerUrl = "file:///android_asset/media_player.html?type=text&title=$encodedTitle$mediaParams"
                sourceWebView.loadUrl(playerUrl)
            }
        }
    }
}
