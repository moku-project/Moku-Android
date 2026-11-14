package dev.moku.mobile.sync

import android.content.ContentValues
import android.content.Context
import android.util.Log
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import dev.moku.mobile.library.DownloadQueueManager
import dev.moku.mobile.library.LocalLibraryDatabase
import dev.moku.mobile.library.LocalLibraryRepository
import dev.moku.mobile.library.ReadingProgressDao
import dev.moku.mobile.library.ReadingProgressEntity
import dev.moku.mobile.remote.AuthRequiredException
import dev.moku.mobile.remote.GraphQLException
import dev.moku.mobile.remote.ServerSession
import dev.moku.mobile.remote.ServerUnreachableException
import dev.moku.mobile.remote.TsunaguServer
import dev.moku.mobile.remote.str
import dev.moku.mobile.remote.strOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime

data class SyncStatus(
    val running: Boolean = false,
    /** Progress updates read on this phone that haven't reached the signed-in server yet. */
    val pending: Int = 0,
    val error: String? = null,
    val needsSignIn: Boolean = false,
)

/**
 * Queues reading progress for server titles and sends it oldest first, so downloads can be read offline.
 * Everything else is read and written live by [ServerLibrary].
 */
object ServerSync {
    private const val TAG = "ServerSync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var scheduled: Job? = null
    private var retry: Job? = null
    private var retryDelayMs = RETRY_MIN_MS
    private var later: Job? = null

    // The server this process has confirmed is the one signed in to, as "<address>|<instanceId>".
    @Volatile private var verified: String? = null

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    fun init(context: Context) {
        ServerSession.init(context)
        ServerSession.legacy?.let { old -> scope.launch { adoptLegacy(context.applicationContext, old) } }
        scope.launch { refreshPending(context) }
    }

    /** Saves a progress update for a server title and queues it for its server. */
    internal suspend fun record(context: Context, progress: ReadingProgressEntity) {
        val outbox = Outbox(context)
        val payload = JSONObject()
            .put("originId", progress.originId)
            .put("mediaId", progress.mediaId)
            .put("unitId", progress.unitId)
            .put("progress", progress.progress.toDouble())
            .put("completed", progress.completed)
        progress.positionSeconds?.let { payload.put("position", it) }
        progress.durationSeconds?.let { payload.put("duration", it) }
        outbox.supersede(payload)
        outbox.add(payload)
        refreshPending(context)
        requestFlush(context)
    }

    /** Sends queued progress shortly, coalescing bursts like marking a whole volume read. */
    fun requestFlush(context: Context) {
        if (!ServerSession.signedIn) return
        val app = context.applicationContext
        scheduled?.cancel()
        scheduled = scope.launch {
            delay(1_500)
            flush(app)
        }
    }

    /** Checks the server is there and sends whatever progress is waiting for it. */
    suspend fun check(context: Context): Result<Unit> {
        if (!ServerSession.signedIn) return Result.success(Unit)
        val app = context.applicationContext
        return runCatching {
            _status.value = _status.value.copy(running = true)
            try {
                verified = null
                verifyServer()
                lock.withLock { flushLocked(app) }
            } finally {
                _status.value = _status.value.copy(running = false)
            }
            retryDelayMs = RETRY_MIN_MS
            _status.value = _status.value.copy(error = null, needsSignIn = false)
        }.onFailure { report(app, it) }
    }

    private suspend fun flush(context: Context) {
        runCatching { lock.withLock { flushLocked(context) } }
            .onSuccess { _status.value = _status.value.copy(error = null, needsSignIn = false) }
            .onFailure { report(context, it) }
    }

    /** Keeps trying while the server is away so the "can't connect" banner clears by itself once it's back. */
    fun scheduleRetry(context: Context) {
        if (retry?.isActive == true) return
        val app = context.applicationContext
        val wait = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
        retry = scope.launch {
            delay(wait)
            retry = null
            if (ServerSession.signedIn && !ServerSession.reachable.value) check(app)
        }
    }

    /** Tries the server right away, e.g. from the banner's Retry. */
    fun retryNow(context: Context) {
        retry?.cancel()
        retry = null
        retryDelayMs = RETRY_MIN_MS
        scope.launch { check(context) }
    }

    /** With [keepDownloads], downloads and unsent progress stay until this phone signs in to that server again. */
    suspend fun disconnect(context: Context, keepDownloads: Boolean) = lock.withLock {
        val app = context.applicationContext
        val instance = ServerSession.current?.instanceId
        if (instance != null && !keepDownloads) forget(app, instance)
        ServerSources.clear(app)
        ServerSession.save(app, null)
        _status.value = SyncStatus()
    }

