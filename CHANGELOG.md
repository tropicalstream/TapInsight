# Changelog

## TapInsight OC Beta 3 - 2026-06-15

- Bumped the Android app release label to `Beta .3`.
- Routed local-library and SMB video playback through native ExoPlayer, with folder queue support, previous/next controls, metadata timeout behavior, and sidecar subtitle handling.
- Added a native CC language picker for available subtitle tracks, plus a longer media controls timeout so language selection is practical on glasses.
- Added HDR display correction for RayNeo X3 Pro hardware to avoid overly dark or metallic HDR rendering.
- Added YouTube dim-mode stability and thermal/battery guardrails intended to reduce background load while watching YouTube.
- Improved dim-mode audio visualizer behavior so bass, mid, and high waveforms use distinct frequency shapes and volume-reactive color.
- Cleaned companion app wording and AI prompt text so host setup is generic across macOS, Linux, and Windows.
- Removed tracked bulky release artifacts from source control: old backup bundle, sample video, and RayNeo developer-guide PPTX.
- Rechecked security-sensitive files and kept API keys, OAuth values, local properties, keystores, APK outputs, and device/build artifacts out of the release tree.

