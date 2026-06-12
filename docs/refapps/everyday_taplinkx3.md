# Reference-app mining: Everyday + TAPLINKX3 (RayNeo X3 Pro)

Mined 2026-06-12 from shallow clones at `/tmp/ref-Everyday` (TheophileGaudin/Everyday) and
`/tmp/ref-TAPLINKX3` (informalTechCode/TAPLINKX3). Lineage cross-checked against
`/Users/me/Downloads/TapInsight-rebuild-6-11-26/tapbrowser`.

Shared-ancestry note up front: TAPLINKX3's README credits "originally created by u/glxblt76" —
the same person as Everyday's author (Théophile Gaudin / BuyMeACoffee "Glxblt76"). So all three
codebases (Everyday, TAPLINKX3, TapInsight tapbrowser) descend from one developer's X3 patterns.

---

# Repo 1: Everyday

## 1. What it is

A widget-dashboard / HUD app for the X3 Pro glasses (GPLv3 + commercial dual license), built on
the philosophy "what we want displayed most of the time is nothing." Three Gradle projects:
`Everyday_glasses` (the AR app: ~75 Kotlin files, draggable/resizable widgets — clock/battery,
weather, browser, text editor, screen mirror, news, finance, speedometer, Google Calendar,
live subtitles), `Everyday_phone` (companion: Bluetooth RFCOMM trackpad/keyboard server, screen
mirroring via MediaProjection, location/weather provider, Google OAuth broker), and
`Everyday_shared` (sync protocol). The glasses app is a single `MainActivity` hosting a
`BinocularView` root, a `WidgetContainer`, a cursor, plus a foreground `GlassesService` for
head-up wake while backgrounded. **Critically: it uses zero vendor SDK code** — pure AOSP APIs
plus one manifest meta-data flag.

## 2. X3-specific adaptations

### 2a. Launcher visibility WITHOUT the Mercury SDK
`Everyday_glasses/app/src/main/AndroidManifest.xml` — the app bundles **no AARs** at all. The only
RayNeo-specific artifact is the meta-data flag (without it the app doesn't appear in the glasses
launcher):

```xml
<meta-data
    android:name="com.rayneo.mercury.app"
    android:value="true" />
```

This is proof that the meta-data flag alone, not `MercurySDK.init()`, is what gates launcher
visibility. Everyday implements binocular rendering, temple input, and 3DoF entirely in
plain Android.

### 2b. Display geometry constants
`Everyday_glasses/.../binocular/DisplayConfig.kt` — canonical X3 numbers worth copying verbatim:

```kotlin
object DisplayConfig {
    // The glasses have a combined 1280x480 display:
    const val SCREEN_WIDTH = 1280
    const val SCREEN_HEIGHT = 480
    const val EYE_WIDTH = 640          // Left eye: x 0-639, Right eye: x 640-1279
    const val EYE_HEIGHT = 480
    // Refresh timing tiers
    const val REFRESH_INTERVAL_IDLE_MS = 0L        // No refresh when static
    const val REFRESH_INTERVAL_LOW_MS = 200L       // 5 FPS slow updates
    const val REFRESH_INTERVAL_NORMAL_MS = 33L     // 30 FPS interactive
    const val REFRESH_INTERVAL_HIGH_MS = 16L       // 60 FPS smooth animation
    const val MIN_PIXELCOPY_INTERVAL_MS = 16L      // PixelCopy overload guard
}
```

### 2c. BinocularView — dual-draw SBS root layout (best-in-class)
`Everyday_glasses/.../binocular/BinocularView.kt` is the root of `activity_main.xml`. It lays out a
`contentContainer` (left eye, 0–640) and a right-eye `SurfaceView` (640–1280), then in
DispatchDraw mode draws the SAME content twice in one frame — perfect temporal sync, zero copies:

