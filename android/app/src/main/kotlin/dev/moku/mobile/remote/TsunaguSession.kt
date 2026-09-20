package dev.moku.mobile.remote

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class TsunaguServer(val baseUrl: String, val token: String?)

/**
 * Holds the currently-connected self-hosted Tsunagu server (if any) and persists it in
 * EncryptedSharedPreferences — Android's equivalent of Moku's `platformService.storeCredential`
 * (which is Tauri's OS-keychain-backed secret store on desktop). Only one server connection
 * is modeled for now, matching Moku's current single-server assumption; nothing here blocks
 * moving to a list of saved servers later.
 */
class TsunaguSession(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "tsunagu_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var current: TsunaguServer? = loadFromDisk()
        set(value) {
            field = value
            persist(value)
        }

    private fun loadFromDisk(): TsunaguServer? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null)
        return TsunaguServer(baseUrl, token)
    }

    private fun persist(server: TsunaguServer?) {
        prefs.edit {
            if (server == null) {
                remove(KEY_BASE_URL)
                remove(KEY_TOKEN)
            } else {
                putString(KEY_BASE_URL, server.baseUrl)
                putString(KEY_TOKEN, server.token)
            }
        }
    }

    fun disconnect() {
        current = null
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "token"
    }
}
