using System.Collections;
using UnityEngine;

/// <summary>
/// The interaction core: download → place → lock → walk around → unlock → reposition,
/// all driven by GAZE + TEMPLE TAP (the reliable X3 model). Hand-tracking is NOT
/// required; see GestureGrabOptional for the optional gesture path.
///
/// Flow:
///  - SpawnAndHold(entry): download the GLB, load it, and HOLD it in front of the
///    user (it follows their gaze).
///  - OnTap(): if holding → LOCK into the room; if gazing at a locked model →
///    (no-op single tap; double-tap unlocks — see HandleDoubleTap).
///  - HandleDoubleTap(): gazing at a locked model → UNLOCK to reposition; holding
///    a model → cancel/remove it. Returns true if it consumed the gesture.
///  - Walking around a LOCKED model "just works" via the XR Plugin's 6DoF tracking.
/// </summary>
public class PlacementController : MonoBehaviour
{
    public Transform head;
    public ModelDownloader downloader;
    public RuntimeGlbLoader loader;
    public ModelGallery gallery;

    [Tooltip("Max gaze-ray distance to select a locked model (meters).")]
    public float gazeReach = 6f;

    Anchorable _held;          // the model currently being positioned (if any)
    bool _busy;                // a download/load is in flight

    public bool HasHeld => _held != null && _held.state == Anchorable.State.Held;

    public void SpawnAndHold(ModelGallery.Entry entry)
    {
        if (_busy) return;
        StartCoroutine(SpawnRoutine(entry));
    }

    IEnumerator SpawnRoutine(ModelGallery.Entry entry)
    {
        _busy = true;
        gallery.SetStatus($"Downloading {entry.name}…");
        string path = null, err = null;
        yield return downloader.GetOrDownload(entry.url, p => path = p, e => err = e);

        if (path == null) { gallery.SetStatus($"Couldn't get {entry.name}: {err}"); _busy = false; yield break; }

        gallery.SetStatus($"Loading {entry.name}…");
        var spawnPos = head.position + head.forward * 1.2f;
        var task = loader.LoadAsync(path, spawnPos, Quaternion.identity, entry.scaleMeters);
        while (!task.IsCompleted) yield return null;

        var t = task.Result;
        if (t == null) { gallery.SetStatus($"Couldn't load {entry.name} (bad GLB?)"); _busy = false; yield break; }

        _held = t.gameObject.AddComponent<Anchorable>();
        _held.Init(head);
        gallery.Hide();
        gallery.SetStatus("Move your head to aim, then tap to lock it in place.");
        _busy = false;
    }

    /// <summary>Single tap.</summary>
    public void OnTap()
    {
        if (HasHeld) { _held.Lock(); gallery.SetStatus("Locked! Walk around it. Double-tap to move it again."); return; }
        // not holding: a single tap on a locked model does nothing destructive
        // (double-tap unlocks) — keeps a kid from losing placement by accident.
    }

    /// <summary>Double tap. Returns true if consumed.</summary>
    public bool HandleDoubleTap()
    {
        if (HasHeld)
        {
            // cancel the held model
            Destroy(_held.gameObject); _held = null;
            gallery.SetStatus("Removed. Open the menu to pick another.");
            return true;
        }
        // gazing at a locked model? unlock it for repositioning.
        if (TryGazeHitAnchorable(out var a) && a.state == Anchorable.State.Locked)
        {
            a.Unlock(); _held = a;
            gallery.SetStatus("Unlocked — move your head, tap to lock again.");
            return true;
        }
        return false; // let AppController handle menu/exit
    }

    bool TryGazeHitAnchorable(out Anchorable a)
    {
        a = null;
        if (Physics.Raycast(head.position, head.forward, out var hit, gazeReach) &&
            hit.collider.CompareTag("Selectable"))
            a = hit.collider.GetComponentInParent<Anchorable>();
        return a != null;
    }

    // --- power / thermal hooks called by AppController ---
    public void EaseOffTracking() { /* [ARDK]: lower 6DoF update rate / pause SLAM if very hot */ }
    public void PauseTracking()   { /* [ARDK]: pause SLAM on app pause */ }
    public void ResumeTracking()  { /* [ARDK]: resume SLAM on app resume */ }
}
