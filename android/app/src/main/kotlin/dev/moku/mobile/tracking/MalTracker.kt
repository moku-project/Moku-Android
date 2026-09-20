package dev.moku.mobile.tracking

import android.util.Base64
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Direct Kotlin port of `internal/tracker/mal.go`'s PKCE flow — `code_challenge_method=plain`
 * (the verifier itself is the challenge, no SHA256 needed), same public client id Tsunagu's
 * server defaults to. **Caveat this port can't fully resolve on its own**: Tsunagu's Go code
 * sets `redirect_uri` to *its own server's* callback route
 * (`{cfg.PublicURL}/api/tracker/mal/callback`), which is registered with MAL for that shared
 * client id. Without a server, this omits `redirect_uri` entirely, which only works if MAL
 * accepts a request with none for this client id (single registered default) — if MAL instead
 * requires an explicit match, this will fail at [authUrl] time with an invalid_request error,
 * and the real fix would be registering a separate MAL app with a redirect URI this app
 * actually controls (an Android custom-scheme deep link). Flagging this rather than papering
 * over it: it's untested against a real MAL login in this session.
 */
class MalTracker(
    private val clientId: String = DEFAULT_CLIENT_ID,
    private val clientSecret: String = "",
) : TrackerService {
    private val network: NetworkHelper by injectLazy()
    private val json = Json { ignoreUnknownKeys = true }

    private var pendingState: String? = null
    private var pendingVerifier: String? = null

    override val key = "mal"
    override val name = "MyAnimeList"
    override val iconUrl = "https://cdn.myanimelist.net/img/sp/icon/apple-touch-icon-256.png"

    override fun authUrl(): String {
        val state = randomToken(16)
        val verifier = randomToken(64)
        pendingState = state
        pendingVerifier = verifier
        val params = listOf(
            "response_type" to "code",
            "client_id" to clientId,
            "state" to state,
            "code_challenge" to verifier,
            "code_challenge_method" to "plain",
        )
        val query = params.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
        return "$AUTHORIZE?$query"
    }

    override suspend fun exchange(pasted: String): TrackerAuth {
        val trimmed = pasted.trim()
        val query = if (trimmed.contains("?")) trimmed.substringAfter("?") else trimmed
        val params = query.split("&").mapNotNull {
            val idx = it.indexOf('=')
            if (idx < 0) null else URLDecoder.decode(it.substring(0, idx), "UTF-8") to URLDecoder.decode(it.substring(idx + 1), "UTF-8")
        }.toMap()
        val code = params["code"] ?: throw IOException("Paste the full redirected URL from MyAnimeList (it contains ?code=...)")
        val state = params["state"] ?: throw IOException("Missing state in redirected URL")
        val verifier = pendingVerifier?.takeIf { pendingState == state }
            ?: throw IOException("Login expired or already used — start again")
        pendingState = null
        pendingVerifier = null

        val form = FormBody.Builder()
            .add("client_id", clientId)
            .apply { if (clientSecret.isNotEmpty()) add("client_secret", clientSecret) }
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", verifier)
            .build()
        return tokenRequest(form)
    }

    override suspend fun refreshIfNeeded(auth: TrackerAuth): TrackerAuth {
        val expiresAt = auth.expiresAtMillis ?: return auth
        if (System.currentTimeMillis() < expiresAt - 60_000) return auth
        val refreshToken = auth.refreshToken ?: throw ReauthRequiredException()
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .apply { if (clientSecret.isNotEmpty()) add("client_secret", clientSecret) }
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        return try {
            tokenRequest(form).let { if (it.username == null) it.copy(username = auth.username) else it }
        } catch (t: Exception) {
            throw ReauthRequiredException()
        }
    }

    private suspend fun tokenRequest(form: FormBody): TrackerAuth = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(TOKEN).post(form).build()
        network.client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw IOException("mal: HTTP ${response.code}: ${body.take(300)}")
            val parsed = json.parseToJsonElement(body).jsonObject
            val accessToken = parsed["access_token"]!!.jsonPrimitive.content
            val expiresIn = parsed["expires_in"]?.jsonPrimitive?.int
            var auth = TrackerAuth(
                accessToken = accessToken,
                refreshToken = parsed["refresh_token"]?.jsonPrimitive?.contentOrNull,
                expiresAtMillis = expiresIn?.let { System.currentTimeMillis() + it * 1000L },
                scoreFormat = "POINT_10",
            )
            val name = runCatching { username(accessToken) }.getOrNull()
            if (name != null) auth = auth.copy(username = name)
            return@withContext auth
        }
    }

    private suspend fun username(token: String): String = withContext(Dispatchers.IO) {
        get(token, "/users/@me?fields=name")["name"]!!.jsonPrimitive.content
    }

    private suspend fun get(token: String, path: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(API + path).addHeader("Authorization", "Bearer $token").build()
        network.client.newCall(request).execute().use { response ->
            if (response.code == 401) throw ReauthRequiredException()
            val body = response.body.string()
            if (!response.isSuccessful) throw IOException("mal: HTTP ${response.code}: ${body.take(200)}")
            json.parseToJsonElement(body).jsonObject
        }
    }

    override suspend fun search(auth: TrackerAuth, query: String, contentType: String): List<TrackSearchResult> {
        if (query.trim().length < 3) return emptyList()
        val kinds = when (contentType) {
            "anime" -> listOf("anime")
            "manga", "novel" -> listOf("manga")
            else -> listOf("manga", "anime")
        }
        val results = mutableListOf<TrackSearchResult>()
        for (kind in kinds) {
            val fields = if (kind == "anime") "id,title,main_picture,synopsis,status,media_type,num_episodes" else "id,title,main_picture,synopsis,status,media_type,num_chapters"
            val path = "/$kind?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=15&fields=$fields"
            val data = get(auth.accessToken, path)
            for (item in data["data"]?.jsonArray.orEmpty()) {
                val node = item.jsonObject["node"]!!.jsonObject
                val mediaType = node["media_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (contentType == "novel" && !mediaType.contains("novel")) continue
                val count = if (kind == "anime") node["num_episodes"]?.jsonPrimitive?.int ?: 0 else node["num_chapters"]?.jsonPrimitive?.int ?: 0
                val id = node["id"]!!.jsonPrimitive.int
                val pic = node["main_picture"]?.jsonObject
                results.add(
                    TrackSearchResult(
                        remoteId = "$kind:$id",
                        title = node["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        url = "https://myanimelist.net/$kind/$id",
                        coverUrl = pic?.get("large")?.jsonPrimitive?.contentOrNull ?: pic?.get("medium")?.jsonPrimitive?.contentOrNull,
                        summary = node["synopsis"]?.jsonPrimitive?.contentOrNull?.take(500),
                        totalChapters = count,
                        publishingStatus = node["status"]?.jsonPrimitive?.contentOrNull,
                        mediaType = if (kind == "anime") "ANIME" else "MANGA",
                    ),
                )
            }
        }
        return results
    }

    override suspend fun listLibrary(auth: TrackerAuth, contentType: String, statuses: List<String>): List<TrackLibraryEntry> =
        throw IOException("library list not implemented for MyAnimeList yet") // matches Tsunagu's own ListLibrary stub

    override suspend fun bind(auth: TrackerAuth, remoteId: String): Track {
        val (kind, id) = splitId(remoteId)
        val fields = if (kind == "anime") {
            "id,title,num_episodes,status,my_list_status{status,score,num_episodes_watched,is_rereading}"
        } else {
            "id,title,num_chapters,status,my_list_status{status,score,num_chapters_read,is_rereading}"
        }
        val node = get(auth.accessToken, "/$kind/$id?fields=$fields")
        return trackFromNode(kind, id, node)
    }

    override suspend fun push(auth: TrackerAuth, track: Track): Track {
        val (kind, id) = splitId(track.remoteId)
        val form = FormBody.Builder()
            .add("status", canonicalToMalStatus(track.status, kind))
            .add("score", track.score.toInt().toString())
            .apply {
                if (kind == "anime") add("num_watched_episodes", track.lastChapterRead.toInt().toString())
                else add("num_chapters_read", track.lastChapterRead.toInt().toString())
            }
            .build()
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$API/$kind/$id/my_list_status")
                .addHeader("Authorization", "Bearer ${auth.accessToken}").patch(form).build()
            network.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("mal: HTTP ${response.code}")
            }
        }
        return bind(auth, track.remoteId)
    }

    override fun scoreOptions(auth: TrackerAuth?): List<String> = (0..10).map { it.toString() }

    private fun trackFromNode(kind: String, id: String, node: kotlinx.serialization.json.JsonObject): Track {
        val total = if (kind == "anime") node["num_episodes"]?.jsonPrimitive?.int ?: 0 else node["num_chapters"]?.jsonPrimitive?.int ?: 0
        val status = node["my_list_status"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonObject
        return Track(
            remoteId = "$kind:$id",
            title = node["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            url = "https://myanimelist.net/$kind/$id",
            totalChapters = total,
            status = status?.get("status")?.jsonPrimitive?.contentOrNull?.let {
                malStatusToCanonical(it, status["is_rereading"]?.jsonPrimitive?.contentOrNull == "true")
            } ?: TrackStatus.PLAN_TO_READ,
            score = status?.get("score")?.jsonPrimitive?.double ?: 0.0,
            lastChapterRead = if (kind == "anime") status?.get("num_episodes_watched")?.jsonPrimitive?.double ?: 0.0
            else status?.get("num_chapters_read")?.jsonPrimitive?.double ?: 0.0,
        )
    }

    private fun splitId(remoteId: String): Pair<String, String> {
        val idx = remoteId.indexOf(':')
        return if (idx > 0) remoteId.substring(0, idx) to remoteId.substring(idx + 1) else "manga" to remoteId
    }

    private fun malStatusToCanonical(s: String, rereading: Boolean): TrackStatus = when (s) {
        "reading", "watching" -> if (rereading) TrackStatus.REREADING else TrackStatus.READING
        "completed" -> TrackStatus.COMPLETED
        "on_hold" -> TrackStatus.ON_HOLD
        "dropped" -> TrackStatus.DROPPED
        else -> TrackStatus.PLAN_TO_READ
    }

    private fun canonicalToMalStatus(s: TrackStatus, kind: String): String = when (s) {
        TrackStatus.READING, TrackStatus.REREADING -> if (kind == "anime") "watching" else "reading"
        TrackStatus.COMPLETED -> "completed"
        TrackStatus.ON_HOLD -> "on_hold"
        TrackStatus.DROPPED -> "dropped"
        TrackStatus.PLAN_TO_READ -> if (kind == "anime") "plan_to_watch" else "plan_to_read"
    }

    private fun randomToken(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        // Same default MAL client id Tsunagu's own config.go ships (config.MALClientID default);
        // clientSecret intentionally blank — PKCE doesn't need one, matching Tsunagu's own setup.
        const val DEFAULT_CLIENT_ID = "611c821aee93c5e51411bfa86ca32597"
        private const val AUTHORIZE = "https://myanimelist.net/v1/oauth2/authorize"
        private const val TOKEN = "https://myanimelist.net/v1/oauth2/token"
        private const val API = "https://api.myanimelist.net/v2"
    }
}
