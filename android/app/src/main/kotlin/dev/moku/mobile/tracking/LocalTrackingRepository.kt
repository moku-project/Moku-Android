package dev.moku.mobile.tracking

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.moku.mobile.library.LocalLibraryDatabase
import dev.moku.mobile.library.getDoubleOrNull
import dev.moku.mobile.library.getLongOrNull
import dev.moku.mobile.library.getStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TrackerInfo(
    val key: String,
    val name: String,
    val iconUrl: String,
    val isLoggedIn: Boolean,
    val username: String?,
    val authUrl: String,
)

data class TrackLinkEntity(
    val originId: String, val mediaId: String, val trackerKey: String, val remoteId: String,
    val libraryId: String?, val title: String, val url: String?, val status: Int,
    val score: Double, val lastChapterRead: Double, val totalChapters: Int,
)

/**
 * Local-mode counterpart of [dev.moku.mobile.remote.RemoteTrackingRepository] — same
 * AniList/MAL surface, but talking to those APIs directly from the phone via
 * [AniListTracker]/[MalTracker] instead of proxying through a Tsunagu server, since there
 * isn't one in local mode. Track-link rows are stored per local media (`local:` originId
 * only — never mixed with remote data, same rule as everything else in this package's
 * neighbourhood, see [dev.moku.mobile.backend.ContentBackend]'s kdoc).
 */
class LocalTrackingRepository(context: Context) {
    private val db = LocalLibraryDatabase.get(context)
    private val store = TrackerAccountStore(context)
    private val services: List<TrackerService> = listOf(AniListTracker(), MalTracker())

    fun getTrackers(): List<TrackerInfo> = services.map { svc ->
        val auth = store.get(svc.key)
        TrackerInfo(svc.key, svc.name, svc.iconUrl, isLoggedIn = auth != null, username = auth?.username, authUrl = svc.authUrl())
    }

    /** [pasted] is whatever the user copied back after visiting the tracker's authUrl. */
    suspend fun login(trackerKey: String, pasted: String): TrackerInfo {
        val svc = serviceFor(trackerKey)
        val auth = svc.exchange(pasted)
        store.set(trackerKey, auth)
        return TrackerInfo(svc.key, svc.name, svc.iconUrl, isLoggedIn = true, username = auth.username, authUrl = svc.authUrl())
    }

    fun logout(trackerKey: String) = store.clear(trackerKey)

    suspend fun search(trackerKey: String, query: String, contentType: String): List<TrackSearchResult> {
        val svc = serviceFor(trackerKey)
        return svc.search(authFor(svc), query, contentType)
    }

    suspend fun bindTrack(originId: String, mediaId: String, trackerKey: String, remoteId: String, title: String): TrackLinkEntity {
        require(originId.startsWith("local:")) { "LocalTrackingRepository only binds local-origin media, got $originId" }
        val svc = serviceFor(trackerKey)
        val track = svc.bind(authFor(svc), remoteId)
        return upsertLink(originId, mediaId, trackerKey, track, title)
    }

    suspend fun updateTrack(originId: String, mediaId: String, trackerKey: String, status: TrackStatus? = null, score: Double? = null, lastChapterRead: Double? = null): TrackLinkEntity {
        val existing = getTrackLink(originId, mediaId, trackerKey) ?: throw IllegalStateException("no track link for $originId/$mediaId/$trackerKey")
        val svc = serviceFor(trackerKey)
        val track = Track(
            remoteId = existing.remoteId, libraryId = existing.libraryId, title = existing.title, url = existing.url.orEmpty(),
            status = status ?: TrackStatus.entries[existing.status - 1], score = score ?: existing.score,
            lastChapterRead = lastChapterRead ?: existing.lastChapterRead, totalChapters = existing.totalChapters,
        )
        val pushed = svc.push(authFor(svc), track)
        return upsertLink(originId, mediaId, trackerKey, pushed, existing.title)
    }

    suspend fun resyncTrack(originId: String, mediaId: String, trackerKey: String): TrackLinkEntity {
        val existing = getTrackLink(originId, mediaId, trackerKey) ?: throw IllegalStateException("no track link for $originId/$mediaId/$trackerKey")
        val svc = serviceFor(trackerKey)
        val fresh = svc.bind(authFor(svc), existing.remoteId)
        return upsertLink(originId, mediaId, trackerKey, fresh, existing.title)
    }

    suspend fun unbindTrack(originId: String, mediaId: String, trackerKey: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete(
            "track_links", "originId = ? AND mediaId = ? AND trackerKey = ?", arrayOf(originId, mediaId, trackerKey),
        )
        Unit
    }

    suspend fun getTrackLinks(originId: String, mediaId: String): List<TrackLinkEntity> = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "track_links", null, "originId = ? AND mediaId = ?", arrayOf(originId, mediaId), null, null, null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toTrackLink()) } }
    }

    suspend fun getTrackLink(originId: String, mediaId: String, trackerKey: String): TrackLinkEntity? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "track_links", null, "originId = ? AND mediaId = ? AND trackerKey = ?",
            arrayOf(originId, mediaId, trackerKey), null, null, null,
        ).use { c -> if (c.moveToFirst()) c.toTrackLink() else null }
    }

    private suspend fun upsertLink(originId: String, mediaId: String, trackerKey: String, track: Track, title: String): TrackLinkEntity = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict(
            "track_links", null,
            ContentValues().apply {
                put("originId", originId); put("mediaId", mediaId); put("trackerKey", trackerKey)
                put("remoteId", track.remoteId); put("libraryId", track.libraryId)
                put("title", title); put("url", track.url)
                put("status", track.status.ordinal + 1)
                put("score", track.score); put("lastChapterRead", track.lastChapterRead)
                put("totalChapters", track.totalChapters)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        TrackLinkEntity(originId, mediaId, trackerKey, track.remoteId, track.libraryId, title, track.url, track.status.ordinal + 1, track.score, track.lastChapterRead, track.totalChapters)
    }

    private fun android.database.Cursor.toTrackLink() = TrackLinkEntity(
        originId = getStringOrNull("originId")!!, mediaId = getStringOrNull("mediaId")!!,
        trackerKey = getStringOrNull("trackerKey")!!, remoteId = getStringOrNull("remoteId")!!,
        libraryId = getStringOrNull("libraryId"), title = getStringOrNull("title")!!, url = getStringOrNull("url"),
        status = getLongOrNull("status")!!.toInt(), score = getDoubleOrNull("score") ?: 0.0,
        lastChapterRead = getDoubleOrNull("lastChapterRead") ?: 0.0, totalChapters = getLongOrNull("totalChapters")?.toInt() ?: 0,
    )

    private fun serviceFor(trackerKey: String): TrackerService = services.firstOrNull { it.key == trackerKey }
        ?: throw IllegalArgumentException("unknown tracker $trackerKey")

    private fun authFor(svc: TrackerService): TrackerAuth = store.get(svc.key) ?: throw ReauthRequiredException()
}
