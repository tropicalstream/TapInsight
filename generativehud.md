# Generative HUD — Design Document

**Status:** design-locked for v1, awaiting implementation.
**Owner:** Mars (TapInsight) + Hermes (Minimax M3 backend).
**Scope:** replaces the fixed 3-row Events / Tasks / News tier strip on
the right side of the unipanel HUD with a small WebView whose body is
generated on demand by Hermes from a user-defined prompt.

This document is the complete spec — anyone (codex, a future Claude
session, a human contributor) should be able to implement the feature
from it without re-asking the design questions.

---

## 1. Vision (one paragraph)

The right-side HUD strip becomes a tiny generative window. The user
writes a natural-language prompt in the companion app (or sets it by
voice through Gemini) describing what they want the HUD to show.
Every N minutes, Hermes is sent that prompt + the user's chosen
context (time / location / calendar / tasks / last chat exchange),
and returns a small HTML fragment that fits exactly inside the HUD
window. The glasses paint that fragment in a sandboxed WebView pinned
to the existing tier-panel coordinates. The heartbeat ticker below
stays untouched.

The user can save multiple named presets ("Morning Brief," "Stocks,"
"Travel mode," etc.), switch between them, and let voice edits flow
into a rolling singleton "Working" scratch slot that never
destructively rewrites a saved preset.

---

## 2. The architectural choice: HTML in a sandboxed WebView

The original design considered a typed row vocabulary
(`text` / `icon_text` / `sparkline` / `progress` / `album_art` / …)
where Hermes picks a kind per row and we have pre-built drawers. We
rejected that in favour of letting Hermes return raw HTML for the
whole window because:

- **Full landscape access.** Hermes can use any layout: flex,
  multiple rows, side-by-side, sparklines via inline `<svg>`, album
  art via `<img>`, gradients, custom typography — anything HTML can
  do.
- **Reuses existing code.** WebView, the `WebViewAssetLoader`, the
  `GroqBridge` interception, and the relay-served `/media/<file>`
  URLs all work in a small WebView as they do in the big browser.
- **Free interactivity.** Buttons / links / hit regions work
  natively. Hermes writes
  `<a href="taplink:calendar">…</a>` and the existing bridge
  resolves the scheme — no coordinate-to-action translation.
- **Cheap to run.** Each refresh is a text-completion call, not an
  image-gen call. At 5 min interval, ~8,640 calls/month — small
  Minimax spend.
- **Stable surface for Hermes.** The bridge URL schemes, the
  baseline CSS classes, the canvas dimensions, and the HTML
  allow-list are the only fixed contract. Adding "weather card" or
  "ETA countdown" later doesn't require new drawer code, just a new
  CSS class shortcut.

Failure modes are well-bounded: Hermes returns invalid JSON → keep
last frame; HTML uses a stripped tag (`<script>`) → silently dropped
before injection; body too long → reject and keep last frame;
WebView still painting after 200ms → keep last frame, drop new one.

---

## 3. Canvas / dimensions

The new WebView is pinned to the exact box currently occupied by
`unipanelHudTierPanel` in
`tapbrowser/src/main/res/layout/tapbrowser_activity_main.xml`
(starts at line 303). The container properties today:

- `layout_width="0dp" layout_weight="1"` — fills horizontal slot
  after avatar/status strip (~520–540 dp on the X3 panel).
- `layout_height="wrap_content"` — currently sized by content; we
  pin to a fixed height for the new WebView.

For v1:

- **Width:** measured at attach time, passed through to Hermes as a
  pixel integer. Log on first attach: `Log.d(TAG, "Generative HUD canvas: ${w}×${h}px")`.
- **Height:** **80 dp** — matches the existing 3-row strip at its
  mid-state (between all-single-line ~60 dp and all-wrapped ~106 dp).
  Hardcoded; no user-facing size picker in v1.

The heartbeat ticker (`unipanelHudHeartbeatText`) sits **below** the
container and is unchanged. The new WebView replaces only the tier
panel's content area; the surrounding HUD chrome (clock, AQI,
avatar, badges) is unaffected.

WebView background: **transparent**, so the existing HUD scrim shows
through. Hermes is told this and renders with light text on transparent
by default.

---

## 4. Companion app — Agents → HUD section

