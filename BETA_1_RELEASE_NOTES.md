# TapInsight Beta 1

TapInsight Beta 1 expands the RayNeo X3 Pro assistant and browser workflow with stronger hands-free research, navigation, and media playback.

## Highlights

- Custom research provider support
  - Configure a separate research model in the companion app and use it for deeper responses.
  - Example: `research the history of cybernetics`
  - Example: `research Bay Area AI meetups this weekend`

- Google Maps directions and AR navigation handoff
  - Tap a Gemini chat card that contains an address to open directions from your current location.
  - Example: ask `what is the address of Chez Panisse?`, then tap the address card.
  - If the card does not contain an address, TapLink opens a Google search for the card topic instead of a broken map.

- YouTube topic playback
  - Ask for a topic or artist and TapLink builds a playable queue in the browser.
  - Example: `open youtube depeche mode`
  - Example: `open youtube retro computers`

- YouTube subscriptions playback
  - Opens the signed-in YouTube subscriptions feed and plays from that feed order.
  - Example: `play youtube subscriptions`

- YouTube history playback
  - Opens the signed-in YouTube history feed and plays from that feed order.
  - Example: `open youtube history`

## Companion App Access over USB

Run:

```bash
adb forward tcp:19110 tcp:19110
```

What it does:
- Forwards port `19110` from the glasses to your computer over USB.
- Lets you open the companion app locally at `http://localhost:19110`.
- Avoids Wi-Fi discovery issues when you are setting up API keys, OAuth, dashboards, or TapRadio stations.

After forwarding, open:

- `http://localhost:19110`

## Safety

Use navigation features responsibly. Follow local laws, keep awareness of your surroundings, and do not let the glasses distract you while walking, driving, or cycling.
