# RayNeo X3 Pro — HARDWARE + PLATFORM REFERENCE DOSSIER
Extracted from the TapInsight codebase (`/Users/me/Downloads/TapInsight-rebuild-6-11-26`), 2026-06-12.
Two modules: `app/` (package `com.rayneo.visionclaw`, the "visionclaw" AI/voice side) and `tapbrowser/` (Android **library** module, namespace `com.TapLinkX3.app`, the browser/launcher side). Ignore `_backup-tapinsight-unified/` and `_decompile/` (stale copies).

---

## 1. MANIFESTS

### 1a. `app/src/main/AndroidManifest.xml` (VERBATIM, complete)

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />

    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

    <!-- Unipanel v2: foreground service permissions for the
         GeminiSessionForegroundService. FOREGROUND_SERVICE_MICROPHONE
         is the Android 14+ flavour required to keep the AudioRecord
         open while visionclaw is backgrounded (which is its normal
         state after the launcher swap below). POST_NOTIFICATIONS is
         requested at runtime in MainActivity.onCreate. -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <!-- Phase 4d: FOREGROUND_SERVICE_CAMERA so the Service can drive
         CameraX while in foreground (Android 14+ enforcement). Pairs
         with foregroundServiceType="microphone|camera" on the Service
         element below. -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <uses-permission android:name="android.permission.READ_CALENDAR" />

    <!--
      Media access for the photos gallery's DCIM merge: lets the
      companion app + on-glasses gallery enumerate the RayNeo native
      Camera app's photos & videos (in /DCIM/Camera) alongside
      TapInsight's own /Android/data/com.rayneo.visionclaw/files/Media/
      Photos. READ_MEDIA_IMAGES / READ_MEDIA_VIDEO are the API 33+
      granular replacements for READ_EXTERNAL_STORAGE; the legacy
      permission stays declared with maxSdkVersion=32 so older
      Android builds still work.
    -->
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
    <uses-permission
        android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />

    <uses-feature android:name="android.hardware.camera" android:required="true" />
    <uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
    <uses-feature android:name="android.hardware.location.gps" android:required="false" />
    <uses-feature android:name="android.hardware.microphone" android:required="true" />

    <application
        android:name=".VisionClawApp"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:networkSecurityConfig="@xml/network_security_config"
        android:usesCleartextTraffic="true"
        android:supportsRtl="true"
        android:theme="@style/Theme.VisionClawRayNeo"
        tools:targetApi="31">

        <meta-data
            android:name="com.rayneo.mercury.app"
            android:value="true" />

        <!-- Removed ar_mode to enable binocular (both lenses) display.
             ar_mode restricts rendering to left lens only. -->

        <!-- Unipanel v2: visionclaw no longer holds the launcher
             intent filter — tapbrowser does. visionclaw is still
             exported so tapbrowser can warm-start it with
             EXTRA_TAPCLAW_WARM_START, and so the chat-side can be
             brought to the front explicitly if a future commit
             wires that path. -->
        <activity
            android:name=".MainActivity"
            android:configChanges="density|orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|keyboard|navigation"
            android:exported="true"
            android:launchMode="singleTask"
            android:screenOrientation="landscape"
            android:windowSoftInputMode="adjustNothing"
            android:theme="@style/Theme.VisionClawRayNeo" />

        <!-- Unipanel v2: foreground service that keeps the Gemini
             Live AudioRecord alive while visionclaw is in the
             background. Type=microphone is mandatory on Android
             14+. -->
        <service
            android:name=".core.session.GeminiSessionForegroundService"
            android:exported="false"
            android:foregroundServiceType="microphone|camera" />

    </application>

</manifest>
```

### 1b. `tapbrowser/src/main/AndroidManifest.xml` (VERBATIM, complete)

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <uses-feature
        android:name="android.hardware.camera"
        android:required="false" />
    <uses-feature
        android:name="android.hardware.location.gps"
        android:required="false" />
    <uses-feature
        android:name="android.hardware.microphone"
        android:required="false" />

    <application>
        <meta-data
            android:name="com.google.android.gms.version"
            android:value="@integer/google_play_services_version" />

        <meta-data
            android:name="com.rayneo.mercury.app"
            android:value="true" />

        <!-- Unipanel v2: tapbrowser is the launcher. Browser fills
             the screen on cold launch; the chat HUD overlay floats
             on top via Step 2c.* wiring. AR_APP category preserves
             the RayNeo launcher integration that lived on
             visionclaw. singleTask mirrors visionclaw's original
             setup so re-launches don't stack new instances. -->
        <activity
            android:name="com.TapLinkX3.app.MainActivity"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:exported="true"
            android:hardwareAccelerated="true"
            android:launchMode="singleTask"
            android:theme="@style/AppTheme.NoActionBar"
            android:windowSoftInputMode="adjustNothing">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="com.rayneo.intent.category.AR_APP" />
            </intent-filter>
        </activity>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

        <service
            android:name="com.TapLinkX3.app.NotificationService"
            android:exported="true"
            android:label="@string/app_name"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
    </application>

</manifest>
```

### 1c. Digest — what matters on the glasses

The merged APK is one app (applicationId `com.rayneo.visionclaw`) where the **tapbrowser Activity is the launcher** and carries the RayNeo-specific intent category `com.rayneo.intent.category.AR_APP` (RayNeo launcher integration). Both manifests declare `<meta-data android:name="com.rayneo.mercury.app" android:value="true"/>` — required for the Mercury SDK display path. Critically, an `ar_mode` meta-data entry was **removed** because it "restricts rendering to left lens only"; without it the app renders binocular (both lenses). Foreground-service mic/camera permissions (`FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_CAMERA`) plus `foregroundServiceType="microphone|camera"` on `GeminiSessionForegroundService` are mandatory on the device's Android 14 build to keep AudioRecord/CameraX alive while backgrounded; `POST_NOTIFICATIONS` is requested at runtime (without an actually-posted notification the FGS loses mic privilege). `HIGH_SAMPLING_RATE_SENSORS` is declared for the head-tracking rotation-vector sensor. Activities use `launchMode="singleTask"`, `screenOrientation="landscape"`, `windowSoftInputMode="adjustNothing"` (the system IME is never used — a custom in-scene keyboard is rendered instead), and `hardwareAccelerated="true"`. `usesCleartextTraffic="true"` + a network security config allow plain-HTTP radio streams. A `NotificationListenerService` mirrors phone notifications.

---

## 2. BUILD

| Item | Value |
|---|---|
| Gradle wrapper | `gradle-8.9-all` (`gradle/wrapper/gradle-wrapper.properties`) |
| AGP | 8.7.3, Kotlin 2.0.21, KSP `2.0.21-1.0.27`, secrets-gradle-plugin 2.0.1 (root `build.gradle.kts`) |
| JDK | 17 (`sourceCompatibility/targetCompatibility = JavaVersion.VERSION_17`, `jvmTarget = "17"` in both modules) |
| compileSdk / minSdk / targetSdk | 35 / 29 / 35 (both modules; tapbrowser has no targetSdk — library) |
| Root project name | `AITap`; `include(":app")`, `include(":tapbrowser")` (`settings.gradle.kts`) |
| Repos | google, mavenCentral, `https://maven.mozilla.org/maven2/` (FAIL_ON_PROJECT_REPOS) |
| gradle.properties | `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8`, `android.useAndroidX=true`, `android.nonTransitiveRClass=true` |

**Vendor SDK AARs (exact filenames, in `tapbrowser/libs/`):**
- `MercuryAndroidSDK-v0.2.2-20250717110238_48b655b3.aar`
- `RayNeoIPCSDK-For-Android-V0.1.0-20231128201840_9b41f025.aar`

Dependency pattern (the key trick): they are `compileOnly` in the library and provided at runtime by the app:

```kotlin
// tapbrowser/build.gradle.kts:76-77
compileOnly(files("libs/MercuryAndroidSDK-v0.2.2-20250717110238_48b655b3.aar"))
compileOnly(files("libs/RayNeoIPCSDK-For-Android-V0.1.0-20231128201840_9b41f025.aar"))

// app/build.gradle.kts:72-76
implementation(project(":tapbrowser"))
// TapBrowser runtime SDKs (compileOnly in :tapbrowser, provided here for runtime)
implementation(files("../tapbrowser/libs/MercuryAndroidSDK-v0.2.2-20250717110238_48b655b3.aar"))
implementation(files("../tapbrowser/libs/RayNeoIPCSDK-For-Android-V0.1.0-20231128201840_9b41f025.aar"))
```

