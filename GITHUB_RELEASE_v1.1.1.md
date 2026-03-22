# TapInsight 1.1.1

## Highlights

- Background audio is now much more reliable when the projector is off / low-power display mode is active.
  - You can keep listening while only using the waveguides when needed, which helps save battery.
- Optional **Phone GPS Bridge** lets TapInsight use a connected phone's GPS as a higher-accuracy location source for maps and nearby-place queries.
  - Use the companion app over `https://<glasses-ip>:19110` when enabling this module from a phone browser.
- Visualizer/theme refresh with multiple new lightweight scenes tuned for the glasses display, including cinematic and scenic audio-reactive themes plus a new orchestra-style theme.
- Companion and browser polish for setup, radio, navigation, and general day-to-day use on the glasses.

## Safety / Notes

- Maps and location services remain experimental.
- Follow local laws while using AR navigation features.
- Do not rely on location services for emergencies.
- If you lose the glasses, you lose locally stored data unless you back it up.
- Logged-in website sessions are not part of the published source or APK.

## Setup reminders

- USB companion access:
  - `adb forward tcp:19110 tcp:19110`
  - then open `http://localhost:19110`
- Wi-Fi companion access:
  - `http://<glasses-ip>:19110`
  - or `https://<glasses-ip>:19110` for phone GPS bridge flows

## Included in this release

- Low-power / projector-off audio playback stability improvements
- Optional phone GPS bridge module in the companion app
- Visualizer theme updates and new orchestra scene
- Current TapRadio, companion, and browser improvements from the working tree
