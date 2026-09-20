package dev.moku.mobile.backend

import android.content.Context
import dev.moku.mobile.extension.ExtensionInstaller
import dev.moku.mobile.extension.ExtensionLoader
import dev.moku.mobile.extension.RuntimeBootstrap
import dev.moku.mobile.novel.NovelPluginCache
import dev.moku.mobile.novel.NovelPluginLoader

/**
 * Rebuilds a local [ContentBackend] from nothing but its [ContentBackend.originId] — needed
 * anywhere that outlives the in-memory instance that first loaded it, chiefly
 * [dev.moku.mobile.library.DownloadWorker] resuming a download after the app process was
 * killed. `originId` already encodes everything needed: `local:<manga|anime|novel>:<packageName>`.
 */
object LocalBackendFactory {
    class UnresolvableOriginException(originId: String) : Exception("Cannot reconstruct local backend for originId=$originId")

    suspend fun reconstruct(context: Context, originId: String): ContentBackend {
        val parts = originId.split(":", limit = 3)
        require(parts.size == 3 && parts[0] == "local") { "Not a local originId: $originId" }
        val (_, kind, packageName) = parts
        RuntimeBootstrap.ensure(context)
        val installer = ExtensionInstaller(context)

        return when (kind) {
            "manga" -> {
                if (!installer.isInstalled(packageName)) throw UnresolvableOriginException(originId)
                val loaded = ExtensionLoader.loadManga(context, installer.installedFile(packageName))
                LocalMangaBackend(loaded.sources.first(), loaded.packageName)
            }
            "anime" -> {
                if (!installer.isInstalled(packageName)) throw UnresolvableOriginException(originId)
                val loaded = ExtensionLoader.loadAnime(context, installer.installedFile(packageName))
                LocalAnimeBackend(loaded.sources.first(), loaded.packageName)
            }
            "novel" -> {
                val jsCode = NovelPluginCache.load(context, packageName) ?: throw UnresolvableOriginException(originId)
                val loaded = NovelPluginLoader.load(context, packageName, jsCode)
                LocalNovelBackend(loaded, packageName)
            }
            else -> throw UnresolvableOriginException(originId)
        }
    }
}
