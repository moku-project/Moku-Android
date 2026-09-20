package dev.moku.mobile.library

import android.content.Context
import dev.moku.mobile.backend.BackendMedia
import dev.moku.mobile.backend.MediaContentType

/**
 * App-level library operations over [LocalLibraryDatabase] — deliberately *not* part of
 * [dev.moku.mobile.backend.ContentBackend] itself, since library membership/progress/folders
 * are local-mode, app-owned state, not something a content source (local or remote) knows
 * about. A remote/Tsunagu session never touches this class; it manages its own library
 * server-side via GraphQL mutations (`setInLibrary`, `updateReadingProgress`, folders, etc.)
 * instead.
 *
 * All getters are plain suspend one-shot reads rather than Room-style observable [kotlinx.coroutines.flow.Flow]s
 * (see [LocalLibraryDatabase]'s kdoc for why there's no Room here) — fine for now since no UI
 * consumes this yet; a reactive layer can be added on top (e.g. a manual invalidation signal)
 * once a screen actually needs to react to writes live.
 */
class LocalLibraryRepository(context: Context) {
    private val db = LocalLibraryDatabase.get(context)
    private val mediaDao = LibraryMediaDao(db)
    private val folderDao = FolderDao(db)
    private val progressDao = ReadingProgressDao(db)
    private val downloadDao = DownloadDao(db)

    suspend fun addToLibrary(media: BackendMedia) {
        require(media.originId.startsWith("local:")) { "LocalLibraryRepository only stores local-origin media, got ${media.originId}" }
        mediaDao.upsert(
            LibraryMediaEntity(
                originId = media.originId,
                mediaId = media.id,
                contentType = media.contentType,
                title = media.title,
                thumbnailUrl = media.thumbnailUrl,
                description = media.description,
                status = media.status,
                authorsCsv = media.authors.joinToString(","),
                artistsCsv = media.artists.joinToString(","),
                genresCsv = media.genres.joinToString(","),
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun removeFromLibrary(originId: String, mediaId: String) = mediaDao.remove(originId, mediaId)

    suspend fun isInLibrary(originId: String, mediaId: String): Boolean = mediaDao.isInLibrary(originId, mediaId)

    suspend fun getLibrary(): List<LibraryMediaEntity> = mediaDao.getAll()

    suspend fun getLibrary(contentType: MediaContentType): List<LibraryMediaEntity> = mediaDao.getByContentType(contentType)

    suspend fun touchLastViewed(originId: String, mediaId: String) =
        mediaDao.touchLastViewed(originId, mediaId, System.currentTimeMillis())

    // ---- folders ----

    suspend fun createFolder(name: String): Long = folderDao.insert(FolderEntity(name = name))
    suspend fun renameFolder(id: Long, name: String) = folderDao.rename(id, name)
    suspend fun deleteFolder(id: Long) = folderDao.delete(id)
    suspend fun getFolders(): List<FolderEntity> = folderDao.getAll()
    suspend fun getFolderContents(folderId: Long): List<LibraryMediaEntity> = mediaDao.getByFolder(folderId)

    suspend fun addToFolder(originId: String, mediaId: String, folderId: Long) =
        folderDao.addMedia(LibraryFolderCrossRef(originId, mediaId, folderId))

    suspend fun removeFromFolder(originId: String, mediaId: String, folderId: Long) =
        folderDao.removeMedia(originId, mediaId, folderId)

    // ---- reading/watch progress ----

    suspend fun updateProgress(
        originId: String,
        mediaId: String,
        unitId: String,
        progress: Float,
        completed: Boolean,
        positionSeconds: Double? = null,
        durationSeconds: Double? = null,
    ) = progressDao.upsert(
        ReadingProgressEntity(
            originId = originId,
            mediaId = mediaId,
            unitId = unitId,
            progress = progress,
            completed = completed,
            positionSeconds = positionSeconds,
            durationSeconds = durationSeconds,
            updatedAt = System.currentTimeMillis(),
        ),
    )

    suspend fun getProgress(originId: String, mediaId: String, unitId: String): ReadingProgressEntity? =
        progressDao.get(originId, mediaId, unitId)

    suspend fun getProgressForMedia(originId: String, mediaId: String): List<ReadingProgressEntity> =
        progressDao.forMedia(originId, mediaId)

    suspend fun nextUnread(originId: String, mediaId: String): ReadingProgressEntity? =
        progressDao.nextUnread(originId, mediaId)

    /** Every progress update at/after [since] (epoch millis), across the whole library — powers the Home dashboard's streak/activity stats. */
    suspend fun getProgressSince(since: Long): List<ReadingProgressEntity> = progressDao.getAllSince(since)

    // ---- downloads (metadata only for now — see DownloadManager for the actual file fetch) ----

    suspend fun upsertDownload(download: DownloadEntity) = downloadDao.upsert(download)
    suspend fun getDownload(originId: String, mediaId: String, unitId: String): DownloadEntity? =
        downloadDao.get(originId, mediaId, unitId)
    suspend fun getDownloads(): List<DownloadEntity> = downloadDao.getAll()
    suspend fun getDownloads(status: DownloadStatus): List<DownloadEntity> = downloadDao.getByStatus(status)
    suspend fun deleteDownload(download: DownloadEntity) = downloadDao.delete(download)
}
