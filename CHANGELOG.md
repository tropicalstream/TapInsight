# Changelog

## tapinsight-alpha-oc.1 - 2026-04-07

### Added
- Gemini voice commands can now discover internet radio stations and podcasts through TapRadio and hand playback directly to the native ExoPlayer player.
- The HUD now shows a Gemini status badge, an OpenClaw crab health icon, and a charging indicator beside battery status.

### Improved
- Gemini Live starts faster from New Chat while still preparing camera-aware context in the background.
- OpenClaw status handling is more resilient across local Wi-Fi, hotspot, and tunnel usage.
- The expanded chat reader uses a safer reading frame, lighter fades, better typography, and smoother live-scroll behavior while Gemini is speaking.

### Fixed
- Gemini Live no longer mixes tool-assist text into spoken reply cards, which reduced mid-response cutoffs and garbled assistant output.
- Camera-driven Gemini prompts now prepare location and time context before image reasoning, improving place and horizon questions.
- Landmark image requests now require valid absolute URLs and fall back more safely when Gemini cannot supply a direct image link.
- OpenClaw heartbeat updates remain visible in the HUD ticker with clearer status text and more reliable refresh timing.
- Radio and podcast playback metadata now stays attached to the native TapRadio player instead of falling back to generic stream labels.
- Local gateway examples and default endpoints were scrubbed from the publishable copy.

### Docs
- TapClaw/OpenClaw setup guidance now covers Linux, macOS, and Windows host systems.
