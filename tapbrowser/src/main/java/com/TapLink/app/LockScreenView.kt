package com.TapLinkX3.app

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.CountDownTimer
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Swipe-unlock boot screen for the TapInsight launcher (tapbrowser).
 *
 * Purely a visual deterrent — no encryption. The expected code is a 3–6
 * entry sequence of directional swipes (Up/Down/Left/Right). This view owns
 * the presentation (animated directional cues, progress pips, jiggle-on-fail,
 * cooldown) and the audio (a classical boot flourish on appear, a soft tick
 * per swipe). It does NOT capture input itself — MainActivity intercepts
 * trackpad gestures while locked and feeds them in via [submitSwipe], so the
 * glasses' cursor/gesture stack never sees them.
 *
 * Sized to fill the DualWebViewGroup's logical 640×480 viewport; because it
 * lives inside that group, BinocularSbsLayout mirrors it to both eyes.
 */
class LockScreenView(context: Context) : FrameLayout(context) {

    companion object {
        private const val GLYPH_UP = "▲"    // ▲
        private const val GLYPH_DOWN = "▼"  // ▼
        private const val GLYPH_LEFT = "◀"  // ◀
        private const val GLYPH_RIGHT = "▶" // ▶
        private const val COOLDOWN_SECONDS = 5L
        private const val ACCENT = 0xFF00E5FF.toInt()
        private const val WARN = 0xFFFF5252.toInt()
        private const val OK = 0xFF69F0AE.toInt()
        private const val DIM = 0xFF37474F.toInt()

        fun glyphFor(dir: Char): String = when (dir) {
            'U' -> GLYPH_UP
            'D' -> GLYPH_DOWN
            'L' -> GLYPH_LEFT
            'R' -> GLYPH_RIGHT
            else -> "•"
        }
    }

    private val content: LinearLayout
    private val titleText: TextView
    private val arrowText: TextView
    private val pipRow: LinearLayout
    private val statusText: TextView

    private var expected: List<Char> = emptyList()
    private val entered = ArrayList<Char>()
    private var attemptLimit = 3
    private var wrongAttempts = 0
    private var inCooldown = false
    private var unlocked = false
    private var onUnlocked: (() -> Unit)? = null

    private var cooldownTimer: CountDownTimer? = null

