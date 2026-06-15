# Fable Unity Guide — Building a Self-Contained AR App for the RayNeo X3 Pro from Scratch

*A complete, bottom-up implementation guide for a frontier model building a Unity app on the RayNeo X3 Pro — from an empty Unity project to a running APK — focused on generating graphics, sound, and the glasses' UI/UX. This is the **official** RayNeo path (OpenXR Unity ARDK), grounded in the vendor developer manual and the MIT Reality Hack 2026 materials. Companion to `FABLE_X3_STARTER_GUIDE.md` (hardware truths) and the native-Android / Apple cookbooks.*

**Running example used throughout:** a self-contained scene — a **glowing 3D orb floating in space that you gaze at to select, which then pulses with light/particles and plays a spatial sound**, plus a **world-space UI panel** the user drives with the temple touchpad. It exercises graphics, sound, gaze UX, and touch input — everything a first vibe-coded AR app needs.

---

## 0. How to use this guide — and two honest truths about Unity on the X3

This is the path RayNeo officially supports and documents, and the **only** one that gives you real spatial AR: 3DoF/6DoF tracking, plane detection, image recognition, gaze-ray interaction — capabilities the native-Android path simply does not have (the X3 has no ARCore). If your app is "3D content anchored in the world," Unity is the answer. If it's "a 2D HUD over the camera," the native path (`FABLE_X3_STARTER_GUIDE.md`) is leaner. Choose by that line.

**Honest truth #1 — Unity is heavier on this chip.** The community (and this project's owner) report the X3 Pro's AR1 Gen1 is resource-strained under Unity, with limited on-device tracking training data. It works — the official sample ships — but you must budget aggressively (§12). Treat 30 fps as the ceiling, not 60.

**Honest truth #2 — an AI cannot click the Unity Editor.** Unity development is half C# scripting (which a frontier model writes well) and half Editor manipulation: dragging prefabs, wiring Inspector fields, setting Build Settings, baking. A model **cannot** do the GUI half. So this guide is written as *"scripts the model authors"* + *"precise Editor steps the model instructs a human to perform."* Every Editor action below is spelled out as a checklist a person can follow blind. The fastest scaffold is to **start from the official sample** (§2) so most Editor wiring already exists.

Build order: **choose Unity → environment → scaffold → manifest → hardware budget → graphics → sound → UI/UX → AR capabilities → worked example → lifecycle → performance → issues → FAQ → test → build.**

---

## 1. When to choose Unity (and what you're committing to)

| You want… | Path |
|---|---|
| 3D objects anchored in the room, gaze interaction, plane/image tracking | **Unity (this guide)** |
| A 2D info HUD, web content, media, voice assistant | Native Android (starter guide) |
| Apple/Google health + AI piped to a HUD | The bridge cookbooks |

Committing to Unity means: a ~10–30 GB toolchain, Editor-driven workflow, the ARDK package, and a heavier runtime — in exchange for the SDK doing **binocular rendering, head tracking, and gaze interaction for you** (you do *not* hand-roll `BinocularSbsLayout` here — the XR Plugin prefab handles both eyes automatically).

---

## 2. Environment setup (from scratch)

Versions are vendor-pinned — deviating causes the most common "it won't build" failures:

