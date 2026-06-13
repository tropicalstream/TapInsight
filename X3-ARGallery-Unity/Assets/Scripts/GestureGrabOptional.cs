using UnityEngine;

/// <summary>
/// OPTIONAL hand-gesture grab — OFF by default and safe to ignore.
///
/// IMPORTANT EXPECTATION-SETTING: the RayNeo X3 Pro does NOT offer free-form
/// skeletal hand tracking. The vendor SDK lists "gesture recognition" for a few
/// PREDEFINED 2D gestures only (a "follow-up capability"). So you cannot reliably
/// pinch-and-drag a model in mid-air. The dependable interaction is gaze + temple
/// tap (PlacementController) — which is also easier for a child.
///
/// This component is a thin, guarded hook: IF the installed ARDK reports gesture
/// support, a recognized "grab"/"pinch" gesture toggles lock on the gazed model,
/// mirroring a double-tap. If gesture support is absent, it disables itself and
/// nothing changes. Treat it as a bonus, never a dependency.
/// </summary>
public class GestureGrabOptional : MonoBehaviour
{
    public PlacementController placement;

    [Tooltip("Leave OFF unless you've confirmed gesture recognition works on your unit.")]
    public bool enableGestureGrab = false;

    void Start()
    {
        if (!enableGestureGrab) { enabled = false; return; }
        // [ARDK]: check whether gesture recognition is available on this device,
        // e.g. GestureRecognition.IsSupported(). If not, disable and bail.
        if (!GestureCapability.IsSupported()) { enabled = false; return; }

        // [ARDK]: subscribe to recognized gestures. The vendor exposes a fixed
        // gesture set; map a "grab"/"pinch" to a lock toggle.
        GestureCapability.OnGrabGesture += HandleGrab;
    }

    void HandleGrab()
    {
        // Mirror the double-tap reposition flow so the UX is consistent.
        placement.HandleDoubleTap();
    }

    void OnDestroy()
    {
        if (enabled) GestureCapability.OnGrabGesture -= HandleGrab;
    }
}
