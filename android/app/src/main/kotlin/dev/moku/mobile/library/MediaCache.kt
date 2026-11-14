package dev.moku.mobile.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import coil.Coil
import coil.request.ImageRequest
import dev.moku.mobile.backend.BackendMedia
import dev.moku.mobile.backend.BackendUnit
import dev.moku.mobile.backend.LocalBackendFactory
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.repository.LocalRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CachedMedia(val media: BackendMedia, val units: List<BackendUnit>, val sourceName: String?, val updatedAt: Long)

/** Last known details and unit list per title, so library titles open and read offline. */
class MediaCache(private val context: Context) {
    private val db = LocalLibraryDatabase.get(context)

    suspend fun get(originId: String, mediaId: String): CachedMedia? = withContext(Dispatchers.IO) {
        db.readableDatabase.query("media_cache", null, "originId = ? AND mediaId = ?", arrayOf(originId, mediaId), null, null, null).use { c ->
            if (!c.moveToFirst()) return@use null
            runCatching {
                CachedMedia(
                    media = JSONObject(c.getStringOrNull("mediaJson")!!).toMedia(),
                    units = JSONArray(c.getStringOrNull("unitsJson")!!).let { a -> (0 until a.length()).map { a.getJSONObject(it).toUnit() } },
                    sourceName = c.getStringOrNull("sourceName"),
                    updatedAt = c.getLongOrNull("updatedAt") ?: 0L,
                )
            }.onFailure { Log.w("MediaCache", "dropping unreadable cache row for $originId/$mediaId", it) }.getOrNull()
        }
    }

    suspend fun put(media: BackendMedia, units: List<BackendUnit>, sourceName: String?) = withContext(Dispatchers.IO) {
        val w = db.writableDatabase
        w.insertWithOnConflict(
            "media_cache", null,
            ContentValues().apply {
                put("originId", media.originId)
                put("mediaId", media.id)
                put("mediaJson", media.toJson().toString())
                put("unitsJson", JSONArray().also { a -> units.forEach { a.put(it.toJson()) } }.toString())
                put("sourceName", sourceName)
                put("updatedAt", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        // Keep the library card in step with the source without touching addedAt or folder membership.
        w.update(
            "library_media",
            ContentValues().apply {
                put("title", media.title)
                put("thumbnailUrl", media.thumbnailUrl)
                put("description", media.description)
                put("status", media.status)
                put("authorsCsv", media.authors.joinToString(","))
                put("artistsCsv", media.artists.joinToString(","))
                put("genresCsv", media.genres.joinToString(","))
            },
            "originId = ? AND mediaId = ?",
            arrayOf(media.originId, media.id),
        )
        Unit
    }

    suspend fun delete(originId: String, mediaId: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("media_cache", "originId = ? AND mediaId = ?", arrayOf(originId, mediaId))
        Unit
    }

    /** Drops cache rows for titles that aren't in the library and haven't been opened for [maxAgeMillis]. */
    suspend fun prune(maxAgeMillis: Long = 14L * 24 * 3600 * 1000) = withContext(Dispatchers.IO) {
        db.writableDatabase.execSQL(
            """DELETE FROM media_cache WHERE updatedAt < ? AND NOT EXISTS (
                SELECT 1 FROM library_media l WHERE l.originId = media_cache.originId AND l.mediaId = media_cache.mediaId
            )""",
            arrayOf(System.currentTimeMillis() - maxAgeMillis),
        )
    }

    /** Fetches fresh details for [originId]/[mediaId] from its source and stores them. */
    suspend fun refresh(originId: String, mediaId: String): CachedMedia = withContext(Dispatchers.IO) {
        val backend = LocalBackendFactory.reconstruct(context, originId)
        val media = backend.mediaDetails(mediaId)
        val units = backend.unitList(mediaId)
        val pkg = originId.substringAfterLast(':')
        val source = runCatching { LocalRepositoryManager(context).getInstalledExtensions().firstOrNull { it.packageName == pkg }?.name }.getOrNull()
            ?: backend.displayName
        put(media, units, source)
        media.thumbnailUrl?.let { Coil.imageLoader(context).enqueue(ImageRequest.Builder(context).data(it).build()) }
        CachedMedia(media, units, source, System.currentTimeMillis())
    }

    /** Refreshes library titles' cached details, skipping any refreshed within [freshFor], so it's cheap per launch. */
    suspend fun syncLibrary(freshFor: Long = 6L * 3600 * 1000): Int = coroutineScope {
        val library = LibraryMediaDao(db).getAll()
        val now = System.currentTimeMillis()
        val gate = Semaphore(3)
        prune()
        library.map { item ->
            async {
                val cached = get(item.originId, item.mediaId)
                if (cached != null && now - cached.updatedAt < freshFor) return@async false
                gate.withPermit {
                    runCatching { refresh(item.originId, item.mediaId) }
                        .onFailure { Log.i("MediaCache", "couldn't refresh ${item.originId}/${item.mediaId}: ${it.message}") }
                        .isSuccess
                }
            }
        }.awaitAll().count { it }
    }
}

private fun BackendMedia.toJson() = JSONObject()
    .put("id", id).put("originId", originId).put("contentType", contentType.name).put("title", title)
    .put("thumbnailUrl", thumbnailUrl).put("description", description).put("status", status)
    .put("authors", JSONArray(authors)).put("artists", JSONArray(artists)).put("genres", JSONArray(genres))

private fun JSONObject.toMedia() = BackendMedia(
    id = getString("id"),
    originId = getString("originId"),
    contentType = MediaContentType.valueOf(getString("contentType")),
    title = getString("title"),
    thumbnailUrl = optStringOrNull("thumbnailUrl"),
    description = optStringOrNull("description"),
    status = optStringOrNull("status"),
    authors = optStrings("authors"),
    artists = optStrings("artists"),
    genres = optStrings("genres"),
)

private fun BackendUnit.toJson() = JSONObject()
    .put("id", id).put("mediaId", mediaId).put("contentType", contentType.name).put("title", title)
    .put("number", number?.toDouble()).put("scanlator", scanlator).put("uploadedAt", uploadedAt)

private fun JSONObject.toUnit() = BackendUnit(
    id = getString("id"),
    mediaId = getString("mediaId"),
    contentType = MediaContentType.valueOf(getString("contentType")),
    title = optStringOrNull("title"),
    number = if (isNull("number") || !has("number")) null else getDouble("number").toFloat(),
    scanlator = optStringOrNull("scanlator"),
    uploadedAt = if (isNull("uploadedAt") || !has("uploadedAt")) null else getLong("uploadedAt"),
)

private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
private fun JSONObject.optStrings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
