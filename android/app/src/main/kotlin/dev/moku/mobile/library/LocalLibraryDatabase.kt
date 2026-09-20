package dev.moku.mobile.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The entire on-device persistence layer for local (no-server) mode — see the kdoc on
 * [LibraryMediaEntity] for why this never touches anything remote/Tsunagu-related.
 *
 * Plain [SQLiteOpenHelper] rather than Room: Room 2.8.5 (latest published) can't run its
 * kapt annotation processor against our Kotlin 2.4.10 metadata output (bundled
 * kotlin-metadata-jvm reader caps out at metadata version 2.3.0) and no KSP release matches
 * 2.4.10 either — see the comment in app/build.gradle.kts. Five small tables with no complex
 * joins don't need an ORM badly enough to fight that; this can migrate to Room later once a
 * compatible release exists, since the DAO-shaped API surface below is unaffected either way.
 */
class LocalLibraryDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "moku_local_library.db", null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE library_media (
                originId TEXT NOT NULL, mediaId TEXT NOT NULL, contentType TEXT NOT NULL,
                title TEXT NOT NULL, thumbnailUrl TEXT, description TEXT, status TEXT,
                authorsCsv TEXT NOT NULL DEFAULT '', artistsCsv TEXT NOT NULL DEFAULT '',
                genresCsv TEXT NOT NULL DEFAULT '', addedAt INTEGER NOT NULL, lastViewedAt INTEGER,
                PRIMARY KEY (originId, mediaId)
            )""",
        )
        db.execSQL(
            """CREATE TABLE folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, sortOrder INTEGER NOT NULL DEFAULT 0
            )""",
        )
        db.execSQL(
            """CREATE TABLE library_folder_cross_ref (
                originId TEXT NOT NULL, mediaId TEXT NOT NULL, folderId INTEGER NOT NULL,
                PRIMARY KEY (originId, mediaId, folderId),
                FOREIGN KEY (folderId) REFERENCES folders(id) ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX idx_folder_cross_ref_folder ON library_folder_cross_ref(folderId)")
        db.execSQL(
            """CREATE TABLE reading_progress (
                originId TEXT NOT NULL, mediaId TEXT NOT NULL, unitId TEXT NOT NULL,
                progress REAL NOT NULL, completed INTEGER NOT NULL,
                positionSeconds REAL, durationSeconds REAL, updatedAt INTEGER NOT NULL,
                PRIMARY KEY (originId, mediaId, unitId)
            )""",
        )
        db.execSQL(
            """CREATE TABLE downloads (
                originId TEXT NOT NULL, mediaId TEXT NOT NULL, unitId TEXT NOT NULL,
                status TEXT NOT NULL, filePath TEXT, bytes INTEGER NOT NULL DEFAULT 0,
                error TEXT, createdAt INTEGER NOT NULL,
                PRIMARY KEY (originId, mediaId, unitId)
            )""",
        )
        createV2Tables(db)
    }

    /** Repos/installed-extensions, tracking accounts+links, content-filter rules — added after v1 shipped. */
    private fun createV2Tables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE repositories (
                id INTEGER PRIMARY KEY AUTOINCREMENT, indexUrl TEXT NOT NULL UNIQUE, name TEXT,
                contentType TEXT NOT NULL, addedAt INTEGER NOT NULL, lastSyncedAt INTEGER
            )""",
        )
        db.execSQL(
            """CREATE TABLE available_extensions (
                repositoryId INTEGER NOT NULL, packageName TEXT NOT NULL, name TEXT NOT NULL,
                versionName TEXT NOT NULL, versionCode INTEGER NOT NULL, lang TEXT NOT NULL,
                apkUrl TEXT NOT NULL, iconUrl TEXT, isNsfw INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (repositoryId, packageName),
                FOREIGN KEY (repositoryId) REFERENCES repositories(id) ON DELETE CASCADE
            )""",
        )
        db.execSQL(
            """CREATE TABLE installed_extensions (
                packageName TEXT PRIMARY KEY, repositoryId INTEGER, name TEXT NOT NULL,
                versionName TEXT NOT NULL, contentType TEXT NOT NULL, lang TEXT NOT NULL,
                installedAt INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1
            )""",
        )
        // Tracker OAuth tokens are NOT stored here — see dev.moku.mobile.tracking.TrackerAccountStore,
        // which uses EncryptedSharedPreferences (matching TsunaguSession's security posture for the
        // Tsunagu bearer token) rather than a plaintext SQLite column for access/refresh tokens.
        db.execSQL(
            """CREATE TABLE track_links (
                originId TEXT NOT NULL, mediaId TEXT NOT NULL, trackerKey TEXT NOT NULL,
                remoteId TEXT NOT NULL, libraryId TEXT, title TEXT NOT NULL, url TEXT,
                status INTEGER NOT NULL, score REAL NOT NULL DEFAULT 0,
                lastChapterRead REAL NOT NULL DEFAULT 0, totalChapters INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (originId, mediaId, trackerKey)
            )""",
        )
        db.execSQL(
            """CREATE TABLE content_filter_rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, field TEXT NOT NULL,
                keyword TEXT NOT NULL, minWeight INTEGER NOT NULL DEFAULT 0, blockLevel INTEGER NOT NULL,
                isDefault INTEGER NOT NULL DEFAULT 0
            )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2Tables(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    companion object {
        private const val VERSION = 2

        @Volatile private var instance: LocalLibraryDatabase? = null

        fun get(context: Context): LocalLibraryDatabase = instance ?: synchronized(this) {
            instance ?: LocalLibraryDatabase(context).also { instance = it }
        }

        /** For [LocalBackupManager.importFrom]: drops the cached handle after the underlying file is replaced. */
        fun resetInstance() {
            synchronized(this) { instance = null }
        }
    }
}

internal fun Cursor.getStringOrNull(column: String): String? = getString(getColumnIndexOrThrow(column))
internal fun Cursor.getLongOrNull(column: String): Long? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getLong(index)
}
internal fun Cursor.getDoubleOrNull(column: String): Double? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getDouble(index)
}
internal fun ContentValues.putNullable(key: String, value: String?) = put(key, value)
internal fun ContentValues.putNullable(key: String, value: Long?) = if (value == null) putNull(key) else put(key, value)
internal fun ContentValues.putNullable(key: String, value: Double?) = if (value == null) putNull(key) else put(key, value)
