package com.rayneo.visionclaw.ui.panels.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.Preview
import androidx.fragment.app.Fragment
import com.rayneo.visionclaw.ui.MainViewModel
import com.rayneo.visionclaw.ui.panels.TrackpadPanel

/**
 * Phase 2 final (unipanel sweep) — headless ChatPanelFragment.
 *
 * The visible chat UI has been migrated entirely into the tapbrowser
 * overlay (driven by the three `com.TapLink.app.unipanel.*Bridge`
 * singletons). visionclaw is now a background host for the
 * `MainViewModel`, audio capture pipeline, Gemini Live session, and
 * the bridges; the user never sees its UI in the foreground.
 *
 * This fragment used to render the chat card carousel, HUD strip,
 * camera PIP, reader-mode overlay, dark mode, oscilloscope, etc.
 * That code has been removed. The class is preserved as a
 * compile-shim so the existing `MainActivity` call sites continue
 * to compile without churn:
 *
 *  - Every public method that `MainActivity.kt` invokes is kept with
 *    its original signature and reduced to a no-op (or a safe
 *    default return for query methods).
 *  - The nested types `ConnectionStatus`, `OpenClawGatewayStatus`,
 *    `FocusedTapResult`, `CoreEyeSurfaceListener`,
 *    `CardActionListener`, and `DarkModeListener` are preserved
 *    verbatim because `MainActivity` constructs anonymous objects
 *    against them.
 *  - `onCreateView` inflates an invisible 0x0 [FrameLayout] so the
 *    `Fragment` lifecycle still completes cleanly even though
 *    `MainActivity` never actually displays this fragment.
 *
 * The `MainPagerAdapter` and sibling Web/Settings panel fragments were
 * deleted in a follow-up sweep. The `view_pager` ID in
 * `activity_main.xml` still resolves to a 0×0 GONE `ViewPager2` stub
 * (with no adapter), since `MainActivity` still calls `currentItem` /
 * `setCurrentItem` against it; those calls are safe no-ops. This
 * fragment is no longer attached anywhere — it is instantiated by
 * `MainActivity` only because the headless `chatFragment` field type
 * is referenced from several call sites. The file can be deleted once
 * those direct references are also removed.
 */
class ChatPanelFragment : Fragment(), TrackpadPanel {

    // ── Nested types kept for the headless contract surface ────────────
    // These are referenced by MainActivity via `ChatPanelFragment.*`
    // qualified names — removing or renaming them would cascade into
    // recompiles in MainActivity, which is explicitly out of scope.

    interface CoreEyeSurfaceListener {
        fun onSurfaceAvailable()
        fun onSurfaceDestroyed()
    }

    interface CardActionListener {
        fun onAssistantRequested()
    }

    interface DarkModeListener {
        fun onDarkModeChanged(enabled: Boolean)
    }

    enum class FocusedTapResult {
        OPENED_URL,
        ACTIVATE_ASSISTANT,
        IGNORED
    }

    enum class ConnectionStatus {
        IDLE,
        CONNECTING,
        GEMINI_CONNECTED,
        TOOLS_READY,
        ERROR
    }

    enum class OpenClawGatewayStatus {
        HIDDEN,
        GOOD,
        BAD
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Invisible 0×0 stub. The visionclaw Activity is never user-
        // visible after the unipanel migration, so the fragment doesn't
        // need to draw anything. We still return a real View so the
        // Fragment manager's lifecycle (onViewCreated, onDestroyView,
        // etc.) executes normally.
        val stub = FrameLayout(inflater.context)
        stub.layoutParams = ViewGroup.LayoutParams(0, 0)
        stub.visibility = View.GONE
        return stub
    }

    // ══════════════════════════════════════════════════════════════════
    // No-op call surface (preserved signatures)
    // ══════════════════════════════════════════════════════════════════
    //
    // Every public function below is referenced from
    // `MainActivity.kt`. The signature must match exactly. The body
    // is either empty (for sinks) or returns a safe default (for
    // query methods). Bridges in `com.TapLink.app.unipanel` are the
    // authoritative destinations now — most of these calls have
    // direct equivalents there (or were redundant with `pushHudState-
    // ToChatFragment`'s `HudStateBridge.publish` call).

