# The RayNeo X3 Pro Vibe-Coding Starter Guide

**For the next Fable session, and for any human who wonders how you program a pair of glasses.**

*Version 1.0 — June 12, 2026. Distilled from the TapInsight codebase (`~/Downloads/TapInsight-rebuild-6-11-26`), three days of on-device debugging, a decompiled APK, and one very educational data-loss incident.*

---

## How to read this document

This guide has two readers in mind, and it tries to be honest with both.

If you are a **person** curious about AR glasses, Parts I and II are written for you: a plain-language tour of how a computer the size of sunglasses paints light onto the world, and what we've measured about this particular pair. No programming required.

If you are **Fable** (or any AI/human developer) starting a new app for the RayNeo X3 Pro, this whole file is your steering prompt. Parts III–V are the build recipe, the reference patterns, and the compendium of mistakes already made so you don't make them twice. Part VI catalogs the working-app ecosystem — five real apps whose code proves the patterns. Part VII is the current mission: an object-tracking / anchoring proof of concept. Appendix A is the honesty ledger — for every claim in this guide, it says whether the claim was **tested on real hardware** or merely **read in someone's documentation**. Treat untested claims as hypotheses.

One principle governs everything here: **the device is the truth.** This guide exists because code comments, vendor docs, and intuition all disagreed with the glasses at some point, and the glasses always won.

A companion file, [`docs/x3_dossier.md`](docs/x3_dossier.md), holds the deep extraction — every constant, line reference, and verbatim snippet this guide summarizes. When this guide says "see dossier §4," that's where to look.

---

# Part I — The glasses, explained (for everyone)

## What the X3 Pro actually is

The RayNeo X3 Pro is a pair of augmented-reality glasses: an Android computer (Android 14, reporting itself as model `X3-Pro`) folded into an eyeglass frame. Unlike a phone, it has no screen you touch. Instead, each temple arm hides a tiny projector that shoots light into a **waveguide** — a sliver of patterned glass in each lens that bends the light toward your eye. The result is a translucent picture that appears to float in front of you, overlaid on the real world.

This leads to the single most important physical fact in this entire guide:

> **On a waveguide, black is not a color. Black is transparency.**

A phone screen makes black by showing dark pixels. A waveguide makes black by *not projecting anything* — so anywhere an app paints black, the user simply sees the world. Every well-behaved X3 app therefore lives on a pure black canvas: the black regions cost no power, draw no light, and leave reality visible. The app's UI floats as bright shapes on the void. "Dimming the screen" in TapInsight is literally a full-screen black view brought to the front — the projector switches off behind it, and the glasses become ordinary glasses with a tiny caption line floating where lyrics appear.

## Two eyes, one picture

You have two eyes; the glasses have two projectors. To make a flat UI appear comfortably in front of both eyes, the app draws its interface **once** into a logical canvas of **640 × 480 pixels**, and a special layout duplicates that canvas side by side — left copy for the left eye, right copy for the right — onto a combined 1280 × 480 surface. Your brain fuses the two identical images into a single, steady picture. (True 3D would show each eye a *slightly different* image; the X3 apps so far show the same one, which reads as a flat screen floating a couple of meters away.)

This duplication is about fifty lines of code (`BinocularSbsLayout`), and it's the foundation every X3 app stands on. The field of view is modest — the floating picture spans roughly **36°** of your vision — so think "elegant heads-up display," not "holodeck."

## How you control glasses with no screen

The temple arms are the controls. The **right arm** is a small trackpad (the kernel calls it `cyttsp5_mt`): sliding a finger moves a mouse-style cursor across the floating UI, and a firm tap *clicks* — but in a hardware quirk worth a paragraph of its own, the tap doesn't arrive as a touch at all. It arrives as a **keyboard key press** (`KEYCODE_BUTTON_A`), as if the glasses contained an invisible game controller. Apps that listen only for touches never hear the click.

The **left arm** (`cyttsp6_mt`) is the system volume pad, which apps must deliberately *ignore* for navigation — otherwise every volume swipe scrolls the page. TapInsight assigns it one job during voice sessions: a quick tap toggles the camera.

On top of taps live gesture conventions established across these projects: **double-tap** = cancel/close, **triple-tap** = switch between cursor mode and scroll mode (or re-center the view when head-anchored), **press-and-hold** = context action, vertical swipe in dim mode = open the audio visualizer. The timing windows that make these reliable were tuned on hardware and are recorded in Part II.

There is also a head-tracking trick: using the rotation sensor, an app can pin its window to a fixed direction in the room — turn your head left and the window slides right, staying "anchored" in space. The X3 reports head **rotation** beautifully (3 degrees of freedom). What it does *not* natively report is head **position** — walk forward and the anchored window doesn't know. Closing that gap (true 6-DoF tracking) is exactly what Part VII's proof of concept is about.

## The supporting cast

A 1280×720 camera looks out from the frame (its images arrive rotated 90°, a fact the code must correct). Speakers in the arms handle audio; a microphone array feeds voice assistants at 16 kHz. GPS is borrowed: the glasses ask their companion phone for coordinates over RayNeo's IPC link. And because typing on glasses is miserable, TapInsight runs a small **companion web server** (port 19110) on the glasses themselves — you configure the device from your phone's browser, over your own Wi-Fi.

That's the machine. The rest of this guide is what we *know* about programming it — with receipts.

---

# Part II — Platform ground truths

Everything in this section was **verified on the device** unless marked otherwise. Full code references: dossier §3–§9, §12. Official numbers come from RayNeo's MIT Reality Hack 2026 deck and the developer-manual exports in [`docs/rayneo-devguide/`](docs/rayneo-devguide/).

## Official spec sheet (RayNeo, Jan 2026)

| Item | Spec |
|---|---|
| Display | Binocular full-color **MicroLED** + waveguide, **640×480 @ 60 Hz**, 3D display supported, **3000 nits to eye**, 30° DFOV |
| Compute | **Qualcomm Snapdragon AR1 Gen1** (4-core 2.5 GHz, 4 nm), **4 GB RAM + 32 GB** storage |
| OS | "RayNeo AIOS based on Android 12" per the deck — but the device's own WebView UA reports **Android 14**; the OS has evidently moved since. Trust the device. |
| Cameras | RGB **12 MP** (1080p30 video) **plus a second VGA 640×480 camera with 160° FOV** for perception ("perception algorithm under evaluation") — exposed as **Camera2 ID 1** per the vendor docs bundled in the SmartTube port |
| Sensors | Accel + gyro, **magnetometer**, touch, **wearing-detection sensor** (left temple) |
| Audio | 3-mic **directional array** (noise + echo cancellation), 2 speakers |
| Power | 245 mAh, 3–5 h typical; vendor guidance: sustained draw >500 mA is thermal trouble — recommend ~30 fps UI, APL <13% |
| Misc | ~70 g, Wi-Fi, BT 5.3, USB 2.0; power + shortcut buttons (**shortcut button is system-reserved — apps cannot intercept it**) |