    private suspend fun forget(context: Context, instanceId: String) {
        val prefix = "${ServerOrigin.PREFIX}$instanceId:"
        val repo = LocalLibraryRepository(context)
        val downloads = repo.getDownloads().filter { it.originId.startsWith(prefix) }
        if (downloads.isNotEmpty()) DownloadQueueManager(context, repo).remove(downloads)
        withContext(Dispatchers.IO) {
            val db = LocalLibraryDatabase.get(context).writableDatabase
            val like = arrayOf("$prefix%")
            db.beginTransaction()
            try {
                listOf("reading_progress", "media_cache", "server_media").forEach { db.delete(it, "originId LIKE ?", like) }
                db.delete("sync_outbox", "originId LIKE ?", like)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            ServerSnapshot.dir(context, instanceId).deleteRecursively()
        }
    }

    private suspend fun refreshPending(context: Context) {
        _status.value = _status.value.copy(pending = Outbox(context).countFor(ServerSession.current?.instanceId))
    }

    private fun report(context: Context, t: Throwable) {
        Log.w(TAG, "server check failed", t)
        _status.value = _status.value.copy(
            running = false,
            error = if (t is AuthRequiredException) "Sign in again to reach this server" else t.message ?: "Couldn't reach the server",
            needsSignIn = t is AuthRequiredException || t is ServerReplacedException,
        )
        scope.launch { refreshPending(context) }
        if (t is ServerUnreachableException) scheduleRetry(context)
    }

    // ---- sending ----

    private suspend fun flushLocked(context: Context) {
        val instance = ServerSession.current?.instanceId ?: return
        verifyServer()
        val outbox = Outbox(context)
        // Progress for another server waits until this phone signs in to that one.
        val entries = outbox.pending().filter { ServerOrigin.parse(it.payload.optString("originId"))?.instanceId == instance }
        var i = 0
        var deferred = false
        while (i < entries.size) {
            val entry = entries[i]
            val batch = readMarks(entries, i)
            try {
                send(context, batch)
            } catch (t: AuthRequiredException) {
                throw t
            } catch (t: GraphQLException) {
                // The remembered server id may belong to a title the server has since re-created.
                outbox.forgetServerId(entry.payload.optString("originId"), entry.payload.optString("mediaId"))
                // Server-side failures are often temporary (a source down, the sandbox restarting), so retry a few
                // times before giving up, without holding up the rest of the queue; a malformed request never succeeds.
                if (t.code != "GRAPHQL_VALIDATION_FAILED" && entry.attempts + 1 < MAX_ATTEMPTS) {
                    outbox.bumpAttempts(batch.map { it.id })
                    deferred = true
                    i += batch.size
                    continue
                }
                Log.w(TAG, "server rejected progress, dropping it: ${t.message}")
            } catch (t: IOException) {
                throw t
            } catch (t: Exception) {
                Log.w(TAG, "dropping unsendable progress", t)
            }
            outbox.delete(batch.map { it.id })
            i += batch.size
        }
        refreshPending(context)
        if (deferred) flushLater(context)
    }

    /** A reinstalled server at the same address is a different server, so nothing is sent until signing in again. */
    private suspend fun verifyServer() {
        val server = ServerSession.current ?: return
        val key = server.baseUrl + "|" + server.instanceId
        if (verified == key) return
        val about = ServerSession.client.query("query Ping { about { instanceId } }")["about"] as? JsonObject
        if (about?.strOrNull("instanceId") != server.instanceId) throw ServerReplacedException()
        verified = key
    }

    /** Fills in the instance id for a sign-in saved before servers had one, instead of signing the user out. */
    private suspend fun adoptLegacy(context: Context, old: TsunaguServer) {
        runCatching {
            val about = ServerSession.client.query(old, "query About { about { instanceId } }")["about"] as? JsonObject
            val instanceId = about?.strOrNull("instanceId") ?: return
            ServerSession.save(context, old.copy(instanceId = instanceId))
        }.onFailure { Log.i(TAG, "couldn't upgrade the saved sign-in yet: ${it.message}") }
    }

    private fun flushLater(context: Context) {
        if (later?.isActive == true) return
        later = scope.launch {
            delay(RETRY_MIN_MS * 4)
            later = null
            flush(context)
        }
    }

    // A run of plain read/unread marks on one title, so "mark all read" is one request instead of hundreds.
    private fun readMarks(entries: List<OutboxEntry>, start: Int): List<OutboxEntry> {
        fun mark(e: OutboxEntry): Pair<String, Boolean>? {
            if (e.payload.has("position")) return null
            val p = e.payload.getDouble("progress")
            val done = e.payload.getBoolean("completed")
            if (!((done && p >= 1.0) || (!done && p <= 0.0))) return null
            return e.payload.getString("originId") + "\u0000" + e.payload.getString("mediaId") to done
        }
        val key = mark(entries[start]) ?: return listOf(entries[start])
        var end = start + 1
        while (end < entries.size && mark(entries[end]) == key) end++
        return entries.subList(start, end)
    }

    private suspend fun send(context: Context, batch: List<OutboxEntry>) {
        val p = batch.first().payload
        val id = serverMediaId(context, p.getString("originId"), p.getString("mediaId"))
        if (batch.size > 1) {
            ServerSession.client.query(
                "mutation Marks(\$id: ID!, \$chapters: [ID!]!, \$read: Boolean!) { markChaptersRead(mediaId: \$id, chapterIds: \$chapters, read: \$read) { id } }",
                buildJsonObject {
                    put("id", id)
                    put("chapters", buildJsonArray { batch.forEach { add(it.payload.getString("unitId")) } })
                    put("read", p.getBoolean("completed"))
                },
            )
            return
        }
        ServerSession.client.query(
            """mutation Progress(${'$'}m: ID!, ${'$'}c: ID!, ${'$'}p: Float!, ${'$'}done: Boolean, ${'$'}pos: Float, ${'$'}dur: Float) {
                updateReadingProgress(mediaId: ${'$'}m, chapterId: ${'$'}c, progress: ${'$'}p, completed: ${'$'}done, positionSeconds: ${'$'}pos, durationSeconds: ${'$'}dur) { id }
            }""",
            buildJsonObject {
                put("m", id)
                put("c", p.getString("unitId"))
                put("p", p.getDouble("progress"))
                put("done", p.getBoolean("completed"))
                if (p.has("position")) put("pos", p.getDouble("position"))
                if (p.has("duration")) put("dur", p.getDouble("duration"))
            },
        )
    }

    // ---- shared with ServerLibrary ----

    /** The server's own id for a title, asked for once and remembered. */
    internal suspend fun serverMediaId(context: Context, originId: String, mediaId: String): String {
        val outbox = Outbox(context)
        outbox.serverId(originId, mediaId)?.let { return it }
        val ref = ServerOrigin.parse(originId) ?: error("not a server origin: $originId")
        val data = ServerSession.client.query(
            "query Resolve(\$ext: ID!, \$ext_id: String!) { resolveMedia(extensionId: \$ext, externalId: \$ext_id, syncChapters: false) { id } }",
            buildJsonObject { put("ext", ref.extensionId); put("ext_id", mediaId) },
        )
        return data["resolveMedia"]!!.jsonObject.str("id").also { outbox.putServerId(originId, mediaId, it) }
    }

    internal suspend fun rememberServerId(context: Context, originId: String, mediaId: String, serverId: String) =
        Outbox(context).putServerId(originId, mediaId, serverId)

    /** Takes the server's progress for a title where it's newer, leaving alone anything still waiting to be sent. */
    internal suspend fun mergeProgress(context: Context, originId: String, mediaId: String, remote: List<JsonObject>) {
        val waiting = Outbox(context).pendingUnits(originId, mediaId)
        val dao = ReadingProgressDao(LocalLibraryDatabase.get(context))
        for (r in remote) {
            val unitId = r.str("chapterId")
            if (unitId in waiting) continue
            val updatedAt = millis(r.strOrNull("updatedAt")) ?: continue
            val local = dao.get(originId, mediaId, unitId)
            if (local != null && local.updatedAt >= updatedAt) continue
            dao.upsert(
                ReadingProgressEntity(
                    originId = originId,
                    mediaId = mediaId,
                    unitId = unitId,
                    progress = r["progress"]!!.jsonPrimitive.content.toFloat(),
                    completed = r["completed"]?.jsonPrimitive?.contentOrNull == "true",
                    positionSeconds = r.strOrNull("positionSeconds")?.toDoubleOrNull(),
                    durationSeconds = r.strOrNull("durationSeconds")?.toDoubleOrNull(),
                    updatedAt = updatedAt,
                ),
            )
        }
    }

    internal fun millis(rfc3339: String?): Long? =
        rfc3339?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    internal const val PROGRESS_FIELDS = "chapterId progress completed positionSeconds durationSeconds updatedAt"
    private const val MAX_ATTEMPTS = 5
    private const val RETRY_MIN_MS = 15_000L
    private const val RETRY_MAX_MS = 5 * 60_000L
}

/** The last library and folders a server returned, so the library still shows what's there while it's away. */
internal object ServerSnapshot {
    fun dir(context: Context, instanceId: String) = File(context.filesDir, "server/$instanceId")