**app module** (`app/build.gradle.kts`): `buildFeatures { buildConfig = true }`; namespace `com.rayneo.visionclaw`; versionName 1.1.2. Deps: core-ktx 1.15.0 (also `force`d in `configurations.all`), appcompat 1.7.0, material 1.12.0, constraintlayout 2.2.0, activity-ktx 1.10.0, fragment-ktx 1.8.6, lifecycle 2.8.7 (**incl. `lifecycle-service:2.8.7` — gives the FGS a LifecycleOwner so CameraX can bind to it**), recyclerview 1.4.0, viewpager2 1.1.0, room 2.6.1 (+ksp), **camera-core/camera-camera2/camera-lifecycle 1.4.1**, guava 33.4.0-android, coroutines 1.9.0, okhttp 4.12.0, gson 2.11.0, bouncycastle `bcprov-jdk18on:1.80` (TLS cert generation for companion server), **nanohttpd 2.3.1** (embedded HTTP server). A `GEMINI_API_KEY` BuildConfig field is generated manually (the secrets plugin emits invalid Java `= ;` for blank values — it's in the plugin `ignoreList`).

**tapbrowser module** (`tapbrowser/build.gradle.kts`): `com.android.library`, namespace `com.TapLinkX3.app`, `buildFeatures { viewBinding = true; buildConfig = true }`. Deps: webkit 1.12.1, **camera-view/camera-core 1.4.1** (hosts only the PreviewView SurfaceProvider; the Service in `app` owns the CameraX pipeline, surface passed across the binder), zxing core 3.5.3 + zxing-android-embedded 4.3.0, **media3-exoplayer 1.5.1 + media3-ui 1.5.1** ("MediaPlayer has a tiny fixed buffer that causes ~14s rebuffer stutters on MP3 streams"), okhttp, gson, coroutines.

---

## 3. BINOCULAR / SBS DISPLAY

Files:
- `app/src/main/java/com/rayneo/visionclaw/ui/BinocularSbsLayout.kt` (126 lines — pure drawChild version)
- `tapbrowser/src/main/java/com/TapLink/app/BinocularSbsLayout.kt` (219 lines — adds Mercury MirroringView path)
- Used as layout XML root: `app/src/main/res/layout/activity_main.xml:2` (`<com.rayneo.visionclaw.ui.BinocularSbsLayout>`), `tapbrowser/src/main/res/layout/tapbrowser_activity_main.xml:1` (`<com.TapLink.app.BinocularSbsLayout>`). NOTE: the tapbrowser class deliberately lives in package `com.TapLink.app` while the rest of the module is `com.TapLinkX3.app`.

**Concept**: a `FrameLayout` with exactly ONE child ("the logical viewport"). The child is measured to **half the physical width** and drawn **twice** — once at x=0 (left eye) and once translated by `logicalWidth` (right eye). Touches/generic motion landing in the right half are remapped back into logical space by `offsetLocation(-logicalWidth, 0)` (per-gesture, latched at ACTION_DOWN so a drag that crosses the seam stays consistent).

Core duplication strategy (identical in both modules — `app .../BinocularSbsLayout.kt:31-69`):

```kotlin
override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    val child = getChildAt(0) ?: return
    val logicalWidth = logicalViewportWidth(measuredWidth)      // = measuredWidth / 2
    val logicalHeight = measuredHeight.coerceAtLeast(0)
    child.measure(
        MeasureSpec.makeMeasureSpec(logicalWidth, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(logicalHeight, MeasureSpec.EXACTLY))
}

override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    val child = getChildAt(0) ?: return
    child.layout(0, 0, child.measuredWidth, child.measuredHeight)
}

override fun dispatchDraw(canvas: Canvas) {
    val child = getChildAt(0) ?: return
    val logicalWidth = logicalViewportWidth(width)
    val drawTime = drawingTime
    canvas.save(); canvas.clipRect(0, 0, logicalWidth, height)
    drawChild(canvas, child, drawTime); canvas.restore()
    canvas.save(); canvas.translate(logicalWidth.toFloat(), 0f)
    canvas.clipRect(0, 0, logicalWidth, height)
    drawChild(canvas, child, drawTime); canvas.restore()
}

override fun onDescendantInvalidated(child: View, target: View) {
    super.onDescendantInvalidated(child, target)
    invalidate()   // both halves must redraw whenever logical content changes
}
private fun logicalViewportWidth(totalWidth: Int) = (totalWidth / 2).coerceAtLeast(0)
```

Touch remap (`app .../BinocularSbsLayout.kt:71-115`): at `ACTION_DOWN`, `remapCurrentTouchSequence = ev.getX(0) >= logicalWidth`; while latched, `MotionEvent.obtain(ev).offsetLocation(-logicalWidth, 0)` is dispatched and recycled; `dispatchGenericMotionEvent` does the same per-event (no latch).

**Mercury SDK MirroringView path** (tapbrowser only, `tapbrowser .../BinocularSbsLayout.kt:10, 34-102, 215-218`). Import path **includes the vendor's 'wiget' typo**:

```kotlin
import com.ffalcon.mercury.android.sdk.ui.wiget.MirroringView   // sic: "wiget"
// gated by SharedPreferences("visionclaw_prefs").getBoolean("sdk_mirroring", false)
private fun attachSdkMirror() {
    val child = getChildAt(0) ?: return
    val mirror = MirroringView(context)
    mirror.setBackgroundColor(0)
    mirror.elevation = 1000f
    mirror.isClickable = false
    mirror.isFocusable = false
    addView(mirror)
    mirror.setSource(child)
    mirror.startMirroring()
    sdkMirrorView = mirror
}
// onDetachedFromWindow → (mirror as MirroringView).stopMirroring()
// onMeasure: sdkMirrorView measured with the SAME logical-width specs as the child
// onLayout:  mirror.layout(lw, 0, lw + mirror.measuredWidth, mirror.measuredHeight)  // right half
// dispatchDraw: when sdkMirrorView != null → just super.dispatchDraw(canvas) (no manual double-draw)
// onDescendantInvalidated: invalidate only the mirror (mirror.invalidate()) instead of self
// Any Throwable from MirroringView ⇒ remove it and fall back to the drawChild path.
```

Pref constant: `PREF_SDK_MIRRORING = "sdk_mirroring"` (default **false** — drawChild mirroring is the proven default).

**Per-eye pixel dimensions — 640 × 480 logical px per eye; full logical surface 1280 × 480.** Evidence:
- `app/src/main/java/com/rayneo/visionclaw/MainActivity.kt:777-782` forces density so dp==px at 160dpi:
  ```kotlin
  override fun attachBaseContext(newBase: Context) {
      val config = Configuration(newBase.resources.configuration).apply {
          densityDpi = DisplayMetrics.DENSITY_MEDIUM
      }
      super.attachBaseContext(newBase.createConfigurationContext(config))
  }
  ```
- `tapbrowser/.../DualWebViewGroup.kt` hardcodes the eye box everywhere: `FrameLayout.LayoutParams(640, 480) // Full left eye size` (line 145), `LayoutParams(640, MATCH_PARENT) // Left eye width only` (line 1710), container math `640 - toggleBarWidthPx // 608`, `480 - navBarHeightPx // 448` (lines 2205-2206), mask overlay fallback width 640 (line 4255).
- Eye center is **(320, 240)**; the cursor transform pivots there: `visualX = 320f + (x - 320f) * uiScale + transX` (`DualWebViewGroup.kt:1648-1649`).
- Cursor views: left at `x=320f, y=240f`, right at `x=960f, y=240f` (`tapbrowser/.../MainActivity.kt:2493-2525`) — i.e., 640 + 320 ⇒ total width 1280.
- `centerCursor()` resets to `lastCursorX = 320f; lastCursorY = 240f` (`tapbrowser/.../MainActivity.kt:890-894`).
- tapbrowser sizing uses `dp()` = `(this * resources.displayMetrics.density).roundToInt()` (`DualWebViewGroup.kt:6017`); nav bar 32dp, toggle bar 32dp, HUD lane 136dp — at density 1.0 these are equal px.
- Desktop-mode pages get viewport meta `width=1280` (`tapbrowser/.../MainActivity.kt:~2731`).

---

## 4. INPUT — the X3 control scheme

### 4.1 Input devices (kernel names — filter by NAME, never by InputDevice id)
From `tapbrowser/src/main/java/com/TapLink/app/MainActivity.kt:755-774` and `app/src/main/java/com/rayneo/visionclaw/MainActivity.kt:223-237`:

- **`cyttsp5_mt` = RIGHT-arm temple trackpad** (the "main touchpad"). Cursor motion arrives as `MotionEvent`s through `dispatchTouchEvent`, **but the physical click/tap arrives as a hardware KEY event** — `KEYCODE_BUTTON_A` / `KEYCODE_DPAD_CENTER` — through `dispatchKeyEvent`. A touch/GestureDetector-based tap detector never sees the right-arm click.
- **`cyttsp6_mt` = LEFT-arm pad** (system volume pad). Emits raw `MotionEvent`s through `dispatchTouchEvent`. Swiping it changes volume at the OS level; apps must filter it out of swipe gestures or volume swipes misfire as navigation.
- Names are matched with `contains("cyttsp6", ignoreCase=true)` to allow suffix variants for hardware revisions. `InputDevice.getDevice(ev.deviceId)?.name` is the lookup; IDs shuffle after reboot/USB reconnect, names are stable.

```kotlin
// app/.../MainActivity.kt:235-237 — copy these
private const val LEFT_ARM_DEVICE_NAME = "cyttsp6_mt"
private const val LEFT_ARM_TAP_MAX_MS = 300L
private const val LEFT_ARM_TAP_MOVE_TOLERANCE_PX = 30f
```

Gesture-role map (during a voice session): LEFT-arm single tap → toggle camera / activate voice; RIGHT-arm double tap → end session; triple-tap (main pad) → recenter (anchored) or scroll/cursor mode toggle (non-anchored).

### 4.2 How touch arrives
- `dispatchTouchEvent` receives both pads' `MotionEvent`s. tapbrowser routing order (`tapbrowser/.../MainActivity.kt:16518-16592`): lock screen → dim-mask GestureDetector short-circuit → device-name check: `cyttsp5_mt` → `ensureMouseTapModeDisabled()`; `cyttsp6*` → right-arm-touch double-tap check, then left-arm short-tap check, then `templeDoubleTapDetector.onTouchEvent(ev)`; **return true (temple events are fully consumed, never reach the WebView)**.
- `dispatchGenericMotionEvent` receives `ACTION_SCROLL` from pointer-class sources. App module (`app/.../MainActivity.kt:9413-9436`): `AXIS_HSCROLL`/`AXIS_VSCROLL` × `GENERIC_SCROLL_SCALE = 22f` (fallback `AXIS_RELATIVE_X/Y`), source check `SOURCE_CLASS_POINTER || SOURCE_MOUSE || SOURCE_TOUCHPAD`. Also handles `ACTION_BUTTON_PRESS/RELEASE` with `BUTTON_PRIMARY` for mouse-like devices.
- `dispatchKeyEvent` receives the temple-pad click keys (see 4.3) and media keys (`KEYCODE_MEDIA_PLAY_PAUSE/PLAY/PAUSE` handled at `tapbrowser/.../MainActivity.kt:16499-16514`).

### 4.3 Temple-pad KEY path (right arm) — timing-based double-tap on ACTION_UP
`tapbrowser/.../MainActivity.kt:7314-7333` (state+constants) and `7453-7500` (detector). **Copy these constants:**

```kotlin
// A single physical tap should release well within this; longer = a hold.
private val RIGHT_ARM_KEY_TAP_MAX_MS = 400L
// Ignore a "second tap" that arrives almost instantly — that's the same
// physical tap echoed as a second keycode, not a real double-tap.
private val RIGHT_ARM_KEY_DOUBLE_TAP_MIN_GAP_MS = 40L
// Upper bound between the two taps to count as a double-tap.
private val RIGHT_ARM_KEY_DOUBLE_TAP_WINDOW_MS = 320L
```

Detector shape (`consumedByRightArmKeyGeminiExitDoubleTap`, lines 7453-7500):

```kotlin
val isTapKey = event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
    event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER
if (!isTapKey) return false
when (event.action) {
    KeyEvent.ACTION_DOWN -> {
        if (event.repeatCount == 0) { rightArmKeyDownMs = SystemClock.uptimeMillis(); rightArmKeyTracking = true }
        return false      // NEVER consume DOWN — preserves the normal browser click
    }
    KeyEvent.ACTION_UP -> {
        if (!rightArmKeyTracking) return false
        rightArmKeyTracking = false
        val elapsed = SystemClock.uptimeMillis() - rightArmKeyDownMs
        if (elapsed >= RIGHT_ARM_KEY_TAP_MAX_MS) { rightArmKeyLastTapUpMs = 0L; return false }
        val now = SystemClock.uptimeMillis()
        val gap = now - rightArmKeyLastTapUpMs
        val isDoubleTap = rightArmKeyLastTapUpMs > 0L &&
            gap in RIGHT_ARM_KEY_DOUBLE_TAP_MIN_GAP_MS..RIGHT_ARM_KEY_DOUBLE_TAP_WINDOW_MS
        if (isDoubleTap) { rightArmKeyLastTapUpMs = 0L; /* fire action */; return true }
        rightArmKeyLastTapUpMs = now   // first tap — record and pass through
        return false
    }
}
```

This is checked FIRST in `dispatchKeyEvent` (line 16496) "so a double-tap can't be swallowed by the WebView / focused view"; only consumes when it actually fires. There is also a 1200 ms grace window (`GEMINI_EXIT_UNMASK_GRACE_MS`, line 7342) because the same physical double-tap can be observed by multiple detectors.

### 4.4 TrackpadGestureEngine.kt (app module) — API surface
`app/src/main/java/com/rayneo/visionclaw/core/input/TrackpadGestureEngine.kt` (335 lines). The reusable, self-contained gesture engine; fed from `dispatchTouchEvent` / `dispatchKeyEvent` / `dispatchGenericMotionEvent`.

```kotlin
enum class TouchSide { LEFT, RIGHT }

class TrackpadGestureEngine {
    companion object {
        const val SHORT_TAP_MAX_MS = 300L      // upper bound for a "short tap" (click)
        const val DOUBLE_TAP_WINDOW_MS = 280L  // single-vs-double tap window
        const val LONG_TAP_MIN_MS = 600L       // press-and-hold threshold
        // private: TAP_MOVE_TOLERANCE_RATIO = 0.04f, TAP_MOVE_TOLERANCE_MIN_PX = 18f,
        //          TAP_MOVE_GUARD_MS = 150L, TAP_MOVE_VELOCITY_GUARD_PX_PER_MS = 10f,
        //          EDGE_DEADZONE_PX = 0f  // disabled: X3 raw coords not always screen-normalized
    }
    var onShortTap: (() -> Unit)?                       // fires AFTER double-tap window expires
    var onDoubleTap: (() -> Unit)?
    var onLongTap: (() -> Unit)?
    var onScroll: ((deltaX: Float, deltaY: Float) -> Unit)?
    var onSideTouch: ((side: TouchSide) -> Unit)?       // LEFT/RIGHT half of pad on DOWN

    fun setScreenSize(width: Int, height: Int)          // call with displayMetrics w/h
    fun setScreenWidth(width: Int)
    fun onTouchEvent(event: MotionEvent): Boolean       // raw trackpad MotionEvents
    fun onKeyEvent(event: KeyEvent): Boolean            // KEYCODE_BUTTON_A / DPAD_CENTER path
    fun onGenericScroll(deltaX: Float, deltaY: Float): Boolean  // ACTION_SCROLL deltas
    fun release()                                       // clears Handler callbacks
}
```

Behavioural notes baked in: single-tap is deferred via `handler.postDelayed(singleTapRunnable, DOUBLE_TAP_WINDOW_MS)` and cancelled if a second tap lands; a tap is rejected when total movement > `max(0.04*minDim, 18px)`, when a scroll delta fired within the last 150 ms, or when peak move velocity ≥ 10 px/ms; `onGenericScroll` is ignored while a touch gesture is tracking (some pads emit both `ACTION_MOVE` and `ACTION_SCROLL` for one swipe — would double-fire). Wiring: `gestureEngine.setScreenSize(resources.displayMetrics.widthPixels, ...)` (`app/.../MainActivity.kt:1292-1296`), `dispatchTouchEvent → consumedByLeftArmTap(ev) || gestureEngine.onTouchEvent(ev)` (9328-9335), `dispatchKeyEvent → gestureEngine.onKeyEvent(event)` (9480-9488).

### 4.5 Cursor system (tapbrowser)
- Two 24×24 `ImageView`s (`cursorLeftView`/`cursorRightView`, `R.drawable.cursor_arrow_image`, `ScaleType.FIT_START` — "anchor to top-left for accurate click alignment"), `elevation = 1000f` so they render above every overlay ("same pattern as the SDK MirroringView at 1000f"). Left starts (320,240), right (960,240). (`tapbrowser/.../MainActivity.kt:2491-2531`.)
- Cursor moved by trackpad scroll deltas in the GestureDetector `onScroll` (lines 1938-1969): `dx = -distanceX * cursorGain; dy = -distanceY * cursorGain`; **first delta of each new gesture is dropped** (`e2.downTime != cursorScrollDownTime` — lifting and re-touching used to teleport the cursor); position clamped: `lastCursorX = (lastCursorX + dx).coerceIn(0f, maxW)`. Sensitivity: `cursorGain = 0.9f * (progress / 100f)` with default progress 50 → 0.45f (lines 527-530, 1213-1215). Cursor render is rate-limited to 16 ms (`CURSOR_UPDATE_INTERVAL`, `DualWebViewGroup.kt:178`) and rescheduled at 8 ms via `scheduleCursorUpdate()` (line 15884).
- **Single source of truth for where the user points** — `cursorInteractionPoint()` (`tapbrowser/.../MainActivity.kt:10469-10482`):

```kotlin
private fun cursorInteractionPoint(): Pair<Float, Float> {
    val groupLocation = IntArray(2)
    dualWebViewGroup.getLocationOnScreen(groupLocation)
    return if (isAnchored) {
        (320f + groupLocation[0]) to (240f + groupLocation[1])   // anchored = look-to-click at eye center
    } else {
        val scale = dualWebViewGroup.uiScale
        val transX = dualWebViewGroup.leftEyeUIContainer.translationX
        val transY = dualWebViewGroup.leftEyeUIContainer.translationY
        val visualX = 320f + (lastCursorX - 320f) * scale + transX
        val visualY = 240f + (lastCursorY - 240f) * scale + transY
        (visualX + groupLocation[0]) to (visualY + groupLocation[1])
    }
}
```

- **Synthetic click** — `dispatchTouchEventAtCursor()` (`tapbrowser/.../MainActivity.kt:10484-10770`). On a confirmed tap, walk an explicit hit-test chain in priority order (mask overlay → native-video controls → fullscreen overlay → dialogs → settings menu → restore button → chat → custom keyboard → bookmarks → windows overview → toggle/nav bar → scrollbar → unipanel overlay (3-state: transparent=fall-through, inert surface=consume, widget=synthetic DOWN+UP) → browser-panel re-show), and only then fall through to the WebView. Click debounce `MIN_CLICK_INTERVAL = 500L` (line 968). WebView dispatch (lines 10694-10744):

```kotlin
isSimulatingTouchEvent = true            // guard so the synthetic event isn't re-detected as a gesture
val webViewLocation = IntArray(2); webView.getLocationOnScreen(webViewLocation)
val translatedX = interactionX - webViewLocation[0]
val translatedY = interactionY - webViewLocation[1]
// anchored mode also un-rotates by leftEyeUIContainer.rotation before unscaling
val adjustedX = translatedX / scale
val adjustedY = translatedY / scale
val eventTime = SystemClock.uptimeMillis()
val motionEventDown = MotionEvent.obtain(eventTime, eventTime,
        MotionEvent.ACTION_DOWN, adjustedX, adjustedY, /*metaState=*/1)
    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
webView.dispatchTouchEvent(motionEventDown)
// ...matching ACTION_UP follows; plus evaluateJavascript("document.elementFromPoint(x,y)...")
```

Overlay sub-targets (dialogs etc.) get a local-coordinate DOWN+UP pair via `container.dispatchTouchEvent` after `getLocationOnScreen` + `/scale` conversion (lines 10527-10561).

### 4.6 Scroll mode vs cursor mode — triple-tap toggle
Constants at `tapbrowser/.../MainActivity.kt:1242-1248`:

```kotlin
private val TAP_INTERVAL = 400L        // Max time between consecutive taps
private val TRIPLE_TAP_DURATION = 800L // Max time for entire 3-tap sequence
```

Detection in the main GestureDetector's `onDown` (lines 1545-1600): count taps whose inter-arrival ≤ `TAP_INTERVAL`; on `tapCount == 3` within `TRIPLE_TAP_DURATION`, cancel any pending double-tap runnable, set `isTripleTapInProgress = true`, then:
- **anchored mode** → recenter: `shouldResetInitialQuaternion = true; dualWebViewGroup.updateLeftEyePosition(0f, 0f, 0f)`; toast "Screen Re-centered".
- **non-anchored** → toggle scroll mode: `toggleCursorVisibility(forceShow/forceHide)`; toasts "Cursor mode activated" / "Scroll mode activated, triple tap again to leave".
Pending double-tap actions are scheduled with a delay extended to outlive the remaining triple-tap window (+30 ms) and abort if `isTripleTapInProgress` (lines 2226-2256). In scroll mode (cursor hidden), trackpad drags scroll the page directly using `H2V_GAIN = 1.0f`, `X_INVERT = -1.0f`, `Y_INVERT = -1.0f` (lines 533-535: horizontal motion mapped onto vertical scroll, both axes inverted). Back-button priority: fullscreen → native video → keyboard/URL edit → hide cursor → system back (lines 1164-1189).

### 4.7 Dim-mode (masked) gesture detector + thresholds
`tapbrowser/.../MainActivity.kt:593-753` — a dedicated `GestureDetector` owns ALL input while masked: single-tap = play/pause (right arm only — left arm filtered via `ignoreFlingsFromDeviceNames = setOf("cyttsp6_mt")`, line 772), double-tap = exit dim (or exit Gemini first), horizontal fling = track skip (`absDx >= 80f && absDx > absDy * 1.4f && absVx > 250f`), vertical fling = visualizer open/close (`absDy > absDx && (absDy >= 30f || absVy > 120f)`). Because GestureDetector misclassifies vertical swipes on the narrow temple pad, a raw-delta tracker fires at **≥22 px** vertical-dominant travel (lines 793-819).

---

## 5. AUDIO

### 5.1 AudioTrack PCM streaming — `app/src/main/java/com/rayneo/visionclaw/core/audio/GeminiAudioPlayer.kt`
- Format: `ENCODING_PCM_16BIT`, `CHANNEL_OUT_MONO`, `MODE_STREAM`; `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`; buffer `max(minBuffer * 2, 4096)`. Sample rate parsed from mime (`Regex("rate=(\\d+)")` — Gemini sends `audio/L16;rate=24000`), `DEFAULT_SAMPLE_RATE = 24_000` (line 25).
- **The 16 KB non-blocking slice fix** (lines 26-33, 120-146): never issue one big `WRITE_BLOCKING` write — slice instead:

```kotlin
/** ~16 KB is ~170 ms at 24 kHz mono PCM16 — small enough that stopAndFlush()
 *  can break the write loop within ~200 ms, large enough to avoid per-slice overhead. */
private const val SLICE_BYTES = 16 * 1024
...
while (offset < data.size) {
    if (writeGeneration != myGeneration) { aborted = true; break }   // volatile cancel check
    val toWrite = minOf(data.size - offset, SLICE_BYTES)
    val wrote = track.write(data, offset, toWrite, AudioTrack.WRITE_NON_BLOCKING)
    if (wrote == AudioTrack.ERROR_DEAD_OBJECT) { /* release+rebuild track, resume at offset, max 3x */ }
    if (wrote == 0) { Thread.sleep(10); continue }                   // buffer full
    offset += wrote
}
```

- Cancellation: `@Volatile var writeGeneration`; `stopAndFlush()` bumps it OUTSIDE the lock, then `pause()+flush()` (lines 294-308). `DEAD_OBJECT` recovery (scrcpy/BT route flips kill the track): rebuild with same params, resume at current offset, cap 3 recoveries (lines 148-208).
- Drain detection: `notifyTurnComplete()` polls `playbackHeadPosition` every 50 ms; 6 consecutive identical reads (300 ms stall) = drained → `onDrainComplete` (lines 252-281). `isActivelySpeaking(windowMs=350)` uses PCM16 peak level.

### 5.2 Audio focus + ducking
- Request: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` via `AudioFocusRequest.Builder` (API 26+) with `USAGE_MEDIA/CONTENT_TYPE_SPEECH`; legacy `STREAM_MUSIC` fallback; abandon on stop/release (`GeminiAudioPlayer.kt:405-446`).
- Player-side focus-change: GAIN → `setVolume(1f)` + resume if paused; LOSS (exactly -1) → pause; **all transient/duck values (-2/-3) → keep speaking, briefly `setVolume(0.4f)`** — beware `change <= AUDIOFOCUS_LOSS` is TRUE for transient values too (the original bug) (lines 57-91).
- **The CAN_DUCK 30% pattern** (other side — media player reacting to the assistant), `tapbrowser/.../MainActivity.kt:1124-1140`:

```kotlin
private val nativeRadioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
    when (change) {
        // Gemini Live requests MAY_DUCK, so when it speaks we get CAN_DUCK.
        // KEEP PLAYING — just lower the radio so Gemini is clearly audible over it.
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
            runOnUiThread { runCatching { nativeRadioPlayer?.volume = 0.3f } }
        AudioManager.AUDIOFOCUS_GAIN ->
            runOnUiThread { runCatching { nativeRadioPlayer?.volume = 1.0f } }
        // Real transient loss (phone call, other exclusive audio) — pause.
        else -> if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            runOnUiThread { pauseNativeRadioStreamInternal(abandonFocus = false) }
        }
    }
}
```

ExoPlayer is built with `handleAudioFocus = false` ("we manage focus ourselves").

### 5.3 `android.media.Visualizer` attach — `tapbrowser/src/main/java/com/TapLink/app/BreathingVisualizerView.kt:211-243`

```kotlin
private fun attachVisualizer(sessionId: Int) {
    releaseVisualizer()
    val hasRecordPermission = context.checkSelfPermission(
        "android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED
    if (!hasRecordPermission) return            // degrade to breathing-only mode
    for (sid in listOf(sessionId, 0).distinct()) {   // try the player session, then global mix (0)
        try {
            val viz = Visualizer(sid)
            viz.setCaptureSize(Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024))
            val rate = Visualizer.getMaxCaptureRate().coerceAtMost(20000)
            viz.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, sr: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, sr: Int) {
                    if (fft != null) processFft(fft, sr)
                }
            }, rate, /*waveform=*/false, /*fft=*/true)
            viz.setEnabled(true)
            visualizer = viz; audioLive = true; attachedSessionId = sid
            return
        } catch (t: Throwable) { /* attach failed for this session — try next */ }
    }
}
```

Session id is supplied by the host via `dualWebViewGroup.audioSessionIdProvider = { nativeRadioPlayer?.audioSessionId ?: 0 }` (`tapbrowser/.../MainActivity.kt:728-731`). **Silent-session watchdog**: if the attached session produces no meaningful FFT for 2.5 s, fail over to session 0 (global output mix) (class doc, lines 41-42, `triedMixFallback`). Frame tick ~30 fps (`FRAME_MIN_INTERVAL_MS = 33L`). FFT magnitude normalized by `/181f`; bands at 20/60/250/2000/6000/16000 Hz.

### 5.4 TTS clients (tapbrowser, `media/`)
**`GlassesTtsClient.kt`** (RayNeo's WebView has no Web Speech API): `POST https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent`, header `x-goog-api-key`, body `{contents:[{parts:[{text: prompt}]}], generationConfig:{responseModalities:["AUDIO"], speechConfig:{voiceConfig:{prebuiltVoiceConfig:{voiceName}}, languageCode}}}`. `DEFAULT_MODEL = "gemini-2.5-flash-preview-tts"`, `FALLBACK_MODEL = "gemini-2.5-pro-preview-tts"`, voice `"Kore"`, lang `"en-US"`, `DEFAULT_SAMPLE_RATE = 24_000`, `MAX_CHARS = 2_400`, timeouts 10 s/60 s. Response = base64 `inlineData` L16 PCM with mime `audio/L16;rate=24000`; PCM is wrapped with a hand-built 44-byte RIFF/WAV header (`wrapPcmAsWav`, lines 323-346: PCM fmt 1, mono, 16-bit, little-endian) so the WebView `<audio>` can play it. Guardrail-400 ("should only be used for TTS") triggers ONE same-model retry with a reinforced narration prompt. Bytes go into `TtsCacheStore` (in-memory, `MAX_ENTRIES = 24`, id = CRC32+UUID8) and are served back at `/tts/<id>.wav` by `MediaFileInterceptor`. API key read from `SharedPreferences("visionclaw_prefs").getString("gemini_api_key", ...)` (`resolveGlassesGeminiKey`, lines 393-396).

**`FishTtsClient.kt`**: same `SynthesisResult` contract; `POST https://api.fish.audio/v1/tts`, auth `Authorization: Bearer <key>`, **`model` (`s2-pro`) sent as a request HEADER**, default format `mp3` ("wav is ~12× larger; dominates decode-start latency on the WebView <audio>"), `MAX_CHARS = 12_000`, `DEFAULT_LATENCY = "balanced"`. Returns raw audio bytes on success / JSON error envelope otherwise — branch on Content-Type.

