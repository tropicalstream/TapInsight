# Fable Native Guide — RayNeo X3 Pro × Android APIs, Health Connect & On-Device AI

*A build guide for a frontier model implementing the Android side of the RayNeo X3 Pro — on-device and cloud AI, Health Connect body metrics, and the Google/Android APIs worth wiring into AR glasses. Grounded in the 2025–2026 Android developer releases (Gemini Nano 4, Gemini Intelligence, ML Kit GenAI, Health Connect). Companion to `FABLE_X3_STARTER_GUIDE.md` and the mirror-image `FABLE_X3_APPLE_BRIDGE_GUIDE.md`.*

**Where this lives:** repo root, beside the other Fable guides. Browsable HTML at `docs/x3-android-native.html`, linked from the guide library at `docs/x3-guide-hub.html`.

---

## 0. Read this first — what's native, and two honest constraints

**The RayNeo X3 Pro runs Android 14 (Snapdragon AR1 Gen1).** Unlike the Apple guide — where everything had to cross a phone bridge — most of this runs **directly on the glasses**: the Android SDK, Google Play services, Health Connect, ML Kit, the Maps/Places/Routes APIs, CameraX, and the cloud Gemini API are all callable in-process. TapInsight already proves this: it ships Google Calendar, Tasks, Places, Routes, News, Air-Quality, and Contacts tools, plus a cloud-Gemini voice pipeline, all running on the glasses today (see the dossier).

But two constraints shape every design choice below, and a frontier model that ignores them will write code that won't run:

**Constraint 1 — the chip probably can't run AICore / Gemini Nano.** As of 2026, on-device Gemini Nano (via Android's AICore system service and the ML Kit GenAI APIs) is gated to flagship silicon: Pixel 8–9, Galaxy S24+, and "progressive support on Snapdragon **8 Gen 3**." The X3 Pro's **AR1 Gen1** is a compact AR chip, not a flagship phone SoC — assume AICore is **absent**. So "on-device LLM on the glasses" does **not** mean Gemini Nano. It means either a **bundled model via MediaPipe LLM Inference / LiteRT** (runs on any capable Android, no AICore), or the **cloud Gemini API** (which TapInsight already uses). Probe for AICore at runtime, but never depend on it.

**Constraint 2 — the glasses have no heart/SpO₂ sensor.** The X3 has an IMU, cameras, and mics — no PPG, no ECG, no pulse oximeter. So Health Connect *on the glasses* is an empty cupboard unless something fills it. Live vitals come from a **Wear OS watch (Pixel Watch, Galaxy Watch) or a third-party wearable** whose data lands in Health Connect, which the glasses then read — directly if the data is synced to the glasses, or via a **phone companion relay** (the same bridge pattern as the Apple guide). The compute is native; the *sensors* still need a wrist.

Net: **Android gives you native AI + native APIs, but health sensing still needs a wearable.** This guide is mostly "things that run on the glasses," with one bridge section for live vitals.

---

## 1. What 2025–2026 changed (verified)

- **Gemini Nano 4 + Gemini Intelligence.** Google unveiled Gemini Nano 4 for on-device Android AI (2026) and announced **Gemini Intelligence** at The Android Show on **May 12, 2026** — its most advanced Android AI system. Both are delivered through **AICore**, the system service that downloads/updates/serves the model centrally. **Device-gated** (Constraint 1) — the headline, not your path on this chip.
- **ML Kit GenAI Prompt API.** The developer entry point to Gemini Nano when AICore is present — summarize, rewrite, describe-image, prompt — no API key, no cloud round-trip. Worth a runtime capability check; graceful-fallback to cloud when unavailable.
- **Gemma 4 via AICore Developer Preview**, and **Gemma models runnable locally** through MediaPipe / LiteRT — this is the device-agnostic on-device path that *does* work on the AR1.
- **LiteRT** (the runtime formerly TensorFlow Lite) and **MediaPipe LLM Inference** — run a quantized Gemma (or other GGUF/`.task`) model in-process on the glasses' CPU/GPU. This is how you get an offline LLM on hardware AICore ignores.
- **Health Connect** is now the Android health hub (Google Fit APIs sunset **end of 2026**). Jetpack `androidx.health.connect:connect-client:1.1.0-beta01`, 50+ data types. A **June 2026** change attributes on-device steps to a device-specific Synthetic Package Name (use `getCurrentDeviceDataSource()` — not yet in the Jetpack lib at time of writing).

---

## 2. On-device & cloud AI on the glasses — the realistic stack

Four tiers, in order of "definitely works on the AR1" to "only if the chip cooperates":

### 2.1 Cloud Gemini API — the proven path (TapInsight uses this today)
Multimodal, no device gate, already wired in the codebase. Takes the glasses' **camera frame** (JPEG, 90° rotation fix — starter guide §Camera) alongside text, which is exactly the existing TapInsight vision pattern. Use for the heavy reasoning the AR1 can't do locally.

