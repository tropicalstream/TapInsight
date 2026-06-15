# TapInsight OC Beta 3

Beta 3 focuses on native media playback, YouTube stability, and release hygiene for RayNeo X3 Pro.

## Highlights

- Native ExoPlayer path for local and SMB videos.
- Folder queue plus previous/next controls for native video playback.
- CC language picker with available subtitle tracks and sidecar `.srt` support.
- Longer media controls timeout for subtitle selection.
- HDR display correction tuned for the X3 Pro panel.
- YouTube dim-mode thermal/battery guardrails.
- Frequency-reactive dim-mode visualizer waveforms for bass, mids, and highs.
- Generic companion app host wording across macOS, Linux, and Windows.

## Security And Packaging

- No API keys, OAuth secrets, keystores, APKs, local properties, or build outputs are intended to be committed.
- Old binary artifacts were removed from source control before this release.
- The release APK is a debug build for sideload testing on RayNeo X3 Pro hardware.
