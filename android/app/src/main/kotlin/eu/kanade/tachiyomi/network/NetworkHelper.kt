package eu.kanade.tachiyomi.network

import android.content.Context
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Minimal stand-in for Mihon's NetworkHelper (which pulls in DoH provider settings,
 * Cloudflare bypass, and Metro DI wiring). Just enough OkHttpClient/UA plumbing for
 * HttpSource (`network: NetworkHelper by injectLazy()`) to resolve and make real calls.
 */
class NetworkHelper(context: Context) {

    private val cookieJar = InMemoryCookieJar()

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .cache(Cache(File(context.cacheDir, "network_cache"), 5L * 1024 * 1024))
        .build()

    fun defaultUserAgentProvider(): String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"
}

/** Trivial in-memory replacement for AndroidCookieJar (which persists via SharedPreferences). */
private class InMemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store[url.host] = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return store[url.host].orEmpty().filter { !it.expiresAt.let { exp -> exp < System.currentTimeMillis() } }
    }
}
