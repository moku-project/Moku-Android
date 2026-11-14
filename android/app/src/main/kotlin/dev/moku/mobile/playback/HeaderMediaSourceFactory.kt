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
import uy.kohesive.injekt.injectLazy

// OkHttp default request properties reach every manifest, segment and key fetch, so
// extension headers work for HLS without Tsunagu's server-side manifest rewriting.
@UnstableApi
object HeaderMediaSourceFactory {
    private val network: NetworkHelper by injectLazy()
    private val application: Application by injectLazy()

    fun create(url: String, headers: Map<String, String>, subtitles: List<SubtitleTrack> = emptyList()): MediaSource {
        val http = OkHttpDataSource.Factory(network.client).apply {
            if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
        }
        val path = url.substringBefore('?').lowercase()
        val item = MediaItem.Builder()
            .setUri(url)
            .apply {
                when {
                    path.endsWith(".m3u8") || path.endsWith("/m3u8") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                }
            }
            .setSubtitleConfigurations(
                subtitles.map {
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(it.url))
                        .setMimeType(subtitleMime(it.url))
                        .setLabel(it.label)
                        .build()
                },
            )
            .build()
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(application, http)).createMediaSource(item)
    }

    private fun subtitleMime(url: String): String = when (url.substringBefore('?').substringAfterLast('.').lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "xml", "dfxp" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
    }
}
