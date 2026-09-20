package dev.moku.mobile.extension.repo

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.zip.GZIPInputStream

/** Default Keiyoushi repo index, same one bundled Moku/Tsunagu point at. */
const val DEFAULT_KEIYOUSHI_INDEX_URL = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"

class ExtensionRepositoryClient {
    private val network: NetworkHelper by injectLazy()

    suspend fun fetchIndex(indexUrl: String = DEFAULT_KEIYOUSHI_INDEX_URL): List<RepoExtension> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(indexUrl).build()
            network.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Failed to fetch repo index: HTTP ${response.code}")
                val raw = response.body!!.bytes()
                // index.pb is served as a raw gzip file (not Content-Encoding: gzip, so OkHttp
                // won't decompress it transparently) — same reason Tsunagu's Go client imports
                // compress/gzip before handing bytes to its protobuf decoder.
                val decompressed = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                    GZIPInputStream(raw.inputStream()).use { it.readBytes() }
                } else {
                    raw
                }
                RepoIndexParser.parse(decompressed)
            }
        }
}
