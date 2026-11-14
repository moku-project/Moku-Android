package dev.moku.mobile.ui.library

import dev.moku.mobile.ui.components.SkeletonPosterGrid
import dev.moku.mobile.ui.components.rememberReselectGridState
import android.content.Context
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.moku.mobile.R
import dev.moku.mobile.library.DownloadStatus
import dev.moku.mobile.library.FolderEntity
import dev.moku.mobile.library.LibraryMediaEntity
import dev.moku.mobile.library.LocalLibraryRepository
import dev.moku.mobile.ui.components.ContentTypeFilterController
import dev.moku.mobile.ui.components.MokuChip
import dev.moku.mobile.ui.components.MokuHeaderAction
import dev.moku.mobile.ui.components.MokuSearchField
import dev.moku.mobile.ui.components.MokuSectionLabel
import dev.moku.mobile.ui.components.MokuTopBar
import dev.moku.mobile.ui.components.MokuToast
import dev.moku.mobile.ui.components.pressable
import dev.moku.mobile.remote.ServerSession
import dev.moku.mobile.library.Libraries
import androidx.compose.runtime.collectAsState
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibrarySort(val label: String) { LAST_READ("Last read"), DATE_ADDED("Date added"), TITLE("Title") }

private enum class Progress(val label: String) { STARTED("Started"), NOT_STARTED("Not started") }

private enum class Status(val label: String) { ONGOING("Ongoing"), COMPLETED("Completed"), ON_HIATUS("Hiatus"), CANCELLED("Cancelled") }

private data class LibraryFilters(
    val progress: Progress? = null,
    val downloaded: Boolean = false,
    val status: Status? = null,
) {
    val active get() = progress != null || downloaded || status != null
}

private data class LibraryData(
    val items: List<LibraryMediaEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val folderMembers: Map<Long, Set<String>> = emptyMap(),
    val downloaded: Set<String> = emptySet(),
    val started: Set<String> = emptySet(),
)

private object LibraryPrefs {
    private const val NAME = "moku_library_prefs"

    fun sort(context: Context): Pair<LibrarySort, Boolean> {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val mode = runCatching { LibrarySort.valueOf(prefs.getString("sort", null)!!) }.getOrDefault(LibrarySort.LAST_READ)
        return mode to prefs.getBoolean("descending", true)
    }

    fun setSort(context: Context, mode: LibrarySort, descending: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("sort", mode.name).putBoolean("descending", descending).apply()
    }

    fun tab(context: Context): Long? = context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong("tab", -1L).takeIf { it >= 0 }

    fun setTab(context: Context, id: Long?) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putLong("tab", id ?: -1L).apply()
    }
}

private val LibraryMediaEntity.key get() = "$originId:$mediaId"

private fun LibraryMediaEntity.statusValue(): Status? {
    val s = status?.uppercase()?.replace(' ', '_') ?: return null
    return if (s == "PUBLISHING_FINISHED") Status.COMPLETED else Status.entries.firstOrNull { it.name == s }
}