```kotlin
override fun dispatchDraw(canvas: Canvas) {
    if (useDispatchDrawMode) {
        // Left eye (0-640)
        canvas.save()
        canvas.clipRect(0, 0, DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT)
        contentContainer.draw(canvas)
        canvas.restore()
        // Right eye (640-1280) - same content, translated
        canvas.save()
        canvas.clipRect(DisplayConfig.EYE_WIDTH, 0, DisplayConfig.SCREEN_WIDTH, DisplayConfig.EYE_HEIGHT)
        canvas.translate(DisplayConfig.EYE_WIDTH.toFloat(), 0f)
        contentContainer.draw(canvas)
        canvas.restore()
    } else super.dispatchDraw(canvas)
}
```

`binocular/MirrorStrategy.kt` makes the trade-off explicit as a strategy interface:
- `DispatchDrawStrategy` — default/preferred; "Does NOT work for Views that render to their own
  surface (WebView, SurfaceView, VideoView)".
- `PixelCopyStrategy` — double-buffered `PixelCopy` into the right-eye SurfaceView; "Works with
  ANY content including WebView … 1-frame latency, GPU→CPU→GPU round-trip overhead".

`binocular/BinocularRenderCoordinator.kt` then drives refresh rate by content class — widgets
register as STATIC / INTERACTIVE / MEDIA and the coordinator picks IDLE / 30 fps / media-rate,
including a **battery downshift** when MEDIA is active and battery is low. This
content-class-driven refresh is the single most copyable power-management idea in either repo.

### 2d. Temple trackpad = plain MotionEvents + ENTER/DPAD_CENTER for tap
`MainActivity.kt:1929-2010` ("Temple Trackpad Handling") + `InputCoordinator.kt`. The temple
touchpad arrives as ordinary `onTouchEvent`/`onGenericMotionEvent` MotionEvents on the Activity
(incl. HOVER_ENTER/MOVE/EXIT variants), and the temple *click* arrives as a key event:

```kotlin
override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    // Note: The glasses' hardware action button controls the display at the OS level
    // and is completely independent of our app's internal wake/sleep state machine.
    // We do NOT intercept or handle the hardware action button in this app.
    when (keyCode) {
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            notifyUserActivity()
            inputCoordinator.handleTap()
            return true
        }
    }
    return super.onKeyDown(keyCode, event)
}
```

`InputCoordinator` adds: two-finger = scroll mode (`ACTION_POINTER_DOWN`), tap detection
(distance < 50px AND duration < 300ms), double/triple-tap timers, asymmetric sensitivity
(`TEMPLE_SENSITIVITY_X = 1.0f`, `TEMPLE_SENSITIVITY_Y = 2.5f` — the temple pad is much narrower
vertically), and wake-on-any-touch while asleep.

### 2e. CursorStabilizer — temple-pad cursor physics
`CursorStabilizer.kt` (~140 lines, fully self-contained, ideal to copy). Four tricks tuned for a
fingertip on a skinny temple arm: (1) Y-reversal hysteresis (40px accumulator kills the "hook"
when reversing direction), (2) momentum clamp (max 25px jump from standstill, expands 3x/frame —
anti-teleport), (3) horizontal rail lock (|dx| > 5×|dy| zeroes dy), (4) anisotropic smoothing
(`SMOOTH_ALPHA_X = 0.85f`, `SMOOTH_ALPHA_Y = 0.45f`). "Phone trackpad uses direct 1:1 mapping and
bypasses this stabilizer."

### 2f. Head-up wake gesture (IMU, no SDK)
`HeadUpWakeManager.kt` — uses `TYPE_GAME_ROTATION_VECTOR` with `TYPE_ACCELEROMETER` fallback,
`SENSOR_DELAY_NORMAL`, run from the foreground `GlassesService` so it works with the app
backgrounded; wakes via `PowerManager.SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`
(`GlassesService.kt:111-118`). Key snippet — manual pitch to avoid getOrientation's ±90° clamp:

