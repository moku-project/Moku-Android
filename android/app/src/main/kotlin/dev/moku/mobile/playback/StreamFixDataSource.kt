package dev.moku.mobile.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream

// Tsunagu's stream proxy on the phone: a default Referer per host, ad segments dropped from HLS playlists,
// and TS segments hidden behind an image unwrapped.
@UnstableApi
internal class StreamFixDataSource(private val upstream: DataSource, private val addReferer: Boolean) : DataSource {
    private var memory: ByteArray? = null
    private var memoryPos = 0
    private var prefix: ByteArray? = null
    private var prefixPos = 0

    class Factory(private val upstream: DataSource.Factory, private val addReferer: Boolean) : DataSource.Factory {
        override fun createDataSource(): DataSource = StreamFixDataSource(upstream.createDataSource(), addReferer)
    }

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val web = uri.scheme == "http" || uri.scheme == "https"
        val spec = if (web && addReferer && dataSpec.httpRequestHeaders.keys.none { it.equals("Referer", true) }) {
            dataSpec.withAdditionalHeaders(mapOf("Referer" to "${uri.scheme}://${uri.encodedAuthority}/"))
        } else {
            dataSpec
        }
        val length = upstream.open(spec)
        if (!web || spec.position != 0L || spec.length != C.LENGTH_UNSET.toLong()) return length

        val type = upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull()?.lowercase().orEmpty()
        val path = uri.path.orEmpty().lowercase()
        if ("mpegurl" in type || path.endsWith(".m3u8") || path.endsWith("/m3u8")) {
            val body = readAll()
            val text = String(body, Charsets.UTF_8)
            val base = upstream.uri?.toString() ?: uri.toString()
            return serve(if (HlsPlaylists.isPlaylist(text)) HlsPlaylists.withoutAds(text, base).toByteArray() else body)
        }
        if (type.startsWith("image/")) {
            val head = ByteArray(HlsPlaylists.TS_SCAN)
            var n = 0
            while (n < head.size) {
                val r = upstream.read(head, n, head.size - n)
                if (r == C.RESULT_END_OF_INPUT) break
                n += r
            }
            val start = HlsPlaylists.tsStart(head, n).coerceAtLeast(0)
            prefix = head.copyOfRange(start, n)
            prefixPos = 0
            return if (length == C.LENGTH_UNSET.toLong()) length else length - start
        }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        memory?.let { m ->
            if (memoryPos >= m.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, m.size - memoryPos)
            System.arraycopy(m, memoryPos, buffer, offset, n)
            memoryPos += n
            return n
        }
        prefix?.let { p ->
            if (prefixPos < p.size) {
                val n = minOf(length, p.size - prefixPos)
                System.arraycopy(p, prefixPos, buffer, offset, n)
                prefixPos += n
                return n
            }
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        memory = null
        prefix = null
        upstream.close()
    }

    private fun serve(bytes: ByteArray): Long {
        memory = bytes
        memoryPos = 0
        return bytes.size.toLong()
    }

    private fun readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (out.size() < MAX_PLAYLIST) {
            val r = upstream.read(buf, 0, buf.size)
            if (r == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_PLAYLIST = 8 * 1024 * 1024
    }
}