A new subsection inside the existing Agents area of
`app/src/main/assets/companion/index.html`. Top-level layout:

```
Agents → HUD
─────────────
Active preset:  Morning Brief                    ▼
[ + New preset ]    [ Refresh now ]

Working  (modified from Morning Brief)              [ Save as… ]  [ Discard ]
─────────────────────────────────────────────────────────────────────────
Saved presets (5 / 8)
─────────────────────────────────────────────────────────────────────────
 ● Morning Brief        5 min · time, calendar              [Edit] [Dup] [Del]
 ○ Stocks               5 min · time                        [Edit] [Dup] [Del]
 ○ Travel mode         15 min · time, location, coords      [Edit] [Dup] [Del]
 ○ Dev focus            1 min · time, last chat             [Edit] [Dup] [Del]
 ○ Empty (off)          manual · —                          (cannot delete)

Editor (Morning Brief) ──────────────────────────────────────────────────
 Name:           [ Morning Brief                                       ]
 Prompt:         ┌─────────────────────────────────────────────────────┐
                 │ Next calendar event, today's top tech news,         │
                 │ current weather, and SPY ticker.                    │
                 └─────────────────────────────────────────────────────┘
 Refresh:        [ 5 min ▼ ]   (1 min · 5 min · 15 min · 60 min · manual)
 Context:        [×] time          [×] calendar
                 [ ] location      [ ] coords
                 [×] task titles   [ ] last chat
 [ Save ]  [ Cancel ]
```

### 4.1 Preset model

A preset is a row in companion-app config:

```json
{
  "name": "Morning Brief",
  "prompt": "Next calendar event, today's top tech news, current weather, and SPY ticker.",
  "refreshSec": 300,
  "context": {
    "time": true,
    "location": false,
    "coords": false,
    "calendar": true,
    "tasks": true,
    "lastChat": false
  },
  "createdAt": 1733175600,
  "lastUsedAt": 1733175950
}
```

Storage: a new key in companion app config (alongside `gemini_model_override`, etc.):

```
hud_presets        — JSON array of preset objects
hud_active_preset  — string, name of currently-active preset, or
                     "__working__" for the working slot, or
                     "__empty__" for the off slot
hud_working        — JSON preset object representing the rolling fork
                     (null when no voice edits pending)
```

### 4.2 Cap and special slots

- **8 named presets max.** When at cap, +New is greyed; voice
  "save as" prompts the user to evict (LRU hint via the
  `lastUsedAt` timestamp).
- **"Empty (off)"** — undeletable singleton. Selecting it shows the
  HUD's skeleton/blank state. Acts as a feature off-switch.
- **"Working"** — appears in the dropdown only when non-empty. Carries
  a derived label (`"modified from Morning Brief"`) showing what it
  forked from. Saving it promotes to a named preset; switching away
  from it preserves its contents for return.

### 4.3 First-install defaults

- `hud_presets` ships pre-populated with one example named
  **"Morning Brief"** so users see the feature works out of the box.
- `hud_active_preset` defaults to **"__empty__"** so first-time users
  aren't surprised by an unprompted Hermes call.
- A small "Try this" panel in the Agents → HUD section walks the user
  through making "Morning Brief" active and refreshing.

---

## 5. Voice vocabulary via Gemini

Gemini Live's router gets one new tool, `hud_prompt`, registered in
the same place existing tools live
(`app/src/main/java/com/rayneo/visionclaw/core/tools/ToolDispatcher.kt`).

### 5.1 Tool signature

```
hud_prompt({
  "action": "set" | "append" | "refresh" | "switch" | "save"
           | "list"  | "delete" | "off",
  "value": string  // see per-action meaning below
})
```

| action | value meaning | side effect |
|---|---|---|
| `set` | new prompt string | forks active → working, replaces prompt, makes working active, kicks immediate refresh |
| `append` | additional text | forks active → working, appends with separator, makes working active, kicks immediate refresh |
| `refresh` | `""` | forces immediate Hermes call against active preset, ignoring interval |
| `switch` | preset name | makes named preset active; if not found, voice replies "I don't have a preset called X — your presets are A, B, C" |
| `save` | new preset name | promotes Working → new named preset; at cap, prompts user to evict |
| `list` | `""` | reads back preset names via TTS, drops a chat card with the list |
| `delete` | preset name | confirms once verbally then removes |
| `off` | `""` | makes "Empty (off)" active |

