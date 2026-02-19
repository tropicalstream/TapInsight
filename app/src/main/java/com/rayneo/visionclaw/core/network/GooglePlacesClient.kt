package com.rayneo.visionclaw.core.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * GooglePlacesClient – finds nearby businesses via Google Places API (New).
 *
 * Uses the Nearby Search endpoint:
 *   POST https://places.googleapis.com/v1/places:searchNearby
 *
 * Requires a Google Maps API key with the Places API (New) enabled.
 * Reuses the same key as Directions / Maps.
 */
class GooglePlacesClient(private val apiKeyProvider: () -> String?) {

    companion object {
        private const val TAG = "GooglePlaces"
        private const val NEARBY_URL =
            "https://places.googleapis.com/v1/places:searchNearby"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000

        /** Fields returned — Basic + Advanced tier. */
        private const val FIELD_MASK =
            "places.displayName,places.formattedAddress,places.rating," +
                "places.userRatingCount,places.currentOpeningHours," +
                "places.businessStatus,places.types,places.shortFormattedAddress"
    }

    // ── Result types ─────────────────────────────────────────────────────

    sealed class PlacesResult {
        data class Success(val places: List<NearbyPlace>) : PlacesResult()
        data class Error(val message: String, val code: Int = -1) : PlacesResult()
        object ApiKeyMissing : PlacesResult()
    }

    data class NearbyPlace(
        val name: String,
        val address: String,
        val shortAddress: String,
        val rating: Double?,
        val ratingCount: Int?,
        val isOpen: Boolean?,
        val businessStatus: String?,
        val types: List<String>
    )

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Search for nearby places by type.
     *
     * @param latitude      Device latitude.
     * @param longitude     Device longitude.
     * @param types         Place types (e.g. ["restaurant"], ["cafe", "bakery"]).
     * @param radiusMeters  Search radius in meters (default 1500, max 50000).
     * @param maxResults    Maximum results to return (1-20, default 5).
     */
    suspend fun searchNearby(
        latitude: Double,
        longitude: Double,
        types: List<String>,
        radiusMeters: Double = 1500.0,
        maxResults: Int = 5
    ): PlacesResult = withContext(Dispatchers.IO) {

        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Google Maps API key is missing")
            return@withContext PlacesResult.ApiKeyMissing
        }

        try {
            // Build request JSON
            val requestBody = JSONObject().apply {
                put("includedTypes", JSONArray(types))
                put("maxResultCount", maxResults.coerceIn(1, 20))
                put("locationRestriction", JSONObject().apply {
                    put("circle", JSONObject().apply {
                        put("center", JSONObject().apply {
                            put("latitude", latitude)
                            put("longitude", longitude)
                        })
                        put("radius", radiusMeters.coerceIn(1.0, 50000.0))
                    })
                })
            }

            val conn = (URL(NEARBY_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", apiKey)
                setRequestProperty("X-Goog-FieldMask", FIELD_MASK)
                doOutput = true
            }

            // Write POST body
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestBody.toString())
            }

            val responseCode = conn.responseCode
            val body = BufferedReader(
                InputStreamReader(
                    if (responseCode in 200..299) conn.inputStream
                    else (conn.errorStream ?: conn.inputStream),
                    Charsets.UTF_8
                )
            ).use { it.readText() }

            if (responseCode == 403 || responseCode == 401) {
                Log.e(TAG, "Places API auth error ($responseCode): $body")
                return@withContext PlacesResult.Error(
                    "Places API key invalid or Places API (New) not enabled in GCP.", responseCode
                )
            }

            if (responseCode !in 200..299) {
                Log.e(TAG, "Places HTTP $responseCode: $body")
                val errorMsg = try {
                    val errJson = JSONObject(body)
                    errJson.optJSONObject("error")?.optString("message")
                        ?: "Places API error ($responseCode)"
                } catch (_: Exception) {
                    "Places API error ($responseCode)"
                }
                return@withContext PlacesResult.Error(errorMsg, responseCode)
            }

            val json = JSONObject(body)
            val placesArray = json.optJSONArray("places")

            if (placesArray == null || placesArray.length() == 0) {
                Log.d(TAG, "No places found near ($latitude, $longitude) for types=$types")
                return@withContext PlacesResult.Success(emptyList())
            }

            val places = (0 until placesArray.length()).map { i ->
                val place = placesArray.getJSONObject(i)
                val displayName = place.optJSONObject("displayName")
                    ?.optString("text", "Unknown") ?: "Unknown"
                val address = place.optString("formattedAddress", "")
                val shortAddr = place.optString("shortFormattedAddress", address)
                val rating = if (place.has("rating")) place.optDouble("rating") else null
                val ratingCount = if (place.has("userRatingCount"))
                    place.optInt("userRatingCount") else null

                // Opening hours — check currentOpeningHours.openNow
                val openNow = place.optJSONObject("currentOpeningHours")
                    ?.let { if (it.has("openNow")) it.optBoolean("openNow") else null }

                val businessStatus = place.optString("businessStatus", null)

                val typesArr = place.optJSONArray("types")
                val typesList = if (typesArr != null) {
                    (0 until typesArr.length()).map { j -> typesArr.getString(j) }
                } else emptyList()

                NearbyPlace(
                    name = displayName,
                    address = address,
                    shortAddress = shortAddr,
                    rating = rating,
                    ratingCount = ratingCount,
                    isOpen = openNow,
                    businessStatus = businessStatus,
                    types = typesList
                )
            }

            Log.d(TAG, "Found ${places.size} places near ($latitude, $longitude)")
            PlacesResult.Success(places)
        } catch (e: Exception) {
            Log.e(TAG, "Places request failed", e)
            PlacesResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }
}
