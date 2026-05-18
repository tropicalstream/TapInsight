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
