package com.TapLink.app.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.WebView
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Thread-safe bridge for capturing the on-glasses browser WebView's
 * current viewport as a JPEG (returned as base64).
 *
 * The visionclaw module's ToolDispatcher lives in a separate Activity
 * but the same process; it can't hold a direct WebView reference
 * because the WebView is owned by tapbrowser's MainActivity and may
 * be destroyed/recreated on configuration changes. This holder keeps
 * a [WeakReference] to whichever WebView is currently active so:
 *
 *   • tapbrowser MainActivity.onCreate sets the WebView.
 *   • tapbrowser MainActivity.onDestroy clears it.
 *   • Any caller (typically [BrowserVisionTool] from the visionclaw
 *     side) gets `null` cleanly when the browser isn't up.
 *
 * Capture mechanics:
 *
 *   • [captureBase64Jpeg] can be called from any thread. It hops to
 *     the UI thread via [Handler], runs [View.draw] into a fresh
 *     [Bitmap], hops back, encodes off-thread (well, on the caller's
 *     thread), and returns the base64 string.
 *
 *   • Uses a [CountDownLatch] with a 1.5 s timeout so callers don't
 *     block indefinitely if the UI thread is wedged.
 *
 *   • [View.draw] is enough for plain HTML pages on hardware-
 *     accelerated WebViews. WebGL/canvas-heavy pages may paint blank;
 *     that's a known limitation we can address later via PixelCopy
 *     or a software-render fallback if it shows up in practice.
 *
 * Why a singleton vs an injected service: keeps the wiring stupid
 * simple — tapbrowser doesn't have to expose a binder, doesn't need
 * a ContentProvider, and the app module just consumes a static
 * function reference as a lambda.
 */
object BrowserFrameHolder {

    private const val TAG = "BrowserFrameHolder"
    /** Cap thumbnail size so /generateContent payloads stay small. */
    private const val MAX_DIM_PX = 1280
    /** JPEG quality. Browser screenshots are mostly text — 75 is
     *  plenty without ballooning the base64 payload. */
    private const val JPEG_QUALITY = 75
    /** Hard timeout for the UI-thread roundtrip. */
    private const val CAPTURE_TIMEOUT_MS = 1500L

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var webViewRef: WeakReference<WebView>? = null

    /** tapbrowser MainActivity calls this in onCreate / after the
     *  WebView is added to its container. Replaces any prior ref. */
    @JvmStatic
    fun attach(webView: WebView) {
        webViewRef = WeakReference(webView)
        Log.d(TAG, "WebView attached: ${webView.javaClass.simpleName}")
    }

    /** tapbrowser MainActivity.onDestroy clears the reference so we
     *  don't keep a stale view alive (the WeakReference would clear
     *  itself eventually, but explicit is cleaner). */
    @JvmStatic
    fun detach(webView: WebView) {
        val current = webViewRef?.get()
        if (current === webView || current == null) {
            webViewRef = null
            Log.d(TAG, "WebView detached")
        }
    }

    /** Quick query for tools — returns true when a capture is at
     *  least theoretically possible. Doesn't actually capture. */
    @JvmStatic
    fun hasWebView(): Boolean = webViewRef?.get() != null

    /**
     * Phase 4c (codex diagnostic) — surfaces the current WebView's
     * dimensions plus whether it's attached. Cheap; safe to call
     * from any thread, doesn't actually draw anything.
     *
     *   • [hasWebView] — true if the holder still has a live ref.
     *   • [width] / [height] — pixel size. -1 if no WebView attached.
     *
     * Useful for "before the capture even runs, did we have anything
     * to capture" debugging when browser_vision returns blank frames.
     */
    data class CaptureStats(
        val hasWebView: Boolean,
        val width: Int,
        val height: Int
    )

    @JvmStatic
    fun captureStats(): CaptureStats {
        val wv = webViewRef?.get() ?: return CaptureStats(false, -1, -1)
        // Width/height read from any thread is best-effort but usually
        // accurate enough for a one-line log; we're not making layout
        // decisions off this.
        return CaptureStats(true, wv.width, wv.height)
    }

    /**
     * Phase 4c (codex diagnostic) — same as [captureBase64Jpeg] but
     * also returns a quick non-black pixel count and the bitmap's
     * dimensions so callers can prove the screenshot wasn't blank
     * before sending it across the wire to Gemini.
     *
     * The non-black pixel count samples every Nth pixel (stride
     * scaled to keep the work bounded under ~1024 samples regardless
     * of image size) and counts pixels whose RGB max channel exceeds
     * a small threshold. A near-zero count after a successful capture
     * indicates either a black WebView (page not loaded) or — much
     * more likely on hardware-accelerated WebViews — that View.draw
     * produced an empty bitmap, which is the known View.draw failure
     * mode this method exists to detect.
     *
     * Returns null with the same semantics as [captureBase64Jpeg] —
     * no WebView, zero size, or capture timeout.
     */
    data class CaptureResult(
        val base64: String,
        val width: Int,
        val height: Int,
        val nonBlackSamples: Int,
        val sampledPixels: Int
    )

