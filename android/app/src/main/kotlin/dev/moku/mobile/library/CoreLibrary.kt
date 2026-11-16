package dev.moku.mobile.library

import android.content.Context
import android.util.Log
import dev.moku.mobile.backend.BackendMedia
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.core.Core
import dev.moku.mobile.core.CoreExtensions
import dev.moku.mobile.remote.str
import dev.moku.mobile.remote.strOrNull
import dev.moku.mobile.sync.ServerSync
import dev.moku.mobile.ui.components.MokuToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

// Titles keep their phone IDs (`local:<type>:<package>` and the source URL) so the core and the phone agree on them.
// [strict] rethrows failed writes instead of telling the user, for callers that retry on their own.
class CoreLibrary(context: Context, private val strict: Boolean = false) : LibraryStore {
    private val app = context.applicationContext

    private suspend fun q(query: String, variables: JsonObject? = null) = Core.query(app, query, variables)


    override suspend fun getLibrary(): List<LibraryMediaEntity> = libraryItems().mapNotNull { toEntity(it) }

    override suspend fun isInLibrary(originId: String, mediaId: String): Boolean =
        read(false) { libraryItems().any { originOf(it) == originId && it.str("externalId") == mediaId } }

    override suspend fun addToLibrary(media: BackendMedia) = setInLibrary(media.originId, media.id, true)

    override suspend fun removeFromLibrary(originId: String, mediaId: String) = setInLibrary(originId, mediaId, false)

    private suspend fun setInLibrary(originId: String, mediaId: String, keep: Boolean) {
        write(if (keep) "add it to the library" else "remove it from the library") {
            val id = coreId(originId, mediaId, create = keep) ?: return@write
            q("mutation Keep(\$id: ID!, \$keep: Boolean!) { setInLibrary(mediaId: \$id, inLibrary: \$keep) { id } }",
                buildJsonObject { put("id", id); put("keep", keep) })
        }
    }

    // Asking the core for the title is what stamps it as viewed.
    override suspend fun touchLastViewed(originId: String, mediaId: String) {
        read(Unit) {
            val id = coreId(originId, mediaId, create = false) ?: return@read
            q("query Viewed(\$m: ID!) { media(id: \$m) { id } }", buildJsonObject { put("m", id) })
            recent = null
        }
    }


    override suspend fun getFolders(): List<FolderEntity> = read(emptyList()) {
        q("query Folders { folders { id name kind sortOrder } }")["folders"]!!.jsonArray.map { it.jsonObject }
            .filter { it.strOrNull("kind") == "custom" }
            .map { FolderEntity(id = it.str("id").toLong(), name = it.str("name"), sortOrder = it.str("sortOrder").toInt()) }
            .sortedBy { it.sortOrder }
    }

    override suspend fun getFolderContents(folderId: Long): List<LibraryMediaEntity> =
        libraryItems().filter { folderId in it.folderIds() }.mapNotNull { toEntity(it) }

    override suspend fun foldersFor(originId: String, mediaId: String): Set<Long> =
        libraryItems().firstOrNull { originOf(it) == originId && it.str("externalId") == mediaId }?.folderIds() ?: emptySet()

    override suspend fun createFolder(name: String): Long? = write("create the folder") {
        q("mutation Make(\$name: String!) { createFolder(name: \$name) { id } }", buildJsonObject { put("name", name) })["createFolder"]!!
            .jsonObject.str("id").toLong()
    }

    override suspend fun renameFolder(id: Long, name: String) {
        write("rename the folder") {
            q("mutation Rename(\$f: ID!, \$name: String!) { renameFolder(folderId: \$f, name: \$name) { id } }",
                buildJsonObject { put("f", id.toString()); put("name", name) })
        }
    }