**WAV→PCM strip** (companion side, for feeding TTS WAV into the AudioTrack): `app/.../core/session/GeminiVoicePipeline.kt:1713-1747` — walks RIFF chunks, reads sample rate from `fmt `, returns `data` payload; treats streaming-size sentinels (`size >= 0xFFFFFF00`) as "rest of buffer".

### 5.5 Mic capture (voice pipeline) — `app/.../core/session/GeminiVoicePipeline.kt:2528-2641, 2657`

```kotlin
private const val SAMPLE_RATE_HZ = 16_000          // mic capture; Gemini playback is 24 kHz
val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ,
    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
val bufferSize = maxOf(minBuffer * 2, 4096)
// Try VOICE_COMMUNICATION first, fall back to MIC:
val sources = intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                         MediaRecorder.AudioSource.MIC)
for (source in sources) {
    val rec = AudioRecord(source, SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
    if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
}
```

Read loop runs on a dedicated daemon thread ("GeminiVoicePipelineAudioThread") pushing `sendAudioChunkPcm16(chunk, read, SAMPLE_RATE_HZ)`.

---

## 6. VIDEO / MEDIA

### 6.1 ExoPlayer (media3 1.5.1) — radio/podcast streaming, `tapbrowser/.../MainActivity.kt:13460-13560`

