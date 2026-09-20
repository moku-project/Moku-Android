package dev.moku.mobile.ui.search

import android.content.Context
import android.util.Log
import dev.moku.mobile.backend.BackendMedia
import dev.moku.mobile.backend.ContentBackend
import dev.moku.mobile.backend.LocalMangaBackend
import dev.moku.mobile.backend.LocalNovelBackend
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.backend.LocalAnimeBackend
import dev.moku.mobile.extension.ExtensionInstaller
import dev.moku.mobile.extension.ExtensionLoader
import dev.moku.mobile.extension.RuntimeBootstrap
import dev.moku.mobile.novel.NovelPluginCache
import dev.moku.mobile.novel.NovelPluginLoader
import dev.moku.mobile.repository.LocalRepositoryManager
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.injectLazy

enum class SearchTab(val label: String, val contentType: MediaContentType) {
    MANGA("Manga", MediaContentType.MANGA),
    NOVEL("Novel", MediaContentType.NOVEL),
    ANIME("Anime", MediaContentType.ANIME),
}

sealed interface SearchBackendResult {
    data class Ready(val backend: ContentBackend) : SearchBackendResult
    /** Anime has no local extension with a searchable public catalog yet — Aniyomi's community
     * extensions repo has been pruned down to personal-media-server sources (Jellyfin, Google
     * Drive) rather than public anime sites. */
    data class Unavailable(val reason: String) : SearchBackendResult
}

/**
 * Lazily builds (and caches per process, keyed by package) the real local backend behind each
 * searchable tab. Prefers whatever the user actually installed via the Extensions tab (real
 * Keiyoushi/Aniyomi repo extensions, tracked in [LocalRepositoryManager]) — the bundled
 * MangaTaro sample only kicks in as a manga fallback when nothing real is installed yet, so
 * Search never comes up completely empty on a fresh install.
 */
object SearchBackends {
    private const val SAMPLE_MANGA_PACKAGE = "eu.kanade.tachiyomi.extension.all.mangataro"
    private const val SAMPLE_MANGA_ASSET = "extensions/mangataro.apk"
    private const val NOVEL_PACKAGE = "lnreader.allnovelfull"
    private const val NOVEL_PLUGIN_URL = "https://raw.githubusercontent.com/lnreader/lnreader-plugins/plugins/v3.0.0/.js/src/plugins/english/AllNovelFull%5Breadnovelfull%5D.js"

    private val network: NetworkHelper by injectLazy()
    private val backendCache = mutableMapOf<String, ContentBackend>()

    suspend fun forTab(context: Context, tab: SearchTab): SearchBackendResult = withContext(Dispatchers.IO) {
        try {
            RuntimeBootstrap.ensure(context)
            when (tab) {
                SearchTab.MANGA -> loadInstalledOrSample(context, MediaContentType.MANGA)
                SearchTab.ANIME -> loadInstalledOrSample(context, MediaContentType.ANIME)
                SearchTab.NOVEL -> SearchBackendResult.Ready(
                    backendCache.getOrPut(NOVEL_PACKAGE) { loadNovel(context) },
                )
            }
        } catch (t: Throwable) {
            Log.d("SearchBackends", "forTab(${tab.name}) failed", t)
            SearchBackendResult.Unavailable("Couldn't load this source. Try again shortly.")
        }
    }

    private suspend fun loadInstalledOrSample(context: Context, contentType: MediaContentType): SearchBackendResult {
        val installed = LocalRepositoryManager(context).getInstalledExtensions()
            .firstOrNull { it.contentType == contentType && it.enabled }

        if (installed != null) {
            val backend = backendCache.getOrPut(installed.packageName) { loadInstalledExtension(context, installed.packageName, contentType) }
            return SearchBackendResult.Ready(backend)
        }
        if (contentType == MediaContentType.MANGA) {
            return SearchBackendResult.Ready(backendCache.getOrPut(SAMPLE_MANGA_PACKAGE) { loadSampleManga(context) })
        }
        return SearchBackendResult.Unavailable(
            "No ${contentType.name.lowercase()} source installed yet. Add one from the Extensions tab.",
        )
    }

    private suspend fun loadInstalledExtension(context: Context, packageName: String, contentType: MediaContentType): ContentBackend {
        val installer = ExtensionInstaller(context)
        val file = installer.installedFile(packageName)
        return when (contentType) {
            MediaContentType.MANGA -> LocalMangaBackend(ExtensionLoader.loadManga(context, file).sources.first(), packageName)
            MediaContentType.ANIME -> LocalAnimeBackend(ExtensionLoader.loadAnime(context, file).sources.first(), packageName)
            MediaContentType.NOVEL -> error("Novel extensions aren't repo-installed yet")
        }
    }

    private suspend fun loadSampleManga(context: Context): ContentBackend {
        val installer = ExtensionInstaller(context)
        if (!installer.isInstalled(SAMPLE_MANGA_PACKAGE)) installer.installFromAsset(SAMPLE_MANGA_PACKAGE, SAMPLE_MANGA_ASSET)
        val loaded = ExtensionLoader.loadManga(context, installer.installedFile(SAMPLE_MANGA_PACKAGE))
        return LocalMangaBackend(loaded.sources.first(), loaded.packageName)
    }

    private suspend fun loadNovel(context: Context): ContentBackend {
        val cached = NovelPluginCache.load(context, NOVEL_PACKAGE)
        val jsCode = cached ?: network.client.newCall(Request.Builder().url(NOVEL_PLUGIN_URL).build()).execute().use { resp ->
            check(resp.isSuccessful) { "Failed to download plugin: HTTP ${resp.code}" }
            resp.body.string().also { NovelPluginCache.save(context, NOVEL_PACKAGE, it) }
        }
        val loaded = NovelPluginLoader.load(context, NOVEL_PACKAGE, jsCode)
        return LocalNovelBackend(loaded, NOVEL_PACKAGE)
    }
}

data class SearchState(
    val loading: Boolean = false,
    val unavailableReason: String? = null,
    val results: List<BackendMedia> = emptyList(),
)
