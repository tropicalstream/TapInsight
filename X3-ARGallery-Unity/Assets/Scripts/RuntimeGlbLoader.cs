using System;
using System.Threading.Tasks;
using UnityEngine;
using GLTFast;   // package: com.unity.cloud.gltfast  (Window > Package Manager > Add by name)

/// <summary>
/// Loads a GLB file from local disk into the scene at runtime using glTFast.
///
/// glTFast is the practical runtime glTF/GLB loader for Unity on Android — it
/// handles the parsing, textures, and mesh instantiation off the main thread
/// where possible. (USDZ is NOT supported at runtime; that's why the gallery
/// is GLB-only.)
///
/// The loaded model is parented under a fresh holder GameObject so the
/// Anchorable/PlacementController can move, scale, and lock it as one unit.
/// </summary>
public class RuntimeGlbLoader : MonoBehaviour
{
    /// <summary>
    /// Instantiate a GLB at the given pose/scale. Returns the holder transform,
    /// or null on failure. Adds a BoxCollider sized to the model so gaze can hit it.
    /// </summary>
    public async Task<Transform> LoadAsync(string localGlbPath, Vector3 position,
                                           Quaternion rotation, float targetSizeMeters)
    {
        var holder = new GameObject("ARModel");
        holder.transform.SetPositionAndRotation(position, rotation);

        var gltf = new GltfImport();
        bool ok = await gltf.Load("file://" + localGlbPath);
        if (!ok) { Destroy(holder); return null; }

        var inst = await gltf.InstantiateMainSceneAsync(holder.transform);
        if (!inst) { Destroy(holder); return null; }

        NormalizeScale(holder.transform, targetSizeMeters);
        AddFitCollider(holder);
        return holder.transform;
    }

    /// <summary>Scale the model so its largest dimension ≈ targetSizeMeters,
    /// so a giant or tiny source model still appears a sensible size in the room.</summary>
    static void NormalizeScale(Transform holder, float targetSizeMeters)
    {
        if (targetSizeMeters <= 0f) return;
        var b = ComputeBounds(holder);
        float largest = Mathf.Max(b.size.x, b.size.y, b.size.z);
        if (largest > 0.0001f)
        {
            float k = targetSizeMeters / largest;
            holder.localScale = holder.localScale * k;
        }
    }

    static void AddFitCollider(GameObject holder)
    {
        var b = ComputeBounds(holder.transform);
        var col = holder.AddComponent<BoxCollider>();
        col.center = holder.transform.InverseTransformPoint(b.center);
        col.size = b.size; // approximate; good enough for gaze selection
        holder.tag = "Selectable"; // ensure this tag exists in Tags & Layers
    }

    static Bounds ComputeBounds(Transform t)
    {
        var rends = t.GetComponentsInChildren<Renderer>();
        if (rends.Length == 0) return new Bounds(t.position, Vector3.one * 0.3f);
        var b = rends[0].bounds;
        for (int i = 1; i < rends.Length; i++) b.Encapsulate(rends[i].bounds);
        return b;
    }
}
