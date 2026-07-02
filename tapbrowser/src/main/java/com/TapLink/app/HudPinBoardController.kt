package com.TapLinkX3.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.TapLink.app.unipanel.HudPinStore
import com.TapLink.app.unipanel.HudPinStore.HudPin
import java.net.URL
import java.util.Locale

/**
 * HUD pin board — renders the user's pinned "hud posts" (icons /
 * post-it notes / pictures, see [HudPinStore]) into the empty HUD
 * zone: below the clock/battery strip, right of the battery icon's
 * left edge, above the HUD bottom line (the Gemini-activation-zone
 * bottom, so the two surfaces always agree on where the HUD ends).
 *
 * Interaction model (glasses trackpad):
 *   • Tap a pin → open it (icon → its URL, note → Google Tasks,
 *     picture → fullscreen viewer; tap again to dismiss).
 *   • RIGHT-ARM LONG-PRESS with the cursor over a pin → "hud modify"
 *     mode: the pin highlights and grows an ✕ (delete) chip and a ✥
 *     (move) chip. Tap ✥ → the pin follows the cursor; tap again to
 *     drop it (position persists). Tap ✕ → delete.
 *   • Moving the pointer OFF the pin (while not carrying it) cancels
 *     modify mode — mirrors Mars's spec exactly.
 *
 * All pin views are clickable=true so the existing three-state
 * unipanel hit-test (commit #280) routes cursor taps to them and the
 * EMPTY pin-zone space keeps its tap-to-activate-Gemini behaviour
 * (findUnipanelHit gets first refusal; misses fall through).
 *
 * Threading: HudPinStore listeners fire on the mutating thread (a
 * voice-tool coroutine when Gemini pins something) — every mutation
 * hops to main via [uiHandler] before touching views.
 */
