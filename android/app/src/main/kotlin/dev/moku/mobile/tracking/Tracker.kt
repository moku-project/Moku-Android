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

/**
 * Kotlin port of Tsunagu's `tracker.Service` interface (`internal/tracker/tracker.go`) — same
 * shape, same semantics, so [AniListTracker]/[MalTracker] are direct ports of
 * `internal/tracker/anilist.go`/`mal.go` rather than a reinterpretation. This is what makes
 * local-mode tracking possible at all without a server: AniList's implicit-grant flow needs no
 * client secret (the token comes back directly to the browser), and MAL's flow is PKCE with
 * `code_challenge_method=plain` (no secret needed either) — both trackers' client ids here are
 * the same public ones Tsunagu itself uses (`49724` for AniList, Tsunagu's default MAL client
 * id), since neither is a secret credential (an OAuth client id is not confidential for a
 * native/public client — only a client *secret* would be, and MAL's is deliberately left blank
 * for the PKCE flow server-side too).
 */
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
