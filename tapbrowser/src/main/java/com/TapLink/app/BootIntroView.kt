package com.TapLinkX3.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Binocular-safe boot intro. This view is hosted inside DualWebViewGroup's
 * lock overlay, so BinocularSbsLayout mirrors one logical scene to both lenses.
 */
class BootIntroView(context: Context) : View(context) {

    companion object {
        private const val INTRO_MS = 2800L
        private const val BG_TOP = 0xFF05070D.toInt()
        private const val BG_BOTTOM = 0xFF000000.toInt()
        private const val CYAN = 0xFF00E5FF.toInt()
        private const val GREEN = 0xFF69F0AE.toInt()
        private const val AMBER = 0xFFFFD166.toInt()
        private const val BLUE = 0xFF4C7DFF.toInt()
    }

    var onComplete: (() -> Unit)? = null

    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val bootSoundPool: SoundPool =
        SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    private var bootSoundId = 0
    private var bootSoundLoaded = false
    private var bootSoundRequested = false
    private var bootSoundPlayed = false
    private var completed = false
    private val finishRunnable = Runnable { fadeOutAndFinish() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val scanline = Paint().apply { color = 0x18000000 }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
    }

    fun start() {
        if (animator != null) return
        playBootChime()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = INTRO_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        removeCallbacks(finishRunnable)
        postDelayed(finishRunnable, INTRO_MS)
    }

