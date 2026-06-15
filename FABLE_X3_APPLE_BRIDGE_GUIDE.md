# Fable Bridge Guide — RayNeo X3 Pro × Apple Health, iCloud & Apple Intelligence

*A build guide for a frontier model implementing Apple-ecosystem features on the RayNeo X3 Pro. Grounded in the WWDC 2026 developer releases (June 2026). Companion to `FABLE_X3_STARTER_GUIDE.md`.*

**Where this lives:** repo root, beside `FABLE_X3_STARTER_GUIDE.md`. Browsable HTML at `docs/x3-apple-bridge.html`, linked from the guide library at `docs/x3-guide-hub.html`.

---

## 0. Read this first — the one architectural fact that governs everything

**The RayNeo X3 Pro runs Android 14 (Snapdragon AR1 Gen1). It is not an Apple platform.** HealthKit, CloudKit, the Foundation Models framework, App Intents, VisionKit — all of these are Apple-platform-only Swift/Objective-C frameworks. **None of them can be called from the glasses directly.**

So "implement Apple Health on the X3 Pro" does not mean porting HealthKit. It means building a **three-tier bridge**:

```
┌─────────────────┐     HealthKit / Foundation Models      ┌──────────────────┐     relay (the existing    ┌─────────────────┐
│  Apple Watch    │ ───── (live sensors, on-wrist) ──────▶ │  iPhone companion │ ──── TapInsight pattern) ─▶ │  RayNeo X3 Pro  │
│  (sensors)      │                                        │  app (Swift)      │     BLE / HTTPS / push     │  (Android HUD)  │
└─────────────────┘                                        └──────────────────┘                            └─────────────────┘
   SpO₂, HR, HRV,                                            reads Health, runs                               renders metrics
   workouts, rings                                           on-device AI, syncs                              on binocular HUD,
                                                             iCloud, relays JSON                              feeds the assistant
```

The Apple Watch senses, the iPhone reads + reasons + relays, the glasses display and act. This mirrors exactly how RayNeo already does GPS (the phone owns the GNSS; the glasses receive it over IPC — see `FABLE_X3_STARTER_GUIDE.md` §IPC). You are extending that proven pattern to health and AI.

**The one exception** — a few Apple services expose **REST APIs with token auth** that the Android glasses *can* call directly, no iPhone required: **WeatherKit REST**, **Apple Maps Server API**, **MusicKit web**, and **APNs** (as a delivery path). Those are noted in §6. Everything else needs the phone bridge.

