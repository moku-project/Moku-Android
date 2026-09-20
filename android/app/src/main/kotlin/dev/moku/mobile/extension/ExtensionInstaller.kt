package dev.moku.mobile.extension

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException

/**
 * Downloads an extension APK to app-private storage and marks it read-only, mirroring
 * Aniyomi's `installPrivateExtensionFile()`: Android's dex loader (API 29+, "W^X"
 * enforcement) refuses to load code from a file this app can still write to.
 */
class ExtensionInstaller(private val context: Context) {
    private val network: NetworkHelper by injectLazy()

    private val extensionsDir: File
        get() = File(context.filesDir, "exts").apply { mkdirs() }

    fun installedFile(packageName: String): File = File(extensionsDir, "$packageName.apk")

    /**
     * Existence alone isn't enough — a copy interrupted mid-write (app/emulator killed
     * during [installFromUrl]/[installFromAsset]) leaves a file that exists but isn't a
     * valid zip, and every APK/JAR is a zip, so a truncated file fails the local-file-header
     * magic-byte check trivially. Caught this via a real corrupted install after an emulator
     * crash: [ExtensionLoader] failed with "not a parseable APK" because callers skipped
     * reinstalling on the (wrongly) assumed-valid cached file.
     */
    fun isInstalled(packageName: String): Boolean {
        val file = installedFile(packageName)
        if (!file.exists() || file.length() < 4) return false
        return file.inputStream().use { input ->
            val header = ByteArray(4)
            input.read(header) == 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        }
    }

    suspend fun installFromUrl(packageName: String, apkUrl: String): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(apkUrl).build()
        network.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to download $packageName: HTTP ${response.code}")
            writeReadOnly(installedFile(packageName)) { out -> response.body!!.byteStream().copyTo(out) }
        }
    }

    fun installFromAsset(packageName: String, assetPath: String): File {
        return writeReadOnly(installedFile(packageName)) { out ->
            context.assets.open(assetPath).use { input -> input.copyTo(out) }
        }
    }

    fun uninstall(packageName: String) {
        val file = installedFile(packageName)
        if (file.exists()) {
            file.setWritable(true, false)
            file.delete()
        }
    }

    fun installedPackageNames(): List<String> =
        extensionsDir.listFiles { f -> f.extension == "apk" }
            ?.map { it.nameWithoutExtension }
            .orEmpty()

    private fun writeReadOnly(outFile: File, write: (java.io.OutputStream) -> Unit): File {
        if (outFile.exists()) {
            outFile.setWritable(true, false)
            outFile.delete()
        }
        outFile.outputStream().use { write(it) }
        outFile.setWritable(false, false)
        return outFile
    }
}
