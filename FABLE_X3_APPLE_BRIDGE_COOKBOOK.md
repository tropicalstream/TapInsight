# Fable Cookbook — Building the Apple-Bridge Health/AI App for the RayNeo X3 Pro, Bottom-Up

*A complete, step-by-step implementation guide for a frontier model building a real app from an empty project to a running pair (iPhone companion + glasses consumer). Pairs with `FABLE_X3_APPLE_BRIDGE_GUIDE.md` (the API reference) and `FABLE_X3_STARTER_GUIDE.md` (the hardware truths). Where the reference says what's possible, this says how to build it — and what will go wrong.*

**Running example used throughout:** a glasses HUD showing a **graphic representation of all the user's Apple Health metrics, refreshing every 20 seconds**, navigable **by voice/AI** ("show my heart rate trend", "am I in a good zone?"), with the data flowing from an Apple Watch through an iPhone bridge to the Android glasses.

---

## 0. How to use this cookbook

Read `FABLE_X3_STARTER_GUIDE.md` (glasses hardware) and `FABLE_X3_APPLE_BRIDGE_GUIDE.md` (the bridge architecture and WWDC26 APIs) first. The defining difference from the Android cookbook: **the X3 is not an Apple platform, so half this app is a Swift iPhone app and half is the Android glasses consumer.** The build order is: **architecture → two scaffolds → entitlements/manifest → hardware → HealthKit read → relay → glasses UI/UX → AI → threading → performance → failure modes → FAQ → test plan.**

The glasses half is *identical* to the Android cookbook's glasses half — same binocular HUD, same 20 s render discipline, same JSON envelope. The work that differs is the **iPhone bridge**. So this document focuses there and points back to the Android cookbook for the shared glasses rendering.

---

## 1. Architecture — there is no native option here

Unlike Android (where compute runs on the glasses), Apple Health/AI **cannot** run on the Android glasses at all. The architecture is fixed:

```
Apple Watch ──HealthKit──▶ iPhone companion app (Swift) ──relay──▶ X3 Pro glasses (Android)
 sensors                    reads Health, runs Apple AI,            renders dashboard,
                            relays JSON every 20s                   voice nav, feeds AI
```

Three decisions to lock first:
- **Watch or phone-only?** A paired Apple Watch is the real sensor; iPhone-only HR/SpO₂ is sparse-to-nonexistent. For the running example, assume an Apple Watch + a WatchKit extension for the live stream.
- **Where does the AI run?** On-device Foundation Models (WWDC26, private, multimodal) on the iPhone, or route to Claude/Gemini via the new Language Model protocol. The glasses never run Apple AI — they display its output.
- **Relay transport?** BLE / LAN for the live tick, CloudKit or a Cloudflare-tunnelled relay for system-of-record (bridge guide §4). Pick before building.

---

## 2. Two scaffolds

**iPhone app** (Xcode 27, SwiftUI, iOS 27): targets `HealthBridge` (phone) + `HealthBridge Watch` (WatchKit extension for the live workout stream). **Glasses app**: the same Android two-module project as the Android cookbook §2 — but the glasses *consume* the relay; they don't read Health Connect. Strip the Health Connect dependency from the glasses module; keep OkHttp, the relay client, the binocular HUD, and the voice/AI.

---

## 3. Entitlements (iPhone) & manifest (glasses)

### iPhone capabilities
- **HealthKit** + **Background Delivery** entitlement.
- **iCloud → CloudKit** + **Push Notifications** + **Background Modes** (Remote notifications; "Uses Bluetooth LE accessories" if BLE-relaying).
- **App Groups** if the Watch extension shares storage.

`Info.plist` purpose strings (the app crashes on first access without them): `NSHealthShareUsageDescription`, `NSHealthUpdateUsageDescription`, `NSBluetoothAlwaysUsageDescription`, plus `NSLocationWhenInUseUsageDescription` if also bridging location.

