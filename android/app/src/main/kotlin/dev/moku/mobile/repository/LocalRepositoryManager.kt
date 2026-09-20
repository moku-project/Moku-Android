package dev.moku.mobile.repository

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.extension.ExtensionInstaller
import dev.moku.mobile.extension.ExtensionLoader
import dev.moku.mobile.library.LocalLibraryDatabase
import dev.moku.mobile.library.getLongOrNull
import dev.moku.mobile.library.getStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RepositoryEntity(val id: Long, val indexUrl: String, val name: String?, val contentType: MediaContentType, val lastSyncedAt: Long?)
data class AvailableExtensionEntity(
    val repositoryId: Long, val packageName: String, val name: String, val versionName: String,
    val versionCode: Long, val lang: String, val apkUrl: String, val iconUrl: String?, val isNsfw: Boolean,
)
data class InstalledExtensionEntity(
    val packageName: String, val repositoryId: Long?, val name: String, val versionName: String,
    val contentType: MediaContentType, val lang: String, val installedAt: Long, val enabled: Boolean,
)

/**
 * The on-device counterpart of Tsunagu's `Repositories`/`Extensions`/`InstalledExtensions`/
 * `installExtension`/`uninstallExtension` GraphQL surface — but for extensions this app loads
 * and runs itself via [ExtensionLoader], not ones a server sandboxes. Previously "installed"
 * meant only "an APK file sits in app-private storage" ([ExtensionInstaller]); this adds the
 * missing bookkeeping layer (which repo it came from, its declared name/version/contentType,
 * enabled/disabled) the way desktop's extension management screen expects to query.
 */
class LocalRepositoryManager(private val context: Context) {
    private val db: LocalLibraryDatabase = LocalLibraryDatabase.get(context)
    private val indexClient = GenericRepoIndexClient()
    private val installer = ExtensionInstaller(context)

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

    /** Downloads+installs the APK and records it as installed — actual loading is [ExtensionLoader]'s job. */
    suspend fun installExtension(ext: AvailableExtensionEntity, contentType: MediaContentType): InstalledExtensionEntity = withContext(Dispatchers.IO) {
        installer.installFromUrl(ext.packageName, ext.apkUrl)
        val installed = InstalledExtensionEntity(
            packageName = ext.packageName, repositoryId = ext.repositoryId, name = ext.name,
            versionName = ext.versionName, contentType = contentType, lang = ext.lang,
            installedAt = System.currentTimeMillis(), enabled = true,
        )
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
        installed
    }

    suspend fun uninstallExtension(packageName: String) = withContext(Dispatchers.IO) {
        installer.uninstall(packageName)
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