```kotlin
val loadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(60_000, 120_000, 2_500, 5_000)  // min/max/forPlayback/afterRebuffer
    .build()
val player = ExoPlayer.Builder(this)
    .setLoadControl(loadControl)
    .setAudioAttributes(
        androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
        /* handleAudioFocus = */ false)        // focus managed manually (see §5.2)
    .setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
    .setMediaSourceFactory(
        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)       // stations bounce http<->https
                .setUserAgent("TapInsight/1.0")
                .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))))
    .build()
player.addListener(object : Player.Listener {
    override fun onMetadata(metadata: androidx.media3.common.Metadata) {
        for (i in 0 until metadata.length()) {
            val entry = metadata.get(i)
            if (entry is androidx.media3.extractor.metadata.icy.IcyInfo) {
                val title = entry.title?.trim()    // live "Artist - Title"
                if (!title.isNullOrBlank()) { NowPlayingBridge.updateTrack(title); ... }
            }
        }
    }
})
player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.playWhenReady = true
```

**Main-thread-only rule**: all player access from non-UI callbacks is wrapped in `runOnUiThread { nativeRadioPlayer?.volume = ... }` (e.g. lines 1131-1137); JS-interface bridge methods marshal via `Handler(Looper.getMainLooper()).post {}` (`media/MediaLibraryBridge.kt:854-857`). **Orphan-player guard**: a `@Volatile static staticNativeRadioPlayer: ExoPlayer?` lets a NEW Activity instance kill the previous instance's still-playing player on AR glasses where onDestroy may not run (lines 253-260, `stopOrphanedNativeRadioPlayer()` first thing in onCreate, line 1343). A second `nativeVideoPlayer: ExoPlayer` + media3 `PlayerView` handles HEVC gallery playback (line 1097).

