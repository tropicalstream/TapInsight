package com.TapLink.app.unipanel

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 2 final — cross-Activity bridge for the unipanel HUD strip.
 *
 * The compact HUD over the browser shows the same situational
 * awareness the legacy Hermes-branch chat-side HUD did (date,
 * battery, AQI, upcoming calendar event, top news headline), but
 * collapsed into three single-line pills so the user can keep the
 * webpage readable underneath.
 *
 * visionclaw's MainActivity already aggregates this state from the
 * ViewModel for pushHudStateToChatFragment; we hook in there and
 * mirror the snapshot through this bridge so tapbrowser can render
 * a compact version without depending on visionclaw's package
 * layout (same pattern as [ChatCardBridge] and [CameraStateBridge]).
 *
 * Threading: state writes are atomic. Listeners fire on the
 * publisher's thread; UI consumers hop to main themselves.
 */
object HudStateBridge {

    /**
     * Snapshot of every HUD field the compact overlay can render.
     * All fields are nullable / blank-string when not applicable
     * so the renderer can decide row-by-row whether to show a pill.
     */
    data class Snapshot(
        /** Wall-clock label, e.g. "09:09". Driven from the clock
         *  ticker in tapbrowser itself, NOT from visionclaw — kept
         *  in the snapshot so future field-driven sources can
         *  override it (timezone, 24h vs 12h preference, etc.). */
        val time: String = "",
        /** Compact date label, e.g. "Tue May 19" or blank. */
        val date: String = "",
        /** Battery percent, 0..100, or null when unknown. */
        val batteryPct: Int? = null,
        /** True while the device is charging. */
        val batteryCharging: Boolean = false,
        /** Compact AQI label, e.g. "AQI 45" or blank when off. */
        val aqi: String = "",
        /** Single-line calendar summary, e.g. "No upcoming events"
         *  or "3pm — Standup". Blank hides the calendar pill. */
        val calendar: String = "",
        /** Single-line news headline, e.g. "NYT — Middle East on
         *  Edge". Blank hides the news pill. */
        val news: String = "",
        /** Connection / agent status text, e.g. "OpenClaw connected".
         *  Blank hides the status pill. */
        val status: String = ""
    ) {
        /** True iff at least one field beyond `time` is populated.
         *  The clock ticker is independent so the overlay still
         *  renders a clock even when the bridge has nothing else. */
        fun hasContent(): Boolean =
            date.isNotBlank() || batteryPct != null || aqi.isNotBlank() ||
                calendar.isNotBlank() || news.isNotBlank() || status.isNotBlank()
    }

    private val state = AtomicReference(Snapshot())
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    /**
     * Replace the published HUD snapshot with [snapshot]. Triggers
     * every registered listener synchronously on the calling thread
     * when the value actually changes; duplicate publishes are
     * dropped so the renderer doesn't churn on every poll cycle.
     */
    fun publish(snapshot: Snapshot) {
        val previous = state.getAndSet(snapshot)
        if (previous == snapshot) return
        for (l in listeners) {
            try {
                l(snapshot)
            } catch (_: Throwable) {
                // Swallow — a misbehaving listener must not break
                // the publisher or other listeners.
            }
        }
    }

    /** Snapshot of the most recently published HUD state. */
    fun current(): Snapshot = state.get()

    /**
     * Subscribe to publishes. The supplied [listener] fires once
     * synchronously with the current snapshot, then on every future
     * change. Returns an [AutoCloseable] for unsubscribe.
     */
    fun observe(listener: (Snapshot) -> Unit): AutoCloseable {
        listeners.add(listener)
        try {
            listener(state.get())
        } catch (_: Throwable) {
            // ditto.
        }
        return AutoCloseable { listeners.remove(listener) }
    }
}
