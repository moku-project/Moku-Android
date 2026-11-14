package dev.moku.mobile.ui.settings

import dev.moku.mobile.ui.components.pressable
import android.content.Intent
import android.net.Uri
import dev.moku.mobile.ui.components.MokuToast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.moku.mobile.update.AppUpdater
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import dev.moku.mobile.BuildConfig
import dev.moku.mobile.core.CrashReports
import dev.moku.mobile.R
import dev.moku.mobile.remote.ServerSession
import dev.moku.mobile.library.ContentBlockLevel
import dev.moku.mobile.library.ContentFilterDao
import dev.moku.mobile.library.ContentFilterRule
import dev.moku.mobile.library.FilterField
import dev.moku.mobile.library.LocalBackupManager
import dev.moku.mobile.library.LocalLibraryDatabase
import dev.moku.mobile.library.LocalStorageInfo
import dev.moku.mobile.library.LocalStorageManager
import dev.moku.mobile.remote.TrackerAuthLauncher
import dev.moku.mobile.tracking.LocalTrackingRepository
import dev.moku.mobile.tracking.TrackerInfo
import dev.moku.mobile.ui.components.MokuChip
import dev.moku.mobile.ui.components.PushSwitcher
import dev.moku.mobile.ui.components.MokuSectionLabel
import dev.moku.mobile.ui.search.ContentFilterController
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme
import dev.moku.mobile.ui.theme.ThemeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LEVELS = listOf("Off", "Moderate", "Strict")
private val LEVEL_HINTS = listOf(
    "Nothing is hidden.",
    "Hides titles that match rules marked Moderate.",
    "Hides titles that match any rule.",
)

private sealed interface Page {
    data object Root : Page
    data object Appearance : Page
    data class ThemeEditor(val id: String?) : Page
    data object Content : Page
    data object Trackers : Page
    data object Server : Page
    data object Storage : Page
    data object Backup : Page
    data object About : Page
}

@Composable
fun SettingsScreen(onOpenExtensions: () -> Unit = {}) {
    var page by remember { mutableStateOf<Page>(Page.Root) }
    BackHandler(enabled = page != Page.Root) {
        page = if (page is Page.ThemeEditor) Page.Appearance else Page.Root
    }
    val toRoot = { page = Page.Root }
    PushSwitcher(page, depth = { it.depth }) { p ->
        when (p) {
            Page.Root -> SettingsRoot(onOpen = { page = it }, onOpenExtensions = onOpenExtensions)
            Page.Appearance -> AppearancePage(onBack = toRoot, onEditTheme = { page = Page.ThemeEditor(it) })
            is Page.ThemeEditor -> ThemeEditorPage(p.id, onBack = { page = Page.Appearance })
            Page.Content -> ContentFilterPage(onBack = toRoot)
            Page.Trackers -> TrackersPage(onBack = toRoot)
            Page.Server -> ServerPage(onBack = toRoot)
            Page.Storage -> StoragePage(onBack = toRoot)
            Page.Backup -> BackupPage(onBack = toRoot)
            Page.About -> AboutPage(onBack = toRoot)
        }
    }
}

private val Page.depth: Int
    get() = when (this) {
        Page.Root -> 0
        is Page.ThemeEditor -> 2
        else -> 1
    }

