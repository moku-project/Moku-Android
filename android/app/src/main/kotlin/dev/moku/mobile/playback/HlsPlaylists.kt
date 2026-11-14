package dev.moku.mobile.playback

import java.net.URI

/** HLS playlist handling shared by playback and downloads, matching Tsunagu's stream proxy. */
internal object HlsPlaylists {
    private val AD_MARKERS = listOf(
        "ibyteimg.com", "ad-site-i18n", "/ads/", "/adserver",
        "doubleclick", "googlesyndication", "2mdn.net", "adsafeprotected", "imasdk",
    )
    private val PER_SEGMENT_TAGS = listOf("#EXTINF", "#EXT-X-DISCONTINUITY", "#EXT-X-PROGRAM-DATE-TIME", "#EXT-X-BYTERANGE")

    fun isAd(uri: String): Boolean = AD_MARKERS.any { it in uri.lowercase() }

    fun resolve(base: String, ref: String): String =
        runCatching { URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref.trim())

    fun isPlaylist(text: String) = text.trimStart('﻿', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")

    fun uriLines(text: String): List<String> = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

    /** Drops ad segments and their tags, unless the whole episode sits on ad hosts. */
    fun withoutAds(text: String, base: String): String {
        if (uriLines(text).none { !isAd(resolve(base, it)) }) return text
        val out = StringBuilder(text.length)
        val pending = mutableListOf<String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#") && PER_SEGMENT_TAGS.any { trimmed.startsWith(it) } -> pending += line
                trimmed.isEmpty() || trimmed.startsWith("#") -> { pending.forEach { out.append(it).append('\n') }; pending.clear(); out.append(line).append('\n') }
                isAd(resolve(base, trimmed)) -> pending.clear()
                else -> { pending.forEach { out.append(it).append('\n') }; pending.clear(); out.append(line).append('\n') }
            }
        }
        pending.forEach { out.append(it).append('\n') }
        return out.toString()
    }

    private const val TS_PACKET = 188
    const val TS_SCAN = 64 * 1024 + 3 * TS_PACKET

    /** Where MPEG-TS starts in [data] (three packets in a row), or -1. Some hosts hide segments behind a small image. */
    fun tsStart(data: ByteArray, size: Int = data.size): Int {
        var i = 0
        while (i < 64 * 1024 && i + 2 * TS_PACKET < size) {
            if (data[i] == 0x47.toByte() && data[i + TS_PACKET] == 0x47.toByte() && data[i + 2 * TS_PACKET] == 0x47.toByte()) return i
            i++
        }
        return -1
    }
}
