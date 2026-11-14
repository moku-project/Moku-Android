package dev.moku.mobile.remote

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.TimeUnit

class GraphQLException(message: String, val code: String?) : IOException(message)
class AuthRequiredException : IOException("Authentication required")

/** GraphQL client for a Tsunagu server; a 401 means signing in again. */
class TsunaguClient(private val server: () -> TsunaguServer?) {
    private val network: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()
    private val jsonMediaType = "application/json".toMediaType()

    // A home server either answers within seconds or is off; waiting out the default timeout only delays saying so.
    private val http: OkHttpClient by lazy { network.client.newBuilder().connectTimeout(6, TimeUnit.SECONDS).build() }

    // Captive portals and proxies answer with web pages; that's the server being unreachable, not a crash.
    private fun parseObject(body: String): JsonObject =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { throw IOException("The server's reply wasn't from Tsunagu") }

    private fun send(request: Request, baseUrl: String): Response = try {
        http.newCall(request).execute()
    } catch (e: ServerUnreachableException) {
        throw e
    } catch (e: IOException) {
        throw ServerUnreachableException(baseUrl, e)
    }

    suspend fun query(query: String, variables: JsonObject? = null): JsonObject =
        query(server() ?: throw AuthRequiredException(), query, variables)

    suspend fun query(target: TsunaguServer, query: String, variables: JsonObject? = null): JsonObject = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("query", query)
            if (variables != null) put("variables", variables)
        }
        val request = Request.Builder()
            .url("${target.baseUrl}/api/graphql")
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(jsonMediaType))
            .authorized(target)
            .build()
        send(request, target.baseUrl).use {
            if (it.code == 401) throw AuthRequiredException()
            if (!it.isSuccessful) throw IOException("Server returned HTTP ${it.code}")
            val root = parseObject(it.body.string())
            val errors = root["errors"]?.jsonArray
            if (!errors.isNullOrEmpty()) {
                val first = errors[0].jsonObject
                val message = first["message"]?.jsonPrimitive?.contentOrNull ?: "GraphQL error"
                val code = first["extensions"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
                throw GraphQLException(message, code)
            }
            root["data"]?.jsonObject ?: JsonObject(emptyMap())
        }
    }

    /** `POST /api/auth/login`, plain REST because it runs before there is a token. */
    suspend fun login(baseUrl: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("password", password) }
        val request = Request.Builder()
            .url("$baseUrl/api/auth/login")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMediaType))
            .build()
        send(request, baseUrl).use { response ->
            if (response.code == 401) throw IOException("Incorrect password")
            if (!response.isSuccessful) throw IOException("Sign-in failed: HTTP ${response.code}")
            val parsed = parseObject(response.body.string())
            LoginResult(
                token = parsed["token"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Server didn't return a session"),
                expiresAt = parsed["expiresAt"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    /** Whether the server at [baseUrl] wants a password; throws if nothing Tsunagu-shaped answers there. */
    suspend fun passwordRequired(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        send(Request.Builder().url("$baseUrl/api/auth/status").build(), baseUrl).use { response ->
            if (!response.isSuccessful) throw IOException("No Tsunagu server at $baseUrl (HTTP ${response.code})")
            val parsed = runCatching { json.parseToJsonElement(response.body.string()).jsonObject }
                .getOrElse { throw IOException("No Tsunagu server at $baseUrl") }
            parsed["passwordRequired"]?.jsonPrimitive?.contentOrNull == "true"
        }
    }

    /** Authenticated GET against a non-GraphQL route such as `/content/.../text`. */
    suspend fun rawGetText(url: String): String = withContext(Dispatchers.IO) {
        val target = server() ?: throw AuthRequiredException()
        send(Request.Builder().url(url).authorized(target).build(), target.baseUrl).use { response ->
            if (response.code == 401) throw AuthRequiredException()
            if (!response.isSuccessful) throw IOException("GET $url failed: HTTP ${response.code}")
            response.body.string()
        }
    }

    private fun Request.Builder.authorized(target: TsunaguServer) = apply {
        target.token?.let { header("Authorization", "Bearer $it") }
    }
}

data class LoginResult(val token: String, val expiresAt: String?)

fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
fun JsonObject.strOrNull(key: String): String? = this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.contentOrNull
