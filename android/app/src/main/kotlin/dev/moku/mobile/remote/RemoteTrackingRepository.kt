package dev.moku.mobile.remote

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tracking through a Tsunagu server, which holds the tracker accounts. AniList's token comes back to the user to
 * paste in; MAL redirects to the server, so the app polls [getTrackers] until it's signed in.
 */
class RemoteTrackingRepository(private val client: TsunaguClient) {

    suspend fun getTrackers(): List<Tracker> {
        val data = client.query(
            """query Trackers {
                trackers { key name configured isLoggedIn authUrl username iconUrl }
            }""",
        )
        return data["trackers"]!!.jsonArray.map {
            val o = it.jsonObject
            Tracker(
                key = o.str("key"),
                name = o.str("name"),
                configured = o["configured"]!!.jsonPrimitive.contentOrNull == "true",
                isLoggedIn = o["isLoggedIn"]!!.jsonPrimitive.contentOrNull == "true",
                authUrl = o.strOrNull("authUrl"),
                username = o.strOrNull("username"),
                iconUrl = o.strOrNull("iconUrl"),
            )
        }
    }

    /** `token` is whatever the user pasted back after visiting a tracker's authUrl — see class kdoc. */
    suspend fun login(trackerKey: String, token: String): Tracker {
        val data = client.query(
            "mutation TrackerLogin(\$key: String!, \$token: String!) { trackerLogin(trackerKey: \$key, token: \$token) { key name isLoggedIn username } }",
            buildJsonObject { put("key", trackerKey); put("token", token) },
        )
        val o = data["trackerLogin"]!!.jsonObject
        return Tracker(
            key = o.str("key"), name = o.str("name"),
            configured = true, isLoggedIn = o["isLoggedIn"]!!.jsonPrimitive.contentOrNull == "true",
            authUrl = null, username = o.strOrNull("username"), iconUrl = null,
        )
    }

    suspend fun logout(trackerKey: String) {
        client.query(
            "mutation TrackerLogout(\$key: String!) { trackerLogout(trackerKey: \$key) }",
            buildJsonObject { put("key", trackerKey) },
        )
    }

    suspend fun search(trackerKey: String, query: String, contentType: String? = null): List<TrackSearchResult> {
        val data = client.query(
            """query TrackSearch(${'$'}key: String!, ${'$'}query: String!, ${'$'}contentType: ContentType) {
                trackSearch(trackerKey: ${'$'}key, query: ${'$'}query, contentType: ${'$'}contentType) {
                    remoteId title url coverUrl totalChapters publishingStatus
                }
            }""",
            buildJsonObject { put("key", trackerKey); put("query", query); contentType?.let { put("contentType", it) } },
        )
        return data["trackSearch"]!!.jsonArray.map {
            val o = it.jsonObject
            TrackSearchResult(remoteId = o.str("remoteId"), title = o.str("title"), url = o.str("url"), coverUrl = o.strOrNull("coverUrl"))
        }
    }

    suspend fun bindTrack(mediaId: String, trackerKey: String, remoteId: String): String {
        val data = client.query(
            "mutation BindTrack(\$mediaId: ID!, \$key: String!, \$remoteId: String!) { bindTrack(mediaId: \$mediaId, trackerKey: \$key, remoteId: \$remoteId) { id } }",
            buildJsonObject { put("mediaId", mediaId); put("key", trackerKey); put("remoteId", remoteId) },
        )
        return data["bindTrack"]!!.jsonObject.str("id")
    }

    suspend fun updateTrack(linkId: String, status: Int? = null, score: Float? = null, lastChapterRead: Float? = null) {
        client.query(
            """mutation UpdateTrack(${'$'}linkId: ID!, ${'$'}status: Int, ${'$'}score: Float, ${'$'}lastChapterRead: Float) {
                updateTrack(linkId: ${'$'}linkId, status: ${'$'}status, score: ${'$'}score, lastChapterRead: ${'$'}lastChapterRead) { id status score }
            }""",
            buildJsonObject {
                put("linkId", linkId)
                status?.let { put("status", it) }
                score?.let { put("score", it) }
                lastChapterRead?.let { put("lastChapterRead", it) }
            },
        )
    }

    suspend fun unbindTrack(linkId: String) {
        client.query(
            "mutation UnbindTrack(\$linkId: ID!) { unbindTrack(linkId: \$linkId) }",
            buildJsonObject { put("linkId", linkId) },
        )
    }

    suspend fun resyncTrack(linkId: String) {
        client.query(
            "mutation ResyncTrack(\$linkId: ID!) { resyncTrack(linkId: \$linkId) { id status score } }",
            buildJsonObject { put("linkId", linkId) },
        )
    }

    suspend fun pullTracker(mediaId: String) {
        client.query(
            "mutation PullTracker(\$mediaId: ID!) { pullTracker(mediaId: \$mediaId) { id status score } }",
            buildJsonObject { put("mediaId", mediaId) },
        )
    }

    suspend fun createTrackerStub(trackerKey: String, remoteId: String, contentType: String, title: String, coverUrl: String? = null): String {
        val data = client.query(
            """mutation CreateStub(${'$'}key: String!, ${'$'}remoteId: String!, ${'$'}contentType: ContentType!, ${'$'}title: String!, ${'$'}coverUrl: String) {
                createTrackerStub(trackerKey: ${'$'}key, remoteId: ${'$'}remoteId, contentType: ${'$'}contentType, title: ${'$'}title, coverUrl: ${'$'}coverUrl) { id }
            }""",
            buildJsonObject {
                put("key", trackerKey); put("remoteId", remoteId); put("contentType", contentType)
                put("title", title); coverUrl?.let { put("coverUrl", it) }
            },
        )
        return data["createTrackerStub"]!!.jsonObject.str("id")
    }

    suspend fun getTrackerLibrary(trackerKey: String, contentType: String, statuses: List<String>? = null): List<TrackerLibraryEntry> {
        val data = client.query(
            """query TrackerLibrary(${'$'}key: String!, ${'$'}contentType: ContentType!, ${'$'}statuses: [String!]) {
                trackerLibrary(trackerKey: ${'$'}key, contentType: ${'$'}contentType, statuses: ${'$'}statuses) {
                    remoteId title status progress coverUrl
                }
            }""",
            buildJsonObject {
                put("key", trackerKey); put("contentType", contentType)
                statuses?.let { put("statuses", buildJsonArray { it.forEach { s -> add(s) } }) }
            },
        )
        return data["trackerLibrary"]!!.jsonArray.map {
            val o = it.jsonObject
            TrackerLibraryEntry(remoteId = o.str("remoteId"), title = o.str("title"), status = o.strOrNull("status"))
        }
    }
}

data class Tracker(
    val key: String,
    val name: String,
    val configured: Boolean,
    val isLoggedIn: Boolean,
    val authUrl: String?,
    val username: String?,
    val iconUrl: String?,
)

data class TrackSearchResult(val remoteId: String, val title: String, val url: String, val coverUrl: String?)
data class TrackerLibraryEntry(val remoteId: String, val title: String, val status: String?)
