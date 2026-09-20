package dev.moku.mobile.novel

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class NovelJsException(message: String) : Exception(message)

/**
 * Hosts one novel plugin's JS execution inside a (headless, never attached to any view)
 * WebView — see [NovelBootstrapJs] for why WebView specifically. All WebView APIs are
 * main-thread-only, so every call here hops to [Dispatchers.Main]; the plugin's own
 * exported methods return Promises (real async/await compiles down to that), so
 * invocation is bridged through [AndroidBridge.onCallResult]/[onCallError] rather than
 * evaluateJavascript's synchronous-only return value.
 */
class NovelJsRuntime private constructor(private val context: Context) {
    private val network: NetworkHelper by injectLazy()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private lateinit var webView: WebView

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun init() = onMain {
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        }
        // A real https origin (not about:blank) so localStorage behaves like a normal page's.
        webView.loadDataWithBaseURL("https://localhost/", "<html><body></body></html>", "text/html", "utf-8", null)
    }

    private suspend fun awaitPageLoad() = suspendCancellableCoroutine<Unit> { cont ->
        // loadDataWithBaseURL's content is synchronous enough in practice for a blank
        // document + our own script injection, but give the WebView a tick to settle.
        Handler(Looper.getMainLooper()).postDelayed({ if (cont.isActive) cont.resume(Unit) }, 50)
    }

    private suspend fun evalRaw(script: String): String = onMain {
        val result = CompletableDeferred<String>()
        webView.evaluateJavascript(script) { value -> result.complete(value ?: "null") }
        result
    }.await()

    suspend fun loadPlugin(rawCode: String, storageNamespace: String): NovelPluginHandle {
        init()
        awaitPageLoad()
        evalRaw(NovelBootstrapJs.SCRIPT.replace("__NOVEL_NAMESPACE__", storageNamespace))

        val wrapped = """
            (function() {
                var module = { exports: {} };
                var exports = module.exports;
                (function(require, module, exports) {
                $rawCode
                })(require, module, exports);
                window.__plugin = module.exports.default || module.exports;
                return JSON.stringify({
                    id: window.__plugin.id, name: window.__plugin.name,
                    site: window.__plugin.site, lang: window.__plugin.lang,
                    version: window.__plugin.version,
                });
            })()
        """.trimIndent()

        val rawResult = evalRaw(wrapped)
        if (rawResult == "null") throw NovelJsException("plugin did not export a default plugin object")
        // evaluateJavascript's callback value is itself a JSON encoding of the JS return
        // value; since our script returns a string (JSON.stringify(...)), the callback
        // delivers that string JSON-quoted a second time — unwrap one level before parsing.
        val metaJson = org.json.JSONTokener(rawResult).nextValue() as String
        val meta = JSONObject(metaJson)
        return NovelPluginHandle(
            id = meta.optString("id", "unknown"),
            name = meta.optString("name", meta.optString("id", "unknown")),
            site = meta.optString("site", ""),
            lang = meta.optString("lang", ""),
            version = meta.optString("version", "0.0.0"),
            runtime = this,
        )
    }

    suspend fun call(methodName: String, args: List<Any?>): String {
        val callId = "k" + System.nanoTime()
        val deferred = CompletableDeferred<String>()
        pending[callId] = deferred
        val argsJson = org.json.JSONArray(args).toString()
        onMain {
            webView.evaluateJavascript(
                "__callPlugin(${JSONObject.quote(callId)}, ${JSONObject.quote(methodName)}, ${JSONObject.quote(argsJson)})",
                null,
            )
        }
        return try {
            withTimeout(30_000) { deferred.await() }
        } finally {
            pending.remove(callId)
        }
    }

    private inner class AndroidBridge {
        @JavascriptInterface
        fun onCallResult(callId: String, resultJson: String) {
            pending[callId]?.complete(resultJson)
        }

        @JavascriptInterface
        fun onCallError(callId: String, message: String) {
            pending[callId]?.completeExceptionally(NovelJsException(message))
        }

        @JavascriptInterface
        fun nativeFetch(callId: String, url: String, initJson: String) {
            scope.launch {
                val result = try {
                    val init = JSONObject(initJson)
                    val method = if (init.has("method")) init.getString("method") else "GET"
                    val headers = mutableMapOf<String, String>()
                    if (init.has("headers")) {
                        val h = init.getJSONObject("headers")
                        h.keys().forEach { key -> headers[key] = h.getString(key) }
                    }
                    val bodyStr = if (init.has("body")) init.getString("body") else null
                    var attempt = performFetch(url, method, headers, bodyStr)
                    // A 403 is the standard Cloudflare-blocked signature — try solving it once
                    // via a real WebView challenge run, then retry with the resulting
                    // cookie/UA, instead of surfacing the error straight to the plugin.
                    if (attempt.status == 403) {
                        val session = runCatching { CloudflareBypassWebView.solve(context, url) }.getOrNull()
                        if (session != null) {
                            headers["Cookie"] = session.cookie
                            headers["User-Agent"] = session.userAgent
                            attempt = performFetch(url, method, headers, bodyStr)
                        }
                    }
                    attempt
                } catch (t: Throwable) {
                    FetchResult(false, 0, "", t.message ?: t.javaClass.simpleName)
                }

                onMain {
                    val script = "__resolveFetch(${JSONObject.quote(callId)}, ${result.ok}, ${result.status}, ${JSONObject.quote(url)}, " +
                        "${JSONObject.quote(result.body)}, ${if (result.error != null) JSONObject.quote(result.error) else "null"})"
                    webView.evaluateJavascript(script, null)
                }
            }
        }

        private fun performFetch(url: String, method: String, headers: Map<String, String>, bodyStr: String?): FetchResult = try {
            val builder = Request.Builder().url(url)
            headers.forEach { (key, value) -> builder.addHeader(key, value) }
            if (bodyStr != null && method != "GET" && method != "HEAD") {
                builder.method(method, bodyStr.toRequestBody(null))
            } else {
                builder.method(method, null)
            }
            network.client.newCall(builder.build()).execute().use { resp ->
                FetchResult(resp.isSuccessful, resp.code, resp.body.string(), null)
            }
        } catch (t: Throwable) {
            FetchResult(false, 0, "", t.message ?: t.javaClass.simpleName)
        }
    }

    private data class FetchResult(val ok: Boolean, val status: Int, val body: String, val error: String?)

    companion object {
        suspend fun create(context: Context): NovelJsRuntime = NovelJsRuntime(context.applicationContext)
    }
}

data class NovelPluginHandle(
    val id: String,
    val name: String,
    val site: String,
    val lang: String,
    val version: String,
    val runtime: NovelJsRuntime,
)
