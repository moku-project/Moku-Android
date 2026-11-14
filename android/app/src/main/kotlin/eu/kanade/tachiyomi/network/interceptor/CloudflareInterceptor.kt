package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Current Mihon/Tachiyomi-format extensions refuse to run unless the default client has an interceptor by
 * this name. It only recognizes a Cloudflare challenge for now and fails the request
 * with a clear error; solving challenges in a WebView, as Mihon does, comes later.
 */
class CloudflareInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code in CHALLENGE_CODES && response.header("Server") in CLOUDFLARE_SERVERS) {
            response.close()
            throw IOException("Cloudflare challenge on ${chain.request().url.host}; solving challenges isn't supported yet")
        }
        return response
    }

    private companion object {
        val CHALLENGE_CODES = setOf(403, 503)
        val CLOUDFLARE_SERVERS = setOf("cloudflare-nginx", "cloudflare")
    }
}
