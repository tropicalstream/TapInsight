# Unipanel v2 — Service Refactor Plan

The goal: get voice back in a unipanel build *without* fighting Android's
task model. Tapbrowser stays foreground; visionclaw Activity goes away
(or becomes a tiny settings shell); the Gemini Live pipeline lives in a
long-running `microphone`-typed `ForegroundService` that tapbrowser binds
to from the overlay.

This file exists because the work crosses ~600 lines of pipeline logic
plus ~25 `runOnUiThread { chatFragment.* }` call sites, and trying to
land it in a single commit produced the v1 / v2 failures. Each phase
below is small enough to ship and verify on-device before the next.

## Why a Service

Two failed alternatives, documented for posterity:

1. **`moveTaskToBack(true)` on visionclaw.** Backgrounded the whole
   shared task because both Activities had the default `taskAffinity`,
   the launcher killed the process. (Commit `bccac80` disabled this.)

2. **Separate `taskAffinity` + `REORDER_TO_FRONT` bounce-back.** Lower
   surgery but still fragile on RayNeo's OEM launcher — the exact thing
   that just killed us. Hard to reason about. Hard to test without a
   logcat loop.

A Service avoids both because it has no Activity task at all. It runs
in the same process as whichever Activity is foreground; the foreground
Activity is always tapbrowser; the Service is the publisher of voice
state and the owner of the WebSocket / `AudioRecord` / `AudioTrack`.

## Cut lines (from the agent map)

The cleanest cut line in `app/src/main/java/com/rayneo/visionclaw/MainActivity.kt`
is **right above** `startGeminiAudioCaptureInternal` at **line 9468**.
Everything from there through `releaseGeminiAudioCapture` (line 10362) is
pure pipeline logic and can move 1:1 into the Service:

- `startGeminiAudioCaptureInternal` (~600 lines, contains the inline
  `LiveSessionListener` callback block — the largest single chunk)
- `startGeminiAudioStreaming` (creates `AudioRecord`, attaches effects,
  spawns `GeminiLiveAudioThread`, runs the read loop)
- `stopGeminiAudioStreaming`
- `releaseGeminiAudioCapture`
- `enableRayNeoVoiceAssistantMicRoute` / `disableRayNeoVoiceAssistantMicRoute(Async)`
- `mergeLiveTranscript`, `calculatePcm16Peak`
- `handleGeminiVoiceFailure`
- `armSilenceWatchdog`, `disarmSilenceWatchdog`,
  `handleGeminiLiveIdleTimeout`, `touchGeminiLiveActivity`
- `markUserSpeechActivity`, `noteGeminiOutputActivity`

Plus the state fields that back them (full list in the agent map —
~40 `@Volatile`/`private` fields between lines 506 and 627).

What **stays Activity-side** for now:

- `syncCameraToGeminiState` / `startCameraCapture` / `stopCameraCapture`
  — these need a `LifecycleOwner` and a `Preview.SurfaceProvider` from
  `chatFragment.getCoreEyeSurfaceProvider()`. The Service can *request*
  camera frames via a callback / bridge, but the binding stays on the
  Activity until the chat panel comes out.
- Permission handling. Activities own the runtime permission UX.
- `setupCustomKeyboard`, all the ViewPager / chat-fragment plumbing
  (until phase 5 deletes it).

## Phase plan

Each phase is one commit. Verify boot + voice + browser on-device
between commits before continuing.

### Phase 1 — Foundation (this commit)

- [x] `HudStateBridge` skeleton added (`tapbrowser/.../unipanel/HudStateBridge.kt`).
      Publisher / subscriber pattern matching `ChatCardBridge`. No
      publishes yet; no consumers yet.
- [x] Planning doc (this file).
- [x] No behavior change. Browser still launches alone; voice still off.

### Phase 2 — Application-scope viewModel

The Service needs to share `MainViewModel` with visionclaw (so
`ChatCardBridge.publish` from `viewModel.messages.collect` keeps
working). Two options:

- (a) Lift `MainViewModel` construction into a custom `Application`
      subclass and access it from both Activity and Service via the
      ApplicationContext.
- (b) Convert `MainViewModel` to a process-wide singleton (`object`
      with internal `MutableStateFlow`s) and have the Activity
      `viewModels()` resolve to it.

Option (a) is cleaner (the ViewModel lifecycle integrations stay
intact). It requires:

1. Adding `class VisionClawApp : Application()` and registering it in
   the manifest.
2. Moving `viewModel` construction out of the Activity's `by viewModels()`
   to an application-scoped factory.
3. Auditing every `viewModel.*` call to confirm none assume Activity scope.

### Phase 3 — Voice Service skeleton

- Promote `GeminiSessionForegroundService` to a real Service with a
  Binder (rename to `GeminiVoiceService` to reflect the broader role).
