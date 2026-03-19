TapInsight Beta 0.2.2

Highlights
- Multi-location maps now let you open directions for one destination while keeping alternate destinations available for quick switching.
- AR directions can return to the original multi-location list, so destination comparison is smoother.
- Location handling is stricter for navigation-sensitive flows, reducing stale or low-confidence fixes from driving routes.
- Google API map flows no longer expose raw Maps URLs in Gemini-visible responses.
- GPS hardware support is declared explicitly for both the app and TapBrowser.

Included in this release
- Improved multi-pin map routing flow
- In-route candidate switching for alternate destinations
- Stronger current-location quality gating for directions and nearby-place queries
- Faster AR directions load path with reduced 3D settle delay
- Security-scrubbed source export and debug APK

Artifacts
- APK: `tapinsight-v0.2.2-beta.apk`
- Source archive: `TapInsight-v0.2.2-beta-source.tar.gz`

Notes
- Use the companion app or `adb forward tcp:19110 tcp:19110` for local setup access.
- For navigation features, wait for a real on-device location fix before trusting nearby or route answers.
