package dev.moku.mobile.backend

import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Sources often serve covers only with their own Referer or User-Agent. Coil loads covers everywhere, so the
 * headers are remembered per cover host, and saved so library covers load before any extension does.
 */
object CoverHeaders : Interceptor {
    private const val PREFS = "moku_cover_headers"
    // Cookies and auth are per-session and would go stale on disk; these are the ones image hosts check.
    private val KEPT = setOf("referer", "user-agent", "origin", "accept-language")

    private val byHost = ConcurrentHashMap<String, Map<String, String>>()
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.all.forEach { (host, raw) ->
            runCatching {
                val o = JSONObject(raw as String)
                byHost[host] = o.keys().asSequence().associateWith { o.getString(it) }
            }
        }
        prefs = p
    }

    /** Called as a source lists titles: covers from [coverUrl]'s host get [headers] from now on. */
    fun remember(coverUrl: String?, headers: Map<String, String>) {
        val host = coverUrl?.toHttpUrlOrNull()?.host ?: return
        val kept = headers.filterKeys { it.lowercase() in KEPT }
        if (kept.isEmpty() || byHost[host] == kept) return
        byHost[host] = kept
        prefs?.edit()?.putString(host, JSONObject(kept).toString())?.apply()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val headers = byHost[request.url.host] ?: return chain.proceed(request)
        val builder = request.newBuilder()
        headers.forEach { (name, value) -> if (request.header(name) == null) builder.header(name, value) }
        return chain.proceed(builder.build())
    }
}
