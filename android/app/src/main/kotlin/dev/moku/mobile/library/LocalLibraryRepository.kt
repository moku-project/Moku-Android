package dev.moku.mobile.library

import android.content.Context
import dev.moku.mobile.backend.BackendMedia
import dev.moku.mobile.backend.MediaContentType

/** The phone's own library. Downloads live here in both modes, since they're files on this phone either way. */
class LocalLibraryRepository(context: Context) : LibraryStore {
    private val db = LocalLibraryDatabase.get(context)
    private val mediaDao = LibraryMediaDao(db)
    private val folderDao = FolderDao(db)
    private val progressDao = ReadingProgressDao(db)
    private val downloadDao = DownloadDao(db)

    override suspend fun addToLibrary(media: BackendMedia) {
        require(media.originId.startsWith("local:")) { "Can't keep ${media.originId} in the phone's library" }
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

    override suspend fun removeFromLibrary(originId: String, mediaId: String) = mediaDao.remove(originId, mediaId)

    override suspend fun isInLibrary(originId: String, mediaId: String): Boolean = mediaDao.isInLibrary(originId, mediaId)

    override suspend fun getLibrary(): List<LibraryMediaEntity> = mediaDao.getAll()

    override suspend fun getLibrary(contentType: MediaContentType): List<LibraryMediaEntity> = mediaDao.getByContentType(contentType)

    override suspend fun touchLastViewed(originId: String, mediaId: String) =
        mediaDao.touchLastViewed(originId, mediaId, System.currentTimeMillis())

    // ---- folders ----

    override suspend fun createFolder(name: String): Long =
        folderDao.insert(FolderEntity(name = name, sortOrder = (folderDao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1))

    override suspend fun renameFolder(id: Long, name: String) = folderDao.rename(id, name)

    override suspend fun deleteFolder(id: Long) = folderDao.delete(id)

    override suspend fun moveFolder(id: Long, delta: Int) {
        val folders = folderDao.getAll().toMutableList()
        val from = folders.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in folders.indices) return
        folders.add(to, folders.removeAt(from))
        folders.forEachIndexed { index, f -> if (f.sortOrder != index) folderDao.setSortOrder(f.id, index) }
    }

    override suspend fun getFolders(): List<FolderEntity> = folderDao.getAll()
    override suspend fun getFolderContents(folderId: Long): List<LibraryMediaEntity> = mediaDao.getByFolder(folderId)
    override suspend fun foldersFor(originId: String, mediaId: String): Set<Long> = folderDao.foldersFor(originId, mediaId).toSet()

    override suspend fun addToFolder(originId: String, mediaId: String, folderId: Long) =
        folderDao.addMedia(LibraryFolderCrossRef(originId, mediaId, folderId))

    override suspend fun removeFromFolder(originId: String, mediaId: String, folderId: Long) =
        folderDao.removeMedia(originId, mediaId, folderId)

    // ---- reading/watch progress ----

    override suspend fun updateProgress(
        originId: String,
        mediaId: String,
        unitId: String,
        progress: Float,
        completed: Boolean,
        positionSeconds: Double?,
        durationSeconds: Double?,
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

    override suspend fun getProgress(originId: String, mediaId: String, unitId: String): ReadingProgressEntity? =
        progressDao.get(originId, mediaId, unitId)

    override suspend fun getProgressForMedia(originId: String, mediaId: String): List<ReadingProgressEntity> =
        progressDao.forMedia(originId, mediaId)

    override suspend fun nextUnread(originId: String, mediaId: String): ReadingProgressEntity? =
        progressDao.nextUnread(originId, mediaId)

    override suspend fun getProgressSince(since: Long): List<ReadingProgressEntity> =
        progressDao.getAllSince(since).filter { it.originId.startsWith("local:") }

    // ---- downloads ----

    suspend fun upsertDownload(download: DownloadEntity) = downloadDao.upsert(download)
    suspend fun getDownload(originId: String, mediaId: String, unitId: String): DownloadEntity? =
        downloadDao.get(originId, mediaId, unitId)
    suspend fun getDownloads(): List<DownloadEntity> = downloadDao.getAll()
    suspend fun getDownloads(status: DownloadStatus): List<DownloadEntity> = downloadDao.getByStatus(status)
    suspend fun deleteDownload(download: DownloadEntity) = downloadDao.delete(download)

    /** Forgets finished downloads whose files are gone, as after restoring a backup that carries the database only. */
    suspend fun dropMissingDownloads() = getDownloads(DownloadStatus.DONE)
        .filter { d -> d.filePath == null || !java.io.File(d.filePath).exists() }
        .forEach { deleteDownload(it) }
}
