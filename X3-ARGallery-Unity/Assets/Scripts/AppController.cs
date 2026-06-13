using System.Collections;
using UnityEngine;

/// <summary>
/// App lifecycle for the X3 AR Gallery.
///
/// Responsibilities:
///  - lock the frame rate to 30 (the AR1 Gen1 is thermally tight; chasing 60
///    under Unity + SLAM is the documented reboot path — see FABLE_X3_STARTER_GUIDE).
///  - request/release audio focus (RayNeo ARDK requirement) around the session.
///  - own the top-level gesture routing (single tap = act on gaze target,
///    double tap = back/exit) by delegating to PlacementController.
///  - keep SLAM/6DoF enabled only while a model is locked and being explored,
///    to save power (6DoF is expensive).
///
/// SDK-SPECIFIC TOUCHPOINTS are marked // [ARDK]: confirm the exact class /
/// namespace against the installed OpenXR Unity ARDK (1.1.2). The shapes here
/// follow the vendor manual (Touch Pad, Audio Focus, Device System Access).
/// </summary>
public class AppController : MonoBehaviour
{
    [Tooltip("The XR Plugin's Head camera transform (the user's eyes).")]
    public Transform head;

    [Tooltip("Scene controllers wired in the Inspector.")]
    public ModelGallery gallery;
    public PlacementController placement;

    [Header("Comfort / safety")]
    [Tooltip("CPU temp (°C) above which we warn and ease off 6DoF. 0 = disabled.")]
    public float cpuTempWarnC = 0f;

    void Awake()
    {
        // 30 fps hard cap — the single most important comfort/thermal setting.
        Application.targetFrameRate = 30;
        QualitySettings.vSyncCount = 0;
        // Keep the screen awake during an AR session.
        Screen.sleepTimeout = SleepTimeout.NeverSleep;
    }

    void Start()
    {
        RequestAudioFocus(true);
        // Subscribe to temple-pad gestures.
        // [ARDK]: replace with the SDK's TouchPad event registration. The vendor
        // manual exposes single/double/triple tap + slide via a binder
        // (Scene1Ctrl / LatticeBrain). Wire those callbacks to the methods below.
        TempleInput.OnSingleTap += HandleSingleTap;
        TempleInput.OnDoubleTap += HandleDoubleTap;
        TempleInput.OnSlide     += HandleSlide;

        gallery.Show(); // start in the model menu
    }

    void HandleSingleTap()
    {
        // If the menu is open and the gaze dot is on a model card, pick it;
        // otherwise the tap goes to placement (drop / lock / unlock).
        if (gallery.IsOpen && gallery.TryPickGazedModel(out var entry))
            placement.SpawnAndHold(entry);
        else
            placement.OnTap();
    }

    void HandleDoubleTap()
    {
        // Double-tap: if a model is locked + gazed, unlock it to reposition;
        // if we're holding a model, cancel it; otherwise toggle the menu / exit.
        if (placement.HandleDoubleTap()) return;
        if (gallery.IsOpen) { gallery.Hide(); Application.Quit(); } // exit from menu
        else gallery.Show();                                        // back to menu
    }

    void HandleSlide(float delta) => gallery.ScrollFocus(delta);

    void Update()
    {
        // Editor-only: keyboard stands in for temple taps (Space/Backspace/↑↓)
        // so the whole flow is testable before the ARDK events are wired.
        TempleInput.EditorPump();

        if (cpuTempWarnC > 0f)
        {
            // [ARDK]: DeviceSystem.GetGlobalCpuTemperature() per the manual.
            float t = DeviceSystem.GetGlobalCpuTemperature();
            if (t > cpuTempWarnC) placement.EaseOffTracking(); // back off 6DoF when hot
        }
    }

    void OnApplicationPause(bool paused)
    {
        // Sleep button / glasses removed: release focus; re-acquire on resume.
        RequestAudioFocus(!paused);
        if (paused) placement.PauseTracking(); else placement.ResumeTracking();
    }

    void OnDestroy()
    {
        RequestAudioFocus(false);
        TempleInput.OnSingleTap -= HandleSingleTap;
        TempleInput.OnDoubleTap -= HandleDoubleTap;
        TempleInput.OnSlide     -= HandleSlide;
    }

    void RequestAudioFocus(bool want)
    {
        // [ARDK]: AudioFocus.SetAudioFocusStatus(want) per the vendor manual.
        try { AudioFocusBridge.SetAudioFocusStatus(want); } catch { /* SDK absent in editor */ }
    }
}