    // ── HUD mode toggles ──────────────────────────────────────────────
    fun isHudModeEnabled(): Boolean = false
    fun setHudModeEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        // no-op — the unipanel HUD strip is always rendered by
        // tapbrowser; there is no "HUD vs cards" toggle to honour.
    }

    fun isBatterySavingDarkMode(): Boolean = false

    // ── Reader mode ───────────────────────────────────────────────────
    fun isReaderModeActive(): Boolean = false
    fun exitReaderModeFromOutside() {
        // no-op — there is no reader overlay to dismiss.
    }

    // ── Card focus & navigation ───────────────────────────────────────
    fun focusNewChatCard(@Suppress("UNUSED_PARAMETER") animate: Boolean = true) {
        // no-op — card focus lives in tapbrowser's unipanel overlay,
        // driven by ChatCardBridge.
    }

    fun focusCardMatchingSnapshot(
        @Suppress("UNUSED_PARAMETER") text: String,
        @Suppress("UNUSED_PARAMETER") timestampMs: Long,
        @Suppress("UNUSED_PARAMETER") animate: Boolean = true
    ): Boolean = false

    fun handleFocusedCardTap(): FocusedTapResult = FocusedTapResult.IGNORED

    fun prepareForAssistantLaunch() {
        // no-op — no transient UI state to reset.
    }

    fun autoFocusLatestAssistantUrl() {
        // no-op — auto-focus happens in the unipanel overlay.
    }

    fun getFocusedCardLinkEntries(): List<CardUrlExtractor.Entry> = emptyList()

    // ── Heartbeat ticker ──────────────────────────────────────────────
    fun showHeartbeat(
        @Suppress("UNUSED_PARAMETER") message: String?,
        @Suppress("UNUSED_PARAMETER") displayMs: Long = 6_000L,
        @Suppress("UNUSED_PARAMETER") scroll: Boolean = displayMs > 0L
    ) {
        // no-op — heartbeat ticker has been retired in the unipanel UI.
    }

    fun hideHeartbeat() {
        // no-op.
    }

    fun clearHeartbeat() {
        // no-op.
    }

    // ── Status indicators ─────────────────────────────────────────────
    fun setOpenClawGatewayStatus(@Suppress("UNUSED_PARAMETER") status: OpenClawGatewayStatus) {
        // no-op — gateway status is no longer surfaced via this fragment.
    }

    fun setHermesGatewayStatus(@Suppress("UNUSED_PARAMETER") status: OpenClawGatewayStatus) {
        // no-op.
    }

    fun setTapClawResultReadyStatus(@Suppress("UNUSED_PARAMETER") visible: Boolean) {
        // no-op.
    }

    fun setStreamActiveIndicator(@Suppress("UNUSED_PARAMETER") active: Boolean) {
        // no-op — streaming activity is conveyed via the unipanel chat
        // card stream itself; there is no separate indicator UI.
    }

    fun setConnectionStatus(@Suppress("UNUSED_PARAMETER") status: ConnectionStatus) {
        // no-op — connection state is observed by tapbrowser through
        // the bridges as it changes.
    }

    fun setResearchReadyStatus(@Suppress("UNUSED_PARAMETER") visible: Boolean) {
        // no-op — historical badge; never re-added to the new UI.
    }

    // ── HUD snapshot ──────────────────────────────────────────────────
    //
    // The caller in MainActivity (`pushHudStateToChatFragment`) ALSO
    // publishes the same data into `HudStateBridge` immediately after
    // this call, so this method is effectively duplicate work for the
    // headless host. We keep the signature to avoid touching the
    // caller, but do nothing in the body — the bridge already carries
    // the snapshot to tapbrowser.
    fun syncHudSnapshot(
        @Suppress("UNUSED_PARAMETER") calendarSummary: String,
        @Suppress("UNUSED_PARAMETER") tasksSummary: String,
        @Suppress("UNUSED_PARAMETER") newsSummary: String,
        @Suppress("UNUSED_PARAMETER") airQualityState: MainViewModel.AirQualityHudState?,
        @Suppress("UNUSED_PARAMETER") radioState: MainViewModel.RadioHudState? = null
    ) {
        // no-op — see comment above; HudStateBridge.publish is the
        // active path.
    }

    // ── Listeners ─────────────────────────────────────────────────────
    //
    // The headless host never fires these callbacks (there's no
    // surface to detect a tap on, no real camera preview view, no
    // dark mode swipe). We accept the listeners for API parity and
    // simply hold them as fields so MainActivity can register / clear
    // them as it does today. Nothing observes the fields.

    @Suppress("unused")
    private var coreEyeSurfaceListener: CoreEyeSurfaceListener? = null
    @Suppress("unused")
    private var cardActionListener: CardActionListener? = null
    @Suppress("unused")
    private var darkModeListener: DarkModeListener? = null

    fun setCoreEyeSurfaceListener(listener: CoreEyeSurfaceListener?) {
        coreEyeSurfaceListener = listener
    }

    fun setCardActionListener(listener: CardActionListener?) {
        cardActionListener = listener
    }

    fun setDarkModeListener(listener: DarkModeListener?) {
        darkModeListener = listener
    }

    // ── CoreEye camera PIP ────────────────────────────────────────────
    //
    // The chat-side camera preview is gone. We return false / null so
    // MainActivity's gating logic (`coreEyeSurfaceReady && chatFragment
    // .isCoreEyeSurfaceReady()`) routes around any surface-dependent
    // path. The frame-capture pipeline in `core/` is independent of
    // this preview and continues to run via `MainActivity.startCamera-
    // Capture()` / `FrameCaptureManager`.

    fun isCoreEyeSurfaceReady(): Boolean = false

    fun getCoreEyeSurfaceProvider(): Preview.SurfaceProvider? = null

    fun setCoreEyeCaptureEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        // no-op.
    }

    fun onCoreEyeFrameStreamed() {
        // no-op.
    }

    fun setDarkModeCameraActive(@Suppress("UNUSED_PARAMETER") active: Boolean) {
        // no-op.
    }

    fun refreshDarkModeBattery() {
        // no-op — dark mode overlay is gone.
    }

    // ── Voice-activity orb ────────────────────────────────────────────
    fun pushVoiceOscilloscope(
        @Suppress("UNUSED_PARAMETER") level: Float,
        @Suppress("UNUSED_PARAMETER") color: Int
    ) {
        // no-op — voice level is mirrored to tapbrowser via the
        // existing CameraStateBridge / equivalent surfaces.
    }

    fun hideVoiceOscilloscope() {
        // no-op.
    }

    // ══════════════════════════════════════════════════════════════════
    // TrackpadPanel — required by the interface, never fires in headless mode
    // ══════════════════════════════════════════════════════════════════

    override fun onTrackpadScroll(deltaY: Float): Boolean = false

    override fun onTextInputFromHold(text: String): Boolean = false

    override fun onHeadYaw(yawDegrees: Float) {
        // no-op — no view to translate.
    }

    override fun getReadableText(): String = ""
}
