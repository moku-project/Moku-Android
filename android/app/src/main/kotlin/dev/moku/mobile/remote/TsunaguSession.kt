package dev.moku.mobile.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.moku.mobile.core.SecurePrefs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/** [instanceId] is the server's permanent id; downloads and progress are filed under it, never under the address. */
data class TsunaguServer(val baseUrl: String, val token: String?, val name: String?, val instanceId: String)

/**
 * The signed-in server and whether server mode is on. Turning server mode off keeps the sign-in, so the server's
 * downloads stay and turning it back on needs no password.
 */
object ServerSession {
    @Volatile var current: TsunaguServer? = null
        private set

    /** A sign-in saved before servers reported an instance id; [dev.moku.mobile.sync.ServerSync] fills it in. */
    @Volatile var legacy: TsunaguServer? = null
        private set

    val client = TsunaguClient { current }

    private var prefs: SharedPreferences? = null
    private var known: SharedPreferences? = null
    @Volatile private var origin: HttpUrl? = null

    private val _serverMode = MutableStateFlow(false)

    /** On when the app shows the server's library and sources instead of the phone's. */
    val serverMode: StateFlow<Boolean> = _serverMode

    val signedIn: Boolean get() = current != null

    /** Signed in and in server mode. */
    val active: Boolean get() = current != null && _serverMode.value

    private val _reachable = MutableStateFlow(true)

    /** False once a request to the server fails to connect, until one gets through again. */
    val reachable: StateFlow<Boolean> = _reachable

    internal fun setReachable(ok: Boolean) {
        if (_reachable.value != ok) _reachable.value = ok
    }

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        prefs = SecurePrefs.open(app, FILE)
        known = app.getSharedPreferences(KNOWN_FILE, Context.MODE_PRIVATE)
        current = prefs?.let { p ->
            val base = p.getString(KEY_BASE_URL, null) ?: return@let null
            val instance = p.getString(KEY_INSTANCE, null)
            if (instance == null) {
                legacy = TsunaguServer(base, p.getString(KEY_TOKEN, null), p.getString(KEY_NAME, null), "")
                return@let null
            }
            TsunaguServer(base, p.getString(KEY_TOKEN, null), p.getString(KEY_NAME, null), instance)
        }
        _serverMode.value = current != null && prefs?.getBoolean(KEY_MODE, true) == true
        origin = current?.baseUrl?.toHttpUrlOrNull()
    }

    @Synchronized
    fun save(context: Context, server: TsunaguServer?) {
        init(context)
        current = server
        legacy = null
        origin = server?.baseUrl?.toHttpUrlOrNull()
        _reachable.value = true
        prefs?.edit {
            if (server == null) {
                remove(KEY_BASE_URL); remove(KEY_TOKEN); remove(KEY_NAME); remove(KEY_INSTANCE); remove(KEY_MODE)
            } else {
                putString(KEY_BASE_URL, server.baseUrl); putString(KEY_TOKEN, server.token); putString(KEY_NAME, server.name)
                putString(KEY_INSTANCE, server.instanceId)
            }
        }
        server?.name?.let { name -> known?.edit { putString(server.instanceId, name) } }
        setServerMode(context, server != null)
    }

    /** The owner renamed the server; its labels follow. */
    @Synchronized
    fun rename(context: Context, name: String) {
        init(context)
        val server = current ?: return
        if (server.name == name) return
        current = server.copy(name = name)
        prefs?.edit { putString(KEY_NAME, name) }
        known?.edit { putString(server.instanceId, name) }
    }

    fun setServerMode(context: Context, on: Boolean) {
        init(context)
        val value = on && current != null
        prefs?.edit { putBoolean(KEY_MODE, value) }
        _serverMode.value = value
    }

    /** The name a server had when this phone last signed in to it, for labelling its downloads once it's gone. */
    fun nameOf(instanceId: String): String? =
        current?.takeIf { it.instanceId == instanceId }?.name ?: known?.getString(instanceId, null)

    /** Server paths like `/proxy/cover/12` become absolute; anything else passes through. */
    fun absolute(url: String?): String? {
        val base = current?.baseUrl ?: return url
        return if (url != null && url.startsWith("/")) base + url else url
    }

    internal fun isServer(url: HttpUrl): Boolean {
        val o = origin ?: return false
        return url.scheme == o.scheme && url.host == o.host && url.port == o.port
    }

    private const val FILE = "tsunagu_session"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_NAME = "name"
    private const val KEY_INSTANCE = "instance_id"
    private const val KEY_MODE = "server_mode"
    private const val KNOWN_FILE = "moku_known_servers"
}

/** Nothing answered at the server's address: it's off, asleep, or on a network this phone can't see. */
class ServerUnreachableException(baseUrl: String, cause: IOException) : IOException("Can't connect to $baseUrl", cause)

/** Adds the token to requests for the server, and only those; also where the app learns the server is down or back. */
object ServerAuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!ServerSession.isServer(request.url)) return chain.proceed(request)
        val token = ServerSession.current?.token
        val authed = if (token == null || request.header("Authorization") != null) request else request.newBuilder().header("Authorization", "Bearer $token").build()
        val response = try {
            chain.proceed(authed)
        } catch (e: IOException) {
            if (chain.call().isCanceled()) throw e
            ServerSession.setReachable(false)
            throw ServerUnreachableException(ServerSession.current?.baseUrl ?: request.url.host, e)
        }
        ServerSession.setReachable(true)
        return response
    }
}