## Display

| Fact | Value | Status |
|---|---|---|
| Logical viewport per eye | **640 × 480 px** (design for this) | ✅ Tested |
| Full SBS surface | 1280 × 480; eye centers at (320,240) and (960,240) | ✅ Tested |
| Density | App forces `densityDpi = DENSITY_MEDIUM` → 1dp = 1px | ✅ Tested |
| Field of view | **30° DFOV** official ("like a 43-inch screen"); TapInsight's head-anchor mapping calibrated to ~36° by feel | ✅ Official + tested |
| Refresh rate | **60 Hz** (official spec — the in-code "90–120 Hz" guess was wrong; the 8 ms frame limiter is harmless headroom) | ✅ Official |
| Black = transparent | Pure black renders as see-through; black canvas is the off state | ✅ Tested |
| Binocular requirement | Do **not** declare `ar_mode` meta-data — it restricts rendering to the left lens | ✅ Tested |
| Outdoor readability | White text + black shadow survives daylight in dim mode (lyrics styling) | ✅ Tested |

## Input

| Fact | Value | Status |
|---|---|---|
| Right temple trackpad | device name `cyttsp5_mt`; cursor deltas via `dispatchTouchEvent` | ✅ Tested |
| Right-arm physical click | arrives as **KEY** event: `KEYCODE_BUTTON_A` / `KEYCODE_DPAD_CENTER` — never as touch | ✅ Tested |
| Left arm | `cyttsp6_mt`, system volume pad; filter it out of gestures | ✅ Tested |
| Device identification | match by **name** (`contains("cyttsp6", true)`); device IDs shuffle across reboots | ✅ Tested |
| Raw pad coordinates | not always screen-normalized — feed real `displayMetrics` into gesture engines | ✅ Tested |
| Key timing constants | tap ≤400 ms; double-tap gap 40–320 ms (the 40 ms floor filters keycode echo) | ✅ Tested |
| Gesture engine constants | short ≤300 ms, double window 280 ms, long ≥600 ms, move tolerance max(4%, 18px) | ✅ Tested |
| Triple-tap | ≤400 ms between taps, ≤800 ms total | ✅ Tested |
| Cursor | 24×24 ImageViews at `elevation = 1000f` (must outrank every overlay), gain default 0.45, drop the first delta of each touch sequence | ✅ Tested |
| Synthetic clicks | dispatched at cursor position through an explicit overlay hit-test chain, then into the WebView as `SOURCE_TOUCHSCREEN` DOWN+UP | ✅ Tested |

## Audio

| Fact | Value | Status |
|---|---|---|
| Assistant playback | AudioTrack, PCM16 mono **24 kHz** (`audio/L16;rate=24000`) | ✅ Tested |
| Mic capture | AudioRecord PCM16 mono **16 kHz**, `VOICE_COMMUNICATION` → `MIC` fallback | ✅ Tested |
| Big writes deadlock | never one blocking write — **16 KB non-blocking slices** (~170 ms each) with a volatile cancel generation | ✅ Tested (deadlock fixed on device) |
| Ducking etiquette | assistant requests `MAY_DUCK`; media reacts to `CAN_DUCK` by dropping to **30% volume, never pausing** | ✅ Tested |
| MediaPlayer vs ExoPlayer | MediaPlayer's ~336 KB fixed buffer rebuffers every ~14 s on MP3 streams; use media3 ExoPlayer with 60/120 s buffers | ✅ Tested |
| Render vs audio budget | 60 fps overlay refresh **starves the audio decoder** (15 s skips); throttle to 1 Hz when masked, 0.5 Hz masked+media | ✅ Tested |
| Web Speech API | absent from RayNeo's WebView — native TTS clients are mandatory | ✅ Tested |
| TLS cost | RSA handshakes stutter audio; use EC certs (companion server) | ✅ Tested |
| Music visualization | `android.media.Visualizer`, FFT-only, ≤1024 capture, session failover to 0 (global mix) after 2.5 s silence | ✅ Tested |
| Mic-array modes | vendor beamforming selector: `audioManager.setParameters("audio_source_record=<mode>")` — X3 modes per the official manual: `off` (MANDATORY on exit or the mic stays seized), `record_translation` (front mics, ambient, 16 kHz mono), `camcorder` (L+R temple mics, stereo ≤48 kHz), `voice_recognition` (3 mics, wearer-only, 16 kHz), `voice_communication` (wearer-only, calls). TAPLINKX3 ships the X2-era names (`voiceassistant`/`off`) in production code | ✅ Proven in TAPLINKX3 (X2 names) · 📄 X3 names per manual |

## Camera, sensors, system

| Fact | Value | Status |
|---|---|---|
| Camera frames | CameraX `ImageAnalysis`, 1280×720, JPEG q88, ~1 frame/1.1 s in TapInsight's duty cycle | ✅ Tested |
| Rotation | frames need **90° rotation** (`imageInfo.rotationDegrees`; YuvImage ignores it) | ✅ Tested |
| Head rotation | `TYPE_ROTATION_VECTOR` @ `SENSOR_DELAY_UI`, SLERP-smoothed, Choreographer-aligned — solid 3-DoF anchoring | ✅ Tested |
| Head **position** (6-DoF) | **no native API found or used** — nothing in this codebase tracks translation | ❓ Unknown / unproven |
| Second camera (VGA 160°) | the perception camera, Camera2 ID 1, max 640×480 — never used by any app surveyed | 📄 Claimed (vendor docs) |
| GPS | **no onboard GNSS** (official: X3 Pro relies entirely on the connected phone; the X2 had built-in GPS) — via RayNeoIPCSDK `Launcher` + `GPSIPCHelper` | ✅ Tested (IPC) + 📄 official |
| ADB access | OS ≥25.8.13: Glasses Settings → General → swipe to far left, trigger the "wall-collision" bounce **10×** to toggle ADB. Windows refusing the device = driver too new; fix with zadig (install WCID driver on "ADB Interface") | 📄 Official manual |
| Sleep button | triggers `onPause` (display off) while the user still wears the glasses — don't stop audio in `onPause` | ✅ Tested |
| Process death | RAM pressure kills Activities without `onDestroy`; keep `@Volatile` static refs to long-lived players and kill orphans on next launch | ✅ Tested |
| Mic in background | requires LifecycleService + `FOREGROUND_SERVICE_TYPE_MICROPHONE` + an actually-posted notification, or Android silently revokes the mic | ✅ Tested |
| Vendor SDK init | `MercurySDK.init(application)` in `Application.onCreate` *and* defensively before each Activity's `super.onCreate`, always `runCatching`-wrapped | ✅ Tested |

