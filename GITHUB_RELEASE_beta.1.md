# TapInsight beta.1

TapInsight beta.1 is the first beta-tagged publish from the current 1.1.2 working line.

Highlights:
- Browser layout stabilization for the TapBrowser scrollbar and right-shift issue during page loads and back navigation.
- Recent Gemini, TapBrowser, OpenClaw, media, and HUD improvements from the current working tree are included in this release build.
- GitHub pages and documentation assets from the current publishable tree are included.

Setup notes:
- Companion access remains on port `19110`.
- Sensitive local files and machine-specific publish settings were scrubbed from the release source.
- Build was produced from a clean temp export, not from the active working directory.

Known notes:
- This release keeps the in-app Android version name at `1.1.2`; `beta.1` is the GitHub release/tag label for this publish.