    fun save(context: Context, instanceId: String, name: String, json: String) {
        val dir = dir(context, instanceId).apply { mkdirs() }
        // A temp file of its own, so Home and Library saving at the same moment can't interleave their writes.
        val tmp = File.createTempFile(name, ".tmp", dir)
        tmp.writeText(json)
        tmp.renameTo(File(dir, "$name.json"))
    }

    fun load(context: Context, instanceId: String, name: String): String? =
        File(dir(context, instanceId), "$name.json").takeIf { it.exists() }?.readText()
}

internal data class OutboxEntry(val id: Long, val payload: JSONObject, val attempts: Int = 0)

/** `sync_outbox` holds progress still to be sent; `server_media` remembers each server title's server-side id. */
internal class Outbox(context: Context) {
    private val db = LocalLibraryDatabase.get(context)

    suspend fun add(payload: JSONObject) = withContext(Dispatchers.IO) {
        db.writableDatabase.insert(
            "sync_outbox", null,
            ContentValues().apply {
                put("originId", payload.getString("originId"))
                put("payload", payload.toString())
                put("createdAt", System.currentTimeMillis())
            },
        )
        Unit
    }

    suspend fun pending(): List<OutboxEntry> = withContext(Dispatchers.IO) {
        db.readableDatabase.query("sync_outbox", null, null, null, null, null, "id ASC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    val payload = runCatching { JSONObject(c.getStringOrNull(c.getColumnIndexOrThrow("payload"))!!) }.getOrDefault(JSONObject())
                    add(OutboxEntry(c.getLong(c.getColumnIndexOrThrow("id")), payload, c.getLongOrNull(c.getColumnIndexOrThrow("attempts"))?.toInt() ?: 0))
                }
            }
        }
    }

    // Only the newest position matters for a chapter; page turns would otherwise queue one request each.
    // Matched in SQL against the stored JSON, so marking a long series read doesn't re-parse the queue per chapter.
    suspend fun supersede(progress: JSONObject) = withContext(Dispatchers.IO) {
        fun field(key: String) = "%" + like(JSONObject.quote(key) + ":" + JSONObject.quote(progress.getString(key))) + "%"
        db.writableDatabase.delete(
            "sync_outbox",
            "originId = ? AND payload LIKE ? ESCAPE '\\' AND payload LIKE ? ESCAPE '\\'",
            arrayOf(progress.getString("originId"), field("mediaId"), field("unitId")),
        )
        Unit
    }

    private fun like(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    suspend fun forgetServerId(originId: String, mediaId: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("server_media", "originId = ? AND mediaId = ?", arrayOf(originId, mediaId))
        Unit
    }

    suspend fun pendingUnits(originId: String, mediaId: String): Set<String> = pending()
        .filter { it.payload.optString("originId") == originId && it.payload.optString("mediaId") == mediaId }
        .map { it.payload.optString("unitId") }
        .toSet()

    suspend fun delete(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        db.writableDatabase.execSQL("DELETE FROM sync_outbox WHERE id IN (${ids.joinToString(",")})")
    }

    suspend fun bumpAttempts(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        db.writableDatabase.execSQL("UPDATE sync_outbox SET attempts = attempts + 1 WHERE id IN (${ids.joinToString(",")})")
    }

    suspend fun countFor(instanceId: String?): Int = withContext(Dispatchers.IO) {
        if (instanceId == null) return@withContext 0
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_outbox WHERE originId LIKE ?", arrayOf("${ServerOrigin.PREFIX}$instanceId:%"))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    suspend fun serverId(originId: String, mediaId: String): String? = withContext(Dispatchers.IO) {
        db.readableDatabase.query("server_media", arrayOf("serverId"), "originId = ? AND mediaId = ?", arrayOf(originId, mediaId), null, null, null)
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    suspend fun putServerId(originId: String, mediaId: String, serverId: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict(
            "server_media", null,
            ContentValues().apply { put("originId", originId); put("mediaId", mediaId); put("serverId", serverId) },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
        Unit
    }
}

/** The address now answers as a different Tsunagu (reinstalled or replaced), so nothing may be sent to it. */
class ServerReplacedException : IOException("A different Tsunagu now answers at this address. Sign in again.")
