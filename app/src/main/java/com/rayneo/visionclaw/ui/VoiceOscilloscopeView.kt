package com.rayneo.visionclaw.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * Wireframe oscilloscope tuned for HUD use:
 * - low-luminance rendering
 * - 2px stroke
 * - vsync animation via Choreographer
 */
class VoiceOscilloscopeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val strokePx = context.resources.displayMetrics.density * 2f

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = 0xFF1F1F1F.toInt()
    }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = 0xFFFF4D4D.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val wavePath = Path()
    private var targetLevel = 0f
    private var displayedLevel = 0f
    private var phase = 0f
    private var active = false
    private var framePosted = false
    private var centerCutoutRadiusPx = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePosted = false
            val delta = targetLevel - displayedLevel
            displayedLevel += delta * 0.28f
            targetLevel *= 0.86f
            if (!active && displayedLevel < 0.01f) {
                displayedLevel = 0f
                invalidate()
                return
            }
            phase += 0.24f
            invalidate()
            postFrame()
        }
    }

    fun pushLevel(amplitude: Float, color: Int) {
        targetLevel = amplitude.coerceIn(0f, 1f)
        wavePaint.color = color
        active = true
        postFrame()
    }

    fun stop() {
        active = false
        targetLevel = 0f
        postFrame()
    }

    fun setCenterCutoutRadiusDp(radiusDp: Float) {
        centerCutoutRadiusPx = (radiusDp * context.resources.displayMetrics.density).coerceAtLeast(0f)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postFrame()
    }

    override fun onDetachedFromWindow() {
        if (framePosted) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            framePosted = false
        }
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val centerX = w / 2f
        val centerY = h / 2f
        val cutoutHalf = centerCutoutRadiusPx + strokePx * 1.2f
        if (cutoutHalf > 0f) {
            val leftEnd = (centerX - cutoutHalf).coerceAtLeast(0f)
            val rightStart = (centerX + cutoutHalf).coerceAtMost(w)
            canvas.drawLine(0f, centerY, leftEnd, centerY, axisPaint)
            canvas.drawLine(rightStart, centerY, w, centerY, axisPaint)
        } else {
            canvas.drawLine(0f, centerY, w, centerY, axisPaint)
        }

        val amplitude = (h * 0.10f) + (h * 0.34f * displayedLevel)
        val steps = 64
        wavePath.reset()
        var pathStarted = false
        for (i in 0..steps) {
            val ratio = i / steps.toFloat()
            val x = w * ratio
            val insideCutout = cutoutHalf > 0f && abs(x - centerX) <= cutoutHalf
            if (insideCutout) {
                pathStarted = false
                continue
            }
            val y = centerY + (sin(phase + ratio * Math.PI * 2.2).toFloat() * amplitude)
            if (!pathStarted) {
                wavePath.moveTo(x, y)
                pathStarted = true
            } else {
                wavePath.lineTo(x, y)
            }
        }
        canvas.drawPath(wavePath, wavePaint)
    }

    private fun postFrame() {
        if (framePosted || !isAttachedToWindow) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }
}
