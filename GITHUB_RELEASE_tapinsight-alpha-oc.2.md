# TapInsight Alpha OC.2

TapInsight Alpha OC.2 packages the latest OpenClaw-focused HUD improvements into a clean GitHub release with refreshed docs and release assets.

## Highlights

- OpenClaw heartbeat status now keeps the latest real heartbeat message in the HUD instead of a generic online/offline label.
- The latest OpenClaw heartbeat is injected into Gemini context so Gemini can immediately explain what the last OpenClaw response means.
- The HUD ticker remains static for this build, which keeps the latest status visible until a new heartbeat arrives.
- The custom YouTube next/previous flow now routes through the next watch URL so the active YouTube page can refresh its displayed title alongside the media change.

## Packaging Notes

- The root README now points to GitHub Releases for APK downloads.
- The user manual is included in the repository root as `TapInsight-User-Guide.html`.
- The clean export removes local build configuration and scrubs local OpenClaw gateway defaults before packaging.

## Setup Notes

- `local.properties` is excluded from the published source.
- If you use TapClaw/OpenClaw, enter your own gateway URL and token from the companion setup page after install.
- Install with `adb install <downloaded-apk>`.

## Overview Video

- [TapInsight Overview Video](https://youtu.be/42DV0rl1NOo)
