package dev.moku.mobile.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import android.app.Application
import dev.moku.mobile.backend.BackendUnitContent
import eu.kanade.tachiyomi.network.NetworkHelper
import uy.kohesive.injekt.injectLazy

/**
 * Turns a [BackendUnitContent.Video] into a playable Media3 [MediaSource] with the
 * extension-provided request headers actually attached to every underlying HTTP request —
 * this is what makes Tsunagu's server-side HLS manifest-rewriting proxy (`serveHLS`,
 * `/content/.../hls?u=...&h=...`) unnecessary for a native app: that proxy exists because a
 * browser's `<video>` element can't attach custom headers to HLS segment/key requests, so the
 * Go server rewrites every URL in the manifest to point back through itself with headers
 * baked in. ExoPlayer has no such limitation — [OkHttpDataSource.Factory.setDefaultRequestProperties]
 * attaches the same headers to the manifest fetch *and* every segment/key fetch it triggers,
 * so the "resolution step" here is choosing the right [MediaSource] for the URL and wiring
 * headers through, not reimplementing a manifest proxy.
 *
 * No player screen exists yet to exercise this against a real stream — it's plumbing for
 * whichever future screen plays a [BackendUnitContent.Video], not itself live-verified.
 */
@UnstableApi
object HeaderMediaSourceFactory {
    private val network: NetworkHelper by injectLazy()
    private val application: Application by injectLazy()

    fun create(video: BackendUnitContent.Video): MediaSource {
        val httpFactory = OkHttpDataSource.Factory(network.client).apply {
            if (video.headers.isNotEmpty()) setDefaultRequestProperties(video.headers)
        }
        val dataSourceFactory = DefaultDataSource.Factory(application, httpFactory)
        val mediaItem = MediaItem.fromUri(video.streamUrl)

        val looksLikeHls = video.streamUrl.substringBefore('?').endsWith(".m3u8")
        return if (looksLikeHls) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }
    }
}
