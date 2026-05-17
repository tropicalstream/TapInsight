package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Base64
import android.util.Log
import com.TapLink.app.media.MediaLibraryService
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gemini-callable camera tool. Currently implements `save_photo`:
 * captures the most recent camera frame (delivered as base64 JPEG via
 * [frameProvider], populated by the Gemini Live multimodal pipeline)
 * and writes it to the on-device Media Library under `Media/Photos/`.
 *
 * After the file lands on disk, [MediaScannerConnection.scanFile] is
 * triggered so the RayNeo native gallery + any other MediaStore-aware
 * apps discover the new photo on next launch.
 *
 * The success response includes an `open_taplink:` directive pointing
 * at the on-glasses photos gallery so the user can say "view it" and
 * the result is rendered immediately. Older flows that returned just
 * the success string still work — the gallery hint is on its own line.
 */
class CameraTool(
    private val context: Context,
    private val frameProvider: () -> String? = { null }
) : AiTapTool {

    override val name = "camera_action"

    companion object {
        private const val TAG = "CameraTool"
        private const val GALLERY_URL =
            "https://appassets.androidplatform.net/assets/photos_gallery.html"
        private val FILENAME_TIMESTAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"] ?: "save_photo"
        val title = args["title"]?.trim()?.takeIf { it.isNotBlank() } ?: "TapInsight Photo"

        Log.d(TAG, "action=$action title=$title")

        return when (action) {
            "save_photo" -> savePhoto(title)
            "read_qr" -> Result.success("QR scan not yet implemented.")
            "start_recording" -> Result.success("Recording started: $title")
            "stop_recording" -> Result.success("Recording saved.")
            else -> Result.success("Unknown camera action: $action")
        }
    }

    private fun savePhoto(title: String): Result<String> {
        val base64 = frameProvider()?.trim().orEmpty()
        if (base64.isEmpty()) {
            Log.w(TAG, "save_photo: no camera frame available")
            return Result.failure(
                IllegalStateException(
                    "Camera frame not available. Make sure the AR camera is active before saving a photo."
                )
            )
        }

        val jpegBytes = try {
            Base64.decode(base64, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "save_photo: base64 decode failed", e)
            return Result.failure(IOException("Failed to decode camera frame: ${e.localizedMessage}"))
        }

        if (jpegBytes.isEmpty()) {
            return Result.failure(IOException("Camera frame decoded to 0 bytes."))
        }

        val library = MediaLibraryService(context).apply { ensureBootstrap() }
        val photosDir = File(library.mediaRoot, MediaLibraryService.DEFAULT_PHOTOS_DIR)
        if (!photosDir.exists()) photosDir.mkdirs()

        val timestamp = FILENAME_TIMESTAMP.format(Date())
        val safeTitle = title.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim()
            .replace(Regex("\\s+"), "_")
            .ifBlank { "photo" }
        val filename = "$timestamp-$safeTitle.jpg"
        val outFile = File(photosDir, filename)

        try {
            outFile.outputStream().use { it.write(jpegBytes) }
        } catch (e: Exception) {
            Log.e(TAG, "save_photo: write failed for ${outFile.path}", e)
            return Result.failure(IOException("Failed to write photo: ${e.localizedMessage}"))
        }

        // Best-effort MediaStore registration so the native RayNeo
        // gallery + any other MediaStore client sees the new image.
        // Failure here is non-fatal — the file is still on disk and
        // visible in the TapInsight Media Library.
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(outFile.absolutePath),
                arrayOf("image/jpeg"),
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "MediaScanner trigger failed (non-fatal): ${e.message}")
        }

        val libraryRelativePath = "${MediaLibraryService.DEFAULT_PHOTOS_DIR}/$filename"
        Log.i(TAG, "save_photo: wrote ${outFile.length()} bytes to $libraryRelativePath")

        // Include an open_taplink: directive on its own line so that
        // GeminiRouter's tool-result handler can pick it up. When the
        // user follows with "view it" or "show me", the launcher opens
        // the photos gallery directly to the just-saved image (the
        // gallery page reads ?focus=<path> and scrolls to it).
        val focusParam = java.net.URLEncoder.encode(libraryRelativePath, "UTF-8")
        val galleryDeepLink = "$GALLERY_URL?focus=$focusParam"
        return Result.success(
            "Photo saved to the on-glasses Photos library as $filename. " +
                "Say \"view it\" or \"open the gallery\" and I'll show you.\n" +
                "open_taplink:$galleryDeepLink"
        )
    }
}
