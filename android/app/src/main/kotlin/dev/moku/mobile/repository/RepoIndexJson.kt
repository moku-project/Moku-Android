package dev.moku.mobile.repository

import dev.moku.mobile.extension.repo.ExtensionRepositoryClient
import dev.moku.mobile.extension.repo.RepoExtension
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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

// Resolves a repo the way Tsunagu does: a repo.json's index_v2 first (some repos serve a
// placeholder index.min.json to apps that skip it), then index.pb, then the JSON index.
class GenericRepoIndexClient {
    private val network: NetworkHelper by injectLazy()
    private val protobufClient = ExtensionRepositoryClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchIndex(url: String): List<RepoExtension> {
        val given = url.trim()
        val isFile = given.endsWith(".json") || given.endsWith(".pb")
        val dir = if (isFile) given.substringBeforeLast('/') + "/" else given.trimEnd('/') + "/"

        indexV2(dir)?.let { v2 -> runCatching { return protobufClient.fetchIndex(v2) } }
        if (given.endsWith(".pb")) return protobufClient.fetchIndex(given)
        if (!given.endsWith(".json")) runCatching { return protobufClient.fetchIndex("${dir}index.pb") }
        return fetchJsonIndex(dir, if (given.endsWith(".json") && !given.endsWith("repo.json")) given else "${dir}index.min.json")
    }

    private suspend fun indexV2(dir: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            network.client.newCall(Request.Builder().url("${dir}repo.json").build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body.string()).optString("index_v2").ifBlank { null }
            }
        }.getOrNull()
    }

    private suspend fun fetchJsonIndex(dir: String, indexUrl: String): List<RepoExtension> = withContext(Dispatchers.IO) {
        network.client.newCall(Request.Builder().url(indexUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Couldn't load $indexUrl (HTTP ${response.code})")
            val body = response.body.string()
            if (JSONArray(body).optJSONObject(0)?.has("pkg") == false) return@use lnReaderPlugins(body)
            json.decodeFromString<List<JsonRepoExtension>>(body).map {
                RepoExtension(
                    name = it.name.removePrefix("Tachiyomi: ").removePrefix("Aniyomi: "),
                    packageName = it.pkg,
                    apkUrl = "${dir}apk/${it.apk}",
                    jarUrl = "",
                    iconUrl = "${dir}icon/${it.pkg}.png",
                    versionName = it.version,
                    versionCode = it.code,
                    isNsfw = it.nsfw != 0,
                    lang = it.lang,
                )
            }
        }
    }

    // LNReader plugin lists: each entry's url is the plugin's JS source.
    private fun lnReaderPlugins(body: String): List<RepoExtension> {
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            RepoExtension(
                name = o.optString("name", id),
                packageName = id,
                apkUrl = o.optString("url"),
                jarUrl = "",
                iconUrl = o.optString("iconUrl"),
                versionName = o.optString("version"),
                versionCode = 0,
                isNsfw = false,
                lang = o.optString("lang").replace("‎", "").trim(),
            )
        }
    }
}