### 6.2 Virtual asset host / request interceptor — `tapbrowser/src/main/java/com/TapLink/app/media/MediaFileInterceptor.kt`
Single virtual HTTPS origin (`shouldInterceptRequest`-based, NOT the stock `WebViewAssetLoader` — manual routing so the **Range header** is visible and 206/Content-Range partial responses work for `<audio>/<video>` seeking):

```kotlin
const val HOST = "appassets.androidplatform.net"
const val MEDIA_PREFIX = "/media/"             // on-glasses Media/ folder bytes (root-contained)
const val ASSETS_PREFIX = "/assets/"           // whitelisted APK asset pages only
const val TTS_PREFIX = "/tts/"                 // TtsCacheStore WAV/MP3 entries
const val DCIM_PREFIX = "/dcim/"               // MediaStore proxy: /dcim/<image|video>/<_ID>
const val LOCAL_PREFIX = "/local-image/"       // URL-encoded absolute path, DCIM-contained
const val LOCAL_VIDEO_PREFIX = "/local-video/" // DCIM-relative path (Chromium media loader is picky)
const val LOCAL_VIDEO_THUMB_PREFIX = "/local-video-thumb/"   // MediaMetadataRetriever JPEG
const val LOCAL_ALLOWED_ROOT = "/storage/emulated/0/DCIM/"   // containment root; no ".." allowed
private val ALLOWED_ASSET_PAGES = setOf("library_local.html", "media_player.html",
    "radio.html", "photos_gallery.html")       // narrow on purpose
private const val BUFFERED_RANGE_MAX_BYTES = 1 * 1024 * 1024
```

