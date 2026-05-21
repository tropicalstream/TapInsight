package com.TapLink.app.unipanel

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-local bridge for one-shot browser commands issued by the
 * voice pipeline (visionclaw `app` module, which runs in the same
 * process) and consumed by the tapbrowser MainActivity that owns the
 * WebView. Mirrors the threading model of [HudStateBridge]: callbacks
 * fire synchronously on the publisher's thread, so UI consumers must
 * hop to the main thread themselves.
 *
 * Currently carries a single command — "toggle reader mode" — fired
 * when the user asks Gemini to render the current page in reader mode.
 * Kept deliberately tiny; add more commands as named methods if needed.
 */
object BrowserCommandBridge {

    fun interface Listener {
        fun onToggleReaderMode()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Subscribe. Returns an [AutoCloseable] for lifecycle-tied removal. */
    fun observe(listener: Listener): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    /**
     * Ask the browser to toggle reader mode on the currently-shown page.
     * Fires every registered listener; a misbehaving listener can never
     * break the publisher.
     */
    fun toggleReaderMode() {
        for (l in listeners) {
            try {
                l.onToggleReaderMode()
            } catch (_: Throwable) {
                // ignore — never let a consumer crash the publisher
            }
        }
    }
}