### 5.2 Utterance → action mapping (router-side intent matching)

| User says | Tool call |
|---|---|
| "Set my HUD to show the latest news" | `set("the latest news")` |
| "Put my morning brief in the HUD" | `set("morning brief")` *or* `switch("Morning Brief")` — router uses fuzzy match to existing names; falls back to `set` if no name match |
| "Add today's weather" / "Also show stocks" | `append("today's weather")` |
| "Refresh my HUD" / "Regenerate" | `refresh("")` |
| "Switch HUD to Morning Brief" / "Use my Stocks preset" | `switch("Morning Brief")` |
| "Save my HUD as Morning Brief" | `save("Morning Brief")` |
| "Save this HUD" (no name) | Gemini asks: *"What should I call it?"*, then `save("<name>")` |
| "Show me my HUD presets" | `list("")` |
| "Delete my Stocks preset" | confirm-then-`delete("Stocks")` |
| "Turn off the HUD" / "Hide the HUD strip" | `off("")` |

The router doesn't need rigid grammar — Gemini Live's normal intent
handling routes any phrasing that maps to one of the eight actions.

---

## 6. Hermes I/O contract

### 6.1 Request format

A POST to Hermes with a JSON body containing the rendered system
prompt (below) and the user prompt as the latest turn.

System prompt template (rendered before sending):

```
You are populating the right-side HUD window on an AR-glasses
heads-up display. The viewport is exactly {W} × {H} pixels and is
rendered by a small WebView with transparent background.

You may produce anything that fits in standard HTML5 + CSS3 +
inline SVG. No external <script> tags. No <iframe>. Inline <style>
is fine. Anything beyond the viewport is clipped. Body cap: 16 KiB.

A baseline stylesheet is already loaded. Available classes:
  .row          — one logical row, flex/inline-block friendly
  .row-label    — bold uppercase 8-char prefix (auto-clipped)
  .row-value    — main content
  .pip-red      — small red status dot
  .pip-green    — small green status dot
  .mono         — monospace font
  .dim          — 0.6 alpha

CSS variables:
  --hud-fg:    #FFE0F4FF
  --hud-fg-dim:#8FB5C4
  --hud-bg:    transparent
  --hud-accent:#5BE384

Tap targets MUST use href values from this whitelist:
  taplink:calendar             — open Google Calendar in browser
  taplink:tasks                — open Google Tasks in browser
  taplink:news                 — open Google News in browser
  taplink:open:<absolute-url>  — open URL in in-app browser
  taplink:hermes:"<query>"     — run a Hermes query
  taplink:gemini:"<query>"     — run a Gemini query
  taplink:none                 — inert (default if href omitted)
Any other scheme is silently dropped by the renderer.

Image policy:
  - Relay-hosted URLs are preferred:
      http://{HOST_IP}:18790/media/<file>
    Files dropped in ~/hermes-media on the host are reachable here.
  - Public HTTPS image URLs are allowed.
  - file:// is rejected.

User prompt:
  "{user_prompt}"

Context (current, computed by the app):
  time:     2026-06-02 17:34 PT
  location: Los Angeles, CA (Hancock Park)   [only if context.location]
  coords:   34.0735, -118.3329                [only if context.coords]
  calendar: ["Standup 18:00", "Dinner 19:30"][only if context.calendar]
  tasks:    ["File expense", "Reply to Anna"][only if context.tasks]
  lastUser: "..."                             [only if context.lastChat]
  lastReply:"..."                             [only if context.lastChat]

Return ONLY a single JSON object of shape:
  { "html": "<body markup>", "ttl_seconds": <int 30..3600> }

ttl_seconds is your suggestion for when to refresh; the app may
clamp it. Lower it for time-sensitive content (calendar within the
next 5 min), raise it for slow-changing content.
```

### 6.2 Response schema

```json
{
  "html": "<div class=\"row\"><span class=\"row-label\">STANDUP</span><a class=\"row-value\" href=\"taplink:calendar\">in 26 min</a></div>",
  "ttl_seconds": 300
}
```

