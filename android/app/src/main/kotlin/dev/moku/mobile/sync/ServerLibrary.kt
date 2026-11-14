package dev.moku.mobile.sync

import android.content.Context
import android.util.Log
import dev.moku.mobile.backend.BackendMedia
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.library.FolderEntity
import dev.moku.mobile.library.LibraryMediaEntity
import dev.moku.mobile.library.LibraryStore
import dev.moku.mobile.library.LocalLibraryDatabase
import dev.moku.mobile.library.ReadingProgressDao
import dev.moku.mobile.library.ReadingProgressEntity
import dev.moku.mobile.remote.ServerSession
import dev.moku.mobile.remote.ServerUnreachableException
import dev.moku.mobile.remote.str
import dev.moku.mobile.remote.strOrNull
import dev.moku.mobile.ui.components.MokuToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/**
 * The server's library, read and written live. The last answer is kept only so the library still shows, downloads
 * first, while the server can't be reached.
 */
class ServerLibrary(context: Context) : LibraryStore {
    private val app = context.applicationContext
    private val progressDao = ReadingProgressDao(LocalLibraryDatabase.get(app))
    private val client get() = ServerSession.client

    // ---- library ----

    override suspend fun getLibrary(): List<LibraryMediaEntity> = libraryItems().mapNotNull { toEntity(it) }

    override suspend fun isInLibrary(originId: String, mediaId: String): Boolean {
        if (!ServerOrigin.isCurrent(originId)) return false
        return read(fallback = { snapshotItem(originId, mediaId) != null }) {
            val id = ServerSync.serverMediaId(app, originId, mediaId)
            client.query("query InLibrary(\$id: ID!) { media(id: \$id) { inLibrary } }", buildJsonObject { put("id", id) })["media"]
                ?.let { it as? JsonObject }?.get("inLibrary")?.jsonPrimitive?.contentOrNull == "true"
        }
    }

    override suspend fun addToLibrary(media: BackendMedia) = setInLibrary(media.originId, media.id, true)

    override suspend fun removeFromLibrary(originId: String, mediaId: String) = setInLibrary(originId, mediaId, false)

    private suspend fun setInLibrary(originId: String, mediaId: String, keep: Boolean) {
        write(if (keep) "add it to the library" else "remove it from the library", originId) {
            client.query(
                "mutation Keep(\$id: ID!, \$keep: Boolean!) { setInLibrary(mediaId: \$id, inLibrary: \$keep) { id } }",
                buildJsonObject { put("id", ServerSync.serverMediaId(app, originId, mediaId)); put("keep", keep) },
            )
        }
    }

    // The server stamps lastViewedAt itself when progress reaches it.
    override suspend fun touchLastViewed(originId: String, mediaId: String) = Unit

    // ---- folders ----

    override suspend fun getFolders(): List<FolderEntity> {
        val instance = ServerSession.current?.instanceId ?: return emptyList()
        val folders = read(fallback = { ServerSnapshot.load(app, instance, "folders")?.let(::parseArray) ?: JsonArray(emptyList()) }) {
            val data = client.query("query Folders { folders { id name kind sortOrder } }")["folders"]!!.jsonArray
            ServerSnapshot.save(app, instance, "folders", data.toString())
            data
        }
        return folders.map { it.jsonObject }
            .filter { it.strOrNull("kind") == "custom" }
            .map { FolderEntity(id = it.str("id").toLong(), name = it.str("name"), sortOrder = it.str("sortOrder").toInt()) }
            .sortedBy { it.sortOrder }
    }

    override suspend fun getFolderContents(folderId: Long): List<LibraryMediaEntity> =
        libraryItems().filter { item -> item.folderIds().contains(folderId) }.mapNotNull { toEntity(it) }

    override suspend fun foldersFor(originId: String, mediaId: String): Set<Long> {
        if (!ServerOrigin.isCurrent(originId)) return emptySet()
        return read(fallback = { snapshotItem(originId, mediaId)?.folderIds() ?: emptySet() }) {
            val id = ServerSync.serverMediaId(app, originId, mediaId)
            val media = client.query("query MediaFolders(\$id: ID!) { media(id: \$id) { folders { id } } }", buildJsonObject { put("id", id) })["media"]
            (media as? JsonObject)?.folderIds() ?: emptySet()
        }
    }

    override suspend fun createFolder(name: String): Long? = write("create the folder") {
        client.query("mutation Make(\$name: String!) { createFolder(name: \$name) { id } }", buildJsonObject { put("name", name) })["createFolder"]!!
            .jsonObject.str("id").toLong()
    }

    override suspend fun renameFolder(id: Long, name: String) {
        write("rename the folder") {
            client.query("mutation Rename(\$f: ID!, \$name: String!) { renameFolder(folderId: \$f, name: \$name) { id } }", buildJsonObject { put("f", id.toString()); put("name", name) })
        }
    }

