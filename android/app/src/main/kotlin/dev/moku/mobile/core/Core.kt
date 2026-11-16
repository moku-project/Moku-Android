package dev.moku.mobile.core

import android.content.Context
import dev.moku.mobile.remote.TsunaguClient
import dev.moku.mobile.remote.TsunaguServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

// The phone keeps its library in its own Tsunagu, reached over GraphQL like any server, so it can sync with other devices.
object Core {
    @Volatile private var server: TsunaguServer? = null

    private val client = TsunaguClient { server }

    /** Waits for the core MainActivity starts; the first call may block while Tsunagu opens its database. */
    suspend fun ready(context: Context): TsunaguServer = server ?: withContext(Dispatchers.IO) {
        val endpoint = TsunaguCore.start(context)
        TsunaguServer(endpoint.baseUrl, endpoint.token, null, INSTANCE).also { server = it }
    }

    suspend fun query(context: Context, query: String, variables: JsonObject? = null): JsonObject =
        client.query(ready(context), query, variables)

    /** Core paths like `/proxy/cover/12` become absolute. */
    fun absolute(url: String?): String? {
        val base = server?.baseUrl ?: return url
        return if (url != null && url.startsWith("/")) base + url else url
    }

    /** Adds the core's token to the image loader's requests for it, such as library covers. */
    object AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val core = server ?: return chain.proceed(request)
            val base = core.baseUrl.toHttpUrlOrNull() ?: return chain.proceed(request)
            if (request.url.host != base.host || request.url.port != base.port || request.header("Authorization") != null) {
                return chain.proceed(request)
            }
            return chain.proceed(request.newBuilder().header("Authorization", "Bearer ${core.token}").build())
        }
    }

    private const val INSTANCE = "device"
}
