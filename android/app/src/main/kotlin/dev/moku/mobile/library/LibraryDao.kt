package dev.moku.mobile.library

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.moku.mobile.backend.MediaContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibraryMediaDao(private val db: LocalLibraryDatabase) {
    suspend fun upsert(media: LibraryMediaEntity) = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict(
            "library_media",
            null,
            ContentValues().apply {
                put("originId", media.originId)
                put("mediaId", media.mediaId)
                put("contentType", media.contentType.name)
                put("title", media.title)
                putNullable("thumbnailUrl", media.thumbnailUrl)
                putNullable("description", media.description)
                putNullable("status", media.status)
                put("authorsCsv", media.authorsCsv)
                put("artistsCsv", media.artistsCsv)
                put("genresCsv", media.genresCsv)
                put("addedAt", media.addedAt)
                putNullable("lastViewedAt", media.lastViewedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        Unit
    }

    suspend fun remove(originId: String, mediaId: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("library_media", "originId = ? AND mediaId = ?", arrayOf(originId, mediaId))
        Unit
    }

    suspend fun get(originId: String, mediaId: String): LibraryMediaEntity? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "library_media", null, "originId = ? AND mediaId = ?", arrayOf(originId, mediaId), null, null, null,
        ).use { c -> if (c.moveToFirst()) c.toLibraryMediaEntity() else null }
    }

    suspend fun isInLibrary(originId: String, mediaId: String): Boolean = get(originId, mediaId) != null

    suspend fun getAll(): List<LibraryMediaEntity> = withContext(Dispatchers.IO) {
        db.readableDatabase.query("library_media", null, null, null, null, null, "addedAt DESC").use { c ->
            buildList { while (c.moveToNext()) add(c.toLibraryMediaEntity()) }
        }
    }

    suspend fun getByContentType(contentType: MediaContentType): List<LibraryMediaEntity> = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "library_media", null, "contentType = ?", arrayOf(contentType.name), null, null, "addedAt DESC",
        ).use { c -> buildList { while (c.moveToNext()) add(c.toLibraryMediaEntity()) } }
    }

    suspend fun getByFolder(folderId: Long): List<LibraryMediaEntity> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            """SELECT m.* FROM library_media m
               INNER JOIN library_folder_cross_ref f ON f.originId = m.originId AND f.mediaId = m.mediaId
               WHERE f.folderId = ? ORDER BY m.addedAt DESC""",
            arrayOf(folderId.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toLibraryMediaEntity()) } }
    }

    suspend fun touchLastViewed(originId: String, mediaId: String, timestamp: Long) = withContext(Dispatchers.IO) {
        db.writableDatabase.execSQL(
            "UPDATE library_media SET lastViewedAt = ? WHERE originId = ? AND mediaId = ?",
            arrayOf<Any>(timestamp, originId, mediaId),
        )
    }

    private fun android.database.Cursor.toLibraryMediaEntity() = LibraryMediaEntity(
        originId = getStringOrNull("originId")!!,
        mediaId = getStringOrNull("mediaId")!!,
        contentType = MediaContentType.valueOf(getStringOrNull("contentType")!!),
        title = getStringOrNull("title")!!,
        thumbnailUrl = getStringOrNull("thumbnailUrl"),
        description = getStringOrNull("description"),
        status = getStringOrNull("status"),
        authorsCsv = getStringOrNull("authorsCsv").orEmpty(),
        artistsCsv = getStringOrNull("artistsCsv").orEmpty(),
        genresCsv = getStringOrNull("genresCsv").orEmpty(),
        addedAt = getLongOrNull("addedAt")!!,
        lastViewedAt = getLongOrNull("lastViewedAt"),
    )
}

class FolderDao(private val db: LocalLibraryDatabase) {
    suspend fun insert(folder: FolderEntity): Long = withContext(Dispatchers.IO) {
        db.writableDatabase.insert(
            "folders",
            null,
            ContentValues().apply { put("name", folder.name); put("sortOrder", folder.sortOrder) },
        )
    }

    suspend fun rename(id: Long, name: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.execSQL("UPDATE folders SET name = ? WHERE id = ?", arrayOf<Any>(name, id))
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("folders", "id = ?", arrayOf(id.toString()))
        Unit
    }

    suspend fun getAll(): List<FolderEntity> = withContext(Dispatchers.IO) {
        db.readableDatabase.query("folders", null, null, null, null, null, "sortOrder ASC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(FolderEntity(id = c.getLongOrNull("id")!!, name = c.getStringOrNull("name")!!, sortOrder = c.getLongOrNull("sortOrder")!!.toInt()))
                }
            }
        }
    }

    suspend fun addMedia(crossRef: LibraryFolderCrossRef) = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict(
            "library_folder_cross_ref",
            null,
            ContentValues().apply {
                put("originId", crossRef.originId)
                put("mediaId", crossRef.mediaId)
                put("folderId", crossRef.folderId)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        Unit
    }

    suspend fun removeMedia(originId: String, mediaId: String, folderId: Long) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete(
            "library_folder_cross_ref",
            "originId = ? AND mediaId = ? AND folderId = ?",
            arrayOf(originId, mediaId, folderId.toString()),
        )
        Unit
    }

    suspend fun foldersFor(originId: String, mediaId: String): List<Long> = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "library_folder_cross_ref", arrayOf("folderId"), "originId = ? AND mediaId = ?",
            arrayOf(originId, mediaId), null, null, null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLongOrNull("folderId")!!) } }
    }
}