    override suspend fun deleteFolder(id: Long) {
        write("delete the folder") {
            client.query("mutation Drop(\$f: ID!) { deleteFolder(folderId: \$f) }", buildJsonObject { put("f", id.toString()) })
        }
    }

    override suspend fun moveFolder(id: Long, delta: Int) {
        val folders = getFolders().toMutableList()
        val from = folders.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in folders.indices) return
        folders.add(to, folders.removeAt(from))
        write("move the folder") {
            folders.forEachIndexed { index, f ->
                if (f.sortOrder != index) {
                    client.query("mutation Order(\$f: ID!, \$o: Int!) { reorderFolder(folderId: \$f, sortOrder: \$o) { id } }", buildJsonObject { put("f", f.id.toString()); put("o", index) })
                }
            }
        }
    }

    override suspend fun addToFolder(originId: String, mediaId: String, folderId: Long) = folderMembership(originId, mediaId, folderId, add = true)

    override suspend fun removeFromFolder(originId: String, mediaId: String, folderId: Long) = folderMembership(originId, mediaId, folderId, add = false)

    private suspend fun folderMembership(originId: String, mediaId: String, folderId: Long, add: Boolean) {
        write(if (add) "add it to the folder" else "take it out of the folder", originId) {
            client.query(
                if (add) "mutation Put(\$m: ID!, \$f: ID!) { addMediaToFolder(mediaId: \$m, folderId: \$f) }" else "mutation Take(\$m: ID!, \$f: ID!) { removeMediaFromFolder(mediaId: \$m, folderId: \$f) }",
                buildJsonObject { put("m", ServerSync.serverMediaId(app, originId, mediaId)); put("f", folderId.toString()) },
            )
        }
    }

    // ---- progress ----

    override suspend fun updateProgress(
        originId: String,
        mediaId: String,
        unitId: String,
        progress: Float,
        completed: Boolean,
        positionSeconds: Double?,
        durationSeconds: Double?,
    ) {
        val entry = ReadingProgressEntity(originId, mediaId, unitId, progress, completed, positionSeconds, durationSeconds, System.currentTimeMillis())
        progressDao.upsert(entry)
        ServerSync.record(app, entry)
    }

    override suspend fun getProgress(originId: String, mediaId: String, unitId: String): ReadingProgressEntity? =
        progressDao.get(originId, mediaId, unitId)

    /** Takes in what other devices read first, when the server is there to ask. */
    override suspend fun getProgressForMedia(originId: String, mediaId: String): List<ReadingProgressEntity> {
        if (ServerOrigin.isCurrent(originId)) {
            runCatching {
                val id = ServerSync.serverMediaId(app, originId, mediaId)
                val data = client.query(
                    "query TitleProgress(\$mediaId: ID!) { readingProgress(mediaId: \$mediaId) { ${ServerSync.PROGRESS_FIELDS} } }",
                    buildJsonObject { put("mediaId", id) },
                )
                ServerSync.mergeProgress(app, originId, mediaId, data["readingProgress"]!!.jsonArray.map { it.jsonObject })
            }.onFailure { Log.i(TAG, "couldn't fetch progress for $originId/$mediaId: ${it.message}") }
        }
        return progressDao.forMedia(originId, mediaId)
    }

    override suspend fun nextUnread(originId: String, mediaId: String): ReadingProgressEntity? = progressDao.nextUnread(originId, mediaId)

    override suspend fun getProgressSince(since: Long): List<ReadingProgressEntity> =
        progressDao.getAllSince(since).filter { ServerOrigin.isCurrent(it.originId) }

    // ---- plumbing ----

    /** Every title on the server, which refreshes the snapshot; the snapshot answers when the server can't. */
    private suspend fun libraryItems(): List<JsonObject> {
        val instance = ServerSession.current?.instanceId ?: return emptyList()
        recent?.takeIf { it.instanceId == instance && System.currentTimeMillis() - it.at < MEMO_MS }?.let { return it.items }
        val items = read(fallback = { ServerSnapshot.load(app, instance, "library")?.let(::parseArray) ?: JsonArray(emptyList()) }) {
            val all = mutableListOf<JsonObject>()
            var offset = 0
            while (true) {
                val page = client.query(
                    """query Library(${'$'}offset: Int!) {
                        library(filter: { inLibrary: true }, limit: 100, offset: ${'$'}offset) {
                            hasMore
                            items {
                                id extensionId externalId contentType title thumbnailUrl description status author artist genres addedAt lastViewedAt
                                folders { id }
                                readingProgress { ${ServerSync.PROGRESS_FIELDS} }
                            }
                        }
                    }""",
                    buildJsonObject { put("offset", offset) },
                )["library"]!!.jsonObject
                val batch = page["items"]!!.jsonArray.map { it.jsonObject }
                all += batch
                if (page["hasMore"]?.jsonPrimitive?.contentOrNull != "true" || batch.isEmpty()) break
                offset += batch.size
            }
            for (item in all) {
                val origin = originOf(item) ?: continue
                val mediaId = item.str("externalId")
                ServerSync.rememberServerId(app, origin, mediaId, item.str("id"))
                ServerSync.mergeProgress(app, origin, mediaId, item["readingProgress"]!!.jsonArray.map { it.jsonObject })
            }
            JsonArray(all).also {
                ServerSnapshot.save(app, instance, "library", it.toString())
                recent = Memo(instance, System.currentTimeMillis(), all)
            }
        }
        return items.map { it.jsonObject }
    }

    private suspend fun snapshotItem(originId: String, mediaId: String): JsonObject? {
        val instance = ServerSession.current?.instanceId ?: return null
        val items = withContext(Dispatchers.IO) { ServerSnapshot.load(app, instance, "library") }?.let(::parseArray) ?: return null
        return items.map { it.jsonObject }.firstOrNull { originOf(it) == originId && it.str("externalId") == mediaId }
    }

    // Titles without an extension (the server's local files) have no source this phone can open them through.
    private fun originOf(item: JsonObject): String? {
        val instance = ServerSession.current?.instanceId ?: return null
        val extensionId = item.strOrNull("extensionId") ?: return null
        val type = MediaContentType.entries.firstOrNull { it.name == item.str("contentType").uppercase() } ?: return null
        return ServerOrigin.of(instance, type, extensionId)
    }

    private fun toEntity(item: JsonObject): LibraryMediaEntity? {
        val origin = originOf(item) ?: return null
        return LibraryMediaEntity(
            originId = origin,
            mediaId = item.str("externalId"),
            contentType = ServerOrigin.parse(origin)!!.type,
            title = item.str("title"),
            thumbnailUrl = ServerSession.absolute(item.strOrNull("thumbnailUrl")),
            description = item.strOrNull("description"),
            status = item.strOrNull("status"),
            authorsCsv = item.strOrNull("author").orEmpty(),
            artistsCsv = item.strOrNull("artist").orEmpty(),
            genresCsv = item["genres"]?.let { it as? JsonArray }?.joinToString(",") { it.jsonPrimitive.content }.orEmpty(),
            addedAt = ServerSync.millis(item.strOrNull("addedAt")) ?: 0L,
            lastViewedAt = ServerSync.millis(item.strOrNull("lastViewedAt")),
        )
    }

    private fun JsonObject.folderIds(): Set<Long> =
        (this["folders"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.strOrNull("id")?.toLongOrNull() }.toSet()

    // A snapshot cut short by the app being killed mid-write reads as empty rather than crashing the offline view.
    private fun parseArray(raw: String): JsonArray = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrElse { JsonArray(emptyList()) }

    /** Runs a server read, answering from [fallback] when the server can't give one. */
    private suspend fun <T> read(fallback: suspend () -> T, block: suspend () -> T): T {
        // While the server is known to be away, each read would otherwise wait out the connect timeout first;
        // the retry loop in ServerSync notices when it's back, and screens reload then.
        if (!ServerSession.reachable.value || ServerSync.status.value.needsSignIn) return withContext(Dispatchers.IO) { fallback() }
        return try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e !is ServerUnreachableException) Log.w(TAG, "server read failed", e)
            withContext(Dispatchers.IO) { fallback() }
        }
    }

    /** A failed write changes nothing anywhere and tells the user; another server's titles can't be changed at all. */
    private suspend fun <T> write(what: String, originId: String? = null, block: suspend () -> T): T? {
        if (originId != null && !ServerOrigin.isCurrent(originId)) {
            val name = ServerOrigin.parse(originId)?.instanceId?.let(ServerSession::nameOf) ?: "its server"
            tell("Sign in to $name to change this title")
            return null
        }
        recent = null
        return try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "server write failed: $what", e)
            tell(if (e is ServerUnreachableException) "${e.message}, so couldn't $what" else "Couldn't $what: ${e.message}")
            null
        }
    }

    private suspend fun tell(message: String) = withContext(Dispatchers.Main) { MokuToast.show(message, long = true) }

    // One screen load asks for the library several times (the grid, then each folder tab); one fetch answers them all.
    private class Memo(val instanceId: String, val at: Long, val items: List<JsonObject>)

    private companion object {
        const val MEMO_MS = 3_000L
        @Volatile var recent: Memo? = null
        const val TAG = "ServerLibrary"
    }
}
