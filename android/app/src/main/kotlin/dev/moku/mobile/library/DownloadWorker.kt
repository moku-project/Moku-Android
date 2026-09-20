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

/**
 * The durable counterpart of the original in-process [DownloadManager]: runs as a WorkManager
 * job, so a download survives the app being killed mid-fetch and WorkManager retries it
 * automatically (network-lost, process-death, etc.) rather than silently leaving a QUEUED row
 * forever. Only needs `originId`/`mediaId`/`unitId` as input — everything else (the actual
 * backend instance) is rebuilt via [LocalBackendFactory], since a Worker can be recreated in a
 * fresh process with no memory of what was running before.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val originId = inputData.getString(KEY_ORIGIN_ID) ?: return Result.failure()
        val mediaId = inputData.getString(KEY_MEDIA_ID) ?: return Result.failure()
        val unitId = inputData.getString(KEY_UNIT_ID) ?: return Result.failure()

        return try {
            val backend = LocalBackendFactory.reconstruct(applicationContext, originId)
            val media = backend.mediaDetails(mediaId)
            val unit = backend.unitList(mediaId).firstOrNull { it.id == unitId } ?: return Result.failure()

            val repo = LocalLibraryRepository(applicationContext)
            val manager = DownloadManager(applicationContext, repo)
            val result = manager.download(backend, media, unit)
            if (result.status == DownloadStatus.DONE) Result.success() else Result.retry()
        } catch (t: LocalBackendFactory.UnresolvableOriginException) {
            Log.d(TAG, "Giving up, not retrying: ${t.message}")
            Result.failure() // extension uninstalled / plugin cache evicted — retrying won't help
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

        fun inputData(originId: String, mediaId: String, unitId: String): Data =
            workDataOf(KEY_ORIGIN_ID to originId, KEY_MEDIA_ID to mediaId, KEY_UNIT_ID to unitId)

        fun uniqueWorkName(originId: String, mediaId: String, unitId: String) = "download:$originId:$mediaId:$unitId"
    }
}

/** Enqueues [DownloadWorker] jobs — the entry point UI/library code should call instead of [DownloadManager] directly. */
class DownloadQueueManager(private val context: Context, private val repo: LocalLibraryRepository) {
    private val workManager get() = WorkManager.getInstance(context)

    suspend fun enqueue(originId: String, mediaId: String, unitId: String) {
        repo.upsertDownload(
            DownloadEntity(originId, mediaId, unitId, DownloadStatus.QUEUED, filePath = null, createdAt = System.currentTimeMillis()),
        )
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(DownloadWorker.inputData(originId, mediaId, unitId))
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, java.time.Duration.ofSeconds(30))
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
}
