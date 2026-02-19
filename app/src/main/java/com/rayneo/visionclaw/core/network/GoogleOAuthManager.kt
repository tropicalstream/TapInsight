package com.rayneo.visionclaw.core.network

import android.util.Log
import com.rayneo.visionclaw.core.storage.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manages the Google OAuth2 token lifecycle:
 *   • Exchange authorization code → access_token + refresh_token
 *   • Auto-refresh expired access tokens
 *   • Provide a valid access token on demand
 */
class GoogleOAuthManager(
    private val prefs: AppPreferences,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "GoogleOAuth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        /** Refresh 5 minutes before actual expiry to avoid race conditions. */
        private const val EXPIRY_BUFFER_MS = 5 * 60 * 1000L
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Exchange an authorization code for an access token + refresh token.
     * Called by CompanionServer when the OAuth callback arrives.
     *
     * @param code        The authorization code from Google.
     * @param redirectUri The exact redirect URI used in the authorization request.
     * @return true on success, false on failure.
     */
    suspend fun exchangeCodeForTokens(code: String, redirectUri: String): Boolean =
        withContext(Dispatchers.IO) {
            val clientId = prefs.googleOAuthClientId
            val clientSecret = prefs.googleOAuthClientSecret

            if (clientId.isBlank()) {
                Log.e(TAG, "OAuth client ID is missing")
                return@withContext false
            }

            val formBody = FormBody.Builder()
                .add("code", code)
                .add("client_id", clientId)
                .add("redirect_uri", redirectUri)
                .add("grant_type", "authorization_code")

            // Client secret is required for "Web application" type clients
            if (clientSecret.isNotBlank()) {
                formBody.add("client_secret", clientSecret)
            }

            val request = Request.Builder()
                .url(TOKEN_ENDPOINT)
                .post(formBody.build())
                .build()

            try {
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                Log.d(TAG, "Token exchange response: ${response.code}")

                if (!response.isSuccessful) {
                    Log.e(TAG, "Token exchange failed (${response.code}): $body")
                    return@withContext false
                }

                val json = JSONObject(body)
                val accessToken = json.optString("access_token", "")
                val refreshToken = json.optString("refresh_token", "")
                val expiresIn = json.optLong("expires_in", 3600)

                if (accessToken.isBlank()) {
                    Log.e(TAG, "No access_token in response")
                    return@withContext false
                }

                prefs.googleOAuthAccessToken = accessToken
                if (refreshToken.isNotBlank()) {
                    prefs.googleOAuthRefreshToken = refreshToken
                }
                prefs.googleOAuthTokenExpiryMs =
                    System.currentTimeMillis() + (expiresIn * 1000)

                Log.i(TAG, "OAuth tokens stored (expires in ${expiresIn}s)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Token exchange error", e)
                false
            }
        }

    /**
     * Returns a valid access token, refreshing if necessary.
     * Returns null if no tokens exist or refresh fails.
     */
    suspend fun getValidAccessToken(): String? = withContext(Dispatchers.IO) {
        val accessToken = prefs.googleOAuthAccessToken
        if (accessToken.isBlank()) return@withContext null

        // Token still valid?
        if (System.currentTimeMillis() < prefs.googleOAuthTokenExpiryMs - EXPIRY_BUFFER_MS) {
            return@withContext accessToken
        }

        // Try to refresh
        Log.d(TAG, "Access token expired, attempting refresh")
        if (refreshAccessToken()) {
            prefs.googleOAuthAccessToken
        } else {
            null
        }
    }

    /**
     * Refresh the access token using the stored refresh token.
     * @return true on success.
     */
    suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = prefs.googleOAuthRefreshToken
        val clientId = prefs.googleOAuthClientId
        val clientSecret = prefs.googleOAuthClientSecret

        if (refreshToken.isBlank() || clientId.isBlank()) {
            Log.w(TAG, "Cannot refresh: missing refresh_token or client_id")
            return@withContext false
        }

        val formBody = FormBody.Builder()
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")

        if (clientSecret.isNotBlank()) {
            formBody.add("client_secret", clientSecret)
        }

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(formBody.build())
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Token refresh failed (${response.code}): $body")
                // If refresh token is invalid/revoked, clear everything
                if (response.code == 400 || response.code == 401) {
                    val lower = body.lowercase()
                    if (lower.contains("invalid_grant") || lower.contains("token has been revoked")) {
                        Log.w(TAG, "Refresh token revoked — clearing OAuth tokens")
                        prefs.clearGoogleOAuthTokens()
                    }
                }
                return@withContext false
            }

            val json = JSONObject(body)
            val newAccessToken = json.optString("access_token", "")
            val expiresIn = json.optLong("expires_in", 3600)

            if (newAccessToken.isBlank()) {
                Log.e(TAG, "No access_token in refresh response")
                return@withContext false
            }

            prefs.googleOAuthAccessToken = newAccessToken
            prefs.googleOAuthTokenExpiryMs =
                System.currentTimeMillis() + (expiresIn * 1000)

            // Google may issue a new refresh token
            val newRefreshToken = json.optString("refresh_token", "")
            if (newRefreshToken.isNotBlank()) {
                prefs.googleOAuthRefreshToken = newRefreshToken
            }

            Log.i(TAG, "Access token refreshed (expires in ${expiresIn}s)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error", e)
            false
        }
    }
}