class HudPinBoardController(
    private val activity: Activity,
    private val board: FrameLayout,
    private val uiHandler: Handler,
    private val openUrl: (String) -> Unit,
    private val forceCursorVisible: () -> Unit,
    private val showToast: (String) -> Unit
) {

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private var subscription: AutoCloseable? = null
    private val pinViews = LinkedHashMap<String, FrameLayout>() // pin id → container
    private var pinsSnapshot: List<HudPin> = emptyList()

    // hud-modify state
    private var modifyPinId: String? = null
    private var carrying = false
    private var fullscreenView: FrameLayout? = null

    private val bitmapCache = LruCache<String, Bitmap>(8)

    fun start() {
        HudPinStore.init(activity)
        subscription?.runCatching { close() }
        subscription = HudPinStore.observe { pins ->
            uiHandler.post { render(pins) }
        }
    }

    fun stop() {
        subscription?.runCatching { close() }
        subscription = null
    }

    /** Re-slot the grid after HUD geometry changes (tier rows, heartbeat). */
    fun refreshZone() {
        if (pinViews.isEmpty() && pinsSnapshot.isEmpty()) return
        render(pinsSnapshot)
    }

    // ------------------------------------------------------------------
    // Zone geometry
    // ------------------------------------------------------------------

    /** Pin zone in BOARD-local px (board is match_parent in the overlay). */
    private data class Zone(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun boardLocalX(view: View): Int {
        val a = IntArray(2); val b = IntArray(2)
        view.getLocationOnScreen(a); board.getLocationOnScreen(b)
        return a[0] - b[0]
    }

    private fun boardLocalY(view: View): Int {
        val a = IntArray(2); val b = IntArray(2)
        view.getLocationOnScreen(a); board.getLocationOnScreen(b)
        return a[1] - b[1]
    }

    private fun computeZone(): Zone {
        val battery = activity.findViewById<View?>(R.id.unipanelHudBattery)
        val topRow = activity.findViewById<View?>(R.id.unipanelTopHudRow)
        val tierPanel = activity.findViewById<View?>(R.id.unipanelHudTierPanel)
        val heartbeat = activity.findViewById<View?>(R.id.unipanelHudHeartbeatText)

        val left = if (battery != null && battery.width > 0) {
            boardLocalX(battery)
        } else dp(150)

        val top = if (topRow != null && topRow.height > 0) {
            boardLocalY(topRow) + topRow.height + dp(4)
        } else dp(42)

        val right = if (tierPanel != null && tierPanel.width > 0 && tierPanel.height > 0) {
            boardLocalX(tierPanel) - dp(6)
        } else {
            (board.width.takeIf { it > 0 } ?: dp(632)) - dp(8)
        }

        // HUD bottom — same candidate set as isUnipanelGeminiActivationZone
        // so the pin board never claims space the HUD doesn't own.
        var bottom = dp(112)
        listOf(topRow, heartbeat, tierPanel).forEach { v ->
            if (v != null && v.visibility == View.VISIBLE && v.height > 0) {
                bottom = maxOf(bottom, boardLocalY(v) + v.height + dp(8))
            }
        }
        return Zone(left, top, maxOf(right, left + dp(60)), bottom)
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun render(pins: List<HudPin>) {
        pinsSnapshot = pins
        exitModifyMode()
        board.removeAllViews()
        pinViews.clear()
        if (pins.isEmpty()) return
        if (board.width <= 0) {
            // pre-layout — retry once the overlay has real bounds
            board.post { if (board.width > 0) render(pinsSnapshot) }
            return
        }

        val zone = computeZone()
        val gap = dp(6)
        var x = zone.left
        var y = zone.top
        var rowH = 0

        for (pin in pins) {
            val container = buildPinView(pin)
            val w = container.layoutParams.width
            val h = container.layoutParams.height
            val lp = container.layoutParams as FrameLayout.LayoutParams
            if (pin.customX >= 0 && pin.customY >= 0) {
                lp.leftMargin = pin.customX.coerceIn(0, maxOf(0, board.width - w))
                lp.topMargin = pin.customY.coerceIn(0, maxOf(0, board.height - h))
            } else {
                if (x + w > zone.right && x > zone.left) {
                    x = zone.left
                    y += rowH + gap
                    rowH = 0
                }
                lp.leftMargin = x
                lp.topMargin = y
                x += w + gap
                rowH = maxOf(rowH, h)
            }
            container.layoutParams = lp
            board.addView(container)
            pinViews[pin.id] = container
        }
    }

    /** Container FrameLayout: content + (hidden until modify) ✕ / ✥ chips. */
    private fun buildPinView(pin: HudPin): FrameLayout {
        val container = FrameLayout(activity)
        val (w, h) = when (pin.type) {
            HudPinStore.TYPE_NOTE -> dp(92) to dp(64)
            HudPinStore.TYPE_PICTURE -> dp(64) to dp(48)
            else -> dp(54) to dp(46)
        }
        container.layoutParams = FrameLayout.LayoutParams(w, h)
        container.elevation = 6f * density
        container.isClickable = true
        container.isFocusable = true
        container.tag = pin.id

        val content: View = when (pin.type) {
            HudPinStore.TYPE_NOTE -> buildNoteContent(pin)
            HudPinStore.TYPE_PICTURE -> buildPictureContent(pin)
            else -> buildIconContent(pin)
        }
        content.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        // taps must land on the CONTAINER (the hit-test walks descendants
        // in reverse; a clickable child would steal the tap from it).
        content.isClickable = false
        content.isFocusable = false
        container.addView(content)

        container.setOnClickListener {
            when {
                carrying && pin.id == modifyPinId -> dropCarriedPin()
                modifyPinId != null -> exitModifyMode()
                else -> openPin(pin)
            }
        }
        return container
    }

    private fun buildIconContent(pin: HudPin): View {
        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL

        val pillSize = dp(26)
        val pill = TextView(activity)
        pill.layoutParams = LinearLayout.LayoutParams(pillSize, pillSize)
        pill.gravity = Gravity.CENTER
        pill.text = iconGlyph(pin)
        pill.setTextColor(Color.WHITE)
        pill.textSize = 12f
        pill.typeface = Typeface.DEFAULT_BOLD
        pill.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC13242F.toInt())
            setStroke(dp(1), 0xB3FFFFFF.toInt())
        }
        col.addView(pill)

        val label = TextView(activity)
        label.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) }
        label.text = pin.label.ifBlank { "link" }
        label.setTextColor(Color.WHITE)
        label.textSize = 8f
        label.maxLines = 1
        label.maxWidth = dp(54)
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        // outdoor readability: white with hard black shadow (07f6b91 lesson)
        label.setShadowLayer(2f * density, 0f, 1f, Color.BLACK)
        col.addView(label)
        return col
    }

    /** Glyph for the icon pill, keyed off the target URL's host. */
    private fun iconGlyph(pin: HudPin): String {
        val u = (pin.linkUrl ?: pin.payload).lowercase(Locale.US)
        return when {
            u.contains("youtube.com") || u.contains("youtu.be") -> "▶"
            u.contains("spotify") -> "♫"
            u.contains("radio") -> "♪"
            u.contains("tasks.google") -> "✓"
            u.contains("calendar.google") -> "▦"
            u.contains("news.google") -> "N"
            else -> pin.label.trim().take(1).uppercase(Locale.US).ifBlank { "•" }
        }
    }

    private fun buildNoteContent(pin: HudPin): View {
        val tv = TextView(activity)
        tv.text = pin.payload.ifBlank { pin.label }
        tv.setTextColor(0xFF1B1B10.toInt())
        tv.textSize = 9f
        tv.maxLines = 4
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        tv.setPadding(dp(6), dp(5), dp(6), dp(5))
        // post-it yellow, near-opaque so the dark text survives outdoors
        tv.background = GradientDrawable().apply {
            setColor(0xF2FFEE58.toInt())
            cornerRadius = 2f * density
        }
        return tv
    }

    private fun buildPictureContent(pin: HudPin): View {
        val iv = ImageView(activity)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.background = GradientDrawable().apply {
            setColor(0xFF10181E.toInt())
            setStroke(dp(1), 0xCCFFFFFF.toInt())
            cornerRadius = 2f * density
        }
        iv.setPadding(dp(1), dp(1), dp(1), dp(1))
        loadPinBitmap(pin) { bmp -> iv.setImageBitmap(bmp) }
        return iv
    }

    private fun openPin(pin: HudPin) {
        when (pin.type) {
            HudPinStore.TYPE_PICTURE -> showFullscreenPicture(pin)
            HudPinStore.TYPE_NOTE -> openUrl(pin.linkUrl ?: "https://tasks.google.com")
            else -> {
                val target = pin.linkUrl ?: pin.payload
                if (target.isNotBlank()) openUrl(target)
            }
        }
    }

    // ------------------------------------------------------------------
    // Fullscreen picture viewer (tap anywhere to dismiss)
    // ------------------------------------------------------------------

    private fun showFullscreenPicture(pin: HudPin) {
        dismissFullscreen()
        val overlayRoot = board.parent as? FrameLayout ?: return
        val frame = FrameLayout(activity)
        frame.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        frame.setBackgroundColor(0xE6000000.toInt())
        frame.elevation = 30f * density
        frame.isClickable = true
        frame.isFocusable = true

        val iv = ImageView(activity)
        iv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { setMargins(dp(24), dp(20), dp(24), dp(28)) }
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        frame.addView(iv)

        if (pin.label.isNotBlank()) {
            val caption = TextView(activity)
            caption.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(6) }
            caption.text = pin.label
            caption.setTextColor(Color.WHITE)
            caption.textSize = 11f
            caption.setShadowLayer(3f * density, 0f, 1f, Color.BLACK)
            frame.addView(caption)
        }

        frame.setOnClickListener { dismissFullscreen() }
        overlayRoot.addView(frame)
        fullscreenView = frame
        forceCursorVisible()
        loadPinBitmap(pin) { bmp -> iv.setImageBitmap(bmp) }
    }

    fun dismissFullscreen(): Boolean {
        val v = fullscreenView ?: return false
        (v.parent as? FrameLayout)?.removeView(v)
        fullscreenView = null
        return true
    }

    fun isFullscreenShowing(): Boolean = fullscreenView != null

    private fun loadPinBitmap(pin: HudPin, onReady: (Bitmap) -> Unit) {
        bitmapCache.get(pin.id)?.let { onReady(it); return }
        Thread {
            val bmp: Bitmap? = try {
                val src = pin.payload
                if (src.startsWith("http://") || src.startsWith("https://")) {
                    URL(src).openStream().use { BitmapFactory.decodeStream(it) }
                } else {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(src, opts)
                    val sample = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / 1280)
                    BitmapFactory.decodeFile(
                        src, BitmapFactory.Options().apply { inSampleSize = sample }
                    )
                }
            } catch (_: Exception) {
                null
            }
            if (bmp != null) {
                bitmapCache.put(pin.id, bmp)
                uiHandler.post { onReady(bmp) }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // Hud-modify mode (right-arm long-press)
    // ------------------------------------------------------------------

    /**
     * Right-arm long-press hook. Returns true when the press landed on
     * a pin and modify mode engaged (caller should consume the event).
     */
    fun onLongPressAt(screenX: Float, screenY: Float): Boolean {
        val hit = pinViews.entries.firstOrNull { (_, v) ->
            viewContains(v, screenX, screenY)
        } ?: return false
        enterModifyMode(hit.key)
        return true
    }

    private fun enterModifyMode(pinId: String) {
        if (modifyPinId != null && modifyPinId != pinId) exitModifyMode()
        val container = pinViews[pinId] ?: return
        modifyPinId = pinId
        carrying = false
        forceCursorVisible()
        container.scaleX = 1.08f
        container.scaleY = 1.08f
        container.elevation = 12f * density

        container.addView(buildChip("✕", 0xE6D32F2F.toInt(), Gravity.TOP or Gravity.END) {
            val id = modifyPinId ?: return@buildChip
            exitModifyMode()
            HudPinStore.remove(id)
            showToast("Pin removed")
        }.also { it.tag = CHIP_TAG })

        container.addView(buildChip("✥", 0xE60288D1.toInt(), Gravity.TOP or Gravity.START) {
            startCarrying()
        }.also { it.tag = CHIP_TAG })
        showToast("Pin: ✕ delete · ✥ move")
    }

    private fun buildChip(
        glyph: String,
        color: Int,
        gravity: Int,
        onTap: () -> Unit
    ): TextView {
        val chip = TextView(activity)
        val size = dp(20)
        chip.layoutParams = FrameLayout.LayoutParams(size, size, gravity)
        chip.gravity = Gravity.CENTER
        chip.text = glyph
        chip.setTextColor(Color.WHITE)
        chip.textSize = 10f
        chip.typeface = Typeface.DEFAULT_BOLD
        chip.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), Color.WHITE)
        }
        chip.elevation = 14f * density
        chip.isClickable = true
        chip.setOnClickListener { onTap() }
        return chip
    }

    private fun startCarrying() {
        val container = pinViews[modifyPinId] ?: return
        carrying = true
        // chips off while carrying — the next tap anywhere on the pin drops it
        removeChips(container)
        showToast("Move the pointer — tap to drop")
    }

    private fun dropCarriedPin() {
        val id = modifyPinId ?: return
        val container = pinViews[id] ?: return
        carrying = false
        val lp = container.layoutParams as FrameLayout.LayoutParams
        exitModifyMode()
        // persists + triggers re-render through the store observer
        HudPinStore.updatePosition(id, lp.leftMargin, lp.topMargin)
    }

    /**
     * Cursor-position feed from MainActivity.refreshCursor(). Carries
     * the pin while in move mode; cancels modify mode when the pointer
     * wanders off the pin (with 28dp slack) without carrying it.
     */
    fun onCursorMoved(screenX: Float, screenY: Float) {
        val id = modifyPinId ?: return
        val container = pinViews[id] ?: return
        if (carrying) {
            val boardLoc = IntArray(2)
            board.getLocationOnScreen(boardLoc)
            val w = container.width
            val h = container.height
            val lp = container.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = (screenX - boardLoc[0] - w / 2f).toInt()
                .coerceIn(0, maxOf(0, board.width - w))
            lp.topMargin = (screenY - boardLoc[1] - h / 2f).toInt()
                .coerceIn(0, maxOf(0, board.height - h))
            container.layoutParams = lp
        } else if (!viewContains(container, screenX, screenY, slackPx = dp(28))) {
            exitModifyMode()
        }
    }

    fun isInModifyMode(): Boolean = modifyPinId != null

    fun exitModifyMode() {
        val container = pinViews[modifyPinId] ?: run {
            modifyPinId = null; carrying = false; return
        }
        modifyPinId = null
        carrying = false
        container.scaleX = 1f
        container.scaleY = 1f
        container.elevation = 6f * density
        removeChips(container)
    }

    private fun removeChips(container: FrameLayout) {
        val chips = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filter { it.tag == CHIP_TAG }
        chips.forEach { container.removeView(it) }
    }

    private fun viewContains(v: View, screenX: Float, screenY: Float, slackPx: Int = 0): Boolean {
        if (v.visibility != View.VISIBLE || v.width == 0) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return screenX >= loc[0] - slackPx && screenX < loc[0] + v.width + slackPx &&
            screenY >= loc[1] - slackPx && screenY < loc[1] + v.height + slackPx
    }

    companion object {
        private const val CHIP_TAG = "hud_pin_chip"
    }
}
