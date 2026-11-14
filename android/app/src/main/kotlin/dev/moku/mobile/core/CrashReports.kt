package dev.moku.mobile.core

import android.content.Context
import android.content.Intent
import android.os.Build
import dev.moku.mobile.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the last crash so the user can share it. Nothing is sent unless they do. */
object CrashReports {
    private const val FILE = "last_crash.txt"
    private const val PREFS = "moku_crash"
    private const val KEY_SEEN = "seen"
    private lateinit var file: File

    fun install(context: Context) {
        file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(report(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun last(): String? = file.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    /** True once per crash, so the next launch mentions it a single time; Settings keeps offering it until cleared. */
    fun isNew(context: Context): Boolean {
        if (!file.exists()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = file.lastModified()
        if (prefs.getLong(KEY_SEEN, 0L) == stamp) return false
        prefs.edit().putLong(KEY_SEEN, stamp).apply()
        return true
    }

    fun clear() {
        file.delete()
    }

    fun share(context: Context) {
        val text = last() ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Moku ${BuildConfig.VERSION_NAME} crash report")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun report(thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return buildString {
            appendLine("Moku ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.firstOrNull()}")
            appendLine("$time on thread ${thread.name}")
            appendLine()
            append(trace)
        }
    }
}