- `Binder` exposes:
  - `activateVoice()` — what `MainActivity.activateChatVoiceAssistant`
    does today but without touching chat-fragment views.
  - `shutdownVoice()` — what `shutdownMultimodalSession` does today.
  - `currentState(): HudStateBridge.State` — for sync at bind time.
- Service `onStartCommand` continues to attach
  `FOREGROUND_SERVICE_TYPE_MICROPHONE` exactly as today.
- visionclaw `activateChatVoiceAssistant` still works the same; just
  routes through the binder when present, falls back to direct calls
  when not. (This keeps voice working through visionclaw if we ever
  re-enable warm-start.)
- tapbrowser overlay binds to the service from its voice-activate
  gesture. The bind is lazy — only happens when the user actually
  taps to start a session.

### Phase 4 — Move the pipeline

The big one. Cut the methods listed in the "Cut lines" section above
from `MainActivity.kt` into a new `core/session/voice/` package inside
visionclaw. The Service holds an instance of this pipeline class.

The ~25 `runOnUiThread { chatFragment.* }` calls inside the
`LiveSessionListener` get replaced with `HudStateBridge.update { ... }`
publishes (transcript, oscilloscope level, connection status, listening
overlay show/hide, HUD notifications).

Transcript publishing through `viewModel.appendUserUtterance` /
`appendLiveAssistantStreamChunk` stays intact — the bridge plumbing
already keys off `viewModel.messages`.

Camera surface integration: the Service exposes a `setCameraFrameSink`
callback; visionclaw's existing camera pipeline calls it instead of
sending frames directly to the WebSocket. Until Phase 5 deletes the
chat panel, the camera surface still lives on `chatFragment`.

### Phase 5 — Delete chat panel UI

Once voice is fully on the Service side, the visionclaw chat panel
is unreachable. We can delete:

- The `R.layout.activity_main` chat content (the ViewPager, listening
  overlay, custom keyboard).
- `ChatPanelFragment` and `MainPagerAdapter`.
- The Activity-side findViewById of chat views.
- The Activity-side `chatFragment.*` calls (~120 sites).

If visionclaw still needs an Activity (settings page, OAuth callbacks),
it becomes a thin shell. Otherwise the Activity can be deleted entirely
and the launcher intent-filter stays exclusively on tapbrowser.

### Phase 6 — tapbrowser overlay subscribes

`tapbrowser/.../MainActivity.kt` subscribes to `HudStateBridge` and
renders the listening state inside the unipanel overlay (alongside the
HUD clock, mini cards, CAM chip already wired). The visionclaw HUD
notification banner moves into the overlay too.

This unblocks all the user-facing voice UX from the chat-panel layout
and completes the "browser as canvas" vision.

## What "voice working" looks like at each phase

| Phase | Browser launches | Voice works | Chat panel rendered |
|-------|------------------|-------------|---------------------|
| current (`bccac80`) | yes | no | no (panel still inflated but invisible per `3198823`) |
| 1 (this commit)     | yes | no | no |
| 2 (App viewModel)   | yes | no | no |
| 3 (Service skeleton)| yes | no (binder exists, not yet wired to pipeline) | no |
| 4 (Pipeline moved)  | yes | **yes** (Service-hosted; activated from tapbrowser bind) | no |
| 5 (Panel deleted)   | yes | yes | gone from code |
| 6 (Overlay HUD)     | yes | yes + visible in overlay | gone |

## Verification checklist (run between every phase)

1. `git status` clean before flashing.
2. Cold boot the device.
3. Tap launcher icon → tapbrowser opens; no flash of chat panel.
4. `adb logcat -d | grep -E "FATAL|AndroidRuntime|moveTaskToBack|Killing"`
   — should be empty except for normal app lifecycle.
5. From phase 4 onward, activate voice from the overlay; confirm
   `GeminiVoiceService` shows up in `dumpsys activity services` and the
   FGS notification is posted (POST_NOTIFICATIONS granted).
6. Speak; confirm Gemini responds.

## UX / HUD spec (from codex, locked in)

The WebView is the HUD's home renderer — **not** disposable browser
content underneath the HUD. On cold launch it shows the TapLinkX3
dashboard tools: QR Scanner, Edit Links, Add Link. Those must remain
visible and tappable at all times the user is in the default state.

The overlay layer is strictly the floating HUD overlay:

  - HUD strip (clock)
  - Mini chat-card stack
  - CAM chip
  - Future expanded chat view (Phase 6+)