    override suspend fun deleteFolder(id: Long) {
        write("delete the folder") { q("mutation Drop(\$f: ID!) { deleteFolder(folderId: \$f) }", buildJsonObject { put("f", id.toString()) }) }
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
                    q("mutation Order(\$f: ID!, \$o: Int!) { reorderFolder(folderId: \$f, sortOrder: \$o) { id } }",
                        buildJsonObject { put("f", f.id.toString()); put("o", index) })
                }
            }
        }
    }

    override suspend fun addToFolder(originId: String, mediaId: String, folderId: Long) = folderMembership(originId, mediaId, folderId, add = true)

    override suspend fun removeFromFolder(originId: String, mediaId: String, folderId: Long) = folderMembership(originId, mediaId, folderId, add = false)

    private suspend fun folderMembership(originId: String, mediaId: String, folderId: Long, add: Boolean) {
        write(if (add) "add it to the folder" else "take it out of the folder") {
            val id = coreId(originId, mediaId, create = add) ?: return@write
            q(if (add) "mutation Put(\$m: ID!, \$f: ID!) { addMediaToFolder(mediaId: \$m, folderId: \$f) }"
              else "mutation Take(\$m: ID!, \$f: ID!) { removeMediaFromFolder(mediaId: \$m, folderId: \$f) }",
                buildJsonObject { put("m", id); put("f", folderId.toString()) })
        }
    }


    override suspend fun updateProgress(
        originId: String,
        mediaId: String,
        unitId: String,
        progress: Float,
        completed: Boolean,
        positionSeconds: Double?,
        durationSeconds: Double?,
        page: Int?,
    ) {
        write("save your progress", quiet = true) {
            val id = coreId(originId, mediaId, create = true) ?: return@write
            val chapter = chapterId(id, originId, mediaId, unitId) ?: return@write
            // Saving progress never clears "read" on the core, so marking unread has its own call.
            if (!completed && progress <= 0f) {
                q("mutation Unread(\$m: ID!, \$c: [ID!]!) { markChaptersRead(mediaId: \$m, chapterIds: \$c, read: false) { id } }",
                    buildJsonObject { put("m", id); put("c", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(chapter)) }) })
                return@write
            }
            q("""mutation Save(${'$'}m: ID!, ${'$'}c: ID!, ${'$'}p: Float!, ${'$'}done: Boolean, ${'$'}s: Float, ${'$'}d: Float${if (page != null) ", ${'$'}page: Int" else ""}) {
                    updateReadingProgress(mediaId: ${'$'}m, chapterId: ${'$'}c, progress: ${'$'}p, completed: ${'$'}done, positionSeconds: ${'$'}s, durationSeconds: ${'$'}d${if (page != null) ", page: ${'$'}page" else ""}) { id }
                }""",
                buildJsonObject {
                    put("m", id); put("c", chapter); put("p", progress.toDouble()); put("done", completed)
                    positionSeconds?.let { put("s", it) }
                    durationSeconds?.let { put("d", it) }
                    page?.let { put("page", it) }
                })
        }
    }

    override suspend fun getProgress(originId: String, mediaId: String, unitId: String): ReadingProgressEntity? =
        getProgressForMedia(originId, mediaId).firstOrNull { it.unitId == unitId }

    override suspend fun getProgressForMedia(originId: String, mediaId: String): List<ReadingProgressEntity> = read(emptyList()) {
        val id = coreId(originId, mediaId, create = false) ?: return@read emptyList()
        val rows = q("query Progress(\$m: ID!) { readingProgress(mediaId: \$m) { $PROGRESS } }", buildJsonObject { put("m", id) })["readingProgress"]!!
            .jsonArray.map { it.jsonObject }
        toProgress(originId, mediaId, id, rows)
    }

    override suspend fun nextUnread(originId: String, mediaId: String): ReadingProgressEntity? =
        getProgressForMedia(originId, mediaId).filter { !it.completed }.maxByOrNull { it.updatedAt }

    override suspend fun getProgressSince(since: Long): List<ReadingProgressEntity> = read(emptyList()) {
        libraryItems().flatMap { item ->
            val origin = originOf(item) ?: return@flatMap emptyList()
            toProgress(origin, item.str("externalId"), item.str("id"), item["readingProgress"]!!.jsonArray.map { it.jsonObject })
        }.filter { it.updatedAt >= since }
    }


    /** Saves a finished reading session; the core turns these into history and stats and syncs them. */
    suspend fun recordSession(s: ReadingSession) {
        write("save the reading session", quiet = true) {
            val id = coreId(s.originId, s.mediaId, create = true) ?: return@write
            val start = chapterId(id, s.originId, s.mediaId, s.startUnitId)
            val end = chapterId(id, s.originId, s.mediaId, s.endUnitId)
            q("mutation Session(\$in: ReadingSessionInput!) { recordReadingSession(input: \$in) { id } }", buildJsonObject {
                put("in", buildJsonObject {
                    put("id", s.id); put("mediaId", id)
                    start?.let { put("startChapterId", it) }
                    end?.let { put("endChapterId", it) }
                    put("startPage", s.startPage); put("endPage", s.endPage)
                    put("startedAt", java.time.Instant.ofEpochMilli(s.startedAt).toString())
                    put("endedAt", java.time.Instant.ofEpochMilli(s.endedAt).toString())
                    put("durationMs", (s.endedAt - s.startedAt).coerceAtMost(SESSION_CAP_MS).toInt())
                    put("chapters", s.units.coerceAtLeast(1))
                })
            })
        }
    }


    private suspend fun toProgress(originId: String, mediaId: String, coreId: String, rows: List<JsonObject>): List<ReadingProgressEntity> {
        if (rows.isEmpty()) return emptyList()
        val units = chapters(coreId).entries.associate { (unit, chapter) -> chapter to unit }
        return rows.mapNotNull { row ->
            val unit = units[row.str("chapterId")] ?: return@mapNotNull null
            ReadingProgressEntity(
                originId = originId,
                mediaId = mediaId,
                unitId = unit,
                progress = row.str("progress").toFloat(),
                completed = row.str("completed") == "true",
                positionSeconds = row.strOrNull("positionSeconds")?.toDouble(),
                durationSeconds = row.strOrNull("durationSeconds")?.toDouble(),
                updatedAt = ServerSync.millis(row.strOrNull("updatedAt")) ?: 0L,
            )
        }
    }

    /** The core's ID for a phone title. With [create], a title the core doesn't have yet is fetched into it. */
    private suspend fun coreId(originId: String, mediaId: String, create: Boolean): String? {
        val key = "$originId\u001f$mediaId"
        mediaIds[key]?.let { return it }
        libraryItems()
        mediaIds[key]?.let { return it }
        if (!create) return null
        val (type, packageName) = parseOrigin(originId) ?: return null
        val extension = CoreExtensions.id(app, packageName, type)
        val media = q("query Resolve(\$e: ID!, \$x: String!) { resolveMedia(extensionId: \$e, externalId: \$x) { id chapters { id externalId } } }",
            buildJsonObject { put("e", extension); put("x", mediaId) })["resolveMedia"]!!.jsonObject
        val id = media.str("id")
        mediaIds[key] = id
        chapterIds[id] = media.chapterMap()
        return id
    }

    /** Phone unit ID (the chapter's source URL) to the core's chapter ID. */
    // Read from what the core stored; media(id) would also mark the title as viewed.
    private suspend fun chapters(coreId: String): Map<String, String> =
        chapterIds[coreId] ?: q("query Chapters(\$m: ID!) { chapters(mediaId: \$m) { id externalId } }", buildJsonObject { put("m", coreId) })
            .chapterMap().also { chapterIds[coreId] = it }

    // A chapter the source added since the core last looked needs the core to fetch the list again.
    private suspend fun chapterId(coreId: String, originId: String, mediaId: String, unitId: String): String? {
        chapters(coreId)[unitId]?.let { return it }
        val (type, packageName) = parseOrigin(originId) ?: return null
        val media = q("query Resolve(\$e: ID!, \$x: String!) { resolveMedia(extensionId: \$e, externalId: \$x, syncChapters: true) { chapters { id externalId } } }",
            buildJsonObject { put("e", CoreExtensions.id(app, packageName, type)); put("x", mediaId) })["resolveMedia"]!!.jsonObject
        return media.chapterMap().also { chapterIds[coreId] = it }[unitId]
    }

    private fun JsonObject.chapterMap(): Map<String, String> =
        (this["chapters"] as? JsonArray).orEmpty().map { it.jsonObject }.associate { it.str("externalId") to it.str("id") }

    private suspend fun libraryItems(): List<JsonObject> {
        recent?.takeIf { System.currentTimeMillis() - it.at < MEMO_MS }?.let { return it.items }
        val all = mutableListOf<JsonObject>()
        var offset = 0
        while (true) {
            val page = q(
                """query Library(${'$'}offset: Int!) {
                    library(filter: { inLibrary: true, contentFilter: false }, limit: 100, offset: ${'$'}offset) {
                        hasMore
                        items {
                            id externalId contentType title thumbnailUrl description status author artist genres addedAt lastViewedAt
                            source { packageName }
                            folders { id }
                            readingProgress { $PROGRESS }
                        }
                    }
                }""",
                buildJsonObject { put("offset", offset) },
            )["library"]!!.jsonObject
            val batch = page["items"]!!.jsonArray.map { it.jsonObject }
            all += batch
            if (page["hasMore"]?.jsonPrimitive?.content != "true" || batch.isEmpty()) break
            offset += batch.size
        }
        for (item in all) originOf(item)?.let { mediaIds["$it\u001f${item.str("externalId")}"] = item.str("id") }
        recent = Memo(System.currentTimeMillis(), all)
        return all
    }

    // Titles with no source, like the core's local files, have nothing this phone can open them through.
    private fun originOf(item: JsonObject): String? {
        val packageName = (item["source"] as? JsonObject)?.strOrNull("packageName") ?: return null
        val type = MediaContentType.entries.firstOrNull { it.name == item.str("contentType").uppercase() } ?: return null
        return "local:${type.name.lowercase()}:$packageName"
    }

    private fun parseOrigin(originId: String): Pair<MediaContentType, String>? {
        val parts = originId.split(":", limit = 3)
        if (parts.size != 3 || parts[0] != "local") return null
        val type = MediaContentType.entries.firstOrNull { it.name.lowercase() == parts[1] } ?: return null
        return type to parts[2]
    }

    private fun toEntity(item: JsonObject): LibraryMediaEntity? {
        val origin = originOf(item) ?: return null
        return LibraryMediaEntity(
            originId = origin,
            mediaId = item.str("externalId"),
            contentType = parseOrigin(origin)!!.first,
            title = item.str("title"),
            thumbnailUrl = Core.absolute(item.strOrNull("thumbnailUrl")),
            description = item.strOrNull("description"),
            status = item.strOrNull("status"),
            authorsCsv = item.strOrNull("author").orEmpty(),
            artistsCsv = item.strOrNull("artist").orEmpty(),
            genresCsv = (item["genres"] as? JsonArray)?.joinToString(",") { it.jsonPrimitive.content }.orEmpty(),
            addedAt = ServerSync.millis(item.strOrNull("addedAt")) ?: 0L,
            lastViewedAt = ServerSync.millis(item.strOrNull("lastViewedAt")),
        )
    }

    private fun JsonObject.folderIds(): Set<Long> =
        (this["folders"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.strOrNull("id")?.toLongOrNull() }.toSet()

    private suspend fun <T> read(fallback: T, block: suspend () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "core read failed", e)
        fallback
    }

    /** A failed write tells the user; [quiet] ones (progress, saved every few pages) only log. */
    private suspend fun <T> write(what: String, quiet: Boolean = false, block: suspend () -> T): T? {
        recent = null
        return try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (strict) throw e
            Log.w(TAG, "core write failed: $what", e)
            if (!quiet) withContext(Dispatchers.Main) { MokuToast.show("Couldn't $what: ${e.message}", long = true) }
            null
        }
    }

    private class Memo(val at: Long, val items: List<JsonObject>)

    private companion object {
        const val TAG = "CoreLibrary"
        const val MEMO_MS = 3_000L
        const val SESSION_CAP_MS = 3_600_000L
        const val PROGRESS = "chapterId progress completed positionSeconds durationSeconds updatedAt"
        @Volatile var recent: Memo? = null
        val mediaIds = ConcurrentHashMap<String, String>()
        val chapterIds = ConcurrentHashMap<String, Map<String, String>>()
    }
}
