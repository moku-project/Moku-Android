package dev.moku.mobile.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException

/** EncryptedSharedPreferences for sign-in tokens, opened so a broken store means signing in again, never a crash. */
object SecurePrefs {
    /** Null if the store can't open. Wiped only when it can't be decrypted, as after a restore onto another phone. */
    fun open(context: Context, file: String): SharedPreferences? {
        val app = context.applicationContext
        return runCatching { create(app, file) }
            .recoverCatching { e ->
                if (generateSequence(e) { it.cause }.none { it is GeneralSecurityException }) throw e
                Log.w("SecurePrefs", "$file can't be decrypted, starting over", e)
                app.deleteSharedPreferences(file)
                create(app, file)
            }
            .onFailure { Log.e("SecurePrefs", "couldn't open $file", it) }
            .getOrNull()
    }

    private fun create(context: Context, file: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, file, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