class ReadingProgressDao(private val db: LocalLibraryDatabase) {
    suspend fun upsert(progress: ReadingProgressEntity) = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict(
            "reading_progress",
            null,
            ContentValues().apply {
                put("originId", progress.originId)
                put("mediaId", progress.mediaId)
                put("unitId", progress.unitId)
                put("progress", progress.progress)
                put("completed", if (progress.completed) 1 else 0)
                putNullable("positionSeconds", progress.positionSeconds)
                putNullable("durationSeconds", progress.durationSeconds)
                put("updatedAt", progress.updatedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        Unit
    }

    suspend fun get(originId: String, mediaId: String, unitId: String): ReadingProgressEntity? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "reading_progress", null, "originId = ? AND mediaId = ? AND unitId = ?",
            arrayOf(originId, mediaId, unitId), null, null, null,
        ).use { c -> if (c.moveToFirst()) c.toProgressEntity() else null }
    }

    suspend fun forMedia(originId: String, mediaId: String): List<ReadingProgressEntity> = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "reading_progress", null, "originId = ? AND mediaId = ?", arrayOf(originId, mediaId), null, null, null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toProgressEntity()) } }
    }

    /** Every progress row updated at/after [since] (epoch millis) — used for streak/stats rollups across the whole library. */
    suspend fun getAllSince(since: Long): List<ReadingProgressEntity> = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "reading_progress", null, "updatedAt >= ?", arrayOf(since.toString()), null, null, "updatedAt DESC",
        ).use { c -> buildList { while (c.moveToNext()) add(c.toProgressEntity()) } }
    }

    suspend fun nextUnread(originId: String, mediaId: String): ReadingProgressEntity? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "reading_progress", null, "originId = ? AND mediaId = ? AND completed = 0",
            arrayOf(originId, mediaId), null, null, "updatedAt DESC", "1",
        ).use { c -> if (c.moveToFirst()) c.toProgressEntity() else null }
    }

    private fun android.database.Cursor.toProgressEntity() = ReadingProgressEntity(
        originId = getStringOrNull("originId")!!,
        mediaId = getStringOrNull("mediaId")!!,
        unitId = getStringOrNull("unitId")!!,
        progress = getDoubleOrNull("progress")!!.toFloat(),
        completed = getLongOrNull("completed") == 1L,
        positionSeconds = getDoubleOrNull("positionSeconds"),
        durationSeconds = getDoubleOrNull("durationSeconds"),
        updatedAt = getLongOrNull("updatedAt")!!,
    )
}

class DownloadDao(private val db: LocalLibraryDatabase) {
    suspend fun upsert(download: DownloadEntity) = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict(
            "downloads",
            null,
            ContentValues().apply {
                put("originId", download.originId)
                put("mediaId", download.mediaId)
                put("unitId", download.unitId)
                put("status", download.status.name)
                putNullable("filePath", download.filePath)
                put("bytes", download.bytes)
                putNullable("error", download.error)
                put("createdAt", download.createdAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        Unit
    }

    suspend fun delete(download: DownloadEntity) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete(
            "downloads", "originId = ? AND mediaId = ? AND unitId = ?",
            arrayOf(download.originId, download.mediaId, download.unitId),
        )
        Unit
    }

    suspend fun get(originId: String, mediaId: String, unitId: String): DownloadEntity? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "downloads", null, "originId = ? AND mediaId = ? AND unitId = ?",
            arrayOf(originId, mediaId, unitId), null, null, null,
        ).use { c -> if (c.moveToFirst()) c.toDownloadEntity() else null }
    }

    suspend fun getAll(): List<DownloadEntity> = withContext(Dispatchers.IO) {
        db.readableDatabase.query("downloads", null, null, null, null, null, "createdAt ASC").use { c ->
            buildList { while (c.moveToNext()) add(c.toDownloadEntity()) }
        }
    }

    suspend fun getByStatus(status: DownloadStatus): List<DownloadEntity> = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "downloads", null, "status = ?", arrayOf(status.name), null, null, "createdAt ASC",
        ).use { c -> buildList { while (c.moveToNext()) add(c.toDownloadEntity()) } }
    }

    private fun android.database.Cursor.toDownloadEntity() = DownloadEntity(
        originId = getStringOrNull("originId")!!,
        mediaId = getStringOrNull("mediaId")!!,
        unitId = getStringOrNull("unitId")!!,
        status = DownloadStatus.valueOf(getStringOrNull("status")!!),
        filePath = getStringOrNull("filePath"),
        bytes = getLongOrNull("bytes") ?: 0,
        error = getStringOrNull("error"),
        createdAt = getLongOrNull("createdAt")!!,
    )
}
