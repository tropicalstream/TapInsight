# TapInsight Beta Point Update

This point beta update tightens the on-glasses experience with better media playback continuity, cleaner diagnostics, stronger map/location behavior, and new visualizer polish.

## What's new

### Visualizer themes
Masked/eyeball mode now includes sequential visualizer themes with cleaner theme switching.

Highlights:
- theme order cycles sequentially
- swipe-to-move no longer changes themes accidentally
- reduced blank-frame/artifact behavior while switching themes

### Better Google API error reporting
Google API failures now surface more clearly, which makes setup and debugging less opaque.

Highlights:
- clearer error visibility for Google-backed features
- easier diagnosis when an API, OAuth setup, or response path fails

### `play youtube history` now works
YouTube history playback now uses the user’s actual watch history flow more reliably.

Example:
- `play my youtube history`

Highlights:
- improved history routing and playback logic
- closer alignment with the latest watched items in YouTube history
- designed so history can continue more naturally across devices

### Firmer map location services
Location and mapping behavior were tightened for more reliable place and route handling.

Highlights:
- better map/location plumbing
- stronger handling around destination/location context
- more dependable map-related responses on-glasses

## Release assets
- `tapinsight-beta-point.apk`
- `TapInsight-Beta-Point-source.tar.gz`

## Security / packaging note
This release was prepared from a scrubbed temporary export of the working repository. Local SDK paths, local user-identifying paths, private-IP defaults, and obvious secret patterns were scanned and removed from the publishable package before rebuilding.
