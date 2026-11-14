package dev.moku.mobile.novel

import android.content.Context
import java.io.File

// Novel plugins are JS source, kept on disk so a backend can be rebuilt after process death.
object NovelPluginCache {
    private fun dir(context: Context): File = File(context.filesDir, "novel_plugins").apply { mkdirs() }

    fun save(context: Context, packageName: String, jsCode: String) {
        File(dir(context), "$packageName.js").writeText(jsCode)
    }

    fun load(context: Context, packageName: String): String? {
        val file = File(dir(context), "$packageName.js")
        return if (file.exists()) file.readText() else null
    }

    fun delete(context: Context, packageName: String) {
        File(dir(context), "$packageName.js").delete()
    }
}