```kotlin
SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
// MANUAL PITCH: getOrientation clamps to -90/+90, so read the Z-axis
// projection directly from the rotation matrix (indices 7 and 8).
val pitchRad = atan2(rotationMatrix[7].toDouble(), rotationMatrix[8].toDouble())
val pitchDegrees = (pitchRad * RAD_TO_DEG).toFloat() - 90f  // mounting offset
processHeadPitch(pitchDegrees)   // threshold + hold-time then triggerWake()
```

### 2g. 3DoF pinning (IMU, no SDK)
`ThreeDofManager.kt` / `ThreeDofOverlay.kt` — `TYPE_GAME_ROTATION_VECTOR` (+ `TYPE_GYROSCOPE`) at
`SENSOR_DELAY_GAME`, `SensorManager.remapCoordinateSystem(AXIS_X, AXIS_Z)` for the head-mounted
orientation, then translates the 640x480 widget plane against head rotation. Proof that 3DoF
"anchored" UX on X3 is achievable with stock sensors (both this and TAPLINKX3 do it that way).

### 2h. Wake/sleep/dim model
`WakeSleepManager.kt` — three states OFF / WAKE / SLEEP (sleep shows only "pinned" widgets),
10 s default inactivity timeout. Brightness/dimmer is **window-level**, not system:
`MainActivity.kt:1017 layoutParams.screenBrightness = brightness.coerceIn(MIN_WINDOW_BRIGHTNESS, 1f)`;
adaptive brightness reads the on-glasses ambient light sensor
(`MainActivity.kt:953 getDefaultSensor(Sensor.TYPE_LIGHT)`).

### 2i. On-glasses GPS
`MainActivity.kt setupLocationUpdates()` — requests `LocationManager` updates from **all enabled
providers on the glasses themselves** (gps/network/fused/passive) for the speedometer widget, with
fallback polling. (Contrast TAPLINKX3, which pulls GPS from the phone over RayNeo IPC.) Implies
location providers are populated on-device when the RayNeo companion phone link is up.

### 2j. AGENTS.md — hardware spec sheet (verbatim, very citable)
`/tmp/ref-Everyday/AGENTS.md` "Target Device (RayNeo X3 Pro)": Snapdragon AR1 Gen1, 4 GB/32 GB,
MicroLED + diffractive waveguide, **up to 60 Hz**, **640x480**, 30° FOV, 27 PPD, peak 6000 /
avg 3500 nits, **12 MP main RGB + spatial camera (VGA)**, gyroscope/accelerometer/magnetometer +
**left-temple removal sensor**, dual speakers, **3 microphones (left temple, right temple,
front)**, Wi-Fi 6, BT 5.2, 245 mAh ≈3–5 h, 76 g, RayNeo AI OS 2.0.

## 3. Display strategy

Own SBS implementation, no Mercury SDK. `BinocularView` (custom ViewGroup, not FrameLayout) with
**dual-draw via dispatchDraw as the default** and PixelCopy→SurfaceView as the fallback for
surface-owning views, selected by a `MirrorStrategy` interface, plus content-class adaptive
refresh. Compared with the duplicate-draw FrameLayout approach: same core idea (draw left content
twice with clip+translate) but productionized — strategy switching, dirty-rect plumbing,
`onTrimMemory` hooks, and per-widget refresh budgets. This is the most evolved
duplicate-draw implementation of the three codebases.

## 4. Input strategy

Cursor-based throughout (CursorView + CursorStabilizer); **no Android focus traversal** — no
focus-based navigation anywhere. Two cursor sources: temple trackpad (raw MotionEvents,
stabilized) and phone trackpad (RFCOMM JSON events `down/up/move/tap/doubletap/tripletap/
pointercount`, 1:1 mapping × 1.5 sensitivity). Temple click = `KEYCODE_ENTER`/`DPAD_CENTER`.
Two-finger = scroll. Text entry comes from the phone keyboard over RFCOMM (plus a
`GlassesKeyboardView` for on-glasses entry). The system IME is actively suppressed
(`hideSystemKeyboard()`, `windowSoftInputMode="stateAlwaysHidden|adjustNothing"`).

## 5. Build facts

