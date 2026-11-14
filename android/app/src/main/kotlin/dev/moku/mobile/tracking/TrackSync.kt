package dev.moku.mobile.tracking

import android.content.Context
import android.util.Log
import dev.moku.mobile.backend.BackendUnit
import dev.moku.mobile.library.LocalLibraryRepository
import dev.moku.mobile.library.MediaCache
import dev.moku.mobile.ui.reader.num
import kotlin.math.floor

/** Syncs progress both ways between the library and its trackers; whichever side is further ahead wins. */
class TrackSync(context: Context) {
    private val tracking = LocalTrackingRepository(context)
    private val library = LocalLibraryRepository(context)
    private val cache = MediaCache(context)

    /** Pulls each tracker, marks local units up to its progress as read, then pushes if local is ahead. */
    suspend fun sync(originId: String, mediaId: String, units: List<BackendUnit>? = null): List<TrackLinkEntity> =
        forEachLink(originId, mediaId, units, pull = true)

    /** Pushes local progress to any bound tracker that is behind it. No network if nothing changed. */
    suspend fun push(originId: String, mediaId: String, units: List<BackendUnit>? = null): List<TrackLinkEntity> =
        forEachLink(originId, mediaId, units, pull = false)

    /** Sends an explicit edit from the tracking sheet, filling in start/finish dates the way an automatic push would. */
    suspend fun edit(link: TrackLinkEntity, status: TrackStatus, progress: Double, score: Double): TrackLinkEntity {
        val before = TrackStatus.entries[link.status - 1]
        val now = System.currentTimeMillis()
        return tracking.updateTrack(
            link.originId, link.mediaId, link.trackerKey, status = status, score = score, lastChapterRead = progress,
            startedAt = now.takeIf { before == TrackStatus.PLAN_TO_READ && status == TrackStatus.READING },
            finishedAt = now.takeIf { before != TrackStatus.COMPLETED && status == TrackStatus.COMPLETED },
        )
    }

    /** Runs [sync] over every library title; titles without a bound tracker cost one local query. */
    suspend fun syncLibrary(): Int = library.getLibrary().sumOf { sync(it.originId, it.mediaId).size }

    private suspend fun forEachLink(originId: String, mediaId: String, units: List<BackendUnit>?, pull: Boolean): List<TrackLinkEntity> {
        if (!originId.startsWith("local:")) return emptyList()
        val links = tracking.getTrackLinks(originId, mediaId)
        if (links.isEmpty()) return links
        val list = units ?: cache.get(originId, mediaId)?.units.orEmpty()
        return links.map { link ->
            runCatching {
                val remote = if (pull) tracking.resyncTrack(originId, mediaId, link.trackerKey) else link
                var local = localProgress(originId, mediaId, list)
                if (pull && remote.lastChapterRead > local) {
                    markReadUpTo(originId, mediaId, list, remote.lastChapterRead)
                    local = remote.lastChapterRead
                }
                if (local > remote.lastChapterRead) pushProgress(remote, local) else remote
            }.onFailure { Log.i("TrackSync", "${link.trackerKey} sync failed for $originId/$mediaId: ${it.message}") }
                .getOrDefault(link)
        }
    }

    // Trackers count whole chapters, so 10.5 read reports as 10 and doesn't re-push forever.
    private suspend fun localProgress(originId: String, mediaId: String, units: List<BackendUnit>): Double {
        val done = library.getProgressForMedia(originId, mediaId).filter { it.completed }.map { it.unitId }.toSet()
        return units.filter { it.id in done }.mapNotNull { it.num() }.maxOrNull()?.let { floor(it.toDouble()) } ?: 0.0
    }

    private suspend fun markReadUpTo(originId: String, mediaId: String, units: List<BackendUnit>, upTo: Double) {
        val existing = library.getProgressForMedia(originId, mediaId).associateBy { it.unitId }
        units.filter { u -> u.num()?.let { it <= upTo } == true && existing[u.id]?.completed != true }
            .forEach { library.updateProgress(originId, mediaId, it.id, progress = 1f, completed = true) }
    }

    private suspend fun pushProgress(link: TrackLinkEntity, progress: Double): TrackLinkEntity {
        val before = TrackStatus.entries[link.status - 1]
        val now = System.currentTimeMillis()
        var status = if (before == TrackStatus.PLAN_TO_READ) TrackStatus.READING else before
        val finished = link.totalChapters > 0 && progress >= link.totalChapters &&
            (status == TrackStatus.READING || status == TrackStatus.REREADING)
        if (finished) status = TrackStatus.COMPLETED
        return tracking.updateTrack(
            link.originId, link.mediaId, link.trackerKey, status = status, lastChapterRead = progress,
            startedAt = now.takeIf { before == TrackStatus.PLAN_TO_READ },
            finishedAt = now.takeIf { finished },
        )
    }
}
