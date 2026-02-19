package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.network.GoogleDirectionsClient

class GoogleRoutesTool(
    private val context: Context,
    private val directionsClient: GoogleDirectionsClient
) : AiTapTool {
    override val name = "google_routes"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val origin = args["origin"]?.takeIf { it.isNotBlank() } ?: "current location"
        val destination = args["destination"]?.takeIf { it.isNotBlank() }
            ?: return Result.failure(Exception("Destination is required for directions."))
        val mode = args["mode"]?.lowercase()
            ?.takeIf { it in listOf("driving", "walking", "transit", "bicycling") }
            ?: "driving"

        Log.d("GoogleRoutesTool", "origin=$origin dest=$destination mode=$mode")

        return when (val result = directionsClient.getDirections(origin, destination, mode)) {
            is GoogleDirectionsClient.DirectionsResult.Success -> {
                val trafficInfo = result.durationInTraffic?.let { " ($it with current traffic)" } ?: ""
                val stepsText = if (result.steps.size <= 5) {
                    result.steps.joinToString("\n") { "• $it" }
                } else {
                    result.steps.take(5).joinToString("\n") { "• $it" } + "\n• ... and ${result.steps.size - 5} more steps"
                }
                val summary = buildString {
                    append("${result.distance}, ${result.duration}$trafficInfo")
                    if (result.summary.isNotBlank()) append(" via ${result.summary}")
                    append("\nFrom: ${result.startAddress}")
                    append("\nTo: ${result.endAddress}")
                    if (stepsText.isNotBlank()) append("\n\nDirections:\n$stepsText")
                }
                Result.success(summary)
            }
            is GoogleDirectionsClient.DirectionsResult.ApiKeyMissing ->
                Result.failure(Exception("Google Maps API key not configured. Add it in TapInsight setup."))
            is GoogleDirectionsClient.DirectionsResult.Error ->
                Result.failure(Exception(result.message))
        }
    }
}
