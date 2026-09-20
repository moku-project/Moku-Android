package dev.moku.mobile.remote

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy
import java.io.IOException

class GraphQLException(message: String, val code: String?) : IOException(message)
class AuthRequiredException : IOException("Authentication required")

/**
 * Kotlin port of Moku's `graphql/client.ts` + `server-adapters/tsunagu/gql.ts`: same
 * endpoint (`POST {baseUrl}/api/graphql`), same envelope, same 401-means-relogin
 * convention, same bearer-token auth header. Deliberately schema-identical so a single
 * mental model (and a single GraphQL surface to keep in sync) covers both the desktop
 * app and this one — see [RemoteTsunaguBackend] for how the queries map onto
 * [dev.moku.mobile.backend.MangaBackend].
 */
class TsunaguClient(
    private val session: TsunaguSession,
) {
    private val network: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    private val jsonMediaType = "application/json".toMediaType()

    // Every call below does a blocking OkHttp .execute(); these are suspend functions so
    // callers can invoke them from lifecycleScope.launch (Dispatchers.Main by default)
    // without freezing the UI thread — the actual blocking I/O is pushed onto Dispatchers.IO.
    suspend fun query(query: String, variables: JsonObject? = null): JsonObject = withContext(Dispatchers.IO) {
        val server = session.current ?: throw AuthRequiredException()
        val payload = buildJsonObject {
            put("query", query)
            if (variables != null) put("variables", variables)
        }

        val requestBuilder = okhttp3.Request.Builder()
            .url("${server.baseUrl.trimEnd('/')}/api/graphql")
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(jsonMediaType))
        server.token?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }

        val response = network.client.newCall(requestBuilder.build()).execute()
        response.use {
            if (it.code == 401) throw AuthRequiredException()
            if (!it.isSuccessful) throw IOException("GraphQL request failed: HTTP ${it.code}")

            val body = it.body!!.string()
            val root = json.parseToJsonElement(body).jsonObject
            val errors = root["errors"]?.jsonArray
            if (errors != null && errors.isNotEmpty()) {
                val first = errors[0].jsonObject
                val message = first["message"]?.jsonPrimitive?.contentOrNull ?: "GraphQL error"
                val code = first["extensions"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
                throw GraphQLException(message, code)
            }
            return@withContext root["data"]?.jsonObject ?: JsonObject(emptyMap())
        }
    }

    /** `POST /api/auth/login` — same REST-not-GraphQL endpoint Moku uses, since login precedes having a token. */
    suspend fun login(baseUrl: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("password", password) }
        val request = okhttp3.Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/auth/login")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMediaType))
            .build()

        network.client.newCall(request).execute().use { response ->
            if (response.code == 401) throw IOException("Incorrect password")
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val parsed = json.parseToJsonElement(response.body!!.string()).jsonObject
            return@withContext LoginResult(
                token = parsed["token"]!!.jsonPrimitive.content,
                expiresAt = parsed["expiresAt"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    /** Plain authenticated GET against a non-GraphQL Tsunagu route (e.g. `/content/.../text`). */
    suspend fun rawGetText(url: String): String = withContext(Dispatchers.IO) {
        val server = session.current ?: throw AuthRequiredException()
        val requestBuilder = okhttp3.Request.Builder().url(url)
        server.token?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }

        network.client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 401) throw AuthRequiredException()
            if (!response.isSuccessful) throw IOException("GET $url failed: HTTP ${response.code}")
            return@withContext response.body!!.string()
        }
    }

    suspend fun authStatus(baseUrl: String): Boolean {
        val server = TsunaguServer(baseUrl, token = null)
        val previous = session.current
        session.current = server
        try {
            val data = query("query AuthStatus { authStatus { passwordSet } }")
            return data["authStatus"]?.jsonObject?.get("passwordSet")?.jsonPrimitive?.contentOrNull == "true"
        } finally {
            session.current = previous
        }
    }
}

data class LoginResult(val token: String, val expiresAt: String?)

/** Extract a required string field from a GraphQL JSON object result — small helper to avoid repeating jsonPrimitive.content everywhere. */
fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
fun JsonObject.strOrNull(key: String): String? = this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.contentOrNull
