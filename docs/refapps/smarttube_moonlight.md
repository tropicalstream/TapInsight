# Reference ports: SmartTube X3 Pro & Moonlight X3 — platform pattern mining

Sources (shallow clones, no git history — deltas located by grep):
- `/tmp/ref-SmartTube-RayNeo-X3-Pro` — github.com/oliverfederico/SmartTube-RayNeo-X3-Pro
- `/tmp/ref-moonlight-android-RayNeoX3` — github.com/informalTechCode/moonlight-android-RayNeoX3

---

## Repo 1: SmartTube — RayNeo X3 Pro Edition

### 1. What it is / what the port changed

A fork of SmartTube (Android-TV YouTube client, Leanback UI, ExoPlayer) adapted for the X3 Pro. The entire adaptation is **~6 new/touched files plus the manifest** — the Leanback app itself is untouched. The port (a) initializes the official RayNeo **Mercury Android SDK** (`com.ffalcon.mercury.android.sdk`), (b) intercepts temple-touchpad MotionEvents and **translates SDK gestures into synthetic DPAD KeyEvents** so the unmodified TV focus UI keeps working, (c) splits the 1280×480 logical screen into two 640×480 eyes using the SDK's `MirroringView`, (d) **spoofs DisplayMetrics to 640 px wide** so Leanback's scroll math works, and (e) forces TextureView for video because SurfaceView fights the mirror. Bonus: the porter committed the **vendor documentation dumps** in `smarttubetv/libs/rayneo_docs/` — a primary source for several "unknown" capabilities.

Fork-delta files:
- `smarttubetv/src/main/java/.../tv/ui/common/keyhandler/RayNeoInputInterceptor.java` (new, 263 lines)
- `smarttubetv/src/main/java/.../tv/util/RayNeoMirrorHelper.java` (new, 125)
- `smarttubetv/src/main/java/.../tv/util/RayNeoHardwareManager.java` (new, 75)
- `common/src/main/java/.../common/utils/RayNeoDeviceUtil.java` (new, 73)
- `common/src/main/java/.../common/misc/MotherActivity.java` (hooks added)
- `smarttubetv/src/main/java/.../tv/ui/main/MainApplication.java` (SDK init + lifecycle wiring)
- `smarttubetv/src/main/java/.../tv/ui/playback/mod/surface/SurfacePlaybackFragment.java` (TextureView force)
- `smarttubetv/src/main/AndroidManifest.xml` (launcher meta-data, overrideLibrary)
- `smarttubetv/libs/rayneo_docs/*` (vendor docs: specs, ARDK API, design spec, FAQ)

### 2. The headline pattern: focus-based TV UI **works without a cursor**

The whole input port is "Mercury gesture → DPAD keycode". Leanback never knows it's not on a TV.

**Gesture → KeyEvent table** (`RayNeoInputInterceptor.java`):

| Mercury SDK callback | Injected event |
|---|---|
| `onTPClick` | `performClick()` on focused view, fallback `KEYCODE_DPAD_CENTER` **+ `KEYCODE_ENTER`** |
| `onTPDoubleClick` | `KEYCODE_BACK` |
| `onTPSlideForward / Backward` | `KEYCODE_DPAD_RIGHT` / `KEYCODE_DPAD_LEFT` |
| `onTPSlideUpwards / Downwards` (X3-only) | `KEYCODE_DPAD_UP` / `KEYCODE_DPAD_DOWN` |
| `onTPSlideContinuous(delta, longClick, vertical)` | repeated DPAD every 45 px of accumulated delta |
| Left temple events (`MyTouchUtils.isLeft(event)`) | **ignored** — left pad is system volume/brightness |

Core dispatch (`RayNeoInputInterceptor.java`):

```java
mTouchDispatcher = new TouchDispatcher(TouchDispatcher.Source.Activity.INSTANCE);
// ...
public boolean onTouchEvent(MotionEvent event) {
    if (MyTouchUtils.isLeft(event)) return false;        // left temple = system controls
    if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) &&
        !event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) return false;
    mTouchDispatcher.onMotionEvent(event, mTouchCallback); // SDK gesture recognizer
    return true; // consume: prevent default pointer/drag behavior
}
```

Key-injection trick — DPAD events are swallowed in touch mode, so the port forces out of it (`RayNeoInputInterceptor.injectKeyEventAsync`, runs on a dedicated daemon thread because `Instrumentation` calls are synchronous):

