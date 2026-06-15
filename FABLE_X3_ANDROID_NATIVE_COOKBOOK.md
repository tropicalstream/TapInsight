# Fable Cookbook — Building an Android-Native Health/AI App for the RayNeo X3 Pro, Bottom-Up

*A complete, step-by-step implementation guide for a frontier model building a real app on the X3 Pro from an empty project to a running APK. Pairs with `FABLE_X3_ANDROID_NATIVE_GUIDE.md` (the API reference) and `FABLE_X3_STARTER_GUIDE.md` (the hardware truths). Where the reference guides say what's possible, this says how to build it — and what will go wrong.*

**Running example used throughout:** a HUD that shows a **graphic representation of all the user's health metrics, refreshing every 20 seconds**, navigable **by voice/AI** ("show me my heart rate trend", "am I in a good zone?"). Every section returns to this example so nothing is abstract.

---

## 0. How to use this cookbook

Read `FABLE_X3_STARTER_GUIDE.md` first — this assumes you know the hardware truths (640×480 per eye, AR1 Gen1, 4 GB, thermal limits, the masked-redraw reboot lesson, the temple-tap input model). This document is the build order: **architecture → scaffold → manifest → hardware → network/data → UI/UX → AI → threading → performance → failure modes → FAQ → test plan**. Build in that order; each step depends on the one before.

Golden rule for this device, restated because it governs every decision below: **the AR1 is a 4-core, 4 GB, 245 mAh, thermally-tight chip. Render and compute the minimum that looks alive.** A health dashboard is a perfect fit *if* you respect that; a naive implementation that redraws charts at 60 fps will overheat and reboot the glasses (we have the logs).

---

## 1. Decide the architecture before you write a line

Answer these three questions first; they determine everything:

**Q1: Where do the health numbers come from?** The glasses have **no heart/SpO₂ sensor** (starter guide Constraint). For the running example you need a real source:
- **Wear OS watch** (Pixel/Galaxy Watch) → writes Health Connect → glasses read it. *Recommended.*
- **Third-party wearable** (Garmin/Polar/Samsung Health) → its SDK on a phone → relay to glasses.
- **No wearable available?** Then the honest app shows steps/activity (which the phone/glasses motion sensors *can* produce) and clearly marks HR/SpO₂ as "connect a watch." Don't fake a heart-rate line — see §11.

**Q2: Does the AI run on-device or in the cloud?** For "navigate by AI," cloud Gemini (multimodal, already in TapInsight) is the pragmatic choice; on-device Gemma via MediaPipe is the offline option but a sustained thermal load (§10). AICore/Gemini Nano: assume absent on the AR1.

**Q3: Is the data read directly on the glasses, or relayed from a phone?** If a wearable syncs Health Connect *to the glasses' own Google account*, read it natively. More commonly, a **phone companion** reads Health Connect and relays the JSON envelope. Pick the relay (the reference guide's three transports) up front — it's the spine.

**For the running example**, the reference architecture is: *Wear OS watch → phone's Health Connect → phone companion app reads + relays every 20 s → glasses render the dashboard + run cloud-Gemini for navigation.* Everything below assumes this unless noted.

---

## 2. Project scaffold

