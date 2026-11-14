package dev.moku.mobile.sync

import android.content.Context
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.remote.ServerSession
import dev.moku.mobile.remote.str
import dev.moku.mobile.remote.strOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

data class ServerRef(val instanceId: String, val type: MediaContentType, val extensionId: String)

/** `server:<instanceId>:<type>:<extensionId>`. No address in it, so a server that changes IP keeps its titles. */
object ServerOrigin {
    const val PREFIX = "server:"

    fun of(instanceId: String, type: MediaContentType, extensionId: String) = "$PREFIX$instanceId:${type.name.lowercase()}:$extensionId"

    fun isServer(originId: String) = originId.startsWith(PREFIX)

    fun parse(originId: String): ServerRef? {
        val parts = originId.split(":", limit = 4)
        if (parts.size != 4 || parts[0] != "server") return null
        val type = MediaContentType.entries.firstOrNull { it.name.equals(parts[2], ignoreCase = true) } ?: return null
        return ServerRef(parts[1], type, parts[3])
    }

    /** True for titles of the server this phone is signed in to now. */
    fun isCurrent(originId: String): Boolean {
        val instance = ServerSession.current?.instanceId ?: return false
        return parse(originId)?.instanceId == instance
    }
}

data class ServerSource(val id: String, val name: String, val contentType: MediaContentType, val lang: String, val iconUrl: String?)

/** The server's installed extensions, remembered so server titles still know their source name offline. */
object ServerSources {
    private const val PREFS = "moku_server_sources"

    suspend fun refresh(context: Context): List<ServerSource> {
        val data = ServerSession.client.query(
            "query InstalledSources { installedExtensions { id name displayName contentType lang iconUrl enabled } }",
        )
        val sources = data["installedExtensions"]!!.jsonArray.map { it.jsonObject }
            .filter { it["enabled"]?.jsonPrimitive?.contentOrNull != "false" }
            .mapNotNull { o ->
                val type = MediaContentType.entries.firstOrNull { it.name == o.str("contentType").uppercase() } ?: return@mapNotNull null
                ServerSource(o.str("id"), o.strOrNull("displayName") ?: o.str("name"), type, o.strOrNull("lang").orEmpty(), ServerSession.absolute(o.strOrNull("iconUrl")))
            }
        save(context, sources)
        return sources
    }

    fun cached(context: Context): List<ServerSource> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("sources", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                ServerSource(o.getString("id"), o.getString("name"), MediaContentType.valueOf(o.getString("type")), o.optString("lang"), o.optString("icon").ifEmpty { null })
            }
        }.getOrDefault(emptyList())
    }

    fun name(context: Context, extensionId: String): String? = cached(context).firstOrNull { it.id == extensionId }?.name

    fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    private fun save(context: Context, sources: List<ServerSource>) {
        val a = JSONArray()
        sources.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("type", it.contentType.name).put("lang", it.lang).put("icon", it.iconUrl ?: "")) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("sources", a.toString()).apply()
    }
}
