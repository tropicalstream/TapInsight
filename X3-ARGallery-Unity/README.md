# AR Gallery for RayNeo X3 Pro — "Place a model in your room and walk around it"

A self-contained Unity AR app for the RayNeo X3 Pro: **download 3D models from free galleries, place them in your real room, lock them in space, and walk all the way around them.** Built for a kid who wants to try it — kept as simple and forgiving as the hardware allows.

> Built from `FABLE_X3_UNITY_GUIDE.md`. Read that guide's §0 first if you're new to X3 Unity dev.

---

## What it does

1. A floating menu shows a few **free, kid-friendly 3D models** (cute CC0 animals/objects).
2. Gaze at one, **tap the temple pad** to download and drop it in front of you.
3. While **unlocked**, the model follows your gaze — move your head to position it.
4. **Tap to lock** it into the room. Now it's anchored to that spot.
5. **Walk around it** — front, back, side — it stays put in space (6DoF / SLAM).
6. **Double-tap** a locked model to unlock and reposition; double-tap empty space to go back to the menu / exit.

---

## ⚠️ Three honest truths (read these or it won't work)

**1. It loads GLB, not USDZ.** USDZ is Apple's format and Unity-on-Android can't open it on the device. Every model this app loads is **GLB/glTF** — which all the kid-friendly free galleries provide. If you find a USDZ you love, convert it to GLB once on a computer (Apple's *Reality Converter*, Blender's glTF export, or `usd2gltf`) and host the GLB. See `gallery.json`.

**2. "Walk around it" uses SLAM (6DoF); "grab it" uses gaze+tap, not hand tracking.** The glasses track *your position* well enough to orbit a locked model — that's the wow. They do **not** have reliable free-form hand grabbing (only a few recognized gestures). So you grab/move with **look + tap**, which is actually easier for a kid than pinching in mid-air. An optional gesture hook is included but off unless the SDK reports gesture support.

**3. 6DoF is power-hungry and can drift.** Walking-around tracking uses the camera + SLAM, which heats the glasses and can wander, especially in plain or dim rooms. Use it in a **well-lit room with some visual texture** (furniture, posters — not a blank white wall), keep sessions to ~10–15 min, and the app locks to 30 fps to stay cool (see the thermal lesson in the starter guide — over-rendering can reboot the glasses).

---

## Setup (a human does these once; an AI can do everything else)

You need **Unity 2022.3.36f1** with Android Build Support + OpenJDK + Android SDK/NDK, and the **RayNeo OpenXR Unity ARDK (1.1.2)**. Then follow **`SETUP_CHECKLIST.md`** step by step — it lists every Editor click (create the project, import the ARDK + glTFast, drop in the XR Plugin prefab, attach these scripts, wire the Inspector fields, build). The scripts in `Assets/Scripts/` and the manifest in `Assets/Plugins/Android/` are ready to drop in.

Fastest start: clone the official sample (`github.com/MaxManausa/RayNeoX3Pro-MITSample`), then copy this project's `Assets/Scripts`, `Assets/Plugins`, and `Assets/StreamingAssets` into it and follow the checklist from "scene setup" onward.

---

## Adding your own models

Edit `Assets/StreamingAssets/gallery.json` — each entry is a name, a thumbnail, and a **direct GLB URL** (CC0 / freely licensed). Examples and trusted free sources are listed in that file. Keep models small (a few MB, low-poly) so they download fast and run cool on the glasses.

---

## Files

| File | What it is |
|---|---|
| `SETUP_CHECKLIST.md` | the click-by-click Editor assembly steps |
| `Assets/Plugins/Android/AndroidManifest.xml` | the X3 manifest (Mercury meta-data, AR_APP category, permissions) |
| `Assets/StreamingAssets/gallery.json` | the curated model list (edit to add your own) |
| `Assets/Scripts/AppController.cs` | app lifecycle, 30 fps lock, audio focus, exit |
| `Assets/Scripts/ModelGallery.cs` | the floating menu of downloadable models |
| `Assets/Scripts/ModelDownloader.cs` | downloads + caches GLB files |
| `Assets/Scripts/RuntimeGlbLoader.cs` | loads a GLB into the scene at runtime (glTFast) |
| `Assets/Scripts/Anchorable.cs` | one model's lock/unlock/anchor state |
| `Assets/Scripts/PlacementController.cs` | gaze + temple-tap placement, lock, reposition |
| `Assets/Scripts/GestureGrabOptional.cs` | optional gesture-grab, off unless SDK supports it |

---

*Made with care, and with the truths above kept honest so it actually works in her hands. Have fun. 🦊*
