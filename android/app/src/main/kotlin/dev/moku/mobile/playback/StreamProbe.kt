package dev.moku.mobile.playback

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

internal enum class StreamKind { PROGRESSIVE, HLS, DASH }

/** The stream answered with an error or held nothing but ads, so another source should be tried. */
internal class StreamUnavailable(message: String, val code: Int = 0) : IOException(message)

// Tsunagu's probe: the stream must answer, and an HLS stream must hold more than ads.
internal object StreamProbe {
    fun check(client: OkHttpClient, url: String, headers: Map<String, String>): StreamKind {
        val byPath = kindFromPath(url)
        if (Uri.parse(url).scheme.let { it != "http" && it != "https" }) return byPath ?: StreamKind.PROGRESSIVE
        val builder = request(url, headers)
        if (byPath == StreamKind.PROGRESSIVE || byPath == null) builder.header("Range", "bytes=0-0")
        client.newCall(builder.build()).execute().use { response ->
            if (response.code >= 400) throw StreamUnavailable("The stream answered HTTP ${response.code}", response.code)
            val type = response.header("Content-Type").orEmpty().lowercase()
            val kind = byPath ?: when {
                "mpegurl" in type -> StreamKind.HLS
                "dash+xml" in type -> StreamKind.DASH
                else -> StreamKind.PROGRESSIVE
            }
            if (kind == StreamKind.HLS) {
                val body = if (byPath == StreamKind.HLS) response.body.string() else null
                if (!hasContent(client, response.request.url.toString(), headers, body, depth = 2)) {
                    throw StreamUnavailable("This source only plays ads")
                }
            }
            return kind
        }
    }

    fun kindFromPath(url: String): StreamKind? {
        val path = Uri.parse(url).path.orEmpty().lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith("/m3u8") -> StreamKind.HLS
            path.endsWith(".mpd") -> StreamKind.DASH
            listOf(".mp4", ".mkv", ".webm", ".ts", ".mov", ".m4v").any { path.endsWith(it) } -> StreamKind.PROGRESSIVE
            else -> null
        }
    }

    /** Hosts that check Referer accept their own origin, which is what Tsunagu sends when the source names none. */
    fun request(url: String, headers: Map<String, String>): Request.Builder {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (headers.keys.none { it.equals("Referer", true) }) {
            Uri.parse(url).let { builder.header("Referer", "${it.scheme}://${it.encodedAuthority}/") }
        }
        return builder
    }

    private fun hasContent(client: OkHttpClient, url: String, headers: Map<String, String>, body: String?, depth: Int): Boolean {
        val text = body ?: client.newCall(request(url, headers).build()).execute().use { r ->
            if (r.code >= 400) return false
            r.body.string()
        }
        val nested = mutableListOf<String>()
        for (line in HlsPlaylists.uriLines(text)) {
            val abs = HlsPlaylists.resolve(url, line)
            when {
                ".m3u8" in abs.lowercase() -> nested += abs
                !HlsPlaylists.isAd(abs) -> return true
            }
        }
        return depth > 0 && nested.any { runCatching { hasContent(client, it, headers, null, depth - 1) }.getOrDefault(false) }
    }
}