    private fun fadeOutAndFinish() {
        animate()
            .alpha(0f)
            .setDuration(360L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { finish() }
            .start()
    }

    private fun finish() {
        if (completed) return
        completed = true
        animator?.cancel()
        animator = null
        onComplete?.invoke()
    }

    fun release() {
        animate().cancel()
        removeCallbacks(finishRunnable)
        animator?.cancel()
        animator = null
        runCatching { bootSoundPool.release() }
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun playBootChime() {
        if (bootSoundPlayed) return
        bootSoundRequested = true
        if (bootSoundId == 0) {
            bootSoundPool.setOnLoadCompleteListener { _, sampleId, status ->
                if (sampleId != bootSoundId) return@setOnLoadCompleteListener
                bootSoundLoaded = status == 0
                if (!bootSoundLoaded) {
                    Log.w("BootIntro", "boot_chime load failed status=$status")
                    return@setOnLoadCompleteListener
                }
                if (bootSoundRequested) playLoadedBootChime()
            }
            bootSoundId = bootSoundPool.load(context, R.raw.boot_chime, 1)
        }
        if (bootSoundLoaded) {
            playLoadedBootChime()
        } else {
            postDelayed({ if (bootSoundRequested && !bootSoundPlayed) playLoadedBootChime() }, 300L)
        }
    }

    private fun playLoadedBootChime() {
        if (bootSoundPlayed || bootSoundId == 0) return
        val streamId = runCatching {
            bootSoundPool.play(bootSoundId, 1f, 1f, 1, 0, 1f)
        }.getOrElse {
            Log.w("BootIntro", "boot_chime play failed", it)
            0
        }
        if (streamId != 0) {
            bootSoundPlayed = true
            Log.d("BootIntro", "boot_chime played")
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawBackground(canvas, w, h)

        val cx = w / 2f
        val cy = h * 0.46f
        val appear = ease(segment(progress, 0f, 0.28f))
        val sweep = ease(segment(progress, 0.16f, 0.82f))
        val settle = ease(segment(progress, 0.72f, 1f))
        val pulse = 0.5f + 0.5f * sin(progress * Math.PI.toFloat() * 5f)

        drawCenterSystem(canvas, cx, cy, min(w, h), appear, sweep, pulse)
        drawStatus(canvas, cx, h, appear, settle)
        drawScanlines(canvas, w, h)
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        fill.shader = LinearGradient(0f, 0f, 0f, h, BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        fill.shader = RadialGradient(
            w * 0.5f,
            h * 0.42f,
            min(w, h) * 0.5f,
            0x3300E5FF,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        stroke.color = 0x2200E5FF
        stroke.strokeWidth = 1f
        val grid = 36f
        var x = 0f
        while (x <= w) {
            canvas.drawLine(x, 0f, x, h, stroke)
            x += grid
        }
        var y = 0f
        while (y <= h) {
            canvas.drawLine(0f, y, w, y, stroke)
            y += grid
        }
    }

    private fun drawCenterSystem(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        appear: Float,
        sweep: Float,
        pulse: Float
    ) {
        val radius = size * 0.17f
        val ringAlpha = (appear * 210).toInt()

        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 2.5f
        stroke.color = withAlpha(CYAN, ringAlpha)
        canvas.drawCircle(cx, cy, radius * (0.95f + pulse * 0.04f), stroke)

        stroke.strokeWidth = 7f
        stroke.color = withAlpha(GREEN, ringAlpha)
        val arc = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arc, -90f, 360f * sweep, false, stroke)

        fill.color = withAlpha(0xFF06131B.toInt(), (appear * 235).toInt())
        canvas.drawCircle(cx, cy, radius * 0.62f, fill)

        text.color = withAlpha(Color.WHITE, (appear * 255).toInt())
        text.textSize = size * 0.07f
        canvas.drawText("TAPLINK", cx, cy - size * 0.015f, text)
        text.color = withAlpha(CYAN, (appear * 230).toInt())
        text.textSize = size * 0.038f
        canvas.drawText("X3", cx, cy + size * 0.055f, text)

        drawOrbitDot(canvas, cx, cy, radius * 1.38f, progress * 360f, CYAN, appear)
        drawOrbitDot(canvas, cx, cy, radius * 1.38f, progress * 360f + 180f, AMBER, appear)
    }

    private fun drawStatus(canvas: Canvas, cx: Float, h: Float, appear: Float, settle: Float) {
        val baseY = h * 0.72f
        drawChip(canvas, cx - 72f, baseY, "HERMES", GREEN, appear)
        drawChip(canvas, cx + 72f, baseY, "OPENCLAW", BLUE, appear)

        text.color = withAlpha(0xFFE0F7FF.toInt(), (appear * 230).toInt())
        text.textSize = 17f
        canvas.drawText("GLASSES ONLINE", cx, h * 0.82f, text)

        val barW = min(width * 0.62f, 340f)
        val barH = 8f
        val left = cx - barW / 2f
        val top = h * 0.875f
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 1.5f
        stroke.color = withAlpha(CYAN, (appear * 160).toInt())
        canvas.drawRoundRect(RectF(left, top, left + barW, top + barH), 4f, 4f, stroke)

        fill.color = withAlpha(GREEN, (appear * 235).toInt())
        canvas.drawRoundRect(
            RectF(left + 2f, top + 2f, left + 2f + (barW - 4f) * settle, top + barH - 2f),
            3f,
            3f,
            fill
        )
    }

    private fun drawChip(canvas: Canvas, cx: Float, cy: Float, label: String, color: Int, appear: Float) {
        val w = 108f
        val h = 30f
        val rect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        fill.color = withAlpha(0xFF071013.toInt(), (appear * 220).toInt())
        canvas.drawRoundRect(rect, 9f, 9f, fill)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 1.5f
        stroke.color = withAlpha(color, (appear * 220).toInt())
        canvas.drawRoundRect(rect, 9f, 9f, stroke)
        text.color = withAlpha(Color.WHITE, (appear * 240).toInt())
        text.textSize = 13f
        canvas.drawText(label, cx, cy + 5f, text)
    }

    private fun drawOrbitDot(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        degrees: Float,
        color: Int,
        appear: Float
    ) {
        val rads = Math.toRadians(degrees.toDouble())
        val x = cx + cos(rads).toFloat() * radius
        val y = cy + sin(rads).toFloat() * radius
        fill.color = withAlpha(color, (appear * 240).toInt())
        canvas.drawCircle(x, y, 5f, fill)
    }

    private fun drawScanlines(canvas: Canvas, w: Float, h: Float) {
        var y = 0f
        while (y < h) {
            canvas.drawLine(0f, y, w, y, scanline)
            y += 4f
        }
    }

    private fun segment(p: Float, start: Float, end: Float): Float =
        ((p - start) / (end - start)).coerceIn(0f, 1f)

    private fun ease(t: Float): Float = t * t * (3f - 2f * t)

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