- Glasses app: `minSdk 24`, `targetSdk 34`, `compileSdk 34`, Kotlin/JVM 1.8, Gradle 8.7 workflow.
- **Vendor AARs: none.** (Major difference from TAPLINKX3/TapInsight.)
- Deps: androidx core/appcompat, material, androidx.credentials + play-services-auth +
  googleid (Google OAuth device flow), security-crypto. No OkHttp (uses HttpURLConnection),
  no Compose.
- License: dual GPL-3.0-only + commercial (`Everyday_glasses/LICENSE` is SPDX GPL-3.0-only).

## 6. Capability proofs / unknowns resolved

- **60 Hz**: confirmed as "up to 60 Hz" in AGENTS.md spec; code budgets 30 fps for interactive
  content and treats 16 ms as the PixelCopy floor (DisplayConfig).
- **Wearing detection**: "left-temple removal sensor" listed in the spec sheet, but **no code in
  either repo reads it** — still unproven in software.
- **Ambient light sensor exists and works**: `Sensor.TYPE_LIGHT` used for adaptive brightness.
- **On-glasses GPS providers**: used directly (speedometer).
- **Hardware action/power button**: explicitly NOT interceptable — comment says it "controls the
  display at the OS level," app deliberately ignores it.
- **Wake control**: `SCREEN_BRIGHT_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP` from a foreground service
  successfully turns the display on (head-up wake) — proof apps can programmatically wake.
- 6DoF / plane / depth / hand tracking / VGA-camera use: absent.

---

# Repo 2: TAPLINKX3

## 1. What it is

"TapLink X3" (Apache-2.0, v1.7.0, June 2026) — a dual-eye **browser shell** for the X3 Pro:
single WebView mirrored into a left-eye clip + right-eye SurfaceView preview, precision cursor,
custom radial/anchored keyboard, Groq-powered voice input and "TapLink AI" chat, bookmarks, QR
scanner, GlassApps store, 3DoF "Anchored Mode," and a companion phone app (`controller/` module)
that pairs over Bluetooth RFCOMM with a low-latency UDP lane (ports 37692/37693) for trackpad /
air-mouse / scroll. Two modules: `app` (glasses, 22 Kotlin files — `MainActivity.kt` is 6,943
lines, `DualWebViewGroup.kt` 8,551) and `controller` (phone, 4 files). Started by u/glxblt76,
maintained by InformalTech. Docs folder is unusually good (ARCHITECTURE / INPUT_SYSTEM /
anchored-mode / HISTORY) and includes a full **MercurySDK API reference**.

## 2. X3-specific adaptations

### 2a. Mercury SDK init + RayNeo IPC (the canonical bootstrap)
`app/src/main/java/com/TapLink/app/MyApplication.kt` (whole file) + manifest meta-data:

```kotlin
import com.ffalcon.mercury.android.sdk.MercurySDK

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MercurySDK.init(this)   // required before any other SDK capability
    }
}
```

`MainActivity.kt:71-72, 1461-1494` — GPS comes from the **phone** through RayNeo IPC, not from
on-glasses providers (requires the RayNeo AR companion app to have Location permission):

```kotlin
import com.ffalconxr.mercury.ipc.Launcher
import com.ffalconxr.mercury.ipc.helpers.GPSIPCHelper
// register:
ipcLauncher = Launcher.getInstance(this)
ipcLauncher?.addOnResponseListener(gpsResponseListener)
GPSIPCHelper.registerGPSInfo(this)
// response payload is JSON with mLatitude / mLongitude:
val jo = JSONObject(response.data)
val lat = jo.getDouble("mLatitude"); val lon = jo.getDouble("mLongitude")
runOnUiThread { dualWebViewGroup.injectLocation(lat, lon) }   // → JS geolocation shim
// teardown: GPSIPCHelper.unRegisterGPSInfo(this); ipcLauncher?.disConnect()
```

