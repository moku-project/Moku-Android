package dev.moku.mobile.novel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class NovelItem(val name: String, val path: String, val cover: String?)
data class NovelChapterItem(val name: String, val path: String, val chapterNumber: Double?, val releaseTime: String?)
data class SourceNovel(
    val name: String,
    val path: String,
    val cover: String?,
    val genres: String?,
    val summary: String?,
    val author: String?,
    val status: String?,
    val chapters: List<NovelChapterItem>,
)

/**
 * Loads one novel plugin (a raw JS file, not an APK — Keiyoushi/LNReader plugins are plain
 * CommonJS modules) into a [NovelJsRuntime] and exposes its four methods with Kotlin types,
 * mirroring Tsunagu's own `NovelPlugin.kt` shape so this vendoring stays recognizable
 * against the JVM version, just over a WebView bridge instead of GraalVM.
 */
object NovelPluginLoader {

    suspend fun load(context: Context, packageName: String, jsCode: String): LoadedNovelExtension {
        val runtime = NovelJsRuntime.create(context)
        val handle = runtime.loadPlugin(jsCode, storageNamespace = packageName)
        return LoadedNovelExtension(handle)
    }
}

class LoadedNovelExtension(private val handle: NovelPluginHandle) {
    val id: String get() = handle.id
    val name: String get() = handle.name
    val site: String get() = handle.site
    val lang: String get() = handle.lang
    val version: String get() = handle.version

    suspend fun popularNovels(page: Int, showLatest: Boolean = false): List<NovelItem> {
        val json = handle.runtime.call("popularNovels", listOf(page, JSONObject().put("filters", JSONObject()).put("showLatestNovels", showLatest)))
        return JSONArray(json).toNovelItemList()
    }

    suspend fun searchNovels(query: String, page: Int): List<NovelItem> {
        val json = handle.runtime.call("searchNovels", listOf(query, page))
        return JSONArray(json).toNovelItemList()
    }

    // Plugins commonly return a cover path relative to their own site (or none at all);
    // resolve it against `site` so Coil gets an absolute URL instead of a dead request.
    private fun resolveCover(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return runCatching { java.net.URL(java.net.URL(site), raw).toString() }.getOrNull()
    }

    suspend fun parseNovel(novelPath: String): SourceNovel {
        val json = handle.runtime.call("parseNovel", listOf(novelPath))
        val obj = JSONObject(json)
        val chapters = obj.optJSONArray("chapters") ?: JSONArray()
        return SourceNovel(
            name = obj.optString("name", novelPath),
            path = obj.optString("path", novelPath),
            cover = resolveCover(obj.optString("cover").ifBlank { null }),
            genres = obj.optString("genres").ifBlank { null },
            summary = obj.optString("summary").ifBlank { null },
            author = obj.optString("author").ifBlank { null },
            status = obj.optString("status").ifBlank { null },
            chapters = (0 until chapters.length()).map { i ->
                val c = chapters.getJSONObject(i)
                NovelChapterItem(
                    name = c.optString("name", "Chapter"),
                    path = c.optString("path", ""),
                    chapterNumber = if (c.has("chapterNumber") && !c.isNull("chapterNumber")) c.optDouble("chapterNumber") else null,
                    releaseTime = c.optString("releaseTime").ifBlank { null },
                )
            },
        )
    }

    suspend fun parseChapter(chapterPath: String): String {
        val json = handle.runtime.call("parseChapter", listOf(chapterPath))
        // parseChapter resolves to a plain string (chapter HTML/text), which JSON.stringify
        // wraps in quotes — unwrap via JSONObject's tokenizer isn't needed, org.json can
        // parse a bare JSON string value directly via JSONTokener.
        return org.json.JSONTokener(json).nextValue() as? String ?: json
    }

    private fun JSONArray.toNovelItemList(): List<NovelItem> = (0 until length()).map { i ->
        val o = getJSONObject(i)
        NovelItem(name = o.optString("name", ""), path = o.optString("path", ""), cover = resolveCover(o.optString("cover").ifBlank { null }))
    }
}