private enum class Sheet { SORT, FILTER, FOLDERS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenMedia: (String, String) -> Unit = { _, _ -> },
    onBrowse: () -> Unit = {},
) {
    val context = LocalContext.current
    val colors = MokuTheme.colors
    val scope = rememberCoroutineScope()
    val serverMode by ServerSession.serverMode.collectAsState()
    val reachable by ServerSession.reachable.collectAsState()
    val repo = remember(serverMode) { Libraries.active(context) }
    val device = remember { LocalLibraryRepository(context) }
    // Offline in server mode the library is the server's last answer: only what's downloaded can open.
    val offline = serverMode && !reachable
    val activeType = ContentTypeFilterController.current.mediaType

    var loading by remember { mutableStateOf(true) }
    var data by remember { mutableStateOf(LibraryData()) }
    var reload by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(LibraryPrefs.tab(context)) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(LibraryFilters()) }
    var sort by remember { mutableStateOf(LibraryPrefs.sort(context)) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var menuFor by remember { mutableStateOf<LibraryMediaEntity?>(null) }
    val searchFocus = remember { FocusRequester() }

    fun selectTab(id: Long?) {
        tab = id
        LibraryPrefs.setTab(context, id)
    }

    fun folders(action: suspend () -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { action() }
        reload++
    }

    LaunchedEffect(activeType, reload, serverMode, reachable) {
        data = withContext(Dispatchers.IO) {
            val items = if (activeType == null) repo.getLibrary() else repo.getLibrary(activeType)
            val folders = repo.getFolders()
            LibraryData(
                items = items,
                folders = folders,
                folderMembers = folders.associate { f -> f.id to repo.getFolderContents(f.id).map { it.key }.toSet() },
                downloaded = device.getDownloads(DownloadStatus.DONE).map { "${it.originId}:${it.mediaId}" }.toSet(),
                started = repo.getProgressSince(0L).map { "${it.originId}:${it.mediaId}" }.toSet(),
            )
        }
        if (tab != null && data.folders.none { it.id == tab }) selectTab(null)
        loading = false
    }

    LaunchedEffect(searchOpen) { if (searchOpen) runCatching { searchFocus.requestFocus() } }

    val inTab = tab?.let { id -> data.items.filter { it.key in data.folderMembers[id].orEmpty() } } ?: data.items
    val (sortMode, descending) = sort
    val shown = inTab
        .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
        .filter {
            when (filters.progress) {
                Progress.STARTED -> it.key in data.started
                Progress.NOT_STARTED -> it.key !in data.started
                null -> true
            }
        }
        .filter { !filters.downloaded || it.key in data.downloaded }
        .filter { filters.status == null || it.statusValue() == filters.status }
        .let { list ->
            val sorted = when (sortMode) {
                LibrarySort.TITLE -> list.sortedBy { it.title.lowercase() }
                LibrarySort.LAST_READ -> list.sortedBy { it.lastViewedAt ?: 0L }
                LibrarySort.DATE_ADDED -> list.sortedBy { it.addedAt }
            }
            val ordered = if (descending) sorted.reversed() else sorted
            if (offline) ordered.sortedByDescending { it.key in data.downloaded } else ordered
        }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        MokuTopBar(
            title = "Library",
            trailing = {
                MokuHeaderAction(R.drawable.ph_magnifying_glass_light, "Search", active = searchOpen || query.isNotEmpty()) {
                    if (searchOpen) query = ""
                    searchOpen = !searchOpen
                }
                MokuHeaderAction(if (descending) R.drawable.ph_sort_descending_light else R.drawable.ph_sort_ascending_light, "Sort") { sheet = Sheet.SORT }
                MokuHeaderAction(R.drawable.ph_funnel_simple_light, "Filter", active = filters.active) { sheet = Sheet.FILTER }
            },
        )

        if (searchOpen) {
            MokuSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search your library",
                focusRequester = searchFocus,
                modifier = Modifier.padding(start = MokuSpacing.Sp4, end = MokuSpacing.Sp4, bottom = MokuSpacing.Sp2),
            )
        }

        if (data.items.isNotEmpty() || data.folders.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = MokuSpacing.Sp4),
                horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = MokuSpacing.Sp2),
            ) {
                item { MokuChip("All", selected = tab == null, count = data.items.size) { selectTab(null) } }
                items(data.folders, key = { it.id }) { folder ->
                    val count = data.items.count { it.key in data.folderMembers[folder.id].orEmpty() }
                    MokuChip(folder.name, selected = tab == folder.id, count = count) { selectTab(folder.id) }
                }
                item { FolderEditChip(hasFolders = data.folders.isNotEmpty()) { sheet = Sheet.FOLDERS } }
            }
        }

        when {
            loading -> SkeletonPosterGrid(modifier = Modifier.padding(start = MokuSpacing.Sp4, end = MokuSpacing.Sp4, top = MokuSpacing.Sp2))
            data.items.isEmpty() -> EmptyState(
                title = "Your library is empty",
                body = "Add titles from Discover and they'll show up here",
                action = "Browse",
                onAction = onBrowse,
            )
            shown.isEmpty() -> EmptyState(
                title = "No matches",
                body = "Nothing in this view matches your search or filters",
                action = "Clear filters",
                onAction = { filters = LibraryFilters(); query = "" },
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                state = rememberReselectGridState(),
                contentPadding = PaddingValues(start = MokuSpacing.Sp4, end = MokuSpacing.Sp4, top = MokuSpacing.Sp2, bottom = MokuSpacing.Sp4),
                horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
                verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp4),
            ) {
                items(shown, key = { it.key }) { entry ->
                    LibraryCard(
                        entry = entry,
                        downloaded = entry.key in data.downloaded,
                        dimmed = offline && entry.key !in data.downloaded,
                        onClick = {
                            if (offline && entry.key !in data.downloaded) {
                                MokuToast.show("Not downloaded, and the server can't be reached")
                            } else {
                                onOpenMedia(entry.originId, entry.mediaId)
                            }
                        },
                        onLongClick = { menuFor = entry },
                    )
                }
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    when (sheet) {
        Sheet.SORT -> ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = sheetState, containerColor = colors.bgSurface) {
            SheetBody("Sort") {
                Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                    MokuChip("Descending", selected = descending) { sort = sortMode to true; LibraryPrefs.setSort(context, sortMode, true) }
                    MokuChip("Ascending", selected = !descending) { sort = sortMode to false; LibraryPrefs.setSort(context, sortMode, false) }
                }
                Column {
                    LibrarySort.entries.forEach { mode ->
                        SheetRow(mode.label, selected = mode == sortMode) {
                            sort = mode to descending
                            LibraryPrefs.setSort(context, mode, descending)
                        }
                    }
                }
            }
        }
        Sheet.FILTER -> ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = sheetState, containerColor = colors.bgSurface) {
            SheetBody("Filter", trailing = if (filters.active) ({ MokuChip("Reset", selected = false) { filters = LibraryFilters() } }) else null) {
                MokuSectionLabel("Progress")
                ChipRow {
                    Progress.entries.forEach { p ->
                        MokuChip(p.label, selected = filters.progress == p) {
                            filters = filters.copy(progress = if (filters.progress == p) null else p)
                        }
                    }
                    MokuChip("Downloaded", selected = filters.downloaded) { filters = filters.copy(downloaded = !filters.downloaded) }
                }
                MokuSectionLabel("Status")
                ChipRow {
                    MokuChip("Any", selected = filters.status == null) { filters = filters.copy(status = null) }
                    Status.entries.forEach { s ->
                        MokuChip(s.label, selected = filters.status == s) { filters = filters.copy(status = s) }
                    }
                }
            }
        }
        Sheet.FOLDERS -> ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = sheetState, containerColor = colors.bgSurface) {
            ManageFolders(
                folders = data.folders,
                counts = data.folders.associate { f -> f.id to data.items.count { it.key in data.folderMembers[f.id].orEmpty() } },
                onCreate = { name -> folders { repo.createFolder(name) } },
                onRename = { folder, name -> folders { repo.renameFolder(folder.id, name) } },
                onDelete = { folder -> folders { repo.deleteFolder(folder.id) } },
                onMove = { folder, delta -> folders { repo.moveFolder(folder.id, delta) } },
            )
        }
        null -> Unit
    }

    menuFor?.let { entry ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }, sheetState = sheetState, containerColor = colors.bgSurface) {
            EntrySheet(
                entry = entry,
                folders = data.folders,
                memberOf = data.folders.filter { entry.key in data.folderMembers[it.id].orEmpty() }.map { it.id }.toSet(),
                onToggleFolder = { folder, member ->
                    folders {
                        if (member) repo.removeFromFolder(entry.originId, entry.mediaId, folder.id)
                        else repo.addToFolder(entry.originId, entry.mediaId, folder.id)
                    }
                },
                onCreateFolder = { name -> folders { repo.createFolder(name)?.let { repo.addToFolder(entry.originId, entry.mediaId, it) } } },
                onRemove = {
                    scope.launch {
                        withContext(Dispatchers.IO) { repo.removeFromLibrary(entry.originId, entry.mediaId) }
                        menuFor = null
                        reload++
                    }
                },
            )
        }
    }
}

