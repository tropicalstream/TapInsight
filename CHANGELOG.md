# Changelog

## TapInsight 0.4 beta - 2026-06-19

- Bumped the Android app release label to `0.4 beta`.
- Routed local-library and SMB video playback through native ExoPlayer, with folder queue support, previous/next controls, metadata timeout behavior, and sidecar subtitle handling.
- Added a native CC language picker for available subtitle tracks, plus a longer media controls timeout so language selection is practical on glasses.
- Added HDR display correction for RayNeo X3 Pro hardware to avoid overly dark or metallic HDR rendering.
- Added URL read-aloud handling for the TTS reader so web URLs can still be spoken when Gemini returns a 404 or otherwise cannot fetch the page directly.
- Added YouTube dim-mode stability and thermal/battery guardrails intended to reduce background load while watching YouTube.
- Added YouTube fullscreen toolbar reveal from X3 Pro touchpad movement, avoiding click-to-reveal pauses.
- Improved dim-mode audio visualizer behavior so bass, mid, and high waveforms use distinct frequency shapes and volume-reactive color.
- Saved Gemini research reports into the Media Browser `Text/` folder with date/time filenames for later reading.
- Reduced Fish.audio chat-card readout stalls by using larger native readout segments and larger Fish WAV chunks while preserving the Media Browser MP3 reader path.
- Removed maintainer-specific relay defaults and personal filesystem hints from agent/media-routing code and docs; users must configure their own relay endpoints.
- Cleaned companion app wording and AI prompt text so host setup is generic across macOS, Linux, and Windows.
- Removed old public release notes and local backup/media-browser backup artifacts from the publishable tree.
- Rechecked security-sensitive files and kept API keys, OAuth values, local properties, keystores, APK outputs, and device/build artifacts out of the release tree.
