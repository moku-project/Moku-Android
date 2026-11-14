package dev.moku.mobile.extension.repo

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.zip.GZIPInputStream

class ExtensionRepositoryClient {
    private val network: NetworkHelper by injectLazy()

    suspend fun fetchIndex(indexUrl: String): List<RepoExtension> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(indexUrl).build()
            network.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Failed to fetch repo index: HTTP ${response.code}")
                val raw = response.body!!.bytes()
                // index.pb is a raw gzip file, not Content-Encoding: gzip, so OkHttp won't unpack it.
                val decompressed = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                    GZIPInputStream(raw.inputStream()).use { it.readBytes() }
                } else {
                    raw
                }
                RepoIndexParser.parse(decompressed)
            }
        }
}
