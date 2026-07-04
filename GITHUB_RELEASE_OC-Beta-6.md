# TapInsight 0.6 beta

0.6 beta promotes the new **custom HUD workspace** for RayNeo X3 Pro into the gold branch. It builds on 0.5 beta's radio/media work with a live, user-arranged HUD shelf for useful cards, pinned media, camera preview placement, and browser-aware metadata.

## Highlights

- **Custom HUD pin board.** Pin useful HUD objects into the calibrated shelf above the browser, including icons, post-it notes, image pins, media cards, and live browser/media metadata.
- **One-tap HUD editing.** Enter HUD modify mode to move or delete pinned items without disturbing the WebView underneath.
- **Live HUD cards.** Watched cards refresh on interval, dim when stale, and keep the HUD useful without covering the browser content.
- **Camera preview shelf calibration.** The camera preview and pinned objects share the measured HUD shelf instead of the older narrow strip, reducing overlap with browser content.
- **Browser-aware pinning.** Pin metadata from the live WebView; `add_live` now refuses static links so the HUD stays tied to what is actually on screen.

## Public TODO

- Continue on-glasses validation of custom HUD placement across bright mode, video playback, and camera-preview-heavy workflows.

## Security And Packaging

- No API keys, OAuth secrets, keystores, APKs, local properties, or build outputs are committed.
- The release APK is built from a scrubbed export with placeholder defaults only.