### 2b. Mic routing via audio_source_record — CAPABILITY PROOF
`MainActivity.kt:2228-2244` — exactly the vendor `AudioManager.setParameters` mic-mode switch the
guide marks unknown; mode `"voiceassistant"` (and `"off"`), applied before speech capture and
before granting WebView audio capture:

```kotlin
private fun setVoiceAssistantAudioRoute(enabled: Boolean) {
    val value = if (enabled) "voiceassistant" else "off"
    try {
        audioManager?.mode = AudioManager.MODE_NORMAL
        audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_UNMUTE, 0)
        audioManager?.setParameters("audio_source_record=$value")
    } catch (e: Exception) {
        DebugLog.e("AudioRoute", "Failed to set audio route: $value", e)
    }
}
```

Also wired into `onPermissionRequest` (`MainActivity.kt:4246-4310`): WebView
`RESOURCE_AUDIO_CAPTURE`/`RESOURCE_VIDEO_CAPTURE` are granted after runtime permission checks, and
the comment notes the route must be set "AFTER granting so WebView can initialise its audio
capture first." getUserMedia works on-glasses.

### 2c. Temple trackpad identified by input device name (cyttsp) — KEY PATTERN
`MainActivity.kt:5744-5780` (`dispatchTouchEvent`) — the X3 temple touchpads enumerate as Cypress
TrueTouch devices `cyttsp5_mt` and `cyttsp6_mt`; TapLink routes by `InputDevice.name`:

```kotlin
override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    val deviceName = ev.device?.name ?: InputDevice.getDevice(ev.deviceId)?.name
    if (deviceName?.contains("cyttsp5_mt", ignoreCase = true) == true) {
        ensureMouseTapModeDisabled()   // real temple input → leave mouse mode
    }
    if (ev.device?.name == "cyttsp6_mt") {
        // second temple arm: reserved for mode-toggle double taps
        templeDoubleTapDetector.onTouchEvent(ev)
        return true
    }
    autoEnterMouseModeForMudraInput(ev)   // "Mudra" ring → mouse-tap mode
    ...
}
```

`templeDoubleTapDetector` is a stock `GestureDetector` with `onDoubleTap → toggleMouseTapMode()`
(`MainActivity.kt:1130-1141`). This name-based demux (cyttsp = temple, "Mudra" = ring mouse,
generic pointer = mouse) is the cleanest multi-input-source pattern found.

### 2d. Anchored Mode (3DoF) — stock TYPE_ROTATION_VECTOR + quaternion math
`MainActivity.kt:1314-1340, 5290-5400`. Sensor: `Sensor.TYPE_ROTATION_VECTOR` at
`SENSOR_DELAY_GAME`. Pipeline worth copying: quaternion-relative orientation → Euler →
angular-offset translation (comment explains why raw quaternion components leak between axes) →
double exponential smoothing → **Choreographer-coalesced** single pending frame → deadband:

```kotlin
SensorManager.getQuaternionFromVector(currentQuaternion, event.values)
quaternionInverse(initialQuaternion!!, inverseInitialQuaternion)
quaternionMultiply(inverseInitialQuaternion, currentQuaternion, relativeQuaternion)
quaternionToEuler(relativeQuaternion, eulerAngles)
val deltaX = eulerAngles[0] * TRANSLATION_SCALE * 0.5f   // angular offsets, not raw
val deltaY = eulerAngles[1] * TRANSLATION_SCALE * 0.5f   // quaternion components
smoothedDeltaX = smoothedDeltaX * (1f - velocitySmoothing) + deltaX * velocitySmoothing
if (!pendingAnchorFrame) {                                // coalesce to one vsync update
    pendingAnchorFrame = true
    Choreographer.getInstance().postFrameCallback {
        pendingAnchorFrame = false
        if (/* deadband exceeded */) dualWebViewGroup.updateLeftEyePosition(
            smoothedDeltaX, smoothedDeltaY, smoothedRollDeg)
    }
}
```

Triple-tap re-centers (`shouldResetInitialQuaternion = true`). Manifest requests
`HIGH_SAMPLING_RATE_SENSORS`. Blank-screen "Media Mode" auto-disables anchoring to save power.

