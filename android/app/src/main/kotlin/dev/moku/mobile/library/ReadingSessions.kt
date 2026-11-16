package dev.moku.mobile.library

import android.content.Context
import dev.moku.mobile.sync.ServerOrigin
import dev.moku.mobile.sync.ServerSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

data class ReadingSession(
    val id: String,
    val originId: String,
    val mediaId: String,
    val startUnitId: String,
    val endUnitId: String,
    val startPage: Int,
    val endPage: Int,
    val startedAt: Long,
    val endedAt: Long,
    val units: Int,
)

// Like desktop, a session ends after an hour without reading or when another title is opened.
// Phone titles go to the phone's Tsunagu, server titles to that server.
object ReadingSessions {
    const val GAP_MS = 3_600_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var active: Active? = null

    private class Active(
        val id: String,
        val originId: String,
        val mediaId: String,
        val startUnitId: String,
        val startPage: Int,
        val startedAt: Long,
    ) {
        var endUnitId = startUnitId
        var endPage = startPage
        var last = startedAt
        val units = mutableSetOf(startUnitId)
    }

    @Synchronized
    fun progress(context: Context, originId: String, mediaId: String, unitId: String, page: Int) {
        if (!originId.startsWith("local:") && !ServerOrigin.isServer(originId)) return
        val now = System.currentTimeMillis()
        active?.let { a ->
            if (a.originId != originId || a.mediaId != mediaId || now - a.last > GAP_MS) {
                commit(context, a, if (now - a.last > GAP_MS) a.last else now)
                active = null
            }
        }
        val a = active ?: Active(UUID.randomUUID().toString(), originId, mediaId, unitId, page, now).also { active = it }
        a.endUnitId = unitId
        a.endPage = page
        a.last = now
        a.units += unitId
    }

    /** Ends the session when the reader closes or the app goes to the background. */
    @Synchronized
    fun close(context: Context) {
        active?.let { commit(context, it, System.currentTimeMillis()) }
        active = null
    }

    private fun commit(context: Context, a: Active, endedAt: Long) {
        if (endedAt - a.startedAt < 1_000) return
        val session = ReadingSession(a.id, a.originId, a.mediaId, a.startUnitId, a.endUnitId, a.startPage, a.endPage, a.startedAt, endedAt, a.units.size)
        val app = context.applicationContext
        scope.launch {
            if (ServerOrigin.isServer(session.originId)) ServerSync.recordSession(app, session) else CoreLibrary(app).recordSession(session)
        }
    }
}
