package dev.moku.mobile.playback

import android.app.Application
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import dev.moku.mobile.backend.SubtitleTrack
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

// OkHttp default request properties reach every manifest, segment and key fetch, so extension headers apply to HLS and DASH too.
@UnstableApi
object HeaderMediaSourceFactory {
    private val network: NetworkHelper by injectLazy()
    private val application: Application by injectLazy()

    /** Checks the stream and loads its subtitles in parallel; throws [StreamUnavailable] when the stream is no good. */
    suspend fun create(
        url: String,
        headers: Map<String, String>,
        subtitles: List<SubtitleTrack> = emptyList(),
        sourceClient: OkHttpClient? = null,
    ): MediaSource = coroutineScope {
        val loaded = async { SubtitleLoader.load(application, subtitles, network.client, sourceClient) }
        val kind = withContext(Dispatchers.IO) { StreamProbe.check(network.client, url, headers) }
        val http = OkHttpDataSource.Factory(network.client).apply {
            if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
        }
        val data = StreamFixDataSource.Factory(
            DefaultDataSource.Factory(application, http),
            addReferer = headers.keys.none { it.equals("Referer", true) },
        )
        val item = MediaItem.Builder()
            .setUri(url)
            .apply {
                when (kind) {
                    StreamKind.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    StreamKind.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                    StreamKind.PROGRESSIVE -> Unit
                }
            }
            .setSubtitleConfigurations(
                loaded.await().map {
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(it.file))
                        .setMimeType(it.mime)
                        .setLabel(it.label)
                        .build()
                },
            )
            .build()
        DefaultMediaSourceFactory(data).createMediaSource(item)
    }
}