Validation rules:

- Top-level JSON object with exactly two keys, `html` (string) and
  `ttl_seconds` (integer).
- `html.length <= 16384` bytes (UTF-8).
- `ttl_seconds` clamped server-side to `[30, 3600]`. The user's
  selected refresh interval is the floor; Hermes can't ask for
  refreshes faster than the user wants.
- HTML sanitized (see §8).

### 6.3 Worked example

Input:

```
viewport: 540 × 80 px
prompt:   "next meeting, today's top news, current weather"
context:  time=2026-06-02T17:34-07:00 calendar=["Standup 18:00","Dinner 19:30"]
```

Possible Hermes output:

```html
<div style="display:flex;flex-direction:column;gap:2px;font-size:11px">
  <div class="row" style="display:flex;justify-content:space-between">
    <a href="taplink:calendar"><b>STANDUP</b> in 26m · 18:00</a>
    <span class="dim">73°F · clear</span>
  </div>
  <div class="row">
    <a href="taplink:news">FED minutes pushed to Thu after software glitch</a>
  </div>
  <div class="row" style="display:flex;justify-content:space-between">
    <a href="taplink:open:https://news.google.com/topics/tech">TECH · OpenAI ships agent SDK 2.0</a>
    <span class="mono dim">+0.4% SPY</span>
  </div>
</div>
```

---

## 7. Render pipeline on the glasses

Touch points (where new code lives):

- **Layout** —
  `tapbrowser/src/main/res/layout/tapbrowser_activity_main.xml`:
  replace the three `unipanelHudTier0/1/2` TextViews with a single
  `WebView` (id `unipanelHudGenerativeWebView`) inside the existing
  `unipanelHudTierPanel` LinearLayout. Keep the LinearLayout so the
  heartbeat ticker's position below is unaffected. Background
  transparent.
- **Controller** —
  new class
  `tapbrowser/src/main/java/com/TapLink/app/unipanel/GenerativeHudController.kt`.
  Responsibilities:
  - Subscribe to HUD-relevant context (`HudStateBridge` for the time
    + battery + location; existing calendar / tasks providers in
    `MainViewModel`; last chat exchange).
  - Run a refresh timer at the active preset's interval.
  - On tick: build the prompt + context payload, call Hermes
    through the existing `HermesClient.kt`.
  - Parse JSON, sanitize HTML (§8), inject via
    `webView.loadDataWithBaseURL(...)`.
  - Maintain cold-start state, stale state, error state (§7.1).
- **JS bridge for taps** — extend the existing `GroqBridge` (or add
  a new `HudBridge`) to intercept `taplink:` URL scheme clicks and
  dispatch to the existing handlers
  (`activity.handleMaskToggle()`, `launchTapBrowserFromService()`,
  `activity.openTextReader()`, Gemini router, etc.).
- **Preset storage** — extend `AppPreferences.kt` with three keys
  (§4.1); add `/api/hud/presets` GET/PUT and `/api/hud/active` GET/PUT
  endpoints on `CompanionServer.kt`.
- **Tool registration** — register `HudPromptTool` in
  `ToolDispatcher.kt` mirroring the existing tool pattern.

### 7.1 Lifecycle states

| State | Visual | When |
|---|---|---|
| **cold-start** | 3 faint horizontal bars at 30% opacity, no text, no animation | Active preset is set, no Hermes reply has arrived yet |
| **populated** | Last successful HTML at full opacity | Hermes returned a valid frame at least once |
| **stale** | Last HTML at 75% opacity | Refresh has failed or timed out since last good frame |
| **off** | Window blank, no skeleton | Active preset is `__empty__` |
| **broken-prompt** | 3 faint bars + tiny "set HUD prompt" hint label | Active preset is named but its prompt string is empty/whitespace |

There is **no static Events / Tasks / News fallback**. The feature is
honest about being offline when it is.

### 7.2 Concurrency rules

- At most one Hermes call in flight at a time. A late reply from a
  previous tick is dropped if a newer tick has already completed.
- Switching the active preset cancels any in-flight call from the
  prior preset and fires a fresh one immediately.
- Manual "Refresh now" or voice "regenerate" fires immediately
  regardless of interval.
