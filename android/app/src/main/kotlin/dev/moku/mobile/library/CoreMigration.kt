package dev.moku.mobile.library

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dev.moku.mobile.backend.BackendMedia

// One-time move of the phone database's library into its Tsunagu. Titles are marked done only once the core has them.
object CoreMigration {
    private const val PREFS = "core_migration"
    private const val DONE = "done"

    suspend fun run(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(DONE, false)) return
        val device = LocalLibraryRepository(context)
        val core = CoreLibrary(context, strict = true)
        val titles = device.getLibrary().filter { it.originId.startsWith("local:") }
        val folders = device.getFolders()
        val coreFolders = runCatching { core.getFolders().associateBy { it.name.trim().lowercase() } }.getOrElse { return }
        val folderIds = mutableMapOf<Long, Long>()
        for (folder in folders) {
            val id = coreFolders[folder.name.trim().lowercase()]?.id ?: runCatching { core.createFolder(folder.name) }.getOrNull() ?: return
            folderIds[folder.id] = id
        }

        var left = 0
        for (title in titles) {
            val key = "${title.originId}\u001f${title.mediaId}"
            if (prefs.getBoolean(key, false)) continue
            val moved = runCatching {
                core.addToLibrary(
                    BackendMedia(
                        id = title.mediaId,
                        originId = title.originId,
                        contentType = title.contentType,
                        title = title.title,
                        thumbnailUrl = title.thumbnailUrl,
                    ),
                )
                for (folder in device.foldersFor(title.originId, title.mediaId)) {
                    folderIds[folder]?.let { core.addToFolder(title.originId, title.mediaId, it) }
                }
                for (p in device.getProgressForMedia(title.originId, title.mediaId)) {
                    if (!p.completed && p.progress <= 0f) continue
                    core.updateProgress(p.originId, p.mediaId, p.unitId, p.progress, p.completed, p.positionSeconds, p.durationSeconds)
                }
            }
            if (moved.isSuccess) {
                prefs.edit { putBoolean(key, true) }
            } else {
                left++
                Log.w(TAG, "couldn't move ${title.title} yet", moved.exceptionOrNull())
            }
        }
        if (left == 0) prefs.edit { putBoolean(DONE, true) }
    }

    private const val TAG = "CoreMigration"
}
