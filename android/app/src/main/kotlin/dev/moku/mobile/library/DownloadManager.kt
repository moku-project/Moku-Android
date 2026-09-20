package dev.moku.mobile.library

import android.content.Context
import dev.moku.mobile.backend.BackendMedia
import dev.moku.mobile.backend.BackendUnit
import dev.moku.mobile.backend.BackendUnitContent
import dev.moku.mobile.backend.ContentBackend
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException

/**
 * Actually fetches a [BackendUnit]'s content to disk for offline reading/watching —
 * [DownloadEntity] rows on their own (written by [LocalLibraryRepository]) are just metadata;
 * this is what turns a QUEUED row into real bytes. Local-mode only, same as everything else
 * in [dev.moku.mobile.library] — a connected Tsunagu server does its own downloading
 * server-side (`enqueueDownload`/the downloader daemon), so this class is never invoked for
 * a [dev.moku.mobile.remote.RemoteTsunaguBackend].
 *
 * Runs in-process via a plain suspend function rather than a WorkManager-backed job queue —
 * fine for now (nothing here survives the app being killed mid-download, no automatic retry
 * on reboot), but that durability gap is a real, known simplification: a background-service
 * download queue is a reasonable follow-up once there's a UI screen actually driving this.
 */
class DownloadManager(private val context: Context, private val repo: LocalLibraryRepository) {
    private val network: NetworkHelper by injectLazy()

    /** Where a given unit's downloaded file(s) live: files/downloads/<originId>/<mediaId>/<unitId>/ */
    private fun unitDir(originId: String, mediaId: String, unitId: String): File =
        File(context.filesDir, listOf("downloads", originId, mediaId, unitId).joinToString("/") { it.sanitize() })

    private fun String.sanitize(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")

    suspend fun download(backend: ContentBackend, media: BackendMedia, unit: BackendUnit): DownloadEntity {
        require(backend.originId.startsWith("local:")) {
            "DownloadManager only downloads local-origin content, got ${backend.originId}"
        }
        val originId = backend.originId
        val dir = unitDir(originId, media.id, unit.id)

        repo.upsertDownload(
            DownloadEntity(originId, media.id, unit.id, DownloadStatus.DOWNLOADING, filePath = null, createdAt = System.currentTimeMillis()),
        )

        return try {
            val (filePath, bytes) = withContext(Dispatchers.IO) {
                dir.mkdirs()
                when (val content = backend.unitContent(media.id, unit.id)) {
                    is BackendUnitContent.Pages -> downloadPages(dir, content)
                    is BackendUnitContent.Video -> downloadVideo(dir, content)
                    is BackendUnitContent.Text -> downloadText(dir, content)
                }
            }
            val done = DownloadEntity(originId, media.id, unit.id, DownloadStatus.DONE, filePath, bytes, createdAt = System.currentTimeMillis())
            repo.upsertDownload(done)
            done
        } catch (t: Throwable) {
            dir.deleteRecursively()
            val failed = DownloadEntity(
                originId, media.id, unit.id, DownloadStatus.FAILED, filePath = null,
                error = "${t.javaClass.simpleName}: ${t.message}", createdAt = System.currentTimeMillis(),
            )
            repo.upsertDownload(failed)
            failed
        }
    }

    suspend fun deleteDownload(originId: String, mediaId: String, unitId: String) {
        withContext(Dispatchers.IO) { unitDir(originId, mediaId, unitId).deleteRecursively() }
        repo.getDownload(originId, mediaId, unitId)?.let { repo.upsertDownload(it.copy(status = DownloadStatus.QUEUED, filePath = null, bytes = 0)) }
    }

    private fun downloadPages(dir: File, content: BackendUnitContent.Pages): Pair<String, Long> {
        var total = 0L
        content.pages.forEach { page ->
            val ext = page.imageUrl.substringAfterLast('.', missingDelimiterValue = "jpg").take(4)
            val file = File(dir, "%03d.%s".format(page.index + 1, ext))
            total += fetchToFile(page.imageUrl, page.headers, file)
        }
        return dir.absolutePath to total
    }

    private fun downloadVideo(dir: File, content: BackendUnitContent.Video): Pair<String, Long> {
        val ext = content.streamUrl.substringAfterLast('.', missingDelimiterValue = "mp4").substringBefore('?').take(4)
        val file = File(dir, "video.$ext")
        val bytes = fetchToFile(content.streamUrl, content.headers, file)
        content.subtitleUrl?.let { runCatching { fetchToFile(it, emptyMap(), File(dir, "subtitle.vtt")) } }
        return file.absolutePath to bytes
    }

    private fun downloadText(dir: File, content: BackendUnitContent.Text): Pair<String, Long> {
        val file = File(dir, "content.html")
        file.writeText(content.html)
        return file.absolutePath to file.length()
    }

    private fun fetchToFile(url: String, headers: Map<String, String>, file: File): Long {
        if (url.isEmpty()) throw IOException("empty content url")
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        network.client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GET $url failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty response body for $url")
            file.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        return file.length()
    }
}