```java
android.app.Instrumentation inst = new android.app.Instrumentation();
// Force out of touch mode so DPAD_CENTER isn't swallowed as a "wake from touch" event
inst.setInTouchMode(false);
inst.sendKeyDownUpSync(keyCode);
if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
    inst.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER); // some dialogs prefer ENTER
}
// fallback path: activity.dispatchKeyEvent(new KeyEvent(..., InputDevice.SOURCE_DPAD))
```

Continuous-scroll emulation (`handleContinuous`): accumulate `delta - lastDelta` per axis; every time `|accum| > 45f` inject one DPAD key and reset — turns a 1-D analog swipe into N discrete focus moves.

Activity hook (`MotherActivity.java` — both `dispatchTouchEvent` **and** `dispatchGenericMotionEvent`):

```java
// Let the RayNeo interceptor handle temple touch events first
if (mTouchEventInterceptor != null && mTouchEventInterceptor.onInterceptTouchEvent(event)) {
    return true;
}
return super.dispatchTouchEvent(event);
```

Wiring is zero-invasive: `MainApplication.registerRayNeoLifecycleCallbacks()` attaches a `RayNeoHardwareManager` (interceptor + mirror) to every `MotherActivity` via `registerActivityLifecycleCallbacks` — no per-activity edits. SDK init: `MercurySDK.INSTANCE.init(this)` in `Application.onCreate`, wrapped in try/catch so non-RayNeo builds still run.

### 3. Display strategy: SBS duplication + metrics spoof

The X3 exposes **one logical 1280×480 screen**; left half → left eye, right half → right eye (vendor doc: "a vanilla phone UI will tear in half"). Two complementary tricks:

**(a) DisplayMetrics half-width spoof** (`RayNeoDeviceUtil.applyDisplayMetrics`) — this is what makes Leanback itself usable:

```java
// Detect RayNeo X3 Pro: 1280×480 binocular display (640×480 per eye).
if (widthPixels <= 1280 && heightPixels <= 480) {
    metrics.density = 1.0f * uiScale;
    metrics.scaledDensity = metrics.density;
    metrics.densityDpi = (int) (160 * uiScale);
    // Trick Android into thinking the physical screen is only 640px wide.
    // Fixes Leanback's HorizontalGridView scroll math, which otherwise
    // assumes a 1280px screen and breaks left-to-right panning/focus.
    metrics.widthPixels = widthPixels / 2;
}
```

**(b) SDK `MirroringView` SBS duplication** (`RayNeoMirrorHelper.wrapContentView`, called from onStart/onPostCreate): shrink existing content to the left 640 px, add the SDK mirror as a sibling on the right — **no re-parenting**, which preserves TextureView surfaces:

```java
int halfWidth = dm.widthPixels; // already 640 thanks to the metrics spoof
mSourceView = contentFrame.getChildAt(0);
mSourceView.setLayoutParams(new FrameLayout.LayoutParams(
        halfWidth, MATCH_PARENT) {{ gravity = Gravity.LEFT; }});

mMirrorImageView = new MirroringView(activity);          // official SDK component
mMirrorImageView.setLayoutParams(new FrameLayout.LayoutParams(
        halfWidth, MATCH_PARENT) {{ gravity = Gravity.RIGHT; }});
mMirrorImageView.setElevation(1000f);
contentFrame.addView(mMirrorImageView);
mMirrorImageView.setSource(mSourceView);
mMirrorImageView.startMirroring();
```

Edge cases handled: `onPause()` draws the source view into a Bitmap snapshot ImageView over the mirror (MirroringView stops updating when paused); `onResume()` swaps back and frees the bitmap.

**(c) SurfaceView is incompatible with MirroringView** (`SurfacePlaybackFragment.onCreateView`):

```java
// On RayNeo X3 Pro, force TextureView mode — SurfaceView hardware layers
// get stolen by MirroringView and blank the left eye.
boolean isRayNeo = RayNeoDeviceUtil.isRayNeoDevice();
mVideoSurfaceWrapper = (!isRayNeo && !PlayerTweaksData...isTextureViewEnabled() && ...) ?
        new SurfaceViewWrapper(getContext(), root) : new TextureViewWrapper(getContext(), root);
```

→ ExoPlayer renders YouTube video into a **TextureView** on this device (cost: extra GPU composition pass, no direct-to-display path), so the mirror can read it.

