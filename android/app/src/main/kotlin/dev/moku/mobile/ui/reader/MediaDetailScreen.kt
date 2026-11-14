package dev.moku.mobile.ui.reader

import dev.moku.mobile.ui.components.SkeletonDetail
import dev.moku.mobile.ui.components.pressable
import android.content.Context
import dev.moku.mobile.ui.components.MokuToast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.moku.mobile.R
import dev.moku.mobile.backend.BackendMedia
import dev.moku.mobile.backend.BackendUnit
import dev.moku.mobile.backend.LocalBackendFactory
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.library.DownloadQueueManager
import dev.moku.mobile.library.DownloadStatus
import dev.moku.mobile.library.LocalLibraryRepository
import dev.moku.mobile.library.Libraries
import dev.moku.mobile.library.MediaCache
import dev.moku.mobile.library.FolderEntity
import dev.moku.mobile.sync.ServerOrigin
import dev.moku.mobile.sync.ServerSync
import dev.moku.mobile.sync.ServerDownloads
import dev.moku.mobile.ui.library.FolderPicker
import dev.moku.mobile.ui.library.SheetBody
import dev.moku.mobile.ui.library.SheetRow
import dev.moku.mobile.library.ReadingProgressEntity
import dev.moku.mobile.tracking.LocalTrackingRepository
import dev.moku.mobile.tracking.TrackLinkEntity
import dev.moku.mobile.tracking.TrackSync
import dev.moku.mobile.repository.LocalRepositoryManager
import dev.moku.mobile.ui.components.MokuChip
import dev.moku.mobile.ui.components.MokuHeaderAction
import dev.moku.mobile.ui.components.MokuSectionLabel
import dev.moku.mobile.ui.settings.ActionChip
import dev.moku.mobile.ui.settings.Danger
import dev.moku.mobile.ui.settings.GroupDivider
import dev.moku.mobile.ui.settings.SettingsGroup
import dev.moku.mobile.ui.settings.SettingsRow
import dev.moku.mobile.ui.settings.SettingsToggleRow
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class DetailState(
    val loading: Boolean = true,
    val error: String? = null,
    val media: BackendMedia? = null,
    val units: List<BackendUnit> = emptyList(),
    val inLibrary: Boolean = false,
    val sourceName: String? = null,
    val cachedAt: Long? = null,
    val refreshing: Boolean = false,
)

private enum class Show(val label: String) { ALL("All"), UNREAD("Unread"), DOWNLOADED("Downloaded") }

private data class ChapterFilter(
    val show: Show = Show.ALL,
    val scanlators: Set<String> = emptySet(),
    val dedupe: Boolean = false,
    val byDate: Boolean = false,
    val descending: Boolean = true,
) {
    val active get() = show != Show.ALL || scanlators.isNotEmpty() || dedupe
}

private object SeriesPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("moku_series_prefs", Context.MODE_PRIVATE)

    fun load(context: Context, key: String): ChapterFilter = runCatching {
        val o = JSONObject(prefs(context).getString(key, null)!!)
        val groups = o.optJSONArray("scanlators") ?: JSONArray()
        ChapterFilter(
            show = Show.valueOf(o.optString("show", Show.ALL.name)),
            scanlators = (0 until groups.length()).map { groups.getString(it) }.toSet(),
            dedupe = o.optBoolean("dedupe"),
            byDate = o.optBoolean("byDate"),
            descending = o.optBoolean("descending", true),
        )
    }.getOrDefault(ChapterFilter())

    fun save(context: Context, key: String, f: ChapterFilter) {
        val o = JSONObject()
            .put("show", f.show.name)
            .put("scanlators", JSONArray(f.scanlators.toList()))
            .put("dedupe", f.dedupe)
            .put("byDate", f.byDate)
            .put("descending", f.descending)
        prefs(context).edit().putString(key, o.toString()).apply()
    }
}

