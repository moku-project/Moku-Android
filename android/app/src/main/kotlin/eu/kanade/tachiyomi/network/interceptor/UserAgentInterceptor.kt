// Adapted from Mihon/Tachiyomi (https://github.com/mihonapp/mihon), licensed under Apache License 2.0.

package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

class UserAgentInterceptor(
    private val userAgentProvider: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        return if (originalRequest.header("User-Agent").isNullOrEmpty()) {
            val newRequest =
                originalRequest
                    .newBuilder()
                    .removeHeader("User-Agent")
                    .addHeader("User-Agent", userAgentProvider())
                    .build()
            chain.proceed(newRequest)
        } else {
            chain.proceed(originalRequest)
        }
    }
}
