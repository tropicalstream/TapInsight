TapInsight v0.2.3 Beta

Highlights
- Added an optional phone-to-glasses GPS bridge module in the companion app.
- Improved map/location handling without changing the default glasses location path.
- Map result cards and search-result cards now split more cleanly between navigation and Google search.
- Visualizer toggle behavior is more reliable.
- Multi-location map flows remain available for comparing alternate destinations.

New in this build
- Phone GPS Bridge (optional, off by default)
  - Enable it from the companion app when the glasses' own location is inaccurate.
  - The phone can push fresh GPS fixes to the glasses over the companion connection.
  - If left off, existing map/location services are unchanged.
- Search behavior polish for location results
  - Dedicated map cards keep opening maps.
  - The parallel Gemini prose/search card opens Google search instead.
- Map close behavior
  - Closing the nav subwindow now returns Google search results for the selected place.
- Visualizer toggle fix
  - The visualizer button now behaves like a true on/off toggle.

Using the phone GPS bridge
1. Open the companion page on your phone.
2. Go to `Phone GPS Bridge (Optional Module)`.
3. Leave it off unless you need it.
4. If needed, tap `Use This Phone's GPS` and allow location permission in the phone browser.
5. Use map/location queries normally while the phone fix stays fresh.
6. Tap `Stop Phone GPS` to disable it and revert to the normal glasses location pipeline.

Install
```bash
adb install -r tapinsight-v0.2.3-beta.apk
```

Companion access over USB
```bash
adb forward tcp:19110 tcp:19110
```
Then open `http://localhost:19110` in your browser.

Important notes
- Maps and location services are experimental.
- Follow your local laws and stay aware of your surroundings.
- Do not rely on TapInsight location services for emergencies.
- If you lose your glasses, you lose the local data stored on them unless you have backed it up elsewhere.
- API keys, OAuth tokens, saved lessons, and other app data are stored locally on the glasses.