Rationale comments worth keeping: serving page + media from one origin avoids CORS-dropped sub-resources (and works with `allowFileAccess=false`); the WebView can't reliably load `content://` URIs on RayNeo's OEM WebView, hence the `/dcim/` proxy; RayNeo's WebView media stack throws `net::ERR_FAILED` when Chromium consumes very small tail Ranges from a streaming `WebResourceResponse` — small ranges (≤1 MB) are buffered into a `ByteArrayInputStream` to make probes deterministic; mime is sniffed from file extension. Media-root containment enforced through `MediaLibraryService.resolveSafe()`.

### 6.3 WebView config for the glasses
- Settings (`tapbrowser/.../DualWebViewGroup.kt:5245-5267`): `javaScriptEnabled`, `domStorageEnabled`, `databaseEnabled`, `useWideViewPort`, `loadWithOverviewMode`, `setSupportZoom(true)` + `builtInZoomControls` with `displayZoomControls=false`, **`mediaPlaybackRequiresUserGesture = false`**.
- Activity is `hardwareAccelerated="true"`; window adds `FLAG_HARDWARE_ACCELERATED` (`MainActivity.kt:1362-1365`); `setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)` (`VideoQualityHints.kt:156`).
- Viewport is locked per page-load "to avoid zoom-loop behavior on X3 trackpad": injected meta `width=1280, initial-scale=1.0, maximum-scale=1.0, user-scalable=no` (desktop mode) or `width=device-width,...` (`MainActivity.kt:~2730-2750`).
- Default UA contains the device id: `"Mozilla/5.0 (Linux; Android 14; X3-Pro; wv) AppleWebKit/..."`; the `"; wv"` embedded-WebView marker is stripped to dodge bot detection (`DualWebViewGroup.kt:6718-6742`).
- `VideoQualityHints.primeYouTubeHdCookie(...)` is called before any WebView exists — primes YouTube's "default to HD" cookie (single biggest WebView video win) (`MainActivity.kt:1352`).
- **JS bridge classes** (`addJavascriptInterface`): `MediaInterface` as `"MediaInterface"` (`DualWebViewGroup.kt:2682`); `MediaLibraryBridge` as `MediaLibraryBridge.JS_NAME` (`DualWebViewGroup.kt:2709`, `MainActivity.kt:12571`); `WebAppInterface` as `"GroqBridge"` (`MainActivity.kt:11699, 12559`) — also hosts `@JavascriptInterface exitImmersiveMode()/enterDimMode()` (lines 14155, 14199); an `"AndroidInterface"` object (`MainActivity.kt:12563`); `GroqInterface` as `"GroqBridge"` + `ChatInputBridge` as `"ChatInputBridge"` in `ChatView.kt:84-85`. The viewport screenshot hook is `com.TapLink.app.media.BrowserFrameHolder.attach(webView)` (`MainActivity.kt:2539`).

---

## 7. CAMERA — `app/src/main/java/com/rayneo/visionclaw/core/camera/FrameCaptureManager.kt`
CameraX (`camera-core/camera2/lifecycle 1.4.1`) driven from the **LifecycleService** (needs `lifecycle-service` artifact). API: `start(owner, previewSurfaceProvider?, onFrameBase64)`, `stop()`, `shutdown()`.

```kotlin
val analysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setTargetResolution(Size(1280, 720))     // CameraX picks closest sensor-supported size
    .build()
analysis.setAnalyzer(cameraExecutor) { imageProxy ->
    if (now - lastFrameMs < FRAME_INTERVAL_MS) { imageProxy.close(); return@setAnalyzer }
    val jpeg = imageProxyToJpeg(imageProxy)   // YUV_420_888 → NV21 → YuvImage.compressToJpeg
    imageProxy.close()
    onFrameBase64(Base64.encodeToString(jpeg, Base64.NO_WRAP))
}
// bind: DEFAULT_BACK_CAMERA, falls back to DEFAULT_FRONT_CAMERA; optional Preview use case
// companion: FRAME_INTERVAL_MS = 1100L; JPEG_QUALITY = 88
```

**rotationDegrees fix** (lines 102-110): `YuvImage` ignores `imageInfo.rotationDegrees`; sensor frames need **90° rotation on the X3 Pro** — decode the JPEG, `Matrix().postRotate(degrees)`, re-encode, or the model literally reports "the image is rotated". NV21 conversion handles row/pixel stride properly (lines 131-171). The companion app's `/api/camera/frame` endpoint pulls the latest JPEG via `cameraFrameProvider` (`CompanionServer.kt:86-87`). Preview on the browser side: tapbrowser hosts a `PreviewView` and hands its `SurfaceProvider` across the binder (`VoiceServiceApi.setCameraPreviewSurfaceProvider`).

---

## 8. SENSORS / IMU — anchored (head-locked) mode
All in `tapbrowser/src/main/java/com/TapLink/app/MainActivity.kt`. Sensor: `Sensor.TYPE_ROTATION_VECTOR` at `SensorManager.SENSOR_DELAY_UI` (lines 2807-2808, 15852-15858). Manifest declares `HIGH_SAMPLING_RATE_SENSORS`. Key constants (lines 1203-1227):

```kotlin
private val TRANSLATION_SCALE = 2000f  // Adjusted for better visual stability (approx 36 deg FOV)
private var smoothnessLevel = 40
private var anchorSmoothingFactor = 0.08f   // SLERP t; mapped 0.02..0.40 from slider (15913)
private var velocitySmoothing = 0.15f       // mapped 0.05..0.55 (15916)
private val MIN_FRAME_INTERVAL_MS = 8L      // ~120 FPS max (displays may be 90-120Hz)
private var shouldResetInitialQuaternion = false
```

Pipeline (`createSensorEventListener`, lines 15936-16015):
1. Quaternion order: `values[0..3] = (qx,qy,qz,qw)` → stored as `floatArrayOf(qw, qx, qy, qz)`.
2. SLERP-smooth against previous (`quaternionSlerp`, hemisphere-corrected, lines 5218-5252).
3. If `shouldResetInitialQuaternion || initialQuaternion == null` → capture current as the anchor, zero velocity state, return. (Triple-tap sets this flag — §4.6.)
4. `relativeQuaternion = quaternionInverse(initial) * active` (helpers at lines 5188-5215).
5. `deltaX = relativeQuaternion[1] * TRANSLATION_SCALE; deltaY = relativeQuaternion[2] * TRANSLATION_SCALE`; roll from `quaternionToEuler` (lines 16017-16040).
6. Double-exponential velocity smoothing, then `Choreographer.getInstance().postFrameCallback { if (isAnchored) dualWebViewGroup.updateLeftEyePosition(smoothedDeltaX, smoothedDeltaY, smoothedRollDeg) }` (vsync-aligned).

`updateLeftEyePosition(xOffset, yOffset, rotationDeg)` (`DualWebViewGroup.kt:5353-5393`) — **note the axis swap** (landscape device vs sensor frame):

```kotlin
leftEyeUIContainer.translationX = yOffset
leftEyeUIContainer.translationY = xOffset
leftEyeUIContainer.rotation = rotationDeg
// fullscreen overlay gets the same transform only while VISIBLE; else reset to 0
// anchored cursor is visually fixed at eye center: listener?.onCursorPositionChanged(320f, 240f, true)
```

Entering/leaving anchored mode (lines 15841-15881): register listener + `dualWebViewGroup.startAnchoring()` + `centerCursor()`; on exit `unregisterListener` then 50 ms-delayed `stopAnchoring()` so in-flight Choreographer frames drain. Pref keys: `"isAnchored"`, `"anchorSmoothness"` (Constants.kt).

---

## 9. SYSTEM UI

### 9.1 Immersive mode
- **app module** — `configureImmersiveDisplay()` (`app/.../MainActivity.kt:2215-2239`), the modern pattern:

```kotlin
WindowCompat.setDecorFitsSystemWindows(window, false)
window.apply {
    addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    statusBarColor = Color.TRANSPARENT; navigationBarColor = Color.TRANSPARENT
    attributes = attributes.apply {
        screenBrightness = viewModel.preferences.screenBrightness  // default 1.0f
    }
}
WindowInsetsControllerCompat(window, window.decorView).apply {
    hide(WindowInsetsCompat.Type.systemBars())
    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}
window.decorView.setBackgroundColor(Color.BLACK)
```