---

# Part III — Project skeleton (the build recipe)

## Toolchain that builds and ships today

| Item | Value |
|---|---|
| Gradle / AGP / Kotlin | gradle-8.9 · AGP 8.7.3 · Kotlin 2.0.21 (+KSP 2.0.21-1.0.27) |
| JDK | 17 |
| compileSdk / minSdk / targetSdk | 35 / 29 / 35 |
| Vendor AARs | `MercuryAndroidSDK-v0.2.2-20250717110238_48b655b3.aar`, `RayNeoIPCSDK-For-Android-V0.1.0-20231128201840_9b41f025.aar` — `compileOnly` in library modules, `implementation(files(...))` in the app |
| Key libraries | camera-core/camera2/lifecycle 1.4.1 · media3 1.5.1 · okhttp 4.12.0 · nanohttpd 2.3.1 · room 2.6.1 · coroutines 1.9.0 |
| Secrets | never in the repo; `buildConfigField` from `~/.gradle/gradle.properties` (the secrets plugin emits invalid Java `= ;` for blank values — ignore-list any key you generate manually) |

## The TapInsight APK manifest, verbatim

This is the **app module** manifest that ships on the glasses today (`app/src/main/AndroidManifest.xml`). Annotations are in the original comments — they are load-bearing documentation.

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

The launcher half lives in the **tapbrowser module** manifest (dossier §1b carries it verbatim). Its essentials: the launcher Activity declares the RayNeo category `com.rayneo.intent.category.AR_APP`, `hardwareAccelerated="true"`, and both modules carry `<meta-data android:name="com.rayneo.mercury.app" android:value="true"/>` for the Mercury display path.

## New-app checklist (proven order of operations)

