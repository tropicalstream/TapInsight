using System;
using UnityEngine;

// ============================================================================
//  SDK SHIMS — TEMPORARY STUBS so the project COMPILES before the real RayNeo
//  OpenXR Unity ARDK is wired in. REPLACE each of these with the actual ARDK
//  calls (see FABLE_X3_UNITY_GUIDE.md and the vendor manual in
//  docs/rayneo-devguide/). They are deliberately inert in the Editor so you can
//  open, compile, and reason about the project before you have the SDK hooked up.
//
//  Search the codebase for "// [ARDK]" to find every place these are used.
// ============================================================================

/// <summary>Temple-pad gestures. REPLACE with the ARDK TouchPad event source
/// (the vendor manual's Scene1Ctrl / LatticeBrain binding: single/double/triple
/// tap + slide). For Editor testing, keyboard keys fire the events.</summary>
public static class TempleInput
{
    public static event Action OnSingleTap;
    public static event Action OnDoubleTap;
    public static event Action<float> OnSlide;

    // Editor-only fallbacks so you can test the flow without glasses:
    // Space = single tap, Backspace = double tap, Up/Down arrows = slide.
    public static void EditorPump()
    {
#if UNITY_EDITOR
        if (Input.GetKeyDown(KeyCode.Space)) OnSingleTap?.Invoke();
        if (Input.GetKeyDown(KeyCode.Backspace)) OnDoubleTap?.Invoke();
        if (Input.GetKeyDown(KeyCode.UpArrow)) OnSlide?.Invoke(1f);
        if (Input.GetKeyDown(KeyCode.DownArrow)) OnSlide?.Invoke(-1f);
#endif
    }
}

/// <summary>Audio focus. REPLACE with the ARDK AudioFocus API
/// (AudioFocus.SetAudioFocusStatus / Regist / UnRegist per the vendor manual).</summary>
public static class AudioFocusBridge
{
    public static bool SetAudioFocusStatus(bool request)
    {
        Debug.Log($"[stub] AudioFocus.SetAudioFocusStatus({request}) — replace with ARDK call");
        return true;
    }
}

/// <summary>Device system info. REPLACE with the ARDK Device System Access API
/// (GetGlobalCpuTemperature / GetScreenBrightness / SetScreenBrightness).</summary>
public static class DeviceSystem
{
    public static float GetGlobalCpuTemperature() => 0f; // stub: 0 = "unknown / fine"
}

/// <summary>Optional gesture capability. REPLACE with the ARDK gesture API.
/// Returns false here so GestureGrabOptional stays disabled until confirmed.</summary>
public static class GestureCapability
{
    public static event Action OnGrabGesture;
    public static bool IsSupported() => false; // stub: no gestures until ARDK says so
    public static void Raise() => OnGrabGesture?.Invoke(); // for manual testing
}
