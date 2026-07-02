package com.TapLink.app.unipanel

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * HUD pin board — persistence + cross-module bridge for user-pinned
 * HUD content ("hud posts"): icons that open URLs, post-it notes that
 * link to Google Tasks, and pictures that open full screen.
 *
 * Written by BOTH modules in the same process:
 *   • visionclaw's HudPinTool (Gemini voice: "pin that to my HUD")
 *   • tapbrowser's HudPinBoardController (long-tap delete / move)
 * so it follows the ChatCardBridge singleton pattern: thread-safe
 * state, listeners fire on the mutating thread, UI consumers hop to
 * main themselves.
 *
 * Persistence: its OWN SharedPreferences file ("hud_pin_store"), NOT
 * visionclaw_prefs and NOT chat_context. Pins aren't companion-app
 * config and keeping them out of visionclaw_prefs avoids ever
 * colliding with CompanionServer's allowed_config_keys machinery
 * (lesson of the Oakland bug, commit 030d119: be deliberate about
 * which prefs file owns what).
 */
object HudPinStore {

    const val TYPE_ICON = "icon"
    const val TYPE_NOTE = "note"
    const val TYPE_PICTURE = "picture"

    /** Hard cap — the pin zone is small (~150×90dp usable). */
    const val MAX_PINS = 10

    private const val PREFS_FILE = "hud_pin_store"
    private const val KEY_PINS = "hud_pins"

    /**
     * One HUD post.
     *
     * [payload] meaning by [type]:
     *   icon    → the URL the icon opens in TapBrowser
     *   note    → the note body text
     *   picture → absolute file path (screen grabs saved by HudPinTool)
     *             or an http(s) image URL
     * [linkUrl]: optional tap-through override. Notes default to
     * Google Tasks; icons default to [payload]; pictures open the
     * fullscreen viewer and ignore it.
     * [customX]/[customY]: overlay-space position in px once the user
     * has manually moved the pin; -1 = auto-grid slot.
     */
    data class HudPin(
        val id: String = UUID.randomUUID().toString(),
        val type: String,
        val label: String,
        val payload: String,
        val linkUrl: String? = null,
        val customX: Int = -1,
        val customY: Int = -1,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("type", type)
            .put("label", label)
            .put("payload", payload)
            .put("linkUrl", linkUrl ?: JSONObject.NULL)
            .put("customX", customX)
            .put("customY", customY)
            .put("createdAt", createdAt)

        companion object {
            fun fromJson(o: JSONObject): HudPin? {
                val type = o.optString("type").takeIf { it.isNotBlank() } ?: return null
                return HudPin(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    type = type,
                    label = o.optString("label"),
                    payload = o.optString("payload"),
                    linkUrl = o.optString("linkUrl").takeIf {
                        it.isNotBlank() && it != "null"
                    },
                    customX = o.optInt("customX", -1),
                    customY = o.optInt("customY", -1),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            }
        }
    }

    @SuppressLint("StaticFieldLeak") // application context only
    @Volatile private var appContext: Context? = null
    private val lock = Any()
    @Volatile private var cache: List<HudPin>? = null
    private val listeners = CopyOnWriteArrayList<(List<HudPin>) -> Unit>()

    /**
     * Idempotent. Either module may call first (tapbrowser
     * MainActivity.onCreate in practice; HudPinTool defensively).
     * Always stores the application context, never an Activity.
     */
    fun init(context: Context) {
        if (appContext == null) {
            synchronized(lock) {
                if (appContext == null) appContext = context.applicationContext
            }
        }
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun all(): List<HudPin> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val loaded = load()
            cache = loaded
            return loaded
        }
    }

    /**
     * Adds [pin]; returns false when at [MAX_PINS] capacity.
     *
     * Dedupe: re-pinning the SAME target (same type + payload, e.g.
     * asking Gemini to pin the current station twice, or the same
     * station with fresher metadata) REPLACES the existing pin in
     * place — keeping its id and any manual position — instead of
     * stacking an identical twin on the board.
     */
    fun add(pin: HudPin): Boolean {
        synchronized(lock) {
            val current = all()
            val existingIdx = current.indexOfFirst {
                it.type == pin.type && it.payload == pin.payload
            }
            if (existingIdx >= 0) {
                val existing = current[existingIdx]
                val next = current.toMutableList()
                next[existingIdx] = pin.copy(
                    id = existing.id,
                    customX = existing.customX,
                    customY = existing.customY,
                    createdAt = existing.createdAt
                )
                persist(next)
            } else {
                if (current.size >= MAX_PINS) return false
                persist(current + pin)
            }
        }
        notifyListeners()
        return true
    }

    /** Remove by exact id. Returns true when something was removed. */
    fun remove(id: String): Boolean {
        val removed: Boolean
        synchronized(lock) {
            val current = all()
            val next = current.filterNot { it.id == id }
            removed = next.size != current.size
            if (removed) persist(next)
        }
        if (removed) notifyListeners()
        return removed
    }

    /**
     * Remove by fuzzy label ("delete the cat pin" shouldn't require
     * the exact stored label). Case-insensitive containment either
     * direction; falls back to matching against note body text.
     * Returns the removed pin's label, or null when nothing matched.
     */
    fun removeByLabel(query: String): String? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        var removedLabel: String? = null
        synchronized(lock) {
            val current = all()
            val victim = current.firstOrNull {
                val l = it.label.trim().lowercase()
                l == q || l.contains(q) || q.contains(l) && l.isNotEmpty()
            } ?: current.firstOrNull {
                it.type == TYPE_NOTE && it.payload.lowercase().contains(q)
            } ?: return null
            removedLabel = victim.label
            persist(current.filterNot { it.id == victim.id })
        }
        notifyListeners()
        return removedLabel
    }

    /** Persist a manual move (overlay-space px). */
    fun updatePosition(id: String, x: Int, y: Int) {
        synchronized(lock) {
            val current = all()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return
            val next = current.toMutableList()
            next[idx] = next[idx].copy(customX = x, customY = y)
            persist(next)
        }
        notifyListeners()
    }

    fun clear() {
        synchronized(lock) { persist(emptyList()) }
        notifyListeners()
    }

    /**
     * Subscribe; fires once synchronously with current state, then on
     * every mutation. Returns an [AutoCloseable] for lifecycle-tied
     * removal (same contract as ChatCardBridge.observe).
     */
    fun observe(listener: (List<HudPin>) -> Unit): AutoCloseable {
        listeners.add(listener)
        try {
            listener(all())
        } catch (_: Throwable) {
            // never let a buggy listener escape
        }
        return AutoCloseable { listeners.remove(listener) }
    }

    private fun notifyListeners() {
        val snapshot = all()
        for (l in listeners) {
            try {
                l(snapshot)
            } catch (_: Throwable) {
                // ditto
            }
        }
    }

    private fun persist(pins: List<HudPin>) {
        cache = pins
        val arr = JSONArray()
        pins.forEach { arr.put(it.toJson()) }
        prefs()?.edit()?.putString(KEY_PINS, arr.toString())?.apply()
    }

    private fun load(): List<HudPin> {
        val raw = prefs()?.getString(KEY_PINS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { HudPin.fromJson(it) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
