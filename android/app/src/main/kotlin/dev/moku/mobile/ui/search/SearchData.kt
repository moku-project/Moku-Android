package dev.moku.mobile.ui.search

import android.content.Context
import android.util.Log
import dev.moku.mobile.backend.BackendMedia
import dev.moku.mobile.backend.ContentBackend
import dev.moku.mobile.backend.LocalAnimeBackend
import dev.moku.mobile.backend.LocalMangaBackend
import dev.moku.mobile.backend.LocalNovelBackend
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.extension.ExtensionInstaller
import dev.moku.mobile.extension.ExtensionLoader
import dev.moku.mobile.extension.RuntimeBootstrap
import dev.moku.mobile.novel.NovelPluginCache
import dev.moku.mobile.novel.NovelPluginLoader
import dev.moku.mobile.repository.InstalledExtensionEntity
import dev.moku.mobile.repository.LocalRepositoryManager
import dev.moku.mobile.remote.RemoteTsunaguBackend
import dev.moku.mobile.remote.ServerSession
import dev.moku.mobile.sync.ServerSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One place Discover can browse: an extension on the phone, or one installed on the connected server. */
data class SourceOption(val key: String, val name: String, val onServer: Boolean, val packageName: String? = null)

object SearchBackends {
    // Searches load sources in parallel, so the cache is shared between coroutines.
    private val backendCache = java.util.concurrent.ConcurrentHashMap<String, ContentBackend>()

    /** The phone's own sources, or in server mode the server's; the two are never offered side by side. */
    suspend fun options(context: Context, type: MediaContentType): List<SourceOption> = withContext(Dispatchers.IO) {
        val server = ServerSession.current
        if (ServerSession.active && server != null) {
            val sources = runCatching { ServerSources.refresh(context) }.getOrElse { ServerSources.cached(context) }
            return@withContext sources.filter { it.contentType == type }
                .map { SourceOption("server:${server.instanceId}:${it.id}", it.name, onServer = true) }
        }
        LocalRepositoryManager(context).getInstalledExtensions()
            .filter { it.contentType == type && it.enabled }
            .map { SourceOption("local:${it.packageName}", it.name, onServer = false, packageName = it.packageName) }
    }

    /** The loaded backend for [option], kept so the next search doesn't load the extension again. */
    suspend fun backend(context: Context, option: SourceOption, type: MediaContentType): ContentBackend =
        backendCache[option.key] ?: withContext(Dispatchers.IO) { load(context, option, type) }.also { backendCache[option.key] = it }

    private suspend fun load(context: Context, pick: SourceOption, type: MediaContentType): ContentBackend {
        if (pick.onServer) {
            val (_, instanceId, extensionId) = pick.key.split(":", limit = 3)
            return RemoteTsunaguBackend(ServerSession.client, instanceId, extensionId, pick.name, type)
        }
        RuntimeBootstrap.ensure(context)
        val ext = LocalRepositoryManager(context).getInstalledExtensions().first { it.packageName == pick.packageName }
        return load(context, ext)
    }

    private suspend fun load(context: Context, ext: InstalledExtensionEntity): ContentBackend {
        val file = ExtensionInstaller(context).installedFile(ext.packageName)
        return when (ext.contentType) {
            MediaContentType.MANGA -> LocalMangaBackend(ExtensionLoader.loadManga(context, file).source, ext.packageName)
            MediaContentType.ANIME -> LocalAnimeBackend(ExtensionLoader.loadAnime(context, file).source, ext.packageName)
            MediaContentType.NOVEL -> {
                val js = NovelPluginCache.load(context, ext.packageName) ?: error("${ext.name} isn't installed")
                LocalNovelBackend(NovelPluginLoader.load(context, ext.packageName, js), ext.packageName)
            }
        }
    }
}