### 2e. Hardcoded 640x480-per-eye SBS layout + PixelCopy mirror loop
`DualWebViewGroup.kt:3726-3850` (`onLayout`: "Hardcoded eye resolution - 640x480 per eye";
`leftEyeClipParent.layout(0,0,640,480)`, `rightEyeView.layout(640,0,1280,480)`) and
`DualWebViewGroup.kt:3536-3630` (`captureLeftEyeContent`):

```kotlin
captureRect.set(0, 0, halfWidth, height)          // left half of the window
captureInFlight = true
PixelCopy.request(window, captureRect, bitmapToUse, { copyResult ->
    if (copyResult == PixelCopy.SUCCESS && isRefreshing) {
        synchronized(bitmapLock) { drawBitmapToSurface()   // → rightEyeView canvas
                                   lastCaptureTime = System.currentTimeMillis() }
    }
    captureInFlight = false
}, refreshHandler)
```

The loop is throttled (`refreshInterval` vs `minCaptureIntervalMs`), skips when a capture is in
flight, uses `Choreographer.postFrameCallback` at high rates, and piggybacks once-per-second
scrollbar-visibility checks on the capture tick. Cursor is drawn left-eye only
(`cursorRightView.visibility = GONE` at MainActivity.kt:1326-1328).

### 2f. Media keys + DPAD "meta mode"
`MainActivity.kt:5715-5742` — `KEYCODE_MEDIA_PLAY_PAUSE/PLAY/PAUSE` handled in `dispatchKeyEvent`
(phone/Bluetooth media buttons control fullscreen video); an optional "meta mode" converts
`KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT` + `KEYCODE_ENTER`/`KEYCODE_DPAD_CENTER` into synthesized
Arrow/Enter key events dispatched into the WebView (`MainActivity.kt:6629-6656`) — i.e., key-based
spatial navigation of web content as an alternative to the cursor.

### 2g. Brightness without WRITE_SETTINGS
`WRITE_SETTINGS` is commented out of the manifest; brightness slider writes
`window.attributes.screenBrightness` (`DualWebViewGroup.kt:6461`, `MainActivity.kt:531` boots at
0.1f) — same window-level approach as Everyday. README warns max brightness is problematic
("RayNeo limitation").

### 2h. Camera: camera2 + zxing for QR, photo→WebView upload
`MainActivity.kt:1666-1740` — `CameraManager`/`CameraDevice` with a 1920x1080 JPEG `ImageReader`
on a dedicated `HandlerThread`; captured JPEG is base64-injected into the page as a `File` via
`DataTransfer` JS (Google image search upload). QR scanning uses
`com.journeyapps:zxing-android-embedded:4.3.0` (`CameraPreview`). Main 12 MP RGB camera only —
**no use of the VGA/wide spatial camera anywhere.**

### 2i. MercurySDK_Skill_Reference_EN.md — bundled vendor API reference
`/tmp/ref-TAPLINKX3/MercurySDK_Skill_Reference_EN.md` is a complete English MercurySDK reference
(v0.2.4 series) — the best documentation artifact found. Documents: `MercurySDK.init`,
`MobileState.isMobileConnected(): Flow<Boolean>`, `BindingPair<B>` left/right layout mapping +
`updateView{}`, `make3DEffect(leftView, rightView, enable, parallax)` binocular parallax,
`BaseMirrorActivity<B : ViewBinding>`, `FToast`/`FDialog` binocular toast/dialog,
**`TempleAction` sealed gesture model + `TempleActionViewModel.state: SharedFlow<TempleAction>`**,
**focus management** (`FocusHolder`/`FocusInfo`, `FixPosFocusTracker`,
`RecyclerViewFocusTracker`, `RecyclerViewSlidingTracker`), `StartSnapHelper`,
`DeviceUtil.isX3Device()`. Also records the SDK's interaction conventions: slide = move focus,
single click = activate, double click = back. Note the launcher meta-data warning is restated
here. Copy this file into the guide's references.

