# TapInsight Alpha OC .1

This point release focuses on Gemini Live stability, OpenClaw reliability, and a cleaner on-glasses reading experience.

## Highlights

- Gemini Radio & Podcast Discovery now routes voice requests into TapRadio with native ExoPlayer playback and metadata handoff.
- Expanded Gemini chat cards now stay readable while streaming, with improved spacing, live-scroll anchoring, and softer top/bottom fades.
- Gemini Live now avoids mixing tool-assist output into the same spoken reply card, reducing cutoffs and garbled mid-answer text.
- Camera-backed Gemini prompts now prepare location and time context before image reasoning.
- OpenClaw heartbeat status stays visible in the HUD ticker, with clearer refreshes and a crab gateway health indicator.
- The HUD now includes a Gemini status badge and a charging indicator next to battery status.

## Additional fixes

- Faster New Chat startup for Gemini Live sessions.
- Safer landmark image opening: invalid relative media URLs are rejected instead of opened.
- Better OpenClaw endpoint fallback behavior across LAN, hotspot, and tunnel use.
- Local gateway defaults and local-only files were scrubbed from the publishable source.

## Setup note

- TapClaw/OpenClaw host setup documentation now covers Linux, macOS, and Windows.

## Security

- Built from a clean export in `/tmp`, not from the active working tree.
- `local.properties`, local backup files, and user-specific local endpoint defaults were removed from the publishable source.