Two-module layout, mirroring TapInsight (it's proven on this device):

```
app/          (com.yourco.healthhud)      — the glasses Activity, HUD, AI, relay client
  └─ build.gradle.kts (application)
phone/        (optional, com.yourco.healthhud.phone) — Health Connect reader + relay, if bridging
settings.gradle.kts                       — include(":app"), include(":phone")
```

`app/build.gradle.kts` essentials (versions proven on the X3, from the dossier):

```kotlin
android {
    namespace = "com.yourco.healthhud"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.yourco.healthhud"
        minSdk = 29; targetSdk = 35
        versionCode = 1; versionName = "0.1"
        // AI key: blank-safe BuildConfig, never committed (starter guide pattern)
        buildConfigField("String", "GEMINI_API_KEY",
            "\"${(project.findProperty("GEMINI_API_KEY") as? String).orEmpty()}\"")
    }
    buildFeatures { buildConfig = true }   // add viewBinding=true if using the Mercury UI toolkit
}
dependencies {
    implementation("androidx.health.connect:connect-client:1.1.0-beta01")  // if reading HC on-glasses
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // relay + cloud Gemini
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // charts: prefer a native Canvas/Compose renderer over a WebView chart — see §7
}
```

If the app should appear in the RayNeo launcher and render binocular, copy the manifest meta-data and `BinocularSbsLayout` from the starter guide (§3 below). If it's a phone-only relay, it's an ordinary Android app — no X3 specifics.

---

## 3. Manifest & permissions (complete)

### Glasses app manifest
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <!-- only if reading Health Connect ON the glasses (vs. receiving via relay) -->
    <uses-permission android:name="android.permission.health.READ_HEART_RATE" />
    <uses-permission android:name="android.permission.health.READ_OXYGEN_SATURATION" />
    <uses-permission android:name="android.permission.health.READ_STEPS" />
    <uses-permission android:name="android.permission.health.READ_EXERCISE" />
    <uses-permission android:name="android.permission.health.READ_ACTIVE_CALORIES_BURNED" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />   <!-- voice nav -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application android:label="Health HUD" android:theme="@style/Theme.Black"
                 android:usesCleartextTraffic="true">
        <!-- REQUIRED for RayNeo launcher + binocular display -->
        <meta-data android:name="com.rayneo.mercury.app" android:value="true" />
        <!-- DO NOT declare ar_mode — it restricts rendering to the left lens -->

        <activity android:name=".MainActivity" android:exported="true"
            android:launchMode="singleTask" android:screenOrientation="landscape"
            android:windowSoftInputMode="adjustNothing">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="com.rayneo.intent.category.AR_APP" />
            </intent-filter>
        </activity>
        <!-- if voice nav must survive backgrounding -->
        <service android:name=".VoiceService" android:exported="false"
                 android:foregroundServiceType="microphone" />
    </application>
</manifest>
```

### Health Connect permission flow (gotcha-heavy — read carefully)
Health Connect permissions are **runtime** and use a **special contract**, not the normal `requestPermissions`:

```kotlin
val perms = setOf(
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(OxygenSaturationRecord::class),
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
)
val contract = PermissionController.createRequestPermissionResultContract()
val launcher = registerForActivityResult(contract) { granted ->
    if (granted.containsAll(perms)) startHealthLoop() else showConnectPrompt()
}
// First check availability — Health Connect can be unavailable / needs update:
when (HealthConnectClient.getSdkStatus(context)) {
    HealthConnectClient.SDK_UNAVAILABLE -> showUnsupported()        // device has no HC
    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> sendToPlayStore()
    HealthConnectClient.SDK_AVAILABLE -> launcher.launch(perms)
}
```

**The three things people miss:** (1) you must check `getSdkStatus` before anything — Health Connect may be absent or need a Play update; (2) granted permissions can be revoked at any time, so re-check on every `onResume`; (3) the health permission strings must also be declared in the manifest *and* a privacy-policy rationale Activity registered, or Play review / the permission dialog fails.

---

## 4. Hardware constraints checklist (pin these to the wall)

| Constraint | Consequence for this app |
|---|---|
| 640×480 per eye | design the dashboard for 640×480; it's drawn twice by `BinocularSbsLayout` |
| Black = transparent | dashboard on pure-black canvas; gauges glow, background is the world |
| No heart/SpO₂ sensor | data must arrive from a wearable (§1) |
| AR1 thermal limit (>500 mA = trouble) | 20 s refresh is fine; 60 fps chart animation is not (§10) |
| Masked-redraw reboot lesson | never animate the dashboard under the dim mask; throttle redraws to the data rate |
| Temple click = KEY event | `KEYCODE_BUTTON_A`/`DPAD_CENTER`, not touch (starter guide §4) |
| WebView has no Web Speech | use native `SpeechRecognizer`/`TextToSpeech` for voice nav |
| 4 GB RAM, process killed without onDestroy | keep static refs to long-lived objects; restore state on relaunch |

---

## 5. Network & data setup

### 5.1 The relay client (receiving health on the glasses)
Reuse the TapInsight pull pattern. The phone POSTs the envelope to the glasses' companion endpoint, or the glasses pull from a Cloudflare-tunnelled relay every cycle. Envelope (identical across both cookbooks so the renderer is shared):

```json
{ "type":"health", "ts":1718200000,
  "hr":78, "hrZone":2, "spo2":98, "hrv":42, "respRate":14,
  "kcalActive":142, "steps":8421, "vo2max":41.2,
  "rings":{"move":0.62,"exercise":0.40,"stand":0.75},
  "source":"Pixel Watch", "spo2Fresh":false }
```

Note `spo2Fresh` — most wearables only sample SpO₂ during sleep, so a "live" SpO₂ number is often hours old. The envelope carries freshness so the UI can be honest (§11). Always timestamp; always carry source.

### 5.2 The 20-second cadence — where to put the clock
**Put the 20 s timer on the data side, not the render side.** The renderer draws only when data changes. Two correct shapes:

```kotlin
// If the phone relays: phone runs the 20s loop, POSTs each cycle; glasses redraw on receipt.
// If the glasses read Health Connect directly:
private val healthLoop = lifecycleScope.launch {
    while (isActive) {
        val snapshot = healthRepo.readLatest()   // HC reads or last relay payload
        _healthState.value = snapshot            // StateFlow → UI recomposes/redraws once
        delay(20_000L)
    }
}
```

Drive the UI from a `StateFlow<HealthSnapshot>`; the view observes and redraws exactly once per update. This is the single most important structural decision: **the 20 s lives in one coroutine, the UI is a pure function of the latest snapshot.** It makes the refresh cheap, testable, and impossible to accidentally turn into a busy-loop.

### 5.3 Cloud Gemini for navigation
OkHttp `generateContent` against `gemini-2.5-flash`, key from BuildConfig/prefs (never committed). For "navigate by AI," send the current snapshot as context plus the user's spoken query (§8).

---

## 6. The data layer

A repository with three responsibilities: **read** (Health Connect or relay), **cache** (last good snapshot, survives reconnect/process death), **expose** (`StateFlow`). Keep a `lastUpdatedMs` and a `staleAfterMs` (e.g. 90 s) so the UI can grey out numbers that stopped arriving. Persist the last snapshot to a tiny prefs/JSON file so a relaunch shows yesterday's-last instead of a blank screen while the first read lands.

---

## 7. UI / UX — the binocular health dashboard (the worked example)

### 7.1 Native rendering, not a WebView chart
You have two ways to draw charts on the glasses: a WebView (Chart.js etc.) or native (Canvas / Jetpack Compose). **For a 20 s-refreshing dashboard, use native.** The WebView is the single biggest CPU/thermal consumer on this device (it's what caused the reboot when video ran under the mask). A native `Canvas` drawing six gauges costs almost nothing and redraws only on the StateFlow tick. Reserve the WebView for things that are genuinely web (maps, rich pages).

### 7.2 Layout for 640×480, drawn twice
Root = `BinocularSbsLayout` with one child (your dashboard view). Design the child at **640×480**. A clean health HUD at that size:

```
┌──────────────────────────────────────┐ 640×480 (per eye)
│  ♥ 78 bpm   Zone 2 ▓▓▒░░    SpO₂ 98%* │  ← top strip: live numbers, big, white-on-black
│  ┌────────────── HR trend ──────────┐ │
│  │      ╭╮      ╭─╮                  │ │  ← sparkline (last ~30 min), native Canvas path
│  │  ╶─╯  ╰─╴╶──╯   ╰──╶              │ │
│  └──────────────────────────────────┘ │
│  Move ◐ 62%   Exercise ◑ 40%   Stand ●│  ← activity rings as arcs
│  8,421 steps · 142 kcal · 14 br/min    │  ← secondary stats, smaller
│  source: Pixel Watch · updated 4s ago  │  ← freshness line (honesty)
└──────────────────────────────────────┘
```

`*` on SpO₂ = "sampled, not live" marker driven by `spo2Fresh`. White text + subtle black-edge shadow for daylight (starter guide outdoor lesson). Numbers update in place; the sparkline shifts left by one sample per tick.

### 7.3 The refresh, done right
On each StateFlow emission: update the text fields, push one new point into the sparkline ring buffer, `invalidate()` **once**. No animation loop. No `postDelayed` redraw chain. If you want a gentle number "tween," cap it at a fixed short duration and cancel on the next tick — but honestly, on a 20 s cadence a hard cut reads fine and costs nothing. Under the dim mask, suspend even this (set the throttle flag from the starter guide's reboot fix).

### 7.4 Navigation by temple + cursor
The user moves between views (overview → HR detail → rings → sleep) with the temple pad. Two proven models (starter guide §4 / Part VI): a **cursor** (free pointing) or **focus navigation** (swipe = move highlight, tap = select). For a fixed set of dashboard cards, focus navigation is less code and more reliable. Double-tap = back/exit (wire it into all detectors — the starter guide's hard-won lesson).

---

## 8. AI navigation — the worked example's second half

"Navigate it by AI" means the user speaks, the assistant interprets against the live snapshot, and either answers or switches the view.

```kotlin
// 1. Native STT (WebView has no Web Speech): SpeechRecognizer → transcript.
// 2. Build a grounded prompt — give the model the snapshot AND the allowed actions.
val prompt = """
You are the assistant for a health HUD on AR glasses. Current metrics:
HR ${s.hr} bpm (zone ${s.hrZone}), SpO2 ${s.spo2}%${if(!s.spo2Fresh) " (sampled, not live)" else ""},
HRV ${s.hrv} ms, steps ${s.steps}, active ${s.kcalActive} kcal, rings move ${s.rings.move}.
The user said: "$transcript".
Reply in ONE short spoken sentence. If they want a different view, end with
ACTION:SHOW=<overview|hr|rings|sleep>. Never give medical diagnoses.
""".trimIndent()
val reply = gemini.generate(prompt)          // cloud, multimodal-capable
// 3. Parse ACTION:, switch the view if present; speak the rest via native TTS.
```

Design notes that prevent the common failures: **ground the model in the actual numbers** (don't let it invent a heart rate); **constrain the action vocabulary** to views that exist (so "show my sleep" can't crash on a missing screen); **forbid diagnosis explicitly** in the prompt; **speak the reply through native TTS**, and if you also show it, route it through the same throttled redraw. For multimodal ("what am I looking at?"), attach the camera frame (starter guide §Camera, 90° fix) — but note that's a heavier call; gate it behind an explicit ask.

---

## 9. Threading & lifecycle

- Health loop + relay: a coroutine on `Dispatchers.IO`, scoped to the Activity/Service lifecycle; cancel in `onStop`, restart in `onStart`.
- UI updates: always main thread (collect the StateFlow with `repeatOnLifecycle(STARTED)`).
- Voice: native `SpeechRecognizer` is main-thread-bound for callbacks; the Gemini call is IO.
- Process death (4 GB pressure): keep the last snapshot in a static/persisted store; on relaunch, render it immediately, then resume the loop. Don't assume `onDestroy` runs.
- Sleep button → `onPause` while still worn (starter guide): don't tear down the health loop in `onPause` if the user expects glanceable vitals to persist; tear down in `onStop`.

---

## 10. Performance & thermals — why 20 seconds is the right number

The user asked for 20 s; that's a *good* cadence and worth understanding why. Health data genuinely changes slowly (HR every few seconds at most from a watch; SpO₂ is sampled sparsely). A 20 s redraw of a native Canvas is thermally invisible. The danger isn't the cadence — it's **what you do per tick** and **what runs in the background**:

- A native Canvas redraw at 20 s: negligible. ✅
- A WebView chart re-render at 20 s: tolerable, but the WebView's idle cost is already high — prefer native. ⚠️
- An on-device Gemma inference per tick: **no** — that's a sustained LLM load every 20 s, it will heat the glasses. Run AI only on user request. ❌
- Animating between ticks at 60 fps: defeats the whole point; the chip pays for 60 fps even though data changed once. ❌
- Anything redrawing under the dim mask: the documented reboot path. Suspend on mask. ❌❌

Rule: **per tick, do the cheapest thing that reflects the new data, and nothing between ticks.** Budget target from the vendor: ~30 fps ceiling, APL <13%, sustained draw <500 mA.

---

## 11. Potential issues (the exhaustive list)

**Data-source issues**
- *Health Connect is empty.* No wearable has written data. Symptom: all zeros / nulls. Fix: detect empty reads, show "Connect a watch to see vitals," don't render a flat-zero chart that looks like a dead patient.
- *SpO₂ is stale.* Most wearables only measure it during sleep. A "live" 98% may be 8 hours old. Fix: the `spo2Fresh` flag + the `*` marker + "sampled" in any spoken reply. This is an honesty issue, not just UX.
- *Heart rate latency.* Watch → Health Connect → relay → glasses can lag 5–30 s. Your "every 20 s" dashboard may show a number that's already a cycle old. Fix: show the sample's own timestamp, not the fetch time.
- *Units.* Health Connect SpO₂ is a 0–1 fraction; ×100 for %. HR is bpm. Energy is kcal vs kJ. Get these wrong and the dashboard lies. Test with known values.
- *Permission revoked mid-session.* Re-check on `onResume`; degrade gracefully.
- *The June-2026 steps SPN change* — on-device step attribution moved to a synthetic package name; if you filter by data origin you may miss steps. Read without origin filtering unless you specifically need it.

**Rendering / device issues**
- *Reboot from over-rendering* — covered; native + throttle + suspend-under-mask.
- *Charts unreadable outdoors* — white-on-black with edge shadow; test in daylight.
- *Text too small at 640×480* — the top-line live numbers must be large; secondary stats can be small. Don't cram six full charts; one sparkline + gauges.
- *One-eye rendering* — if you see content in only one eye, you declared `ar_mode` or didn't use `BinocularSbsLayout`.
- *Cursor frozen / taps ignored* — the input-routing and double-tap lessons from the starter guide; the temple click is a KEY event.

**AI issues**
- *Model invents metrics* — ground every prompt in the real snapshot; never let it answer health questions from nothing.
- *Diagnosis creep* — the model says "you may have a condition." Forbid in the prompt; surface numbers and trends only.
- *Action hallucination* — it returns `ACTION:SHOW=ecg` for a view you don't have. Validate against the real view list; ignore unknown actions.
- *Latency* — a cloud call mid-conversation; show a "thinking" state, don't freeze the HUD.
- *No network* — cloud Gemini fails on a hotspot dropout. Fall back to a canned local response ("I can't reach the assistant right now; your HR is 78") so the app stays useful.
- *Mic conflict* — voice nav and any other audio capture fighting; one capture owner, released on stop (starter guide audio lessons).

**Lifecycle / network issues**
- *Relay disconnect* — the Wi-Fi adb / hotspot nap problem; auto-reconnect with backoff, show last-good with a staleness badge.
- *Process killed* — render persisted last snapshot on relaunch.
- *Clock skew* — phone and glasses clocks differ; trust the sample's source timestamp, display relative ("4s ago") not absolute where possible.

---

## 12. FAQ

**Can the glasses measure my heart rate themselves?** No — no PPG/ECG sensor. A watch or wearable must provide it.

**Do I need a phone?** For live wrist vitals, effectively yes (the watch pairs to a phone; the phone relays). The AI and rendering run on the glasses. WeatherKit-style direct REST needs no phone, but health data does.

**Why not update every second instead of 20?** You can, but health data barely changes that fast, and tighter cadences cost battery/heat for no information gain. 20 s is a good glanceable rhythm; live HR during a workout is the one case worth 1–5 s, and only via the Wear OS live stream.

**Can I run the AI fully offline?** Partly — MediaPipe/Gemma on-device works for short tasks, but it's a thermal load; don't run it per tick. Cloud Gemini is the practical path for conversational navigation.

**Will Gemini Nano work?** Almost certainly not on the AR1 (it's flagship-gated). Probe at runtime; fall back to cloud/MediaPipe.

**Native charts or a web dashboard?** Native (Canvas/Compose). The WebView is the device's heaviest component and the reboot culprit under load.

**How do I show SpO₂ honestly if it's only measured at night?** Mark it sampled, show its real timestamp, and have the AI say "sampled overnight" rather than implying a live reading.

**Can it work without a Google account?** Health Connect itself doesn't require one, but the source wearable usually does, and cloud Gemini needs a key. Direct REST Google APIs need a key, not an account.

**What if the user has no wearable at all?** Build the activity/steps view (motion sensors can do that) and gate HR/SpO₂ behind "connect a watch." Don't fabricate vitals.

**How do I test without glasses?** Most of the data/AI layer runs in an emulator; the binocular rendering and temple input need the device, but you can stub the input and view one eye in the emulator (set Game-view-style 640×480 per the vendor Unity note — the principle carries).

**Does this drain the battery fast?** A 20 s native dashboard is light; the costs are the screen, the relay radio, and any AI calls. The 245 mAh battery gives 3–5 h of normal use; a glanceable HUD with on-request AI fits comfortably.

---

## 13. Test plan / acceptance

1. **Empty state** — no wearable: shows "connect a watch," no fake data.
2. **Live update** — wear a watch, confirm the HUD changes within ~20 s of a real HR change; the sparkline shifts one sample per tick.
3. **Freshness** — let SpO₂ go stale; confirm the `*`/timestamp reflects it.
4. **Thermal soak** — run the dashboard 20+ minutes; temples stay cool, no reboot, no static (the starter guide's `top -H` + thermal-log method).
5. **Dim mask** — enter dim mode; confirm redraws suspend (throttle flag), audio/data continue.
6. **Voice nav** — "show my heart rate" switches views; "am I in a good zone" answers grounded in the real number; "do I have a heart problem" refuses diagnosis.
7. **Network loss** — drop Wi-Fi mid-session; HUD shows last-good + staleness, AI degrades gracefully, reconnects.
8. **Relaunch** — kill the app; on reopen it shows the persisted last snapshot immediately.
9. **Units** — inject known values; verify bpm, %, kcal render correctly.

---

## 14. Build & install

```bash
# key out of the binary for any shared/public build; in ~/.gradle/gradle.properties for your own
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
# verify no key shipped in a public build:
strings app/build/outputs/apk/debug/app-debug.apk | grep -c AIza   # must be 0 for public
```

The `&&` matters — a failed build must not install a stale APK (starter guide working agreement). Commit and push at every milestone.

---

*Companions: `FABLE_X3_ANDROID_NATIVE_GUIDE.md` (API reference) · `FABLE_X3_STARTER_GUIDE.md` (hardware) · `FABLE_X3_APPLE_BRIDGE_COOKBOOK.md` (the same build, Apple ecosystem).*
