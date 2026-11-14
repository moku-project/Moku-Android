package dev.moku.mobile.library

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.moku.mobile.backend.LocalBackendFactory
import dev.moku.mobile.core.NotificationPermission
import dev.moku.mobile.sync.ServerDownloadFailed
import dev.moku.mobile.sync.ServerDownloads
import dev.moku.mobile.sync.ServerOrigin
import kotlin.coroutines.cancellation.CancellationException

/** One chapter download as a WorkManager job, so it survives the app being killed and retries on its own. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val originId = inputData.getString(KEY_ORIGIN_ID) ?: return Result.failure()
        val mediaId = inputData.getString(KEY_MEDIA_ID) ?: return Result.failure()
        val unitId = inputData.getString(KEY_UNIT_ID) ?: return Result.failure()
        val repo = LocalLibraryRepository(applicationContext)
        // Removed from the queue before this run started.
        if (repo.getDownload(originId, mediaId, unitId) == null) return Result.success()

        suspend fun fail(message: String?): Result {
            if (repo.getDownload(originId, mediaId, unitId) != null) {
                repo.upsertDownload(DownloadEntity(originId, mediaId, unitId, DownloadStatus.FAILED, filePath = null, error = message, createdAt = System.currentTimeMillis()))
            }
            return Result.failure()
        }

        return try {
            val cached = MediaCache(applicationContext).get(originId, mediaId)
            // A foreground job isn't cut off after ten minutes, which a long episode can need. If Android won't allow
            // one right now, the download still runs as ordinary background work.
            runCatching { setForeground(DownloadNotifications.foreground(applicationContext, cached?.media?.title)) }
            val backend = LocalBackendFactory.reconstruct(applicationContext, originId)
            // The details page cached the chapter list; asking again would make a server re-check the source per chapter.
            val media = cached?.media ?: backend.mediaDetails(mediaId)
            val unit = cached?.units?.firstOrNull { it.id == unitId }
                ?: backend.unitList(mediaId).firstOrNull { it.id == unitId }
                ?: return fail("This chapter is no longer listed")

            // A server title is downloaded on the server first; the phone then copies the finished files.
            if (ServerOrigin.isServer(originId) && !ServerDownloads.ensureOnServer(unit.mediaId, unit.id, SERVER_WAIT_MS)) {
                return Result.retry()
            }
            val result = DownloadManager(applicationContext, repo).download(backend, media, unit)
            when {
                result.status == DownloadStatus.DONE -> Result.success()
                repo.getDownload(originId, mediaId, unitId) == null -> Result.success()
                else -> Result.retry()
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: ServerDownloadFailed) {
            fail(t.message)
        } catch (t: LocalBackendFactory.UnresolvableOriginException) {
            // Extension uninstalled, plugin cache evicted, or another server's title: retrying won't help.
            fail(t.message)
        } catch (t: Throwable) {
            Log.d(TAG, "doWork failed, will retry: ${t.javaClass.simpleName}: ${t.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DownloadWorker"
        private const val KEY_ORIGIN_ID = "originId"
        private const val KEY_MEDIA_ID = "mediaId"
        private const val KEY_UNIT_ID = "unitId"

        // Checked again on the next run if the server is still working on it.
        private const val SERVER_WAIT_MS = 8 * 60_000L

        fun inputData(originId: String, mediaId: String, unitId: String): Data =
            workDataOf(KEY_ORIGIN_ID to originId, KEY_MEDIA_ID to mediaId, KEY_UNIT_ID to unitId)

        fun uniqueWorkName(originId: String, mediaId: String, unitId: String) = "download:$originId:$mediaId:$unitId"
    }
}

/** Enqueues [DownloadWorker] jobs — the entry point UI/library code should call instead of [DownloadManager] directly. */
class DownloadQueueManager(private val context: Context, private val repo: LocalLibraryRepository) {
    private val workManager get() = WorkManager.getInstance(context)

    suspend fun enqueue(originId: String, mediaId: String, unitId: String) {
        NotificationPermission.askOnce(context)
        repo.upsertDownload(
            DownloadEntity(originId, mediaId, unitId, DownloadStatus.QUEUED, filePath = null, createdAt = System.currentTimeMillis()),
        )
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(DownloadWorker.inputData(originId, mediaId, unitId))
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, java.time.Duration.ofSeconds(30))
            .build()
        workManager.enqueueUniqueWork(
            DownloadWorker.uniqueWorkName(originId, mediaId, unitId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(originId: String, mediaId: String, unitId: String) {
        workManager.cancelUniqueWork(DownloadWorker.uniqueWorkName(originId, mediaId, unitId))
    }

    suspend fun remove(entries: List<DownloadEntity>) {
        val files = DownloadManager(context, repo)
        entries.forEach { e ->
            cancel(e.originId, e.mediaId, e.unitId)
            files.deleteDownload(e.originId, e.mediaId, e.unitId)
        }
    }
}
