# TapInsight — AI-Powered AR Companion for RayNeo X3 Pro

> **ALPHA SOFTWARE — Use at your own risk.** This project is under active development. Features may be incomplete, unstable, or change without notice. No warranty is provided.

TapInsight transforms your RayNeo X3 Pro AR glasses into an AI-powered smart assistant with voice and vision capabilities, hands-free navigation, internet radio, and a full web browser — all controlled by simple gestures.

---

## What It Does

**TapInsight** is a companion layer that runs on the RayNeo X3 Pro, adding AI capabilities on top of the existing glasses experience. At its core, it uses Google's Gemini API for both text/vision and live voice conversations, letting you interact with an AI assistant that can see what you see through the glasses camera.

### AI Assistant (Gemini-Powered)

The AI assistant runs through Gemini's models and supports two modes. Standard mode handles text and vision queries — point the camera at something and ask about it. Live mode enables real-time voice conversation with the assistant, which can respond naturally while seeing through your camera. The assistant can look up nearby places, get directions with traffic, identify objects, read text, and answer questions about what's in view.

The key here is simplicity: the Gemini API currently offers generous free tiers, so you can get started without any cost. And if you want to swap in a different model later, you can do that right from the companion app — no code changes needed.

### Gesture Controls

Everything is designed for hands-free use on AR glasses. The X3 Pro's touchpad on the temple handles all navigation:

- **Single Tap** — Select, click links, focus inputs, interact with UI elements
- **Double Tap** — Go back (browser history, close dialogs, return to list view)
- **Swipe Left/Right** — Scroll horizontally, switch tabs
- **Swipe Up/Down** — Scroll through content

No phone needed once you're set up.

### TapRadio — Internet Radio Player

TapRadio is a built-in internet radio player optimized for the glasses' 960x480 display. It comes preloaded with 18 stations across genres like Chill, Jazz, Electronic, Rock, Classical, News, and more (SomaFM, Radio Paradise, NPR, BBC World Service, NASA Third Rock Radio).

Features:
- Favorites system — star any station for quick access, Favorites tab is always first
- Genre filtering — tap genre tabs to browse by category
- Play/pause, next/previous, volume control — all gesture-friendly
- Add/edit/delete stations directly on the glasses
- Search 30,000+ stations from the companion app using the Radio Browser API
- Stations sync between the companion app and glasses automatically

### TapBrowser — Full Web Browser