    @JvmStatic
    fun captureBase64JpegWithStats(): CaptureResult? {
        val webView = webViewRef?.get() ?: run {
            Log.d(TAG, "captureBase64JpegWithStats: no WebView attached")
            return null
        }
        val resultBitmap = arrayOfNulls<Bitmap>(1)
        val latch = CountDownLatch(1)
        val runCapture = Runnable {
            try {
                val w = webView.width
                val h = webView.height
                if (w <= 0 || h <= 0) {
                    Log.w(TAG, "captureBase64JpegWithStats: WebView 0-size ($w×$h)")
                    return@Runnable
                }
                val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(full)
                webView.draw(canvas)
                resultBitmap[0] = downscaleIfNeeded(full, MAX_DIM_PX)
                if (resultBitmap[0] !== full) full.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "captureBase64JpegWithStats: draw failed: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCapture.run()
        } else {
            mainHandler.post(runCapture)
            try {
                if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "captureBase64JpegWithStats: timed out")
                    return null
                }
            } catch (_: InterruptedException) {
                Log.w(TAG, "captureBase64JpegWithStats: interrupted")
                return null
            }
        }
        val bmp = resultBitmap[0] ?: return null
        return try {
            val (nonBlack, sampled) = countNonBlackPixels(bmp)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            CaptureResult(
                base64 = base64,
                width = bmp.width,
                height = bmp.height,
                nonBlackSamples = nonBlack,
                sampledPixels = sampled
            )
        } catch (e: Exception) {
            Log.w(TAG, "captureBase64JpegWithStats: encode failed: ${e.message}")
            null
        } finally {
            bmp.recycle()
        }
    }

    /** Sample-counts non-black pixels in [bmp]. Keeps total samples
     *  bounded so a giant viewport doesn't trigger a megabyte-pixel
     *  scan. Threshold of 24 on the max channel skips JPEG-encode
     *  noise but counts real content pixels. */
    private fun countNonBlackPixels(bmp: Bitmap): Pair<Int, Int> {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return 0 to 0
        val targetSamples = 1024
        val totalPx = w.toLong() * h.toLong()
        val stride = maxOf(1, (totalPx / targetSamples).toInt())
        var nonBlack = 0
        var sampled = 0
        var i = 0
        while (i < totalPx) {
            val x = (i % w).toInt()
            val y = (i / w).toInt()
            if (y >= h) break
            val px = bmp.getPixel(x, y)
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            if (maxOf(r, maxOf(g, b)) > 24) nonBlack++
            sampled++
            i += stride
        }
        return nonBlack to sampled
    }

    /**
     * Returns the WebView's current viewport as a base64 JPEG, or
     * null if there's no attached WebView, the view has 0 size, or
     * the draw timed out.
     *
     * Safe to call from any thread. Synchronously blocks the caller
     * up to [CAPTURE_TIMEOUT_MS].
     */
    @JvmStatic
    fun captureBase64Jpeg(): String? {
        val webView = webViewRef?.get() ?: run {
            Log.d(TAG, "captureBase64Jpeg: no WebView attached")
            return null
        }
        // Synchronously capture on the UI thread. We allocate the
        // bitmap inside the UI block because View.width/height are
        // only safe to read there.
        val resultHolder = arrayOfNulls<Bitmap>(1)
        val latch = CountDownLatch(1)
        val runCapture = Runnable {
            try {
                val w = webView.width
                val h = webView.height
                if (w <= 0 || h <= 0) {
                    Log.w(TAG, "captureBase64Jpeg: WebView has zero size ($w×$h)")
                    return@Runnable
                }
                // Allocate at native size first; downscale after to
                // keep the on-screen aspect ratio exact.
                val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(full)
                webView.draw(canvas)
                resultHolder[0] = downscaleIfNeeded(full, MAX_DIM_PX)
                if (resultHolder[0] !== full) full.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "captureBase64Jpeg: draw failed: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCapture.run()
        } else {
            mainHandler.post(runCapture)
            try {
                if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "captureBase64Jpeg: timed out after ${CAPTURE_TIMEOUT_MS}ms")
                    return null
                }
            } catch (_: InterruptedException) {
                Log.w(TAG, "captureBase64Jpeg: interrupted")
                return null
            }
        }
        val bitmap = resultHolder[0] ?: return null
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            val bytes = out.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "captureBase64Jpeg: encode failed: ${e.message}")
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun downscaleIfNeeded(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxDim) return src
        val scale = maxDim.toFloat() / longest
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }
}