## 3. Display strategy

Single logical 640x480 viewport; left eye is the real interactive view tree inside
`leftEyeClipParent`, right eye is a `SurfaceView` fed by a throttled `PixelCopy`-of-the-window
loop (2e). No Mercury `MirroringView`, no dispatchDraw dual-draw — the SDK is initialized but its
rendering classes are unused. Versus the duplicate-draw FrameLayout approach: PixelCopy handles
the WebView (which dual-draw cannot mirror) at the cost of one frame of right-eye latency and
CPU round-trips; Everyday's strategy-pattern hybrid is the synthesis of both. TapInsight later
extracted exactly this trade-off into `BinocularSbsLayout` (see §7).

## 4. Input strategy

Cursor-first, with a documented **focus-driven fallback** (docs/INPUT_SYSTEM.md): the custom
keyboard has (a) *anchored mode* — keyboard fixed in viewport, cursor projection "pokes" keys via
`handleAnchoredTap`, drags/flings suppressed; and (b) *free/focus mode* — horizontal drags/flings
move a focus highlight, tap fires `performFocusedTap()`. That focus mode is a **custom focus
highlight, not Android focus traversal** — neither app uses `requestFocus`/DPAD traversal for its
UI. True Android-style focus navigation on X3 exists only as the documented Mercury SDK pattern
(FocusHolder/FixPosFocusTracker, §2i) — a genuine alternative pattern for the guide, with the SDK
reference as proof, but with no open-source app exercising it here. Other input lanes: temple
gestures (single=click, double=back, triple=recenter/scroll-mode), Mudra ring → mouse-tap mode,
phone controller (UDP-preferred trackpad/air-mouse with per-frame coalescing; BT RFCOMM for
control), Groq speech-to-text, and notification-listener-driven media keys.

## 5. Build facts

- `app`: `minSdk 29`, `targetSdk 36`, `compileSdk 36`, AGP 8.13.2, Kotlin 2.1.0, viewBinding,
  output renamed `TaplinkX3.apk`. `controller` (phone): `minSdk 29`/`targetSdk 36`,
  applicationId `com.TapLinkX3.controller`.
- Vendor AARs in `app/libs/` — **identical filenames/versions to TapInsight's**:
  `MercuryAndroidSDK-v0.2.2-20250717110238_48b655b3.aar`,
  `RayNeoIPCSDK-For-Android-V0.1.0-20231128201840_9b41f025.aar`.
  (The bundled SDK doc describes the v0.2.4 line, so docs are slightly ahead of the AAR.)
- Notable deps: okhttp 4.12.0, gson, zxing-android-embedded 4.3.0, coroutines 1.10.0,
  androidx.webkit 1.15.0, **`com.google.ar:core:1.47.0` (ARCore) — declared but no source
  references found; appears vestigial, NOT evidence of ARCore working on X3.**
- License: Apache-2.0.

## 6. Capability proofs

- **Mic modes via `audioManager.setParameters("audio_source_record=voiceassistant"|"off")`** —
  proven, with ordering caveat (set after WebView permission grant). MainActivity.kt:2228-2244.
- **GPS over RayNeo IPC from phone** (`GPSIPCHelper`, JSON `mLatitude`/`mLongitude`) — proven.
- **WebView getUserMedia (camera+mic)** on-glasses — proven (onPermissionRequest grant path).
- **Glasses battery** read via standard `ACTION_BATTERY_CHANGED` (`SystemInfoView.kt`).
- **Temple pads = `cyttsp5_mt`/`cyttsp6_mt` input devices** — proven, enables per-arm routing.
- NOT found in either repo: CPU temperature reads, wearing-detection code, 6DoF, plane detection,
  depth, hand/gesture tracking, second VGA/160° camera, shortcut/power-button interception
  (Everyday explicitly documents the button as OS-owned).

## 7. Lineage vs TapInsight's tapbrowser — CONFIRMED ancestor

