# TapInsight 0.3 beta

0.3 beta focuses on native media playback, YouTube stability, and release hygiene for RayNeo X3 Pro.

## Highlights

- Native ExoPlayer path for local and SMB videos.
- Folder queue plus previous/next controls for native video playback.
- CC language picker with available subtitle tracks and sidecar `.srt` support.
- Longer media controls timeout for subtitle selection.
- HDR display correction tuned for the X3 Pro panel.
- URL read-aloud handling in the TTS reader for pages Gemini cannot fetch directly.
- YouTube fullscreen toolbars reveal from X3 Pro touchpad movement instead of click-to-reveal.
- YouTube dim-mode thermal/battery guardrails.
- Frequency-reactive dim-mode visualizer waveforms for bass, mids, and highs.
- Generic companion app host wording across macOS, Linux, and Windows.

## Public TODO

- Bright mode: continue validating that bright-mode YouTube/video playback does not reboot the glasses.

## Security And Packaging

- No API keys, OAuth secrets, keystores, APKs, local properties, or build outputs are intended to be committed.
- Old public release notes and local media-browser backup artifacts were removed before this release.
- The release APK is a debug build for sideload testing on RayNeo X3 Pro hardware.
