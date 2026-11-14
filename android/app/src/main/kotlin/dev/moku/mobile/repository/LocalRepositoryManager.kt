package dev.moku.mobile.repository

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.extension.ExtensionInstaller
import dev.moku.mobile.extension.ExtensionLoader
import dev.moku.mobile.library.LocalLibraryDatabase
import dev.moku.mobile.library.getLongOrNull
import dev.moku.mobile.library.getStringOrNull
import kotlinx.coroutines.Dispatchers
import dev.moku.mobile.novel.NovelPluginCache
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException

data class RepositoryEntity(val id: Long, val indexUrl: String, val name: String?, val contentType: MediaContentType, val lastSyncedAt: Long?)
data class AvailableExtensionEntity(
    val repositoryId: Long, val packageName: String, val name: String, val versionName: String,
    val versionCode: Long, val lang: String, val apkUrl: String, val iconUrl: String?, val isNsfw: Boolean,
)
data class InstalledExtensionEntity(
    val packageName: String, val repositoryId: Long?, val name: String, val versionName: String,
    val contentType: MediaContentType, val lang: String, val installedAt: Long, val enabled: Boolean,
)

class LocalRepositoryManager(private val context: Context) {
    private val db: LocalLibraryDatabase = LocalLibraryDatabase.get(context)
    private val indexClient = GenericRepoIndexClient()
    private val installer = ExtensionInstaller(context)
    private val network: NetworkHelper by injectLazy()