Re-applied in `onConfigurationChanged`. Brightness setter clamps 0.05f–1.0f (`setScreenBrightness`, lines 2254-2260).
- **tapbrowser** — legacy sticky-immersive flags, used when exiting CSS fullscreen (`exitImmersiveMode` JS bridge, `MainActivity.kt:14155-14181`): `SYSTEM_UI_FLAG_LAYOUT_STABLE | LAYOUT_HIDE_NAVIGATION | LAYOUT_FULLSCREEN | HIDE_NAVIGATION | FULLSCREEN | IMMERSIVE_STICKY`. Comment: on these glasses system bars are normally hidden app-wide; letting them reappear shifts the WebView's measured height and stales scroll metrics.

### 9.2 Keep-screen-on & brightness
`FLAG_KEEP_SCREEN_ON` while video plays (`tapbrowser/.../MainActivity.kt:13954, 14545`, cleared at 14598); `View.keepScreenOn = true` while masked (`DualWebViewGroup.maskScreen()`). tapbrowser sets initial `window.attributes.screenBrightness = 0.1f` in onCreate "to reduce power consumption" (`MainActivity.kt:1359`); user brightness comes via a settings slider writing `screenBrightness = clamped / 100f` (`DualWebViewGroup.kt:8398`). Pure black background everywhere — on the X3 Pro waveguides **pure black renders as fully transparent (see-through)** (`MainActivity.kt:146` comment), which is both the power trick and the AR aesthetic.

### 9.3 Dim mask overlay + caption slot — `tapbrowser/.../DualWebViewGroup.kt:4293-4366`
"Dim mode" = a full-screen black `maskOverlay` view brought to front (NOT brightness change — projector shows black = off). `maskScreen()`: idempotent; show overlay + `bringToFront()`, `keepScreenOn = true`, reset double-tap state, start 30 s clock/battery refresh, start the **dim caption ticker** (`dimCaptionTick` polling `@Volatile var dimCaptionProvider: (() -> String?)?`, line 1797 — fed by `DimCaptionEngine` which fetches LRCLIB synced lyrics keyed by the ICY track title), and `updateRefreshRate()`. `unmaskScreen()` reverses everything; the now-playing/caption `TextView`s go `INVISIBLE` not `GONE` so they keep non-zero measured size for the next entry. Entry point must be `MainActivity.handleMaskToggle()` (snapshots/hides cursor + HUD), not `maskScreen()` directly (line 14189 comment). Mirroring refresh throttles while masked: 16 ms normal → 1000 ms masked → 2000 ms masked+media (`DualWebViewGroup.kt:157-172`) because faster redraw starves the audio decoder (§12).

### 9.4 Boot / launch conventions
- tapbrowser MainActivity is LAUNCHER + `com.rayneo.intent.category.AR_APP`; `MercurySDK.init(application)` is called in `MyApplication.onCreate` AND defensively at the top of each Activity's `onCreate` **before `super.onCreate`** (`tapbrowser/.../MainActivity.kt:1345`, `app/.../MainActivity.kt:818-819`).
- Cross-activity warm-start handshake (the two Activities live in one task universe): extras `"tapclaw_warm_start"` (browser warm start) and `"visionclaw_warm_start"`; the warm-started side swaps to a translucent theme descended from its AppCompat theme (bare `Theme_Translucent_NoTitleBar` hard-crashes AppCompatActivity) and posts `moveTaskToBack(true)` (`app/.../MainActivity.kt:821-841`, `tapbrowser/.../MainActivity.kt:2541-2560`).
- tapbrowser binds the voice service by FQN string since it cannot import the app-module class: `GeminiSessionForegroundService.FQN = "com.rayneo.visionclaw.core.session.GeminiSessionForegroundService"` + `Intent.setClassName` (`GeminiSessionForegroundService.kt:807-812`).

### 9.5 Foreground service + notification — `app/.../core/session/GeminiSessionForegroundService.kt`
`LifecycleService` (so CameraX can bind to it). Bind alone does NOT grant mic; promotion happens inside `activateVoice()`:

```kotlin
private fun startForegroundIfNeeded() {            // lines 772-800
    if (foregroundActive) return
    ensureNotificationChannel(this)                 // CHANNEL_ID = "tapinsight_voice_session"
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("TapInsight assistant")
        .setContentText("Voice session active")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setSilent(true)
        .build()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(NOTIFICATION_ID,            // NOTIFICATION_ID = 0x76_3F_45
            notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } else {
        startForeground(NOTIFICATION_ID, notification)
    }
    foregroundActive = true
}
```

`onStartCommand` → `startForegroundIfNeeded(); return START_NOT_STICKY`. Local binder implements `com.TapLink.app.unipanel.VoiceServiceApi` (`activateVoice/shutdownVoice/currentState/toggleCamera/isCameraOn/setCameraPreviewSurfaceProvider/speakAgentReply/showAssistantCard`) — the cross-module API a tapbrowser-style shell uses. Cross-module state flows via static bridges in `tapbrowser/.../unipanel/`: `HudStateBridge`, `ChatCardBridge`, `CameraStateBridge`, `NowPlayingBridge`, `BrowserCommandBridge`.
- **onPause rule** (`tapbrowser/.../MainActivity.kt:4757-4767`): pressing the X3 Pro **sleep button triggers onPause** (OS powers the display down) but the user still wears the glasses — do NOT pause audio in onPause.

---

## 10. IPC / VENDOR SDK USAGE

**Mercury SDK** (`MercuryAndroidSDK-v0.2.2-...aar`) — classes actually used:
- `com.ffalcon.mercury.android.sdk.MercurySDK` → `MercurySDK.init(application)` (`tapbrowser/MyApplication.kt:12`, `tapbrowser/.../MainActivity.kt:1345`, `app/.../MainActivity.kt:819`; always `runCatching`-wrapped).
- `com.ffalcon.mercury.android.sdk.ui.wiget.MirroringView` (note 'wiget') → optional right-eye mirroring (§3).
- Manifest `<meta-data android:name="com.rayneo.mercury.app" android:value="true"/>` in both modules.

**RayNeoIPCSDK** (`RayNeoIPCSDK-For-Android-V0.1.0-...aar`) — used solely to pull GPS from the RayNeo launcher/companion-phone link (`tapbrowser/.../MainActivity.kt:91-92, 1230-1269, 4913-4946`):

```kotlin
import com.ffalconxr.mercury.ipc.Launcher
import com.ffalconxr.mercury.ipc.helpers.GPSIPCHelper

ipcLauncher = Launcher.getInstance(this)
ipcLauncher?.addOnResponseListener(gpsResponseListener)
GPSIPCHelper.registerGPSInfo(this)
// gpsResponseListener: Launcher.OnResponseListener { response ->
//     val jo = JSONObject(response.data)             // {"mLatitude":..,"mLongitude":..}
//     if (jo.has("mLatitude") && jo.has("mLongitude")) { ... inject into WebView ... }
// }
// teardown (GPS_IDLE_TIMEOUT_MS = 60000L of no use):
GPSIPCHelper.unRegisterGPSInfo(this)
ipcLauncher?.removeOnResponseListener(gpsResponseListener)
ipcLauncher?.disConnect()      // sic — vendor method name
```

`app/.../core/input/RayNeoArdkTrackpadBridge.kt` is only a reflection stub probing `Class.forName("com.rayneo.ardk.input.TrackpadManager")` — the ARDK low-level trackpad API was never linked; MotionEvent/KeyEvent fallback (§4) is the proven path.

---

## 11. COMPANION / WEB-UI PATTERN — `app/src/main/java/com/rayneo/visionclaw/core/config/CompanionServer.kt`
Embedded **NanoHTTPD 2.3.1** server, **port 19110**, reachable at `https://<glasses-ip>:19110` from any device on the same Wi-Fi. Skeleton a new app would reuse:

- `class CompanionServer(context, port: Int = 19110, ...providers) : NanoHTTPD(port)` (lines 74-88). Providers are injected lambdas (location, camera frame `(() -> ByteArray?)`, summaries) so the server stays decoupled.
- **HTTPS via self-signed cert** (`setupHttps`, lines 229-260): PKCS12 keystore (NOT Android KeyStore — NanoHTTPD's `SSLServerSocketFactory` needs the private key) persisted at `filesDir/companion_tls.p12` so users accept the warning once; needed because `window.isSecureContext` gates the browser Geolocation API (phone-GPS bridge). Migration deletes the keystore if the cert uses **RSA (too slow for TLS on the X3 Pro — causes audio stutters)** — i.e. use EC keys — or validity > 398 days. Falls back to plain HTTP on failure.
- **Session-token auth** (lines 415-457): token = `UUID.randomUUID()` stripped to 16 chars, persisted under prefs key `"companion_session_token"`. HTML pages are served WITHOUT auth but get the token injected (`__SESSION_TOKEN__` placeholder in bridge JS / `window.__companionToken` + a `fetch` monkey-patch adding `X-Session-Token`), plus `Set-Cookie: companion_session_token=...; Path=/; SameSite=Lax`. API requests accept any of: `Authorization: Bearer <token>`, `X-Session-Token` header, the cookie, or `?token=` query; otherwise 401 JSON.
- **Config whitelist idea** (lines 526-749): three maps of known keys + defaults (`intKeyDefaults`, `floatKeyDefaults`, boolean defaults) and a single `allowedKeys: Set<String>` — `GET /api/config` serializes only whitelisted keys from SharedPreferences (`"visionclaw_prefs"`); `POST /api/config` writes only whitelisted keys, type-coerced via the default maps. Routes dispatch in `serve()` (lines 763-790): unauthorized API → 401; `"/" → index`, `/browser`, `/dashboard`, `/radio` pages; `/api/config`, `/api/dashboard`, `/api/radio`, OAuth callback, `/api/camera/frame`, etc.
- A JS shim (`AiTapBridge` with `getString/putString/putFloat/putBoolean/putInt/applyConfig`) emulates an Android `JavascriptInterface` over REST so the same HTML pages run on-glasses (real bridge) and on a phone browser (REST shim) (lines 96-131).

---

## 12. PROVEN-ON-DEVICE FACTS (numeric truths from comments/code)

**Display**
- Per-eye logical viewport: **640 × 480 px**; full SBS surface **1280 × 480**; eye centers (320,240) / (960,240). (`DualWebViewGroup.kt:145,1710,2205-2206`; cursor views `MainActivity.kt:2493-2525`.)
- App forces `densityDpi = DENSITY_MEDIUM` (160 dpi ⇒ 1dp = 1px) (`app/.../MainActivity.kt:777-782`); tapbrowser sizes via real `displayMetrics.density`.
- "Displays may be 90-120Hz" → frame limiter `MIN_FRAME_INTERVAL_MS = 8L` (~120 fps) (`tapbrowser/.../MainActivity.kt:1224`).
- `TRANSLATION_SCALE = 2000f` ≈ **36° FOV** mapping for head-anchored translation (`tapbrowser/.../MainActivity.kt:1204-1205`).
- Pure black = transparent/see-through on the waveguide; black background is the "off" state (`tapbrowser/.../MainActivity.kt:146`).
- `ar_mode` manifest meta-data restricts rendering to the LEFT lens only — omit it for binocular (`app manifest:70-71`).
- WebView UA: `"Mozilla/5.0 (Linux; Android 14; X3-Pro; wv) ..."` → device model string **X3-Pro**, Android 14 (`DualWebViewGroup.kt:6721`).

**Input**
- `cyttsp5_mt` = RIGHT temple trackpad (cursor MotionEvents; click = `KEYCODE_BUTTON_A`/`KEYCODE_DPAD_CENTER` KEY); `cyttsp6_mt` = LEFT volume pad (raw MotionEvents). Filter by name; ids shuffle. (`tapbrowser/.../MainActivity.kt:755-774`.)
- Trackpad raw coordinates are **not always screen-normalized** (edge deadzone disabled for that reason; engine defaults assume ~2000×1200 until `setScreenSize` is fed real metrics) (`TrackpadGestureEngine.kt:42-49,78-79`).
- Tap/double-tap timing: engine 300/280/600 ms (short/double-window/long); right-arm KEY double-tap 400 ms tap-max, 40 ms min-gap (keycode echo!), 320 ms window; left-arm tap 300 ms / 30 px; triple-tap 400 ms inter-tap / 800 ms total; synthetic click debounce 500 ms.
- Dim-mode fling thresholds tuned on hardware: horizontal skip ≥80 px, dominance ×1.4, vx>250; vertical ≥30 px or vy>120; raw-delta vertical ≥22 px (GestureDetector misses vertical flings on the narrow pad).
- Cursor gain default 0.45 (`0.9 * slider/100`); first delta of each touch sequence must be dropped.

**Audio**
- Gemini Live playback PCM: **24 kHz mono 16-bit** (`audio/L16;rate=24000`); mic capture **16 kHz mono 16-bit** (`GeminiAudioPlayer.kt:25`, `GeminiVoicePipeline.kt:2657`).
- 16 KB ≈ 170 ms at 24 kHz mono PCM16 — the non-blocking slice size (`GeminiAudioPlayer.kt:26-32`).
- "2.5 s covers typical X3 AudioTrack buffering" (drain suppression window) (`GeminiVoicePipeline.kt:2660-2663`).
- Gemini TTS throughput on-device: 1800-char segment ≈ 120 s PCM ≈ 5.7 MB ≈ **15–18 s synth on-device**; 400-char segments start playback in ~2–4 s (`app/.../MainActivity.kt:209-220`).
- MediaPlayer's fixed ~336 KB buffer drains in ~14 s at 192 kbps → use ExoPlayer with 60 s/120 s buffers (`tapbrowser/.../MainActivity.kt:13470-13478`).
- 60 fps mirror refresh **starves the audio-decoder thread on the X3 Pro** (≈15-second skips in dim-mode local playback); masked refresh 1 Hz, masked+media 0.5 Hz (`DualWebViewGroup.kt:158-172, 5431`).
- RSA TLS handshakes are too slow on the X3 Pro and cause audio stutters — use EC certs, validity ≤398 days (`CompanionServer.kt:247-248`).
- Visualizer: capture size ≤1024, rate ≤20000 mHz, FFT-only; silent-session failover to session 0 after 2.5 s (`BreathingVisualizerView.kt:42,221-237`).
- RayNeo's WebView does NOT expose the Web Speech API (hence native TTS clients) (`GlassesTtsClient.kt:21-22`).
- Gemini Live requests focus with `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`; media should duck to **30%** on `CAN_DUCK`, never pause (`tapbrowser/.../MainActivity.kt:1126-1131`).

**Camera / sensors / system**
- Camera frames need **90° rotation on the X3 Pro** (`imageInfo.rotationDegrees`) (`FrameCaptureManager.kt:102-110`); capture 1280×720 @ ~1.1 s interval, JPEG q88.
- `TYPE_ROTATION_VECTOR` at `SENSOR_DELAY_UI` is sufficient for head anchoring with SLERP 0.02–0.40 + velocity smoothing 0.05–0.55; `HIGH_SAMPLING_RATE_SENSORS` permission declared.
- Sleep button press ⇒ `onPause` (display off) while audio should continue; explicit dim mode is a black overlay + `keepScreenOn`, brightness default boot value 0.1f, user clamp 0.05–1.0.
- FGS notification is mandatory or Android yanks mic privilege ("listening" hangs silently); `FOREGROUND_SERVICE_TYPE_MICROPHONE` (+`|camera`) and runtime `POST_NOTIFICATIONS` on Android 13+ (`app/.../MainActivity.kt:1664-1679`).
- AR glasses RAM pressure kills Activities without `onDestroy` — keep a `@Volatile` static ref to long-lived players to kill orphans on next launch (`tapbrowser/.../MainActivity.kt:253-260`).
- Companion server port **19110**; GPS IPC idle timeout 60 s; browser_vision screenshot "<5 ms on RayNeo X3 hardware" (`app/.../MainActivity.kt:10636`).
- OpenClaw client self-identifies as `CLIENT_DEVICE_FAMILY = "RayNeo X3 Pro"` (`OpenClawClient.kt:83`).

---

### Quick-start checklist for a NEW X3 Pro app
1. Manifest: `com.rayneo.mercury.app=true` meta, `AR_APP` launcher category, no `ar_mode`, landscape + singleTask + adjustNothing, hardwareAccelerated.
2. `MercurySDK.init(application)` in `Application.onCreate` (runCatching-wrapped).
3. Root layout = `BinocularSbsLayout` with ONE logical-viewport child; design for 640×480; optionally force `DENSITY_MEDIUM`.
4. Route input: `dispatchTouchEvent` (device-name routing cyttsp5/cyttsp6) + `dispatchKeyEvent` (`KEYCODE_BUTTON_A`/`DPAD_CENTER` = temple click) + `dispatchGenericMotionEvent` (ACTION_SCROLL ×22) → `TrackpadGestureEngine`.
5. Audio out: AudioTrack 24 kHz mono PCM16, 16 KB non-blocking slices, MAY_DUCK focus; mic: AudioRecord 16 kHz VOICE_COMMUNICATION→MIC.
6. Long-running mic/camera ⇒ LifecycleService + typed `startForeground` + posted notification.
7. Black background everywhere; dim = black overlay, not brightness; keep refresh work ≤1 Hz whenever audio matters.
