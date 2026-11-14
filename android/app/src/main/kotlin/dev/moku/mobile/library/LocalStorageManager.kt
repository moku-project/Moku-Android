package dev.moku.mobile.library

import android.content.Context
import dev.moku.mobile.remote.ServerSession
import dev.moku.mobile.sync.ServerOrigin
import dev.moku.mobile.sync.ServerSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class StorageCategoryInfo(val key: String, val label: String, val path: String, val bytes: Long, val fileCount: Int)
data class LocalStorageInfo(val usedBytes: Long, val freeBytes: Long, val totalBytes: Long, val categories: List<StorageCategoryInfo>)

/** What Moku keeps on this phone, with downloads split by where they came from: the phone's library or each server. */
class LocalStorageManager(private val context: Context) {
    private val downloadsDir get() = File(context.filesDir, "downloads")

    suspend fun storageInfo(): LocalStorageInfo = withContext(Dispatchers.IO) {
        val categories = listOf(category("extensions", "Extensions", File(context.filesDir, "exts"))) +
            downloadCategories() +
            listOf(
                category("novel_plugins", "Novel plugins", File(context.filesDir, "novel_plugins")),
                category("database", "Library database", context.getDatabasePath("moku_local_library.db")),
            )
        val stat = android.os.StatFs(context.filesDir.path)
        LocalStorageInfo(
            usedBytes = categories.sumOf { it.bytes },
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
            categories = categories,
        )
    }

    suspend fun clearCategory(key: String) = withContext(Dispatchers.IO) {
        when {
            key == "extensions" -> File(context.filesDir, "exts").deleteContentsOnly()
            key == "novel_plugins" -> File(context.filesDir, "novel_plugins").deleteContentsOnly()
            key == "downloads" -> clearDownloads(null)
            key.startsWith(SERVER_KEY) -> clearDownloads(key.removePrefix(SERVER_KEY))
            else -> throw IllegalArgumentException("unknown storage category $key")
        }
        Unit
    }

    /** The phone's downloads when [instanceId] is null. A server this phone has left takes everything it left behind. */
    private suspend fun clearDownloads(instanceId: String?) {
        if (instanceId != null && instanceId != ServerSession.current?.instanceId) {
            ServerSync.forget(context, instanceId)
        } else {
            val repo = LocalLibraryRepository(context)
            val mine = repo.getDownloads().filter { ServerOrigin.parse(it.originId)?.instanceId == instanceId }
            DownloadQueueManager(context, repo).remove(mine)
        }
        downloadDirs()[instanceId].orEmpty().forEach { it.deleteRecursively() }
    }

    private fun downloadCategories(): List<StorageCategoryInfo> {
        val byOwner = downloadDirs()
        val phone = sum("downloads", "Downloads", byOwner[null].orEmpty())
        val servers = byOwner.keys.filterNotNull().sorted().map { id ->
            sum(SERVER_KEY + id, "From ${ServerSession.nameOf(id) ?: "a server"}", byOwner[id].orEmpty())
        }
        return listOf(phone) + servers
    }

    // Download folders are named after their sanitized originId, so a server's start with server_<instanceId>_.
    private fun downloadDirs(): Map<String?, List<File>> =
        downloadsDir.listFiles()?.filter { it.isDirectory }.orEmpty().groupBy { SERVER_DIR.find(it.name)?.groupValues?.get(1) }

    private fun sum(key: String, label: String, dirs: List<File>): StorageCategoryInfo {
        val sizes = dirs.map(::sizeAndCount)
        return StorageCategoryInfo(key, label, downloadsDir.path, sizes.sumOf { it.first }, sizes.sumOf { it.second })
    }

    private fun category(key: String, label: String, file: File): StorageCategoryInfo {
        val (bytes, count) = sizeAndCount(file)
        return StorageCategoryInfo(key, label, file.path, bytes, count)
    }

    private fun sizeAndCount(file: File): Pair<Long, Int> {
        if (!file.exists()) return 0L to 0
        if (file.isFile) return file.length() to 1
        var bytes = 0L
        var count = 0
        file.walkTopDown().forEach { f -> if (f.isFile) { bytes += f.length(); count++ } }
        return bytes to count
    }

    private fun File.deleteContentsOnly() {
        listFiles()?.forEach { it.deleteRecursively() }
    }

    private companion object {
        const val SERVER_KEY = "server:"
        val SERVER_DIR = Regex("^server_([0-9a-f]+)_")
    }
}
