package dev.moku.mobile.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Plain SQLite: Room's annotation processor can't read the metadata our Kotlin version emits. */
class LocalLibraryDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "moku_local_library.db", null, VERSION) {
    private val filesDir = context.applicationContext.filesDir

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
        createV3Tables(db)
        createServerTables(db)
    }

    /** Server titles: the server's own id for each one, and reading progress not yet sent to its server. */
    private fun createServerTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE server_media (
                originId TEXT NOT NULL, mediaId TEXT NOT NULL, serverId TEXT NOT NULL,
                PRIMARY KEY (originId, mediaId)
            )""",
        )
        db.execSQL(
            """CREATE TABLE sync_outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT, originId TEXT NOT NULL, payload TEXT NOT NULL,
                createdAt INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0
            )""",
        )
    }

    /** Versions 4 and 5 copied the server's library onto the phone; server titles are read live now, so it goes. */
    private fun dropServerCopy(db: SQLiteDatabase) {
        val like = arrayOf("server:%")
        listOf("library_media", "library_folder_cross_ref", "reading_progress", "media_cache", "downloads", "track_links")
            .forEach { db.delete(it, "originId LIKE ?", like) }
        // Their files too; the folders are named after the old origins, so nothing would ever point at them again.
        java.io.File(filesDir, "downloads").listFiles { f -> f.name.startsWith("server_") }?.forEach { it.deleteRecursively() }
        db.execSQL("DROP TABLE sync_outbox")
        db.execSQL("DROP TABLE server_media")
        // Only the phone's own folders stay. Memberships go first, or dropping folders would cascade-delete them.
        db.execSQL("CREATE TEMP TABLE kept_folders AS SELECT id, name, sortOrder FROM folders WHERE serverId IS NULL")
        db.execSQL("CREATE TEMP TABLE kept_members AS SELECT originId, mediaId, folderId FROM library_folder_cross_ref WHERE folderId IN (SELECT id FROM kept_folders)")
        db.execSQL("DROP TABLE library_folder_cross_ref")
        db.execSQL("DROP TABLE folders")
        db.execSQL("CREATE TABLE folders (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, sortOrder INTEGER NOT NULL DEFAULT 0)")
        db.execSQL(
            """CREATE TABLE library_folder_cross_ref (
                originId TEXT NOT NULL, mediaId TEXT NOT NULL, folderId INTEGER NOT NULL,
                PRIMARY KEY (originId, mediaId, folderId),
                FOREIGN KEY (folderId) REFERENCES folders(id) ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX idx_folder_cross_ref_folder ON library_folder_cross_ref(folderId)")
        db.execSQL("INSERT INTO folders (id, name, sortOrder) SELECT id, name, sortOrder FROM kept_folders")
        db.execSQL("INSERT INTO library_folder_cross_ref SELECT originId, mediaId, folderId FROM kept_members")
        db.execSQL("DROP TABLE kept_folders")
        db.execSQL("DROP TABLE kept_members")
        createServerTables(db)
    }

    private fun createV3Tables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE media_cache (
                originId TEXT NOT NULL, mediaId TEXT NOT NULL, mediaJson TEXT NOT NULL,
                unitsJson TEXT NOT NULL, sourceName TEXT, updatedAt INTEGER NOT NULL,
                PRIMARY KEY (originId, mediaId)
            )""",
        )
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
        // Tracker tokens live in TrackerAccountStore's encrypted storage, never in this database.
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
        if (oldVersion < 3) createV3Tables(db)
        if (oldVersion < 4) createServerTables(db)
        if (oldVersion in 4..5) dropServerCopy(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    companion object {
        private const val VERSION = 6

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