@Composable
private fun FolderEditChip(hasFolders: Boolean, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Md)
    Row(
        Modifier
            .height(32.dp)
            .pressable(shape, pressedScale = 0.94f, onClick = onClick)
            .border(1.dp, colors.borderDim, shape)
            .padding(horizontal = MokuSpacing.Sp3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painterResource(if (hasFolders) R.drawable.ph_pencil_simple_light else R.drawable.ph_folder_simple_plus_light),
            contentDescription = null,
            tint = colors.textMuted,
            modifier = Modifier.size(13.dp),
        )
        Text(if (hasFolders) "Edit" else "New folder", style = MokuText.meta, color = colors.textMuted, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryCard(entry: LibraryMediaEntity, downloaded: Boolean, dimmed: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MokuTheme.colors
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(MokuRadius.Md)
    Column(
        modifier = Modifier.alpha(if (dimmed) 0.4f else 1f).combinedClickable(
            onClick = onClick,
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            },
        ),
        verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(colors.bgRaised)
                .border(1.dp, colors.borderDim, shape),
        ) {
            if (entry.thumbnailUrl != null) {
                AsyncImage(
                    model = entry.thumbnailUrl,
                    contentDescription = entry.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    painterResource(R.drawable.ph_book_open_light),
                    contentDescription = null,
                    tint = colors.textFaint,
                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                )
            }
            if (downloaded) {
                val badge = RoundedCornerShape(MokuRadius.Sm)
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(badge)
                        .background(Color.Black.copy(alpha = 0.62f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), badge)
                        .padding(4.dp),
                ) {
                    Icon(painterResource(R.drawable.ph_download_simple_bold), contentDescription = "Downloaded", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(10.dp))
                }
            }
        }
        Text(
            entry.title,
            style = MokuText.caption,
            color = colors.textSecondary,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyState(title: String, body: String, action: String, onAction: () -> Unit) {
    val colors = MokuTheme.colors
    Column(
        Modifier.fillMaxSize().padding(MokuSpacing.Sp6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2, Alignment.CenterVertically),
    ) {
        Icon(painterResource(R.drawable.ph_books_light), contentDescription = null, tint = colors.textFaint, modifier = Modifier.size(28.dp))
        Text(title, style = MokuText.body, color = colors.textMuted)
        Text(body, style = MokuText.meta, color = colors.textFaint)
        Box(Modifier.padding(top = MokuSpacing.Sp2)) { MokuChip(action, selected = false, onClick = onAction) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) { content() }
}

@Composable
private fun EntrySheet(
    entry: LibraryMediaEntity,
    folders: List<FolderEntity>,
    memberOf: Set<Long>,
    onToggleFolder: (FolderEntity, Boolean) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MokuTheme.colors
    SheetBody(entry.title) {
        FolderPicker(folders, memberOf, onToggle = onToggleFolder, onCreate = onCreateFolder)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderDim))
        SheetRow("Remove from library", selected = false, icon = R.drawable.ph_trash_light, danger = true, onClick = onRemove)
    }
}
