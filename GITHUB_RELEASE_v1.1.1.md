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

## Phone GPS Bridge Usage

The phone GPS bridge works only when the phone browser can reach the glasses companion server over the same network.

Recommended flow:

1. Put the phone and glasses on the same network.
2. Find the glasses IP in the glasses Wi-Fi settings.
3. Open `https://<glasses-ip>:19110` on the phone.
4. Accept the local certificate warning once if needed.
5. Turn **Phone GPS Bridge** on in the companion app.
6. Tap **Use This Phone's GPS**.
7. Allow browser location permission.

Supported real-world setups:

- **Home / office Wi-Fi**
  - Both devices connected to the same Wi-Fi network.
- **iPhone Personal Hotspot**
  - Connect the glasses to the iPhone hotspot, then open the companion page on that iPhone.
- **Android hotspot**
  - Connect the glasses to the Android hotspot, then open the companion page on that Android phone.
- **Other same-network arrangements**
  - Travel router, mobile hotspot puck, or any shared LAN where both devices can reach each other.

Notes:

- If the phone and glasses are not on the same network, the bridge will not work.
- If the network isolates clients from each other, the bridge will not work until that is disabled or you switch networks.
- Prefer `https://<glasses-ip>:19110` for phone GPS bridge use, especially on iPhone.

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
