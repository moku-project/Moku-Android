package dev.moku.mobile.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.moku.mobile.R
import dev.moku.mobile.backend.BackendUnit
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.library.DownloadEntity
import dev.moku.mobile.library.DownloadQueueManager
import dev.moku.mobile.library.DownloadStatus
import dev.moku.mobile.library.LocalLibraryRepository
import dev.moku.mobile.library.MediaCache
import dev.moku.mobile.remote.ServerSession
import dev.moku.mobile.sync.ServerDownload
import dev.moku.mobile.sync.ServerDownloader
import dev.moku.mobile.sync.ServerDownloads
import dev.moku.mobile.sync.ServerOrigin
import dev.moku.mobile.sync.ServerTitleDownloads
import dev.moku.mobile.ui.components.MokuChip
import dev.moku.mobile.ui.components.MokuHeaderAction
import dev.moku.mobile.ui.components.MokuToast
import dev.moku.mobile.ui.components.SkeletonRows
import dev.moku.mobile.ui.reader.formatNumber
import dev.moku.mobile.ui.settings.ActionChip
import dev.moku.mobile.ui.settings.GroupDivider
import dev.moku.mobile.ui.settings.SettingsGroup
import dev.moku.mobile.ui.settings.SettingsPage
import dev.moku.mobile.ui.settings.SettingsRow
import dev.moku.mobile.ui.settings.SettingsToggleRow
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the phone knows about a downloaded title: from the phone's library, or the details cached when it was opened. */
private data class TitleInfo(val title: String, val thumbnailUrl: String?, val units: List<BackendUnit>, val type: MediaContentType?)

private data class SeriesDownloads(val key: Pair<String, String>, val info: TitleInfo?, val entries: List<DownloadEntity>) {
    val bytes get() = entries.sumOf { it.bytes }
}

private enum class Tab { QUEUE, DOWNLOADED, SERVER }

private sealed interface ServerState {
    data object Loading : ServerState
    data class Ready(val downloader: ServerDownloader, val queue: List<ServerDownload>, val titles: List<ServerTitleDownloads>) : ServerState
    data class Failed(val message: String) : ServerState
}

