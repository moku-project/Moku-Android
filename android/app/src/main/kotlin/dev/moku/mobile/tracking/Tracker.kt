package dev.moku.mobile.tracking

import java.io.IOException

/** Thrown when a stored token is expired/revoked and the user needs to log in again — mirrors Tsunagu's `ErrReauth`. */
class ReauthRequiredException : IOException("Re-authentication required")

enum class TrackStatus { READING, PLAN_TO_READ, COMPLETED, ON_HOLD, DROPPED, REREADING }

data class TrackerAuth(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMillis: Long? = null,
    val username: String? = null,
    val scoreFormat: String? = null,
)

data class Track(
    val remoteId: String,
    val libraryId: String? = null,
    val title: String,
    val url: String,
    val status: TrackStatus,
    val lastChapterRead: Double = 0.0,
    val totalChapters: Int = 0,
    val score: Double = 0.0,
    // Only set when a status change starts or finishes the entry; null leaves the remote date alone.
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)

data class TrackSearchResult(
    val remoteId: String,
    val title: String,
    val url: String,
    val coverUrl: String?,
    val summary: String?,
    val totalChapters: Int,
    val publishingStatus: String?,
    val mediaType: String?,
)

data class TrackLibraryEntry(
    val remoteId: String,
    val title: String,
    val status: String,
    val progress: Double,
    val score: Double,
    val coverUrl: String?,
    val mediaType: String?,
    val url: String,
    val totalChapters: Int,
)

/** Port of Tsunagu's tracker interface. Both trackers use public client ids, which aren't secrets. */
interface TrackerService {
    val key: String
    val name: String
    val iconUrl: String
    fun authUrl(): String

    /** [pasted] is whatever the user copied back after visiting [authUrl] — a bare token or a full redirected URL. */
    suspend fun exchange(pasted: String): TrackerAuth

    suspend fun refreshIfNeeded(auth: TrackerAuth): TrackerAuth = auth

    suspend fun search(auth: TrackerAuth, query: String, contentType: String): List<TrackSearchResult>
    suspend fun listLibrary(auth: TrackerAuth, contentType: String, statuses: List<String>): List<TrackLibraryEntry>
    suspend fun bind(auth: TrackerAuth, remoteId: String): Track
    suspend fun push(auth: TrackerAuth, track: Track): Track
    fun scoreOptions(auth: TrackerAuth?): List<String>
}
