package dev.moku.mobile.sync

import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.remote.ServerSession
import dev.moku.mobile.remote.str
import dev.moku.mobile.remote.strOrNull
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException

/** A chapter in the server's download queue, or already downloaded there. */
data class ServerDownload(
    val mediaId: String,
    val chapterId: String,
    val mediaTitle: String,
    val chapterTitle: String?,
    val chapterNumber: Float?,
    val status: String,
    val progress: Float,
    val error: String?,
)

data class ServerTitleDownloads(val originId: String, val mediaId: String, val title: String, val thumbnailUrl: String?, val count: Int)

data class ServerDownloader(val running: Boolean, val queued: Int, val downloading: Int, val failed: Int)

/** The server couldn't download a chapter, so the phone has nothing to copy. */
class ServerDownloadFailed(message: String) : IOException(message)

/** The server's downloads. A download to the phone goes through the server first, then the phone copies the files. */
object ServerDownloads {
    private val client get() = ServerSession.client

    /** True once the server has the chapter, queueing it there if needed; false if [timeoutMs] ran out first. */
    suspend fun ensureOnServer(mediaId: String, chapterId: String, timeoutMs: Long): Boolean {
        when (status(mediaId, chapterId)?.status) {
            "DONE" -> return true
            "FAILED" -> client.query(
                "mutation Retry(\$m: ID!, \$c: ID!) { retryDownload(mediaId: \$m, chapterId: \$c) { id } }",
                buildJsonObject { put("m", mediaId); put("c", chapterId) },
            )
            null -> enqueue(mediaId, listOf(chapterId))
        }
        if (!downloader().running) {
            val name = ServerSession.current?.name ?: "the server"
            throw ServerDownloadFailed("Downloads are paused on $name. Start them under Downloads, On server.")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_MS)
            val now = status(mediaId, chapterId) ?: throw ServerDownloadFailed("The server dropped it from its queue")
            when (now.status) {
                "DONE" -> return true
                "FAILED" -> throw ServerDownloadFailed(now.error ?: "The server couldn't download it")
            }
        }
        return false
    }

    suspend fun queue(): List<ServerDownload> {
        val items = client.query("query Queue { downloadQueue { $FIELDS } }")["downloadQueue"]!!.jsonArray.map { it.jsonObject.toDownload() }
        val titles = titles(items.map { it.mediaId }.distinct())
        return items.map { it.copy(mediaTitle = titles[it.mediaId].orEmpty()) }
    }

    // Chapters don't carry their title's name, so every title in the queue is looked up in one request.
    private suspend fun titles(mediaIds: List<String>): Map<String, String> {
        if (mediaIds.isEmpty()) return emptyMap()
        val fields = mediaIds.mapIndexed { i, id -> "m$i: media(id: \"${id.filter(Char::isDigit)}\") { title }" }.joinToString(" ")
        val data = client.query("query Titles { $fields }")
        return mediaIds.mapIndexedNotNull { i, id -> (data["m$i"] as? JsonObject)?.strOrNull("title")?.let { id to it } }.toMap()
    }

    suspend fun downloader(): ServerDownloader {
        val o = client.query("query Downloader { downloaderStatus { isRunning queuedCount downloadingCount failedCount } }")["downloaderStatus"]!!.jsonObject
        return ServerDownloader(o.bool("isRunning"), o.int("queuedCount"), o.int("downloadingCount"), o.int("failedCount"))
    }

    /** Ids of the chapters of [mediaId] the server has downloaded. */
    suspend fun downloadedChapters(mediaId: String): Set<String> {
        val media = client.query("query Downloaded(\$id: ID!) { media(id: \$id) { chapters { id downloaded } } }", buildJsonObject { put("id", mediaId) })["media"]
        return (media as? JsonObject)?.get("chapters")?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { it.bool("downloaded") }
            .map { it.str("id") }
            .toSet()
    }


    /** Library titles with chapters downloaded on the server, for opening them from the Downloads tab. */
    suspend fun downloadedTitles(): List<ServerTitleDownloads> {
        val instance = ServerSession.current?.instanceId ?: return emptyList()
        val items = client.query(
            "query DownloadedTitles { library(filter: { downloadedOnly: true }, limit: 500) { items { extensionId externalId contentType title thumbnailUrl downloadedCount } } }",
        )["library"]!!.jsonObject["items"]!!.jsonArray.map { it.jsonObject }
        return items.mapNotNull { o ->
            val extensionId = o.strOrNull("extensionId") ?: return@mapNotNull null
            val type = MediaContentType.entries.firstOrNull { it.name == o.str("contentType").uppercase() } ?: return@mapNotNull null
            ServerTitleDownloads(
                originId = ServerOrigin.of(instance, type, extensionId),
                mediaId = o.str("externalId"),
                title = o.str("title"),
                thumbnailUrl = ServerSession.absolute(o.strOrNull("thumbnailUrl")),
                count = o.int("downloadedCount"),
            )
        }
    }
    suspend fun enqueue(mediaId: String, chapterIds: List<String>) {
        client.query(
            "mutation Enqueue(\$m: ID!, \$c: [ID!]!) { enqueueDownload(mediaId: \$m, chapterIds: \$c) { id } }",
            buildJsonObject { put("m", mediaId); put("c", chapterIds.toJson()) },
        )
    }

    suspend fun delete(mediaId: String, chapterIds: List<String>) {
        client.query(
            "mutation Delete(\$m: ID!, \$c: [ID!]!) { deleteDownload(mediaId: \$m, chapterIds: \$c) }",
            buildJsonObject { put("m", mediaId); put("c", chapterIds.toJson()) },
        )
    }

    suspend fun retry(mediaId: String, chapterId: String) {
        client.query("mutation Retry(\$m: ID!, \$c: ID!) { retryDownload(mediaId: \$m, chapterId: \$c) { id } }", buildJsonObject { put("m", mediaId); put("c", chapterId) })
    }

    suspend fun setRunning(running: Boolean) {
        client.query(if (running) "mutation Start { startDownloader }" else "mutation Stop { stopDownloader }")
    }

    suspend fun clearFinished() {
        client.query("mutation Clear { clearDownloads(status: [DONE, FAILED]) }")
    }

    private suspend fun status(mediaId: String, chapterId: String): ServerDownload? {
        val d = client.query(
            "query Status(\$m: ID!, \$c: ID!) { downloadStatus(mediaId: \$m, chapterId: \$c) { $FIELDS } }",
            buildJsonObject { put("m", mediaId); put("c", chapterId) },
        )["downloadStatus"]
        return (d as? JsonObject)?.toDownload()
    }

    private fun JsonObject.toDownload(): ServerDownload {
        val chapter = this["chapter"]!!.jsonObject
        return ServerDownload(
            mediaId = str("mediaId"),
            chapterId = str("chapterId"),
            mediaTitle = "",
            chapterTitle = chapter.strOrNull("title"),
            chapterNumber = chapter.strOrNull("number")?.toFloatOrNull(),
            status = str("status"),
            progress = strOrNull("progress")?.toFloatOrNull() ?: 0f,
            error = strOrNull("error"),
        )
    }

    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.contentOrNull == "true"
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
    private fun List<String>.toJson() = buildJsonArray { forEach { add(it) } }

    private const val POLL_MS = 4_000L
    private const val FIELDS = "mediaId chapterId status progress error chapter { title number }"
}
