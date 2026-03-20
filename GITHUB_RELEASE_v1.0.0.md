# TapInsight 1.0.0

TapInsight 1.0.0 is the first whole-number public release and supersedes the prior beta builds while leaving the original alpha available for reference.

## What's New Since The Last Push

- LearnLM tutoring mode with saved lesson memory and reference images
- Custom research routing/model support
- Improved Google Maps directions and multi-location flows
- Optional phone GPS bridge module in the companion app
- Better YouTube playback flows including history, subscriptions, and topic playback
- TapRadio visualizer themes with stronger audio response
- TapRadio genre cleanup and companion-side station preview in new tabs
- Cleaner camera/chat-card presentation and tighter map-vs-search routing
- Better Google API error reporting and more robust map/location services

## Examples

- `learnlm help me solve this triangle`
- `research the latest battery breakthroughs`
- `play my youtube history`
- `play youtube subscriptions`
- `open youtube ambient coding music`
- ask for nearby restaurants, then tap the numbered results card to open the multi-location map

## Companion Access

Use USB for the easiest setup:

```bash
adb forward tcp:19110 tcp:19110
```

Then open [http://localhost:19110](http://localhost:19110) in your browser.

## Notes

- Maps/location services are experimental.
- Follow your local laws while using navigation or media in AR glasses.
- Do not rely on location services for emergencies.
- If you lose the glasses, you lose the local data stored on them unless you back it up.