```kotlin
// The cloud path the app already runs: multimodal generateContent over the
// camera frame + context. Key from companion app at runtime (see starter
// guide §build — blank-safe BuildConfig + prefs fallback).
val parts = listOf(
    Part.text("Heart rate $hr bpm. What is the glasses camera looking at, and anything notable?"),
    Part.inlineData("image/jpeg", cameraFrameBase64)
)
geminiClient.generateContent(model = "gemini-2.5-flash", parts = parts)
```

### 2.2 On-device LLM via MediaPipe LLM Inference / LiteRT — the offline path that works here
Bundle a quantized **Gemma** (e.g. Gemma 3 2B/4B `.task` or `.litertlm`) and run it in-process. No AICore, no flagship requirement — runs on the AR1's CPU/GPU. Budget carefully (the glasses are a 4-core, 4 GB, thermally-limited device — see the starter guide's reboot lesson; an LLM is a sustained load, so gate it and never run it under the dim mask).

```kotlin
val options = LlmInference.LlmInferenceOptions.builder()
    .setModelPath("/data/local/.../gemma3-2b-it-int4.task")
    .setMaxTokens(512)
    .build()
val llm = LlmInference.createFromOptions(context, options)
val out = llm.generateResponse("Summarize: $text")
```

Best for short, private, offline tasks (summarize a notification, classify intent) — not long generation, which will heat the glasses.

### 2.3 ML Kit — on-device vision/text that definitely works
No AICore needed. On-device **text recognition (OCR)**, **translation** (50+ languages, downloadable models), **barcode**, **face/pose detection**, **smart reply**, **language ID**. These are the cheap, reliable, offline building blocks for an AR HUD — translate a sign in the camera view, read text aloud, scan a QR. Pair with CameraX (starter guide §Camera).

### 2.4 AICore / Gemini Nano — only if present, never assumed
Probe at runtime; if the ML Kit GenAI Prompt API reports the feature available and downloadable, use it for free private inference. On the AR1, expect it absent and fall through to 2.1/2.2. **Write the capability check, not the assumption.**

> **Design rule:** cloud Gemini for heavy multimodal reasoning, MediaPipe/Gemma for offline short tasks, ML Kit for vision/text primitives, AICore as a bonus when the device has it. TapInsight's existing provider-fallback pattern (companion-app key → BuildConfig → graceful degrade) is the template.

---

## 3. Body health metrics — Health Connect

### 3.1 Setup
```kotlin
// build.gradle: implementation("androidx.health.connect:connect-client:1.1.0-beta01")
val client = HealthConnectClient.getOrCreate(context)   // check availability first
```
Declare read permissions in the manifest (`android.permission.health.READ_HEART_RATE`, `READ_OXYGEN_SATURATION`, `READ_STEPS`, `READ_EXERCISE`, …) and request them at runtime via the Health Connect permission contract. Health Connect is built into Android 14 — but **only holds data a wearable/phone has written** (Constraint 2).

### 3.2 The metrics that matter for AR, and their record types

| Metric | Health Connect record | Notes |
|---|---|---|
| **Heart rate** | `HeartRateRecord` | series of bpm samples |
| **Blood oxygen** | `OxygenSaturationRecord` | SpO₂ %; typically sleep-time on most wearables |
| **HR variability** | `HeartRateVariabilityRmssdRecord` | recovery/stress |
| **Respiratory rate** | `RespiratoryRateRecord` | breaths/min |
| **VO₂ max** | `Vo2MaxRecord` | fitness trend |
| **Steps** | `StepsRecord` | June-2026 SPN attribution change |
| **Exercise / workout** | `ExerciseSessionRecord` | session type + bounds |
| **Active energy** | `ActiveCaloriesBurnedRecord` | kcal |
| **Sleep** | `SleepSessionRecord` | stages |
| **Body temperature** | `BodyTemperatureRecord` | |

### 3.3 Reading
```kotlin
val resp = client.readRecords(
    ReadRecordsRequest(
        recordType = HeartRateRecord::class,
        timeRangeFilter = TimeRangeFilter.between(start, Instant.now())
    )
)
resp.records.flatMap { it.samples }.forEach { /* it.beatsPerMinute, it.time */ }
```
For **live** data during a workout, the dense stream comes from **Health Services on a Wear OS watch** (`androidx.health:health-services-client`, `MeasureClient`/`ExerciseClient`) — the watch app forwards samples to the phone/glasses, mirroring the Apple `HKLiveWorkoutBuilder` story. Periodic background reads on the glasses use Health Connect's change-tokens to poll deltas.

### 3.4 Getting vitals to the glasses
Same three transports as the Apple guide, reusing TapInsight infrastructure: **(a)** the glasses read their own Health Connect if a paired source syncs into it; **(b)** a **phone companion** reads Health Connect and relays the JSON envelope over the companion server / BLE / the Cloudflare pull-relay (`image_relay.py` pattern); **(c)** the wearable's own SDK (Samsung Health, Garmin, Polar) → phone → relay. The envelope is identical to the Apple guide so the **glasses-side consumer is the same code** regardless of which ecosystem feeds it:
```json
{ "type":"health", "ts":1718200000, "hr":78, "spo2":98, "kcalActive":142, "steps":8421 }
```

---

## 4. Google & Android APIs worth wiring into AR glasses

