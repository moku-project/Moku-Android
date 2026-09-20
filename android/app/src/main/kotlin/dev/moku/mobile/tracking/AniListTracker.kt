package dev.moku.mobile.tracking

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy
import java.io.IOException

/**
 * Direct Kotlin port of `internal/tracker/anilist.go` — same GraphQL endpoint
 * (`https://graphql.anilist.co`), same implicit-grant auth URL, same queries. No server
 * involved at all: the token comes back to the browser directly (AniList's
 * `response_type=token`), so this talks to AniList's API exactly the way Tsunagu's own
 * Go code does, just from the phone instead of the server.
 */
class AniListTracker(private val clientId: String = DEFAULT_CLIENT_ID) : TrackerService {
    private val network: NetworkHelper by injectLazy()
    private val json = Json { ignoreUnknownKeys = true }

    override val key = "anilist"
    override val name = "AniList"
    override val iconUrl = "https://anilist.co/img/icons/android-chrome-512x512.png"

    override fun authUrl(): String = "$AUTH_BASE?client_id=$clientId&response_type=token"

    private suspend fun query(token: String?, doc: String, vars: JsonObject = JsonObject(emptyMap())): JsonObject =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject { put("query", doc); put("variables", vars) }
            val requestBuilder = Request.Builder().url(API)
                .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json".toMediaType()))
                .addHeader("Accept", "application/json")
            token?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }

            network.client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code == 401) throw ReauthRequiredException()
                val raw = response.body.string()
                val root = json.parseToJsonElement(raw).jsonObject
                val errors = root["errors"]?.jsonArray
                if (!response.isSuccessful || (errors != null && errors.isNotEmpty())) {
                    val message = errors?.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    throw IOException("anilist: ${message ?: "HTTP ${response.code}"}")
                }
                return@withContext root["data"]?.jsonObject ?: JsonObject(emptyMap())
            }
        }

    override suspend fun exchange(pasted: String): TrackerAuth {
        val token = extractToken(pasted) ?: throw IOException("No access token found in pasted value")
        val data = query(token, "query { Viewer { name mediaListOptions { scoreFormat } } }")
        val viewer = data["Viewer"]!!.jsonObject
        return TrackerAuth(
            accessToken = token,
            username = viewer["name"]?.jsonPrimitive?.contentOrNull,
            scoreFormat = viewer["mediaListOptions"]?.jsonObject?.get("scoreFormat")?.jsonPrimitive?.contentOrNull,
        )
    }

    override suspend fun search(auth: TrackerAuth, query: String, contentType: String): List<TrackSearchResult> {
        val pages = when (contentType) {
            "anime" -> listOf("anime" to "type: ANIME")
            "novel" -> listOf("novel" to "type: MANGA, format: NOVEL")
            "manga" -> listOf("manga" to "type: MANGA, format_not: NOVEL")
            else -> listOf("manga" to "type: MANGA, format_not: NOVEL", "novel" to "type: MANGA, format: NOVEL", "anime" to "type: ANIME")
        }
        val doc = buildString {
            appendLine("query(\$q: String) {")
            pages.forEach { (alias, args) ->
                appendLine("  $alias: Page(page: 1, perPage: 15) { media(search: \$q, $args, sort: SEARCH_MATCH) { $MEDIA_FIELDS } }")
            }
            append("}")
        }
        val data = query(auth.accessToken, doc, buildJsonObject { put("q", query) })
        return pages.flatMap { (alias, _) ->
            data[alias]?.jsonObject?.get("media")?.jsonArray?.map { it.jsonObject.toSearchResult() }.orEmpty()
        }
    }

    override suspend fun listLibrary(auth: TrackerAuth, contentType: String, statuses: List<String>): List<TrackLibraryEntry> {
        val username = auth.username ?: run {
            val v = query(auth.accessToken, "query { Viewer { name } }")["Viewer"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            v ?: throw IOException("no username associated with AniList account")
        }
        val alType = if (contentType.equals("anime", true)) "ANIME" else "MANGA"
        val statusIn = statuses.map { it.uppercase() }.filter { it.isNotBlank() }
        val entries = mutableListOf<TrackLibraryEntry>()
        val seen = mutableSetOf<Int>()
        var chunk = 1
        while (chunk <= 50) {
            val vars = buildJsonObject {
                put("userName", username); put("type", alType); put("chunk", chunk)
                if (statusIn.isNotEmpty()) put("statuses", kotlinx.serialization.json.buildJsonArray { statusIn.forEach { add(it) } })
            }
            val data = query(auth.accessToken, LIST_COLLECTION_DOC, vars)
            val collection = data["MediaListCollection"]?.jsonObject ?: break
            val lists = collection["lists"]?.jsonArray.orEmpty()
            for (listEl in lists) {
                val list = listEl.jsonObject
                val listStatus = list["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                for (itemEl in list["entries"]?.jsonArray.orEmpty()) {
                    val item = itemEl.jsonObject
                    val media = item["media"]!!.jsonObject
                    val id = media["id"]!!.jsonPrimitive.int
                    if (!seen.add(id)) continue
                    val format = media["format"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (contentType.equals("novel", true) && !format.equals("NOVEL", true)) continue
                    if (contentType.equals("manga", true) && format.equals("NOVEL", true)) continue

                    val type = media["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val total = if (type == "ANIME") media["episodes"]?.jsonPrimitive?.int ?: 0 else media["chapters"]?.jsonPrimitive?.int ?: 0
                    entries.add(
                        TrackLibraryEntry(
                            remoteId = id.toString(),
                            title = media.title(),
                            status = item["status"]?.jsonPrimitive?.contentOrNull ?: listStatus,
                            progress = item["progress"]?.jsonPrimitive?.double ?: 0.0,
                            score = item["score"]?.jsonPrimitive?.double ?: 0.0,
                            coverUrl = media["coverImage"]?.jsonObject?.get("large")?.jsonPrimitive?.contentOrNull,
                            mediaType = type,
                            url = media["siteUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            totalChapters = total,
                        ),
                    )
                }
            }
            if (collection["hasNextChunk"]?.jsonPrimitive?.contentOrNull != "true") break
            chunk++
        }
        return entries
    }

    override suspend fun bind(auth: TrackerAuth, remoteId: String): Track {
        val id = remoteId.toIntOrNull() ?: throw IOException("bad anilist media id $remoteId")
        val data = query(auth.accessToken, BIND_DOC, buildJsonObject { put("id", id) })
        val media = data["Media"]!!.jsonObject
        val entry = media["mediaListEntry"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonObject
        return Track(
            remoteId = remoteId,
            libraryId = entry?.get("id")?.jsonPrimitive?.contentOrNull,
            title = media.title(),
            url = media["siteUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            status = entry?.get("status")?.jsonPrimitive?.contentOrNull?.let { statusFromAniList(it) } ?: TrackStatus.PLAN_TO_READ,
            lastChapterRead = entry?.get("progress")?.jsonPrimitive?.double ?: 0.0,
            totalChapters = media["chapters"]?.jsonPrimitive?.int ?: 0,
            score = entry?.get("score")?.jsonPrimitive?.double ?: 0.0,
        )
    }

    override suspend fun push(auth: TrackerAuth, track: Track): Track {
        val id = track.remoteId.toIntOrNull() ?: throw IOException("bad anilist media id ${track.remoteId}")
        val vars = buildJsonObject {
            put("mediaId", id)
            put("status", statusToAniList(track.status))
            put("progress", track.lastChapterRead.toInt())
            put("score", track.score)
        }
        val data = query(auth.accessToken, PUSH_DOC, vars)
        val entry = data["SaveMediaListEntry"]!!.jsonObject
        return track.copy(
            libraryId = entry["id"]?.jsonPrimitive?.contentOrNull,
            status = entry["status"]?.jsonPrimitive?.contentOrNull?.let { statusFromAniList(it) } ?: track.status,
            score = entry["score"]?.jsonPrimitive?.double ?: track.score,
            lastChapterRead = entry["progress"]?.jsonPrimitive?.double ?: track.lastChapterRead,
        )
    }

    override fun scoreOptions(auth: TrackerAuth?): List<String> = when (auth?.scoreFormat) {
        "POINT_100" -> (0..100).map { it.toString() }
        "POINT_10_DECIMAL" -> generateSequence(0.0) { (it + 0.5).takeIf { v -> v <= 10.0 } }.map { "%.1f".format(it) }.toList()
        "POINT_5" -> (0..5).map { it.toString() }
        "POINT_3" -> listOf("-", "😦", "😐", "😊")
        else -> (0..10).map { it.toString() }
    }

    private fun JsonObject.title(): String {
        val t = this["title"]?.jsonObject ?: return ""
        return t["english"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: t["romaji"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: t["native"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun JsonObject.toSearchResult(): TrackSearchResult {
        val type = this["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val count = if (type == "ANIME") this["episodes"]?.jsonPrimitive?.int ?: 0 else this["chapters"]?.jsonPrimitive?.int ?: 0
        return TrackSearchResult(
            remoteId = this["id"]!!.jsonPrimitive.int.toString(),
            title = title(),
            url = this["siteUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            coverUrl = this["coverImage"]?.jsonObject?.get("large")?.jsonPrimitive?.contentOrNull,
            summary = this["description"]?.jsonPrimitive?.contentOrNull?.take(500),
            totalChapters = count,
            publishingStatus = this["status"]?.jsonPrimitive?.contentOrNull,
            mediaType = type,
        )
    }

    companion object {
        const val DEFAULT_CLIENT_ID = "49724" // same public client id Tsunagu's own AniListClientID default uses
        private const val AUTH_BASE = "https://anilist.co/api/v2/oauth/authorize"
        private const val API = "https://graphql.anilist.co"
        private const val MEDIA_FIELDS = "id siteUrl chapters episodes type status title { romaji english native } description(asHtml: false) coverImage { large }"

        private val LIST_COLLECTION_DOC = """
            query(${'$'}userName: String, ${'$'}type: MediaType, ${'$'}statuses: [MediaListStatus], ${'$'}chunk: Int) {
              MediaListCollection(userName: ${'$'}userName, type: ${'$'}type, status_in: ${'$'}statuses, chunk: ${'$'}chunk, perChunk: 500) {
                hasNextChunk
                lists { name isCustomList status entries { id status score progress media { id siteUrl chapters episodes type format status title { romaji english native } coverImage { large } } } }
              }
            }
        """.trimIndent()

        private val BIND_DOC = """
            query(${'$'}id: Int) {
              Media(id: ${'$'}id) {
                id siteUrl chapters status title { romaji english native }
                mediaListEntry { id status score progress }
              }
            }
        """.trimIndent()

        private val PUSH_DOC = """
            mutation(${'$'}mediaId: Int, ${'$'}status: MediaListStatus, ${'$'}progress: Int, ${'$'}score: Float) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, progress: ${'$'}progress, score: ${'$'}score) {
                id status score progress
              }
            }
        """.trimIndent()

        private fun extractToken(pasted: String): String? {
            val s = pasted.trim()
            if (!s.contains("access_token=")) return s.takeIf { it.isNotEmpty() }
            val normalized = s.replace("#", "&")
            val query = normalized.substringAfter("?", normalized)
            return query.split("&").firstOrNull { it.startsWith("access_token=") }?.removePrefix("access_token=")
        }

        private fun statusFromAniList(s: String): TrackStatus = when (s) {
            "CURRENT" -> TrackStatus.READING
            "PLANNING" -> TrackStatus.PLAN_TO_READ
            "COMPLETED" -> TrackStatus.COMPLETED
            "DROPPED" -> TrackStatus.DROPPED
            "PAUSED" -> TrackStatus.ON_HOLD
            "REPEATING" -> TrackStatus.REREADING
            else -> TrackStatus.PLAN_TO_READ
        }

        private fun statusToAniList(s: TrackStatus): String = when (s) {
            TrackStatus.READING -> "CURRENT"
            TrackStatus.PLAN_TO_READ -> "PLANNING"
            TrackStatus.COMPLETED -> "COMPLETED"
            TrackStatus.DROPPED -> "DROPPED"
            TrackStatus.ON_HOLD -> "PAUSED"
            TrackStatus.REREADING -> "REPEATING"
        }
    }
}