@Composable
fun DownloadsScreen(onOpenMedia: (String, String) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MokuTheme.colors
    val repo = remember { LocalLibraryRepository(context) }
    val cache = remember { MediaCache(context) }
    val queue = remember { DownloadQueueManager(context, repo) }
    val serverMode by ServerSession.serverMode.collectAsState()

    var loading by remember { mutableStateOf(true) }
    var downloads by remember { mutableStateOf(emptyList<DownloadEntity>()) }
    var titles by remember { mutableStateOf(emptyMap<Pair<String, String>, TitleInfo>()) }
    var tab by remember { mutableStateOf<Tab?>(null) }
    var expanded by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var server by remember { mutableStateOf<ServerState>(ServerState.Loading) }
    var serverReload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        downloads = repo.getDownloads().sortedByDescending { it.createdAt }
        val library = repo.getLibrary().associateBy { it.originId to it.mediaId }
        titles = downloads.map { it.originId to it.mediaId }.distinct().associateWith { key ->
            val cached = cache.get(key.first, key.second)
            val local = library[key]
            when {
                cached != null -> TitleInfo(cached.media.title, cached.media.thumbnailUrl, cached.units, cached.media.contentType)
                local != null -> TitleInfo(local.title, local.thumbnailUrl, emptyList(), local.contentType)
                else -> null
            }
        }.filterValues { it != null }.mapValues { it.value!! }
        loading = false
    }

    val pending = downloads.filter { it.status != DownloadStatus.DONE }
    val failed = pending.filter { it.status == DownloadStatus.FAILED }
    val done = downloads.filter { it.status == DownloadStatus.DONE }
        .groupBy { it.originId to it.mediaId }
        .map { (key, entries) -> SeriesDownloads(key, titles[key], entries) }
    val current = tab?.takeIf { it != Tab.SERVER || serverMode } ?: if (pending.isNotEmpty()) Tab.QUEUE else Tab.DOWNLOADED

    // Downloads run in a background worker that can't call back here, so the list re-reads itself while any are in flight.
    val inFlight = pending.any { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
    LaunchedEffect(inFlight, reload) {
        if (!inFlight) return@LaunchedEffect
        delay(2_000)
        reload++
    }

    // The phone can't be told when the server's queue moves, so it's re-read every few seconds while in view.
    LaunchedEffect(current, serverReload) {
        if (current != Tab.SERVER) return@LaunchedEffect
        while (true) {
            server = runCatching {
                ServerState.Ready(ServerDownloads.downloader(), ServerDownloads.queue(), ServerDownloads.downloadedTitles())
            }.getOrElse { ServerState.Failed(it.message ?: "Couldn't reach the server") }
            delay(4_000)
        }
    }

    fun remove(entries: List<DownloadEntity>) = scope.launch {
        queue.remove(entries)
        reload++
    }

    fun onServer(action: suspend () -> Unit) = scope.launch {
        runCatching { action() }.onFailure { MokuToast.show(it.message ?: "The server didn't take that") }
        serverReload++
    }

    SettingsPage("Downloads", trailing = {
        if (current != Tab.SERVER) {
            MokuHeaderAction(R.drawable.ph_arrow_clockwise_light, "Retry failed", active = failed.isNotEmpty()) {
                if (failed.isEmpty()) {
                    MokuToast.show("Nothing failed")
                } else scope.launch {
                    failed.forEach { queue.enqueue(it.originId, it.mediaId, it.unitId) }
                    reload++
                }
            }
            MokuHeaderAction(R.drawable.ph_x_light, "Cancel queue", active = confirmCancel) {
                when {
                    pending.isEmpty() -> MokuToast.show("The queue is empty")
                    !confirmCancel -> {
                        confirmCancel = true
                        MokuToast.show("Tap again to cancel ${pending.size} downloads")
                    }
                    else -> { confirmCancel = false; remove(pending) }
                }
            }
        }
    }) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                MokuChip("Queue", selected = current == Tab.QUEUE, count = pending.size) { tab = Tab.QUEUE }
                MokuChip("On this phone", selected = current == Tab.DOWNLOADED, count = done.sumOf { it.entries.size }) { tab = Tab.DOWNLOADED }
                if (serverMode) MokuChip("On server", selected = current == Tab.SERVER) { tab = Tab.SERVER }
            }
        }
        when {
            current == Tab.SERVER -> serverTab(server, onOpenMedia, ::onServer)
            loading -> item { SkeletonRows(5, leading = 40.dp) }
            current == Tab.QUEUE && pending.isEmpty() -> item { Empty("Nothing downloading", "Downloads you start from a series page show up here") }
            current == Tab.DOWNLOADED && done.isEmpty() -> item { Empty("No downloads yet", "Downloaded chapters and episodes are kept here for offline reading") }
            current == Tab.QUEUE -> item {
                SettingsGroup(footer = if (failed.isNotEmpty()) "${failed.size} failed · use the retry button to try them again" else null) {
                    pending.forEachIndexed { i, e ->
                        if (i > 0) GroupDivider()
                        val info = titles[e.originId to e.mediaId]
                        SettingsRow(
                            info?.title ?: e.mediaId,
                            subtitle = unitName(info, e.unitId) + " · " + when (e.status) {
                                // A server title waits for the server to finish before the phone copies it.
                                DownloadStatus.QUEUED -> if (ServerOrigin.isServer(e.originId)) "Waiting for the server" else "Queued"
                                DownloadStatus.DOWNLOADING -> if (ServerOrigin.isServer(e.originId)) "Copying from the server" else "Downloading"
                                DownloadStatus.FAILED -> "Failed" + (e.error?.let { ": $it" } ?: "")
                                DownloadStatus.DONE -> "Done"
                            },
                            leading = { Cover(info?.thumbnailUrl) },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp1)) {
                                    when (e.status) {
                                        DownloadStatus.DOWNLOADING -> CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                        DownloadStatus.FAILED -> ActionChip("Retry", accent = true) {
                                            scope.launch { queue.enqueue(e.originId, e.mediaId, e.unitId); reload++ }
                                        }
                                        else -> Unit
                                    }
                                    IconButton(R.drawable.ph_x_light, "Cancel") { remove(listOf(e)) }
                                }
                            },
                        )
                    }
                }
            }
            else -> {
                // The phone's own titles first, then one group per server, so it's clear where each came from.
                val groups = done.groupBy { ServerOrigin.parse(it.key.first)?.instanceId }
                    .toList()
                    .sortedBy { (instance, _) -> instance != null }
                groups.forEach { (instance, series) ->
                    item(key = "group:${instance ?: "phone"}") {
                        SettingsGroup(
                            label = if (instance == null) "This phone's library" else "From ${ServerSession.nameOf(instance) ?: "a server"}",
                            footer = when {
                                instance == null -> null
                                instance == ServerSession.current?.instanceId -> null
                                else -> "This phone isn't signed in to that server. These still open, and progress waits until you sign in to it again."
                            },
                        ) {
                            series.forEachIndexed { i, s ->
                                if (i > 0) GroupDivider()
                                val open = expanded == s.key
                                SettingsRow(
                                    s.info?.title ?: s.key.second,
                                    subtitle = "${s.entries.size} downloaded · ${formatBytes(s.bytes)}",
                                    leading = { Cover(s.info?.thumbnailUrl) },
                                    chevron = !open,
                                ) { expanded = if (open) null else s.key }
                                if (open) {
                                    Row(Modifier.fillMaxWidth().padding(start = 60.dp, end = MokuSpacing.Sp3, bottom = MokuSpacing.Sp2), horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                                        ActionChip("Open", icon = R.drawable.ph_book_open_light) { onOpenMedia(s.key.first, s.key.second) }
                                        ActionChip("Delete all", icon = R.drawable.ph_trash_light, danger = true) { remove(s.entries) }
                                    }
                                    s.entries.sortedBy { e -> s.info?.units?.indexOfFirst { it.id == e.unitId } ?: 0 }.forEach { e ->
                                        Row(
                                            Modifier.fillMaxWidth().padding(start = 60.dp, end = MokuSpacing.Sp3),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(unitName(s.info, e.unitId), style = MokuText.sub, color = colors.textSecondary, modifier = Modifier.weight(1f), maxLines = 1)
                                            Text(formatBytes(e.bytes), style = MokuText.meta, color = colors.textFaint)
                                            IconButton(R.drawable.ph_trash_light, "Delete") { remove(listOf(e)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.serverTab(
    state: ServerState,
    onOpenMedia: (String, String) -> Unit,
    act: (suspend () -> Unit) -> Unit,
) {
    when (state) {
        ServerState.Loading -> item { SkeletonRows(4, leading = 40.dp) }
        is ServerState.Failed -> item { Empty("Can't reach the server", state.message) }
        is ServerState.Ready -> {
            item {
                val d = state.downloader
                SettingsGroup(footer = "Chapters you download to this phone are downloaded here first, then copied over.") {
                    SettingsToggleRow(
                        "Server downloads",
                        checked = d.running,
                        onCheckedChange = { on -> act { ServerDownloads.setRunning(on) } },
                        icon = R.drawable.ph_hard_drives_light,
                        subtitle = listOfNotNull(
                            if (d.running) "Running" else "Paused",
                            d.downloading.takeIf { it > 0 }?.let { "$it downloading" },
                            d.queued.takeIf { it > 0 }?.let { "$it queued" },
                            d.failed.takeIf { it > 0 }?.let { "$it failed" },
                        ).joinToString(" · "),
                    )
                }
            }
            val active = state.queue.filter { it.status != "DONE" }
            if (active.isNotEmpty()) {
                item {
                    SettingsGroup("Server queue") {
                        active.forEachIndexed { i, d ->
                            if (i > 0) GroupDivider()
                            SettingsRow(
                                d.mediaTitle.ifBlank { "Title ${d.mediaId}" },
                                subtitle = listOfNotNull(
                                    d.chapterNumber?.let { "Ch. ${formatNumber(it)}" } ?: d.chapterTitle,
                                    when (d.status) {
                                        "DOWNLOADING" -> "${(d.progress * 100).toInt()}%"
                                        "FAILED" -> "Failed" + (d.error?.let { ": $it" } ?: "")
                                        else -> "Queued"
                                    },
                                ).joinToString(" · "),
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp1)) {
                                        if (d.status == "FAILED") ActionChip("Retry", accent = true) { act { ServerDownloads.retry(d.mediaId, d.chapterId) } }
                                        IconButton(R.drawable.ph_x_light, "Cancel") { act { ServerDownloads.delete(d.mediaId, listOf(d.chapterId)) } }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (state.queue.any { it.status == "DONE" || it.status == "FAILED" }) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ActionChip("Clear finished", icon = R.drawable.ph_checks_light) { act { ServerDownloads.clearFinished() } }
                    }
                }
            }
            item {
                if (state.titles.isEmpty()) {
                    Empty("Nothing downloaded on the server", "Download chapters on the server from a series page, or download them to this phone")
                } else {
                    SettingsGroup("Downloaded on the server", footer = "Open a title to remove chapters from the server or copy them to this phone.") {
                        state.titles.forEachIndexed { i, t ->
                            if (i > 0) GroupDivider()
                            SettingsRow(t.title, subtitle = "${t.count} downloaded", leading = { Cover(t.thumbnailUrl) }, chevron = true) {
                                onOpenMedia(t.originId, t.mediaId)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun unitName(info: TitleInfo?, unitId: String): String {
    val unit = info?.units?.firstOrNull { it.id == unitId } ?: return unitId.substringAfterLast('/')
    val word = if (info.type == MediaContentType.ANIME) "Ep." else "Ch."
    return unit.number?.let { "$word ${formatNumber(it)}" } ?: unit.title ?: unitId.substringAfterLast('/')
}

@Composable
private fun Cover(url: String?) {
    val colors = MokuTheme.colors
    Box(Modifier.size(width = 30.dp, height = 42.dp).clip(RoundedCornerShape(MokuRadius.Sm)).background(colors.bgOverlay)) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun IconButton(icon: Int, label: String, onClick: () -> Unit) {
    Icon(
        painterResource(icon),
        contentDescription = label,
        tint = MokuTheme.colors.textMuted,
        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(MokuRadius.Md)).clickable(onClick = onClick).padding(7.dp),
    )
}

@Composable
private fun Empty(title: String, body: String) {
    val colors = MokuTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = MokuSpacing.Sp10),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2),
    ) {
        Icon(painterResource(R.drawable.ph_download_simple_light), contentDescription = null, tint = colors.textFaint, modifier = Modifier.size(28.dp))
        Text(title, style = MokuText.body, color = colors.textMuted)
        Text(body, style = MokuText.meta, color = colors.textFaint)
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        mb >= 1024 -> "%.1f GB".format(mb / 1024)
        mb >= 1 -> "%.1f MB".format(mb)
        else -> "${bytes / 1024} KB"
    }
}