1. Manifest: Mercury meta-data, `AR_APP` launcher category, **no `ar_mode`**, landscape + `singleTask` + `adjustNothing`, `hardwareAccelerated`.
2. `MercurySDK.init(application)` in `Application.onCreate`, `runCatching`-wrapped.
3. Root layout = `BinocularSbsLayout` with ONE logical-viewport child. Design at 640×480. Optionally force `DENSITY_MEDIUM` so dp == px.
4. Input plumbing: `dispatchTouchEvent` (route by device *name*: cyttsp5 vs cyttsp6) + `dispatchKeyEvent` (`KEYCODE_BUTTON_A`/`DPAD_CENTER` = temple click) + `dispatchGenericMotionEvent` (ACTION_SCROLL × 22) → `TrackpadGestureEngine` (copy it — it's self-contained, dossier §4.4).
5. Audio out: AudioTrack 24 kHz mono PCM16, 16 KB non-blocking slices, `MAY_DUCK` focus. Mic: 16 kHz, `VOICE_COMMUNICATION` → `MIC`.
6. Anything long-running with mic/camera: `LifecycleService` + typed `startForeground` + a real notification.
7. Black background everywhere. Dim = black overlay, never brightness. Keep refresh work ≤1 Hz whenever audio matters.

## Working agreements (the vibe-coding protocol)

These are process truths from the sessions that built TapInsight — they steer *how* Fable works, not what it builds:

- **Mars runs all `git push`, `./gradlew`, and `adb` commands** in his own terminal. Fable prepares edits and hands over a single copy-paste command block. The `&&` between build and install matters — a failed build must not install a stale APK.
- **You cannot run the build.** Verify every Kotlin edit with the brace/paren/comment-depth balance check against `git show HEAD:<file>` (strip strings and comments first; deltas before/after must match). Lint HTML by extracting `<script>` blocks and running `node --check`.
- **Commit and push at every milestone.** This habit was paid for with a near-total data loss. A stale `.git/HEAD.lock`/`index.lock` recurs — `rm -f .git/HEAD.lock .git/index.lock` before each commit.
- **Trace before editing.** Grep the symptom, read the region, name the root cause, then edit. The codebase rewards archaeology: most "new" bugs are a missing guard on a path someone else already guarded.
- **The decompile habit:** when source and device disagree, `jadx` the installed APK and compare. The last good build is always a recoverable artifact.

---

# Part IV — Reference patterns (copy these, don't reinvent)

Each pattern below is shipped, on-device-proven code. The guide shows the essence; the dossier carries the full verbatim version with line references.

## 1. Binocular rendering — `BinocularSbsLayout` (dossier §3)

A `FrameLayout` with one child measured at half width and drawn twice:

```kotlin
override fun dispatchDraw(canvas: Canvas) {
    val child = getChildAt(0) ?: return
    val logicalWidth = width / 2
    canvas.save(); canvas.clipRect(0, 0, logicalWidth, height)
    drawChild(canvas, child, drawingTime); canvas.restore()
    canvas.save(); canvas.translate(logicalWidth.toFloat(), 0f)
    canvas.clipRect(0, 0, logicalWidth, height)
    drawChild(canvas, child, drawingTime); canvas.restore()
}
override fun onDescendantInvalidated(child: View, target: View) {
    super.onDescendantInvalidated(child, target); invalidate()
}
```

Touches landing in the right half are remapped into logical space with `offsetLocation(-logicalWidth, 0)`, latched per gesture at `ACTION_DOWN`. An optional Mercury-SDK path (`com.ffalcon.mercury.android.sdk.ui.wiget.MirroringView` — yes, "wiget," the vendor's typo is part of the API) replaces manual double-draw; it ships **off by default** behind pref `sdk_mirroring`, with a Throwable-catch fallback to drawChild. The drawChild path is the proven default.

## 2. Temple-pad click detection — the KEY path (dossier §4.3)

The right-arm click is a key event. Timing-based double-tap on `ACTION_UP`, never consuming `DOWN` (that would break normal clicks):

```kotlin
val isTapKey = event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
    event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER
// tap ≤ 400 ms; double-tap gap in 40..320 ms (40 ms floor filters the
// same physical tap echoing as two keycodes)
```

Check this **first** in `dispatchKeyEvent` so the WebView can't swallow it, and consume **only** when the gesture actually fires. If several detectors can observe the same physical double-tap, give the action a ~1200 ms grace window so it doesn't double-fire.

## 3. Synthetic clicks at the cursor (dossier §4.5)

One source of truth for "where the user points" (`cursorInteractionPoint()` — eye center when head-anchored, scaled cursor position otherwise), then an explicit hit-test chain over every overlay in priority order, and only at the end a `SOURCE_TOUCHSCREEN` DOWN+UP pair into the WebView with coordinates un-scaled and un-rotated. Guard with an `isSimulatingTouchEvent` flag so synthetic events aren't re-detected as gestures, and debounce clicks at 500 ms.

Hard-won corollaries: clickable Views must actually *cover* the area users will tap (a TextView inside a tall ScrollView leaves dead space — `fillViewport` fixes it); overlays beat the cursor by **elevation**, not child order, so cursors live at `elevation = 1000f`; and any scroll-routing shortcut must check `!isCursorVisible` or it eats every drag and "freezes the mouse."

## 4. Audio that never fights itself (dossier §5)

- **Playback:** AudioTrack `MODE_STREAM`, 24 kHz mono PCM16, writes sliced to 16 KB `WRITE_NON_BLOCKING`, a `@Volatile writeGeneration` bumped by `stopAndFlush()` so cancellation interrupts mid-stream, `DEAD_OBJECT` recovery (rebuild track, resume offset, max 3).
- **Focus:** the assistant requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`. Media players react: `CAN_DUCK` → volume 0.3, keep playing; `GAIN` → volume 1.0; only a true transient loss pauses. Build ExoPlayer with `handleAudioFocus = false` and manage it yourself. Beware `change <= AUDIOFOCUS_LOSS` — it's also true for transient values (that comparison was a shipped bug).
- **TTS:** RayNeo's WebView has no Web Speech API. Native clients call Gemini TTS (`gemini-2.5-flash-preview-tts`, L16 24 kHz, ≤2400 chars/call) or Fish (`api.fish.audio`, mp3, model as a *header*). For perceived speed, synthesize a short lead chunk (≤240 chars) first and start playback while the rest follows in ≤1600-char chunks.
- **Visualization:** `Visualizer` on the player's session id, FFT-only, failover to session 0 after 2.5 s of silence.

## 5. Camera frames for vision work (dossier §7)

CameraX `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`, 1280×720, YUV→NV21→JPEG q88, **rotate 90°** (YuvImage ignores `rotationDegrees` — skip this and every vision model sees a sideways world). TapInsight samples ~1 frame/1.1 s; that cadence is thermally and CPU-comfortable alongside rendering and audio. The pipeline lives in a `LifecycleService` (needs the `lifecycle-service` artifact) so frames continue while backgrounded, with `foregroundServiceType="microphone|camera"`.

## 6. Head-anchored content — 3-DoF (dossier §8)

`TYPE_ROTATION_VECTOR` → quaternion (reorder to w,x,y,z) → SLERP toward the new sample (t ≈ 0.08) → relative rotation from a capture-time anchor → `deltaX = q[1] * 2000f; deltaY = q[2] * 2000f` → double-exponential velocity smoothing → apply on a Choreographer frame callback. **Axis swap on apply** (landscape device vs sensor frame): `translationX = yOffset; translationY = xOffset`. Triple-tap re-captures the anchor ("Screen Re-centered"). This is rotation only — see Part VII for position.

## 7. The WebView, tamed (dossier §6)

If the app shows web content: single virtual origin `https://appassets.androidplatform.net/` via a *manual* `shouldInterceptRequest` (the stock WebViewAssetLoader hides the Range header; manual routing enables 206 partial responses so `<audio>/<video>` can seek). Buffer small ranges (≤1 MB) into memory — RayNeo's Chromium throws `net::ERR_FAILED` on streamed tail-ranges. `content://` URIs are unreliable; proxy MediaStore through the same origin. Strip `"; wv"` from the UA to dodge bot detection. Lock the viewport per page-load (`width=1280, user-scalable=no`) or the trackpad zoom-loops. `mediaPlaybackRequiresUserGesture = false`.

## 8. Companion configuration server (dossier §11)

NanoHTTPD on **port 19110**, HTTPS via a self-signed **EC** cert in a PKCS12 file (RSA handshakes stutter audio on this CPU; secure context is required for the browser Geolocation bridge). Auth: one 16-char session token injected into served pages, accepted as Bearer/header/cookie/query. Config endpoint pattern: a single `allowedKeys` whitelist + typed default maps; GET serializes only whitelisted keys, POST writes only whitelisted keys. A tiny JS shim emulates the Android `JavascriptInterface` over REST so identical HTML runs on-glasses and on a phone browser.

## 9. The vendor UI toolkit — official MercurySDK surface

The full official API reference lives at [`docs/MercurySDK_Skill_Reference_EN.md`](docs/MercurySDK_Skill_Reference_EN.md) (v0.2.4 line; **every documented class verified present in the v0.2.2 AAR this repo ships** — checked against the decompile). It documents a complete vendor-native app pattern that none of the five surveyed apps fully use, and it's the most idiomatic starting point for a *new* simple app:

- **Binocular UI without hand-rolling**: `BaseMirrorActivity<B>` (Activity-level mirrored layout via ViewBinding pairs), `BindingPair.updateView { }` (write UI code once, both eyes update), `MirrorContainerView` for view-level embedding, `BaseMirrorFragment` for fragments. This is a *third* display strategy beside TapInsight's duplicate-`dispatchDraw` and Moonlight's PixelCopy — and the only one with official support.
- **3D parallax**: `make3DEffect(leftView, rightView, enable, parallax)` / `make3DEffectForSide(...)` — real depth on a per-view basis (default parallax 3f). Only ever pass *left* views to `enable3DEffect`; the right is looked up internally and passing a right view NPEs.
- **The official gesture model**: `TempleActionViewModel.state: SharedFlow<TempleAction>` — `Click`, `DoubleClick`, `TripleClick`, `LongClick`, `SlideForward/Backward/Upwards/Downwards`, `SlideContinuous(delta, longClick, vertical)`, double-finger events. Important nuance the raw-input path never revealed: **slide direction semantics flip with the system's "natural mode" setting** — design for forward/backward, never hardcode left/right.
- **Official focus management**: `FocusHolder` + `FocusInfo` + `FixPosFocusTracker` (fixed controls), `RecyclerViewFocusTracker` / `RecyclerViewSlidingTracker` + `StartSnapHelper` (lists). Slide = move focus, click = activate, double-click = back — the same conventions the whole ecosystem converged on, here as supported API.
- **Binocular system UI**: `FToast.show(...)` and `FDialog.Builder<T>` render fused in both eyes — this is the official answer to the "Android toasts/dialogs appear in one eye" problem the Moonlight port solved by hand.
- **Utilities**: `DeviceUtil.isX3Device()`, `MobileState.isMobileConnected(): Flow<Boolean>` (companion-phone link state), `dp`/`sp` extensions, `FLogger`.
- **Vendor constraints worth obeying** (their checklist, §9 of the reference): everything UI on the main thread; collect gestures with `repeatOnLifecycle(RESUMED)`; release camera/sensors/GPS in `onPause`/`onDestroy`; pure-black `windowBackground` (their words — confirming the waveguide rule); `scrcpy --crop` for monocular debug mirroring.

When to use which: **vendor toolkit** for a fresh, conventional app (lists, dialogs, settings — least code, official conventions); **TapInsight's duplicate-draw** when the content is a WebView or you need total control of the surface; **PixelCopy** when mirroring something that draws itself (decoder surfaces, SurfaceViews).

## 10. Cross-module architecture (dossier §9.4–9.5)

The shipped shape: a **library module** owns the launcher/UI shell; the **app module** owns heavy services (voice, camera). They talk through tiny static bridge objects (`HudStateBridge`, `ChatCardBridge`, `NowPlayingBridge`...) and a binder interface (`VoiceServiceApi`), with the service bound by FQN string (a library can't import the app's class). Activities warm-start each other with intent extras and a translucent theme + `moveTaskToBack(true)`. Kotlin lets package names diverge from directory paths — this repo uses that (`com.TapLinkX3.app` classes living under `com/TapLink/app/`), and the build is fine with it, but every grep must remember it.

---

# Part V — The gotcha compendium

Each entry cost real debugging time. Read once now; grep later when something feels haunted.

**Hardware & platform**
1. The right-arm click is a KEY, not a touch (Part II). Gesture detectors built on MotionEvents wait forever.
2. Black is transparent. An app that "looks off" may be rendering perfectly — in black.
3. `ar_mode` meta-data silently halves your display to one eye.
4. Camera frames arrive rotated 90°; YuvImage won't fix it for you.
5. The sleep button fires `onPause` while the user is still wearing the glasses. Pause-on-onPause kills their music.
6. Activities die without `onDestroy` under RAM pressure — orphaned ExoPlayers keep playing into the void. Keep a static `@Volatile` ref and kill orphans at next launch.
7. Fast UI refresh starves the audio decoder. If sound stutters, look at your invalidate rate before your audio code.
8. The mask overlay's custom layout pass never measures views added after layout — a view can be VISIBLE at 0×0. Self-heal by re-measuring every frame, and explicitly size views when showing them.
9. Views with XML `elevation` beat later-added siblings regardless of order. `bringToFront()` is not enough; set elevation.
10. Trackpad device IDs shuffle across reboots; match kernel *names*.
11. The same physical double-tap can be observed by multiple detectors (touch + key + GestureDetector). Guard shared actions with a grace window, and when you add a "collapse/close" stage gate, add it to **every** detector — there are five in tapbrowser's MainActivity alone.

**Kotlin & build**
12. Kotlin block comments **nest**. A `/* ... text/* ... */` doc comment is an unclosed comment and the error appears 100 lines later. Never write glob-like `x/*` sequences inside comments.
13. Package ≠ directory in Kotlin. Trust `package` declarations and the decompile, not file paths.
14. The secrets-gradle-plugin emits invalid Java (`= ;`) for blank values. Generate sensitive BuildConfig fields yourself from `findProperty(...).orEmpty()` and ignore-list the key.
15. Untracked files (`local.properties`, `~/.gradle/gradle.properties`) are shared across every branch in a worktree. A "scrub" on one branch can break the build on another days later.
16. Build intermediates carry absolute paths. After copying a repo (`cp -a`), `rm -rf .gradle app/build */build` before the first build or dex transforms point at the dead folder.

**WebView & JS**
17. Injections at page-start can land in a dying page. Install with retries (`postDelayed(inject, 1800L)` rungs) and make installers idempotent.
18. When two writers update one TextView (poll + push), you get flicker that looks like a model bug. One writer, one state machine.
19. YouTube's timedtext endpoint requires the player's own `pot=` proof-of-origin token; the resource-timing buffer overflows before captions load unless you enlarge it at page start.
20. `ScrollView.onTouchEvent` never calls `performClick` — clickable ScrollViews don't click. Put the listener on the child, and `fillViewport` so the child covers the box.

**Voice & assistant plumbing**
21. Native-audio LLMs degenerate when asked to recite long verbatim text (repeats, then raw control tokens like `<ctrl46>`). Route verbatim content through deterministic TTS and suppress the model's audio for that turn; ack the tool call with reference-only text so follow-ups still work.
22. The mic hears your own TTS. Any "release suppression on next user input" gate must refuse to fire while the readout is audible.
23. Time-to-first-audio is governed by the *first* synthesis chunk. Peel a small lead chunk.

---

# Part VI — Proof of code: the working-app ecosystem

Five real apps run on this hardware today. Together they prove every load-bearing claim in this guide twice over, and they disagree with each other in instructive ways. Deep extractions with snippets live in [`docs/refapps/`](docs/refapps/); clone any of them and grep the patterns below.

## The five, and what each one proves

**[TapInsight](https://github.com/tropicalstream/TapInsight-2b)** (this repo's public release) — the maximalist: voice assistant, HUD, media library, companion server. Proves the full Part IV pattern set. Its private ancestor is the reference implementation behind this guide.

**[TAPLINKX3](https://github.com/informalTechCode/TAPLINKX3)** (Apache-2.0, 26 files) — **the teaching example.** This is TapInsight's literal ancestor (same `com.TapLinkX3.app` package; its MainActivity grew from 6.9k to 17.7k lines downstream). The original is small enough to read in one sitting: device-name input demux (`cyttsp5_mt`/`cyttsp6_mt`), quaternion-relative 3-DoF anchoring with Choreographer coalescing + deadband, dual-eye WebView with a throttled `PixelCopy(window)` → right-eye SurfaceView mirror, and a phone-as-controller channel (BT RFCOMM + UDP). Also the in-the-wild proof of the vendor mic modes: `audioManager.setParameters("audio_source_record=voiceassistant")` … `="off"` on teardown (MainActivity.kt ~2228). Bundles the exact same Mercury v0.2.2 + RayNeoIPC v0.1.0 AARs as TapInsight.

**[Everyday](https://github.com/TheophileGaudin/Everyday)** (GPLv3, by the original TAPLINKX3 author) — the minimalist counter-proof: a widget HUD that uses **zero vendor AARs**. The `com.rayneo.mercury.app` manifest meta-data *alone* gets an app into the RayNeo launcher — the Mercury AAR is optional if you do your own mirroring. Steal three things: `binocular/BinocularView` (dual-draw with a pluggable `MirrorStrategy` — dispatchDraw for ordinary views, PixelCopy for WebViews — plus content-class adaptive refresh with battery downshift); `CursorStabilizer.kt` (self-contained temple-pad cursor physics: reversal hysteresis, momentum clamping, rail locking, anisotropic smoothing — the polished version of "drop the first delta"); and `HeadUpWakeManager`/`ThreeDofManager` (look-up-to-wake from a foreground service using stock `TYPE_GAME_ROTATION_VECTOR` + `SCREEN_BRIGHT_WAKE_LOCK`).

**[SmartTube for X3 Pro](https://github.com/oliverfederico/SmartTube-RayNeo-X3-Pro)** (MIT-licensed delta, ~6 files) — proves you may not need a cursor at all. A huge Android-TV app runs **unmodified** on the glasses because the port translates temple gestures into synthetic D-pad keys (`RayNeoInputInterceptor`: click → `DPAD_CENTER`+`ENTER`, double-click → `BACK`, swipes → directional keys at a 45 px threshold, `Instrumentation.setInTouchMode(false)`) and lets Leanback's **focus navigation** do the rest. This is the "Focus Management" path the vendor SDK table mentions, demonstrated at scale. Two more tricks: spoofing `metrics.widthPixels /= 2` + density 1.0 so TV layout math fits one eye, and the Mercury `MirroringView`-vs-SurfaceView conflict — the mirror steals SurfaceView layers, so video must render to a **TextureView**. Its `libs/rayneo_docs/` folder bundles vendor documentation you won't find elsewhere.

**[Moonlight for X3](https://github.com/informalTechCode/moonlight-android-RayNeoX3)** (GPLv3) — the performance ceiling, measured. Game streaming with **no vendor SDK**: a hardcoded 640×480 left-eye container, and a right-eye SurfaceView fed by `PixelCopy` from the **decoder surface itself** every 16 ms (`StereoMirrorController.java` is reusable as-is). The left eye keeps Moonlight's zero-copy low-latency path untouched (`vendor.qti-ext-dec-low-latency.enable`, `requestUnbufferedDispatch`, `holder.setFixedSize(640,480)`). Net proof: the AR1 Gen1 sustains **640×480@60 hardware decode plus a concurrent 60 Hz RGBA readback/blit** — comfortably above anything the POC needs. Also instructive: system toasts/dialogs render unfused (one eye), so the port ships its own — assume the same for any system UI you trigger.

## Decision table for a new project

| Question | Answer the ecosystem gives |
|---|---|
| Vendor AARs or not? | Optional but now well-documented. The Mercury AAR carries a full UI toolkit (Part IV §9: mirrored Activities, gesture stream, focus system, binocular toasts/dialogs, 3D parallax) — not just `MirroringView`. Everyday/Moonlight prove you can skip it entirely; manifest meta-data remains the only hard requirement for launcher visibility. |
| Cursor or focus navigation? | Both proven, and focus is *officially supported*: Mercury's `FocusHolder`/`TempleAction` (documented API) or SmartTube's synthetic-D-pad hack. Cursor (TapInsight/Everyday) for web/free-form content; focus for list/grid UIs — dramatically less code. |
| Mirroring strategy? | Ordinary Views → vendor `BaseMirrorActivity`/`BindingPair` (official, least code for new apps) or duplicate `dispatchDraw` (TapInsight, total control). WebView/SurfaceView content → `PixelCopy` blit (TAPLINKX3/Everyday/Moonlight). Mercury `MirroringView` steals SurfaceView layers (SmartTube) and ships behind a fallback for a reason (TapInsight). |
| System toasts/dialogs (one-eye problem)? | `FToast` / `FDialog` from the vendor toolkit render fused in both eyes — use them instead of Android's, or hand-roll like Moonlight did before this doc surfaced. |
| Performance budget? | 60 Hz decode + mirror is attainable (Moonlight); but vendor thermal guidance (30 fps UI, APL <13%, >500 mA = trouble) and TapInsight's audio-starvation experience say: render the minimum that looks alive. |

---

# Part VII — The mission: object tracking & anchoring POC

## The goal

Demonstrate on the X3 Pro, indoors and outdoors: the glasses know **where they are** (6-DoF: position + orientation), and can keep virtual markers **anchored to real places and objects** as Mars walks around. This is the capability TapInsight's 3-DoF head-anchoring can't reach — it knows where you *look*, not where you *stand*.

## Why not Unity (for now)

Community experience (secondhand, untested by us): the X3 Pro Unity path currently strains the hardware and lacks training data for the on-device tracking stack. Mars is open to revisiting, but the POC should not gamble on it. Everything in Parts II–V is native Android — and the native stack is already proven on this device.

## The MultiSet approach

[MultiSet AI](https://www.multiset.ai) is a Visual Positioning System: you map a space once (phone scan, LiDAR, 360 video, E57 — scan-agnostic), the cloud builds a queryable map, then any device sends camera frames and gets back a **centimeter-class 6-DoF pose**. Object tracking works similarly: upload a textured GLB of an object, get pose + confidence when the camera sees it. Indoor/outdoor transitions, multi-floor stitching (MapSet), georeferencing to WGS-84, on-device/offline deployment options, free tier to start. Docs: [docs.multiset.ai](https://docs.multiset.ai) — note the machine-readable index at [docs.multiset.ai/llms.txt](https://docs.multiset.ai/llms.txt) and `.md` variants of every page (append `.md`), which future Fable should fetch directly.

**The critical caveat (Appendix A, Q12), now resolved:** MultiSet's Android Native SDK ([github.com/MultiSet-AI/multiset-android-sdk](https://github.com/MultiSet-AI/multiset-android-sdk)) requires an **ARCore-compatible device** (plus Kotlin 2.2, target SDK 36) — and the vendor documentation bundled in the SmartTube port states the X3 Pro has **no ARCore**, with 6-DoF achievable "only via SLAM/ShareCamera" at high power cost. So the architecture decision is made: **the REST path.** MultiSet exposes the same localization as a REST API (`/v1/m2m/token` with clientId/secret → JWT; Map Query endpoint takes camera frames + optional hints like `geoHint`/`hintMapCodes`, returns 6-DoF pose) — and the X3 side of that pipeline (camera JPEG capture, OkHttp, JSON, IPC GPS for geo-hints) is all shipped, tested TapInsight code. A tantalizing extra: the official spec lists a second **VGA 640×480 camera with 160° FOV** built for perception (Camera2 ID 1). A 160° lens sees far more landmarks per frame than the 12 MP RGB — if it's accessible to apps, it may be the better VPS query source. Worth one Phase-0 experiment; the RGB camera is the proven fallback.

## Phased plan

**Phase 0 — Ground truth (one evening).**
(a) Confirm the vendor docs empirically: `adb shell pm list packages | grep -iE "arcore|ar\\.core"` (expected: nothing). Probe the perception camera: enumerate Camera2 IDs and try opening ID 1 at 640×480.
(b) Mars creates a MultiSet account, gets `clientId`/`clientSecret` (free tier), maps one room: the MultiSet Mapping App wants a LiDAR iPhone/iPad; alternatives are 360 video or third-party scans. Outdoors: map a stretch of sidewalk/yard and georeference it.
(c) Fable fetches `docs.multiset.ai/llms.txt` and the REST API pages (`.md` suffix) for exact request shapes.

**Phase 1 — "Where am I?" spike (the heart of the POC).**
A minimal native app (fresh repo, skeleton from Part III): camera frame every ~1 s (existing `FrameCaptureManager`, 90° rotation, JPEG q88) → MultiSet REST query → log + display pose & confidence on a 640×480 black-canvas HUD. Success = stable, repeatable poses walking around the mapped room, and sane behavior outdoors with `geoHint` from the IPC GPS. Measure: query latency over Wi-Fi vs phone hotspot, battery/thermals at 1 Hz duty cycle (untested territory — TapInsight's vision cadence was similar but not sustained for navigation sessions).

**Phase 2 — Anchors you can walk around.**
Place virtual markers at map coordinates. Render them on the HUD: project anchor direction into the ~36° view using the proven rotation-vector pipeline for orientation *between* VPS fixes, correcting drift at every fix (VPS gives absolute pose; IMU interpolates the 1–2 s gaps). A marker that stays glued to a doorframe while Mars orbits it is the demo. Keep rendering ≤1 Hz-refresh-friendly when audio is involved (gotcha #7); markers are cheap Views/Canvas draws, not WebView.

**Phase 3 — Object tracking + polish.**
Upload a GLB of a real object (MultiSet object codes), query object pose, draw a halo around the physical object. Then the showpiece: indoor→outdoor handoff on one MapSet, with the dim-mode aesthetic (black canvas, white-on-black labels) for daylight readability.

## What Fable should ask Mars for up front

MultiSet credentials (`multiset.properties`-style, **never committed** — gotcha #14 pattern), the mapped map/mapset codes, whether a LiDAR iPhone is available for mapping, ARCore test result from Phase 0(a), and a feishu export (below).

---

# Part VIII — Resources

| Resource | Where | Notes |
|---|---|---|
| Official RayNeo developer guide | https://leiniao-ibg.feishu.cn/wiki/IwTRwecN0ikZcjkHAhicN5lWn0g | JS/auth-walled from Fable's environment — but **local exports live in [`docs/rayneo-devguide/`](docs/rayneo-devguide/)**: Touch Pad, Sharecamera, Audio Focus, Audio Capture Modes, GPS Streaming, Device System Access, Build Your First XR App, ADB (+ Windows driver fix), and the MIT Reality Hack 2026 deck (official spec sheet, slide 9; SDK capability matrix, slides 13–15). |
| **MercurySDK official API reference** | [`docs/MercurySDK_Skill_Reference_EN.md`](docs/MercurySDK_Skill_Reference_EN.md) | The vendor's full Android SDK surface (v0.2.4 doc; all classes verified in the shipped v0.2.2 AAR): mirrored UI, TempleAction gestures, focus system, FToast/FDialog, 3D parallax, threading/lifecycle checklists. Summarized in Part IV §9. |
| Working-app extractions | [`docs/refapps/`](docs/refapps/) | Deep dives with snippets: Everyday + TAPLINKX3, SmartTube + Moonlight ports. |
| Proof-of-code repos | [TAPLINKX3](https://github.com/informalTechCode/TAPLINKX3) · [Everyday](https://github.com/TheophileGaudin/Everyday) · [SmartTube-X3](https://github.com/oliverfederico/SmartTube-RayNeo-X3-Pro) · [Moonlight-X3](https://github.com/informalTechCode/moonlight-android-RayNeoX3) | See Part VI. SmartTube's `libs/rayneo_docs/` bundles extra vendor docs. |
| Vendor Unity sample | https://github.com/MaxManausa/RayNeoX3Pro-MITSample | Official MIT-hackathon Unity starter (Unity 2022.3.36f1, ARDK 1.1.2) — the Unity-path reference if that door reopens. |
| RayNeo community | open.rayneo.com · Discord [community](https://discord.gg/v2y3nNmu5c) / [developer](https://discord.gg/9FTwVSp4es) | Publishing + support channels. |
| MultiSet | https://www.multiset.ai · https://docs.multiset.ai · [llms.txt](https://docs.multiset.ai/llms.txt) · [Android SDK repo](https://github.com/MultiSet-AI/multiset-android-sdk) · developer.multiset.ai | Append `.md` to any docs page URL for raw markdown. |
| Deep reference dossier | [`docs/x3_dossier.md`](docs/x3_dossier.md) | Every constant + line reference behind this guide. |
| TapInsight repo | `~/Downloads/TapInsight-rebuild-6-11-26`, branch `unipanel` (private) | The living reference implementation. Public release: `github.com/tropicalstream/TapInsight-2b`. |
| Vendor AARs | `tapbrowser/libs/` | Mercury SDK v0.2.2, RayNeoIPCSDK v0.1.0 — copy into new projects. |

---

# Appendix A — Claimed vs. tested: the hardware-support FAQ

The honesty ledger. **✅ Tested** = verified on the physical X3 Pro through TapInsight. **📄 Claimed** = stated by vendor docs/community but never run by us. **❓ Unknown** = nobody has checked.

**Q1. Does binocular (both-eyes) rendering work?**
✅ Tested. `BinocularSbsLayout` double-draw ships today. The Mercury `MirroringView` alternative also ran on-device but stays off by default — treat it as ✅-with-an-asterisk: it worked, with a fallback catch for a reason.

**Q2. Is the display really 640×480 per eye?**
✅ Tested **and official**: the spec sheet lists 640×480 MicroLED, and shipped code hardcodes it throughout (cursor centers at (320,240)/(960,240)). The vendor Unity guide even tells you to set the Game view to 640×480.

**Q3. What refresh rate?**
✅ **60 Hz, official** (spec sheet). The "90–120 Hz" comment in TapInsight's code was a wrong guess, now corrected. Moonlight demonstrates the full 60 Hz being *used*: hardware decode + 16 ms PixelCopy mirroring, sustained.

**Q4. Do the trackpads work like the guide says?**
✅ Tested, extensively: `cyttsp5_mt` right pad (cursor + KEY-event click), `cyttsp6_mt` left volume pad, all timing constants tuned on hardware. The vendor ARDK `TrackpadManager` API: ❓ Unknown — only a reflection stub exists; it was never linked.

**Q5. Sound — playback, mic, TTS, music visualization?**
✅ Tested, all of it: 24 kHz PCM out / 16 kHz mic in, ducking etiquette, ExoPlayer streaming (MediaPlayer is unusable for streams), Visualizer FFT with session-0 failover. Web Speech API confirmed absent.

**Q6. Video playback?**
✅ Tested: media3 ExoPlayer for streams and local HEVC via PlayerView; WebView video (YouTube etc.) works with the HD-cookie priming and viewport lock. Heavy caveat from gotcha #7: rendering rate fights audio.

**Q7. Camera access?**
✅ Tested: CameraX 1280×720 at ~1 fps duty cycle, 90° rotation required. The hardware also carries a **second VGA 640×480 camera with a 160° lens** for perception (📄 official spec; Camera2 ID 1 per vendor docs in the SmartTube port) — never opened by any surveyed app, so app-level access is ❓ untested (Phase-0 experiment). Sustained high-FPS capture thermals: ❓ unmeasured; vendor guidance says >500 mA sustained draw is trouble.

**Q8. GPS?**
✅ Tested via the RayNeoIPCSDK phone bridge — and now 📄 **officially confirmed: the X3 Pro has no onboard GNSS** (the manual is explicit; the X2 had it, the X3 Pro borrows the phone's). The IPC path is the only path.

**Q9. Head tracking?**
✅ Tested for **rotation** (3-DoF, rotation-vector sensor; Everyday and TAPLINKX3 independently ship the same approach). **Position** (6-DoF): the vendor capability matrix lists 6DOF — **Unity-only**, and the bundled vendor docs say it runs via SLAM/ShareCamera at high power, **no ARCore**. No Android-native 6-DoF API exists in anything surveyed. This gap is the whole reason Part VII exists.

**Q10. Depth sensing / plane detection / image tracking / face / hands?**
📄 Claimed, **Unity-only**, per the vendor matrix: plane detection (horizontal, textured, 3–5 s, init-time only), image recognition & tracking, face detection, 2D gesture recognition ("follow-up capability"), and a `make3DEffect` parallax-depth API. ❓ None verified by us or by any of the four surveyed Android apps — zero native-path evidence. Assume unavailable outside Unity.

**Q11. Unity?**
📄 Real and officially supported (Unity 2022.3.36f1 + ARDK 1.1.2; official MIT sample repo exists) — but claimed-problematic in practice (community + Mars's report: hardware resource strain, missing training data). Untested by us in either direction. The interesting AR capabilities (Q9/Q10) are locked behind it; that tension is the road not taken in Part VII.

**Q12. MultiSet on the X3 Pro?**
📄 The Android Native SDK requires ARCore — which the vendor docs confirm the X3 Pro **does not have**. So: the **REST path**, which requires only what's already ✅ tested (camera JPEG + HTTPS + JSON + phone GPS for geo-hints). The SDK door is closed, not ajar; don't spend Phase 0 on it beyond the one confirmation command.

**Q13. Can apps run a web server, serve a companion UI, do TLS?**
✅ Tested: NanoHTTPD on 19110, EC-cert HTTPS (RSA stutters audio — that's a tested fact, not folklore), token auth, REST-shimmed JS bridge.

**Q14. Multi-app / launcher integration?**
✅ Tested: `AR_APP` launcher category, two cooperating APKs side-by-side (private + public builds), warm-start handshake, foreground-service survival in background. The RayNeo launcher honors all of it.

**Q15. What kills apps on this device?**
✅ Tested the hard way: RAM pressure (no `onDestroy` — the official 4 GB explains it), mic revocation without a posted FGS notification, audio-decoder starvation from UI refresh, and one sleep button that pauses Activities while the user is still listening. Vendor docs add: no background multitasking is promised, and the shortcut button is system-owned.

**Q16. Can a focus-driven UI (no cursor) work?**
✅ Proven at scale by the SmartTube port — and ✅ **officially supported**: the MercurySDK reference documents the whole model (`FocusHolder`, `TempleAction`, RecyclerView trackers; Part IV §9), with every class verified present in the shipped v0.2.2 AAR. For list/grid apps this is far less code than a cursor.

**Q17. How hard can I push the chip?**
✅ Moonlight's measured ceiling: 640×480@60 low-latency hardware decode **plus** a concurrent 60 Hz PixelCopy readback/blit. 📄 Vendor thermal guidance still says target ~30 fps UI, APL <13%, sustained draw <500 mA. Both are true: bursts of 60, cruise at 30-or-less.

**Q18. Do I need the vendor SDKs at all?**
✅ Proven optional: Everyday and Moonlight ship with zero vendor AARs — the `com.rayneo.mercury.app` manifest meta-data alone gets you into the launcher. But the AARs buy more than we knew before the official reference surfaced: a complete binocular UI toolkit (mirrored Activities/Fragments, gesture stream, focus system, fused toasts/dialogs, 3D parallax — Part IV §9), plus the GPS IPC bridge. Known sharp edge stands: `MirroringView` steals SurfaceView layers (SmartTube moved video to TextureView). Take the toolkit deliberately — for a fresh conventional app it's now the least-code path.

**Q19. Is per-view 3D depth real?**
📄 Officially documented (`make3DEffect` / `make3DEffectForSide`, default parallax 3f) and ✅ the API verified present in the shipped AAR — but ❓ never rendered by any surveyed app. First project to call it owns the test.

---

*End of guide. Build something that knows where it is.* 🥽
