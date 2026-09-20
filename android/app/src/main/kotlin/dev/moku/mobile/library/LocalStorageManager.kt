package dev.moku.mobile.library

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class StorageCategoryInfo(val key: String, val label: String, val path: String, val bytes: Long, val fileCount: Int)
data class LocalStorageInfo(val usedBytes: Long, val freeBytes: Long, val totalBytes: Long, val categories: List<StorageCategoryInfo>)

/** Local-mode equivalent of Tsunagu's `storageInfo`/`clearStorageCategory`/`clearImageCache`. */
class LocalStorageManager(private val context: Context) {

    suspend fun storageInfo(): LocalStorageInfo = withContext(Dispatchers.IO) {
        val categories = listOf(
            category("extensions", "Extensions", File(context.filesDir, "exts")),
            category("downloads", "Downloads", File(context.filesDir, "downloads")),
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
        when (key) {
            "extensions" -> File(context.filesDir, "exts").deleteContentsOnly()
            "downloads" -> File(context.filesDir, "downloads").deleteContentsOnly()
            "novel_plugins" -> File(context.filesDir, "novel_plugins").deleteContentsOnly()
            else -> throw IllegalArgumentException("unknown storage category $key")
        }
        Unit
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
}
