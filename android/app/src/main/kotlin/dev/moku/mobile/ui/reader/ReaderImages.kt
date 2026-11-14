package dev.moku.mobile.ui.reader

import android.content.Context
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.Options
import dev.moku.mobile.backend.BackendPageImage
import dev.moku.mobile.backend.fetch
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import uy.kohesive.injekt.injectLazy
import java.io.File

internal object ReaderImages {
    @Volatile
    private var instance: ImageLoader? = null

    fun loader(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: ImageLoader.Builder(context.applicationContext)
            .components {
                add(PageKeyer())
                add(PageFetcher.Factory())
            }
            .memoryCache { MemoryCache.Builder(context.applicationContext).maxSizePercent(0.2).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("reader_pages")).maxSizeBytes(400L * 1024 * 1024).build() }
            .crossfade(120)
            .build()
            .also { instance = it }
    }

    fun prefetch(context: Context, pages: List<BackendPageImage>) {
        val loader = loader(context)
        pages.forEach { loader.enqueue(ImageRequest.Builder(context).data(it).build()) }
    }
}

private class PageKeyer : Keyer<BackendPageImage> {
    override fun key(data: BackendPageImage, options: Options) = data.imageUrl
}

@OptIn(ExperimentalCoilApi::class)
private class PageFetcher(
    private val page: BackendPageImage,
    private val options: Options,
    private val diskCache: DiskCache?,
) : Fetcher {
    private val network: NetworkHelper by injectLazy()

    override suspend fun fetch(): FetchResult {
        if (page.imageUrl.startsWith("/")) {
            return SourceResult(ImageSource(File(page.imageUrl).toOkioPath(), FileSystem.SYSTEM), null, DataSource.DISK)
        }
        val key = page.imageUrl
        diskCache?.openSnapshot(key)?.let { snapshot ->
            return SourceResult(ImageSource(snapshot.data, diskCache.fileSystem, key, snapshot), null, DataSource.DISK)
        }
        val bytes = page.fetch(network.client).use { response ->
            val body = response.body
            withContext(Dispatchers.IO) { body.bytes() }
        }
        diskCache?.openEditor(key)?.let { editor ->
            try {
                diskCache.fileSystem.write(editor.data) { write(bytes) }
                editor.commitAndOpenSnapshot()?.let { snapshot ->
                    return SourceResult(ImageSource(snapshot.data, diskCache.fileSystem, key, snapshot), null, DataSource.NETWORK)
                }
            } catch (e: Exception) {
                runCatching { editor.abort() }
            }
        }
        return SourceResult(ImageSource(Buffer().write(bytes), options.context), null, DataSource.NETWORK)
    }

    class Factory : Fetcher.Factory<BackendPageImage> {
        override fun create(data: BackendPageImage, options: Options, imageLoader: ImageLoader): Fetcher =
            PageFetcher(data, options, imageLoader.diskCache)
    }
}