- WebView render budget: 200 ms layout deadline. If the new frame
  isn't painted in that window, log and keep the previous frame.

### 7.3 Asset prefetch

Before swapping the new frame in, the controller scans the HTML for
`<img src="…">` URLs and prefetches each into the WebView's HTTP
cache (OkHttp-backed). Swap is flicker-free. Cache budget 10 MiB,
LRU.

---

## 8. HTML sanitization

Server-side (the controller, not Hermes) strips before injection:

- `<script>` tags and any embedded JS.
- `<iframe>` tags.
- `<object>`, `<embed>`, `<form>` tags.
- All `on*` event handler attributes (`onclick`, `onload`, …).
- `javascript:` URIs in any `href` or `src`.
- `data:` URIs are allowed only for `<img src="data:image/…">` (lets
  Hermes inline tiny pre-rendered sparklines / icons if it wants).
- `href` values that don't match the `taplink:` whitelist (§6.1) are
  rewritten to `href="taplink:none"` so the link renders but is
  inert.

A small Jsoup-based pass is the natural implementation since Jsoup
is already in the dependency tree for other HTML work (check
`build.gradle`; if not, add as `org.jsoup:jsoup:1.17.2`).

Body cap (16 KiB) enforced before sanitization. CSS `animation` and
`transition` durations capped at 1 second via a regex post-process
so a bad Hermes reply can't spin the GPU forever.

---

## 9. Tap-action handler reference

The bridge handler maps `taplink:<scheme>` to the actual app
action. The receiving Kotlin side lives in
`tapbrowser/src/main/java/com/TapLink/app/MainActivity.kt`,
mirroring the existing scheme-handling pattern around line 12239+
(`@JavascriptInterface`). Mappings:

| scheme | handler |
|---|---|
| `taplink:calendar` | launch the Google Calendar URL the existing tier-row tap uses |
| `taplink:tasks` | launch Google Tasks URL |
| `taplink:news` | launch Google News URL |
| `taplink:open:<url>` | `launchTapBrowserFromService(url)` |
| `taplink:hermes:"<query>"` | `viewModel.sendUserQuery(query, agent="hermes")` |
| `taplink:gemini:"<query>"` | `viewModel.sendUserQuery(query, agent="gemini")` |
| `taplink:none` | no-op (still consumed, prevents default WebView nav) |
| anything else | already neutralized to `taplink:none` by sanitizer |

---

## 10. Locked design decisions (reference table)

| # | Decision | Value |
|---|---|---|
| D1 | Source | Hermes generates a full HTML body per refresh |
| D2 | Render | Sandboxed WebView pinned to current tier-panel coordinates |
| D3 | Canvas | ~540 × 80 dp (measured at attach, logged to logcat) |
| D4 | Heartbeat ticker | Unchanged, sits below the new WebView |
| D5 | Body cap | 16 KiB |
| D6 | Tap vocab | `taplink:calendar|tasks|news|open:<url>|hermes:"<q>"|gemini:"<q>"|none` |
| D7 | Image policy | Relay preferred, public HTTPS allowed, `file://` rejected |
| D8 | HTML allow-list | strip `<script>`, `<iframe>`, `<object>`, `<embed>`, `<form>`, `on*=`, `javascript:` |
| D9 | Animation cap | 1 second max per CSS transition/animation |
| D10 | Fallback | None — faint skeleton when Hermes offline (no static feed paper-over) |
| D11 | Preset cap | 8 named + Working singleton + Empty(off) singleton |
| D12 | Voice edit policy | Always fork into Working slot (rolling singleton, not per-utterance multiplier) |
| D13 | First-install default | `Empty (off)` active; example "Morning Brief" preset ships |
| D14 | Concurrency | One Hermes call in flight; late replies dropped |
| D15 | Refresh intervals | 1 / 5 / 15 / 60 min · manual only |
| D16 | Context flags | time · location · coords · calendar · tasks · last chat (per-preset opt-in) |
| D17 | Storage keys | `hud_presets`, `hud_active_preset`, `hud_working` in AppPreferences |
| D18 | Companion endpoints | `GET/PUT /api/hud/presets`, `GET/PUT /api/hud/active` on CompanionServer |
| D19 | Voice tool | new `hud_prompt` tool registered in `ToolDispatcher.kt` |