This guide is therefore two builds: an **iPhone companion app** (most of the work) and the **glasses-side consumer** (light — it's the patterns you already know from the starter guide).

---

## 1. What WWDC 2026 actually changed (verified)

These are the June 2026 facts that make this bridge far more capable than it would have been a year ago. Sources at the bottom; treat anything not sourced as design, not gospel.

**Foundation Models framework — now a hybrid, provider-agnostic, multimodal platform.** This is the headline for AR. As of WWDC26 the on-device Apple model is reached through a **Language Model protocol** that *also* accepts cloud models — **Claude, Gemini, or any conforming provider** — behind the same Swift API. Prompts are now **multimodal: you can pass images alongside text**, and Vision tools (OCR, barcode) are callable by the model on-device. "Dynamic Profiles" hot-swap models/tools/instructions within one session. Small-Business-Program apps under 2M downloads get the next-gen model on **Private Cloud Compute at no cloud-API cost**. There's an Evaluations framework, an `fm` CLI, and a Python SDK.

> **Why this matters for the glasses:** the iPhone bridge can take a **camera frame from the X3 Pro**, pair it with **live health context from the Watch**, and run a **multimodal reason step on-device** (or route to Claude/Gemini through the same protocol) — "what am I looking at, and should I be concerned given my heart rate just spiked?" That's a genuinely novel AR capability, and it's the centerpiece use case in §5.

**HealthKit — live training zones exposed to third parties.** WWDC26 opened **heart-rate and cycling-power zones** to third-party apps: read the user's zone configuration, receive **live zone updates during a workout**, and set custom zone boundaries. iOS 27 also adds perimenopause/menopause tracking. (The rest of HealthKit — HR, SpO₂, HRV, VO₂ max, workouts, rings — has been stable and available for years; §3 covers it.)

**App Intents — on-screen awareness via the new View Annotations API**, plus entity/intent schemas that feed the Spotlight semantic index and let Siri act on app content. Relevant if the iPhone app should expose glasses data to Siri/Shortcuts.

**Image Playground API** (Private Cloud Compute generative images) and **Visual Intelligence / VisionKit** (define entities, process images, one-tap actions) round out the on-device vision stack the bridge can call.

---

## 2. The iPhone companion app — project setup

A SwiftUI iOS 27 app (Xcode 27). Capabilities to enable in the target:

- **HealthKit** (+ "Background Delivery" entitlement) — read body metrics.
- **iCloud → CloudKit** (+ Push Notifications, Background Modes: Remote notifications) — sync + silent wake.
- **Background Modes** → "Background fetch" and "Uses Bluetooth LE accessories" if relaying over BLE to the glasses.
- **App Groups** — if a Watch extension shares storage with the phone app.

`Info.plist` must carry purpose strings or the app crashes on first Health access: `NSHealthShareUsageDescription`, `NSHealthUpdateUsageDescription`, `NSBluetoothAlwaysUsageDescription` (BLE relay), `NSLocationWhenInUseUsageDescription` (if you also bridge location).

Minimum deployment iOS 26 to use Foundation Models at all; iOS 27 for the WWDC26 multimodal/provider features and HealthKit zones.

---

## 3. Reading body health metrics (HealthKit)

### 3.1 Authorization

Request the narrowest set you need; Health permissions are per-type and user-revocable.

```swift
import HealthKit
let store = HKHealthStore()

let readTypes: Set<HKObjectType> = [
    HKQuantityType(.heartRate),
    HKQuantityType(.restingHeartRate),
    HKQuantityType(.heartRateVariabilitySDNN),
    HKQuantityType(.oxygenSaturation),          // SpO₂ / blood oxygen
    HKQuantityType(.respiratoryRate),
    HKQuantityType(.vo2Max),
    HKQuantityType(.activeEnergyBurned),
    HKQuantityType(.stepCount),
    HKObjectType.workoutType(),
    HKObjectType.activitySummaryType(),         // Move / Exercise / Stand rings
    HKQuantityType(.bodyTemperature)
]
try await store.requestAuthorization(toShare: [], read: readTypes)
```

### 3.2 The metrics that matter for AR glasses, and how to get each

| Metric | HealthKit identifier | How / cadence | Notes |
|---|---|---|---|
| **Heart rate** | `.heartRate` | `HKAnchoredObjectQuery` + `updateHandler`; live during a Watch workout | bpm; the most demanded real-time HUD number |
| **Blood oxygen (SpO₂)** | `.oxygenSaturation` | periodic background samples | 0–1 fraction → ×100 for %; not continuous, sampled |
| **HR variability** | `.heartRateVariabilitySDNN` | anchored query | ms; stress/recovery proxy |
| **Resting HR** | `.restingHeartRate` | daily | trend, not live |
| **Respiratory rate** | `.respiratoryRate` | sleep/rest samples | breaths/min |
| **VO₂ max** | `.vo2Max` | occasional | fitness trend |
| **Live HR/power zones** | (WWDC26 zone API) | live updates during workout | **new in WWDC26** — read config + live zone, set custom boundaries |
| **Active energy / exercise** | `.activeEnergyBurned`, `workoutType()` | live builder or query | kcal + workout sessions |
| **Activity rings** | `activitySummaryType()` | `HKActivitySummaryQuery` | Move/Exercise/Stand |
| **Steps** | `.stepCount` | cumulative query | |
| **Body temperature** | `.bodyTemperature` | samples | |

### 3.3 Live streaming (the real-time HUD path)

For numbers that change second-to-second (heart rate, zones, energy during a workout), use an **anchored object query that stays open** and pushes deltas:

```swift
func streamHeartRate(_ onSample: @escaping (Double, Date) -> Void) {
    let hr = HKQuantityType(.heartRate)
    let unit = HKUnit.count().unitDivided(by: .minute())
    let q = HKAnchoredObjectQuery(type: hr, predicate: nil,
                                  anchor: nil, limit: HKObjectQueryNoLimit) { _, samples, _, _, _ in
        handle(samples, unit, onSample)
    }
    q.updateHandler = { _, samples, _, _, _ in handle(samples, unit, onSample) }
    store.execute(q)
}
private func handle(_ samples: [HKSample]?, _ unit: HKUnit, _ cb: (Double, Date) -> Void) {
    (samples as? [HKQuantitySample])?.forEach { cb($0.quantity.doubleValue(for: unit), $0.endDate) }
}
```

The richest live stream comes from an **`HKLiveWorkoutBuilder` on a paired Apple Watch app** (a WatchKit extension you add to the same project): it surfaces HR, energy, distance, and the new zone updates at sensor cadence, far tighter than phone-side polling. The Watch extension forwards samples to the phone over `WatchConnectivity`, the phone relays to the glasses.

### 3.4 Background delivery (so the bridge works with the phone in your pocket)

```swift
store.enableBackgroundDelivery(for: HKQuantityType(.heartRate), frequency: .immediate) { _, _ in }
// Pair with a long-lived HKObserverQuery whose handler wakes the app,
// reads the new sample, relays it to the glasses, then calls completionHandler().
```

This is what lets a glanceable HR appear on the glasses while the iPhone is asleep in a pocket — the OS wakes the app on each new Health sample.

---

## 4. The relay — getting data to the glasses

You have three transports; pick by scenario. All carry the **same compact JSON envelope** so the glasses-side consumer is transport-agnostic:

```json
{ "type": "health", "ts": 1718200000, "hr": 78, "spo2": 98, "hrZone": 2,
  "kcalActive": 142, "rings": {"move": 0.62, "exercise": 0.40, "stand": 0.75} }
```

**(a) Direct BLE / LAN — lowest latency, same-network.** The X3 already runs a companion HTTP server (port 19110, see starter guide §Companion) and accepts `/api/notify`. Extend it with an `/api/health` endpoint (token-auth, same session-token scheme) and have the iPhone POST the envelope. Or, for sub-second live HR, a BLE GATT characteristic the glasses subscribe to — the X3 has BT 5.3.

**(b) iCloud / CloudKit — works anywhere, survives app death.** Write samples to a CloudKit **private database** record; register a `CKQuerySubscription` so a **silent push** wakes a tiny relay (a Mac/server, or the iPhone itself) that forwards to the glasses. CloudKit is the durability layer: the user's data syncs across their own devices, and the glasses pull the latest on reconnect. Use this as the **system of record**; use (a) for the live tick.

**(c) Cloudflare-tunnelled relay — the TapInsight pattern, IP-proof.** The exact `tools/image_relay.py` model from the starter guide: the iPhone POSTs to a Cloudflare-tunnelled relay, the glasses **pull** from it (works over any network, no glasses IP needed). Reuse `RelayUrlHelper` and the pull-sync engine verbatim; add a health channel beside the media channel.

Recommended: **(c) for the system-of-record + away-from-home, (a) for the live HUD number when on the same network.** CloudKit (b) if you want zero server infrastructure and Apple-native sync.

---

## 5. The Apple Intelligence layer — the actual reason to do this

This is where WWDC26 pays off. The iPhone bridge isn't just a pipe; it's a **reasoning node** sitting between the Watch's senses and the glasses' eyes.

### 5.1 Multimodal AR reasoning (the flagship use case)

The X3 camera captures a frame (starter guide §Camera — JPEG, 90° rotation fix). The bridge receives it, pairs it with live health context, and runs **one multimodal Foundation Models call**:

```swift
import FoundationModels
// WWDC26: prompts accept images + text; pick the provider via the
// Language Model protocol — Apple on-device, or Claude/Gemini in the cloud.
let session = LanguageModelSession(model: .systemDefault)   // or a cloud provider
let reply = try await session.respond(to: Prompt {
    "The user is wearing AR glasses. Their heart rate is \(hr) bpm, SpO₂ \(spo2)%."
    "Here is what their glasses camera sees:"
    cameraFrame                       // multimodal image input — new in WWDC26
    "Briefly: what are they looking at, and is anything notable given their vitals?"
})
relayToGlasses(text: reply.content)   // rendered on the binocular HUD / spoken via TTS
```

The reply goes back to the glasses HUD or the existing readout-engine TTS (starter guide §Audio). On-device keeps health data private (no cloud); the provider protocol means you can A/B Apple's model against Claude/Gemini without rewriting the app.

### 5.2 Health-aware proactive prompts

`HKObserverQuery` fires on an SpO₂ dip or an HR-zone change → the bridge composes a short Foundation Models summary → pushes a HUD notification through the glasses' existing bell (`NotificationCenter` / `/api/notify`). "You've been in Zone 4 for 12 minutes — ease off?" entirely on-device.

### 5.3 App Intents / Siri surface (optional)

Expose the glasses' state to Siri via App Intents schemas (WWDC26 View Annotations + entity schemas), so "Hey Siri, what's my heart rate on my glasses" works through the system. Phone-side only; the glasses never see Siri.

---

## 6. Apple services the glasses CAN call directly (no iPhone)

These have **REST APIs with token auth** — the Android glasses hit them straight, reusing the OkHttp/JSON stack from the starter guide:

| Service | Endpoint shape | Auth | AR use |
|---|---|---|---|
| **WeatherKit REST** | `weatherkit.apple.com/api/v1/weather/...` | JWT signed with a `.p8` key (ES256) | live conditions on the HUD, no phone |
| **Apple Maps Server API** | `maps-api.apple.com/v1/...` | JWT → access token | geocode / directions / search for nav |
| **MusicKit (web)** | `api.music.apple.com/v1/...` | developer token + user token | now-playing, library |
| **APNs** | `api.push.apple.com` | JWT (`.p8`) | push channel into a relay |

The JWT signing is the only fiddly part — sign once on a server (or the relay), cache the token, refresh before expiry. WeatherKit in particular is a clean direct win: the glasses already do HTTP, so a weather HUD needs no iPhone at all.

Everything else Apple (Find My, Wallet, full MapKit, on-device Vision) stays phone-side or has no public API — route it through the bridge or skip it.

---

## 7. Glasses-side consumer (the light half)

On the X3, this is all patterns from `FABLE_X3_STARTER_GUIDE.md`:

1. **Receive** the JSON envelope — extend `CompanionServer` with `/api/health` (token-auth), or add a health channel to the relay pull-sync (`RelayMediaSync` sibling), or subscribe to the BLE characteristic.
2. **Render** on the binocular HUD — a metrics tier beside the existing Events/Tasks/News tiers (the `renderUnipanelTieredHud` pattern); white-on-black for daylight, throttled redraws (the dim-mode reboot lesson — never redraw faster than the data changes).
3. **Speak / react** — feed notable changes to the readout engine; ring the bell via `NotificationCenter` for threshold events.
4. **Feed the assistant** — health context becomes part of the voice pipeline's prompt, and the camera frame is what the bridge reasons over in §5.1.

No new hardware knowledge required — if you've read the starter guide, the glasses half is an afternoon.

---

## 8. Privacy, App Review, and honest limits

- **Health data is sensitive.** Keep it on-device where possible (Foundation Models on-device is the privacy win); if it touches CloudKit it stays in the user's **private** database, never a public/shared one. Apple's App Review rejects health apps that send Health data to third-party servers without explicit consent — the Cloudflare relay (§4c) must be the user's own, disclosed, and opt-in.
- **No medical claims.** SpO₂/HR from consumer wearables are wellness signals, not diagnostics. Anything the assistant says about vitals must avoid diagnosis; surface numbers and trends, not verdicts.
- **The Watch is the good sensor.** Phone-only HR/SpO₂ is sparse; the live, dense stream needs an Apple Watch + a WatchKit extension. Set expectations accordingly.
- **Verify against current docs.** This guide is anchored to the WWDC26 releases as of June 2026; Foundation Models and HealthKit APIs are evolving fast. Before building, a frontier model should fetch the live Apple docs (links below) and the specific WWDC26 session videos — treat any API signature here as a starting shape to confirm, not a contract.

---

## Sources & where to confirm

WWDC26 primary:
- Apple — [WWDC26 Apple Intelligence guide](https://developer.apple.com/wwdc26/guides/apple-intelligence/) (Foundation Models: provider protocol, multimodal, PCC; App Intents View Annotations; Image Playground; Visual Intelligence)
- Apple — [What's new in the Foundation Models framework (WWDC26 session 241)](https://developer.apple.com/videos/play/wwdc2026/241), [Bring an LLM provider to the Foundation Models framework (339)](https://developer.apple.com/videos/play/wwdc2026/339), [Build with the new Apple Foundation Model on Private Cloud Compute (319)](https://developer.apple.com/videos/play/wwdc2026/319)
- [MacRumors — Everything Apple Announced at WWDC 2026](https://www.macrumors.com/2026/06/08/wwdc-2026-recap/) · [iClarified — the 263 WWDC 2026 features](https://www.iclarified.com/101152/here-are-the-263-new-features-revealed-in-apples-massive-wwdc-2026-slide)
- [Appbot — Foundation Models becomes a hybrid AI platform](https://appbot.co/blog/apple-wwdc-2026-ai-foundation-model-update/) · [TechTimes — Foundation Models swaps AI providers without code changes](https://www.techtimes.com/articles/318039/20260609/wwdc-2026-developer-tools-foundation-models-now-swaps-ai-providers-without-code-changes.htm)
- [the5krunner — watchOS 27 details](https://the5krunner.com/2026/06/08/watchos-27-features-compatibility/)

Standing Apple docs to confirm against (fetch live before building):
- HealthKit, HKAnchoredObjectQuery, HKLiveWorkoutBuilder, enableBackgroundDelivery
- CloudKit (CKQuerySubscription, private database), WatchConnectivity
- FoundationModels (LanguageModelSession, Language Model protocol, multimodal Prompt)
- WeatherKit REST, Apple Maps Server API (JWT auth)

Companion: `FABLE_X3_STARTER_GUIDE.md` (the glasses-side hardware truths every section above leans on).
