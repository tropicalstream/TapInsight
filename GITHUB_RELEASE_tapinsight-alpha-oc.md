# TapInsight Alpha OC

This public alpha packages the latest OpenClaw-focused HUD and routing updates from the current TapInsight working tree.

## Highlights

- Persistent OpenClaw ticker under the HUD clock with better heartbeat/status continuity.
- OpenClaw gateway crab indicator near AQI with green/red health state.
- Gemini status badge moved into the top HUD row next to battery.
- Charging indicator shown when external power is connected.
- Reduced stray Gemini route/place tool calls by guarding native map tool execution against the actual spoken intent.
- Refined OpenClaw HUD behavior so the last meaningful status stays visible instead of dropping back immediately to a generic connection line.

## Setup Notes

- `local.properties` is intentionally excluded from the published source.
- Configure your own OpenClaw gateway URL and token in the companion UI before using gateway features.
- This release is built from a clean temp export, not from the live working tree.

## Warnings

- This is an alpha build and may contain unfinished UI and workflow changes.
- OpenClaw features depend on your own gateway availability and credentials.