Split by where they run:

### 4.1 Native on the glasses (no phone)
| API | Use for AR | Status |
|---|---|---|
| **Maps SDK / Places / Routes / Directions** | turn-by-turn nav, place cards, geocode | ✅ TapInsight ships Places + Routes tools today |
| **CameraX** | the vision pipeline (1280×720, 90° rotation) | ✅ starter guide §Camera |
| **ML Kit** (OCR/translate/barcode/face/pose) | translate signs, read text, scan codes | ✅ on-device, offline |
| **Google account tools** (Calendar, Tasks, Keep, Contacts, News, Air Quality) | HUD tiers | ✅ all proven in the codebase |
| **TextToSpeech / SpeechRecognizer** | system TTS/STT fallback | ✅ — but RayNeo's WebView lacks Web Speech (starter guide §Audio); native works |
| **Nearby Connections / Cast** | device-to-device, screen cast | available |
| **Cloud Vision / Cloud TTS / STT / Translation v3** | heavier-than-ML-Kit cloud variants | key-gated |

### 4.2 Notably NOT available on the X3
- **ARCore** — the vendor docs (bundled in the SmartTube port's `rayneo_docs`) state the X3 Pro has **no ARCore**; 6-DoF is "SLAM/ShareCamera only, high power." So no ARCore plane detection, no ARCore depth, no Sceneform. (This is also why the object-tracking POC in the starter guide goes the MultiSet-REST route.)
- **Google Fit APIs** — sunset end of 2026; use Health Connect.
- **Wear-only** Health Services live measurement — needs the watch, not the glasses.

### 4.3 The standout direct win
Like WeatherKit on the Apple side, several Google services are pure REST + key and need nothing but the OkHttp stack already in the app — Maps/Places/Routes/Air-Quality/Directions. The glasses already call these; adding a new one is a tool class, not an architecture.

---

## 5. Glasses-side rendering (the light half)

All starter-guide patterns: receive the health/AI envelope (`CompanionServer` `/api/*`, relay pull, or BLE), render a metrics tier beside Events/Tasks/News (`renderUnipanelTieredHud`), white-on-black for daylight, **throttle redraws** (the dim-mode reboot lesson — never redraw faster than the data changes, and never run an on-device LLM under the mask), feed notable changes to the readout engine, ring the bell for threshold events. If you've read the starter guide, this is an afternoon.

---

## 6. Privacy, performance & honest limits

- **Health data is sensitive** — Health Connect enforces per-type, user-revocable, runtime permissions and rejects background access without a foreground rationale. Keep vitals on-device; any relay must be the user's own and disclosed.
- **The AR1 is thermally tight** — 4 cores, 4 GB, 245 mAh, >500 mA sustained = trouble (starter guide §12). An on-device LLM (§2.2) is a sustained load: gate it, keep generations short, and obey the masked-redraw rule or you'll reproduce the reboot.
- **No medical claims** — consumer wearable SpO₂/HR are wellness signals, not diagnostics.
- **No on-glasses heart sensor** — the single most important expectation to set: live vitals require a wearable. The glasses are the display + reasoning surface, not the sensor.
- **APIs move fast** — Gemini Nano, ML Kit GenAI, and Health Connect all shipped major changes in 2025–2026. A frontier model should fetch the live Android docs (links below) and confirm every signature before building; treat the code here as a starting shape.

---

## Sources & where to confirm

On-device AI (2026):
- [Google officially unveils Gemini Nano 4 for on-device Android AI — Ubergizmo](https://www.ubergizmo.com/2026/04/gemini-nano-4/)
- [Add on-device AI to your Android app in 2026 (Gemini Nano + AICore) — Stora](https://stora.sh/blog/2026-04-13-on-device-ai-android-app-gemini-nano-guide) · [Practical guide to Gemini Nano — Medium](https://medium.com/@Y4583L/supercharge-your-android-app-with-on-device-ai-a-practical-guide-to-gemini-nano-d9f6cccb39e6)
- [How to check if your Android phone supports Gemini AI features in 2026 — Memeburn](https://memeburn.com/how-to-check-your-android-phone-supports-gemini-ai-features-in-2026/) (the device-gating that excludes the AR1)
- Android docs to confirm: ML Kit GenAI APIs, AICore, MediaPipe LLM Inference, LiteRT.

Health Connect:
- [Health Connect — Android Developers](https://developer.android.com/health-and-fitness/health-connect) · [Read raw data](https://developer.android.com/health-and-fitness/health-connect/read-data) · [Data types](https://developer.android.com/health-and-fitness/health-connect/data-types) · [Vitals experiences](https://developer.android.com/health-and-fitness/health-connect/experiences/vitals)
- [Health Connect Jetpack releases](https://developer.android.com/jetpack/androidx/releases/health-connect) · [Health Services (Wear OS)](https://developer.android.com/health-and-fitness/health-services/health-platform)

Companions: `FABLE_X3_STARTER_GUIDE.md` (glasses hardware truths) · `FABLE_X3_APPLE_BRIDGE_GUIDE.md` (the Apple mirror — same relay envelope, opposite ecosystem).