Built on [TapLinkX3](https://github.com/informalTechCode/TAPLINKX3) (see acknowledgments), TapBrowser provides a full web browsing experience on the glasses with a customizable dashboard of quick-launch links organized by category (AI/Chatbots, Music/Streaming, Social, Productivity, and more). It includes bookmarks, desktop/mobile mode switching, and a QR code scanner for quick URL entry.

### Library — Local Media, Playlists & Read-Aloud

TapInsight also includes a local Media Library for files stored directly on the glasses. Drop audio, video, text files, and `.m3u` / `.m3u8` playlists into the library over USB/MTP or through the companion web app, then browse and play them from the on-glasses Media Library tile.

Features:
- Default `Music/` and `Videos/` folders, with nested folders supported
- `.m3u` playlists can live in any folder, and relative paths resolve from the playlist file itself
- Companion Library tab can upload files, auto-generate playlists for a folder, and edit playlist entries
- Text files open in the media viewer and can be read aloud with Gemini TTS

### TapClaw — OpenClaw AI Agent Integration

TapClaw connects your glasses to an [OpenClaw](https://openclaw.ai) AI agent running on your host computer. That host can be a Linux machine, a Mac, or a Windows PC. This gives the glasses access to a powerful, tool-equipped agent that can control smart home devices, check emails, run automations, and analyze what you see through the camera — all by voice.

The integration uses a lightweight image relay service that runs on the same host computer as OpenClaw. When you ask the agent to look at something, the glasses capture a camera frame, POST it to the relay over your local network, and the agent reads the saved image from its workspace. This bypasses the OpenClaw gateway's attachment limitations entirely.

Key components:
- **Image Relay** — A Python HTTP server (port 18790) that receives camera frames and saves them to the OpenClaw workspace. A macOS helper script can install it as a launchd service, and Linux or Windows can run the same relay manually or via a login service.
- **Remote Access** — Cloudflare Tunnel support for accessing your OpenClaw agent from anywhere, not just your home network.
- **Image Archival** — Every analyzed camera frame is permanently saved in dated folders (`saved_photos/YYYY-MM-DD/`) with timestamps.
- **Media Display** — The agent can display images, videos, web pages, and text files on your glasses using the `open_taplink` tool through TapBrowser.

### Companion App (Phone/Laptop WiFi Configuration)

The companion app is a web interface served from the glasses over WiFi. Open it on your phone or laptop by navigating to the glasses' IP address on port 19110. From here you can:

- **Setup** — Enter your Gemini API key, configure OAuth for Google services (Maps, Calendar, etc.), set Spotify credentials, adjust the AI model and system prompt, and configure HUD display settings
- **Browser** — Manage bookmarks and browser settings, view supported media types for TapBrowser display
- **Dashboard** — Customize the TapBrowser homepage links and layout
- **Library** — Upload local audio/video/text files, create or edit `.m3u` playlists, and add the Media Library tile to the on-glasses dashboard
- **TapRadio** — Search for stations online, manage your station list, toggle favorites, and sync everything to the glasses with one button
- **TapClaw** — Configure OpenClaw gateway connection, set up Cloudflare Tunnel for remote access, check image relay status, view saved images gallery, and install the relay service

There are also diagnostic tools: Test Location (verify GPS) and Test Traffic (verify directions API).

---

## Download

**[TapInsight 0.4 beta APK](https://github.com/tropicalstream/TapInsight/releases/download/TapInsight-OC-Beta-4/TapInsight-0.4-beta.apk)** — Latest publishable debug build for RayNeo X3 Pro.

0.4 beta highlights:
- Customizable avatars and companion-side bookmark editing.
- Left-arm tap camera toggle for faster privacy/battery control.
- Native ExoPlayer video for local and SMB library videos, with folder queue, previous/next, CC language selection, and sidecar `.srt` support.
- HDR video display correction tuned for the RayNeo X3 Pro panel so HDR sources do not render overly dark or metallic.
- Longer native video controls timeout, giving more time to open CC and choose a subtitle language.
- YouTube dim-mode stability and thermal/battery guardrails, plus frequency-reactive dim-mode audio waveforms for bass, mids, and highs.
- Song Identification — AudD (optional): ask "what song is this?" while listening to TapRadio. With an AudD token, TapInsight fingerprints the current stream first so stale station metadata does not win; without a token, it falls back to free track metadata broadcast by many stations. Get a free token (300 IDs/month) at audd.io.
- Gemini research reports save into Media Browser `Text/` with date/time filenames, and Fish.audio chat-card readout handoffs are smoother.
- Maintainer-specific relay defaults were removed; configure your own relay endpoint for remote media delivery.
- Tighter Gemini, TapClaw, OpenClaw, TapBrowser, radio/podcast, and media-agent handoffs.
- URL read-aloud handling for the TTS reader when Gemini cannot fetch a page directly.

Public TODO:
- Bright mode: continue validating that bright-mode YouTube/video playback does not reboot the glasses.

---

## Videos

- **[TapInsight 0.4 beta - New Features](https://youtu.be/EH8dxzB1UKg)** — Customizable avatars, tap-left-arm camera toggle, companion bookmark editing, and tighter agent integration.
- **[How to Set Up TapInsight](https://youtu.be/VxpLvR1Jz2Y)** — A practical setup walkthrough for the glasses and companion app.
- **[TapInsight Overview](https://youtu.be/42DV0rl1NOo)** — The main walkthrough showing TapInsight running on the glasses, including voice, vision, and companion workflow.
- **[TapInsight - Learning Partner](https://youtu.be/nUUxjQn-ZgU)** — Based on the video description: a demo of TapInsight acting as a thoughtful learning partner, building on the earlier camera-learning examples.
- **[TapInsight Setup Overview](https://youtu.be/shRHLzmlQOk)** — Based on the video description: a quick setup overview that also shows the media-files workflow.

---

## Getting Started

### Prerequisites

- RayNeo X3 Pro AR glasses
- A Google Gemini API key ([get one free at Google AI Studio](https://aistudio.google.com/apikey))
- A phone or laptop on the same WiFi network as the glasses
- Android Studio only needed if building from source — otherwise just grab the APK above

### Quick Setup

1. **Download [`TapInsight.apk`](TapInsight.apk)** from this repo
2. Sideload it onto your RayNeo X3 Pro via ADB: `adb install TapInsight.apk`
3. Launch TapInsight on the glasses
4. **Open [`companion.html`](companion.html)** in any browser on your phone or laptop — it's a one-page setup wizard that connects to the glasses over WiFi
5. Enter your glasses' IP address (default: `<glasses-ip>`) and click **Connect**
6. In the Setup tab, enter your Gemini API key
7. Start talking — tap the glasses touchpad to activate the AI

The companion app runs on the glasses themselves. Over Wi-Fi, open `https://<glasses-ip>:19110` and accept the local certificate warning on first use. If the device falls back to non-TLS mode, use `http://<glasses-ip>:19110` instead. From there you can configure everything: API keys, AI model, OAuth, TapRadio stations, and more.

### Connecting via USB (Recommended)

USB is the fastest and most reliable way to access the companion app. Connect the glasses via USB and run:

```bash
adb forward tcp:19110 tcp:19110
```

Then open **https://localhost:19110** in any browser and accept the local certificate warning on first use. If the device falls back to non-TLS mode, use **http://localhost:19110** instead. The `companion.html` page defaults to USB mode and now points to the HTTPS URL first.

**The port forward resets** when the glasses disconnect, reboot, or ADB restarts. To fix this permanently, use the included helper script:

```bash
./setup_usb.sh --watch
```

This runs in the background and automatically re-establishes the port forward whenever the glasses reconnect. You can also run `./setup_usb.sh` (without `--watch`) for a one-shot forward.

### Connecting via WiFi

If you prefer wireless, your computer and glasses must be on the same WiFi network. Find the glasses' IP in Settings → WiFi, then open `https://<glasses-ip>:19110` in your browser and accept the local certificate warning on first use. If the device falls back to non-TLS mode, use `http://<glasses-ip>:19110`. The `companion.html` page has a WiFi tab for this.

### Using The Phone GPS Bridge

The phone GPS bridge only works when the phone browser can reach the glasses companion server. In practice, that means the phone and the glasses must be on the **same network** and able to talk to each other directly.

Basic steps:

1. Put the phone and glasses on the same network.
2. Find the glasses IP in the companion app `Connection Info` block or in the glasses Wi-Fi settings.
3. On the phone, open `https://<glasses-ip>:19110`.
4. Accept the local certificate warning once if the browser asks.
5. In the companion app, turn **Phone GPS Bridge** on.
6. Tap **Use This Phone's GPS**.
7. Allow location permission in the phone browser.

Common ways to use it:

- **Home / office Wi-Fi**
  - Connect both the phone and the glasses to the same Wi-Fi network.
- **iPhone Personal Hotspot**
  - Turn on Personal Hotspot on the iPhone and connect the glasses to it.
  - Open `https://<glasses-ip>:19110` on that iPhone.
- **Android hotspot**
  - Turn on the Android hotspot and connect the glasses to it.
  - Open `https://<glasses-ip>:19110` on that Android phone.
- **Other same-network arrangements**
  - Travel router, mobile hotspot device, or any shared LAN where both devices can reach each other.

Important notes:

- If the phone and glasses are **not** on the same network, the bridge will not work.
- If your router or hotspot uses **client isolation**, the bridge will not work until that is disabled or you switch networks.
- Prefer the `https://` companion URL for phone GPS bridge use, especially on iPhone.

### Optional Configuration

- **Google OAuth** — Enable Google Maps, Places, and Calendar integration by setting up OAuth credentials in the companion app's Setup tab
- **Spotify** — Connect your Spotify account for music control
- **Custom AI Prompt** — Modify the system prompt to customize the AI's personality and behavior
- **HUD Settings** — Adjust font size, display duration, and formatting for the heads-up display

---

## Architecture

The project has two main modules:

- **`app`** — The main TapInsight application (AI assistant, companion server, tool system)
- **`tapbrowser`** — The web browser module (based on TapLinkX3)

Key technical details:

- **AI Models**: Gemini Flash (text/vision) and Gemini 2.5 Flash Native Audio (live voice) — configurable via companion app or routed through OpenClaw gateway, no cost on free tier
- **Tool System**: Google Places, Routes/Directions with traffic, Location, Weather, Calendar, Spotify, Web Search, and media display via `open_taplink` — all accessible to the AI through natural conversation
- **ToolAssist Engine**: Client-side tool execution that proactively detects when you're asking about places, directions, or location and injects results into the conversation
- **OpenClaw Client**: WebSocket client that connects to an OpenClaw gateway for agent-based AI, with image relay support for camera frame analysis
- **Media Library**: App-private media storage under `.../files/Media/` with local audio/video playback, text read-aloud, and `.m3u` / `.m3u8` playlist support
- **Image Relay**: HTTP relay service (port 18790) bridging camera frames from glasses to the OpenClaw workspace, with automatic rotation and archival
- **Companion Server**: NanoHTTPD server on port 19110 serving HTML configuration and management pages
- **HUD**: Real-time heads-up display showing AI responses formatted for the glasses' compact viewport

---

## TapClaw Image Relay Setup

The image relay is required for camera-based AI analysis through OpenClaw. It runs on the same host computer as your OpenClaw instance.

### One-Command Install (macOS)

```bash
bash tools/install-relay.sh
```

This installs the relay to `~/.tapclaw/`, creates a macOS launchd service that auto-starts on login, and verifies the relay is running. Frames are saved to `~/.openclaw/workspace/camera_frame.jpg` and archived in dated folders.

### Manual Start (Linux, macOS, or Windows)

```bash
python3 tools/image_relay.py
```

Linux:

```bash
python3 -m pip install Pillow
python3 tools/image_relay.py
```

Windows PowerShell:

```powershell
py -3 -m pip install Pillow
py -3 tools\image_relay.py
```

On Linux, use a `systemd --user` service, `tmux`, `screen`, or desktop autostart if you want the relay to come up automatically after login. On Windows, use Task Scheduler or your Startup folder.

### Uninstall (macOS helper)

```bash
bash tools/install-relay.sh --uninstall
```

On Linux, remove the `systemd --user` unit or startup command you created for `image_relay.py`. On Windows, remove the Task Scheduler or Startup entry.

### Endpoints

- `POST /frame` — Receive a JPEG camera frame (raw body), rotate 90° clockwise, save to workspace
- `GET /latest` — Serve the most recent camera frame as JPEG
- `GET /status` — JSON health check with frame availability and age

### Remote Access via Cloudflare Tunnel

To access your OpenClaw agent from outside your home network, set up a Cloudflare named tunnel. This requires a domain on Cloudflare (from ~$2/year).

```bash
# 1. Install cloudflared
#    macOS: brew install cloudflared
#    Linux: install cloudflared from your distro package manager or Cloudflare package repo

# 2. Authenticate with your Cloudflare account
cloudflared tunnel login

# 3. Create a named tunnel
cloudflared tunnel create tapclaw

# 4. Route DNS to your domain (gateway + image relay)
cloudflared tunnel route dns tapclaw tapclaw.yourdomain.com
cloudflared tunnel route dns tapclaw relay.yourdomain.com

# 5. Create ~/.cloudflared/config.yml:
#    tunnel: YOUR_TUNNEL_ID
#    credentials-file: /Users/YOU/.cloudflared/YOUR_TUNNEL_ID.json
#    ingress:
#      - hostname: tapclaw.yourdomain.com
#        service: http://localhost:18789
#      - hostname: relay.yourdomain.com
#        service: http://localhost:18790
#      - service: http_status:404

# 6. Allow the tunnel origin in OpenClaw
openclaw config set gateway.controlUi.allowedOrigins "https://tapclaw.yourdomain.com"

# 7. Start the tunnel
cloudflared tunnel run tapclaw

# Auto-start on login:
#   macOS: brew services start cloudflared
#   Linux: use your system service manager or `cloudflared service install`
```

Your gateway is now reachable at `wss://tapclaw.yourdomain.com`. Use this as the Gateway URL in the companion app.

**Note:** The `relay.` subdomain tunnels port 18790 so camera vision works remotely. The app auto-detects remote gateways and routes frames through `https://relay.yourdomain.com` instead of a local IP. Without the relay tunnel entry, camera vision only works on the local network.

**Troubleshooting:**
- **"origin not allowed"** — Two fixes needed: (1) Add your tunnel URL to the `allowedOrigins` array in `~/.openclaw/openclaw.json` under `gateway.controlUi`, (2) Retrieve your gateway token with `grep '"token"' ~/.openclaw/openclaw.json`, enter it in the dashboard, then approve the device with `openclaw devices list` and `openclaw devices approve <device-id>`. Restart the gateway after.
- **"503 Service Unavailable"** — Missing `~/.cloudflared/config.yml`. Create it with the ingress rules above.
- **"Error 1033"** — The tunnel isn't running. Start with `cloudflared tunnel run tapclaw`.

**Alternative:** [Tailscale](https://tailscale.com) provides a free mesh VPN with stable private IPs — no domain needed. Use your Tailscale IP with `ws://` protocol in the companion app.

### Magentic Project UI on the OpenClaw Host

If your OpenClaw host also runs the Magentic project, start it locally before pairing TapInsight so you have the Magentic inspection dashboard available while the glasses talk to the OpenClaw gateway.

```bash
source .venv/bin/activate
magentic-ui --config ./config.yaml --run-without-docker --port 8081
```

Purpose:
- `source .venv/bin/activate` loads the Magentic Python environment.
- `magentic-ui --config ./config.yaml --run-without-docker --port 8081` launches the Magentic web UI directly on the host using your local config, without Docker.
- OpenClaw is still the TapInsight-facing gateway on port `18789`; Magentic UI is the host-side admin/debug dashboard on port `8081`.

---

## Acknowledgments

Special thanks to **InformalTech** and **glxblt76**, the developers of [TapLinkX3](https://github.com/informalTechCode/TAPLINKX3), for creating the browser foundation and for allowing integration of their work into this project. The `tapbrowser` module is built on their excellent AR-optimized web browser for the RayNeo X3 Pro.

---

## Disclaimer

This is beta software provided as-is. The developers are not responsible for any issues arising from its use. API keys and credentials are stored locally on your device and are never transmitted to third parties beyond the configured API providers (Google, Spotify, etc.).

**Security note**: This repository has been scrubbed of all personal information, API keys, and credentials. All sensitive values use placeholders. You must supply your own API keys via the companion app or `local.properties`.

---

## Developer Docs: OpenClaw CDP Browser Control

**Target Platform:** OpenClaw host computer with Chrome or Chromium (Linux, macOS, or Windows)
**OpenClaw Version:** 2026.4.2
**Method:** Direct CDP Attachment via Port 9222

### 1. Overview

The CDP method bypasses visual screen-scraping (Peekaboo) and extension relays. It creates a binary WebSocket pipe between the OpenClaw Gateway and the Chrome V8 engine. This allows the agent to read the DOM tree directly, making it immune to UI shifts that break coordinates-based automation.

### 2. Installation & Environment Setup

TapClaw does not require a Mac. The key requirement is that your OpenClaw host can run Chrome or Chromium with `--remote-debugging-port=9222` and is reachable from the glasses.

**Step A: Clean the Configuration**

Ensure no "zombie" browser keys are present in your `openclaw.json`.

```bash
openclaw config set browser.profiles.user '{
  "driver": "cdp",
  "cdpUrl": "http://127.0.0.1:9222",
  "attachOnly": true,
  "color": "#4285F4"
}'
openclaw config set browser.defaultProfile "user"
```

**Step B: Launch Chrome with remote debugging**

Chrome will only write the required `DevToolsActivePort` file if started with the explicit debugging flag from the CLI.

macOS:

1. Fully quit Chrome (`Cmd+Q`).
2. Launch via Terminal:

```bash
/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --remote-debugging-port=9222 --restore-last-session &
```

Linux:

1. Fully quit Chrome or Chromium.
2. Launch from a shell:

```bash
CHROME_BIN="$(command -v google-chrome || command -v google-chrome-stable || command -v chromium || command -v chromium-browser)"
"$CHROME_BIN" --remote-debugging-port=9222 --restore-last-session >/tmp/tapclaw-chrome.log 2>&1 &
```

Windows PowerShell:

1. Fully quit Chrome.
2. Launch from PowerShell:

```powershell
$chrome = "${Env:ProgramFiles}\Google\Chrome\Application\chrome.exe"
if (!(Test-Path $chrome)) { $chrome = "${Env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe" }
Start-Process $chrome -ArgumentList "--remote-debugging-port=9222","--restore-last-session"
```

### 3. Running the Bridge

**Step 1: Health Check**

Verify Chrome is actually "listening" before starting the Gateway.

```bash
curl http://127.0.0.1:9222/json/version
```

If you see a JSON block with `webSocketDebuggerUrl`, you are ready.

**Step 2: Start the Gateway**

```bash
openclaw gateway restart
```

**Step 3: Manual Attachment**

Force the profile to "Handshake" with the open Chrome window:

```bash
openclaw browser start --profile user
```

### 4. Troubleshooting

**Issue: `Could not find DevToolsActivePort`**
- Cause: Chrome was opened normally instead of from a shell or PowerShell with the debugging flag.
- Fix: Quit all Chrome processes and restart using the command in Section 2B.

**Issue: `Error: Unrecognized key: "entries"`**
- Cause: Attempting to use the old 2025 "nested" config schema.
- Fix: Use the flat schema: `openclaw config set tools.canvas.enabled false`.

**Issue: Connection Refused (Port 9222)**
- Cause: Local firewall settings or a conflicting "zombie" instance of Chrome.
- Fix:

```bash
lsof -i :9222  # Find what is using the port
kill -9 <PID>  # Kill the ghost process
```

Windows PowerShell:

```powershell
Get-NetTCPConnection -LocalPort 9222
taskkill /PID <PID> /F
```

### 5. Optimized Workflow for "Deep Research"

To prevent credit burn, use this specific prompt pattern now that CDP is active:

**Developer Command:**
"Assistant, switch to the configured browser profile. Locate the target tab. Instead of clicking via coordinates, use a DOM selector to find the requested control and execute a native click. If the selector is missing, log the error and stop."

---

## License

Distributed under the GNU General Public License v3.0 — see the [LICENSE](LICENSE) file for details.