@Composable
private fun SettingsRoot(onOpen: (Page) -> Unit, onOpenExtensions: () -> Unit) {
    val context = LocalContext.current
    val filterDao = remember { ContentFilterDao(LocalLibraryDatabase.get(context)) }
    var ruleCount by remember { mutableStateOf(0) }
    var trackers by remember { mutableStateOf(emptyList<TrackerInfo>()) }
    var usedBytes by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
            ContentFilterController.init(context)
            ruleCount = filterDao.getAll().size
            trackers = LocalTrackingRepository(context).getTrackers()
            usedBytes = withContext(Dispatchers.IO) { LocalStorageManager(context).storageInfo().usedBytes }
    }

    SettingsPage("Settings") {
            item { UpdateAvailableRow { onOpen(Page.About) } }
            item { CrashReportRow() }
            item { CurrentThemeCard { onOpen(Page.Appearance) } }
            item {
                SettingsGroup("Preferences") {
                    SettingsRow("Appearance", icon = R.drawable.ph_paint_brush_light, value = ThemeController.nameFor(ThemeController.selectedId), chevron = true) { onOpen(Page.Appearance) }
                    GroupDivider()
                    SettingsRow(
                        "Content filter",
                        icon = R.drawable.ph_shield_check_light,
                        value = "${LEVELS[ContentFilterController.level]} · $ruleCount",
                        chevron = true,
                    ) { onOpen(Page.Content) }
                }
            }
            item {
                SettingsGroup("Sources") {
                    val server = ServerSession.current
                    SettingsRow(
                        "Server",
                        icon = if (server != null) R.drawable.ph_cloud_check_light else R.drawable.ph_cloud_slash_light,
                        subtitle = server?.baseUrl ?: "Sync with Tsunagu on your computer",
                        value = if (server != null) (if (ServerSession.active) "On" else "Off") else null,
                        chevron = true,
                    ) { onOpen(Page.Server) }
                    GroupDivider()
                    SettingsRow("Extensions", icon = R.drawable.ph_puzzle_piece_light, subtitle = "Repositories and installed sources", chevron = true, onClick = onOpenExtensions)
                    GroupDivider()
                    val connected = trackers.count { it.isLoggedIn }
                    SettingsRow("Trackers", icon = R.drawable.ph_broadcast_light, value = if (connected > 0) "$connected connected" else "None", chevron = true) { onOpen(Page.Trackers) }
                }
            }
            item {
                SettingsGroup("Data") {
                    SettingsRow("Storage", icon = R.drawable.ph_hard_drives_light, value = usedBytes?.let(::formatBytes), chevron = true) { onOpen(Page.Storage) }
                    GroupDivider()
                    SettingsRow("Backup & restore", icon = R.drawable.ph_archive_light, chevron = true) { onOpen(Page.Backup) }
                }
            }
            item {
                SettingsGroup {
                    val update by AppUpdater.available.collectAsState()
                    SettingsRow("About Moku", icon = R.drawable.ph_info_light, value = if (update != null) "Update available" else BuildConfig.VERSION_NAME, chevron = true) { onOpen(Page.About) }
                }
            }
    }
}

