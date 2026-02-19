@file:Suppress("unused")
package com.rayneo.visionclaw.core.network

/**
 * DEPRECATED: OpenClawClient has been replaced by AITap's native ToolDispatcher.
 * This file is kept as an empty stub to avoid build errors from stale references.
 * All tool execution now goes through [com.rayneo.visionclaw.core.tools.ToolDispatcher].
 */
class OpenClawClient(
    baseUrlProvider: () -> String = { "" },
    endpointProvider: () -> String = { "" },
    gatewayTokenProvider: () -> String = { "" },
    locationContextProvider: () -> DeviceLocationContext? = { null },
    timeoutMsProvider: () -> Int = { 15000 }
) {
    data class DeviceLocationContext(
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val accuracyMeters: Float? = null,
        val altitudeMeters: Double? = null,
        val speedMps: Float? = null,
        val bearingDeg: Float? = null,
        val provider: String? = null,
        val timestampMs: Long = 0L
    )

    data class ToolCall(
        val tool: String = "",
        val args: Map<String, String> = emptyMap()
    )

    data class ToolContextHint(
        val skill: String? = null,
        val intent: String? = null,
        val clientNowIso: String? = null,
        val clientTz: String? = null,
        val clientLat: Double? = null,
        val clientLng: Double? = null,
        val deviceId: String? = null
    )

    suspend fun ping(): Boolean = false

    suspend fun executeSkill(
        query: String,
        skill: String? = null,
        intent: String? = null,
        clientNowIso: String? = null,
        clientTz: String? = null,
        clientLat: Double? = null,
        clientLng: Double? = null,
        deviceId: String? = null
    ): Result<String> = Result.failure(UnsupportedOperationException("OpenClaw removed in AITap"))

    suspend fun forwardToolCall(toolCall: ToolCall): Result<String> =
        Result.failure(UnsupportedOperationException("OpenClaw removed in AITap"))
}
