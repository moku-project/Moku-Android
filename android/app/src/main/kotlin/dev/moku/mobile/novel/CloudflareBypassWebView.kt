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
 * Local (no-server) equivalent of Tsunagu's FlareSolverr integration
 * (`cloudflare_solver_mode`/`CloudflareSolver` in the schema) — FlareSolverr itself is a
 * separate headless-Chrome *service* Tsunagu shells out to; a phone already has a real
 * Chromium engine on-device (the same WebView [NovelJsRuntime] already uses for plugin JS),
 * so instead of running a whole extra browser process, this loads the challenged page in a
 * real (headless, unattached) WebView, lets Cloudflare's own JS challenge run exactly as it
 * would in a normal mobile browser, and harvests the resulting `cf_clearance` cookie +
 * User-Agent once the challenge clears — then [dev.moku.mobile.novel.NovelJsRuntime]'s
 * `nativeFetch` (and any other OkHttp call hitting that domain) attaches them to plain HTTP
 * requests instead of needing a browser for every subsequent request.
 *
 * One real limitation this doesn't solve: some Cloudflare challenge tiers require an
 * interactive checkbox/turnstile tap, which a headless (never-attached) WebView can't satisfy
 * — this only clears the non-interactive JS-computation challenge tier, same ceiling
 * FlareSolverr itself has without a "solve manually" fallback.
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
            // Fall through with whatever cookies exist — some sites set a clearance cookie
            // under a different name, or the challenge tier needs interaction we can't do headlessly.
        }

        return withContext(Dispatchers.Main.immediate) {
            val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
            val userAgent = webView.settings.userAgentString
            webView.destroy()
            CloudflareSession(cookie, userAgent)
        }
    }
}
