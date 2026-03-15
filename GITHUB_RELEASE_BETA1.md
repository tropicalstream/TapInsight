# TapInsight Beta 1

TapInsight Beta 1 builds on the current public TapInsight release and adds a stronger assistant + browser workflow for RayNeo X3 Pro.

## New in Beta 1

- Custom research capability with a configurable model provider
  - Configure a separate research model in the companion app for deeper answers.
  - Example: `research the history of cybernetics`
  - Example: `research Bay Area AI meetups this weekend`

- Address-aware Google Maps directions handoff
  - Gemini cards with a real address can open directions from your current location.
  - If a card does not contain an address, TapLink opens a Google search for that topic instead.
  - Example: `what is the address of Chez Panisse?`

- YouTube topic playback
  - Open and autoplay topic- or artist-based YouTube queues in TapBrowser.
  - Example: `open youtube depeche mode`
  - Example: `open youtube retro computers`

- YouTube subscriptions playback
  - Uses the signed-in YouTube subscriptions feed.
  - Example: `play youtube subscriptions`

- YouTube history playback
  - Uses the signed-in YouTube history feed.
  - Example: `open youtube history`

## Companion Access over USB

Run:

```bash
adb forward tcp:19110 tcp:19110
```

Then open:

- `http://localhost:19110`

This forwards the TapInsight companion server on the glasses to your computer over USB so setup and debugging do not depend on Wi-Fi discovery.

## Safety

Use navigation and HUD features responsibly. Follow local laws, stay aware of your surroundings, and do not let the glasses distract you while walking, driving, or cycling.
