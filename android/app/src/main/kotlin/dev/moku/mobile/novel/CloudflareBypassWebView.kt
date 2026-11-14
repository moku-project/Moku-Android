package dev.moku.mobile.novel

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

data class CloudflareSession(val cookie: String, val userAgent: String)

/**
 * Clears a Cloudflare challenge by loading the page in a headless WebView and keeping its cf_clearance cookie and
 * User-Agent. Challenges that need a tap can't be solved this way.
 */
class CloudflareBypassWebView private constructor(private val context: Context) {
    companion object {
        // Domain -> session, so repeated requests to the same site don't re-run a challenge solve.
        private val cache = ConcurrentHashMap<String, CloudflareSession>()

        suspend fun solve(context: Context, url: String, timeoutMs: Long = 20_000): CloudflareSession {
            val domain = java.net.URI(url).host.orEmpty()
            cache[domain]?.let { return it }
            val session = CloudflareBypassWebView(context).runSolve(url, timeoutMs)
            cache[domain] = session
            return session
        }

        fun cached(url: String): CloudflareSession? = cache[java.net.URI(url).host.orEmpty()]
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runSolve(url: String, timeoutMs: Long): CloudflareSession {
        val cleared = CompletableDeferred<Unit>()
        lateinit var webView: WebView

        withContext(Dispatchers.Main.immediate) {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        // Cloudflare's interstitial reloads the page once the JS challenge
                        // computation finishes; a clean cf_clearance cookie means we're past it.
                        val cookies = CookieManager.getInstance().getCookie(loadedUrl).orEmpty()
                        if (cookies.contains("cf_clearance=") && !cleared.isCompleted) cleared.complete(Unit)
                    }
                }
            }
            webView.loadUrl(url)
        }

        try {
            withTimeout(timeoutMs) { cleared.await() }
        } catch (e: TimeoutCancellationException) {
            // Go on with whatever cookies exist; some sites name the clearance cookie differently.
        }

        return withContext(Dispatchers.Main.immediate) {
            val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
            val userAgent = webView.settings.userAgentString
            webView.destroy()
            CloudflareSession(cookie, userAgent)
        }
    }
}
