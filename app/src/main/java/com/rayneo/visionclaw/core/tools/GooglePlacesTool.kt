package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.network.GooglePlacesClient

/**
 * AiTapTool for Google Places — nearby business search.
 *
 * Finds restaurants, cafes, gas stations, pharmacies, etc. near the user's
 * current GPS location. Returns names, ratings, open/closed status.
 */
class GooglePlacesTool(
    private val context: Context,
    private val placesClient: GooglePlacesClient,
    private val locationProvider: () -> DeviceLocationContext?
) : AiTapTool {
    override val name = "google_places"

    companion object {
        private const val TAG = "GooglePlacesTool"
        private const val DEFAULT_RADIUS = 1500.0
        private const val MAX_RADIUS = 5000.0

        /** Map common natural-language aliases to Google Places types. */
        private val TYPE_ALIASES = mapOf(
            "coffee" to "cafe",
            "coffee shop" to "cafe",
            "food" to "restaurant",
            "eat" to "restaurant",
            "dining" to "restaurant",
            "gas" to "gas_station",
            "fuel" to "gas_station",
            "petrol" to "gas_station",
            "drugs" to "pharmacy",
            "drugstore" to "pharmacy",
            "medicine" to "pharmacy",
            "groceries" to "supermarket",
            "grocery" to "supermarket",
            "market" to "supermarket",
            "atm" to "bank",
            "money" to "bank",
            "hotel" to "lodging",
            "motel" to "lodging",
            "doctor" to "hospital",
            "urgent care" to "hospital",
            "er" to "hospital",
            "gym" to "gym",
            "fitness" to "gym",
            "drinks" to "bar",
            "pub" to "bar",
            "park" to "park",
            "parking" to "parking",
            "ev charging" to "electric_vehicle_charging_station",
            "charger" to "electric_vehicle_charging_station"
        )
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val rawType = args["type"]?.trim()?.lowercase()
        val query = args["query"]?.trim()
        val radiusStr = args["radius"]

        // Resolve place type
        val placeType = if (rawType.isNullOrBlank() && !query.isNullOrBlank()) {
            resolveType(query)
        } else if (!rawType.isNullOrBlank()) {
            resolveType(rawType)
        } else {
            return Result.failure(Exception("Please specify what type of place you're looking for (e.g. restaurant, cafe, gas station)."))
        }

        val radius = radiusStr?.toDoubleOrNull()?.coerceIn(100.0, MAX_RADIUS) ?: DEFAULT_RADIUS

        // Get GPS location
        val location = locationProvider()
        if (location == null) {
            Log.w(TAG, "No GPS location available")
            return Result.failure(Exception(
                "GPS location not available. Make sure location services are enabled on the glasses."
            ))
        }

        // Check if location is stale (older than 10 minutes)
        val ageMs = System.currentTimeMillis() - location.timestampMs
        if (ageMs > 10 * 60 * 1000) {
            Log.w(TAG, "GPS location is ${ageMs / 1000}s old")
        }

        Log.d(TAG, "Searching for '$placeType' near (${location.latitude}, ${location.longitude}), radius=$radius")

        return when (val result = placesClient.searchNearby(
            latitude = location.latitude,
            longitude = location.longitude,
            types = listOf(placeType),
            radiusMeters = radius,
            maxResults = 5
        )) {
            is GooglePlacesClient.PlacesResult.Success -> {
                if (result.places.isEmpty()) {
                    Result.success("No ${placeType.replace("_", " ")}s found within ${(radius / 1000).let { if (it >= 1) "${it.toInt()}km" else "${radius.toInt()}m" }} of your location.")
                } else {
                    val formatted = result.places.mapIndexed { index, place ->
                        formatPlace(index + 1, place)
                    }.joinToString("\n")
                    val radiusDesc = if (radius >= 1000) "${(radius / 1000).toInt()}km" else "${radius.toInt()}m"
                    Result.success("Found ${result.places.size} nearby (within $radiusDesc):\n$formatted")
                }
            }
            is GooglePlacesClient.PlacesResult.ApiKeyMissing ->
                Result.failure(Exception("Google Maps API key not configured. Add it in the TapInsight companion app."))
            is GooglePlacesClient.PlacesResult.Error ->
                Result.failure(Exception(result.message))
        }
    }

    /** Map user input to a valid Google Places type. */
    private fun resolveType(input: String): String {
        // Direct match to alias
        TYPE_ALIASES[input]?.let { return it }

        // Partial match — check if any alias key is contained in the input
        for ((alias, type) in TYPE_ALIASES) {
            if (input.contains(alias)) return type
        }

        // Already a valid Google type (has underscore or is a known type)
        if (input.contains("_") || input in setOf(
                "restaurant", "cafe", "bar", "bakery", "bank", "pharmacy",
                "hospital", "supermarket", "gas_station", "parking", "park",
                "gym", "lodging", "library", "laundry", "car_wash",
                "car_repair", "dentist", "veterinary_care"
            )) {
            return input
        }

        // Fallback — use as-is and let Google API handle it
        return input
    }

    /** Format a single place result for HUD display. */
    private fun formatPlace(index: Int, place: GooglePlacesClient.NearbyPlace): String {
        val parts = mutableListOf<String>()
        parts.add("$index. ${place.name}")

        // Rating
        if (place.rating != null) {
            val stars = "★${String.format("%.1f", place.rating)}"
            val count = place.ratingCount?.let { " ($it)" } ?: ""
            parts.add("$stars$count")
        }

        // Open/closed status
        when {
            place.isOpen == true -> parts.add("Open Now")
            place.isOpen == false -> parts.add("Closed")
            place.businessStatus == "CLOSED_TEMPORARILY" -> parts.add("Temporarily Closed")
            place.businessStatus == "CLOSED_PERMANENTLY" -> parts.add("Permanently Closed")
        }

        // Short address
        if (place.shortAddress.isNotBlank()) {
            parts.add(place.shortAddress)
        }

        return parts.joinToString(" — ")
    }
}
