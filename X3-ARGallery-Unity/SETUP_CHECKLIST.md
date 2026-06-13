# Setup Checklist — AR Gallery (every click, in order)

A human performs these once. An AI can author/adjust any script but cannot click the Editor.
Check each box; the order matters.

## A. Toolchain
- [ ] Install **Unity 2022.3.36f1** (Unity Hub) with modules: **Android Build Support**, **OpenJDK**, **Android SDK & NDK Tools**.
- [ ] Have the **RayNeo OpenXR Unity ARDK 1.1.2** package downloaded (from the RayNeo developer wiki).
- [ ] ADB works to the glasses (Settings → General → swipe far-left, bounce the wall 10× to enable ADB; zadig driver fix on Windows if unrecognized).

## B. Project
- [ ] Create a new **3D (URP)** project in 2022.3.36f1. *(Built-in RP works too; URP is lighter.)*
- [ ] **Window → Package Manager → + → Add package by name:** `com.unity.cloud.gltfast` (the runtime GLB loader). Let it resolve.
- [ ] Import the **RayNeo OpenXR ARDK** package.
- [ ] Copy this project's `Assets/Scripts`, `Assets/Plugins`, and `Assets/StreamingAssets` folders into the new project's `Assets/`.
- [ ] **Project Settings → Tags and Layers → Tags:** add a tag **`Selectable`** (the loader and gallery cards use it).

## C. Scene
- [ ] New scene. **Delete the default Main Camera and Directional Light.**
- [ ] From `Packages/RayNeo OpenXR ARDK/SDK/Runtime/Resources/Prefab`, drag the **XR Plugin** prefab into the scene.
- [ ] Set the **Game view resolution to 640×480**.
- [ ] On the **Head** camera (child of XR Plugin): **Clear Flags = Solid Color, Color = pure black (0,0,0,0)**. *(Black = see-through on the waveguide. This is the #1 thing to get right.)*

## D. UI (world-space menu)
- [ ] Create **UI → Canvas**; set **Render Mode = World Space**. Scale it small (e.g. 0.001) and size ~600×400. This is the model menu.
- [ ] Under the Canvas, add a **vertical layout** content object (`ListRoot`) and a **TMP_Text** `StatusText` at the bottom. (Import TMP Essentials if prompted.)
- [ ] Make a **Card prefab**: a UI **Image** with a child **TMP_Text** label, and a **BoxCollider** on the card sized to it, tagged **`Selectable`** (so gaze can hit cards).

## E. Wire the scripts
- [ ] Create an empty GameObject **`App`**. Add components: **AppController**, **PlacementController**, **ModelGallery**, **ModelDownloader**, **RuntimeGlbLoader**, and (optional) **GestureGrabOptional**.
- [ ] In the Inspector, set every `head` field to the **Head** camera transform.
- [ ] **AppController:** assign `gallery` and `placement`.
- [ ] **PlacementController:** assign `head`, `downloader`, `loader`, `gallery`.
- [ ] **ModelGallery:** assign `head`, `listRoot` (the ListRoot), `cardPrefab`, `statusText`, `canvas`.
- [ ] **GestureGrabOptional:** assign `placement`; leave `enableGestureGrab` **OFF** (only turn on after confirming gestures work on your unit).

## F. SDK wiring (replace the stubs)
- [ ] Open `Assets/Scripts/SdkShims.cs`. Replace each stub with the real ARDK calls:
  - `TempleInput` → the ARDK TouchPad single/double/triple-tap + slide events (vendor manual: Touch Pad / Scene1Ctrl / LatticeBrain).
  - `AudioFocusBridge` → `AudioFocus.SetAudioFocusStatus` etc.
  - `DeviceSystem.GetGlobalCpuTemperature` → the Device System Access API (optional comfort throttle).
  - `GestureCapability` → the gesture API (optional; leave unsupported if your unit lacks it).
- [ ] In `AppController.Update()` (Editor only) you can call `TempleInput.EditorPump()` to test with the keyboard (Space=tap, Backspace=double-tap, ↑/↓=slide) before you have the SDK events.

## G. Player settings & build
- [ ] **Project Settings → Player → Android:** package name (match the manifest, e.g. `com.yourco.argallery`), Minimum API **29**, orientation **Landscape**, Scripting Backend **IL2CPP**, target architecture **ARM64**.
- [ ] Confirm `Assets/Plugins/Android/AndroidManifest.xml` is present (the X3 manifest with the Mercury meta-data + AR_APP category; no `ar_mode`).
- [ ] **Application.targetFrameRate is locked to 30 in AppController.Awake()** — leave it.
- [ ] **File → Build Settings → Android → Build** (or **Build and Run** with glasses on ADB).
- [ ] `adb install -r <built>.apk` if you built without Run.

## H. First run (with your daughter)
- [ ] Well-lit room with some visual texture (not a blank white wall — SLAM needs features).
- [ ] Launch from the glasses launcher → the model menu floats ahead.
- [ ] Look at a model, **tap** → it downloads and appears, held in front, glowing softly.
- [ ] Move your head to aim, **tap** → it locks into the room.
- [ ] **Walk around it.** 🎉
- [ ] **Double-tap** the locked model to unlock and move it again; **double-tap** empty space for the menu.
- [ ] Keep the first session ~10–15 min (6DoF is power-hungry); if the glasses get warm, take a break.

## Troubleshooting
- *Grey background hides the room* → Head camera Clear Flags not solid black.
- *Renders in one eye* → `ar_mode` left in the manifest, or you didn't use the XR Plugin's Head camera.
- *Can't select a model/card* → missing `Selectable` tag or no Collider.
- *Model won't load* → it's USDZ (convert to GLB) or the URL isn't a direct GLB; check `gallery.json`.
- *Tracking drifts / model swims* → poor lighting or a featureless wall; add texture/light; 6DoF has limits (no ARCore).
- *Glasses get hot / reboot* → too many polys/particles or not 30 fps; keep models small, stay at 30.
