package dev.moku.mobile.library

import android.content.Context
import dev.moku.mobile.backend.BackendMedia
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.remote.ServerSession
import dev.moku.mobile.sync.ServerLibrary
import dev.moku.mobile.sync.ServerOrigin

/**
 * The phone's library or the signed-in server's; never merged. Writes that need the server report their own
 * failure, so a screen never has to guess whether a change went through.
 */
interface LibraryStore {
    suspend fun addToLibrary(media: BackendMedia)
    suspend fun removeFromLibrary(originId: String, mediaId: String)
    suspend fun isInLibrary(originId: String, mediaId: String): Boolean
    suspend fun getLibrary(): List<LibraryMediaEntity>
    suspend fun getLibrary(contentType: MediaContentType): List<LibraryMediaEntity> = getLibrary().filter { it.contentType == contentType }
    suspend fun touchLastViewed(originId: String, mediaId: String)

    /** The new folder's id, or null when it couldn't be created. */
    suspend fun createFolder(name: String): Long?
    suspend fun renameFolder(id: Long, name: String)
    suspend fun deleteFolder(id: Long)

    /** Moves a folder [delta] places along the tab order. */
    suspend fun moveFolder(id: Long, delta: Int)
    suspend fun getFolders(): List<FolderEntity>
    suspend fun getFolderContents(folderId: Long): List<LibraryMediaEntity>
    suspend fun foldersFor(originId: String, mediaId: String): Set<Long>
    suspend fun addToFolder(originId: String, mediaId: String, folderId: Long)
    suspend fun removeFromFolder(originId: String, mediaId: String, folderId: Long)

    suspend fun updateProgress(
        originId: String,
        mediaId: String,
        unitId: String,
        progress: Float,
        completed: Boolean,
        positionSeconds: Double? = null,
        durationSeconds: Double? = null,
    )
    suspend fun getProgress(originId: String, mediaId: String, unitId: String): ReadingProgressEntity?
    suspend fun getProgressForMedia(originId: String, mediaId: String): List<ReadingProgressEntity>
    suspend fun nextUnread(originId: String, mediaId: String): ReadingProgressEntity?

    /** Every progress update at or after [since] (epoch millis) across this library, for Home's stats. */
    suspend fun getProgressSince(since: Long): List<ReadingProgressEntity>
}

object Libraries {
    /** The library the app is showing: the server's in server mode, otherwise the phone's. */
    fun active(context: Context): LibraryStore =
        if (ServerSession.active) ServerLibrary(context) else LocalLibraryRepository(context)

    /** A server title's downloads and progress outlive server mode, so per-title work goes by origin, not [active]. */
    fun forOrigin(context: Context, originId: String): LibraryStore =
        if (ServerOrigin.isServer(originId)) ServerLibrary(context) else LocalLibraryRepository(context)
}
