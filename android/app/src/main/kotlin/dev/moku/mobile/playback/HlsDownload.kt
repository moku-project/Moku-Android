package dev.moku.mobile.playback

import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Saves an HLS episode as one file by joining its segments, like Tsunagu's download fallback.
internal object HlsDownload {
    private val ATTR = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private data class Key(val url: String, val iv: ByteArray?)
    private data class Segment(val url: String, val key: Key?, val sequence: Long, val range: Segments.Range?)

    fun save(client: OkHttpClient, url: String, headers: Map<String, String>, dir: File): File {
        val text = String(Segments.get(client, url, headers, null))
        if (!HlsPlaylists.isPlaylist(text)) throw IOException("The stream isn't an HLS playlist")
        if ("#EXT-X-STREAM-INF" !in text) return saveTrack(client, url, text, headers, dir, "video").let { finish(it, dir) }

        val (variantUrl, audioGroup) = bestVariant(text, url)
        val video = saveTrack(client, variantUrl, null, headers, dir, "video")
        val audioUrl = audioGroup?.let { audioRendition(text, url, it) } ?: return finish(video, dir)
        val audio = saveTrack(client, audioUrl, null, headers, dir, "audio")
        val out = File(dir, "video.mp4")
        try {
            TrackMuxer.mux(video, audio, out)
        } finally {
            video.delete()
            audio.delete()
        }
        return out
    }

    private fun finish(track: File, dir: File): File {
        val file = File(dir, "video.${track.extension}")
        if (!track.renameTo(file)) throw IOException("Couldn't save the episode")
        return file
    }

    /** Writes one media playlist's segments to a file, .mp4 for fMP4 segments and .ts otherwise. */
    private fun saveTrack(client: OkHttpClient, url: String, known: String?, headers: Map<String, String>, dir: File, name: String): File {
        val text = known ?: String(Segments.get(client, url, headers, null))
        var init: Pair<String, Segments.Range?>? = null
        val all = parse(text, url) { map, range -> init = map to range }
        val segments = if (all.any { !HlsPlaylists.isAd(it.url) }) all.filterNot { HlsPlaylists.isAd(it.url) } else all
        if (segments.isEmpty()) throw IOException("The playlist has no segments")

        val file = File(dir, "$name.part.${if (init != null) "mp4" else "ts"}")
        val keys = HashMap<String, ByteArray>()
        file.outputStream().buffered().use { out ->
            init?.let { (mapUrl, range) -> out.write(Segments.get(client, mapUrl, headers, range)) }
            for (segment in segments) {
                var data = Segments.retry { Segments.get(client, segment.url, headers, segment.range) }
                segment.key?.let { key ->
                    val secret = keys.getOrPut(key.url) { Segments.get(client, key.url, headers, null) }
                    data = decrypt(data, secret, key.iv ?: sequenceIv(segment.sequence))
                }
                if (init == null && data.isNotEmpty() && data[0] != 0x47.toByte()) {
                    val start = HlsPlaylists.tsStart(data)
                    if (start > 0) data = data.copyOfRange(start, data.size)
                }
                out.write(data)
            }
        }
        return file
    }

    private fun bestVariant(master: String, base: String): Pair<String, String?> {
        val lines = master.lines()
        var best: Triple<Long, String, String?>? = null
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val attrs = attributes(line)
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: continue
            val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0
            if (best == null || bandwidth > best.first) best = Triple(bandwidth, HlsPlaylists.resolve(base, uri), attrs["AUDIO"])
        }
        return best?.let { it.second to it.third } ?: throw IOException("The playlist lists no streams")
    }

    // The group's default rendition, else its first; null when the audio is already inside the video.
    private fun audioRendition(master: String, base: String, group: String): String? {
        val renditions = master.lines().filter { it.startsWith("#EXT-X-MEDIA") }.map(::attributes)
            .filter { it["TYPE"] == "AUDIO" && it["GROUP-ID"] == group && it["URI"] != null }
        val pick = renditions.firstOrNull { it["DEFAULT"] == "YES" } ?: renditions.firstOrNull() ?: return null
        return HlsPlaylists.resolve(base, pick.getValue("URI"))
    }

    private fun parse(text: String, base: String, onMap: (String, Segments.Range?) -> Unit): List<Segment> {
        val segments = mutableListOf<Segment>()
        var key: Key? = null
        var sequence = 0L
        var range: Segments.Range? = null
        var nextOffset = 0L
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').toLongOrNull() ?: 0
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = attributes(line)
                    key = when (attrs["METHOD"]) {
                        null, "NONE" -> null
                        "AES-128" -> Key(HlsPlaylists.resolve(base, attrs["URI"] ?: throw IOException("Encryption key has no URI")), attrs["IV"]?.let(::hex))
                        else -> throw IOException("This episode uses ${attrs["METHOD"]} encryption, which can't be downloaded")
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = attributes(line)
                    attrs["URI"]?.let { onMap(HlsPlaylists.resolve(base, it), attrs["BYTERANGE"]?.let { r -> byteRange(r, 0) }) }
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> range = byteRange(line.substringAfter(':'), nextOffset)
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    segments += Segment(HlsPlaylists.resolve(base, line), key, sequence++, range)
                    nextOffset = range?.let { it.offset + it.length } ?: 0
                    range = null
                }
            }
        }
        return segments
    }

    private fun attributes(line: String): Map<String, String> =
        ATTR.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    private fun byteRange(spec: String, defaultOffset: Long): Segments.Range {
        val length = spec.substringBefore('@').trim().toLong()
        val offset = spec.substringAfter('@', "").trim().toLongOrNull() ?: defaultOffset
        return Segments.Range(offset, length)
    }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    private fun sequenceIv(sequence: Long): ByteArray = ByteBuffer.allocate(16).putLong(8, sequence).array()

    private fun hex(s: String): ByteArray {
        val clean = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(16) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** Fetching for segmented downloads, with the same default Referer as playback. */
internal object Segments {
    data class Range(val offset: Long, val length: Long)

    private const val RETRIES = 3

    fun get(client: OkHttpClient, url: String, headers: Map<String, String>, range: Range?): ByteArray {
        val request = StreamProbe.request(url, headers).apply {
            range?.let { header("Range", "bytes=${it.offset}-${it.offset + it.length - 1}") }
        }.build()
        return client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("A segment answered HTTP ${r.code}")
            r.body.bytes()
        }
    }

    fun <T> retry(block: () -> T): T {
        var last: IOException? = null
        repeat(RETRIES) {
            try { return block() } catch (e: IOException) { last = e }
        }
        throw last!!
    }
}
