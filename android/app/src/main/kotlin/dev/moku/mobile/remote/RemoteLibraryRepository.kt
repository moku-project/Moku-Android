package dev.moku.mobile.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Library/folders/reading-progress against a connected Tsunagu server's own GraphQL API —
 * the remote counterpart of [dev.moku.mobile.library.LocalLibraryRepository]. There is
 * nothing to persist on-device here: the server already owns this state (its own DB), so
 * every method here is a thin GraphQL call, not a cache. See [dev.moku.mobile.backend
 * .ContentBackend]'s kdoc for why local and remote are never merged into one library.
 */
class RemoteLibraryRepository(private val client: TsunaguClient) {

    // ---- library ----

    suspend fun setInLibrary(mediaId: String, inLibrary: Boolean): RemoteMedia {
        val data = client.query(
            """mutation SetInLibrary(${'$'}mediaId: ID!, ${'$'}inLibrary: Boolean!) {
                setInLibrary(mediaId: ${'$'}mediaId, inLibrary: ${'$'}inLibrary) { ${MEDIA_FIELDS} }
            }""",
            buildJsonObject { put("mediaId", mediaId); put("inLibrary", inLibrary) },
        )
        return data["setInLibrary"]!!.jsonObject.toRemoteMedia()
    }

    suspend fun getLibrary(inLibrary: Boolean = true, contentType: String? = null): List<RemoteMedia> {
        val data = client.query(
            """query Library(${'$'}filter: LibraryFilter) {
                library(filter: ${'$'}filter) { items { ${MEDIA_FIELDS} } total hasMore }
            }""",
            buildJsonObject {
                put(
                    "filter",
                    buildJsonObject {
                        put("inLibrary", inLibrary)
                        contentType?.let { put("contentType", it) }
                    },
                )
            },
        )
        return data["library"]!!.jsonObject["items"]!!.jsonArray.map { it.jsonObject.toRemoteMedia() }
    }

    // ---- folders ----

    suspend fun getFolders(): List<RemoteFolder> {
        val data = client.query("query Folders { folders { id name kind sortOrder } }")
        return data["folders"]!!.jsonArray.map {
            val o = it.jsonObject
            RemoteFolder(id = o.str("id"), name = o.str("name"), kind = o.str("kind"))
        }
    }

    suspend fun createFolder(name: String): RemoteFolder {
        val data = client.query(
            "mutation CreateFolder(\$name: String!) { createFolder(name: \$name) { id name kind sortOrder } }",
            buildJsonObject { put("name", name) },
        )
        val o = data["createFolder"]!!.jsonObject
        return RemoteFolder(id = o.str("id"), name = o.str("name"), kind = o.str("kind"))
    }

    suspend fun deleteFolder(folderId: String) {
        client.query(
            "mutation DeleteFolder(\$id: ID!) { deleteFolder(folderId: \$id) }",
            buildJsonObject { put("id", folderId) },
        )
    }

    suspend fun addMediaToFolder(mediaId: String, folderId: String) {
        client.query(
            "mutation AddToFolder(\$mediaId: ID!, \$folderId: ID!) { addMediaToFolder(mediaId: \$mediaId, folderId: \$folderId) }",
            buildJsonObject { put("mediaId", mediaId); put("folderId", folderId) },
        )
    }

    suspend fun removeMediaFromFolder(mediaId: String, folderId: String) {
        client.query(
            "mutation RemoveFromFolder(\$mediaId: ID!, \$folderId: ID!) { removeMediaFromFolder(mediaId: \$mediaId, folderId: \$folderId) }",
            buildJsonObject { put("mediaId", mediaId); put("folderId", folderId) },
        )
    }

    suspend fun getFolderContents(folderId: String): List<RemoteMedia> {
        val data = client.query(
            "query MediaInFolder(\$folderId: ID!) { mediaInFolder(folderId: \$folderId) { ${MEDIA_FIELDS} } }",
            buildJsonObject { put("folderId", folderId) },
        )
        return data["mediaInFolder"]!!.jsonArray.map { it.jsonObject.toRemoteMedia() }
    }

    // ---- reading/watch progress ----

    suspend fun updateReadingProgress(
        mediaId: String,
        chapterId: String,
        progress: Float,
        completed: Boolean? = null,
        positionSeconds: Double? = null,
        durationSeconds: Double? = null,
    ) {
        client.query(
            """mutation UpdateProgress(${'$'}mediaId: ID!, ${'$'}chapterId: ID!, ${'$'}progress: Float!, ${'$'}completed: Boolean, ${'$'}positionSeconds: Float, ${'$'}durationSeconds: Float) {
                updateReadingProgress(mediaId: ${'$'}mediaId, chapterId: ${'$'}chapterId, progress: ${'$'}progress, completed: ${'$'}completed, positionSeconds: ${'$'}positionSeconds, durationSeconds: ${'$'}durationSeconds) { id progress completed }
            }""",
            buildJsonObject {
                put("mediaId", mediaId)
                put("chapterId", chapterId)
                put("progress", progress)
                completed?.let { put("completed", it) }
                positionSeconds?.let { put("positionSeconds", it) }
                durationSeconds?.let { put("durationSeconds", it) }
            },
        )
    }

    suspend fun markChapterRead(mediaId: String, chapterId: String) {
        client.query(
            "mutation MarkRead(\$mediaId: ID!, \$chapterId: ID!) { markChapterRead(mediaId: \$mediaId, chapterId: \$chapterId) { id completed } }",
            buildJsonObject { put("mediaId", mediaId); put("chapterId", chapterId) },
        )
    }

    suspend fun markChaptersRead(mediaId: String, chapterIds: List<String>, read: Boolean) {
        client.query(
            "mutation MarkChaptersRead(\$mediaId: ID!, \$chapterIds: [ID!]!, \$read: Boolean!) { markChaptersRead(mediaId: \$mediaId, chapterIds: \$chapterIds, read: \$read) { id completed } }",
            buildJsonObject {
                put("mediaId", mediaId)
                put("chapterIds", buildJsonArray { chapterIds.forEach { add(it) } })
                put("read", read)
            },
        )
    }

    suspend fun getReadingProgress(mediaId: String): List<RemoteProgress> {
        val data = client.query(
            "query Progress(\$mediaId: ID!) { readingProgress(mediaId: \$mediaId) { chapterId progress completed positionSeconds durationSeconds } }",
            buildJsonObject { put("mediaId", mediaId) },
        )
        return data["readingProgress"]!!.jsonArray.map {
            val o = it.jsonObject
            RemoteProgress(
                chapterId = o.str("chapterId"),
                progress = o["progress"]!!.jsonPrimitive.content.toFloat(),
                completed = o["completed"]!!.jsonPrimitive.contentOrNull == "true",
            )
        }
    }

    private fun JsonObject.toRemoteMedia() = RemoteMedia(
        id = str("id"),
        title = str("title"),
        thumbnailUrl = strOrNull("thumbnailUrl"),
        inLibrary = this["inLibrary"]?.jsonPrimitive?.contentOrNull == "true",
    )

    private companion object {
        // A subset of Media's fields — enough for a library grid, not the full detail view
        // RemoteTsunaguBackend.mediaDetails() already covers.
        const val MEDIA_FIELDS = "id title thumbnailUrl inLibrary"
    }
}

data class RemoteMedia(val id: String, val title: String, val thumbnailUrl: String?, val inLibrary: Boolean)
data class RemoteFolder(val id: String, val name: String, val kind: String)
data class RemoteProgress(val chapterId: String, val progress: Float, val completed: Boolean)
