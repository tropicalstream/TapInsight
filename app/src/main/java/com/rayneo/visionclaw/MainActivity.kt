package com.rayneo.visionclaw

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
// android.graphics.Bitmap import removed – no longer needed
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.DisplayMetrics
import android.util.Log
import android.util.Patterns
// Choreographer removed – no longer needed for mirror frame callback
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
// PixelCopy / SurfaceView mirroring removed – BinocularSbsLayout handles SBS
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.rayneo.visionclaw.core.audio.GeminiAudioPlayer
import com.rayneo.visionclaw.core.audio.TtsController
import com.rayneo.visionclaw.core.camera.FrameCaptureManager
import com.rayneo.visionclaw.core.input.RayNeoArdkTrackpadBridge
import com.rayneo.visionclaw.core.input.SpeechInputController
import com.rayneo.visionclaw.core.input.TrackpadGestureEngine
import com.rayneo.visionclaw.core.tools.ToolDispatcher
import com.rayneo.visionclaw.ui.MainPagerAdapter
import com.rayneo.visionclaw.ui.MainViewModel
import com.rayneo.visionclaw.ui.CustomKeyboardView
import com.rayneo.visionclaw.ui.VoiceOscilloscopeView
import com.rayneo.visionclaw.ui.panels.TrackpadPanel
import com.rayneo.visionclaw.ui.panels.chat.ChatPanelFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * MainActivity – entry point for AITap on RayNeo X3 Pro.
 *
 * Handles: • XR session / origin null-safety checks • Trackpad gesture engine wiring (short /
 * double tap + swipe) • Edge-zone panel switching (5% left/right edges with 20px center movement) •
 * Speech input & TTS audio output integration • Frame capture for vision-based queries • HUD panel
 * anchoring for the 6 000-nit MicroLED binocular display • API-key-required notification overlay •
 * ViewPager2 hosting Chat HUD (browser launches to original TAPLINKX3 activity)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AITap"
        private const val HUD_NOTIFICATION_DURATION_MS = 3_000L
        private const val CHAT_HUD_IDLE_TIMEOUT_MS = 60_000L
        /** Minimum center-movement distance (px) to trigger edge-zone panel switch. */
        private const val EDGE_CENTER_MOVEMENT_THRESHOLD_PX = 48f
        /** Screen edge zone percentage (5% from left or right edge). */
        private const val EDGE_ZONE_PERCENTAGE = 0.05f
        private const val TRACKPAD_EDGE_DEADZONE_PX = 32f
        private const val VOICE_ACTIVATE_BEEP_MS = 90
        private const val VOICE_TIMEOUT_BEEP_MS = 180
        private const val VOICE_LISTEN_START_DELAY_MS = 220L
        private const val VOICE_ACTIVATION_DEBOUNCE_MS = 400L
        private const val GEMINI_LIVE_IDLE_TIMEOUT_MS = 5_000L
        private const val GEMINI_LIVE_ACTIVITY_HEARTBEAT_MS = 250L
        private const val GEMINI_LIVE_CONNECT_TIMEOUT_MS = 15_000L
        private const val GEMINI_AUDIO_SAMPLE_RATE = 16_000
        private const val GEMINI_AUDIO_NON_SILENT_THRESHOLD = 120
        private const val MULTIMODAL_FRAME_INTERVAL_MS = 2_000L
        private const val CAMERA_IDLE_TIMEOUT_MS = 5_000L
        // AITap: always use Gemini Live directly for continuous camera + voice.
        // Native STT would kill the camera after each utterance.
        private const val USE_NATIVE_STT = false
        private const val OSCILLOSCOPE_USER_COLOR = 0xFFFF4B52.toInt()
        private const val OSCILLOSCOPE_MODEL_COLOR = 0xFF4AA6FF.toInt()
        private const val OSCILLOSCOPE_UI_THROTTLE_MS = 45L
        // MIRROR_FRAME_INTERVAL_NS removed – BinocularSbsLayout handles SBS
        private const val TAP_BROWSER_ACTIVITY_CLASS = "com.TapLinkX3.app.MainActivity"
        private const val EXTRA_BROWSER_INITIAL_URL = "tapclaw_initial_url"
        private const val EXTRA_RETURN_TO_CHAT_ON_DOUBLE_TAP = "tapclaw_return_to_chat_double_tap"
        private const val GENERIC_SCROLL_SCALE = 22f
        private const val LOCATION_MIN_TIME_MS = 2_000L
        private const val LOCATION_MIN_DISTANCE_METERS = 2f
    }

    private enum class GeminiLiveState {
        IDLE,
        LISTENING,
        THINKING,
        FOLLOW_UP
    }

    // ── ViewModel & gesture engine ───────────────────────────────────────
    private val viewModel: MainViewModel by viewModels()
    private val gestureEngine = TrackpadGestureEngine()

    // ── Fragment instances (retained across config changes via ViewPager2) ─
    private val chatFragment = ChatPanelFragment()

    // ── Views ────────────────────────────────────────────────────────────
    private var viewPager: ViewPager2? = null
    private var hudNotification: TextView? = null
    private var holdProgressBar: ProgressBar? = null
    private var listeningOverlay: FrameLayout? = null
    private var listeningTranscript: TextView? = null
    private var voiceOscilloscope: VoiceOscilloscopeView? = null
    private var customKeyboardView: CustomKeyboardView? = null
    private var activeTextInput: EditText? = null

    // Screen mirroring removed – BinocularSbsLayout handles SBS rendering

    private val uiHandler = Handler(Looper.getMainLooper())
    private val delayedVoiceStartRunnable = Runnable { startVoiceInputSession() }
    private val stopGeminiCaptureRunnable = Runnable { handleGeminiLiveIdleTimeout() }
    private val liveSetupTimeoutRunnable = Runnable {
        if (geminiLiveSession != null && !liveSessionReady) {
            handleGeminiVoiceFailure("Gemini Live connection timed out. Try again.")
        }
    }
    private val cameraIdleTimeoutRunnable = Runnable {
        if (!cameraCaptureActive) return@Runnable
        val recentVoiceActivity =
                (SystemClock.uptimeMillis() - lastUserSpeechActivityMs) < CAMERA_IDLE_TIMEOUT_MS
        if (isGeminiListeningOrThinking() || recentVoiceActivity) {
            scheduleCameraIdleTimeout()
        } else {
            stopCameraCapture()
            showHudNotification("Camera auto-off")
        }
    }
    private val hideHudNotificationRunnable = Runnable {
        hudNotification
                ?.animate()
                ?.alpha(0f)
                ?.setDuration(220)
                ?.withEndAction { hudNotification?.visibility = View.GONE }
                ?.start()
    }
    private val chatHudIdleRunnable = Runnable {
        val currentPanel = viewPager?.currentItem ?: return@Runnable
        if (currentPanel != MainViewModel.PANEL_CHAT) return@Runnable
        if (chatFragment.isHudModeEnabled()) return@Runnable
        if (isGeminiListeningOrThinking()) {
            scheduleChatHudIdleTimer()
            return@Runnable
        }
        chatFragment.setHudModeEnabled(true)
    }

    // ── Speech & Audio ───────────────────────────────────────────────────
    private var speechController: SpeechInputController? = null
    private var geminiLiveSession:
            com.rayneo.visionclaw.core.network.GeminiRouter.LiveSessionHandle? =
            null
    private var geminiAudioRecord: AudioRecord? = null
    private var geminiAudioThread: Thread? = null
    @Volatile private var geminiCaptureActive = false
    @Volatile private var liveSessionReady = false
    @Volatile private var liveSessionClosingByApp = false
    @Volatile private var liveState = GeminiLiveState.IDLE
    @Volatile private var awaitingServerTurnComplete = false
    private var latestLiveTranscript = ""
    private var latestLiveOutputTranscript = ""
    @Volatile private var lastLiveActivityHeartbeatMs = 0L
    @Volatile private var lastMultimodalFrameSentMs = 0L
    @Volatile private var lastUserSpeechActivityMs = 0L
    @Volatile private var forceDirectGeminiLive = true
    @Volatile private var lastVoiceActivationMs = 0L
    /** Monotonically increasing counter to detect stale WebSocket callbacks from old sessions. */
    @Volatile private var geminiSessionEpoch = 0L
    @Volatile private var nativeSttFallbackTriggered = false
    private lateinit var toolDispatcher: ToolDispatcher
    private var toolAssistEngine: com.rayneo.visionclaw.core.tools.ToolAssistEngine? = null
    private var companionServer: com.rayneo.visionclaw.core.config.CompanionServer? = null
    private lateinit var oauthManager: com.rayneo.visionclaw.core.network.GoogleOAuthManager

    private var lastKnownAiConnectionStatus = ChatPanelFragment.ConnectionStatus.IDLE
    private var sawNonSilentGeminiAudio = false
    private var loggedGeminiAudioProbe = false
    private var rayNeoMicRouteActive = false
    private var frameCapture: FrameCaptureManager? = null
    private var geminiAudioPlayer: GeminiAudioPlayer? = null
    private var ttsController: TtsController? = null
    private var toneGenerator: ToneGenerator? = null
    private var latestFrame: String? = null
    private var cameraCaptureActive = false
    private var coreEyeSurfaceReady = false
    private var pendingCameraStart = false
    @Volatile private var lastOscilloscopeUiUpdateMs = 0L

    // ── Edge-zone tracking ───────────────────────────────────────────────
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var isTrackingSwipe = false
    private var edgeZoneLeft = 0f
    private var edgeZoneRight = 0f
    private var initialPageSnapDone = false

    // ── Runtime permissions ───────────────────────────────────────────────
    private var micPermissionGranted = false
    private var cameraPermissionGranted = false
    private var locationPermissionGranted = false
    private var locationManager: LocationManager? = null
    private var locationTrackingActive = false
    private val locationListener =
            LocationListener { location -> publishDeviceLocationContext(location) }

    private val permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants
                ->
                micPermissionGranted =
                        grants[Manifest.permission.RECORD_AUDIO]
                                ?: (ContextCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED)
                cameraPermissionGranted =
                        grants[Manifest.permission.CAMERA]
                                ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                                        PackageManager.PERMISSION_GRANTED)
                val fineGranted =
                        grants[Manifest.permission.ACCESS_FINE_LOCATION]
                                ?: (ContextCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED)
                val coarseGranted =
                        grants[Manifest.permission.ACCESS_COARSE_LOCATION]
                                ?: (ContextCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED)
                locationPermissionGranted = fineGranted || coarseGranted
                Log.i(
                        TAG,
                        "Permissions — mic=$micPermissionGranted camera=$cameraPermissionGranted location=$locationPermissionGranted"
                )
                syncCameraToGeminiState(viewModel.voiceAssistantActive.value == true)
                if (locationPermissionGranted) {
                    startLocationTracking()
                } else {
                    stopLocationTracking()
                    viewModel.clearDeviceLocationContext()
                }
            }

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply {
            densityDpi = DisplayMetrics.DENSITY_MEDIUM
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        // Initialize Mercury SDK for binocular (both lenses) display — must be before super.
        runCatching { com.ffalcon.mercury.android.sdk.MercurySDK.init(application) }
        super.onCreate(savedInstanceState)

        // ── Immersive full-screen for AR HUD ─────────────────────────
        configureImmersiveDisplay()
        window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )

        setContentView(R.layout.activity_main)

        // ── Bind views (null-safe) ───────────────────────────────────
        viewPager = findViewById(R.id.view_pager)
        hudNotification = findViewById(R.id.hud_notification)
        holdProgressBar = findViewById(R.id.hold_progress)
        listeningOverlay = findViewById(R.id.listening_overlay)
        listeningTranscript = findViewById(R.id.listening_transcript)
        voiceOscilloscope = findViewById(R.id.voice_oscilloscope)
        customKeyboardView = findViewById(R.id.custom_keyboard_view)

        setupCustomKeyboard()

        // ── Initialize OAuth manager and API clients ─────────────────────
        val prefs = viewModel.preferences
        oauthManager = com.rayneo.visionclaw.core.network.GoogleOAuthManager(prefs)

        val calendarClient = com.rayneo.visionclaw.core.network.GoogleCalendarClient(
            apiKeyProvider = { prefs.calendarApiKey },
            accessTokenProvider = {
                kotlinx.coroutines.runBlocking { oauthManager.getValidAccessToken() }
            }
        )
        viewModel.setCalendarClient(calendarClient)

        val directionsClient = com.rayneo.visionclaw.core.network.GoogleDirectionsClient(
            apiKeyProvider = { prefs.googleMapsApiKey }
        )

        val tasksClient = com.rayneo.visionclaw.core.network.GoogleTasksClient(
            accessTokenProvider = {
                kotlinx.coroutines.runBlocking { oauthManager.getValidAccessToken() }
            }
        )
        viewModel.setTasksClient(tasksClient)

        val placesClient = com.rayneo.visionclaw.core.network.GooglePlacesClient(
            apiKeyProvider = { prefs.googleMapsApiKey }
        )

        val deviceLocationLambda: () -> com.rayneo.visionclaw.core.model.DeviceLocationContext? =
            { viewModel.getDeviceLocationContext() }

        toolDispatcher = ToolDispatcher(
            this, calendarClient, directionsClient, tasksClient,
            placesClient = placesClient,
            locationProvider = deviceLocationLambda
        )

        // ToolAssistEngine: client-side tool execution for native-audio model
        // which has unreliable function calling.
        toolAssistEngine = com.rayneo.visionclaw.core.tools.ToolAssistEngine(
            toolDispatcher = toolDispatcher,
            locationProvider = deviceLocationLambda
        )

        viewModel.setMultimodalCameraEnabled(false)
        viewModel.setMultimodalTextureReady(false)

        // Start companion config server so phone can configure AITap via WiFi
        val serverPort = viewModel.appConfig.debugServerSettings.port
        companionServer = com.rayneo.visionclaw.core.config.CompanionServer(
            this, serverPort, oauthManager,
            locationProvider = deviceLocationLambda
        )
        companionServer?.startServer()
        Log.d(TAG, "Companion config server available at http://<glasses-ip>:$serverPort")

        // ── Calculate edge zones + gesture side awareness ─────────────────
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        edgeZoneLeft = screenWidth * EDGE_ZONE_PERCENTAGE
        edgeZoneRight = screenWidth * (1f - EDGE_ZONE_PERCENTAGE)
        gestureEngine.setScreenSize(screenWidth, screenHeight)

        // ── XR Origin / session safety checks ────────────────────────
        initXrOriginSafe()

        // ── ViewPager setup ──────────────────────────────────────────
        setupViewPager()
        chatFragment.setCoreEyeSurfaceListener(
                object : ChatPanelFragment.CoreEyeSurfaceListener {
                    override fun onSurfaceAvailable() {
                        coreEyeSurfaceReady = true
                        viewModel.setMultimodalTextureReady(true)
                        syncCameraToGeminiState(viewModel.voiceAssistantActive.value == true)
                    }

                    override fun onSurfaceDestroyed() {
                        coreEyeSurfaceReady = false
                        viewModel.setMultimodalTextureReady(false)
                        if (cameraCaptureActive) {
                            stopCameraCapture()
                        }
                        pendingCameraStart =
                                cameraPermissionGranted && viewModel.voiceAssistantActive.value == true
                    }
                }
        )
        chatFragment.setCardActionListener(
                object : ChatPanelFragment.CardActionListener {
                    override fun onAssistantRequested() {
                        runOnUiThread { activateChatVoiceAssistant() }
                    }
                }
        )

        // ── Trackpad gesture engine ──────────────────────────────────
        setupGestureEngine()

        // ── Speech input controller ──────────────────────────────────
        if (USE_NATIVE_STT) {
            speechController =
                    SpeechInputController(
                            this,
                            object : SpeechInputController.Listener {
                                override fun onSpeechResult(text: String) {
                                    handleSpeechResult(text)
                                }

                                override fun onSpeechPartial(text: String) {
                                    handleSpeechPartial(text)
                                }

                                override fun onSpeechStatus(status: String) {
                                    runOnUiThread { updateListeningTranscript(status) }
                                }

                                override fun onSpeechError(errorCode: Int) {
                                    handleSpeechError(errorCode)
                                }
                            }
                    )
        }

        // ── Frame capture manager ────────────────────────────────────
        frameCapture = FrameCaptureManager(this)

        // ── TTS controller (triggered via voice command, not double-tap) ─
        geminiAudioPlayer = GeminiAudioPlayer(this)
        if (viewModel.preferences.ttsVolume <= 0f) {
            viewModel.preferences.ttsVolume = 0.80f
        }
        ttsController = TtsController(this, viewModel.preferences)
        toneGenerator =
                runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85) }.getOrNull()

        // ── Wire trackpad scroll → active panel onTrackpadScroll ─────
        gestureEngine.onScroll = { deltaX, deltaY ->
            runOnUiThread {
                if (customKeyboardView?.visibility == View.VISIBLE) {
                    customKeyboardView?.handleTrackpadSwipe(deltaX, deltaY)
                } else {
                    currentTrackpadPanel()?.onTrackpadPan(deltaX, deltaY)
                }
            }
        }

        // ── Observe ViewModel events ─────────────────────────────────
        observeViewModel()
        viewModel.refreshHudUpcomingCalendar(force = true)
        applyInitialPageSelection()
        viewPager?.post { syncCameraToGeminiState(viewModel.voiceAssistantActive.value == true) }

        // ── Request runtime permissions for mic + camera ─────────────
        requestRequiredPermissions()

        Log.i(TAG, "AITap MainActivity created successfully")
    }

    override fun onResume() {
        super.onResume()
        if (!initialPageSnapDone) {
            initialPageSnapDone = true
            applyInitialPageSelection()
        }
        viewModel.setMultimodalTextureReady(
                coreEyeSurfaceReady && chatFragment.isCoreEyeSurfaceReady()
        )
        syncCameraToGeminiState(viewModel.voiceAssistantActive.value == true)
        handlePanelChanged(viewPager?.currentItem ?: MainViewModel.PANEL_CHAT)
        if (locationPermissionGranted) {
            startLocationTracking()
        }
        viewModel.refreshHudUpcomingCalendar(force = false)
        exitTextInputMode()
    }

    override fun onPause() {
        uiHandler.removeCallbacks(delayedVoiceStartRunnable)
        uiHandler.removeCallbacks(stopGeminiCaptureRunnable)
        uiHandler.removeCallbacks(cameraIdleTimeoutRunnable)
        uiHandler.removeCallbacks(chatHudIdleRunnable)
        pendingCameraStart = false
        releaseGeminiAudioCapture(cancelOnly = true)
        geminiAudioPlayer?.stopAndFlush()
        hideCustomKeyboard(clearFocus = true)
        stopCameraCapture()
        stopLocationTracking()
        super.onPause()
    }

    override fun onDestroy() {
        companionServer?.stopServer()
        companionServer = null
        gestureEngine.release()
        chatFragment.setCoreEyeSurfaceListener(null)
        chatFragment.setCardActionListener(null)
        speechController?.destroy()
        releaseGeminiAudioCapture(cancelOnly = true)
        viewModel.setMultimodalTextureReady(false)
        viewModel.setMultimodalCameraEnabled(false)
        frameCapture?.shutdown()
        geminiAudioPlayer?.release()
        geminiAudioPlayer = null
        ttsController?.shutdown()
        toneGenerator?.release()
        toneGenerator = null
        viewPager = null
        hudNotification = null
        holdProgressBar = null
        listeningOverlay = null
        listeningTranscript = null
        voiceOscilloscope = null
        customKeyboardView = null
        activeTextInput = null
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.removeCallbacks(cameraIdleTimeoutRunnable)
        stopLocationTracking()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configureImmersiveDisplay()
        // Recalculate edge zones + gesture side awareness on config change
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        edgeZoneLeft = screenWidth * EDGE_ZONE_PERCENTAGE
        edgeZoneRight = screenWidth * (1f - EDGE_ZONE_PERCENTAGE)
        gestureEngine.setScreenSize(screenWidth, screenHeight)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Runtime Permissions
    // ══════════════════════════════════════════════════════════════════════

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionGranted = true
        } else {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionGranted = true
        } else {
            needed.add(Manifest.permission.CAMERA)
        }

        val fineGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
        val coarseGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
        locationPermissionGranted = fineGranted || coarseGranted
        if (!locationPermissionGranted) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            syncCameraToGeminiState(viewModel.voiceAssistantActive.value == true)
            startLocationTracking()
        }
    }

    private fun startLocationTracking() {
        if (locationTrackingActive || !locationPermissionGranted) return

        val manager =
                locationManager
                        ?: getSystemService(LocationManager::class.java)?.also {
                            locationManager = it
                        }
                        ?: return

        publishBestLastKnownLocation(manager)

        var requested = false
        val hasFine =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
        val hasCoarse =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED

        if (hasFine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            runCatching {
                        manager.requestLocationUpdates(
                                LocationManager.GPS_PROVIDER,
                                LOCATION_MIN_TIME_MS,
                                LOCATION_MIN_DISTANCE_METERS,
                                locationListener,
                                Looper.getMainLooper()
                        )
                    }
                    .onSuccess { requested = true }
                    .onFailure { Log.w(TAG, "Failed to request GPS updates: ${it.message}") }
        }

        if (hasCoarse && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                        manager.requestLocationUpdates(
                                LocationManager.NETWORK_PROVIDER,
                                LOCATION_MIN_TIME_MS,
                                LOCATION_MIN_DISTANCE_METERS,
                                locationListener,
                                Looper.getMainLooper()
                        )
                    }
                    .onSuccess { requested = true }
                    .onFailure { Log.w(TAG, "Failed to request network location updates: ${it.message}") }
        }

        if (hasCoarse && manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
            runCatching {
                        manager.requestLocationUpdates(
                                LocationManager.PASSIVE_PROVIDER,
                                LOCATION_MIN_TIME_MS,
                                LOCATION_MIN_DISTANCE_METERS,
                                locationListener,
                                Looper.getMainLooper()
                        )
                    }
                    .onSuccess { requested = true }
                    .onFailure { Log.w(TAG, "Failed to request passive location updates: ${it.message}") }
        }

        locationTrackingActive = requested
        if (requested) {
            Log.i(TAG, "Location tracking enabled")
        } else {
            Log.w(TAG, "Location tracking unavailable; no providers registered")
        }
    }

    private fun stopLocationTracking() {
        if (!locationTrackingActive) return
        runCatching { locationManager?.removeUpdates(locationListener) }
                .onFailure { Log.w(TAG, "Failed to remove location updates: ${it.message}") }
        locationTrackingActive = false
    }

    private fun publishBestLastKnownLocation(manager: LocationManager) {
        val hasFine =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
        val hasCoarse =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        val providers = mutableListOf<String>()
        if (hasFine) {
            providers += LocationManager.GPS_PROVIDER
        }
        if (hasCoarse) {
            providers += LocationManager.NETWORK_PROVIDER
            providers += LocationManager.PASSIVE_PROVIDER
        }

        var best: Location? = null
        providers.forEach { provider ->
            val candidate = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: return@forEach
            best = selectBetterLocation(current = best, candidate = candidate)
        }
        best?.let { publishDeviceLocationContext(it) }
    }

    private fun selectBetterLocation(current: Location?, candidate: Location): Location {
        if (current == null) return candidate
        val candidateTime = candidate.time
        val currentTime = current.time
        val candidateAccuracy = if (candidate.hasAccuracy()) candidate.accuracy else Float.MAX_VALUE
        val currentAccuracy = if (current.hasAccuracy()) current.accuracy else Float.MAX_VALUE
        val timeDelta = candidateTime - currentTime

        return when {
            timeDelta > 120_000L -> candidate
            timeDelta < -120_000L -> current
            candidateAccuracy + 10f < currentAccuracy -> candidate
            timeDelta > 0L && candidateAccuracy <= currentAccuracy + 50f -> candidate
            else -> current
        }
    }

    private fun publishDeviceLocationContext(location: Location) {
        val context =
                com.rayneo.visionclaw.core.model.DeviceLocationContext(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                        altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                        speedMps = if (location.hasSpeed()) location.speed else null,
                        bearingDeg = if (location.hasBearing()) location.bearing else null,
                        provider = location.provider,
                        timestampMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                )
        viewModel.updateDeviceLocationContext(context)
        Log.d(
                TAG,
                "Location update provider=${context.provider} lat=${context.latitude} lon=${context.longitude} acc=${context.accuracyMeters}"
        )
        viewModel.refreshHudUpcomingCalendar(force = false)
    }

    // ══════════════════════════════════════════════════════════════════════
    // XR Origin Safety
    // ══════════════════════════════════════════════════════════════════════

    private fun initXrOriginSafe() {
        try {
            val xrDisplayManager = getSystemService("xr_display")
            if (xrDisplayManager == null) {
                Log.w(TAG, "XR display subsystem is null — running in fallback 2D mode")
                return
            }
            Log.d(TAG, "XR display subsystem initialised: $xrDisplayManager")

            // Attach vendor trackpad bridge if ARDK is available on-device
            val bridge = RayNeoArdkTrackpadBridge()
            val attached = bridge.attachIfAvailable(this)
            Log.d(TAG, "Vendor trackpad bridge attached: $attached")

            try {
                val cameraManager = getSystemService(CAMERA_SERVICE)
                if (cameraManager == null) {
                    Log.w(TAG, "Camera service is null — photo features disabled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera subsystem init failed", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "XR Origin init failed — running in 2D fallback mode", e)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Immersive Display (6 000-nit MicroLED optimised)
    // ══════════════════════════════════════════════════════════════════════

    @SuppressLint("WrongConstant")
    private fun configureImmersiveDisplay() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            applyTransparentSystemBarColors()

            val lp = attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            attributes = lp
        }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        window.decorView.setBackgroundColor(Color.BLACK)
    }

    @Suppress("DEPRECATION")
    private fun applyTransparentSystemBarColors() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    fun enterTextInputMode() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        hideSystemIme()
    }

    fun exitTextInputMode() {
        hideCustomKeyboard(clearFocus = true)
        configureImmersiveDisplay()
        window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
    }

    fun showCustomKeyboardFor(target: EditText) {
        activeTextInput = target
        target.showSoftInputOnFocus = false
        target.requestFocus()
        target.requestFocusFromTouch()
        target.isCursorVisible = true
        target.setSelection(target.text?.length ?: 0)
        enterTextInputMode()
        customKeyboardView?.visibility = View.VISIBLE
        customKeyboardView?.bringToFront()
        customKeyboardView?.post { customKeyboardView?.focusHideButton() }
    }

    fun hideCustomKeyboard(clearFocus: Boolean = false) {
        hideSystemIme()
        customKeyboardView?.visibility = View.GONE
        if (clearFocus) {
            activeTextInput?.clearFocus()
            currentFocus?.clearFocus()
            activeTextInput = null
        }
    }

    private fun hideSystemIme() {
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        val imm = getSystemService(InputMethodManager::class.java)
        val token = currentFocus?.windowToken ?: window.decorView.windowToken
        imm?.hideSoftInputFromWindow(token, 0)
    }

    private fun setupCustomKeyboard() {
        customKeyboardView?.setOnKeyboardActionListener(
                object : CustomKeyboardView.OnKeyboardActionListener {
                    override fun onKeyPressed(key: String) {
                        withActiveInput { target ->
                            val start = target.selectionStart.coerceAtLeast(0)
                            val end = target.selectionEnd.coerceAtLeast(0)
                            val min = minOf(start, end)
                            val max = maxOf(start, end)
                            target.text?.replace(min, max, key)
                            target.setSelection(min + key.length)
                        }
                    }

                    override fun onBackspacePressed() {
                        withActiveInput { target ->
                            val start = target.selectionStart.coerceAtLeast(0)
                            val end = target.selectionEnd.coerceAtLeast(0)
                            val min = minOf(start, end)
                            val max = maxOf(start, end)
                            when {
                                min != max -> {
                                    target.text?.delete(min, max)
                                    target.setSelection(min)
                                }
                                min > 0 -> {
                                    target.text?.delete(min - 1, min)
                                    target.setSelection(min - 1)
                                }
                            }
                        }
                    }

                    override fun onEnterPressed() {
                        withActiveInput { target ->
                            val handled =
                                    target.dispatchKeyEvent(
                                            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                                    ) ||
                                            target.dispatchKeyEvent(
                                                    KeyEvent(
                                                            KeyEvent.ACTION_UP,
                                                            KeyEvent.KEYCODE_ENTER
                                                    )
                                            )
                            if (!handled) {
                                target.text?.append("\n")
                                target.setSelection(target.text?.length ?: 0)
                            }
                        }
                    }

                    override fun onHideKeyboard() {
                        hideCustomKeyboard(clearFocus = true)
                    }

                    override fun onClearPressed() {
                        withActiveInput { target ->
                            target.text?.clear()
                            target.setSelection(0)
                        }
                    }

                    override fun onMoveCursorLeft() {
                        withActiveInput { target ->
                            val pos = target.selectionStart.coerceAtLeast(0)
                            target.setSelection((pos - 1).coerceAtLeast(0))
                        }
                    }

                    override fun onMoveCursorRight() {
                        withActiveInput { target ->
                            val pos = target.selectionStart.coerceAtLeast(0)
                            val max = target.text?.length ?: 0
                            target.setSelection((pos + 1).coerceAtMost(max))
                        }
                    }

                }
        )
        customKeyboardView?.visibility = View.GONE
    }

    private inline fun withActiveInput(action: (EditText) -> Unit) {
        val cached = activeTextInput?.takeIf { it.isAttachedToWindow }
        val target = cached ?: (currentFocus as? EditText) ?: return
        activeTextInput = target
        target.showSoftInputOnFocus = false
        hideSystemIme()
        action(target)
    }

    // ══════════════════════════════════════════════════════════════════════
    // ViewPager (Chat HUD host)
    // ══════════════════════════════════════════════════════════════════════

    private fun setupViewPager() {
        val pager =
                viewPager
                        ?: run {
                            Log.e(TAG, "ViewPager is null — cannot set up panels")
                            return
                        }

        pager.adapter = MainPagerAdapter(this, chatFragment)
        pager.offscreenPageLimit = 1
        pager.isUserInputEnabled = false
        pager.isSaveEnabled = false

        pager.registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        viewModel.setActivePanel(position)
                        handlePanelChanged(position)
                    }
                }
        )
    }

    private fun applyInitialPageSelection() {
        val pager = viewPager ?: return
        pager.setCurrentItem(MainViewModel.PANEL_CHAT, false)
        viewModel.setActivePanel(MainViewModel.PANEL_CHAT)
        pager.post {
            pager.setCurrentItem(MainViewModel.PANEL_CHAT, false)
            viewModel.setActivePanel(MainViewModel.PANEL_CHAT)
            handlePanelChanged(MainViewModel.PANEL_CHAT)
        }
    }

    private fun handlePanelChanged(position: Int) {
        if (position != MainViewModel.PANEL_CHAT) return
        chatFragment.setHudModeEnabled(false)
        scheduleChatHudIdleTimer()
    }

    private fun scheduleChatHudIdleTimer() {
        uiHandler.removeCallbacks(chatHudIdleRunnable)
        if (viewPager?.currentItem != MainViewModel.PANEL_CHAT) return
        if (chatFragment.isHudModeEnabled()) return
        uiHandler.postDelayed(chatHudIdleRunnable, CHAT_HUD_IDLE_TIMEOUT_MS)
    }

    private fun markTrackpadActivity() {
        if (viewPager?.currentItem != MainViewModel.PANEL_CHAT) return
        if (chatFragment.isHudModeEnabled()) return
        scheduleChatHudIdleTimer()
    }

    /**
     * Double-tap behaviour depends on Gemini session state:
     *   1. Gemini active + camera ON  → turn camera OFF (Gemini keeps running)
     *   2. Gemini active + camera OFF → end Gemini session entirely
     *   3. Gemini NOT active           → launch TapBrowser (original behaviour)
     */
    private fun cyclePanelViaDoubleTap() {
        uiHandler.removeCallbacks(chatHudIdleRunnable)

        // ── If the chat panel is showing a reader-expanded card, double-tap
        //    closes it back to the normal chat carousel. ──
        val currentPanel = viewPager?.currentItem ?: MainViewModel.PANEL_CHAT
        if (currentPanel == MainViewModel.PANEL_CHAT && chatFragment.isReaderModeActive()) {
            chatFragment.exitReaderModeFromOutside()
            return
        }

        val geminiActive = viewModel.voiceAssistantActive.value == true

        if (geminiActive && cameraCaptureActive) {
            // State 1 → turn off camera, keep Gemini alive
            stopCameraCapture()
            showHudNotification("Camera off")
            return
        }

        if (geminiActive && !cameraCaptureActive) {
            // State 2 → end Gemini session
            shutdownMultimodalSession("Session ended.")
            return
        }

        // State 3 → no Gemini session, launch TapBrowser
        launchTapBrowser()
    }

    private fun launchTapBrowser(initialUrl: String? = null) {
        // Inject saved cookies from companion app into WebView CookieManager
        // before launching TapBrowser (same APK = shared CookieManager).
        injectSavedBrowserCookies()

        val intent =
                Intent().setClassName(this, TAP_BROWSER_ACTIVITY_CLASS).apply {
                    putExtra(EXTRA_RETURN_TO_CHAT_ON_DOUBLE_TAP, true)
                    initialUrl
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { putExtra(EXTRA_BROWSER_INITIAL_URL, it) }
                }

        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showHudNotification("TapBrowser module is unavailable")
        } finally {
            viewModel.setActivePanel(MainViewModel.PANEL_CHAT)
            viewPager?.setCurrentItem(MainViewModel.PANEL_CHAT, false)
            chatFragment.setHudModeEnabled(false)
            scheduleChatHudIdleTimer()
        }
    }

    /**
     * Reads browser_cookies from SharedPreferences (set by companion app)
     * and injects them into Android's CookieManager so TapBrowser can use them.
     * Format: JSON array of { domain, cookies, label } objects.
     */
    private fun injectSavedBrowserCookies() {
        try {
            val raw = viewModel.preferences.let {
                val prefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
                prefs.getString("browser_cookies", null)
            }
            if (raw.isNullOrBlank()) return

            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                val domain = entry.optString("domain", "").trim()
                val cookieStr = entry.optString("cookies", "").trim()
                if (domain.isBlank() || cookieStr.isBlank()) continue

                // The cookie string from document.cookie is semicolon-separated.
                // CookieManager.setCookie expects one cookie at a time.
                val url = if (domain.startsWith(".")) "https://${domain.substring(1)}" else "https://$domain"
                for (cookie in cookieStr.split(";")) {
                    val trimmed = cookie.trim()
                    if (trimmed.isNotBlank()) {
                        cookieManager.setCookie(url, trimmed)
                    }
                }
            }
            cookieManager.flush()
            Log.d(TAG, "Injected saved browser cookies for ${arr.length()} domain(s)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inject browser cookies: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Trackpad Gesture Engine
    // ══════════════════════════════════════════════════════════════════════

    private fun setupGestureEngine() {
        gestureEngine.onShortTap = {
            Log.d(TAG, "Short tap")
            runOnUiThread {
                if (customKeyboardView?.visibility == View.VISIBLE) {
                    customKeyboardView?.performFocusedTap()
                    return@runOnUiThread
                }
                val currentPanel = viewPager?.currentItem ?: MainViewModel.PANEL_CHAT
                if (currentPanel == MainViewModel.PANEL_CHAT) {
                    if (chatFragment.isHudModeEnabled()) {
                        chatFragment.setHudModeEnabled(false)
                        scheduleChatHudIdleTimer()
                        return@runOnUiThread
                    }
                    when (chatFragment.handleFocusedCardTap()) {
                        ChatPanelFragment.FocusedTapResult.OPENED_URL -> return@runOnUiThread
                        ChatPanelFragment.FocusedTapResult.IGNORED -> return@runOnUiThread
                        ChatPanelFragment.FocusedTapResult.ACTIVATE_ASSISTANT ->
                                activateChatVoiceAssistant()
                    }
                    return@runOnUiThread
                }
                // Let the active panel handle explicit trackpad selection first.
                val consumed = currentTrackpadPanel()?.onTrackpadSelect() ?: false
                if (!consumed) {
                    // Fallback click if panel does not implement trackpad-select.
                    viewPager?.focusedChild?.performClick()
                }
            }
        }

        gestureEngine.onDoubleTap = {
            Log.d(TAG, "Double tap → cycle panel/session")
            runOnUiThread {
                cyclePanelViaDoubleTap()
            }
        }
    }

    private fun activateChatVoiceAssistant() {
        // ── Debounce rapid taps ──────────────────────────────────────
        val now = SystemClock.elapsedRealtime()
        if (now - lastVoiceActivationMs < VOICE_ACTIVATION_DEBOUNCE_MS) {
            Log.d(TAG, "activateChatVoiceAssistant: debounced (${now - lastVoiceActivationMs}ms)")
            return
        }
        lastVoiceActivationMs = now

        if (!micPermissionGranted) {
            showHudNotification("Microphone permission required")
            playVoiceTimeoutBeep()
            return
        }

        // ── Tear down any existing / stale session first ─────────────
        if (geminiLiveSession != null || geminiCaptureActive || liveState != GeminiLiveState.IDLE) {
            Log.d(TAG, "activateChatVoiceAssistant: cleaning stale session " +
                    "(session=${geminiLiveSession != null}, capture=$geminiCaptureActive, state=$liveState)")
            releaseGeminiAudioCapture(cancelOnly = true)
            viewModel.deactivateVoiceAssistant()
            showListeningOverlay(false)
            // Brief pause to let WebSocket close, then start fresh session
            // automatically — no second tap required.
            uiHandler.postDelayed({
                lastVoiceActivationMs = 0L  // reset debounce so activation proceeds
                activateChatVoiceAssistant()
            }, 300L)
            return
        }

        // ── Start fresh session ──────────────────────────────────────
        nativeSttFallbackTriggered = false
        viewModel.activateVoiceAssistant()
        showListeningOverlay(true)
        updateListeningTranscript("Listening…")
        pushOscilloscopeLevel(0.08f, OSCILLOSCOPE_USER_COLOR, force = true)
        playVoiceActivateBeep()
        uiHandler.removeCallbacks(delayedVoiceStartRunnable)
        uiHandler.postDelayed(delayedVoiceStartRunnable, VOICE_LISTEN_START_DELAY_MS)
    }

    private fun isChatUiReady(): Boolean {
        return chatFragment.isAdded && chatFragment.view != null
    }

    private fun setHudConnectionStatus(status: ChatPanelFragment.ConnectionStatus) {
        when (status) {
            ChatPanelFragment.ConnectionStatus.GEMINI_CONNECTED,
            ChatPanelFragment.ConnectionStatus.GEMINI_CONNECTED -> {
                lastKnownAiConnectionStatus = status
            }

            else -> Unit
        }

        val renderStatus =
            if (status == ChatPanelFragment.ConnectionStatus.IDLE &&
                lastKnownAiConnectionStatus != ChatPanelFragment.ConnectionStatus.IDLE
            ) {
                lastKnownAiConnectionStatus
            } else {
                status
            }

        runOnUiThread {
            if (isChatUiReady()) {
                chatFragment.setConnectionStatus(renderStatus)
            }
        }
    }

    // AITap: OpenClaw bridge infrastructure removed.
    // All tool routing now handled by ToolDispatcher via Gemini native tool calls.

    /** Last user transcript from the Live session, used by ToolAssist recovery. */
    @Volatile private var lastToolAssistTranscript = ""
    /** Prevents double-firing recovery for the same turn. */
    @Volatile private var toolAssistRecoveryFired = false

    /**
     * Detect when Gemini says "I can't access the tool" or similar failure
     * responses, and re-inject the tool result via ToolAssist.  This handles
     * the race condition where Gemini's native-audio model starts responding
     * before our proactive ToolAssist injection arrives.
     */
    private fun maybeRecoverFromGeminiFallback(modelText: String): Boolean {
        val lower = modelText.lowercase()
        val isToolFailure = lower.contains("unable to access") ||
            lower.contains("tool") && (lower.contains("not available") || lower.contains("can't") || lower.contains("cannot")) ||
            lower.contains("don't have access to") ||
            lower.contains("i don't have the ability") ||
            lower.contains("i'm not able to") && (lower.contains("location") || lower.contains("place") || lower.contains("traffic")) ||
            lower.contains("enable location") ||
            lower.contains("i don't know where you are") ||
            lower.contains("don't have your location")

        if (!isToolFailure) return false
        if (toolAssistRecoveryFired) return false

        val transcript = lastToolAssistTranscript
        if (transcript.isBlank()) return false

        val engine = toolAssistEngine ?: return false
        toolAssistRecoveryFired = true

        Log.d(TAG, "ToolAssist RECOVERY triggered for: $transcript")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val assist = engine.maybeAssist(transcript) ?: return@launch
                Log.d(TAG, "ToolAssist recovery result [${assist.toolName}]: ${assist.resultText.take(200)}")
                val sent = geminiLiveSession?.sendClientText(assist.contextPrompt) == true
                Log.d(TAG, "ToolAssist recovery injected=$sent")
                if (sent) {
                    runOnUiThread {
                        viewModel.appendLiveAssistantStreamChunk(assist.resultText)
                        viewModel.commitLiveAssistantStreamIfNeeded()
                        showHudNotification(assist.resultText.take(120))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ToolAssist recovery error", e)
            }
        }
        return false // don't suppress the output — let Gemini finish, then it'll see the injected data
    }

    private fun refreshToolBridgeStatus() {
        // AITap: No external bridge. Tools are always local.
        setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.TOOLS_READY)
    }

    private fun dispatchLiveToolCall(callId: String, name: String, args: String) {
        val functionName = name.trim()
        if (functionName.isBlank()) return
        if (!toolDispatcher.isSupported(functionName)) {
            Log.w(TAG, "Unsupported tool call: $functionName — sending error back to Gemini")
            lifecycleScope.launch(Dispatchers.IO) {
                val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
                geminiLiveSession?.sendToolResponse(responseId, functionName, "Unknown tool: $functionName")
            }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = toolDispatcher.dispatch(functionName, args)
            val resultText = result.getOrElse { err ->
                Log.e(TAG, "Tool dispatch error for $functionName", err)
                "Tool $functionName is not yet configured."
            }
            val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
            Log.d(
                TAG,
                "Tool result ready callId=$responseId function=$functionName text=${resultText.take(220)}"
            )
            val sent = geminiLiveSession?.sendToolResponse(responseId, functionName, resultText) == true
            Log.d(TAG, "sendToolResponse sent=$sent callId=$responseId")
            val hudText = hudSafeCalendarResult(resultText)
            runOnUiThread {
                viewModel.appendLiveAssistantStreamChunk(hudText)
                viewModel.commitLiveAssistantStreamIfNeeded()
                showHudNotification(hudText)
                if (sent) {
                    setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.TOOLS_READY)
                } else {
                    Log.w(TAG, "sendToolResponse failed — Gemini session may have closed")
                    setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.GEMINI_CONNECTED)
                }
            }
        }
    }

    /**
     * AITap: No local intent bypass needed — Gemini routes all queries
     * through native tool calls. Kept as stub to avoid breaking callers.
     */
    private fun maybeRouteLocalIntentDirectly(
        transcript: String,
        forcedSkill: String? = null,
        forcedIntent: String? = null
    ): Boolean = false

    private fun hudSafeCalendarResult(raw: String): String {
        val cleaned = raw.replace('\r', '\n').trim()
        if (cleaned.isBlank()) return "No upcoming events."
        // If the cleaned text looks like a direct calendar answer, return it
        // before running line-level noise filtering.
        val looksLikeCalendarAnswer = cleaned.contains("—") ||
            cleaned.contains(" AM") || cleaned.contains(" PM") ||
            cleaned.lowercase(Locale.US).let {
                it.startsWith("no upcoming events") ||
                it.startsWith("no events") ||
                it.startsWith("you have no") ||
                it.startsWith("your next event") ||
                it.startsWith("here are your") ||
                it.startsWith("today's events") ||
                it.startsWith("tomorrow's events")
            }
        if (looksLikeCalendarAnswer) {
            val firstLines = cleaned.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(6)
                .toList()
            return if (firstLines.isEmpty()) "No upcoming events." else firstLines.joinToString("\n")
        }
        val lines =
            cleaned
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { line ->
                    when {
                        line.startsWith("•") -> line.removePrefix("•").trim()
                        line.startsWith("-") -> line.removePrefix("-").trim()
                        line.matches(Regex("^\\d+[.)].*")) ->
                            line.replaceFirst(Regex("^\\d+[.)]\\s*"), "").trim()
                        else -> line
                    }
                }
                .filterNot { looksLikeInternalHudNoise(it) }
                .take(6)
                .toList()
        if (lines.isEmpty()) return "No upcoming events."
        return lines.joinToString("\n")
    }

    private fun looksLikeInternalHudNoise(line: String): Boolean {
        val value = line.trim().lowercase(Locale.US)
        if (value.isBlank()) return false
        // Only filter genuinely internal/diagnostic output.
        // Do NOT filter lines starting with '[' generically — calendar
        // answers like "[10:00] Meeting" are valid.
        return value.startsWith("[tools]") ||
            value.startsWith("ps ") ||
            value.startsWith("http/") ||
            (value.startsWith("{") && value.contains("\"")) ||
            value.startsWith("error:") ||
            value.startsWith("tool dispatch error") ||
            (value.contains("exception") && value.contains(" at ")) ||
            value.contains("stack trace") ||
            value.contains("restart gateway") ||
            value.contains(" at com.")
    }

    private fun syncCameraToGeminiState(active: Boolean) {
        if (!active) {
            stopCameraCapture()
            return
        }

        if (!cameraPermissionGranted) {
            pendingCameraStart = true
            return
        }

        if (!isChatUiReady()) {
            pendingCameraStart = true
            return
        }

        // Ensure PiP is visible before checking TextureView readiness.
        chatFragment.setCoreEyeCaptureEnabled(true)

        if (!coreEyeSurfaceReady || !chatFragment.isCoreEyeSurfaceReady()) {
            pendingCameraStart = true
            return
        }

        if (!cameraCaptureActive) {
            pendingCameraStart = false
            startCameraCapture()
        } else {
            pendingCameraStart = false
            viewModel.setMultimodalCameraEnabled(true)
            chatFragment.setCoreEyeCaptureEnabled(true)
        }
    }

    private fun refreshCameraForGeminiSession() {
        if (viewModel.voiceAssistantActive.value != true) {
            showHudNotification("Activate Gemini first")
            return
        }
        if (!cameraPermissionGranted) {
            showHudNotification("Camera permission required")
            return
        }

        if (!isChatUiReady() || !coreEyeSurfaceReady || !chatFragment.isCoreEyeSurfaceReady()) {
            pendingCameraStart = true
            showHudNotification("Preparing camera…")
            return
        }

        if (cameraCaptureActive) {
            stopCameraCapture()
        }
        syncCameraToGeminiState(active = true)
        showHudNotification("Camera refreshed")
    }

    private fun startCameraCapture() {
        if (viewModel.voiceAssistantActive.value != true) {
            stopCameraCapture()
            return
        }
        if (cameraCaptureActive) return
        val surfaceProvider = chatFragment.getCoreEyeSurfaceProvider()
        if (surfaceProvider == null) {
            pendingCameraStart = true
            showHudNotification("Waiting for camera surface…")
            return
        }
        pendingCameraStart = false
        chatFragment.setCoreEyeCaptureEnabled(true)
        frameCapture?.start(this, surfaceProvider) { base64 ->
            latestFrame = base64
            maybeSendMultimodalImageFrame(base64)
            runOnUiThread { chatFragment.onCoreEyeFrameStreamed() }
        }
        cameraCaptureActive = true
        viewModel.setMultimodalCameraEnabled(true)
    }

    private fun stopCameraCapture() {
        uiHandler.removeCallbacks(cameraIdleTimeoutRunnable)
        if (cameraCaptureActive) {
            frameCapture?.stop()
        }
        cameraCaptureActive = false
        pendingCameraStart = false
        latestFrame = null
        lastMultimodalFrameSentMs = 0L
        viewModel.setMultimodalCameraEnabled(false)
        if (isChatUiReady()) {
            chatFragment.setCoreEyeCaptureEnabled(false)
        }
    }

    private fun isGeminiListeningOrThinking(): Boolean {
        return viewModel.voiceAssistantActive.value == true ||
                geminiCaptureActive ||
                geminiLiveSession != null ||
                liveSessionReady
    }

    private fun scheduleCameraIdleTimeout() {
        if (!cameraCaptureActive) return
        uiHandler.removeCallbacks(cameraIdleTimeoutRunnable)
        uiHandler.postDelayed(cameraIdleTimeoutRunnable, CAMERA_IDLE_TIMEOUT_MS)
    }

    /** Forward trackpad touch events and update chat HUD idle timers. */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev == null) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP -> markTrackpadActivity()
        }
        if (customKeyboardView?.visibility == View.VISIBLE) {
            if (gestureEngine.onTouchEvent(ev)) {
                return true
            }
            return super.dispatchTouchEvent(ev)
        }

        if (gestureEngine.onTouchEvent(ev)) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val isPointerLike =
                        ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER) ||
                                ev.isFromSource(InputDevice.SOURCE_MOUSE) ||
                                ev.isFromSource(InputDevice.SOURCE_TOUCHPAD)
                if (isPointerLike) {
                    markTrackpadActivity()
                    var deltaX = ev.getAxisValue(MotionEvent.AXIS_HSCROLL) * GENERIC_SCROLL_SCALE
                    var deltaY = -ev.getAxisValue(MotionEvent.AXIS_VSCROLL) * GENERIC_SCROLL_SCALE

                    if (deltaX == 0f && deltaY == 0f) {
                        deltaX = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                        deltaY = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                    }

                    if (gestureEngine.onGenericScroll(deltaX, deltaY)) {
                        return true
                    }
                    if (currentTrackpadPanel()?.onTrackpadPan(deltaX, deltaY) == true) {
                        return true
                    }
                }
            }

            MotionEvent.ACTION_BUTTON_PRESS -> {
                if ((ev.buttonState and MotionEvent.BUTTON_PRIMARY) != 0) {
                    markTrackpadActivity()
                    if (dispatchSyntheticTrackpadTap(MotionEvent.ACTION_DOWN, ev)) {
                        return true
                    }
                }
            }

            MotionEvent.ACTION_BUTTON_RELEASE -> {
                markTrackpadActivity()
                if (dispatchSyntheticTrackpadTap(MotionEvent.ACTION_UP, ev)) {
                    return true
                }
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun dispatchSyntheticTrackpadTap(action: Int, sourceEvent: MotionEvent): Boolean {
        val synthetic =
                MotionEvent.obtain(
                        sourceEvent.downTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis(),
                        action,
                        sourceEvent.x,
                        sourceEvent.y,
                        0
                )
        synthetic.source = sourceEvent.source
        return try {
            gestureEngine.onTouchEvent(synthetic)
        } finally {
            synthetic.recycle()
        }
    }

    /**
     * Forward hardware key events from the temple trackpad. Signature uses NON-nullable KeyEvent to
     * match AppCompatActivity.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            markTrackpadActivity()
        }
        if (gestureEngine.onKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Speech & Audio Handlers
    // ══════════════════════════════════════════════════════════════════════

    private fun touchGeminiLiveActivity(force: Boolean = false) {
        if (geminiLiveSession == null) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastLiveActivityHeartbeatMs < GEMINI_LIVE_ACTIVITY_HEARTBEAT_MS) {
            return
        }
        lastLiveActivityHeartbeatMs = now
    }

    private fun armSilenceWatchdog() {
        if (geminiLiveSession == null) return
        if (chatFragment.isReaderModeActive()) return
        uiHandler.removeCallbacks(stopGeminiCaptureRunnable)
        uiHandler.postDelayed(stopGeminiCaptureRunnable, GEMINI_LIVE_IDLE_TIMEOUT_MS)
    }

    private fun disarmSilenceWatchdog() {
        uiHandler.removeCallbacks(stopGeminiCaptureRunnable)
    }

    private fun handleGeminiLiveIdleTimeout() {
        if (geminiLiveSession == null || liveState == GeminiLiveState.IDLE) {
            hideOscilloscope()
            return
        }
        if (chatFragment.isReaderModeActive()) {
            return
        }
        shutdownMultimodalSession("Session ended after 5s of silence.")
    }

    private fun shutdownMultimodalSession(message: String? = null) {
        disarmSilenceWatchdog()
        awaitingServerTurnComplete = false
        releaseGeminiAudioCapture(cancelOnly = true)
        viewModel.deactivateVoiceAssistant()
        showListeningOverlay(false)
        clearLiveSpeechPreview()
        clearListeningTranscript()
        hideOscilloscope()
        chatFragment.setStreamActiveIndicator(false)
        setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.IDLE)
        stopCameraCapture()
        runOnUiThread {
            chatFragment.autoFocusLatestAssistantUrl()
        }
        message?.trim()?.takeIf { it.isNotBlank() }?.let { showHudNotification(it) }
    }

    private fun markUserSpeechActivity() {
        lastUserSpeechActivityMs = SystemClock.uptimeMillis()
        awaitingServerTurnComplete = true
        disarmSilenceWatchdog()
        if (liveState == GeminiLiveState.FOLLOW_UP || liveState == GeminiLiveState.THINKING) {
            liveState = GeminiLiveState.LISTENING
            runOnUiThread { updateListeningTranscript("Listening… Speak now") }
        }
        touchGeminiLiveActivity(force = true)
    }

    private fun maybeSendMultimodalImageFrame(imageBase64: String) {
        if (!viewModel.canSendMultimodalFrame()) return
        if (!liveSessionReady || geminiLiveSession == null) return
        if (liveState == GeminiLiveState.IDLE) {
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastMultimodalFrameSentMs < MULTIMODAL_FRAME_INTERVAL_MS) return
        val sent = geminiLiveSession?.sendImageChunkBase64(imageBase64, "image/jpeg") == true
        if (sent) {
            lastMultimodalFrameSentMs = now
            Log.d(TAG, "Sent multimodal frame to Gemini Live")
        }
    }

    private fun startVoiceInputSession() {
        if (USE_NATIVE_STT) {
            val controller = speechController
            if (controller != null) {
                controller.startListening()
            } else {
                fallbackToGeminiLiveFromNativeStt("speech_controller_unavailable")
            }
            return
        }
        startGeminiAudioCapture()
    }

    private fun fallbackToGeminiLiveFromNativeStt(reason: String): Boolean {
        if (!USE_NATIVE_STT) return false
        if (nativeSttFallbackTriggered) return false
        if (geminiLiveSession != null || geminiCaptureActive) return false

        nativeSttFallbackTriggered = true
        Log.w(TAG, "Native STT fallback -> Gemini Live ($reason)")
        showHudNotification("Voice fallback: Gemini Live")
        startGeminiAudioCapture()
        return true
    }

    private fun startGeminiAudioCapture() {
        if (geminiCaptureActive || geminiLiveSession != null) return

        viewModel.resetLiveAssistantStream()
        geminiAudioPlayer?.stopAndFlush()
        liveSessionReady = false
        liveSessionClosingByApp = false
        liveState = GeminiLiveState.LISTENING
        awaitingServerTurnComplete = false
        lastLiveActivityHeartbeatMs = 0L
        lastMultimodalFrameSentMs = 0L
        lastUserSpeechActivityMs = 0L
        latestLiveTranscript = ""
        latestLiveOutputTranscript = ""
        sawNonSilentGeminiAudio = false
        loggedGeminiAudioProbe = false
        updateListeningTranscript("Connecting to Gemini Live…")
        setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.CONNECTING)
        pushOscilloscopeLevel(0.06f, OSCILLOSCOPE_USER_COLOR, force = true)
        uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
        uiHandler.removeCallbacks(stopGeminiCaptureRunnable)
        uiHandler.postDelayed(liveSetupTimeoutRunnable, GEMINI_LIVE_CONNECT_TIMEOUT_MS)

        // Capture the epoch so callbacks from THIS session can detect staleness.
        val sessionEpoch = geminiSessionEpoch

        val session =
                viewModel.geminiRouter.startLiveAudioSession(
                        listener =
                                object :
                                        com.rayneo.visionclaw.core.network.GeminiRouter.LiveSessionListener {
                                    /** True only while this session is still the active one. */
                                    private fun isCurrentSession(): Boolean =
                                            sessionEpoch == geminiSessionEpoch

                                    override fun onSessionReady() {
                                        if (!isCurrentSession()) return
                                        runOnUiThread {
                                            if (!isCurrentSession()) return@runOnUiThread
                                            uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
                                            liveSessionReady = true
                                            forceDirectGeminiLive = true
                                            liveState = GeminiLiveState.LISTENING
                                            awaitingServerTurnComplete = false
                                            // Minimal UI updates first — get the mic streaming ASAP.
                                            setHudConnectionStatus(
                                                    ChatPanelFragment.ConnectionStatus.GEMINI_CONNECTED
                                            )
                                            updateListeningTranscript("Listening… Speak now")
                                            chatFragment.setStreamActiveIndicator(false)
                                            startGeminiAudioStreaming()
                                            touchGeminiLiveActivity(force = true)
                                            // Deferred: bridge-reachability ping is cosmetic —
                                            // run it after the session is fully streaming.
                                            uiHandler.postDelayed({ refreshToolBridgeStatus() }, 800L)
                                        }
                                    }

                                    override fun onInputTranscription(text: String) {
                                        if (!isCurrentSession()) return
                                        val safe = text.trim()
                                        if (safe.isBlank()) return
                                        if (maybeRouteLocalIntentDirectly(safe)) return
                                        liveState = GeminiLiveState.LISTENING
                                        awaitingServerTurnComplete = true
                                        markUserSpeechActivity()
                                        latestLiveTranscript =
                                                mergeLiveTranscript(latestLiveTranscript, safe)
                                        runOnUiThread {
                                            updateListeningTranscript(safe)
                                        }
                                        // ── ToolAssist: proactively execute tools client-side ──
                                        // The native-audio model rarely calls tools itself, so we
                                        // detect tool-worthy queries and inject results directly.
                                        lastToolAssistTranscript = safe
                                        toolAssistRecoveryFired = false
                                        val engine = toolAssistEngine ?: return
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            try {
                                                val assist = engine.maybeAssist(safe) ?: return@launch
                                                Log.d(TAG, "ToolAssist matched [${assist.toolName}]: ${assist.resultText.take(200)}")
                                                // Inject the tool result as a client text turn
                                                val sent = geminiLiveSession?.sendClientText(assist.contextPrompt) == true
                                                Log.d(TAG, "ToolAssist injected clientContent sent=$sent")
                                                // Also show in HUD
                                                runOnUiThread {
                                                    viewModel.appendLiveAssistantStreamChunk(assist.resultText)
                                                    viewModel.commitLiveAssistantStreamIfNeeded()
                                                    showHudNotification(assist.resultText.take(120))
                                                }
                                            } catch (e: Exception) {
                                                Log.e(TAG, "ToolAssist error", e)
                                            }
                                        }
                                    }

                                    override fun onOutputTranscription(text: String) {
                                        if (!isCurrentSession()) return
                                        val safe = text.trim()
                                        if (safe.isBlank()) return
                                        if (maybeRecoverFromGeminiFallback(safe)) return

                                        liveState = GeminiLiveState.THINKING
                                        awaitingServerTurnComplete = true
                                        touchGeminiLiveActivity()
                                        latestLiveOutputTranscript =
                                                mergeLiveTranscript(latestLiveOutputTranscript, safe)
                                        runOnUiThread {
                                            chatFragment.setStreamActiveIndicator(true)
                                            updateListeningTranscript(safe)
                                            viewModel.appendLiveAssistantStreamChunk(
                                                    latestLiveOutputTranscript
                                            )
                                            if (Patterns.WEB_URL.matcher(latestLiveOutputTranscript).find()) {
                                                chatFragment.autoFocusLatestAssistantUrl()
                                            }
                                        }
                                    }
                                    override fun onModelText(text: String) {
                                        if (!isCurrentSession()) return
                                        if (maybeRecoverFromGeminiFallback(text)) return

                                        liveState = GeminiLiveState.THINKING
                                        awaitingServerTurnComplete = true
                                        touchGeminiLiveActivity()
                                        // Verbatim dialog mode: ignore free-form model text/metadata payloads.
                                        // Chat persistence is driven only by outputTranscription.
                                    }
                                    override fun onModelAudio(mimeType: String, data: ByteArray) {
                                        if (!isCurrentSession()) return
                                        liveState = GeminiLiveState.THINKING
                                        awaitingServerTurnComplete = true
                                        touchGeminiLiveActivity()
                                        val outputPeak = calculatePcm16Peak(data, data.size)
                                        val normalised = (outputPeak / 32767f).coerceIn(0f, 1f)
                                        pushOscilloscopeLevel(normalised, OSCILLOSCOPE_MODEL_COLOR)
                                        runOnUiThread { chatFragment.setStreamActiveIndicator(true) }
                                        val prefs = viewModel.preferences
                                        geminiAudioPlayer?.playChunk(
                                                mimeType = mimeType,
                                                data = data,
                                                muted = prefs.ttsMuted,
                                                volume = prefs.ttsVolume
                                        )
                                    }

                                    override fun onToolCall(callId: String, name: String, args: String) {
                                        if (!isCurrentSession()) return
                                        awaitingServerTurnComplete = true
                                        touchGeminiLiveActivity()
                                        dispatchLiveToolCall(callId = callId, name = name, args = args)
                                    }

                                    override fun onTurnComplete(finishReason: String?) {
                                        if (!isCurrentSession()) return
                                        viewModel.commitLiveAssistantStreamIfNeeded()
                                        awaitingServerTurnComplete = false
                                        liveState = GeminiLiveState.FOLLOW_UP
                                        val shouldStartCleanupTimer =
                                                finishReason.isNullOrBlank() ||
                                                        finishReason.equals("STOP", ignoreCase = true)
                                        if (shouldStartCleanupTimer) {
                                            armSilenceWatchdog()
                                        } else {
                                            disarmSilenceWatchdog()
                                        }
                                        runOnUiThread {
                                            chatFragment.setStreamActiveIndicator(false)
                                            updateListeningTranscript("Listening for follow-up…")
                                            chatFragment.autoFocusLatestAssistantUrl()
                                        }
                                        Log.d(TAG, "Gemini turn complete finishReason=${finishReason ?: "unknown"}")
                                    }

                                    override fun onError(message: String) {
                                        if (!isCurrentSession()) return
                                        uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
                                        awaitingServerTurnComplete = false

                                        val gatewayProtocolMismatch =
                                                !forceDirectGeminiLive &&
                                                        (message.contains("invalid request frame", ignoreCase = true) ||
                                                                (message.contains("unexpected property", ignoreCase = true) &&
                                                                        message.contains("setup", ignoreCase = true)) ||
                                                                (message.contains("required property", ignoreCase = true) &&
                                                                        message.contains("method", ignoreCase = true)))
                                        if (gatewayProtocolMismatch) {
                                            forceDirectGeminiLive = true
                                            runOnUiThread {
                                                setHudConnectionStatus(
                                                        ChatPanelFragment.ConnectionStatus.CONNECTING
                                                )
                                                showHudNotification(
                                                        "Gateway RPC mode detected. Retrying direct Gemini…"
                                                )
                                                releaseGeminiAudioCapture(cancelOnly = true)
                                                uiHandler.postDelayed(
                                                        { startGeminiAudioCapture() },
                                                        150L
                                                )
                                            }
                                            return
                                        }

                                        setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.ERROR)
                                        handleGeminiVoiceFailure(message)
                                    }

                                    override fun onClosed(code: Int, reason: String) {
                                        Log.d(
                                                TAG,
                                                "Gemini Live session closed code=$code reason=$reason epoch=$sessionEpoch current=$geminiSessionEpoch"
                                        )
                                        if (!isCurrentSession()) {
                                            // Stale callback from a previous session — ignore.
                                            Log.d(TAG, "Ignoring onClosed from stale session (epoch $sessionEpoch)")
                                            return
                                        }
                                        uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
                                        val closedByApp = liveSessionClosingByApp
                                        val wasLiveSessionReady = liveSessionReady
                                        liveSessionClosingByApp = false
                                        liveSessionReady = false
                                        liveState = GeminiLiveState.IDLE
                                        awaitingServerTurnComplete = false
                                        if (closedByApp) {
                                            runOnUiThread {
                                                chatFragment.setStreamActiveIndicator(false)
                                                hideOscilloscope()
                                                setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.IDLE)
                                            }
                                            return
                                        }
                                        val invalidGatewayFrame =
                                                code == 1008 &&
                                                        reason.contains(
                                                                "invalid request frame",
                                                                ignoreCase = true
                                                        ) &&
                                                        !forceDirectGeminiLive
                                        if (invalidGatewayFrame) {
                                            forceDirectGeminiLive = true
                                            runOnUiThread {
                                                setHudConnectionStatus(
                                                        ChatPanelFragment.ConnectionStatus.CONNECTING
                                                )
                                                showHudNotification(
                                                        "Gateway live rejected. Retrying direct Gemini…"
                                                )
                                                releaseGeminiAudioCapture(cancelOnly = true)
                                                uiHandler.postDelayed(
                                                        { startGeminiAudioCapture() },
                                                        150L
                                                )
                                            }
                                            return
                                        }
                                        if (!wasLiveSessionReady) {
                                            val suffix =
                                                    reason.takeIf { it.isNotBlank() }?.let {
                                                        ": $it"
                                                    }
                                                            ?: ""
                                            handleGeminiVoiceFailure(
                                                    "Gemini Live closed before ready (code $code)$suffix"
                                            )
                                            return
                                        }
                                        runOnUiThread {
                                            chatFragment.setStreamActiveIndicator(false)
                                            shutdownMultimodalSession("Gemini Live session closed.")
                                        }
                                    }
                                },
                        forceDirect = true
                )

        if (session == null) {
            uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
            // startLiveAudioSession already reports the concrete error via listener.onError.
            return
        }
        geminiLiveSession = session
        touchGeminiLiveActivity(force = true)
    }

    private fun handleGeminiVoiceFailure(message: String) {
        val display = message.trim().ifBlank { "Voice request failed. Please try again." }
        Log.w(TAG, "Gemini voice failure: $display")
        runOnUiThread {
            awaitingServerTurnComplete = false
            viewModel.resetLiveAssistantStream()
            releaseGeminiAudioCapture(cancelOnly = true)
            viewModel.deactivateVoiceAssistant()
            showListeningOverlay(false)
            clearLiveSpeechPreview()
            clearListeningTranscript()
            chatFragment.setStreamActiveIndicator(false)
            setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.ERROR)
            playVoiceTimeoutBeep()
            showHudNotification(display)
        }
    }

    private fun startGeminiAudioStreaming() {
        if (geminiCaptureActive) return
        if (!liveSessionReady || geminiLiveSession == null) return

        enableRayNeoVoiceAssistantMicRoute()

        val minBuffer =
                AudioRecord.getMinBufferSize(
                        GEMINI_AUDIO_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                )
        if (minBuffer <= 0) {
            handleGeminiVoiceFailure("Microphone buffer could not be created.")
            return
        }

        val bufferSize = maxOf(minBuffer * 2, 4096)
        val recorder =
                runCatching {
                    AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            GEMINI_AUDIO_SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize
                    )
                }
                        .getOrElse {
                            handleGeminiVoiceFailure("Unable to start microphone capture.")
                            return
                        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            handleGeminiVoiceFailure("Microphone is not available.")
            return
        }

        geminiAudioRecord = recorder
        geminiCaptureActive = true
        recorder.startRecording()

        geminiAudioThread =
                Thread {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                    val chunk = ByteArray(2048)
                    var chunkCount = 0
                    while (geminiCaptureActive) {
                        val read = recorder.read(chunk, 0, chunk.size)
                        if (read > 0) {
                            if (!loggedGeminiAudioProbe) {
                                loggedGeminiAudioProbe = true
                                val preview =
                                        chunk.take(read.coerceAtMost(10)).joinToString(" ") { b ->
                                            "%02x".format(b.toInt() and 0xFF)
                                        }
                                Log.d(TAG, "Gemini mic probe bytes: $preview")
                            }
                            val peak = calculatePcm16Peak(chunk, read)
                            if (peak >= GEMINI_AUDIO_NON_SILENT_THRESHOLD) {
                                sawNonSilentGeminiAudio = true
                                awaitingServerTurnComplete = true
                                touchGeminiLiveActivity(force = true)
                            }
                            val normalisedPeak = (peak / 32767f).coerceIn(0f, 1f)
                            pushOscilloscopeLevel(normalisedPeak, OSCILLOSCOPE_USER_COLOR)
                            if (chunkCount % 12 == 0) {
                                Log.d(
                                        TAG,
                                        "Gemini mic chunk=$chunkCount bytes=$read peak=$peak nonSilent=$sawNonSilentGeminiAudio"
                                )
                            }
                            chunkCount += 1
                            geminiLiveSession?.sendAudioChunkPcm16(
                                    chunk,
                                    read,
                                    GEMINI_AUDIO_SAMPLE_RATE
                            )
                        } else if (read < 0) {
                            Log.w(TAG, "Gemini mic read error code=$read")
                        }
                    }
                }
                        .apply {
                            name = "GeminiLiveAudioThread"
                            start()
                        }
        touchGeminiLiveActivity(force = true)
    }

    private fun stopGeminiAudioStreaming() {
        if (!geminiCaptureActive && geminiAudioRecord == null) return

        geminiCaptureActive = false
        runCatching { geminiAudioRecord?.stop() }
        runCatching { geminiAudioRecord?.release() }
        geminiAudioRecord = null

        // Never join() on the UI thread — it blocked for up to 250ms per tap.
        // The audio thread exits on its own once geminiCaptureActive == false.
        val thread = geminiAudioThread
        geminiAudioThread = null
        if (thread != null) {
            // Fire-and-forget cleanup on a background thread.
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { thread.join(300) }
            }
        }
        disableRayNeoVoiceAssistantMicRouteAsync()
    }

    private fun releaseGeminiAudioCapture(cancelOnly: Boolean) {
        disarmSilenceWatchdog()
        uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
        stopGeminiAudioStreaming()
        geminiAudioPlayer?.stopAndFlush()
        liveSessionReady = false
        liveState = GeminiLiveState.IDLE
        awaitingServerTurnComplete = false
        lastLiveActivityHeartbeatMs = 0L
        lastMultimodalFrameSentMs = 0L
        lastUserSpeechActivityMs = 0L

        // Bump the epoch so stale callbacks from the dying session are ignored.
        geminiSessionEpoch++
        liveSessionClosingByApp = true
        // Close the WebSocket on a background thread to avoid blocking the UI.
        val session = geminiLiveSession
        geminiLiveSession = null
        if (session != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { session.close() }
            }
        }
        latestLiveTranscript = ""
        latestLiveOutputTranscript = ""
        chatFragment.setStreamActiveIndicator(false)
        hideOscilloscope()
        // stopGeminiAudioStreaming already released the mic route asynchronously;
        // belt-and-suspenders async call here is harmless if the flag was already cleared.
        disableRayNeoVoiceAssistantMicRouteAsync()
        if (cancelOnly) {
            viewModel.resetLiveAssistantStream()
        }
    }

    /**
     * MUST be synchronous — AudioRecord is created immediately after this call
     * in startGeminiAudioStreaming(), and the RayNeo hardware requires the
     * audio_source_record parameter to be set BEFORE the recorder opens.
     * The call typically completes in <5 ms on RayNeo X3 hardware.
     */
    private fun enableRayNeoVoiceAssistantMicRoute() {
        if (rayNeoMicRouteActive) return
        rayNeoMicRouteActive = true
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            audioManager.setParameters("audio_source_record=voiceassistant")
            Log.i(TAG, "RayNeo mic route enabled (voiceassistant)")
        }.onFailure { Log.w(TAG, "Unable to enable RayNeo mic route: ${it.message}") }
    }

    /** Synchronous variant — only called from stopGeminiAudioStreaming which already defers
     *  to a background thread for cleanup.  Kept for the legacy call-site in releaseGeminiAudioCapture. */
    private fun disableRayNeoVoiceAssistantMicRoute() {
        if (!rayNeoMicRouteActive) return
        rayNeoMicRouteActive = false
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            audioManager.setParameters("audio_source_record=off")
            Log.i(TAG, "RayNeo mic route released")
        }.onFailure { Log.w(TAG, "Unable to release RayNeo mic route: ${it.message}") }
    }

    /** Async variant — safe to call from the UI thread. */
    private fun disableRayNeoVoiceAssistantMicRouteAsync() {
        if (!rayNeoMicRouteActive) return
        rayNeoMicRouteActive = false
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                audioManager.setParameters("audio_source_record=off")
                Log.i(TAG, "RayNeo mic route released (async)")
            }.onFailure { Log.w(TAG, "Unable to release RayNeo mic route: ${it.message}") }
        }
    }

    private fun mergeLiveTranscript(existing: String, incoming: String): String {
        val prev = existing.trim()
        val next = incoming.trim()
        if (prev.isBlank()) return next
        if (next.isBlank()) return prev
        if (next.startsWith(prev)) return next
        if (prev.startsWith(next)) return prev
        if (next.contains(prev)) return next
        if (prev.contains(next)) return prev
        val maxOverlap = minOf(prev.length, next.length)
        for (n in maxOverlap downTo 1) {
            if (prev.endsWith(next.substring(0, n))) {
                return prev + next.substring(n)
            }
        }
        return "$prev $next"
    }

    private fun calculatePcm16Peak(data: ByteArray, size: Int): Int {
        if (size < 2) return 0
        var peak = 0
        var i = 0
        while (i + 1 < size) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF))
            val signed = if ((sample and 0x8000) != 0) sample - 0x10000 else sample
            val magnitude = abs(signed)
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak
    }

    private fun pushOscilloscopeLevel(level: Float, color: Int, force: Boolean = false) {
        if (!force && !isGeminiListeningOrThinking()) {
            return
        }
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastOscilloscopeUiUpdateMs < OSCILLOSCOPE_UI_THROTTLE_MS) {
            return
        }
        lastOscilloscopeUiUpdateMs = now
        runOnUiThread {
            if (!force && !isGeminiListeningOrThinking()) {
                chatFragment.hideVoiceOscilloscope()
                voiceOscilloscope?.stop()
                voiceOscilloscope?.visibility = View.GONE
                return@runOnUiThread
            }
            chatFragment.pushVoiceOscilloscope(level, color)
            // Legacy overlay view is intentionally disabled in favor of inline chat rendering.
            voiceOscilloscope?.stop()
            voiceOscilloscope?.visibility = View.GONE
        }
    }

    private fun hideOscilloscope() {
        lastOscilloscopeUiUpdateMs = 0L
        runOnUiThread {
            chatFragment.hideVoiceOscilloscope()
            voiceOscilloscope?.stop()
            voiceOscilloscope?.visibility = View.GONE
        }
    }

    /**
     * Handle speech recognition result.
     *
     * Flow:
     * 1. If the active panel implements TrackpadPanel, try injecting
     * ```
     *      text into its focused field via onTextInputFromHold().
     * ```
     * 2. If no panel consumed the text, route through Gemini for
     * ```
     *      intent routing / tool calls.
     * ```
     * Note: STT may run through Android SpeechRecognizer or Gemini audio transcription fallback
     * (device-dependent). Gemini still handles intent routing and tool-call dispatch after
     * transcript extraction.
     */
    private fun handleSpeechResult(text: String) {
        Log.d(TAG, "Speech result: $text")
        runOnUiThread {
            nativeSttFallbackTriggered = false
            viewModel.deactivateVoiceAssistant()
            showListeningOverlay(false)
            clearLiveSpeechPreview()
            clearListeningTranscript()

            val currentPanel = viewPager?.currentItem ?: MainViewModel.PANEL_CHAT

            // Chat panel: voice input always routes to Gemini directly —
            // the user expects a conversational response, not text sitting
            // in the EditText waiting for a manual Send tap.
            if (currentPanel == MainViewModel.PANEL_CHAT) {
                if (maybeRouteLocalIntentDirectly(text)) {
                    Log.d(TAG, "Chat panel — local intent routed through AITap tools")
                    return@runOnUiThread
                }
                Log.d(TAG, "Chat panel — routing voice input to Gemini")
                viewModel.routeWithToolCalls(text, latestFrame)
                return@runOnUiThread
            }

            // Other panels (Settings, Web): try injecting into the focused
            // text field first (e.g. dictating into an API key field).
            val panel = currentTrackpadPanel()
            val consumed = panel?.onTextInputFromHold(text) ?: false

            if (!consumed) {
                // No active text field — fall back to Gemini routing
                Log.d(TAG, "No active text field, routing to Gemini")
                viewModel.routeWithToolCalls(text, latestFrame)
            } else {
                Log.d(TAG, "Text injected into active panel field")
            }
        }
    }

    private fun handleSpeechPartial(text: String) {
        runOnUiThread {
            updateListeningTranscript(text)
        }
    }

    /** Handle speech recognition error. Deactivates voice assistant and shows HUD notification. */
    private fun handleSpeechError(errorCode: Int) {
        Log.e(TAG, "Speech error: $errorCode")
        runOnUiThread {
            viewModel.deactivateVoiceAssistant()
            showListeningOverlay(false)
            clearLiveSpeechPreview()
            clearListeningTranscript()

            val message =
                    when (errorCode) {
                        SpeechRecognizer.ERROR_NO_MATCH ->
                                "No speech detected. Hold trackpad ~1s, then speak clearly."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "Speech timed out. Try holding trackpad and speaking sooner."
                        SpeechRecognizer.ERROR_AUDIO ->
                                "Mic error — check microphone permission in Settings."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Network error — speech service needs internet."
                        SpeechRecognizer.ERROR_SERVER ->
                                "Speech server error. Try again in a moment."
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                                "Speech service disconnected — reconnecting..."
                        SpeechRecognizer.ERROR_CLIENT ->
                                "Speech client error. Restarting recognizer."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "Mic permission denied. Grant in system Settings."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                "Recognizer busy. Wait a moment and try again."
                        else -> "Voice error (code $errorCode)"
                    }

            val isTimeout =
                    errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                            errorCode == SpeechRecognizer.ERROR_NO_MATCH
            if (isTimeout) playVoiceTimeoutBeep()

            val shouldFallbackToGeminiLive =
                    USE_NATIVE_STT &&
                            (errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
                                    errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                    errorCode == SpeechRecognizer.ERROR_CLIENT ||
                                    errorCode == SpeechRecognizer.ERROR_SERVER ||
                                    errorCode == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                                    errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            if (shouldFallbackToGeminiLive &&
                    fallbackToGeminiLiveFromNativeStt("speech_error_$errorCode")) {
                return@runOnUiThread
            }

            showHudNotification(message)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Trackpad scroll/input forwarding to active panel
    // ══════════════════════════════════════════════════════════════════════

    /** Returns the current panel fragment cast to TrackpadPanel if applicable. */
    private fun currentTrackpadPanel(): TrackpadPanel? {
        return chatFragment as? TrackpadPanel
    }

    // ══════════════════════════════════════════════════════════════════════
    // ViewModel Observers
    // ══════════════════════════════════════════════════════════════════════

    private fun observeViewModel() {
        viewModel.apiKeyRequired.observe(this) { message ->
            if (message != null) {
                showHudNotification(message)
                viewModel.clearApiKeyRequired()
            }
        }

        viewModel.activePanelIndex.observe(this) { index ->
            if (index == MainViewModel.PANEL_WEB) {
                launchTapBrowser(viewModel.webNavigationUrl.value)
                viewModel.clearWebNavigation()
                return@observe
            }

            if (viewPager?.currentItem != MainViewModel.PANEL_CHAT) {
                viewPager?.setCurrentItem(MainViewModel.PANEL_CHAT, false)
            }
            handlePanelChanged(MainViewModel.PANEL_CHAT)
        }

        viewModel.voiceAssistantActive.observe(this) { active ->
            showListeningOverlay(active)
            syncCameraToGeminiState(active)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // HUD Notification (non-intrusive overlay)
    // ══════════════════════════════════════════════════════════════════════

    private fun showHudNotification(message: String) {
        val formatted =
                message.trim().ifBlank {
                    return
                }
        hudNotification?.apply {
            uiHandler.removeCallbacks(hideHudNotificationRunnable)
            animate().cancel()
            text = formatted
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(160).start()
            uiHandler.postDelayed(hideHudNotificationRunnable, HUD_NOTIFICATION_DURATION_MS)
        }
    }

    fun showMirroredNotice(message: String) {
        runOnUiThread { showHudNotification(message) }
    }

    // Intentionally no-op for privacy: user speech transcripts are not shown in chat/HUD.
    private fun showLiveSpeechPreview(text: String) = Unit

    private fun clearLiveSpeechPreview() {
        hudNotification?.apply {
            uiHandler.removeCallbacks(hideHudNotificationRunnable)
            animate().cancel()
            visibility = View.GONE
        }
    }

    private fun updateListeningTranscript(text: String) {
        val value = text.trim()
        if (value.isBlank()) return
        listeningTranscript?.apply {
            this.text = value
            visibility = View.VISIBLE
            alpha = 1f
            isSelected = true
        }
    }

    private fun clearListeningTranscript() {
        listeningTranscript?.apply {
            text = ""
            visibility = View.GONE
            isSelected = false
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Listening Overlay (Siri-style visual)
    // ══════════════════════════════════════════════════════════════════════

    private fun showListeningOverlay(show: Boolean) {
        listeningOverlay?.apply {
            if (show) {
                uiHandler.removeCallbacks(cameraIdleTimeoutRunnable)
                visibility = View.VISIBLE
                alpha = 0f
                animate().alpha(1f).setDuration(200).start()
                listeningTranscript?.visibility = View.VISIBLE
                listeningTranscript?.text = "Listening…"
                listeningTranscript?.isSelected = true
            } else {
                animate()
                        .alpha(0f)
                        .setDuration(300)
                        .withEndAction { visibility = View.GONE }
                        .start()
                listeningTranscript?.visibility = View.GONE
                listeningTranscript?.isSelected = false
            }
        }
    }

    private fun playVoiceActivateBeep() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, VOICE_ACTIVATE_BEEP_MS)
    }

    private fun playVoiceTimeoutBeep() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, VOICE_TIMEOUT_BEEP_MS)
    }

    // Screen mirroring functions removed – BinocularSbsLayout handles SBS rendering

}
