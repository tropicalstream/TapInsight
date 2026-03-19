# TapInsight — AI-Powered AR Companion for RayNeo X3 Pro

> **Current release: v0.2.3-beta**
>
> **BETA SOFTWARE — Experimental and under active development.** Features may be incomplete, unstable, or change without notice.

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

### Companion App (Phone/Laptop WiFi Configuration)

The companion app is a web interface served from the glasses over WiFi. Open it on your phone or laptop by navigating to the glasses' IP address on port 19110. From here you can:

- **Setup** — Enter your Gemini API key, configure OAuth for Google services (Maps, Calendar, etc.), set Spotify credentials, adjust the AI model and system prompt, and configure HUD display settings
- **Browser** — Manage bookmarks and browser settings remotely
- **Dashboard** — Customize the TapBrowser homepage links and layout
- **TapRadio** — Search for stations online, manage your station list, toggle favorites, and sync everything to the glasses with one button

There are also diagnostic tools: Test Location (verify GPS) and Test Traffic (verify directions API).

---

## Download

**Download the latest APK from the GitHub Releases page.**

Current release asset: `tapinsight-v0.2.3-beta.apk`

---

## Getting Started

### Prerequisites

- RayNeo X3 Pro AR glasses
- A Google Gemini API key ([get one free at Google AI Studio](https://aistudio.google.com/apikey))
- A phone or laptop on the same WiFi network as the glasses
- Android Studio only needed if building from source — otherwise just grab the APK above

### Quick Setup

1. **Download `tapinsight-v0.2.3-beta.apk`** from the latest GitHub release
2. Sideload it onto your RayNeo X3 Pro via ADB: `adb install tapinsight-v0.2.3-beta.apk`
3. Launch TapInsight on the glasses
4. **Open [`companion.html`](companion.html)** in any browser on your phone or laptop — it's a one-page setup wizard that connects to the glasses over WiFi
5. Enter your glasses' IP address and click **Connect**
6. In the Setup tab, enter your Gemini API key
7. Start talking — tap the glasses touchpad to activate the AI

The companion app runs entirely over WiFi at `http://<glasses-ip>:19110` (adjust the IP to match your glasses — check Settings → WiFi on the glasses to find it). From there you can configure everything: API keys, AI model, OAuth, TapRadio stations, and more.

### Connecting via USB (Recommended)

USB is the fastest and most reliable way to access the companion app. Connect the glasses via USB and run:

```bash
adb forward tcp:19110 tcp:19110
```

Then open **http://localhost:19110** in any browser. The `companion.html` page defaults to USB mode and auto-detects when the forwarding is active.

**The port forward resets** when the glasses disconnect, reboot, or ADB restarts. To fix this permanently, use the included helper script:

```bash
./setup_usb.sh --watch
```

This runs in the background and automatically re-establishes the port forward whenever the glasses reconnect. You can also run `./setup_usb.sh` (without `--watch`) for a one-shot forward.

### Connecting via WiFi

If you prefer wireless, your computer and glasses must be on the same WiFi network. Find the glasses' IP in Settings → WiFi, then open `http://<glasses-ip>:19110` in your browser. The `companion.html` page has a WiFi tab for this.

### Safety & Data Notes

- **Maps and location services are experimental.** Validate routes and destinations yourself before acting on them.
- **Follow your local laws** and stay aware of your surroundings while using AR navigation or media features.
- **Do not rely on TapInsight location services for emergencies** or any situation where inaccurate location data could cause harm.
- **If you lose your glasses, you lose the local data stored on them** unless you have exported or backed it up elsewhere.
- API keys, OAuth tokens, saved lessons, and on-device history are stored locally on the glasses.

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

- **AI Models**: Gemini 3 Flash (text/vision) and Gemini 2.5 Flash Native Audio (live voice) — configurable via companion app, no cost on free tier
- **Tool System**: Google Places, Routes/Directions with traffic, Location, Weather, Calendar, Spotify, and Web Search — all accessible to the AI through natural conversation
- **ToolAssist Engine**: Client-side tool execution that proactively detects when you're asking about places, directions, or location and injects results into the conversation
- **Companion Server**: NanoHTTPD server on port 19110 serving HTML configuration and management pages
- **HUD**: Real-time heads-up display showing AI responses formatted for the glasses' compact viewport

---

## Acknowledgments

Special thanks to **InformalTech** and **glxblt76**, the developers of [TapLinkX3](https://github.com/informalTechCode/TAPLINKX3), for creating the browser foundation and for allowing integration of their work into this project. The `tapbrowser` module is built on their excellent AR-optimized web browser for the RayNeo X3 Pro.

---

## Disclaimer

This is alpha software provided as-is. The developers are not responsible for any issues arising from its use. API keys and credentials are stored locally on your device and are never transmitted to third parties beyond the configured API providers (Google, Spotify, etc.).

**Security note**: This repository has been scrubbed of all personal information, API keys, and credentials. All sensitive values use placeholders. You must supply your own API keys via the companion app or `local.properties`.

---

## License

Distributed under the GNU General Public License v3.0 — see the [LICENSE](LICENSE) file for details.
