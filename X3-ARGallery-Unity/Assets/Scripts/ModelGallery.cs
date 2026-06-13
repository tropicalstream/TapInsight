using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using UnityEngine;
using UnityEngine.Networking;

/// <summary>
/// The floating menu of downloadable models, read from StreamingAssets/gallery.json.
/// Rendered on a world-space Canvas (wire it in the Inspector). The user moves a
/// gaze highlight across the cards (slide) and taps to pick (AppController routes
/// the tap to PlacementController.SpawnAndHold).
///
/// Kept deliberately simple for a young user: a vertical list of named cards,
/// a status line, and a gaze cursor. No typing, no URLs on screen.
/// </summary>
public class ModelGallery : MonoBehaviour
{
    [Serializable] public class Entry { public string name; public string thumb; public string url; public float scaleMeters = 0.3f; }
    [Serializable] class GalleryFile { public Entry[] models; }

    [Header("Wire these in the Inspector")]
    public Transform head;
    public RectTransform listRoot;        // world-space Canvas content parent
    public GameObject cardPrefab;         // a card with a TMP_Text label + a Collider tagged "Selectable"
    public TMPro.TMP_Text statusText;
    public Canvas canvas;

    public bool IsOpen { get; private set; }
    readonly List<Entry> _entries = new List<Entry>();
    readonly List<GameObject> _cards = new List<GameObject>();
    int _focus = 0;

    IEnumerator Start()
    {
        yield return LoadManifest();
        BuildCards();
        Hide();
    }

    IEnumerator LoadManifest()
    {
        string path = Path.Combine(Application.streamingAssetsPath, "gallery.json");
        string json;
        if (path.Contains("://")) // Android: StreamingAssets is inside the APK
        {
            using var req = UnityWebRequest.Get(path);
            yield return req.SendWebRequest();
            json = req.result == UnityWebRequest.Result.Success ? req.downloadHandler.text : "{}";
        }
        else json = File.Exists(path) ? File.ReadAllText(path) : "{}";

        var parsed = JsonUtility.FromJson<GalleryFile>(json);
        _entries.Clear();
        if (parsed?.models != null) _entries.AddRange(parsed.models);
    }

    void BuildCards()
    {
        foreach (var c in _cards) Destroy(c);
        _cards.Clear();
        for (int i = 0; i < _entries.Count; i++)
        {
            var card = Instantiate(cardPrefab, listRoot);
            var label = card.GetComponentInChildren<TMPro.TMP_Text>();
            if (label) label.text = _entries[i].name;
            card.name = $"Card_{i}";
            _cards.Add(card);
        }
        UpdateFocusVisual();
    }

    public void Show() { IsOpen = true; if (canvas) canvas.enabled = true; PositionInFront(); SetStatus("Look at a model and tap to place it."); }
    public void Hide() { IsOpen = false; if (canvas) canvas.enabled = false; }

    void PositionInFront()
    {
        if (!head || !canvas) return;
        var t = canvas.transform;
        t.position = head.position + head.forward * 1.6f;
        Vector3 f = head.forward; f.y = 0;
        if (f.sqrMagnitude > 0.001f) t.rotation = Quaternion.LookRotation(f);
    }

    /// <summary>Slide moves the highlight; used by AppController.HandleSlide.</summary>
    public void ScrollFocus(float delta)
    {
        if (_cards.Count == 0) return;
        if (Mathf.Abs(delta) < 0.01f) return;
        _focus = Mathf.Clamp(_focus + (delta > 0 ? 1 : -1), 0, _cards.Count - 1);
        UpdateFocusVisual();
    }

    /// <summary>Pick the model the gaze dot is on, else the focused card.</summary>
    public bool TryPickGazedModel(out Entry entry)
    {
        entry = null;
        // gaze hit first
        if (head && Physics.Raycast(head.position, head.forward, out var hit, 6f) &&
            hit.collider.CompareTag("Selectable"))
        {
            int idx = _cards.FindIndex(c => hit.collider.transform.IsChildOf(c.transform) || hit.collider.gameObject == c);
            if (idx >= 0) { entry = _entries[idx]; return true; }
        }
        // fallback: focused card
        if (_focus >= 0 && _focus < _entries.Count) { entry = _entries[_focus]; return true; }
        return false;
    }

    void UpdateFocusVisual()
    {
        for (int i = 0; i < _cards.Count; i++)
        {
            var img = _cards[i].GetComponent<UnityEngine.UI.Image>();
            if (img) img.color = (i == _focus) ? new Color(0.2f, 0.6f, 0.8f, 0.9f)
                                               : new Color(0.1f, 0.1f, 0.12f, 0.7f);
        }
    }

    public void SetStatus(string s) { if (statusText) statusText.text = s; }
}