@Composable
private fun CurrentThemeCard(onClick: () -> Unit) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Xl)
    Row(
            Modifier
                .fillMaxWidth()
                .pressable(shape, onClick = onClick)
                .background(colors.bgSurface)
                .border(1.dp, colors.borderDim, shape)
                .padding(MokuSpacing.Sp3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
    ) {
            ThemePreview(
                colors,
                Modifier.width(92.dp).height(70.dp).clip(RoundedCornerShape(MokuRadius.Lg)).border(1.dp, colors.borderDim, RoundedCornerShape(MokuRadius.Lg)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                MokuSectionLabel("Theme")
                Text(ThemeController.nameFor(ThemeController.selectedId), style = MokuText.heading, color = colors.textPrimary)
                Text(if (ThemeController.followSystem) "Matches system" else "Tap to change or create your own", style = MokuText.meta, color = colors.textMuted)
            }
            Icon(painterResource(R.drawable.ph_caret_right_bold), contentDescription = null, tint = colors.textFaint, modifier = Modifier.size(12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContentFilterPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MokuTheme.colors
    val dao = remember { ContentFilterDao(LocalLibraryDatabase.get(context)) }
    var rules by remember { mutableStateOf(emptyList<ContentFilterRule>()) }
    var keyword by remember { mutableStateOf("") }
    var field by remember { mutableStateOf(FilterField.GENRE) }
    var level by remember { mutableStateOf(ContentBlockLevel.MODERATE) }

    LaunchedEffect(Unit) { rules = dao.getAll() }

    SettingsPage("Content filter", onBack = onBack) {
            item {
                SettingsGroup("Blocking", footer = LEVEL_HINTS[ContentFilterController.level]) {
                    Row(Modifier.padding(MokuSpacing.Sp3), horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                        LEVELS.forEachIndexed { i, label ->
                            MokuChip(label, selected = ContentFilterController.level == i, modifier = Modifier.weight(1f)) {
                                ContentFilterController.setLevel(context, i)
                            }
                        }
                    }
                }
            }
            item {
                SettingsGroup("Add rule") {
                    Column(Modifier.padding(MokuSpacing.Sp3), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2), verticalAlignment = Alignment.CenterVertically) {
                            TextInput(keyword, { keyword = it }, "Keyword or genre", Modifier.weight(1f))
                            ActionChip("Add", icon = R.drawable.ph_plus_light, accent = keyword.isNotBlank()) {
                                if (keyword.isBlank()) return@ActionChip
                                val rule = ContentFilterRule(category = "custom", field = field, keyword = keyword.trim().lowercase(), blockLevel = level)
                                keyword = ""
                                scope.launch { dao.add(rule); rules = dao.getAll() }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                            FilterField.entries.filter { it != FilterField.TAG }.forEach { f ->
                                MokuChip(f.name.lowercase().replaceFirstChar(Char::uppercase), selected = field == f) { field = f }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                            ContentBlockLevel.entries.forEach { l ->
                                MokuChip(l.name.lowercase().replaceFirstChar(Char::uppercase), selected = level == l) { level = l }
                            }
                        }
                    }
                }
            }
            item {
                SettingsGroup("Rules") {
                    if (rules.isEmpty()) {
                        Text("No rules yet", style = MokuText.sub, color = colors.textFaint, modifier = Modifier.padding(MokuSpacing.Sp4))
                    }
                    rules.forEachIndexed { i, rule ->
                        if (i > 0) GroupDivider()
                        SettingsRow(
                            rule.keyword,
                            icon = R.drawable.ph_shield_check_light,
                            subtitle = "${rule.field.name.lowercase()} · ${rule.blockLevel.name.lowercase()}",
                            trailing = {
                                Icon(
                                    painterResource(R.drawable.ph_x_light),
                                    contentDescription = "Remove",
                                    tint = colors.textMuted,
                                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(MokuRadius.Md)).clickable {
                                        scope.launch { dao.remove(rule.id); rules = dao.getAll() }
                                    }.padding(7.dp),
                                )
                            },
                        )
                    }
                }
            }
    }
}

@Composable
private fun TrackersPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MokuTheme.colors
    val repo = remember { LocalTrackingRepository(context) }
    var trackers by remember { mutableStateOf(repo.getTrackers()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) trackers = repo.getTrackers() }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
    }

    SettingsPage("Trackers", onBack = onBack) {
            item {
                SettingsGroup(footer = "Connected trackers sync your reading progress as you go.") {
                    trackers.forEachIndexed { i, tracker ->
                        if (i > 0) GroupDivider()
                        SettingsRow(
                            tracker.name,
                            subtitle = if (tracker.isLoggedIn) "Connected as ${tracker.username}" else "Not connected",
                            leading = {
                                AsyncImage(
                                    model = trackerIcon(tracker.key) ?: tracker.iconUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(MokuRadius.Md)).background(colors.bgOverlay),
                                )
                            },
                            trailing = {
                                if (tracker.isLoggedIn) {
                                    ActionChip("Disconnect") { repo.logout(tracker.key); trackers = repo.getTrackers() }
                                } else {
                                    ActionChip("Connect", accent = true) { TrackerAuthLauncher.launch(context, tracker.authUrl) }
                                }
                            },
                        )
                    }
                }
            }
    }
}