---

## 11. Open questions to resolve during implementation

These don't block the design — they're judgement calls the
implementer makes with sample data in hand:

1. **Exact dimensions at runtime.** First-pass build should log
   `unipanelHudTierPanel.measuredWidth/Height` once on attach. If
   it's significantly off ~540×80 we may want a different default
   height — but the measured value is what we send to Hermes
   regardless.
2. **Hermes timeout.** Suggested 8 s connect + 12 s read. Tune
   based on Minimax M3 p99 latency from logs after first week.
3. **Cache budget for image prefetch.** Suggested 10 MiB LRU. Could
   be lower (1 MiB) since the same album art / icons repeat across
   refreshes.
4. **Whether to ship a `weather`-shaped CSS class shortcut.**
   Hermes can produce a weather row with inline styles; whether to
   add `.weather { … }` to the baseline stylesheet is a polish
   call after seeing what Hermes actually emits in the wild.
5. **Telemetry surface.** Optional small line in the companion app
   section: "≈ N Hermes calls in the last 24 h." Helps users see
   what they're paying for. Not v1-critical.
6. **Multi-prompt context.** Future: a preset whose "context"
   includes results from another preset (e.g., "Travel mode" uses
   the active calendar AND a weather lookup). Out of scope for v1.

---

## 12. Implementation order (suggested phases)

A reasonable build order — each phase is testable end-to-end before
moving on:

### Phase 1 — Storage + companion app UI (no glasses changes)

- Add `hud_presets`, `hud_active_preset`, `hud_working` to
  `AppPreferences.kt`.
- Add `/api/hud/presets` (GET/PUT array) and `/api/hud/active`
  (GET/PUT string) to `CompanionServer.kt`.
- Build the Agents → HUD section UI in
  `app/src/main/assets/companion/index.html`. The section can be
  fully usable (create/edit/delete presets) before any glasses
  rendering exists. Active selector is just a stored string at this
  point.

### Phase 2 — WebView + skeleton state on glasses (no Hermes yet)

- Replace tier-panel children with a single WebView in the layout XML.
- Add `GenerativeHudController.kt` that paints the skeleton-state
  (3 faint bars) on init and the "off" state when the active preset
  is `__empty__`.
- Confirm heartbeat ticker still appears where it always did.

### Phase 3 — Hermes call wired

- Controller subscribes to context providers (`HudStateBridge`,
  `MainViewModel.calendarEvents`, `MainViewModel.taskTitles`, etc.).
- Implement the request payload builder per §6.1.
- Call Hermes via `HermesClient`. Parse JSON, validate.
- Sanitize HTML via Jsoup per §8.
- `webView.loadDataWithBaseURL("file:///android_asset/hud/", html, "text/html", "UTF-8", null)`
  with a small `hud/baseline.css` shipped in assets containing the
  classes from §6.1.
- Refresh timer at the active preset's interval.

### Phase 4 — Tap bridge

- Add `taplink:` URL interception (WebViewClient's
  `shouldOverrideUrlLoading`).
- Implement the handler table in §9.

### Phase 5 — Voice tool

- Register `HudPromptTool` in `ToolDispatcher.kt`.
- Implement the 8 actions in §5.1 against the storage in §4.1.
- Update `GeminiRouter`'s routing rules so utterances in §5.2 map to
  the tool. Likely an addition to `DEFAULT_ROUTING_RULES_PART_2`
  (mind the 65 KiB JVM constant cap).

### Phase 6 — Polish

- Asset prefetch (§7.3).
- Stale-state opacity (§7.1).
- Animation duration cap regex.
- Telemetry line in companion app (optional).
- Documentation update on the GitHub page.

---

## 13. Future extensions (out of scope for v1)

- **Multiple HUD slots.** Same pattern applied to the chat card,
  the status pill, or a left-eye companion overlay. The bridge
  schemes are generic so this is mostly more storage keys + a few
  more WebViews.
- **Cross-preset composition.** A preset whose context includes
  another preset's last result.
- **Editable baseline CSS.** Power users wanting to ship their own
  visual identity. Risk: every CSS surface becomes a contract.
- **Per-context-source overrides per preset.** E.g., "Travel mode"
  uses the next-3-days calendar instead of just today's. Right now
  context flags are boolean; could become structured.