private val DATE = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MediaDetailScreen(
    originId: String,
    mediaId: String,
    onBack: () -> Unit,
    onOpenUnit: (BackendUnit) -> Unit,
    active: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MokuTheme.colors
    // A server title's library, folders and progress are its server's, even with server mode off.
    val repo = remember(originId) { Libraries.forOrigin(context, originId) }
    val device = remember { LocalLibraryRepository(context) }
    val cache = remember { MediaCache(context) }
    val tracking = remember { LocalTrackingRepository(context) }
    val trackSync = remember { TrackSync(context) }
    val trackable = originId.startsWith("local:")
    val queue = remember { DownloadQueueManager(context, device) }
    val prefsKey = "$originId|$mediaId"

    var state by remember { mutableStateOf(DetailState()) }
    var progress by remember { mutableStateOf(emptyMap<String, ReadingProgressEntity>()) }
    var downloads by remember { mutableStateOf(emptyMap<String, DownloadStatus>()) }
    var filter by remember { mutableStateOf(SeriesPrefs.load(context, prefsKey)) }
    var expanded by remember { mutableStateOf(false) }
    var downloadSheet by remember { mutableStateOf(false) }
    var filterSheet by remember { mutableStateOf(false) }
    var trackSheet by remember { mutableStateOf(false) }
    var folderSheet by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf(emptyList<FolderEntity>()) }
    var memberOf by remember { mutableStateOf(emptySet<Long>()) }
    val serverTitle = ServerOrigin.isServer(originId)
    // Chapters the server itself has downloaded, separate from what's downloaded to this phone.
    var onServer by remember { mutableStateOf(emptySet<String>()) }
    var tracks by remember { mutableStateOf(emptyList<TrackLinkEntity>()) }
    var actionsFor by remember { mutableStateOf<BackendUnit?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var reloadToken by remember { mutableIntStateOf(0) }

    fun setFilter(f: ChapterFilter) {
        filter = f
        SeriesPrefs.save(context, prefsKey, f)
    }

    suspend fun refreshLocal() {
        progress = repo.getProgressForMedia(originId, mediaId).associateBy { it.unitId }
        downloads = device.getDownloads().filter { it.originId == originId && it.mediaId == mediaId }.associate { it.unitId to it.status }
        if (trackable) tracks = tracking.getTrackLinks(originId, mediaId)
        folders = repo.getFolders()
        memberOf = repo.foldersFor(originId, mediaId)
        val serverMediaId = state.units.firstOrNull()?.mediaId
        if (serverTitle && serverMediaId != null && ServerOrigin.isCurrent(originId)) {
            onServer = runCatching { ServerDownloads.downloadedChapters(serverMediaId) }.getOrDefault(onServer)
        }
    }

    LaunchedEffect(originId, mediaId, reloadToken) {
        val cached = cache.get(originId, mediaId)
        val inLibrary = repo.isInLibrary(originId, mediaId)
        if (cached != null) {
            state = DetailState(loading = false, media = cached.media, units = cached.units, inLibrary = inLibrary, sourceName = cached.sourceName, cachedAt = cached.updatedAt, refreshing = true)
        }
        state = try {
            val fresh = cache.refresh(originId, mediaId)
            DetailState(loading = false, media = fresh.media, units = fresh.units, inLibrary = inLibrary, sourceName = fresh.sourceName)
        } catch (t: Throwable) {
            android.util.Log.e("MediaDetailScreen", "load failed for $originId/$mediaId", t)
            if (cached != null) {
                state.copy(refreshing = false)
            } else {
                DetailState(loading = false, error = t.message ?: "Couldn't load this title")
            }
        }
        // The chapter list carries the server's id for the title, which saves asking for it again.
        val serverMediaId = state.units.firstOrNull()?.mediaId
        if (ServerOrigin.isServer(originId) && !state.refreshing && serverMediaId != null) {
            ServerSync.rememberServerId(context, originId, mediaId, serverMediaId)
            refreshLocal()
        }
        if (trackable && state.units.isNotEmpty() && tracking.getTrackLinks(originId, mediaId).isNotEmpty()) {
            tracks = trackSync.sync(originId, mediaId, state.units)
            refreshLocal()
        }
    }

    LaunchedEffect(originId, mediaId, active) { if (active) refreshLocal() }

    LaunchedEffect(downloads) {
        if (downloads.values.any { it == DownloadStatus.QUEUED || it == DownloadStatus.DOWNLOADING }) {
            delay(1500)
            refreshLocal()
        }
    }

    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    val media = state.media
    val word = if (media?.contentType == MediaContentType.ANIME) "Episode" else "Chapter"
    val ordered = remember(state.units) {
        state.units.withIndex().sortedWith(compareBy({ it.value.num() ?: Float.MAX_VALUE }, { -it.index })).map { it.value }
    }
    val groups = remember(ordered) { ordered.map { it.group() }.filter { it.isNotBlank() }.groupingBy { it }.eachCount() }
    val inGroups = ordered.filter { filter.scanlators.isEmpty() || it.group() in filter.scanlators }
    val shown = inGroups
        .filter {
            when (filter.show) {
                Show.ALL -> true
                Show.UNREAD -> progress[it.id]?.completed != true
                Show.DOWNLOADED -> downloads[it.id] == DownloadStatus.DONE
            }
        }
        .let { list -> if (filter.dedupe) list.distinctBy { it.num() ?: it.id } else list }
        .let { list -> if (filter.byDate) list.sortedBy { it.uploadedAt ?: 0L } else list }
        .let { list -> if (filter.descending) list.reversed() else list }

    val readCount = inGroups.count { progress[it.id]?.completed == true }
    val inProgress = inGroups.filter { progress[it.id]?.let { p -> !p.completed && p.progress > 0f } == true }
        .maxByOrNull { progress[it.id]!!.updatedAt }
    val next = inProgress ?: inGroups.firstOrNull { progress[it.id]?.completed != true }
    val started = readCount > 0 || inProgress != null

    fun libraryTapped() = scope.launch {
        val m = media ?: return@launch
        if (state.inLibrary) {
            folderSheet = true
            return@launch
        }
        repo.addToLibrary(m)
        state = state.copy(inLibrary = repo.isInLibrary(originId, mediaId))
        if (!state.inLibrary) return@launch
        refreshLocal()
        if (folders.isNotEmpty()) folderSheet = true else MokuToast.show("Added to library")
    }

    fun download(units: List<BackendUnit>) = scope.launch {
        val m = media ?: return@launch
        val todo = units.filter { downloads[it.id] != DownloadStatus.DONE }
        if (todo.isEmpty()) {
            MokuToast.show("Already downloaded")
            return@launch
        }
        if (!state.inLibrary) {
            repo.addToLibrary(m)
            state = state.copy(inLibrary = repo.isInLibrary(originId, mediaId))
        }
        todo.forEach { queue.enqueue(originId, mediaId, it.id) }
        downloads = downloads + todo.associate { it.id to DownloadStatus.QUEUED }
        MokuToast.show("Queued ${todo.size} ${word.lowercase()}${if (todo.size == 1) "" else "s"}")
    }

    fun deleteDownloads(units: List<BackendUnit>) = scope.launch {
        val ids = units.map { it.id }.toSet()
        val entries = device.getDownloads().filter { it.originId == originId && it.mediaId == mediaId && it.unitId in ids }
        queue.remove(entries)
        refreshLocal()
        if (entries.isNotEmpty()) MokuToast.show("Deleted ${entries.size} download${if (entries.size == 1) "" else "s"}")
    }

    fun mark(units: List<BackendUnit>, read: Boolean) = scope.launch {
        units.forEach { repo.updateProgress(originId, mediaId, it.id, progress = if (read) 1f else 0f, completed = read) }
        refreshLocal()
        if (read && tracks.isNotEmpty()) tracks = trackSync.push(originId, mediaId, state.units)
    }

    fun onServerAction(units: List<BackendUnit>, remove: Boolean) = scope.launch {
        val serverMediaId = state.units.firstOrNull()?.mediaId ?: return@launch
        val ids = units.map { it.id }
        runCatching { if (remove) ServerDownloads.delete(serverMediaId, ids) else ServerDownloads.enqueue(serverMediaId, ids) }
            .onSuccess {
                val n = "${ids.size} ${word.lowercase()}${if (ids.size == 1) "" else "s"}"
                MokuToast.show(if (remove) "Removed $n from the server" else "Queued $n on the server")
                refreshLocal()
            }
            .onFailure { MokuToast.show(it.message ?: "The server didn't take that") }
    }

    fun from(unit: BackendUnit): List<BackendUnit> = inGroups.dropWhile { it.id != unit.id }

    when {
        state.loading -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopActions(onBack = onBack)
            SkeletonDetail()
        }
        state.error != null || media == null -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopActions(onBack = onBack)
            Column(
                Modifier.fillMaxSize().padding(MokuSpacing.Sp6),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3, Alignment.CenterVertically),
            ) {
                Text("Couldn't load this title", style = MokuText.body, color = colors.textMuted)
                Text(state.error.orEmpty(), style = MokuText.meta, color = colors.textFaint)
                ActionChip("Try again", icon = R.drawable.ph_arrows_clockwise_light) { state = DetailState(); reloadToken++ }
            }
        }
        else -> Box(Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 96.dp),
            ) {
                item {
                    Hero(
                        media = media,
                        sourceName = state.sourceName,
                        cachedAt = state.cachedAt,
                        refreshing = state.refreshing,
                        inLibrary = state.inLibrary,
                        onBack = onBack,
                        onRefresh = { reloadToken++ },
                        onToggleLibrary = { libraryTapped() },
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = MokuSpacing.Sp4, end = MokuSpacing.Sp4, top = MokuSpacing.Sp4),
                        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2),
                    ) {
                        val label = when {
                            ordered.isEmpty() -> "No ${word.lowercase()}s yet"
                            !started -> "Start reading"
                            next == null -> "Read again"
                            else -> "Continue · " + (next.num()?.let { "$word ${formatNumber(it)}" } ?: next.label(word))
                        }
                        PrimaryButton(label, Modifier.weight(1f), enabled = ordered.isNotEmpty()) {
                            (next ?: inGroups.firstOrNull() ?: ordered.firstOrNull())?.let(onOpenUnit)
                        }
                        if (trackable) SquareButton(R.drawable.ph_chart_line_up_light, "Tracking", active = tracks.isNotEmpty()) { trackSheet = true }
                        SquareButton(R.drawable.ph_download_simple_light, "Download options") { downloadSheet = true }
                    }
                }
                if (readCount > 0) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(start = MokuSpacing.Sp4, end = MokuSpacing.Sp4, top = MokuSpacing.Sp4),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val total = inGroups.distinctBy { it.num() ?: it.id }.size.coerceAtLeast(1)
                            val readDistinct = inGroups.filter { progress[it.id]?.completed == true }.distinctBy { it.num() ?: it.id }.size
                            Row {
                                MokuSectionLabel("Progress", Modifier.weight(1f))
                                Text("$readDistinct / $total · ${readDistinct * 100 / total}%", style = MokuText.label, color = colors.accentFg)
                            }
                            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(MokuRadius.Sm)).background(colors.borderBase)) {
                                Box(Modifier.fillMaxWidth(readDistinct / total.toFloat()).fillMaxHeight().background(colors.accent))
                            }
                        }
                    }
                }
                if (!media.description.isNullOrBlank() || media.genres.isNotEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(start = MokuSpacing.Sp4, end = MokuSpacing.Sp4, top = MokuSpacing.Sp5),
                            verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2),
                        ) {
                            MokuSectionLabel("About")
                            if (!media.description.isNullOrBlank()) {
                                Text(
                                    plainDescription(media.description),
                                    style = MokuText.caption.copy(fontSize = 13.sp, lineHeight = 20.sp),
                                    color = colors.textMuted,
                                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { expanded = !expanded },
                                )
                                Text(
                                    if (expanded) "Show less" else "Show more",
                                    style = MokuText.meta,
                                    color = colors.accentFg,
                                    modifier = Modifier.clip(RoundedCornerShape(MokuRadius.Sm)).clickable { expanded = !expanded }.padding(vertical = 2.dp),
                                )
                            }
                            if (media.genres.isNotEmpty()) {
                                FlowRow(
                                    Modifier.padding(top = MokuSpacing.Sp1),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) { media.genres.filter { it.isNotBlank() }.distinct().forEach { Tag(it) } }
                            }
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = MokuSpacing.Sp4, end = MokuSpacing.Sp2, top = MokuSpacing.Sp5, bottom = MokuSpacing.Sp1),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp1),
                    ) {
                        val count = if (shown.size == ordered.size) "${ordered.size}" else "${shown.size} of ${ordered.size}"
                        MokuSectionLabel("${word}s · $count", Modifier.weight(1f))
                        SmallIcon(R.drawable.ph_funnel_simple_light, "Filter", active = filter.active) { filterSheet = true }
                        SmallIcon(
                            if (filter.descending) R.drawable.ph_sort_descending_light else R.drawable.ph_sort_ascending_light,
                            if (filter.descending) "Newest first" else "Oldest first",
                        ) { setFilter(filter.copy(descending = !filter.descending)) }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderDim))
                }
                if (shown.isEmpty() && ordered.isNotEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(MokuSpacing.Sp6), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                            Text("No ${word.lowercase()}s match these filters", style = MokuText.sub, color = colors.textFaint)
                            ActionChip("Reset filters") { setFilter(ChapterFilter(descending = filter.descending)) }
                        }
                    }
                }
                items(shown, key = { it.id }) { unit ->
                    UnitRow(
                        unit = unit,
                        word = word,
                        progress = progress[unit.id],
                        download = downloads[unit.id],
                        onServer = unit.id in onServer,
                        selecting = selected.isNotEmpty(),
                        selected = unit.id in selected,
                        onClick = {
                            if (selected.isNotEmpty()) selected = if (unit.id in selected) selected - unit.id else selected + unit.id
                            else onOpenUnit(unit)
                        },
                        onLongClick = {
                            if (selected.isNotEmpty()) selected = selected + unit.id else actionsFor = unit
                        },
                        onDownloadTap = {
                            when (downloads[unit.id]) {
                                null, DownloadStatus.FAILED -> download(listOf(unit))
                                else -> actionsFor = unit
                            }
                        },
                    )
                }
            }

            if (listState.firstVisibleItemIndex > 0) {
                Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(colors.bgVoid))
            }

            if (selected.isNotEmpty()) {
                val picked = ordered.filter { it.id in selected }
                SelectionBar(
                    count = selected.size,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onSelectAll = { selected = shown.map { it.id }.toSet() },
                    onRead = { mark(picked, true); selected = emptySet() },
                    onUnread = { mark(picked, false); selected = emptySet() },
                    onDownload = { download(picked); selected = emptySet() },
                    onDelete = { deleteDownloads(picked); selected = emptySet() },
                    onClose = { selected = emptySet() },
                )
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    actionsFor?.let { unit ->
        val read = progress[unit.id]?.completed == true
        val dl = downloads[unit.id]
        ModalBottomSheet(onDismissRequest = { actionsFor = null }, sheetState = sheetState, containerColor = colors.bgSurface) {
            SheetColumn(unit.label(word)) {
                SettingsGroup {
                    SettingsRow(if (read) "Mark as unread" else "Mark as read", icon = if (read) R.drawable.ph_eye_slash_light else R.drawable.ph_eye_light) {
                        actionsFor = null; mark(listOf(unit), !read)
                    }
                    GroupDivider()
                    SettingsRow("Mark previous as read", icon = R.drawable.ph_checks_light) {
                        actionsFor = null; mark(inGroups.takeWhile { it.id != unit.id }, true)
                    }
                }
                SettingsGroup {
                    when (dl) {
                        DownloadStatus.DONE -> SettingsRow("Delete download", icon = R.drawable.ph_trash_light, danger = true) {
                            actionsFor = null; deleteDownloads(listOf(unit))
                        }
                        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> SettingsRow("Cancel download", icon = R.drawable.ph_x_light) {
                            actionsFor = null; deleteDownloads(listOf(unit))
                        }
                        else -> SettingsRow(
                            when {
                                dl == DownloadStatus.FAILED -> "Retry download"
                                serverTitle -> "Download to phone"
                                else -> "Download"
                            },
                            icon = R.drawable.ph_download_simple_light,
                        ) {
                            actionsFor = null; download(listOf(unit))
                        }
                    }
                    GroupDivider()
                    SettingsRow("Download next 5 from here", icon = R.drawable.ph_download_simple_light) {
                        actionsFor = null; download(from(unit).filter { downloads[it.id] == null }.take(5))
                    }
                    GroupDivider()
                    SettingsRow("Download all from here", icon = R.drawable.ph_download_simple_light) {
                        actionsFor = null; download(from(unit).filter { downloads[it.id] == null })
                    }
                }
                if (serverTitle && ServerOrigin.isCurrent(originId)) {
                    SettingsGroup("On the server") {
                        if (unit.id in onServer) {
                            SettingsRow("Remove from the server", icon = R.drawable.ph_trash_light, danger = true) {
                                actionsFor = null; onServerAction(listOf(unit), remove = true)
                            }
                        } else {
                            SettingsRow("Download on the server", icon = R.drawable.ph_hard_drives_light) {
                                actionsFor = null; onServerAction(listOf(unit), remove = false)
                            }
                        }
                    }
                }
                SettingsGroup {
                    SettingsRow("Select", icon = R.drawable.ph_list_checks_light) { actionsFor = null; selected = setOf(unit.id) }
                }
            }
        }
    }

    if (downloadSheet) {
        val unread = inGroups.filter { progress[it.id]?.completed != true && downloads[it.id] == null }
        val missing = inGroups.filter { downloads[it.id] == null }
        val done = ordered.filter { downloads[it.id] == DownloadStatus.DONE }
        val readDone = done.filter { progress[it.id]?.completed == true }
        ModalBottomSheet(onDismissRequest = { downloadSheet = false }, sheetState = sheetState, containerColor = colors.bgSurface) {
            SheetColumn("Downloads") {
                SettingsGroup("Download") {
                    SettingsRow("Next 5 unread", icon = R.drawable.ph_download_simple_light, value = "${minOf(5, unread.size)}") { downloadSheet = false; download(unread.take(5)) }
                    GroupDivider()
                    SettingsRow("Next 10 unread", icon = R.drawable.ph_download_simple_light, value = "${minOf(10, unread.size)}") { downloadSheet = false; download(unread.take(10)) }
                    GroupDivider()
                    SettingsRow("All unread", icon = R.drawable.ph_download_simple_light, value = "${unread.size}") { downloadSheet = false; download(unread) }
                    GroupDivider()
                    SettingsRow("Everything", icon = R.drawable.ph_download_simple_light, value = "${missing.size}") { downloadSheet = false; download(missing) }
                }
                if (done.isNotEmpty()) {
                    SettingsGroup("Delete") {
                        SettingsRow("Delete read downloads", icon = R.drawable.ph_trash_light, value = "${readDone.size}") { downloadSheet = false; deleteDownloads(readDone) }
                        GroupDivider()
                        SettingsRow("Delete all downloads", icon = R.drawable.ph_trash_light, value = "${done.size}", danger = true) { downloadSheet = false; deleteDownloads(done) }
                    }
                }
            }
        }
    }

    if (folderSheet && media != null) {
        ModalBottomSheet(onDismissRequest = { folderSheet = false }, sheetState = sheetState, containerColor = colors.bgSurface) {
            SheetBody(media.title) {
                FolderPicker(
                    folders = folders,
                    memberOf = memberOf,
                    onToggle = { folder, member ->
                        scope.launch {
                            if (member) repo.removeFromFolder(originId, mediaId, folder.id) else repo.addToFolder(originId, mediaId, folder.id)
                            refreshLocal()
                        }
                    },
                    onCreate = { name -> scope.launch { repo.createFolder(name)?.let { repo.addToFolder(originId, mediaId, it) }; refreshLocal() } },
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderDim))
                SheetRow("Remove from library", selected = false, icon = R.drawable.ph_trash_light, danger = true) {
                    scope.launch {
                        repo.removeFromLibrary(originId, mediaId)
                        state = state.copy(inLibrary = false)
                        folderSheet = false
                    }
                }
            }
        }
    }

    if (trackSheet && media != null) {
        TrackingSheet(
            originId = originId,
            mediaId = mediaId,
            title = media.title,
            contentType = media.contentType,
            tracks = tracks,
            onTracksChanged = { tracks = it },
            onLocalProgressChanged = { scope.launch { refreshLocal() } },
            onDismiss = { trackSheet = false },
        )
    }

    if (filterSheet) {
        ModalBottomSheet(onDismissRequest = { filterSheet = false }, sheetState = sheetState, containerColor = colors.bgSurface) {
            SheetColumn("${word}s", trailing = if (filter.active) ({ ActionChip("Reset") { setFilter(ChapterFilter(descending = filter.descending, byDate = filter.byDate)) } }) else null) {
                MokuSectionLabel("Show")
                Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                    Show.entries.forEach { s -> MokuChip(s.label, selected = filter.show == s) { setFilter(filter.copy(show = s)) } }
                }
                if (groups.isNotEmpty()) {
                    MokuSectionLabel("Scanlators")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                        MokuChip("All groups", selected = filter.scanlators.isEmpty()) { setFilter(filter.copy(scanlators = emptySet())) }
                        groups.entries.sortedByDescending { it.value }.forEach { (name, count) ->
                            val on = name in filter.scanlators
                            MokuChip(name, selected = on, count = count) {
                                setFilter(filter.copy(scanlators = if (on) filter.scanlators - name else filter.scanlators + name))
                            }
                        }
                    }
                }
                SettingsGroup {
                    SettingsToggleRow(
                        "Hide duplicates",
                        checked = filter.dedupe,
                        onCheckedChange = { setFilter(filter.copy(dedupe = it)) },
                        subtitle = "One entry per ${word.lowercase()} number",
                    )
                    GroupDivider()
                    SettingsToggleRow(
                        "Sort by upload date",
                        checked = filter.byDate,
                        onCheckedChange = { setFilter(filter.copy(byDate = it)) },
                        subtitle = "Instead of ${word.lowercase()} number",
                    )
                }
            }
        }
    }
}

