<p align="center">
  <img src="docs/assets/tapinsight-logo-512.png" width="120" alt="TapInsight logo">
</p>

<h1 align="center">TapInsight</h1>

<p align="center">
  <strong>AI-powered AR companion for RayNeo X3 Pro</strong><br>
  Voice, vision, web, radio, and OpenClaw/TapClaw on your glasses.
</p>

<p align="center">
  <a href="https://tropicalstream.github.io/TapInsight/">Website</a> |
  <a href="https://github.com/tropicalstream/TapInsight/releases/download/tapinsight-alpha-oc.2/tapinsight-alpha-oc.2.apk">Download Alpha OC.2 APK</a> |
  <a href="https://github.com/tropicalstream/TapInsight/releases">All Releases</a> |
  <a href="https://youtu.be/42DV0rl1NOo">Overview Video</a> |
  <a href="TapInsight-User-Guide.html">User Guide</a>
</p>

<p align="center">
  <a href="https://www.youtube.com/watch?v=42DV0rl1NOo">
    <img src="https://img.youtube.com/vi/42DV0rl1NOo/hqdefault.jpg" alt="TapInsight overview video">
  </a>
</p>

> Alpha software. Use at your own risk.

TapInsight turns the RayNeo X3 Pro into a wearable AI workstation: a live Gemini assistant, a full AR browser, an internet radio player, and a bridge to an OpenClaw agent running on your host machine.

## What It Does

| Capability | What it gives you |
| --- | --- |
| Gemini assistant | Live voice plus camera-aware Q&A directly from the glasses |
| TapBrowser | A full browser with dashboard shortcuts, media views, and companion-linked pages |
| TapRadio | Gesture-friendly internet radio with favorites, categories, and companion sync |
| TapClaw / OpenClaw | Voice access to a tool-using host agent with heartbeat updates and media handoff |
| Companion app | Browser-based setup for API keys, OAuth, dashboard links, radio, HUD settings, and OpenClaw |
| HUD workflows | Compact AR-friendly status cards, heartbeats, notifications, and assistant output |

## Watch

- Main walkthrough: [youtube.com/watch?v=42DV0rl1NOo](https://www.youtube.com/watch?v=42DV0rl1NOo)
- Short link: [youtu.be/42DV0rl1NOo](https://youtu.be/42DV0rl1NOo)
- Project site: [tropicalstream.github.io/TapInsight](https://tropicalstream.github.io/TapInsight/)

## Latest Build

- Recommended build: [TapInsight Alpha OC.2 APK](https://github.com/tropicalstream/TapInsight/releases/download/tapinsight-alpha-oc.2/tapinsight-alpha-oc.2.apk)
- Release hub: [GitHub Releases](https://github.com/tropicalstream/TapInsight/releases)
- Manual: [TapInsight-User-Guide.html](TapInsight-User-Guide.html)
- Quick companion launcher: [companion.html](companion.html)

## Quick Start

1. Install the latest APK from the release page.
2. Launch TapInsight on the glasses.
3. Connect to the companion app.

USB setup:

```bash
adb forward tcp:19110 tcp:19110
```

Then open:

```text
https://localhost:19110/
```

Accept the local certificate warning on first use.

Wi-Fi setup:

```text
https://<glasses-ip>:19110/
```

If the device falls back to non-TLS mode, try `http://<glasses-ip>:19110/`.

## Companion App

The companion app runs on the glasses and is reached from your phone or laptop browser.

You can configure:

- Gemini API keys and model selection
- Google OAuth, Maps, Calendar, Tasks, and Places
- TapBrowser dashboard links and browser settings
- TapRadio station sync and favorites
- OpenClaw endpoint, token, heartbeat behavior, and relay tooling
- HUD display, voice, brightness, and assistant behavior settings

## Why HTTPS

The companion server prefers HTTPS because the Phone GPS bridge needs a browser secure context. Over `https://localhost:19110/` or `https://<glasses-ip>:19110/`, browsers allow the Geolocation API and the companion app can receive phone GPS updates correctly.

## OpenClaw / TapClaw

TapInsight can connect to an OpenClaw agent on your Mac, Linux, or Windows host.

That gives you:

- voice-triggered host automation
- camera frame relay to the host workspace
- readable heartbeat updates on the glasses HUD
- media and page handoff back into TapBrowser

If you want to explore that path, start here:

- [OpenClaw](https://openclaw.ai)
- [TapInsight User Guide](TapInsight-User-Guide.html)
- [Companion launcher](companion.html)

## Repository Guide

| Path | Purpose |
| --- | --- |
| `app/` | Main TapInsight Android app |
| `tapbrowser/` | Browser module and AR web runtime |
| `docs/` | GitHub Pages site |
| `companion.html` | Offline quick-launch setup page |
| `TapInsight-User-Guide.html` | Main visual manual |
| `tools/` | Helper scripts such as the image relay installer |

## More Links

- Website: [tropicalstream.github.io/TapInsight](https://tropicalstream.github.io/TapInsight/)
- Releases: [github.com/tropicalstream/TapInsight/releases](https://github.com/tropicalstream/TapInsight/releases)
- Issues: [github.com/tropicalstream/TapInsight/issues](https://github.com/tropicalstream/TapInsight/issues)
- License: [LICENSE](LICENSE)

## Acknowledgments

TapInsight builds on the work of the TapLinkX3 browser project and the broader open-source and AR tooling community.

Special thanks:

- [InformalTechCode / TAPLINKX3](https://github.com/informalTechCode/TAPLINKX3)
- [informalTechCode](https://github.com/informalTechCode)
- [glxblt76](https://github.com/glxblt76)
