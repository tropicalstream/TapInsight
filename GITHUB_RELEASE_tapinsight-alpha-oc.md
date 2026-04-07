# TapInsight Alpha OC

This alpha refresh updates TapRadio, podcast playback, OpenClaw ticker behavior, and media URL handling.

## Bug Fix Refresh

- TapRadio follow-up playback now uses the selected station's stream URL instead of re-looking up by station name, which fixes failed or unreliable station playback after Gemini search results.

## Gemini Radio & Podcast Discovery

TapRadio now integrates directly with Gemini voice commands, giving you hands-free access to 30,000+ internet radio stations and millions of podcasts through Apple's iTunes database, all played through the native ExoPlayer with full toolbar controls.

## Search & Browse Stations

- "Play classical" returns a list of classical radio stations and podcasts to browse.
- "Play jazz" helps you discover jazz stations worldwide, then pick the one you want.
- "Search news stations" finds news radio from NPR, BBC, and thousands more.

## Podcast Playback

The keyword "podcast" now routes directly to TapRadio's podcast engine, which searches Apple's iTunes database, fetches the latest episode from the show's RSS feed, and streams it through the native player.

- "Play the Flashpoints podcast" finds and plays the latest episode.
- "Search news podcasts" lets you browse podcasts by topic before committing.
- "Play KPFA" plays a named radio station directly without a search step.

## Native TapRadio Player

All audio, radio stations and podcast episodes alike, now plays through TapRadio's native ExoPlayer interface with adaptive 60-120 second buffering. No more raw browser media player windows.

- The play/pause toolbar reflects current playback state for both stations and podcasts.
- Station skipping works across saved stations.
- Requesting a new podcast or station automatically stops the current stream.
- Persistent playback continues across page navigation via the `DualWebViewGroup` overlay.

## Additional Fixes

- Heartbeat ticker refresh: OpenClaw connection status now updates reliably with timestamps shown up front.
- URL sanitization: spaces in media domains like "tap claw" are automatically stripped to prevent `ERR_NAME_NOT_RESOLVED`.
- OpenClaw message prefix: all OpenClaw messages are now prefixed with "Open Claw says" for clarity.
- YouTube routing guard: radio and podcast keywords no longer get hijacked by the YouTube fast-path.

## Notes

- `local.properties` is intentionally excluded from the published source.
- Configure your own OpenClaw gateway URL and token in the companion UI before using gateway features.
- This APK was built from a clean temp export, not from the live working tree.