- **Local-only mode.** A preset whose prompt is a hardcoded data
  query that never hits Hermes (e.g., "show calendar + weather
  using built-in providers"). Saves cost for users on cellular.

---

## Appendix A — Reference: existing tier-panel code locations

For an implementer mapping the current state:

| What | Where |
|---|---|
| Tier panel layout XML | `tapbrowser/src/main/res/layout/tapbrowser_activity_main.xml:303-360` |
| Tier 1 / 2 / 3 TextView refs in Kotlin | `tapbrowser/src/main/java/com/TapLink/app/MainActivity.kt` — search `unipanelHudTier0` |
| Heartbeat ticker view | same layout file, just below the tier panel |
| Current tier render logic | search `renderUnipanelTieredHud` in MainActivity.kt |
| Tier row tap handler (open Google Calendar/Tasks/News) | same function, look for the URL map (`calendar` → `https://calendar.google.com`, etc.) |
| Existing Hermes client | `app/src/main/java/com/rayneo/visionclaw/core/network/HermesClient.kt` |
| Companion server endpoint pattern to mirror | `app/src/main/java/com/rayneo/visionclaw/core/config/CompanionServer.kt:798-830` |
| AppPreferences key registration | `app/src/main/java/com/rayneo/visionclaw/core/storage/AppPreferences.kt` |
| Tool registration pattern | `app/src/main/java/com/rayneo/visionclaw/core/tools/ToolDispatcher.kt` |
| GeminiRouter routing rules constant | `app/src/main/java/com/rayneo/visionclaw/core/network/GeminiRouter.kt` — `DEFAULT_ROUTING_RULES_PART_1/PART_2` |

---

## Appendix B — Baseline CSS to ship in `tapbrowser/src/main/assets/hud/baseline.css`

A draft starting point — refine as Hermes's outputs settle:

```css
:root {
  --hud-fg:     #FFE0F4FF;
  --hud-fg-dim: #8FB5C4;
  --hud-bg:     transparent;
  --hud-accent: #5BE384;
}

* { box-sizing: border-box; }

html, body {
  margin: 0;
  padding: 0;
  background: var(--hud-bg);
  color: var(--hud-fg);
  font: 11px -apple-system, "SF Pro Text", "Segoe UI", "Roboto", sans-serif;
  overflow: hidden;
  width: 100%;
  height: 100%;
}

a { color: inherit; text-decoration: none; }

.row {
  background: rgba(0,0,0,0.70);
  padding: 2px 8px;
  margin-bottom: 2px;
  line-height: 1.25;
  border-radius: 2px;
}

.row-label {
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  font-size: 10px;
  margin-right: 6px;
  /* visual cap; sanitizer also clips text to 8 chars */
  max-width: 8ch;
  display: inline-block;
  overflow: hidden;
  vertical-align: middle;
}

.row-value { vertical-align: middle; }

.pip-red,
.pip-green {
  display: inline-block;
  width: 6px; height: 6px;
  border-radius: 50%;
  vertical-align: middle;
  margin-right: 4px;
}
.pip-red   { background: #FF5252; }
.pip-green { background: var(--hud-accent); }

.mono { font-family: ui-monospace, "SF Mono", Menlo, monospace; }
.dim  { opacity: 0.6; }
```

---

## Appendix C — Test prompts to validate first build

Use these to smoke-test the v1 implementation:

1. **"the latest news"** — simplest case, one row of text.
2. **"my next calendar event, today's top news, current weather"** —
   the worked example in §6.3.
3. **"now playing on spotify with album art"** — exercises the
   relay-image path. Hermes needs to know the relay URL pattern.
4. **"three things I should care about based on the time and where I am"** —
   AI-curated mode, no specific feeds named.
5. **"a single big chart of SPY over the last day"** — exercises the
   single-large-block layout vs. multi-row.
6. **""** (empty) — should land on `broken-prompt` skeleton.
7. **(switch active to Empty(off))** — should show blank window,
   heartbeat ticker still visible.

If all 7 render acceptably and tap actions work, v1 is shippable.

---

**End of document.** Questions / clarifications: drop a comment in
this file's header section before adding code so the design intent
stays documented next to the work.
