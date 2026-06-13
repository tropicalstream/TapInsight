using System;
using System.Collections;
using System.IO;
using UnityEngine;
using UnityEngine.Networking;

/// <summary>
/// Downloads a GLB file from a direct URL and caches it on the glasses'
/// persistent storage so repeat views are instant and offline-friendly.
///
/// GLB ONLY — not USDZ. USDZ can't be loaded at runtime on Android; convert
/// to GLB on a computer first (see gallery.json). The cache key is a hash of
/// the URL so different models never collide.
/// </summary>
public class ModelDownloader : MonoBehaviour
{
    string CacheDir => Path.Combine(Application.persistentDataPath, "models");

    /// <summary>Returns the local file path of the GLB, downloading if needed.</summary>
    public IEnumerator GetOrDownload(string url, Action<string> onReady, Action<string> onError)
    {
        Directory.CreateDirectory(CacheDir);
        string local = Path.Combine(CacheDir, SafeName(url));

        if (File.Exists(local) && new FileInfo(local).Length > 0)
        {
            onReady?.Invoke(local);
            yield break;
        }

        using (var req = UnityWebRequest.Get(url))
        {
            // stream straight to disk so a big model never balloons RAM (4 GB device).
            req.downloadHandler = new DownloadHandlerFile(local) { removeFileOnAbort = true };
            req.timeout = 60;
            yield return req.SendWebRequest();

            if (req.result != UnityWebRequest.Result.Success)
            {
                if (File.Exists(local)) File.Delete(local);
                onError?.Invoke($"Download failed: {req.error}");
                yield break;
            }
            onReady?.Invoke(local);
        }
    }

    static string SafeName(string url)
    {
        // stable filename from the URL hash + .glb
        unchecked
        {
            int h = 23;
            foreach (char c in url) h = h * 31 + c;
            return $"m_{(uint)h:x8}.glb";
        }
    }
}
