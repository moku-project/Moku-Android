package dev.moku.mobile.library

import dev.moku.mobile.backend.MediaContentType

/** The phone's own library, plus the downloads and progress it keeps for server titles. */
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

/** Per chapter or episode, so each keeps its own position. */
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
