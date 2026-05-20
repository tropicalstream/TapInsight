package com.TapLink.app.unipanel

/**
 * Unipanel v2 Phase 3 — cross-module API surface for the voice
 * foreground Service.
 *
 * Why this interface exists: the Service implementation lives in
 * the visionclaw `app` module (because that's where MainViewModel
 * and the Gemini Live client live), but tapbrowser is a library
 * the `app` module depends on, so tapbrowser can't reference any
 * visionclaw class at compile time. The Binder this Service returns
 * needs to expose a callable API to tapbrowser without that import.
 *
 * Solution: declare the API as a Kotlin interface in this module.
 * The Service's `LocalBinder` (in visionclaw) implements it. Tapbrowser
 * does `bindService(...)` with an Intent constructed by package + class
 * name string, then casts the returned `IBinder` to [VoiceServiceApi]
 * to call its methods.
 *
 * Both Activities run in the same process, so this is a plain in-process
 * call — no AIDL, no parcelization, no IPC overhead.
 *
 * Phase 3 contract:
 *   - [activateVoice] and [shutdownVoice] are STUBS that only update
 *     [HudStateBridge]. They don't yet drive the AudioRecord / Gemini
 *     Live pipeline; that wires in Phase 4.
 *   - [currentState] returns the current HudStateBridge snapshot,
 *     useful for tapbrowser to seed its overlay UI at bind time.
 *
 * Phase 4 will expand this interface with anything else the migrated
 * pipeline needs to expose (e.g. camera-frame sink callback registration).
 */
interface VoiceServiceApi {

    /**
     * Begin a voice session — Phase 3 stub publishes a LISTENING
     * state to [HudStateBridge] so we can verify the bind path
     * end-to-end via logcat. Phase 4 will spin up AudioRecord, open
     * the WebSocket, and feed audio frames.
     *
     * Idempotent: calling while already active is a no-op (or in
     * Phase 4, resets the idle watchdog).
     */
    fun activateVoice()

    /**
     * End the current voice session — Phase 3 stub restores IDLE
     * state. Phase 4 will close the WebSocket, stop AudioRecord,
     * release effects, and drop the FGS notification.
     *
     * Idempotent.
     */
    fun shutdownVoice()

    /**
     * Snapshot of the HUD state the Service has published. Convenience
     * for callers that just bound and want to render the current
     * state immediately without waiting for the next [HudStateBridge]
     * publish.
     */
    fun currentState(): HudStateBridge.State

    /**
     * Phase 4d — toggle CameraX streaming. When ON, the Service binds
     * an ImageAnalysis use case and streams frames into the active
     * Gemini Live session via `sendImageChunkBase64`. When OFF, the
     * camera is unbound and released. Idempotent.
     *
     * The Service is foreground-promoted before opening the camera
     * (Android 14+ requires foregroundServiceType="camera" + the
     * matching runtime permission). State is mirrored via
     * [CameraStateBridge] so the tapbrowser CAM chip + pill reflect
     * the on/off state without polling.
     */
    fun toggleCamera()

    /** True when CameraX is currently streaming. */
    fun isCameraOn(): Boolean

    companion object {
        /**
         * Fully-qualified class name of the Service that implements
         * this interface. Tapbrowser uses it with
         * `Intent.setClassName(packageName, SERVICE_FQN)` to bind
         * without importing the visionclaw class (module dependency
         * runs visionclaw → tapbrowser, not the other way).
         *
         * The string MUST match
         * `com.rayneo.visionclaw.core.session.GeminiSessionForegroundService.FQN`
         * — kept in sync manually.
         */
        const val SERVICE_FQN: String =
            "com.rayneo.visionclaw.core.session.GeminiSessionForegroundService"
    }
}