@Composable
internal fun SheetColumn(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = MokuTheme.colors
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = MokuSpacing.Sp4, end = MokuSpacing.Sp4, bottom = MokuSpacing.Sp6),
        verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MokuText.heading, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        content()
    }
}

@Composable
private fun TopActions(onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = MokuSpacing.Sp4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2),
    ) {
        MokuHeaderAction(R.drawable.ph_arrow_left_light, "Back", onClick = onBack)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun Hero(
    media: BackendMedia,
    sourceName: String?,
    cachedAt: Long?,
    refreshing: Boolean,
    inLibrary: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleLibrary: () -> Unit,
) {
    val colors = MokuTheme.colors
    val coverShape = RoundedCornerShape(MokuRadius.Lg)
    Box(Modifier.fillMaxWidth()) {
        if (media.thumbnailUrl != null) {
            AsyncImage(
                model = media.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.matchParentSize().blur(36.dp).alpha(0.4f),
                contentScale = ContentScale.Crop,
            )
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(colors.bgVoid.copy(alpha = 0.3f), colors.bgVoid))))
        Column(Modifier.statusBarsPadding()) {
            TopActions(onBack = onBack) {
                MokuHeaderAction(R.drawable.ph_arrows_clockwise_light, "Refresh", onClick = onRefresh)
                MokuHeaderAction(
                    if (inLibrary) R.drawable.ph_bookmark_simple_fill else R.drawable.ph_bookmark_simple_light,
                    if (inLibrary) "Library and folders" else "Add to library",
                    active = inLibrary,
                    onClick = onToggleLibrary,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(start = MokuSpacing.Sp4, end = MokuSpacing.Sp4, top = MokuSpacing.Sp1),
                horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp4),
            ) {
                Box(Modifier.width(112.dp).height(168.dp).clip(coverShape).background(colors.bgRaised).border(1.dp, colors.borderDim, coverShape)) {
                    if (media.thumbnailUrl != null) {
                        AsyncImage(model = media.thumbnailUrl, contentDescription = media.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Column(Modifier.weight(1f).height(168.dp), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                    Text(media.title, style = MokuText.heading, color = colors.textPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    val people = (media.authors + media.artists).distinct()
                    if (people.isNotEmpty()) {
                        Text(people.joinToString(", "), style = MokuText.meta, color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp1)) {
                        media.status?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }?.let {
                            Badge(it.replace('_', ' '), accent = it.uppercase().contains("ONGOING"))
                        }
                        Badge(media.contentType.name)
                    }
                    if (sourceName != null) Text(sourceName, style = MokuText.label, color = colors.textFaint, maxLines = 1)
                    if (cachedAt != null && !refreshing) OfflineNote(cachedAt)
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, accent: Boolean = false) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Sm)
    Text(
        text.uppercase(),
        style = MokuText.label,
        color = if (accent) colors.accentFg else colors.textMuted,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(if (accent) colors.accentMuted else colors.bgRaised)
            .border(1.dp, if (accent) colors.accentDim else colors.borderDim, shape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun Tag(text: String) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Sm)
    Text(
        text,
        style = MokuText.label,
        color = colors.textMuted,
        modifier = Modifier.clip(shape).background(colors.bgRaised).border(1.dp, colors.borderDim, shape).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    Row(
        modifier
            .height(44.dp)
            .pressable(RoundedCornerShape(MokuRadius.Md), enabled = enabled, onClick = onClick)
            .background(if (enabled) colors.accent else colors.bgRaised)
            .padding(horizontal = MokuSpacing.Sp4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2, Alignment.CenterHorizontally),
    ) {
        val fg = if (enabled) colors.bgVoid else colors.textFaint
        Icon(painterResource(R.drawable.ph_play_fill), contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Text(text, style = MokuText.sub, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SquareButton(@DrawableRes icon: Int, label: String, active: Boolean = false, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Md)
    Box(
        Modifier.pressable(shape, onClick = onClick).size(44.dp)
            .background(if (active) colors.accentDim else colors.bgRaised)
            .border(1.dp, if (active) colors.accent.copy(alpha = 0.5f) else colors.borderBase, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = if (active) colors.accentFg else colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SmallIcon(@DrawableRes icon: Int, label: String, active: Boolean = false, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    Box(
        Modifier
            .size(32.dp)
            .pressable(RoundedCornerShape(MokuRadius.Md), onClick = onClick)
            .background(if (active) colors.accentMuted else androidx.compose.ui.graphics.Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = if (active) colors.accentFg else colors.textMuted, modifier = Modifier.size(16.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UnitRow(
    unit: BackendUnit,
    word: String,
    progress: ReadingProgressEntity?,
    download: DownloadStatus?,
    onServer: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownloadTap: () -> Unit,
) {
    val colors = MokuTheme.colors
    val haptics = LocalHapticFeedback.current
    val read = progress?.completed == true
    val title = unit.cleanTitle()
    Column(Modifier.background(if (selected) colors.accent.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
                )
                .padding(start = MokuSpacing.Sp4, end = MokuSpacing.Sp2, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
        ) {
            if (selecting) {
                Icon(
                    painterResource(if (selected) R.drawable.ph_check_square_fill else R.drawable.ph_square_light),
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) colors.accentFg else colors.textFaint,
                    modifier = Modifier.size(18.dp),
                )
            }
            val n = unit.num()
            Text(
                n?.let { "${word.take(2)}. ${formatNumber(it)}" } ?: "—",
                style = MokuText.sub,
                color = if (read) colors.textFaint else colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.width(72.dp),
            )
            Column(Modifier.weight(1f).alpha(if (read) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val shownTitle = if (n == null) unit.title.orEmpty() else title
                if (shownTitle.isNotBlank()) {
                    Text(shownTitle, style = MokuText.caption.copy(fontSize = 13.sp), color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val meta = listOfNotNull(
                    unit.uploadedAt?.takeIf { it > 0 }?.let { DATE.format(Date(it)) },
                    unit.group().takeIf { it.isNotBlank() },
                )
                if (meta.isNotEmpty()) {
                    Text(
                        meta.joinToString(" · "),
                        style = if (shownTitle.isBlank()) MokuText.meta else MokuText.label,
                        color = if (shownTitle.isBlank()) colors.textMuted else colors.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                read -> Icon(painterResource(R.drawable.ph_check_bold), contentDescription = "Read", tint = colors.textFaint, modifier = Modifier.size(12.dp))
                progress != null && progress.progress > 0f -> Text("${(progress.progress * 100).toInt()}%", style = MokuText.label, color = colors.accentFg)
            }
            if (onServer) Icon(painterResource(R.drawable.ph_hard_drives_light), contentDescription = "On the server", tint = colors.textFaint, modifier = Modifier.size(14.dp))
            if (!selecting) DownloadIndicator(download, onDownloadTap)
        }
        Box(Modifier.fillMaxWidth().padding(start = MokuSpacing.Sp4).height(1.dp).background(colors.borderDim))
    }
}

@Composable
private fun DownloadIndicator(status: DownloadStatus?, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Md)
    val base = Modifier.size(32.dp).clip(shape)
    when (status) {
        DownloadStatus.DONE -> Box(
            base.background(colors.accentMuted).border(1.dp, colors.accentDim, shape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ph_check_bold), contentDescription = "Downloaded", tint = colors.accentFg, modifier = Modifier.size(13.dp))
        }
        DownloadStatus.DOWNLOADING -> Box(base.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent, strokeWidth = 1.5.dp, modifier = Modifier.size(14.dp))
        }
        DownloadStatus.QUEUED -> Box(base.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ph_clock_light), contentDescription = "Queued", tint = colors.textMuted, modifier = Modifier.size(16.dp))
        }
        DownloadStatus.FAILED -> Box(base.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ph_warning_circle_light), contentDescription = "Download failed, tap to retry", tint = Danger, modifier = Modifier.size(16.dp))
        }
        null -> Box(base.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ph_download_simple_light), contentDescription = "Download", tint = colors.textFaint, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    modifier: Modifier,
    onSelectAll: () -> Unit,
    onRead: () -> Unit,
    onUnread: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Xxl)
    Row(
        modifier
            .navigationBarsPadding()
            .padding(MokuSpacing.Sp3)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.bgRaised)
            .border(1.dp, colors.borderBase, shape)
            .padding(horizontal = MokuSpacing.Sp2, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallIcon(R.drawable.ph_x_light, "Clear selection", onClick = onClose)
        Text("$count", style = MokuText.value, color = colors.textPrimary, modifier = Modifier.padding(horizontal = MokuSpacing.Sp2).weight(1f))
        SmallIcon(R.drawable.ph_list_checks_light, "Select all", onClick = onSelectAll)
        SmallIcon(R.drawable.ph_eye_light, "Mark read", onClick = onRead)
        SmallIcon(R.drawable.ph_eye_slash_light, "Mark unread", onClick = onUnread)
        SmallIcon(R.drawable.ph_download_simple_light, "Download", onClick = onDownload)
        SmallIcon(R.drawable.ph_trash_light, "Delete downloads", onClick = onDelete)
    }
}

@Composable
private fun OfflineNote(cachedAt: Long) {
    val colors = MokuTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(R.drawable.ph_cloud_slash_light), contentDescription = null, tint = colors.textFaint, modifier = Modifier.size(12.dp))
        Text("Offline · saved ${relativeAge(cachedAt)}", style = MokuText.label, color = colors.textFaint, maxLines = 1)
    }
}

internal fun relativeAge(at: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - at) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}

private val MD_LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
private val MD_EMPHASIS = Regex("""(\*\*|__)(.+?)\1""")
private val MD_BULLET = Regex("""(?m)^\s*[-*]\s+""")

/** Sources often write descriptions in Markdown; the markers read as noise in plain text. */
internal fun plainDescription(text: String): String = text.trim()
    .replace(MD_LINK, "$1")
    .replace(MD_EMPHASIS, "$2")
    .replace(MD_BULLET, "• ")