### Glasses manifest
Identical to the Android cookbook §3 **minus the Health Connect permissions** (the glasses receive health over the relay, they don't read HealthKit or Health Connect). Keep `com.rayneo.mercury.app` meta-data, the `AR_APP` launcher category, `RECORD_AUDIO` for voice nav, and the foreground-service entry.

---

## 4. Hardware constraints checklist

Same glasses-side table as the Android cookbook §4 (640×480, black=transparent, thermal limit, masked-redraw reboot lesson, temple-click-is-a-KEY, no Web Speech). **Plus** the Apple-side reality: the **Apple Watch is the only good sensor** — phone-only vitals are sparse; set expectations accordingly.

---

## 5. Reading HealthKit (the iPhone side)

### 5.1 Authorization
```swift
let store = HKHealthStore()
let read: Set<HKObjectType> = [
    HKQuantityType(.heartRate), HKQuantityType(.oxygenSaturation),
    HKQuantityType(.heartRateVariabilitySDNN), HKQuantityType(.respiratoryRate),
    HKQuantityType(.vo2Max), HKQuantityType(.activeEnergyBurned),
    HKQuantityType(.stepCount), HKObjectType.activitySummaryType(),
    HKObjectType.workoutType()
]
try await store.requestAuthorization(toShare: [], read: read)
```
Per-type, user-revocable; HealthKit deliberately does **not** tell you if read access was denied (privacy) — you discover it by getting no samples. Plan for silent denial (§11).

### 5.2 The live stream + background delivery
```swift
// Dense live HR: anchored query that stays open.
let q = HKAnchoredObjectQuery(type: HKQuantityType(.heartRate), predicate: nil,
                              anchor: nil, limit: HKObjectQueryNoLimit) { _,s,_,_,_ in push(s) }
q.updateHandler = { _,s,_,_,_ in push(s) }
store.execute(q)
// Works in pocket: background delivery + observer query wakes the app on each sample.
store.enableBackgroundDelivery(for: HKQuantityType(.heartRate), frequency: .immediate) {_,_ in}
```
The densest, lowest-latency stream is an `HKLiveWorkoutBuilder` in the **Watch extension**, forwarded to the phone over `WatchConnectivity`. SpO₂ is sampled sparsely (usually sleep) — carry a freshness flag (§11), exactly as the Android cookbook does.

### 5.3 The 20-second cadence — same rule, phone side
**The 20 s timer lives on the iPhone**, which composes a snapshot and relays it. The glasses redraw on receipt. Don't poll HealthKit in a tight loop — coalesce the latest values from the open anchored query into a snapshot every 20 s:
```swift
Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { _ in
    relay(snapshot: latestSnapshot())   // POST/BLE/CloudKit the JSON envelope
}
```

---

## 6. The relay — identical envelope, so the glasses code is shared

Emit the **exact same JSON** as the Android cookbook (that's the point — one glasses renderer for both ecosystems):
```json
{ "type":"health","ts":1718200000,"hr":78,"hrZone":2,"spo2":98,"hrv":42,
  "respRate":14,"kcalActive":142,"steps":8421,"vo2max":41.2,
  "rings":{"move":0.62,"exercise":0.40,"stand":0.75},
  "source":"Apple Watch","spo2Fresh":false }
```
Transports (bridge guide §4): **(a)** BLE GATT or LAN POST to the glasses' companion server for the live tick; **(b)** CloudKit private DB + `CKQuerySubscription` silent push as durable sync; **(c)** Cloudflare-tunnelled relay the glasses pull from, IP-proof. Recommended: (a)+(c). Health data on (b)/(c) stays in the user's **private** database / their own relay, disclosed and opt-in (App Review enforces this).

---

## 7. Glasses UI/UX — identical to the Android cookbook

The binocular dashboard, the 640×480 layout, native-Canvas-not-WebView rendering, the once-per-tick `invalidate()`, the freshness line, the temple/focus navigation, suspend-under-mask — **all identical to `FABLE_X3_ANDROID_NATIVE_COOKBOOK.md` §7.** Build it once; it doesn't care that the bytes came from HealthKit instead of Health Connect. The only label difference: `source: "Apple Watch"`.

---

## 8. AI navigation — on the iPhone, displayed on the glasses

Here the ecosystems diverge meaningfully. Apple's WWDC26 Foundation Models is **multimodal and provider-agnostic**, and it runs on the **iPhone**, not the glasses. So the flow is: glasses capture voice (native Android STT) + optionally a camera frame → relay to the iPhone → iPhone runs the reasoning (on-device Apple model, or Claude/Gemini via the Language Model protocol) → relays text back → glasses speak/show it.

```swift
// iPhone bridge, WWDC26 Foundation Models — multimodal, grounded in the snapshot:
let session = LanguageModelSession(model: .systemDefault)   // or a cloud provider
let reply = try await session.respond(to: Prompt {
    "Health HUD assistant. HR \(s.hr) bpm (zone \(s.hrZone)), SpO2 \(s.spo2)%\(s.spo2Fresh ? "" : " sampled, not live")."
    "User said: \"\(transcript)\". One short spoken sentence."
    "If they want another view end with ACTION:SHOW=<overview|hr|rings|sleep>. No diagnoses."
    if let frame = cameraFrame { frame }   // multimodal: the glasses' camera frame
})
relayToGlasses(text: reply.content)
```
Same grounding/constraint/no-diagnosis discipline as the Android cookbook §8. The privacy win: on-device Apple inference means vitals never leave the phone. The latency cost: voice→phone→model→glasses adds a hop versus Android's on-glasses path — show a "thinking" state.

---

## 9. Threading & lifecycle

- **iPhone**: HealthKit queries on the main actor for store calls; relay on a background task; background delivery + observer query keep it alive in-pocket; handle the app being suspended (CloudKit silent push or BLE keep-alive to resume).
- **Watch extension**: `HKLiveWorkoutBuilder` runs during a workout session only; forward over `WatchConnectivity`, which itself sleeps when the apps aren't foreground.
- **Glasses**: identical to the Android cookbook §9 — IO coroutine for the relay client, main thread for UI, persist last snapshot, survive process death, don't tear down in `onPause` (sleep button).

---

## 10. Performance, battery & thermals

Two budgets now:
- **Glasses**: identical to the Android cookbook §10 — 20 s native redraw is thermally invisible; the danger is over-rendering, WebView charts, AI-per-tick, and redraws under the mask.
- **iPhone**: background HealthKit delivery and a 20 s relay timer are light, but **on-device Foundation Models inference is not free** — run it on user request, not per tick, or you'll drain the phone. CloudKit pushes are cheap; BLE keep-alive is the bigger phone-battery cost — prefer pull-relay or push over a held BLE link for always-on.

---

## 11. Potential issues (exhaustive)

**HealthKit / Apple-side**
- *Silent read denial.* HealthKit won't tell you access was refused — you just get nothing. Detect "no samples ever" and prompt the user to check Settings → Privacy → Health. Don't render flat-zero.
- *SpO₂ stale.* Apple Watch measures it periodically/at night, not continuously. Same `spo2Fresh` flag and `*` marker as the Android cookbook.
- *No Apple Watch.* iPhone-only HR/SpO₂ is essentially absent. Gate those metrics behind "pair a Watch"; show steps/energy (iPhone motion can do those).
- *Units.* HealthKit SpO₂ is a 0–1 fraction (×100 for %); HR in count/min; energy in kcal. Convert explicitly with `HKUnit`.
- *Workout-only zones.* The WWDC26 live HR-zone API delivers during a workout session; outside one, there's no live zone. Don't show a zone gauge at rest as if it's live.
- *Background suspension.* iOS suspends the app; without background delivery / silent push / BLE keep-alive the 20 s relay stops when the phone locks. Wire at least one wake path.
- *App Review.* Sending Health data off-device (to your relay) without explicit, disclosed consent gets rejected. The relay must be the user's own and opt-in; say so in the UI and privacy policy.

**Relay / cross-platform**
- *Two clocks, two ecosystems.* iPhone and glasses clocks differ; trust the sample's own timestamp, show "Ns ago."
- *BLE pairing drops.* Phones aggressively sleep BLE; reconnect with backoff, fall back to pull-relay, show last-good + staleness on the glasses.
- *CloudKit latency.* Silent pushes aren't instant; CloudKit is the durable record, not the live tick — pair it with (a) for real-time.
- *Envelope drift.* If you change the JSON on one side, the shared glasses renderer breaks for both ecosystems — version the envelope.

**Glasses-side** — identical to the Android cookbook §11 (reboot from over-render, daylight readability, one-eye rendering, cursor/tap, AI hallucination/diagnosis/action-validation, network loss, process death).

---

## 12. FAQ

**Why can't the glasses just read Apple Health directly?** They're Android. HealthKit is Apple-only; there is no Android HealthKit client. The iPhone must bridge it.

**Do I really need an iPhone *and* an Apple Watch?** The Watch is the sensor; the iPhone is the bridge that has HealthKit and the AI. For live vitals, yes, both. (The glasses + a Watch with no iPhone won't work — the Watch's data lives in the iPhone's HealthKit.)

**Why 20 seconds?** Same as the Android answer: health data changes slowly, tighter cadences cost battery/heat for no gain. Live HR during a workout is the one 1–5 s case, via the Watch live builder.

**Where does the AI run?** On the iPhone (Apple Foundation Models, or Claude/Gemini via the WWDC26 provider protocol). The glasses display/speak the result. On-device keeps vitals private.

**Can the glasses' camera feed Apple's multimodal model?** Yes — relay the frame to the iPhone, include it in the multimodal prompt (bridge guide §5.1). It's a heavier call; gate behind an explicit ask.

**Is this more or less work than the Android version?** More — you build a Swift iPhone app (+ Watch extension) *and* the glasses consumer. The glasses half is shared code; the iPhone half is the added cost.

**Direct-from-glasses Apple services?** Only the REST ones (WeatherKit, Maps Server, MusicKit, APNs) — health is not among them.

**How honest must I be about SpO₂?** Very — Apple Watch SpO₂ is periodic, not live. Mark it sampled, show its timestamp, and have the AI say so.

**Will it pass App Review?** The iPhone app will if Health data handling is disclosed, consented, and the relay is the user's own. The glasses app isn't on the App Store (it's an Android APK), but the iPhone bridge is, and that's where review applies.

---

## 13. Test plan / acceptance

Glasses-side tests are identical to the Android cookbook §13 (empty state, live update within 20 s, freshness, thermal soak, dim-mask suspend, voice nav grounded + no diagnosis, network loss, relaunch, units). **Add the Apple-bridge tests:**
1. **Authorization denial** — deny Health access; confirm the app detects "no data" and prompts, not flat-zero.
2. **In-pocket delivery** — lock the phone, trigger a real HR change on the Watch, confirm the glasses update within ~20 s (background delivery working).
3. **Watch-less** — no Watch paired: HR/SpO₂ gated, steps/energy still shown.
4. **Bridge reconnect** — kill BLE/Wi-Fi between phone and glasses; confirm reconnect + last-good staleness.
5. **CloudKit durability** — fully quit the phone app, reopen; confirm the latest record syncs back.

---

## 14. Build & install

iPhone: Xcode → run on device (HealthKit needs a real device + a real Watch for live data; the simulator has no Health data). Glasses:
```bash
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Two binaries, two stores (App Store for the iPhone bridge, sideload/RayNeo store for the glasses APK). Commit and push at every milestone.

---

*Companions: `FABLE_X3_APPLE_BRIDGE_GUIDE.md` (API reference) · `FABLE_X3_STARTER_GUIDE.md` (hardware) · `FABLE_X3_ANDROID_NATIVE_COOKBOOK.md` (the shared glasses half + the native-Android alternative).*