1. **Install Unity Hub**, then **Unity 2022.3.36f1** (the version RayNeo's manual pins). Add modules: **Android Build Support**, **OpenJDK**, **Android SDK & NDK Tools**.
2. **Get the OpenXR Unity ARDK** (RayNeo's SDK, the manual's "ARDK Download" — current line **1.1.2**). It's a `.unitypackage` / UPM package from the RayNeo developer wiki.
3. **Fastest start — clone the official sample** and open it in 2022.3.36f1: `https://github.com/MaxManausa/RayNeoX3Pro-MITSample`. It already has the XR Plugin prefab placed, Build Settings configured, and the manifest merged — so a human can build to the glasses in minutes, and the model edits from a known-good baseline instead of wiring everything blind.
4. **ADB** working against the glasses (starter guide: Settings → General → swipe far-left, bounce the wall 10× to enable ADB; Windows driver fix via zadig if unrecognized).

> **Model's role here:** you can't run Unity Hub. Generate the *exact* version/module list and the clone command for the human, then take over once the project opens and you're authoring `.cs` files and Project Settings text.

---

## 3. Project scaffold

If starting fresh rather than from the sample (Editor steps for a human):

1. New Unity project, **3D (URP)** or **3D (Built-in)** template — URP gives cheaper, more controllable rendering on mobile GPUs; prefer it (§6).
2. Import the RayNeo OpenXR ARDK package.
3. **Create a scene**; **delete the default Main Camera and Directional Light**.
4. From `Packages/RayNeo OpenXR ARDK/SDK/Runtime/Resources/Prefab`, **drag the `XR Plugin` prefab into the scene.** This is the heart of every X3 Unity app. Its children (per the vendor manual):
   - **XR Plugin** — the main object.
   - **Head** — the camera rig for **3DoF head tracking** (this *is* your camera; it renders both eyes).
   - **LaserBeam** — the gaze-ray controller projected from the camera center.
   - **BeamGraphic** — the visible ray (can be disabled for a cleaner look).
   - **LaserBeamDot** — the cursor dot where the ray hits a collider.
5. **Set the Game view to 640×480** (Game view → add resolution 640×480) so what you preview matches one eye.
6. Project Settings → Player → Android: set package name, minimum API (29), orientation landscape, and (Editor) the splash/black background.

The model authors scripts and can *describe* every one of these clicks precisely, but a human performs steps 1–6 once.

---

## 4. The Android manifest (Unity-generated + required merges)

Unity generates `AndroidManifest.xml`, but the X3 needs specific entries. Provide a custom manifest at `Assets/Plugins/Android/AndroidManifest.xml` so Unity merges yours:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.yourco.x3unity">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.CAMERA" />          <!-- ShareCamera -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />    <!-- mic capture -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" /> <!-- GPS via phone -->
    <uses-feature android:name="android.hardware.camera" android:required="false" />

    <application android:label="@string/app_name" android:icon="@mipmap/app_icon">
        <!-- REQUIRED: RayNeo Mercury app marker (or it won't show in the launcher / binocular) -->
        <meta-data android:name="com.rayneo.mercury.app" android:value="true" />
        <!-- DO NOT add ar_mode — it forces left-lens-only rendering -->

        <activity android:name="com.unity3d.player.UnityPlayerActivity"
            android:theme="@style/UnityThemeSelector"
            android:screenOrientation="landscape"
            android:launchMode="singleTask"
            android:configChanges="density|orientation|screenSize|screenLayout|keyboardHidden|keyboard"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <!-- RayNeo launcher integration -->
                <category android:name="com.rayneo.intent.category.AR_APP" />
            </intent-filter>
            <meta-data android:name="unityplayer.UnityActivity" android:value="true" />
        </activity>
    </application>
</manifest>
```

The three non-negotiables (same as every X3 app): `com.rayneo.mercury.app` meta-data, the `AR_APP` launcher category, and **no `ar_mode`**. The ARDK may inject its own entries on top — let Unity's manifest merger combine them; resolve conflicts by keeping the ARDK's XR entries and your launcher/permission entries.

---

## 5. Hardware budget (pin these before you build content)

| Truth | Unity consequence |
|---|---|
| 640×480 per eye, binocular auto-rendered by the XR Plugin | design/preview at 640×480; the SDK draws both eyes — don't duplicate yourself |
| ~36° FOV | content must sit within a narrow central cone; edges are unseen |
| Black = transparent on the waveguide | **camera clear must be solid black (or transparent)**; black pixels = see-through (§6) |
| AR1 Gen1, 4 GB, 245 mAh, thermal >500 mA = trouble | hard 30 fps target, tight poly/draw-call budget (§12) |
| 60 Hz display | no benefit chasing >60; 30 is the comfortable real target under Unity |
| Temple click = a KEY event natively, but the ARDK TouchPad API abstracts it | use the SDK's TouchPad/gaze events, not raw Android key handling (§8) |

---

## 6. Graphics — rendering for a waveguide

### 6.1 The one camera setting that defines AR look
On the `Head` camera, set **Clear Flags = Solid Color** with **color = pure black (0,0,0)**, or the camera background to transparent. On the waveguide, **black is not drawn — it's the real world showing through.** So your scene is bright objects floating on nothing. Never use a skybox or a lit background; that would paint a wall of light over reality. This is the single most important graphics decision and the most common beginner mistake (a "grey background" means you're occluding the world).

### 6.2 Render pipeline & materials
- Prefer **URP** with mobile-tuned settings (no HDR unless needed, MSAA off or 2×, shadows minimal/off). The AR1 is a mobile GPU; overdraw is the enemy.
- Use **Unlit** or **additive/emissive** materials for that glowing-hologram look — they're cheap and read beautifully on a transparent display. Lit materials need light sources and cost more; on a black world they often look worse anyway.
- For the orb's glow: an emissive unlit material + a soft additive particle halo reads as "hologram" and costs little.

### 6.3 Particles & effects, on a budget
Unity's particle system is fine **if capped**: a few hundred particles, additive blend, no collision, no lights. The orb's "pulse" can be a single burst of ~100 additive sprites scaled by an `AnimationCurve` — visually rich, nearly free. Avoid full VFX Graph / GPU particles (overkill and heavy here).

### 6.4 Text & UI graphics
Use **TextMeshPro** on a **world-space Canvas** (§8). White or bright text with a subtle dark outline reads in daylight (the starter guide's outdoor lesson applies — though on a transparent display "dark" means lower intensity). Keep canvases small and few; every canvas is a draw cost.

### 6.5 The budget, concretely
Target per frame: a few thousand triangles, **under ~50 draw calls**, minimal overdraw, 30 fps locked. A glowing orb + a particle burst + a small panel is comfortably within this. A detailed 3D environment is not — keep scenes spare, which also suits the AR aesthetic.

---

## 7. Sound — generating and managing audio

### 7.1 Playing sound (Unity native)
Standard Unity `AudioSource` works — no Web Speech limitation here (that was a WebView constraint; Unity uses the platform audio directly). For the orb: an `AudioSource` on the orb GameObject, **Spatial Blend = 1 (3D)**, so the sound appears to come from the orb's position in space — genuinely spatial audio through the glasses' speakers. Set a sensible rolloff (Linear, max distance a few meters) so it fades with the FOV scale.

```csharp
// On selection: pulse + spatial chime
audioSource.spatialBlend = 1f;            // 3D positional
audioSource.PlayOneShot(chimeClip);
particleBurst.Play();
StartCoroutine(PulseGlow());              // animate emissive intensity
```

### 7.2 Audio focus (the ARDK API — important on glasses)
The glasses require apps to **request audio focus explicitly** and **release it on close**, or audio behaves badly when mixed with system sounds. The ARDK exposes (vendor manual):

```csharp
// Request focus when audio starts mattering; release on pause/quit.
bool ok = AudioFocus.SetAudioFocusStatus(true);     // request
AudioFocus.RegistAudioFocusChangeCallBack(OnFocusChanged);
// ... on OnApplicationPause(true) / OnDestroy:
AudioFocus.SetAudioFocusStatus(false);              // release
AudioFocus.UnRegistAudioFocusChangeCallBack(OnFocusChanged);
```
(Exact class/namespace per the ARDK version — confirm against the SDK; the manual documents `SetAudioFocusStatus`, `RegistAudioFocusChangeCallBack`, `UnRegistAudioFocusChangeCallBack`.)

### 7.3 Microphone capture modes (if the app listens)
RayNeo glasses expose beamforming modes set via Android `AudioManager.setParameters("audio_source_record=<mode>")` before recording. X3 modes (vendor manual): `voice_recognition` (3 mics, wearer-only, 16 kHz — for voice commands), `camcorder` (stereo temple mics — for recording), `record_translation` (front mics, ambient), `voice_communication` (calls), and **`off` — which you MUST set on stop or the mic stays seized**. From Unity, call through a small AndroidJavaObject bridge to `AudioManager`.

---

## 8. UI / UX — the glasses interaction model

### 8.1 Head + Gaze (the XR Plugin does the heavy lifting)
The `Head` rig gives **3DoF head tracking** automatically — look around and the camera rotates; world-space content stays put. The **LaserBeam** projects a gaze ray from the center of view; **LaserBeamDot** marks where it hits a collider. This is the X3's primary pointing model: **you point with your head/eyes, the dot is your cursor.** Put a **Collider** on anything selectable (the orb needs one) or the ray can't hit it.

### 8.2 The TouchPad API (temple input, abstracted)
You don't parse raw Android key events in Unity — the ARDK surfaces temple gestures. Per the vendor manual the TouchPad supports **swipe up/down (X3), single/double/triple tap**, with bindable events (the manual's `Scene1Ctrl` + `LatticeBrain` pattern) and a configurable `m_MovingThreshold` / `m_IsMovingChange` for slide sensitivity. Conventions (vendor-recommended, match every X3 app):
- **Slide forward/back** → move focus / cycle selection.
- **Single tap** → activate what the gaze dot is on (select the orb).
- **Double tap** → back / **exit the app** (the manual's "double-tap exit" — wire this in every scene, users expect it).
- **Triple tap** → mode/special.

```csharp
// Conceptual: subscribe to ARDK touch events + raycast the gaze.
void OnTpSingleTap() {
    if (Physics.Raycast(head.position, head.forward, out var hit, 5f) &&
        hit.collider.CompareTag("Selectable"))
        hit.collider.GetComponent<Orb>().Activate();   // pulse + sound
}
void OnTpDoubleTap() => Application.Quit();             // expected exit gesture
```

### 8.3 World-space UI
Put your panel on a **world-space Canvas** placed a comfortable ~1.5–2 m in front of the Head at start, sized to sit within the ~36° FOV. Don't use screen-space-overlay canvases — they fight the binocular/gaze model. Buttons are selected by gaze-dot + tap, not touch.

### 8.4 Slide-direction caveat
Forward/back slide semantics can flip with the system's "natural mode" setting (also noted in the MercurySDK reference). Design for "forward/back," never hardcode "left/right," or offer a settings toggle.

---

## 9. AR capabilities — what Unity unlocks (and the caveats)

Per the vendor SDK capability matrix, **Unity-only** (the native path can't do these):
- **3DoF** — head rotation; hover a panel in a fixed direction. Always available.
- **Gaze Ray** — center-of-view selection/click/drag. The core interaction.
- **6DoF** — position + rotation; anchor a virtual object at a fixed point in the room. **Caveat: runs via SLAM/ShareCamera, high power, no ARCore** — budget thermally, expect drift, test outdoors vs indoors.
- **Image recognition & tracking** — recognize a book/poster, anchor content to it.
- **Plane detection** — horizontal, textured surfaces; **detection takes 3–5 s and is available only at initialization** (don't expect continuous re-scan).
- **Face detection** — overlay info near a detected face.
- **Gesture recognition** — 2D hand gestures (a "follow-up capability").

For the worked example, **3DoF + Gaze** is enough and the most reliable; reach for 6DoF/planes only when the app genuinely needs world anchoring, and budget for the power/thermal cost.

---

## 10. Worked example — the interactive orb, from scratch

**Goal:** a glowing orb floats ~2 m ahead; gaze at it and single-tap; it pulses with light + particles and plays a spatial chime; a small world-space panel shows a hint and a tap counter; double-tap exits.

**Editor steps (human):**
1. From the sample/fresh scaffold, confirm the `XR Plugin` prefab is in the scene, Game view 640×480.
2. Create a **Sphere** ~0.3 m, at (0, 0, 2) relative to Head. Tag it `Selectable`. Ensure it has a **Sphere Collider**.
3. Material: new **URP/Unlit** (or Built-in Unlit), emissive cyan; assign to the sphere.
4. Add a **Particle System** child: additive material, ~100 particles, burst on demand, Play On Awake off.
5. Add an **AudioSource** to the sphere: assign a short chime clip, **Spatial Blend = 1**, Play On Awake off.
6. Create a **world-space Canvas** at (0, -0.4, 2), small scale; add a TextMeshPro label "Gaze + tap the orb" and a counter.
7. Attach the `Orb.cs` and `AppController.cs` scripts (below) and wire the Inspector references.

**Scripts (model authors):**

```csharp
// Orb.cs — on the sphere
public class Orb : MonoBehaviour {
    public ParticleSystem burst; public AudioSource audioSrc;
    public Renderer rend; public TMPro.TMP_Text counter;
    int taps;
    public void Activate() {
        taps++; counter.text = $"Taps: {taps}";
        audioSrc.spatialBlend = 1f; audioSrc.Play();
        burst.Play();
        StopAllCoroutines(); StartCoroutine(Pulse());
    }
    System.Collections.IEnumerator Pulse() {
        var mat = rend.material; float t = 0;
        while (t < 0.6f) { t += Time.deltaTime;
            float k = Mathf.Sin(t / 0.6f * Mathf.PI);
            mat.SetColor("_EmissionColor", Color.cyan * (1f + 3f * k));
            yield return null; }
    }
}
```

```csharp
// AppController.cs — on the XR Plugin or a manager object
public class AppController : MonoBehaviour {
    public Transform head;            // the Head camera transform
    // Subscribe these to the ARDK TouchPad events in Start() per the SDK API.
    void OnSingleTap() {
        if (Physics.Raycast(head.position, head.forward, out var hit, 5f)
            && hit.collider.CompareTag("Selectable"))
            hit.collider.GetComponent<Orb>()?.Activate();
    }
    void OnDoubleTap() => Application.Quit();          // expected exit
    void OnApplicationPause(bool paused) {
        if (paused) AudioFocus.SetAudioFocusStatus(false);   // release on background
        else AudioFocus.SetAudioFocusStatus(true);
    }
}
```

That's a complete, self-contained AR app: graphics (emissive orb + particles on a transparent world), spatial sound, gaze+tap UX, world-space UI, and the expected exit gesture.

---

## 11. Threading & lifecycle

- Unity is **single-threaded for the API** — all GameObject/Transform/UI work on the main thread; use **coroutines** (as above) for time-based animation, `async`/`Job System` only for pure computation.
- **`OnApplicationPause(true)`** fires when the user removes the glasses or the sleep button engages (the starter guide's onPause note) — release audio focus and the mic mode (`audio_source_record=off`) here; re-acquire on resume.
- **`OnDestroy`/`OnApplicationQuit`** — release ShareCamera (`ShareCamera.CloseCamera(handle)`), unregister focus callbacks, stop the mic. The vendor manual stresses releasing camera/GPS/sensors promptly to avoid background drain.
- Process death under 4 GB pressure applies as on native — keep restorable state lightweight.

---

## 12. Performance & thermals — Unity makes this stricter

Everything in the starter guide's thermal lesson applies **doubly** under Unity (it's a heavier runtime):
- **Lock 30 fps** (`Application.targetFrameRate = 30`); chasing 60 wastes power for no perceptible AR gain and risks the thermal/reboot path.
- **Quality Settings**: lowest viable — shadows off/minimal, no HDR, MSAA ≤2×, texture sizes modest, anisotropic off.
- **Draw calls under ~50**, triangles in the low thousands, overdraw minimal (additive particles are overdraw — cap them).
- **6DoF/SLAM and plane detection are power-hungry** — enable only while needed, disable when not.
- **Vendor guidance**: ~30 fps UI, APL <13%, sustained draw <500 mA. Exceed it and the glasses heat, then reboot (we have the native-side logs proving the reboot path; Unity reaches it faster).
- Profile on-device with the Unity Profiler over USB; watch GPU time and battery temperature.

---

## 13. Potential issues (exhaustive)

**Setup / build**
- *Wrong Unity version* — not 2022.3.36f1 ⇒ ARDK incompatibility, cryptic errors. Pin it.
- *Missing Android modules* — no OpenJDK/SDK/NDK ⇒ build fails. Install via Unity Hub.
- *ARDK version mismatch* — SDK 1.1.2 vs project expectations; keep them aligned, re-import cleanly.
- *Manifest merge conflict* — your manifest vs the ARDK's; keep XR entries from the ARDK and your launcher/permission entries; check the merged `AndroidManifest.xml` in the build output.
- *Won't appear in launcher* — missing `com.rayneo.mercury.app` meta-data or the `AR_APP` category.
- *Renders in one eye only* — `ar_mode` present, or you replaced the XR Plugin camera with a plain Main Camera. Use the prefab's `Head`.

**Graphics**
- *Grey/opaque background occluding the world* — camera Clear not solid black; this is the #1 visual mistake.
- *Content invisible / off-screen* — placed outside the ~36° FOV cone or behind the Head; keep it centered and ~1.5–2 m out.
- *Washed out in daylight* — increase emissive intensity; bright additive reads better than lit materials on a transparent display.
- *Frame drops / heat* — overdraw from too many additive particles/canvases, shadows on, 60 fps target. Cap and lock 30.

**Sound**
- *Audio cuts out or won't mix* — focus not requested, or not released on pause (other apps then can't duck). Use the AudioFocus API both ways.
- *Mic stays on / blocks other apps* — you didn't set `audio_source_record=off` on stop. Always reset.
- *Sound isn't spatial* — `spatialBlend` left at 0 (2D); set to 1 for positional.

**UX / input**
- *Can't select the orb* — no Collider on it, or the gaze ray length too short; add a collider, raycast a few meters.
- *Double-tap doesn't exit* — not wired; users expect double-tap = back/exit in every scene.
- *Slide direction feels backwards* — natural-mode flip; design for forward/back, not left/right.
- *Gaze dot drifts* — 3DoF is rotation-only; if you expect content to stay put as you *walk*, you need 6DoF (and accept its cost/drift).

**AR capability caveats**
- *Plane detection never fires* — it only runs at init and takes 3–5 s on a textured horizontal surface; don't expect continuous scanning.
- *6DoF drifts / drains battery* — SLAM via ShareCamera, no ARCore; budget thermally, test in varied lighting.
- *Image tracking loses the target* — lighting/angle sensitive; keep targets well-lit and flat.

**Workflow**
- *The model "can't build the project"* — correct; it writes scripts and Project Settings and instructs the human through Editor steps. Start from the official sample to minimize blind Editor wiring.

---

## 14. FAQ

**Do I hand-roll binocular rendering like the native path?** No — the XR Plugin's `Head` rig renders both eyes automatically. That's a major reason to use Unity.

**Why does my background hide the real world?** Camera Clear isn't solid black. Black = transparent on the waveguide; set Clear Flags → Solid Color → (0,0,0).

**Can an AI build the whole Unity app for me?** It can write every C# script, the manifest, and Project Settings, and give you exact Editor steps — but it can't operate the Editor GUI. Pair it with a human for the clicks, or start from the official sample so most wiring is done.

**Unity or native Android for my idea?** Unity for 3D-anchored AR (gaze, 6DoF, planes, image tracking). Native for 2D HUDs, web, media, voice. The X3 has no ARCore, so Unity is the *only* way to get real spatial tracking.

**What frame rate should I target?** 30. The display is 60 Hz but the AR1 under Unity is happier at 30, and AR content gains nothing perceptible from 60 while costing heat and battery.

**How do I do voice commands?** Native mic via `audio_source_record=voice_recognition` through an AndroidJavaObject bridge, or pipe audio to a cloud STT/LLM. Unity has no built-in speech; the WebView limitation doesn't apply here (you're not in a WebView).

**Is 6DoF reliable?** Usable but power-hungry and drift-prone (SLAM, no ARCore). Great for short anchored experiences; budget thermally for long ones.

**Will it overheat?** It can — Unity is heavy on the AR1. Lock 30 fps, strip Quality Settings, cap draw calls/particles, disable SLAM/planes when unused. The reboot path is real (starter guide).

**Where's the official sample and docs?** Sample: `github.com/MaxManausa/RayNeoX3Pro-MITSample`. Docs: the RayNeo developer manual (feishu) — Touch Pad, Sharecamera, Audio Focus, Audio Capture Modes, Device System Access, Build Your First XR Application; local exports in `docs/rayneo-devguide/`.

**Can I read screen brightness / CPU temp?** Yes — the ARDK Device System Access API: `GetScreenBrightness()`, `SetScreenBrightness(0–1)`, `GetGlobalCpuTemperature()` (useful to self-throttle), `SetGlassLegTouchEventExchange(bool)` to swap which temple is which.

---

## 15. Test plan / acceptance

1. **Builds & launches** from the glasses launcher (manifest correct, appears as an AR app).
2. **Binocular** — content in both eyes, fused, not one-eye (XR Plugin correct, no `ar_mode`).
3. **Transparent world** — black background shows the real room; only the orb/panel glow.
4. **Gaze + select** — looking at the orb places the dot on it; single tap activates pulse + spatial sound.
5. **Spatial audio** — turning your head moves the apparent sound source.
6. **Exit** — double-tap quits cleanly; audio focus and mic released (check no lingering mic).
7. **Thermal soak** — run 20+ min at locked 30 fps; temples stay cool, no reboot (the starter guide's `top -H` + thermal-log method).
8. **Pause/resume** — remove glasses / sleep button: audio focus released, restored on resume.
9. **(If used) 6DoF/planes** — anchor holds within tolerance; disable when leaving that mode.

---

## 16. Build & install

Editor (human): **File → Build Settings → Android → Build** (or Build and Run with the glasses connected). Then:
```bash
adb install -r path/to/your.apk
```
Or use **Build and Run** with the glasses on ADB. Confirm it launches binocular with a transparent background and the orb responds to gaze+tap. Commit the project (scenes, scripts, Project Settings, the custom manifest) and push at every milestone — Unity projects are large, so use `.gitignore` for `Library/`, `Temp/`, `Build/`.

---

*Companions: `FABLE_X3_STARTER_GUIDE.md` (hardware truths the Unity runtime still obeys) · `docs/rayneo-devguide/` (the vendor's own Unity manual exports + the MIT Reality Hack deck) · the native-Android and Apple-bridge cookbooks (the non-Unity paths).*
