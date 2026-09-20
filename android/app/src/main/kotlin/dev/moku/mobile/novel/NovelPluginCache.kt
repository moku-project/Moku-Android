package dev.moku.mobile.novel

import android.content.Context
import java.io.File

/**
 * Novel plugins are plain JS text, not an installed APK [dev.moku.mobile.extension.ExtensionInstaller]
 * can re-read from disk — without this, a [dev.moku.mobile.library.DownloadWorker] resuming
 * after process death would have no way to reconstruct a [LocalNovelBackend], since
 * [NovelPluginLoader.load] needs the raw source text, not just a package name.
 */
object NovelPluginCache {
    private fun dir(context: Context): File = File(context.filesDir, "novel_plugins").apply { mkdirs() }

    fun save(context: Context, packageName: String, jsCode: String) {
        File(dir(context), "$packageName.js").writeText(jsCode)
    }

    fun load(context: Context, packageName: String): String? {
        val file = File(dir(context), "$packageName.js")
        return if (file.exists()) file.readText() else null
    }
}