Hit-test contract (the three-state hit-test already enforces this in
tapbrowser):

  - **Transparent overlay zones** → taps pass through to dashboard
    tiles / WebView underneath.
  - **Opaque inert overlay zones** → consume taps with NO action; the
    consume is critical so taps that land on overlay backgrounds
    don't leak to dashboard tiles that happen to sit beneath them.
  - **Clickable overlay widgets** → own their own taps; fire their
    onClick handlers.

**Hard rule (codex):** future expanded chat may cover the dashboard
**only temporarily** and MUST have an obvious collapse path back to
the default overlay. The implementation must NEVER permanently bury
QR Scanner, Edit Links, or Add Link behind chat UI.

Interaction model the rest of the refactor must preserve:

  - **Default state**: WebView shows dashboard; overlay (clock + mini
    cards + CAM chip) floats on top; both are reachable.
  - **Tap on the mini-card stack** → expand to a fuller chat view.
    Must include a collapse affordance. Tap outside the expanded view
    → collapse back to default overlay.
  - **Swipe down** (or some equally cheap gesture) → focus toggle
    between HUD overlay and browser. Cursor tap pipeline already
    handles the routing.
  - **Voice activation** → a single dedicated tap. Service-backed
    once Phase 4 lands.

Phase 6 wires the swipe/tap surfaces; Phases 2–5 must not paint
themselves into a corner that makes Phase 6 expensive. Chat content
rendering must grow from "3 mini cards" to "full chat list" without
re-architecting how `ChatCardBridge` is consumed, and without burying
the dashboard.

## Non-goals (for now)

- Fish.audio readout migration. Stays where it is until voice is back.
- Touch / gesture overhaul. Cursor tap pipeline stays as-is.
- Companion app changes.

---

## Phase 4i — LiveToolCallCoordinator extraction (in progress)

Codex's bb20ac4 wired a real ToolDispatcher into the Service path
(unipanel build can now run open_taplink, send_video_list, tapradio,
ask_maps, hermes_agent — not just browser_vision). But ~11
Hermes-era guardrails still live ONLY in MainActivity, so the
Service path lacks the "nuanced all-Gemini" behavior:

  - lookup/list with send_video_list display=cache (no browser open)
  - "show/send the list" → picker with display=glasses
  - "play the first/second/that one/all" → cached-title resolver
  - "play <keyword> on YouTube" → YouTube search + taplink_autoplay
  - "can you play X" capability questions answered verbally, no
    auto-open
  - media-type conflict: YouTube request never routes into TapRadio,
    vice-versa
  - tapradio podcast action requires current consent
  - redundant lookup after a recent suggestion cache hits the cache
  - research_topic local readout / Gemini-output suppression
  - local-agent Hermes/TapClaw direct presentation

Migration sequence (one commit per guard so each is reviewable on
its own):

  4i.1 ✓ Scaffold:
    - app/.../core/session/LiveToolCallActionSink.kt (this commit)
    - app/.../core/session/LiveToolCallCoordinator.kt (this commit)
    - dispatch() stub; no logic moved.
  4i.2 — mediaServiceCapabilityQuestionResponse (pure)
  4i.3 — explicitMediaTypeConflictResponse (pure)
  4i.4 — tapRadioPodcastActionWithoutCurrentConsentResponse (pure)
  4i.5 — youtubeSuggestionListWithoutCurrentConsentResponse (pure)
  4i.6 — redundantMediaLookupAfterSuggestionCacheResponse (prefs)
  4i.7 — rewriteTapRadioArgsForRequestedMediaType (pure)
  4i.8 — forceTapRadioPlaybackArgsIfExplicit (pure)
  4i.9 — resolveRecentYouTubeSuggestionListDisplayRequest (prefs)
  4i.10 — resolveRecentYouTubeFollowUpPlaybackRequest (prefs)
  4i.11 — research_topic local readout/suppression (sink.speak)
  4i.12 — local-agent direct presentation (sink.presentLocalAgentResult)
  4i.13 — final dispatch() body: chain guards in the same order
    MainActivity.dispatchLiveToolCall does today
  4i.14 — wire MainActivity → coordinator; delete Activity copies
  4i.15 — wire GeminiVoicePipeline.onToolCall → coordinator; remove
    pipeline's bb20ac4 inline tool dispatch

Done when:
  - MainActivity no longer holds any of the ~11 guard private funs
  - GeminiVoicePipeline routes onToolCall through the coordinator
  - Service-path voice flows replicate the Hermes-branch behavior
    one-for-one in logcat and on-device traces

Risk: this is exactly the kind of multi-component refactor that
broke Phase 4 the first time. Mitigation: one guard per commit, full
verification each step. No "do everything in one swing" attempts.

---

**Current status:** Phase 4h complete. Codex's bb20ac4 in. Phase 4i.1
(coordinator scaffold) landed. Next session resumes at 4i.2 once
Mars confirms bb20ac4 still boots clean.
