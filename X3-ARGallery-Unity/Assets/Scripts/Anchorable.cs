using UnityEngine;

/// <summary>
/// One placed model's state: HELD (following the user's gaze, repositionable)
/// or LOCKED (anchored to a fixed point in the room so you can walk around it).
///
/// "Locking into a physical space" on the X3 means binding the model to a
/// world pose that the SLAM/6DoF tracker keeps stable as the user moves. The
/// XR Plugin's Head rig provides the tracked camera pose; when LOCKED we simply
/// stop following the head and leave the transform at its world pose — the
/// tracker does the rest, keeping it visually pinned as you orbit it.
///
/// If the installed ARDK exposes an explicit spatial-anchor API, bind it in
/// Lock()/Unlock() where marked // [ARDK-ANCHOR] for drift-resistant anchoring.
/// </summary>
public class Anchorable : MonoBehaviour
{
    public enum State { Held, Locked }
    public State state = State.Held;

    [Tooltip("Distance in front of the eyes while HELD (meters).")]
    public float holdDistance = 1.2f;

    Transform _head;
    Renderer[] _rends;
    Color[] _baseEmission;

    public void Init(Transform head)
    {
        _head = head;
        _rends = GetComponentsInChildren<Renderer>();
        // a soft glow while held tells the user "this is grabbed / movable"
        SetHeldGlow(true);
    }

    void Update()
    {
        if (state == State.Held && _head != null)
        {
            // Follow the gaze: sit holdDistance ahead, face the user, stay upright.
            Vector3 target = _head.position + _head.forward * holdDistance;
            transform.position = Vector3.Lerp(transform.position, target, 0.25f);
            Vector3 look = transform.position - _head.position; look.y = 0f;
            if (look.sqrMagnitude > 0.001f)
                transform.rotation = Quaternion.Slerp(transform.rotation,
                    Quaternion.LookRotation(look), 0.25f);
        }
    }

    public void Lock()
    {
        state = State.Locked;
        SetHeldGlow(false);
        // [ARDK-ANCHOR]: if available, create a world anchor at transform.pose here
        // and parent under it so SLAM drift is corrected. Otherwise the fixed
        // world transform + the tracked Head rig already keeps it pinned.
    }

    public void Unlock()
    {
        state = State.Held;
        SetHeldGlow(true);
        // [ARDK-ANCHOR]: release the world anchor if one was created.
    }

    public void ToggleLock() { if (state == State.Locked) Unlock(); else Lock(); }

    void SetHeldGlow(bool on)
    {
        // gentle emissive tint while held; neutral when locked
        if (_rends == null) return;
        foreach (var r in _rends)
            foreach (var m in r.materials)
                if (m.HasProperty("_EmissionColor"))
                {
                    m.EnableKeyword("_EMISSION");
                    m.SetColor("_EmissionColor", on ? new Color(0.15f, 0.35f, 0.5f) : Color.black);
                }
    }
}
