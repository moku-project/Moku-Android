package dev.moku.mobile.backend

import android.content.Context
import dev.moku.mobile.extension.ExtensionInstaller
import dev.moku.mobile.extension.ExtensionLoader
import dev.moku.mobile.extension.RuntimeBootstrap
import dev.moku.mobile.novel.NovelPluginCache
import dev.moku.mobile.novel.NovelPluginLoader
import dev.moku.mobile.remote.RemoteTsunaguBackend
import dev.moku.mobile.remote.ServerSession
import dev.moku.mobile.sync.ServerOrigin
import dev.moku.mobile.sync.ServerSources

/** Rebuilds a backend from its originId, `local:<type>:<packageName>`, e.g. for a download resumed after a restart. */
object LocalBackendFactory {
    class UnresolvableOriginException(originId: String, message: String = "Cannot reconstruct local backend for originId=$originId") : Exception(message)

    suspend fun reconstruct(context: Context, originId: String): ContentBackend {
        ServerOrigin.parse(originId)?.let { ref ->
            ServerSession.init(context)
            val server = ServerSession.current
            if (server?.instanceId != ref.instanceId) {
                val name = ServerSession.nameOf(ref.instanceId) ?: "a server"
                throw UnresolvableOriginException(originId, "This title is from $name, which this phone isn't signed in to")
            }
            return RemoteTsunaguBackend(ServerSession.client, ref.instanceId, ref.extensionId, ServerSources.name(context, ref.extensionId) ?: server.name ?: "Server", ref.type)
        }
        val parts = originId.split(":", limit = 3)
        require(parts.size == 3 && parts[0] == "local") { "Not a local originId: $originId" }
        val (_, kind, packageName) = parts
        RuntimeBootstrap.ensure(context)
        val installer = ExtensionInstaller(context)

        return when (kind) {
            "manga" -> {
                if (!installer.isInstalled(packageName)) throw UnresolvableOriginException(originId)
                val loaded = ExtensionLoader.loadManga(context, installer.installedFile(packageName))
                LocalMangaBackend(loaded.source, loaded.packageName)
            }
            "anime" -> {
                if (!installer.isInstalled(packageName)) throw UnresolvableOriginException(originId)
                val loaded = ExtensionLoader.loadAnime(context, installer.installedFile(packageName))
                LocalAnimeBackend(loaded.source, loaded.packageName)
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
