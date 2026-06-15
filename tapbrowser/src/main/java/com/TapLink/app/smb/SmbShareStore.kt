package com.TapLink.app.smb

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One configured LAN SMB share. [password] is only ever read server-side
 * (CompanionServer / the media interceptor) and is never returned to any UI —
 * the public JSON exposes only whether a password is set.
 */
data class SmbShare(
    val id: String,
    val label: String,
    val host: String,
    val share: String,
    /** Optional subpath inside the share that browsing starts from. "" = root. */
    val path: String,
    val domain: String,
    val username: String,
    val password: String
) {
    /** No-secret view for UI listings. */
    fun toPublicJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label.ifBlank { "$host/$share" })
        .put("host", host)
        .put("share", share)
        .put("path", path)
        .put("domain", domain)
        .put("username", username)
        .put("hasPassword", password.isNotEmpty())

    /** Full view (with password) for the encrypted store only. */
    fun toStoredJson(): JSONObject = toPublicJson().put("password", password)

    companion object {
        fun fromStoredJson(o: JSONObject): SmbShare = SmbShare(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            label = o.optString("label").trim(),
            host = o.optString("host").trim(),
            share = o.optString("share").trim().trim('/'),
            path = o.optString("path").trim().trim('/'),
            domain = o.optString("domain").trim(),
            username = o.optString("username"),
            password = o.optString("password")
        )
    }
}

/**
 * Encrypted, on-glasses store of SMB share configs (credentials included).
 * Backed by [EncryptedSharedPreferences]; if that can't initialize (e.g. a
 * wiped keystore) it falls back to a private prefs file so the feature still
 * works rather than crashing — the shares stay device-local either way.
 */
class SmbShareStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val app = context.applicationContext
        try {
            val key = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                PREFS_NAME,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, using plain prefs: ${e.message}")
            app.getSharedPreferences(PREFS_NAME + "_plain", Context.MODE_PRIVATE)
        }
    }

    fun list(): List<SmbShare> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { SmbShare.fromStoredJson(it) }
            }
        }.getOrDefault(emptyList())
    }

    fun get(id: String): SmbShare? = list().firstOrNull { it.id == id }

    /** Insert or update by id. Returns the saved share (with a generated id). */
    fun save(share: SmbShare): SmbShare {
        val withId = if (share.id.isBlank()) share.copy(id = UUID.randomUUID().toString()) else share
        val current = list().filter { it.id != withId.id }.toMutableList()
        current.add(withId)
        persist(current)
        return withId
    }

    fun remove(id: String) = persist(list().filter { it.id != id })

    private fun persist(shares: List<SmbShare>) {
        val arr = JSONArray()
        shares.forEach { arr.put(it.toStoredJson()) }
        prefs.edit().putString(KEY, arr.toString()).commit()
    }

    companion object {
        private const val TAG = "SmbShareStore"
        private const val PREFS_NAME = "tapinsight_smb_shares"
        private const val KEY = "shares_json"
    }
}
