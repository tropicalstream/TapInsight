# TapInsight 1.1.0

TapInsight 1.1.0 replaces the beta line with a cleaner stable-style release while keeping the alpha release intact for historical reference.

## Highlights

- Optional **Phone GPS Bridge** for companion-assisted location fixes
  - Enable it from the companion app only when needed
  - Safer 1.0.1/1.1.0 handoff so enabling phone GPS no longer tears down the glasses app
- Stronger **maps and multi-location workflows**
  - multi-location maps keep candidate places visible and switchable
  - current user location is shown in the multi-location view
  - candidate colors match their map pins
  - selecting a candidate opens directions using the existing AR navigation flow
- Better **Gemini location result routing**
  - numbered location list cards open maps
  - prose location cards open Google search with cleaned place/topic queries
- Improved **TapRadio**
  - cleaner genre organization
  - companion station manager keeps genres normalized
  - search/saved stations can be opened directly for stream testing
- Improved **Visualizer**
  - theme switching is cleaner and more deliberate
  - stronger audio response in the spoke, ring, line, and oscilloscope themes
- Better **masked/eyeball mode**
  - now-playing text is more stable and less likely to flicker between old and current tracks

## Safety and data notes

- Maps and location services remain experimental.
- Follow local laws while navigating.
- Do not rely on TapInsight location services for emergencies or life-safety use.
- Local data is stored on the glasses. If you lose the glasses or wipe them, that local data can be lost unless you have your own backup process.

## Useful examples

- `play my youtube history`
- `play youtube subscriptions`
- `nearest coffee shops`
- `learnlm help me understand Kirchhoff's laws`

## Companion access

USB is still the easiest way to access the companion app:

```bash
adb forward tcp:19110 tcp:19110
```

Then open [http://localhost:19110](http://localhost:19110).
