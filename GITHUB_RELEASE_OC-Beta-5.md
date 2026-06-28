# TapInsight 0.5 beta

0.5 beta adds **Radio Garden** to the AR browser dashboard for RayNeo X3 Pro, plus now‑playing metadata display and non‑interrupting song identification.

## Highlights

- **Radio Garden in the browser dashboard.** A Radio Garden tile is now installed automatically in the AR browser dashboard on update — no manual setup. Open it to browse and play stations from the live world map.
  - The page renders in full (no cut‑off map), with pop‑ups blocked so stray windows can't hijack the view.
  - Location works: the dashboard supplies the glasses' best‑known location so the map opens where you are and "near me" browsing behaves correctly.
- **Now‑playing metadata.** When a stream provides track info (ICY / SHOUTcast `StreamTitle`), the artist/title is captured and shown on the masked now‑playing label.
- **Non‑interrupting song identification.** Asking Gemini to identify the current TapRadio song no longer pauses playback — TapRadio keeps playing while Gemini answers.

## Public TODO

- Bright mode: continue validating that bright‑mode YouTube/video playback does not reboot the glasses.

## Security And Packaging

- No API keys, OAuth secrets, keystores, APKs, local properties, or build outputs are committed.
- The release APK is a debug build for sideload testing on RayNeo X3 Pro hardware.
