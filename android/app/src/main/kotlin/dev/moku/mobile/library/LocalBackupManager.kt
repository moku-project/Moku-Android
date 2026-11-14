package dev.moku.mobile.library

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Backs up and restores the library database file. Downloaded files aren't included. */
class LocalBackupManager(private val context: Context) {

    /** [destUri] from an `ACTION_CREATE_DOCUMENT` intent result. */
    suspend fun exportTo(destUri: Uri): Long = withContext(Dispatchers.IO) {
        val db = LocalLibraryDatabase.get(context)
        // A no-op outside WAL mode; keeps the copy complete if that changes.
        runCatching { db.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)") }

        val dbFile = context.getDatabasePath("moku_local_library.db")
        val resolver = context.contentResolver
        val output = resolver.openOutputStream(destUri) ?: throw IOException("Cannot open $destUri for writing")
        output.use { out -> dbFile.inputStream().use { it.copyTo(out) } }
        dbFile.length()
    }

    /** [srcUri] from an `ACTION_OPEN_DOCUMENT` intent result. Restarting the app after this is recommended. */
    suspend fun importFrom(srcUri: Uri): Long = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val input = resolver.openInputStream(srcUri) ?: throw IOException("Cannot open $srcUri for reading")
        val dbFile = context.getDatabasePath("moku_local_library.db")

        // Close the live handle before overwriting the file out from under it.
        LocalLibraryDatabase.get(context).close()
        LocalLibraryDatabase.resetInstance()

        input.use { inp -> dbFile.outputStream().use { out -> inp.copyTo(out) } }
        // Re-open (and implicitly run onUpgrade if the restored file is an older schema version).
        LocalLibraryDatabase.get(context).readableDatabase
        dbFile.length()
    }
}