**(d) Device detection** (`RayNeoDeviceUtil.detectRayNeo`): reflection on `com.ffalcon.mercury.android.sdk.util.DeviceUtil.isX3Device()` first, fallback to `Build.MANUFACTURER/BRAND/MODEL` contains `rayneo|ffalcon|mercury|x3 pro|x3pro`. Cached.

### 4. Build facts

- Mercury SDK consumed as a **local AAR**: `implementation(fileTree(dir: 'libs', include: ['*.aar']))` (`smarttubetv/build.gradle:192`). The AAR itself is **not committed** — `libs/` only contains `rayneo_docs/`. Vendor FAQ confirms why: the Maven repo is intranet-only, "Developers can directly use the AAR file (provided in the sdk file)". Consistent with the MercuryAndroidSDK-v0.2.2 AAR distribution model; exact AAR version not recoverable from the repo.
- Manifest: `<meta-data android:name="com.rayneo.mercury.app" android:value="true"/>` — **required for the app icon to appear in the glasses launcher** (per `quick_start.txt`). Plus `tools:overrideLibrary="...com.ffalcon.mercury.android.sdk"` to bypass the SDK's higher minSdk.
- versionName 31.45 / versionCode 2335; min/target/compileSdk indirected through `project.properties` defined in the `SharedModules` git submodule (absent in shallow clone; the f-droid flavor hardcodes `minSdkVersion 21`; `android.suppressUnsupportedCompileSdk=34`). NDK 21.0.6113669.
- License: **MIT** (upstream SmartTube's). Updater repointed: `common/src/stbeta/res/values/update_urls.xml` → `github.com/oliverfederico/SmartTube-RayNeo-X3-Pro/releases/latest/download/smarttube_beta2.json`.
- SDK requires ViewBinding (`buildFeatures { viewBinding true }`) for its BindingPair components — this port avoids that by using only `TouchDispatcher`/`MirroringView` directly (no `BaseMirrorActivity`).

### 5. Capability proofs from the bundled vendor docs (`smarttubetv/libs/rayneo_docs/`)

NOTE: these are vendor doc claims the porter committed, not exercised app code — but they are first-party RayNeo documentation.

From `x3_pro_specs.txt`:
- Snapdragon **AR1 Gen1**, 4 GB RAM + 32 GB ROM, RayNeo AI OS 2.0 (Android 12 per `ardk_android_overview.txt`).
- Display: 640×480 per eye, **up to 60 Hz**, 30° FOV, 27 PPD, 6000 nits peak / 3500 avg, MicroLED + diffractive waveguide.
- Cameras: **12 MP main RGB + spatial camera (VGA)** — second camera confirmed.
- **"Removal sensor: one on left temple"** — wearing detection hardware confirmed.
- 3 microphones (one per temple + one front), stereo speakers w/ reverse noise cancellation, IMU gyro+accel+mag, BT 5.2, Wi-Fi 6, 245 mAh (3–5 h), USB-C 2.0.

From `ardk_for_android/capabilities_and_api.txt`:
- **VGA spatial camera is exposed as Camera ID 1 via Camera2** — "specifically used for spatial positioning", with full size list captured from a real device log (2025-09-25): preview & picture sizes 640×480, 640×400, 640×360, 480×360, 352×288, 320×240, 320×180, 176×144.
- **Mic modes confirmed** (X2 doc, X3 "refer to specific implementations"): `AudioManager.setParameters("audio_source_record=sound|camcorder|translation|voiceassistant|off")` — `camcorder` = 2 temple mics no NC, `translation` = 3 mics ignoring wearer's voice, `voiceassistant` = prioritizes wearer; reset to `off` when done.
- 3D depth illusion: `make3DEffect(leftView, rightView, enabled, depth)` / `make3DEffectForSide(view, isLeft, enabled)` — binocular-parallax pixel offset ("control the depth of field" per FAQ).
- Focus framework: `FocusHolder` / `FocusInfo` / `FixPosFocusTracker` / `RecyclerViewSlidingTracker` / `RecyclerViewFocusTracker` / `IFocusable`; `TempleAction` Kotlin-Flow event stream (`Click/DoubleClick/TripleClick/LongClick/SlideForward/Backward/Upwards/Downwards/TpSlideContinuous`); X3 adds two-finger tap/long-press and `filterMode` (OnlyX/OnlyY). Natural vs non-natural swipe mapping is a **user setting** — don't hardcode swipe semantics.
- IMU via stock Android sensors incl. `TYPE_GAME_ROTATION_VECTOR`; phone link: `MobileState.isMobileConnected()`, GPS streaming from phone via IPC SDK.

From `developement_and_sdk_issues.txt` (mostly X2-era but platform-relevant):
- **6DoF**: "If using the 6dof function, [camera frames] can be obtained through ShareCamera API"; Unity SDK has 6dof/3dof samples. No ARCore/ARKit support. No plane-detection API mentioned anywhere.
- **No gesture (hand) interaction** ("high power consumption"), no system ASR/TTS, **no background running/multitasking** ("If you start other applications, the previous application will be forcibly killed").
- Thermal: total current > 500 mA causes overheating; advice: minimize lit pixels, brightness ~20, disable ALS.
- ADB sideload gate: `adb shell settings put global mercury_install_allowed 1`.

From `design_spec_for_ar_glasses.txt`:
- DoF modes: **0DoF recommended/default for all system apps**; 3DoF for panoramas ("self-righting mechanism" required); 6DoF "requires cameras and SLAM algorithms… high power consumption, short-term exploration only".
- **Shortcut button confirmed**: "Shortcut Key — One-touch Start / Recording — X3 Pro system button, located above the right temple, is **not available to developers**". Long-press on touchpad also system-reserved (dock bar).
- Power red lines: APL < 13% sustained / < 25% peak, UI at **30 fps** ("non-game applications do not require 60fps"), ≤ 4 logical threads, black #000000 = transparent, min font 16 px, lines ≥ 2 px, 16–30 px safety margin, virtual image distance ~2–5 m.
- Accessory input: X2 ring (ray-casting); X3 Pro pairs with a watch ("pinch to confirm, twist wrist to select").

---

## Repo 2: Moonlight Android — RayNeo X3 Pro Port

### 1. What it is / what the port changed

Fork of Moonlight (NVIDIA GameStream/Sunshine client — latency-critical H.264/HEVC/AV1 streaming) by informalTechCode. The port does **not use the Mercury SDK at all** — pure AOSP. Changes: every activity layout is wrapped in a `stereoRoot` with a hard-coded **640×480 px left-eye container** and a second 640×480 `SurfaceView` for the right eye; a **`PixelCopy`-based mirror loop at 16 ms** copies the left eye to the right; default stream settings changed to **640x480 @ 60 fps**; custom toasts/dialogs replace system UI that renders unreadably in-headset (README: "Custom in-app dialogs, confirmation prompts, loading spinners, and toasts"). The decoder pipeline is **completely untouched upstream Moonlight** — the significant finding being that it didn't need changes.

Fork-delta files: `app/src/main/java/com/limelight/Game.java` (stereo + mirror inlined), `utils/StereoMirrorController.java` (new, reusable), `PcView.java`/`AppView.java`/`StreamSettings.java`/`AddComputerManually.java`/`HelpActivity.java` (same stereo wiring for menus), `utils/ToastHelper.java` (new) + `res/layout/view_custom_toast.xml`, all `res/layout/activity_*.xml` (stereoRoot/leftEyeContainer/rightEyeSurfaceView), `preferences/PreferenceConfiguration.java` (defaults), `AndroidManifest.xml` (`com.rayneo.mercury.app` meta-data), `GPL_COMPLIANCE.md`/`THIRD_PARTY_NOTICES.md` (new).

### 2. Display strategy: SDK-free SBS with an asymmetric cost model

Layout (`res/layout/activity_game.xml`) — absolute pixels, real SurfaceView for the stream inside the left eye:

```xml
<FrameLayout android:id="@+id/stereoRoot" ...>
    <FrameLayout android:id="@+id/leftEyeContainer"
        android:layout_width="640px" android:layout_height="480px">
        <View android:id="@+id/backgroundTouchView" .../>
        <com.limelight.ui.StreamView android:id="@+id/surfaceView" .../>
        <TextView android:id="@+id/performanceOverlay" .../>
    </FrameLayout>
    <SurfaceView android:id="@+id/rightEyeSurfaceView"
        android:layout_width="640px" android:layout_height="480px"/>
</FrameLayout>
```

Decoder output pinned to one eye (`Game.java:1167-1170`):

```java
// Force output render size to one eye so both eyes stay pixel-identical.
// We intentionally bypass stream aspect matching here.
streamView.setDesiredAspectRatio(0);
streamView.getHolder().setFixedSize(EYE_WIDTH_PX, EYE_HEIGHT_PX); // 640x480
```

Right-eye mirror loop (`Game.java:690-755`, `RIGHT_EYE_MIRROR_FRAME_DELAY_MS = 16` → ~60 fps assumption; identical standalone version in `utils/StereoMirrorController.java`):

```java
// every 16ms, guarded by copyInProgress so copies never queue up:
Surface streamSurface = connected ? streamView.getHolder().getSurface() : null;
PixelCopy.OnPixelCopyFinishedListener finishListener = copyResult -> {
    if (copyResult == PixelCopy.SUCCESS && rightEyeMirrorActive) {
        Canvas canvas = rightEyeSurfaceView.getHolder().lockCanvas();
        if (canvas != null) canvas.drawBitmap(targetBitmap, 0f, 0f, null);
        rightEyeSurfaceView.getHolder().unlockCanvasAndPost(canvas);
    }
    rightEyeCopyInProgress = false;
};
if (streamSurface != null) {       // in-stream: copy decoder surface directly
    PixelCopy.request(streamSurface, targetBitmap, finishListener, rightEyeMirrorHandler);
} else {                           // menus: copy the window region of the left eye
    PixelCopy.request(getWindow(), leftEyeCaptureRect, targetBitmap, finishListener, rightEyeMirrorHandler);
}
```

Why this matters for the guide:
- The **left eye keeps Moonlight's zero-latency SurfaceView path** (hardware overlay, direct decoder output). Only the duplicated right eye pays the GPU-readback + software-canvas cost. Latency asymmetry between eyes is bounded at ≤1 mirror period (16 ms).
- `PixelCopy.request(Surface, …)` (API 24/26+) reads the **decoder's surface directly**, bypassing window composition — the menus fall back to the window-rect variant (API 26+; `start()` no-ops below `Build.VERSION_CODES.O`).
- Right-eye SurfaceView: `setFormat(PixelFormat.RGBA_8888)`, `setClickable(false)`, `setFocusable(false)`; bitmap reused (`ensureRightEyeBitmap`), recycled on surface destroy.
- `applyStereoLayout()` centers the 1280×480 stereo pair in whatever the window reports (`leftMargin = (rootWidth - 1280)/2`), so the same APK still renders sanely on a phone.
- This is exactly the "additional processing required for SurfaceView mirroring" case the RayNeo FAQ says the SDK does not handle ("the SDK only supports UI interface display and does not support video mirroring display") — the port's PixelCopy loop is the missing recipe, and the menus' window-rect variant replaces `BaseMirrorActivity` without the SDK or ViewBinding.

`StereoMirrorController.attach(activity)` (`utils/StereoMirrorController.java`) packages the whole thing — find `stereoRoot`/`leftEyeContainer`/`rightEyeSurfaceView` by id, attach surface callbacks, run the 16 ms loop. Drop-in for any activity whose layout follows the convention. (`EYE_WIDTH_PX=640, EYE_HEIGHT_PX=480, FRAME_DELAY_MS=16` at the top of the class.)

### 3. Decoder / performance: how hard the AR1 Gen1 is pushed

- **Zero decoder changes.** `MediaCodecDecoderRenderer.java` and `MediaCodecHelper.java` are upstream — i.e. the X3 Pro runs Moonlight's standard low-latency ladder unmodified:
  - `MediaCodecHelper.setDecoderLowLatencyOptions` (line 495): try `low-latency=1` (Android 11 `KEY_LOW_LATENCY` / `FEATURE_LowLatency`), then `vdec-lowlatency`, then Qualcomm vendor keys — `vendor.qti-ext-dec-picture-order.enable=1` and **`vendor.qti-ext-dec-low-latency.enable=1`** (lines 560-564; AR1 Gen1 is Qualcomm `c2.qti`/`omx.qcom`, line 227-230).
  - `Game.java`: `streamView.requestUnbufferedDispatch(...)` on API 30+ ("input events are buffered to be delivered in lock-step with VBlank") — keeps temple-pad/gamepad input out of VSync batching.
- **Targets** (`preferences/PreferenceConfiguration.java:72-73`): `DEFAULT_RESOLUTION = "640x480"`, `DEFAULT_FPS = "60"` — a non-standard resolution string added by the port (upstream defaults 1280x720/60; standard list `RES_480P = "854x480"` retained). `DEFAULT_FRAME_PACING = "latency"` (upstream value — prefer latency over smoothness).
- The resolution picker still offers **360p → 4K** (`res/values/arrays.xml:13-20`) and the decoder ladder is intact, so higher-than-native decode + downscale-to-640×480 remains user-selectable; the port's *demonstrated* operating point is 640×480@60 low-latency H.264/HEVC plus a concurrent 60 Hz RGBA8888 PixelCopy readback + software canvas blit (≈1.2 MB/frame, ~70 MB/s) — i.e. decode is nowhere near the chip's ceiling, and the mirror tax fits alongside it.
- Custom toast pipeline (`utils/ToastHelper.java` + `view_custom_toast.xml`): system toasts bypass the stereo wrap (rendered by SysUI, single-eye/teared) — any system-drawn UI (toasts, system dialogs, notification shade) must be re-implemented in-app to appear fused. README: "Updated interaction flows and overlays to avoid system UI paths that are hard to use in AR."

### 4. Input

- **No temple-gesture code and no Mercury SDK** — the port relies on the X3 temple pad emitting ordinary touch events and on Moonlight's existing **touchscreen-as-trackpad mode** (`DEFAULT_TOUCHSCREEN_TRACKPAD = true`, upstream): relative finger motion moves the remote mouse cursor; taps click. So Moonlight's answer to "cursor on a 1-D pad" is *the remote PC's cursor*, fed by raw relative deltas.
- All upstream input intact: gamepads (USB/BT, Xbox driver service), keyboards, `KEYCODE_BACK` handling. No key-remap table added.
- Implication for the guide: pointer-driven apps can survive on the temple pad only when the "pointer" is remote/virtual; SmartTube's gesture→DPAD bridge is the pattern for local UIs.

### 5. Build facts

- `app/build.gradle`: **minSdk 21, targetSdk 34, compileSdk 34**, versionName "12.1" (tracks upstream Moonlight v12.1), versionCode 314. NDK build for moonlight-core (JNI).
- Manifest: `com.rayneo.mercury.app=true` meta-data (glasses launcher visibility) — the **only** RayNeo-specific manifest entry; no vendor AARs, no extra permissions.
- License: **GPLv3** (`LICENSE.txt`) + a port-added `GPL_COMPLIANCE.md` (binary distribution checklist) and `THIRD_PARTY_NOTICES.md`.

### 6. Capability proofs

None beyond display/input behavior: confirms launcher meta-data requirement, confirms SBS is achievable **without** the vendor SDK on stock APIs (PixelCopy, API 26+; X3 runs Android 12), and confirms the platform sustains 60 Hz mirror + 60 fps low-latency decode concurrently. No 6DoF/camera/mic/sensor usage anywhere in the fork.

---

## Cross-cutting takeaways for the guide

1. **"Can focus-based TV UIs work without a cursor?" — Yes, proven.** SmartTube runs the entire Leanback UI unmodified via (a) gesture→DPAD synthesis with `Instrumentation.setInTouchMode(false)` and (b) the `metrics.widthPixels /= 2` spoof. The only UI-code change in the whole fork is the TextureView force.
2. **Two SBS recipes, opposite trade-offs.** SDK `MirroringView` (SmartTube): less code, but breaks SurfaceView (forces TextureView for video, slower path both eyes). DIY `PixelCopy` (Moonlight): more code, but keeps the latency-critical SurfaceView pristine for the left eye and confines the cost to the duplicate.
3. **Display contract:** one logical 1280×480 window; 640×480 per eye; 60 Hz ceiling; both ports hardcode 640/480/16 ms. Vendor guidance: UI at 30 fps, APL < 13%, black = transparent.
4. **Decoder headroom:** AR1 Gen1 runs Moonlight's full Qualcomm low-latency stack (`vendor.qti-ext-dec-low-latency.enable`) untouched at 640×480@60 with simultaneous 60 Hz GPU readback; options up to 4K left enabled (unverified).
5. **Vendor doc proofs bundled in SmartTube repo** (`smarttubetv/libs/rayneo_docs/`): VGA spatial camera = Camera2 ID 1 (640×480 max, full size list); wearing/removal sensor on left temple; 4 named mic modes via `audio_source_record=`; shortcut button above right temple is system-reserved; 6DoF only via SLAM/ShareCamera (high power, no ARCore); no plane-detection API documented; no background multitasking; >500 mA = thermal throttle.