    suspend fun addRepository(indexUrl: String, name: String?, contentType: MediaContentType): RepositoryEntity = withContext(Dispatchers.IO) {
        val id = db.writableDatabase.insertWithOnConflict(
            "repositories", null,
            ContentValues().apply {
                put("indexUrl", indexUrl)
                put("name", name)
                put("contentType", contentType.name)
                put("addedAt", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        val repo = RepositoryEntity(id, indexUrl, name, contentType, lastSyncedAt = null)
        syncRepository(repo)
    }

    suspend fun removeRepository(repositoryId: Long) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("repositories", "id = ?", arrayOf(repositoryId.toString()))
        Unit
    }

    suspend fun getRepositories(): List<RepositoryEntity> = withContext(Dispatchers.IO) {
        db.readableDatabase.query("repositories", null, null, null, null, null, "addedAt ASC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        RepositoryEntity(
                            id = c.getLongOrNull("id")!!,
                            indexUrl = c.getStringOrNull("indexUrl")!!,
                            name = c.getStringOrNull("name"),
                            contentType = MediaContentType.valueOf(c.getStringOrNull("contentType")!!),
                            lastSyncedAt = c.getLongOrNull("lastSyncedAt"),
                        ),
                    )
                }
            }
        }
    }

    /** Refetches a repo's index and caches its entries — mirrors Tsunagu's `syncRepository`. */
    suspend fun syncRepository(repo: RepositoryEntity): RepositoryEntity = withContext(Dispatchers.IO) {
        val entries = indexClient.fetchIndex(repo.indexUrl)
        val writable = db.writableDatabase
        writable.delete("available_extensions", "repositoryId = ?", arrayOf(repo.id.toString()))
        entries.forEach { e ->
            writable.insertWithOnConflict(
                "available_extensions", null,
                ContentValues().apply {
                    put("repositoryId", repo.id)
                    put("packageName", e.packageName)
                    put("name", e.name)
                    put("versionName", e.versionName)
                    put("versionCode", e.versionCode)
                    put("lang", e.lang)
                    put("apkUrl", e.apkUrl)
                    put("iconUrl", e.iconUrl)
                    put("isNsfw", if (e.isNsfw) 1 else 0)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        val now = System.currentTimeMillis()
        writable.execSQL("UPDATE repositories SET lastSyncedAt = ? WHERE id = ?", arrayOf<Any>(now, repo.id))
        repo.copy(lastSyncedAt = now)
    }

    suspend fun getAvailableExtensions(repositoryId: Long): List<AvailableExtensionEntity> = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "available_extensions", null, "repositoryId = ?", arrayOf(repositoryId.toString()), null, null, "name ASC",
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        AvailableExtensionEntity(
                            repositoryId = c.getLongOrNull("repositoryId")!!,
                            packageName = c.getStringOrNull("packageName")!!,
                            name = c.getStringOrNull("name")!!,
                            versionName = c.getStringOrNull("versionName")!!,
                            versionCode = c.getLongOrNull("versionCode")!!,
                            lang = c.getStringOrNull("lang")!!,
                            apkUrl = c.getStringOrNull("apkUrl")!!,
                            iconUrl = c.getStringOrNull("iconUrl"),
                            isNsfw = c.getLongOrNull("isNsfw") == 1L,
                        ),
                    )
                }
            }
        }
    }

    suspend fun installExtension(ext: AvailableExtensionEntity, contentType: MediaContentType): InstalledExtensionEntity = withContext(Dispatchers.IO) {
        if (contentType == MediaContentType.NOVEL) {
            val js = network.client.newCall(Request.Builder().url(ext.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Couldn't download ${ext.name} (HTTP ${response.code})")
                response.body.string()
            }
            NovelPluginCache.save(context, ext.packageName, js)
        } else {
            installer.installFromUrl(ext.packageName, ext.apkUrl)
        }
        record(
            InstalledExtensionEntity(
                packageName = ext.packageName, repositoryId = ext.repositoryId, name = ext.name,
                versionName = ext.versionName, contentType = contentType, lang = ext.lang,
                installedAt = System.currentTimeMillis(), enabled = true,
            ),
        )
    }

    suspend fun installFromFile(uri: Uri): InstalledExtensionEntity = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "sideload-${System.nanoTime()}.apk")
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(tmp.path, PackageManager.GET_META_DATA) ?: error("That file isn't an APK")
            val app = info.applicationInfo ?: error("That file isn't an APK")
            app.sourceDir = tmp.path
            app.publicSourceDir = tmp.path
            val meta = app.metaData
            val type = when {
                meta?.containsKey("tachiyomi.animeextension.class") == true -> MediaContentType.ANIME
                meta?.containsKey("tachiyomi.extension.class") == true -> MediaContentType.MANGA
                else -> error("That APK isn't a Mihon or Aniyomi extension")
            }
            installer.installFromFile(info.packageName, tmp)
            record(
                InstalledExtensionEntity(
                    packageName = info.packageName,
                    repositoryId = null,
                    name = pm.getApplicationLabel(app).toString().removePrefix("Tachiyomi: ").removePrefix("Aniyomi: "),
                    versionName = info.versionName.orEmpty(),
                    contentType = type,
                    lang = info.packageName.split('.').dropLast(1).lastOrNull().orEmpty(),
                    installedAt = System.currentTimeMillis(),
                    enabled = true,
                ),
            )
        } finally {
            tmp.delete()
        }
    }

    private fun record(installed: InstalledExtensionEntity): InstalledExtensionEntity {
        db.writableDatabase.insertWithOnConflict(
            "installed_extensions", null,
            ContentValues().apply {
                put("packageName", installed.packageName)
                put("repositoryId", installed.repositoryId)
                put("name", installed.name)
                put("versionName", installed.versionName)
                put("contentType", installed.contentType.name)
                put("lang", installed.lang)
                put("installedAt", installed.installedAt)
                put("enabled", 1)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return installed
    }

    suspend fun uninstallExtension(packageName: String) = withContext(Dispatchers.IO) {
        installer.uninstall(packageName)
        NovelPluginCache.delete(context, packageName)
        db.writableDatabase.delete("installed_extensions", "packageName = ?", arrayOf(packageName))
        Unit
    }

    suspend fun setEnabled(packageName: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        db.writableDatabase.execSQL(
            "UPDATE installed_extensions SET enabled = ? WHERE packageName = ?",
            arrayOf<Any>(if (enabled) 1 else 0, packageName),
        )
    }

    suspend fun getInstalledExtensions(): List<InstalledExtensionEntity> = withContext(Dispatchers.IO) {
        db.readableDatabase.query("installed_extensions", null, null, null, null, null, "name ASC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        InstalledExtensionEntity(
                            packageName = c.getStringOrNull("packageName")!!,
                            repositoryId = c.getLongOrNull("repositoryId"),
                            name = c.getStringOrNull("name")!!,
                            versionName = c.getStringOrNull("versionName")!!,
                            contentType = MediaContentType.valueOf(c.getStringOrNull("contentType")!!),
                            lang = c.getStringOrNull("lang")!!,
                            installedAt = c.getLongOrNull("installedAt")!!,
                            enabled = c.getLongOrNull("enabled") == 1L,
                        ),
                    )
                }
            }
        }
    }

    /** Checks the cached available-extension version against what's installed — mirrors `Extension.needsUpdate`. */
    suspend fun checkForUpdate(packageName: String): AvailableExtensionEntity? = withContext(Dispatchers.IO) {
        val installed = getInstalledExtensions().firstOrNull { it.packageName == packageName } ?: return@withContext null
        db.readableDatabase.query(
            "available_extensions", null, "packageName = ?", arrayOf(packageName), null, null, "versionCode DESC", "1",
        ).use { c ->
            if (!c.moveToFirst()) return@withContext null
            val versionName = c.getStringOrNull("versionName")!!
            if (versionName == installed.versionName) return@withContext null
            AvailableExtensionEntity(
                repositoryId = c.getLongOrNull("repositoryId")!!,
                packageName = c.getStringOrNull("packageName")!!,
                name = c.getStringOrNull("name")!!,
                versionName = versionName,
                versionCode = c.getLongOrNull("versionCode")!!,
                lang = c.getStringOrNull("lang")!!,
                apkUrl = c.getStringOrNull("apkUrl")!!,
                iconUrl = c.getStringOrNull("iconUrl"),
                isNsfw = c.getLongOrNull("isNsfw") == 1L,
            )
        }
    }
}
