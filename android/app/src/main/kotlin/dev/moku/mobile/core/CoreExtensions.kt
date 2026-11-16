package dev.moku.mobile.core

import android.content.Context
import androidx.core.content.edit
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.extension.ExtensionInstaller
import dev.moku.mobile.novel.NovelPluginCache
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException

// The core needs each extension to create, refresh and sync its titles; the phone still browses and reads through its own copy.
object CoreExtensions {
    private val network: NetworkHelper by injectLazy()
    private val lock = Mutex()
    @Volatile private var ids: Map<String, String>? = null

    /** The core's ID for [packageName], uploading the phone's copy first when the core lacks it or it changed. */
    suspend fun id(context: Context, packageName: String, type: MediaContentType): String = lock.withLock {
        val file = fileOf(context, packageName, type) ?: throw IOException("$packageName isn't installed on this phone")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = "${file.length()}:${file.lastModified()}"
        val known = ids ?: installed(context)
        known[packageName]?.takeIf { prefs.getString(packageName, null) == stamp }?.let { return it }
        upload(context, file)
        prefs.edit { putString(packageName, stamp) }
        installed(context)[packageName] ?: throw IOException("The core didn't take $packageName")
    }

    /** Forgets what the core has, after the phone installs, updates or removes an extension. */
    fun invalidate() {
        ids = null
    }

    private fun fileOf(context: Context, packageName: String, type: MediaContentType): File? = when (type) {
        MediaContentType.NOVEL -> NovelPluginCache.file(context, packageName).takeIf { it.exists() }
        else -> ExtensionInstaller(context).takeIf { it.isInstalled(packageName) }?.installedFile(packageName)
    }

    private suspend fun installed(context: Context): Map<String, String> {
        val data = Core.query(context, "query CoreExtensions { installedExtensions { id packageName } }")
        return data["installedExtensions"]!!.jsonArray.map { it.jsonObject }
            .associate { it["packageName"]!!.jsonPrimitive.content to it["id"]!!.jsonPrimitive.content }
            .also { ids = it }
    }

    private suspend fun upload(context: Context, file: File) = withContext(Dispatchers.IO) {
        val core = Core.ready(context)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = Request.Builder().url("${core.baseUrl}/api/extensions/install-file")
            .header("Authorization", "Bearer ${core.token}")
            .post(body)
            .build()
        network.client.newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("The core couldn't install ${file.name}: ${it.body.string().trim()}")
        }
    }

    private const val PREFS = "core_extensions"
}
