package dev.moku.mobile.library

import dev.moku.mobile.backend.MediaContentType

/**
 * On-device library persistence — **local-mode only**. There is no remote/local merge here:
 * every row is keyed by a `local:`-prefixed [dev.moku.mobile.backend.ContentBackend.originId]
 * (see that interface's kdoc). A connected Tsunagu server keeps its own library/progress/
 * downloads/tracking state server-side via its GraphQL API; this database exists only so
 * local, no-server usage isn't read-only-and-forgotten between app launches. Nothing here is
 * ever queried against, or written from, a [dev.moku.mobile.remote.RemoteTsunaguBackend].
 *
 * Plain data classes, not Room entities — see [LocalLibraryDatabase]'s kdoc for why this is a
 * hand-rolled SQLiteOpenHelper layer instead.
 */
data class LibraryMediaEntity(
    val originId: String,
    val mediaId: String,
    val contentType: MediaContentType,
    val title: String,
    val thumbnailUrl: String?,
    val description: String?,
    val status: String?,
    val authorsCsv: String = "",
    val artistsCsv: String = "",
    val genresCsv: String = "",
    val addedAt: Long,
    val lastViewedAt: Long? = null,
)

data class FolderEntity(
    val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
)

data class LibraryFolderCrossRef(
    val originId: String,
    val mediaId: String,
    val folderId: Long,
)

/**
 * Progress is keyed per unit (chapter/episode), not per media, so partially-read chapters
 * keep their own scroll/seek position independent of whether the whole media is "completed".
 */
data class ReadingProgressEntity(
    val originId: String,
    val mediaId: String,
    val unitId: String,
    /** 0f..1f — page fraction for manga/novels, playback fraction for anime. */
    val progress: Float,
    val completed: Boolean,
    /** Anime-only: exact resume position, since page-fraction alone is too coarse for video. */
    val positionSeconds: Double? = null,
    val durationSeconds: Double? = null,
    val updatedAt: Long,
)

enum class DownloadStatus { QUEUED, DOWNLOADING, DONE, FAILED }

data class DownloadEntity(
    val originId: String,
    val mediaId: String,
    val unitId: String,
    val status: DownloadStatus,
    /** Directory (manga: page images: 001.jpg...; anime: single video file; novel: one text file). */
    val filePath: String?,
    val bytes: Long = 0,
    val error: String? = null,
    val createdAt: Long,
)
