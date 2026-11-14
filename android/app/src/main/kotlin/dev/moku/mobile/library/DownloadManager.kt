package dev.moku.mobile.library

import android.content.Context
import dev.moku.mobile.backend.BackendMedia
import dev.moku.mobile.backend.BackendUnit
import dev.moku.mobile.backend.BackendUnitContent
import dev.moku.mobile.backend.ContentBackend
import dev.moku.mobile.backend.fetch
import dev.moku.mobile.backend.imageExtension
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class DownloadManager(private val context: Context, private val repo: LocalLibraryRepository) {
    private val network: NetworkHelper by injectLazy()

    private fun unitDir(originId: String, mediaId: String, unitId: String): File =
        File(context.filesDir, listOf("downloads", originId, mediaId, unitId).joinToString("/") { it.sanitize() })

    private fun String.sanitize(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")

    suspend fun download(backend: ContentBackend, media: BackendMedia, unit: BackendUnit): DownloadEntity {
        val originId = backend.originId
        val dir = unitDir(originId, media.id, unit.id)

        // Removed while it waited to start: writing a row now would bring it back.
        if (repo.getDownload(originId, media.id, unit.id) == null) return DownloadEntity(originId, media.id, unit.id, DownloadStatus.FAILED, null, error = "Removed", createdAt = 0)
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
            if (repo.getDownload(originId, media.id, unit.id) == null) {
                dir.deleteRecursively()
                return done
            }
            repo.upsertDownload(done)
            done
        } catch (t: CancellationException) {
            // Cancelled because it was removed or the worker was stopped; whoever cancelled decides what's left.
            dir.deleteRecursively()
            throw t
        } catch (t: Throwable) {
            dir.deleteRecursively()
            val failed = DownloadEntity(
                originId, media.id, unit.id, DownloadStatus.FAILED, filePath = null,
                error = "${t.javaClass.simpleName}: ${t.message}", createdAt = System.currentTimeMillis(),
            )
            if (repo.getDownload(originId, media.id, unit.id) != null) repo.upsertDownload(failed)
            failed
        }
    }

    suspend fun deleteDownload(originId: String, mediaId: String, unitId: String) {
        withContext(Dispatchers.IO) { unitDir(originId, mediaId, unitId).deleteRecursively() }
        repo.getDownload(originId, mediaId, unitId)?.let { repo.deleteDownload(it) }
    }

    private suspend fun downloadPages(dir: File, content: BackendUnitContent.Pages): Pair<String, Long> {
        var total = 0L
        content.pages.forEach { page ->
            page.fetch(network.client).use { response ->
                val body = response.body
                val file = File(dir, "%03d.%s".format(page.index + 1, response.imageExtension()))
                file.outputStream().use { out -> body.byteStream().copyTo(out) }
                total += file.length()
            }
        }
        return dir.absolutePath to total
    }

    private fun downloadVideo(dir: File, content: BackendUnitContent.Video): Pair<String, Long> {
        val part = File(dir, "video.part")
        val type = fetchToFile(content.streamUrl, content.headers, part)
        val file = File(dir, "video." + videoExtension(type, content.streamUrl))
        part.renameTo(file)
        content.subtitleUrl?.let { runCatching { fetchToFile(it, emptyMap(), File(dir, "subtitle.vtt")) } }
        return file.absolutePath to file.length()
    }

    // Server URLs like http://10.0.2.2:6007/content/3/5/video have no extension, and the dots in the host aren't one.
    private fun videoExtension(contentType: String?, url: String): String {
        val fromType = when (contentType?.substringBefore(';')?.trim()) {
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            "video/mp2t" -> "ts"
            else -> null
        }
        val path = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
        return fromType ?: path.substringAfterLast('.', "").takeIf { it.length in 2..4 } ?: "mp4"
    }

    private fun downloadText(dir: File, content: BackendUnitContent.Text): Pair<String, Long> {
        val file = File(dir, "content.html")
        file.writeText(content.html)
        return file.absolutePath to file.length()
    }

    /** Saves [url] to [file] and returns the response's content type. */
    private fun fetchToFile(url: String, headers: Map<String, String>, file: File): String? {
        if (url.isEmpty()) throw IOException("empty content url")
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        return network.client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GET $url failed: HTTP ${response.code}")
            val body = response.body
            file.outputStream().use { out -> body.byteStream().copyTo(out) }
            response.header("Content-Type")
        }
    }
}