Compared against `/Users/me/Downloads/TapInsight-rebuild-6-11-26/tapbrowser/src/main/java/com/TapLink/app/`:

- **Same package + class roster.** TapInsight's `MainActivity.kt` and `DualWebViewGroup.kt`
  declare `package com.TapLinkX3.app` — identical to TAPLINKX3 — and share the core file set:
  `MainActivity`, `DualWebViewGroup`, `BookmarksView`, `ChatView`, `ColorWheelView`, `Constants`,
  `CustomKeyboardView`, `DebugLog`, `FontIconView`, `GroqAudioService`, `GroqInterface`,
  `MyApplication`, `NotificationService`, `SystemInfoView`, `WebAppInterface`. (TapInsight mixes
  in some files declared `package com.TapLink.app`, e.g. `BinocularSbsLayout.kt`.)
- **Growth:** MainActivity 6,943 → 17,741 lines; DualWebViewGroup 8,551 → 12,113. TapInsight adds
  `BinocularSbsLayout`, `LastUrlBridge`, `media/` (TTS clients, media library, interceptors),
  `unipanel/` bridges, caption stack (`DimCaptionEngine`, `YouTubeCaptionEnforcer`, …),
  `LockScreenView`, `BootIntroView`, `BrowserAgentSession`.
- **`BinocularSbsLayout` is a TapInsight addition, absent from TAPLINKX3.** It is the
  duplicate-draw FrameLayout ("first child measured to half the physical width, then drawn twice:
  left eye and right eye") with an optional, pref-gated Mercury
  `com.ffalcon.mercury.android.sdk.ui.wiget.MirroringView` path and graceful fallback to
  drawChild mirroring. So the evolution ran: TAPLINKX3 PixelCopy-inside-DualWebViewGroup →
  TapInsight extracted a clean SBS compositor and experimented with the official SDK widget.
- **Teaching-example verdict:** TAPLINKX3 is the better reference for the *PixelCopy mirror loop,
  cyttsp input demux, anchored-mode math, audio routing, and IPC GPS* — the same code exists in
  TapInsight but buried in files 2x the size. It is not "minimal," though (MainActivity ~7k
  lines); for a minimal SBS compositor, TapInsight's `BinocularSbsLayout` (~small, single
  purpose) or Everyday's `binocular/` package (cleanly factored, documented trade-offs) are the
  better classroom artifacts.
- Bonus provenance: TAPLINKX3 README — "It was originally created by u/glxblt76" — i.e., the
  Everyday author, which explains the near-identical RFCOMM device filtering
  (`!name.contains("rayneo")` appears in both repos: Everyday `RfcommClient.kt:448`, TAPLINKX3
  `ControllerBluetoothClient.kt:241`).

---

# Cross-repo summary for the guide

| Topic | Everyday | TAPLINKX3 |
|---|---|---|
| Vendor SDK | None (meta-data flag only) | Mercury v0.2.2 + RayNeoIPC V0.1.0 AARs (same files as TapInsight) |
| SBS rendering | dispatchDraw dual-draw default, PixelCopy strategy fallback, content-class refresh | PixelCopy(window left half)→right-eye SurfaceView, throttled |
| 3DoF | TYPE_GAME_ROTATION_VECTOR, SENSOR_DELAY_GAME | TYPE_ROTATION_VECTOR quaternions + Choreographer coalescing |
| Temple input | Raw MotionEvents + ENTER/DPAD_CENTER click, CursorStabilizer | Device-name demux (cyttsp5_mt/cyttsp6_mt), GestureDetector double-tap |
| Focus vs cursor | Cursor only | Cursor + custom keyboard focus mode; Android-focus pattern only in bundled Mercury SDK docs |
| GPS | On-glasses LocationManager | Phone GPS via GPSIPCHelper IPC |
| Mic modes | — | `audio_source_record=voiceassistant` proven |
| minSdk/target | 24 / 34 | 29 / 36 |
| License | GPLv3 + commercial | Apache-2.0 |
