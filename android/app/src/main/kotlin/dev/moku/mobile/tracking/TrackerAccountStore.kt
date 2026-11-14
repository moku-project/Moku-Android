package dev.moku.mobile.tracking

import android.content.Context
import androidx.core.content.edit
import dev.moku.mobile.core.SecurePrefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Tracker sign-ins, kept in encrypted storage. */
class TrackerAccountStore(context: Context) {
    private val prefs = SecurePrefs.open(context, FILE)

    fun get(trackerKey: String): TrackerAuth? {
        val raw = prefs?.getString(trackerKey, null) ?: return null
        val obj = Json.parseToJsonElement(raw).jsonObject
        return TrackerAuth(
            accessToken = obj["accessToken"]!!.jsonPrimitive.content,
            refreshToken = obj["refreshToken"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull,
            expiresAtMillis = obj["expiresAtMillis"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long,
            username = obj["username"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull,
            scoreFormat = obj["scoreFormat"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull,
        )
    }

    fun set(trackerKey: String, auth: TrackerAuth) {
        val json = buildJsonObject {
            put("accessToken", auth.accessToken)
            put("refreshToken", auth.refreshToken?.let { JsonPrimitive(it) } ?: JsonNull)
            put("expiresAtMillis", auth.expiresAtMillis?.let { JsonPrimitive(it) } ?: JsonNull)
            put("username", auth.username?.let { JsonPrimitive(it) } ?: JsonNull)
            put("scoreFormat", auth.scoreFormat?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        prefs?.edit { putString(trackerKey, json.toString()) }
    }

    fun clear(trackerKey: String) {
        prefs?.edit { remove(trackerKey) }
    }

    fun keys(): Set<String> = prefs?.all?.keys.orEmpty()

    private companion object {
        const val FILE = "tracker_accounts"
    }
}