@Composable
private fun StoragePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MokuTheme.colors
    val manager = remember { LocalStorageManager(context) }
    var info by remember { mutableStateOf<LocalStorageInfo?>(null) }
    var confirming by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { info = manager.storageInfo() }

    SettingsPage("Storage", onBack = onBack) {
            info?.let { storage ->
                item {
                    val shape = RoundedCornerShape(MokuRadius.Xl)
                    Column(
                        Modifier.fillMaxWidth().clip(shape).background(colors.bgSurface).border(1.dp, colors.borderDim, shape).padding(MokuSpacing.Sp4),
                        verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
                    ) {
                        MokuSectionLabel("Used by Moku")
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                            Text(formatBytes(storage.usedBytes), style = MokuText.bigValue, color = colors.textPrimary)
                            Text("${formatBytes(storage.freeBytes)} free", style = MokuText.meta, color = colors.textFaint, modifier = Modifier.padding(bottom = 3.dp))
                        }
                        val fraction = if (storage.totalBytes > 0) (storage.usedBytes.toFloat() / storage.totalBytes).coerceIn(0.01f, 1f) else 0f
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(MokuRadius.Sm)).background(colors.bgOverlay)) {
                            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(colors.accent))
                        }
                    }
                }
                item {
                    SettingsGroup("Categories", footer = "Clearing a category frees space without touching your library.") {
                        storage.categories.forEachIndexed { i, category ->
                            if (i > 0) GroupDivider()
                            val confirm = confirming == category.key
                            SettingsRow(
                                category.label,
                                icon = R.drawable.ph_hard_drives_light,
                                subtitle = "${formatBytes(category.bytes)} · ${category.fileCount} files",
                                trailing = {
                                    ActionChip(if (confirm) "Confirm" else "Clear", danger = confirm) {
                                        if (!confirm) confirming = category.key
                                        else scope.launch {
                                            manager.clearCategory(category.key)
                                            confirming = null
                                            info = manager.storageInfo()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
    }
}

@Composable
private fun BackupPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmRestore by remember { mutableStateOf(false) }
    val backup = remember { LocalBackupManager(context) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
            if (uri != null) scope.launch {
                val bytes = backup.exportTo(uri)
                MokuToast.show("Backup saved (${formatBytes(bytes)})")
            }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            confirmRestore = false
            if (uri != null) scope.launch {
                backup.importFrom(uri)
                MokuToast.show("Library restored")
            }
    }

    SettingsPage("Backup & restore", onBack = onBack) {
            item {
                SettingsGroup(footer = "Backups hold your library, folders, reading progress and filters.") {
                    SettingsRow("Create backup", icon = R.drawable.ph_upload_simple_light, subtitle = "Save a copy to a file you choose", chevron = true) {
                        exportLauncher.launch("moku_backup.db")
                    }
                    GroupDivider()
                    SettingsRow(
                        if (confirmRestore) "Pick a backup to restore" else "Restore backup",
                        icon = R.drawable.ph_download_simple_light,
                        subtitle = if (confirmRestore) "Your current library will be replaced" else "Replace your library with a backup",
                        danger = confirmRestore,
                        chevron = true,
                    ) {
                        if (confirmRestore) importLauncher.launch(arrayOf("*/*")) else confirmRestore = true
                    }
                }
            }
    }
}

@Composable
private fun CrashReportRow() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashReports.last()) }
    if (report == null) return
    SettingsGroup("Last crash", footer = "The report holds the app version, the phone model and where Moku failed. It only leaves the phone if you share it.") {
        SettingsRow("Share crash report", icon = R.drawable.ph_warning_circle_light, subtitle = "Moku closed unexpectedly", chevron = true) {
            CrashReports.share(context)
        }
        GroupDivider()
        SettingsRow("Dismiss", icon = R.drawable.ph_x_light) {
            CrashReports.clear()
            report = null
        }
    }
}

@Composable
private fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MokuTheme.colors
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    SettingsPage("About", onBack = onBack) {
            item {
                val shape = RoundedCornerShape(MokuRadius.Xl)
                Column(
                    Modifier.fillMaxWidth().clip(shape).background(colors.bgSurface).border(1.dp, colors.borderDim, shape).padding(MokuSpacing.Sp5),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp1),
                ) {
                    Text("Moku", style = MokuText.title, color = colors.textPrimary)
                    Text("v${BuildConfig.VERSION_NAME}", style = MokuText.sub, color = colors.textMuted)
                    Text("Manga, novels and anime, with Tsunagu running on-device", style = MokuText.meta, color = colors.textFaint, modifier = Modifier.padding(top = MokuSpacing.Sp2))
                }
            }
            updatesGroup()
            item {
                SettingsGroup {
                    SettingsRow("Source code", icon = R.drawable.ph_github_logo_light, subtitle = "moku-project/Moku-Android", chevron = true) {
                        open("https://github.com/moku-project/Moku-Android")
                    }
                    GroupDivider()
                    SettingsRow("Discord", icon = R.drawable.ph_discord_logo_light, subtitle = "Get help and share feedback", chevron = true) {
                        open("https://discord.gg/x97hj8zR72")
                    }
                }
        }
    }
}

private fun trackerIcon(key: String): Int? = when (key) {
    "anilist" -> R.drawable.tracker_anilist
    "mal" -> R.drawable.tracker_mal
    else -> null
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.1f GB".format(gb)
        mb >= 1 -> "%.1f MB".format(mb)
        kb >= 1 -> "%.0f KB".format(kb)
        else -> "$bytes B"
    }
}
