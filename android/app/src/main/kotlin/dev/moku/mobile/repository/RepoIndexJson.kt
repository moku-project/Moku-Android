package dev.moku.mobile.repository

import dev.moku.mobile.extension.repo.ExtensionRepositoryClient
import dev.moku.mobile.extension.repo.RepoExtension
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.io.IOException

@Serializable
private data class JsonRepoExtension(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long = 0,
    val version: String = "",
    val nsfw: Int = 0,
)

/**
 * Both extension repos this app targets (Keiyoushi's manga repo, Aniyomi's anime repo) are
 * built by the same Tachiyomi/Mihon repo-generator tooling, but only one of the two formats
 * it emits actually works for each in practice: Keiyoushi's `index.pb` carries a fully
 * resolved absolute `apkUrl` per extension (used by [ExtensionRepositoryClient] already);
 * Aniyomi's repo doesn't publish an `index.pb` at all (confirmed: 404), only `index.min.json`,
 * whose `apk` field is a bare filename you resolve yourself as `<repoBase>apk/<apk>` — which
 * in turn 404s for Keiyoushi's actual APK hosting (a different CDN, not the `apk/` convention).
 * So this tries the protobuf index first and falls back to the JSON index + convention-based
 * apkUrl, rather than assuming either format alone covers every repo a user might add.
 */
class GenericRepoIndexClient {
    private val network: NetworkHelper by injectLazy()
    private val protobufClient = ExtensionRepositoryClient()
    private val json = Json { ignoreUnknownKeys = true }

    /** [repoBaseUrl] is the repo root, e.g. "https://raw.githubusercontent.com/keiyoushi/extensions/repo/". */
    suspend fun fetchIndex(repoBaseUrl: String): List<RepoExtension> {
        val base = repoBaseUrl.trimEnd('/') + "/"
        return try {
            protobufClient.fetchIndex("${base}index.pb")
        } catch (_: Exception) {
            fetchJsonIndex(base)
        }
    }

    private suspend fun fetchJsonIndex(base: String): List<RepoExtension> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${base}index.min.json").build()
        network.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to fetch $base index.min.json: HTTP ${response.code}")
            val entries = json.decodeFromString<List<JsonRepoExtension>>(response.body.string())
            entries.map {
                RepoExtension(
                    name = it.name,
                    packageName = it.pkg,
                    apkUrl = "$base" + "apk/" + it.apk,
                    jarUrl = "",
                    iconUrl = "",
                    versionName = it.version,
                    versionCode = it.code,
                    isNsfw = it.nsfw != 0,
                    lang = it.lang,
                )
            }
        }
    }
}