    // ── Audio ────────────────────────────────────────────────────────────
    private val soundPool: SoundPool =
        SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    private var swipeSoundId = 0
    private var swipeSoundLoaded = false
    private var bootPlayer: MediaPlayer? = null

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
        elevation = 3000f // above the fullscreen-video overlay (2000f)

        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        addView(
            content,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            }
        )

        titleText = TextView(context).apply {
            text = "LOCKED"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 26f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
        }
        content.addView(titleText)

        arrowText = TextView(context).apply {
            text = ""
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 96f)
            gravity = Gravity.CENTER
            alpha = 0f
        }
        content.addView(
            arrowText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                140
            ).apply { topMargin = 8; bottomMargin = 8 }
        )

        pipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        content.addView(pipRow)

        statusText = TextView(context).apply {
            text = "Swipe your code to unlock"
            setTextColor(0xFFB0BEC5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f)
            gravity = Gravity.CENTER
        }
        content.addView(
            statusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 18 }
        )
    }

    /**
     * Configure the lock with the expected [sequence] (3–6 of U/D/L/R) and the
     * number of wrong tries allowed before a brief cooldown.
     */
    fun bind(sequence: List<Char>, attemptLimit: Int, onUnlocked: () -> Unit) {
        this.expected = sequence
        this.attemptLimit = attemptLimit.coerceAtLeast(1)
        this.onUnlocked = onUnlocked
        entered.clear()
        wrongAttempts = 0
        rebuildPips()
        updateStatus("Swipe your code to unlock")
    }

    /** Call once the view is attached/visible: play the boot flourish + fade in. */
    fun onShown() {
        if (swipeSoundLoaded.not() && swipeSoundId == 0) {
            soundPool.setOnLoadCompleteListener { _, sampleId, status ->
                if (sampleId == swipeSoundId && status == 0) swipeSoundLoaded = true
            }
            swipeSoundId = soundPool.load(context, R.raw.swipe_tick, 1)
        }
        playBootChime()
        content.alpha = 0f
        content.animate().alpha(1f).setDuration(450).start()
    }

    private fun playBootChime() {
        runCatching {
            bootPlayer?.release()
            bootPlayer = MediaPlayer.create(context, R.raw.boot_chime)?.apply {
                setOnCompletionListener { mp ->
                    runCatching { mp.release() }
                    if (bootPlayer === mp) bootPlayer = null
                }
                start()
            }
        }
    }

    private fun playSwipeTick() {
        if (swipeSoundLoaded && swipeSoundId != 0) {
            runCatching { soundPool.play(swipeSoundId, 0.9f, 0.9f, 1, 0, 1f) }
        }
    }

    /**
     * Feed a single directional swipe. [dir] is one of 'U','D','L','R'.
     * Ignored while in cooldown or already unlocked.
     */
    fun submitSwipe(dir: Char) {
        if (unlocked || inCooldown) return
        if (dir != 'U' && dir != 'D' && dir != 'L' && dir != 'R') return
        if (expected.isEmpty()) return

        playSwipeTick()
        flickArrow(dir)
        entered.add(dir)
        fillNextPip(entered.size)

        // Validate only once a full-length code has been entered. We don't
        // reveal per-swipe correctness — it's a deterrent, not a keypad.
        if (entered.size >= expected.size) {
            if (entered == expected) onSuccess() else onFailure()
        }
    }

    private fun onSuccess() {
        unlocked = true
        updateStatus("Unlocked", OK)
        titleText.setTextColor(OK)
        for (i in 0 until pipRow.childCount) pipRow.getChildAt(i).setBackgroundColor(OK)
        content.animate()
            .scaleX(1.06f).scaleY(1.06f).alpha(0f)
            .setDuration(420)
            .withEndAction { onUnlocked?.invoke() }
            .start()
    }

    private fun onFailure() {
        entered.clear()
        wrongAttempts++
        jiggle()
        if (wrongAttempts >= attemptLimit) {
            startCooldown()
        } else {
            rebuildPips(WARN)
            updateStatus("Wrong code — try again", WARN)
            postDelayed({ if (!inCooldown && !unlocked) { rebuildPips(); updateStatus("Swipe your code to unlock") } }, 900)
        }
    }

    private fun startCooldown() {
        inCooldown = true
        rebuildPips(DIM)
        titleText.setTextColor(WARN)
        cooldownTimer?.cancel()
        cooldownTimer = object : CountDownTimer(COOLDOWN_SECONDS * 1000L, 1000L) {
            override fun onTick(msLeft: Long) {
                val s = (msLeft / 1000L) + 1
                updateStatus("Too many tries — wait ${s}s", WARN)
            }

            override fun onFinish() {
                inCooldown = false
                wrongAttempts = 0
                titleText.setTextColor(Color.WHITE)
                rebuildPips()
                updateStatus("Swipe your code to unlock")
            }
        }.start()
    }

    // ── Visuals ────────────────────────────────────────────────────────────
    private fun flickArrow(dir: Char) {
        arrowText.text = glyphFor(dir)
        arrowText.setTextColor(ACCENT)
        arrowText.alpha = 1f
        arrowText.translationX = 0f
        arrowText.translationY = 0f
        val dist = 60f
        val (dx, dy) = when (dir) {
            'U' -> 0f to -dist
            'D' -> 0f to dist
            'L' -> -dist to 0f
            else -> dist to 0f
        }
        arrowText.animate()
            .translationX(dx).translationY(dy).alpha(0f)
            .setDuration(360)
            .start()
    }

    private fun jiggle() {
        ObjectAnimator.ofFloat(
            content, "translationX",
            0f, -22f, 20f, -16f, 14f, -8f, 6f, 0f
        ).apply {
            duration = 420
            start()
        }
    }

    private fun rebuildPips(color: Int = DIM) {
        pipRow.removeAllViews()
        val count = expected.size.coerceAtLeast(1)
        for (i in 0 until count) {
            val pip = View(context).apply {
                setBackgroundColor(color)
                alpha = 0.85f
            }
            pipRow.addView(
                pip,
                LinearLayout.LayoutParams(18, 18).apply { leftMargin = 7; rightMargin = 7 }
            )
        }
    }

    private fun fillNextPip(index1Based: Int) {
        val i = index1Based - 1
        if (i in 0 until pipRow.childCount) {
            pipRow.getChildAt(i).setBackgroundColor(ACCENT)
        }
    }

    private fun updateStatus(text: String, color: Int = 0xFFB0BEC5.toInt()) {
        statusText.text = text
        statusText.setTextColor(color)
    }

    /** Release audio resources. Call from the host's hide/teardown path. */
    fun release() {
        cooldownTimer?.cancel()
        cooldownTimer = null
        runCatching { bootPlayer?.release() }
        bootPlayer = null
        runCatching { soundPool.release() }
    }
}
