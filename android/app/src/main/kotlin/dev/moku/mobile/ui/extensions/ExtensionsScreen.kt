package dev.moku.mobile.ui.extensions

import android.net.Uri
import dev.moku.mobile.ui.components.MokuToast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.moku.mobile.R
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.repository.AvailableExtensionEntity
import dev.moku.mobile.repository.InstalledExtensionEntity
import dev.moku.mobile.repository.LocalRepositoryManager
import dev.moku.mobile.repository.RepositoryEntity
import dev.moku.mobile.ui.components.ContentTypeFilterController
import dev.moku.mobile.ui.components.MokuChip
import dev.moku.mobile.ui.components.MokuHeaderAction
import dev.moku.mobile.ui.components.MokuSearchField
import dev.moku.mobile.ui.components.PushSwitcher
import dev.moku.mobile.ui.settings.ActionChip
import dev.moku.mobile.ui.settings.Danger
import dev.moku.mobile.ui.settings.GroupDivider
import dev.moku.mobile.ui.settings.IconTile
import dev.moku.mobile.ui.settings.MokuSwitch
import dev.moku.mobile.ui.settings.SettingsGroup
import dev.moku.mobile.ui.settings.SettingsPage
import dev.moku.mobile.ui.settings.SettingsRow
import dev.moku.mobile.ui.settings.TextInput
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.launch

private fun MediaContentType.label() = when (this) {
    MediaContentType.MANGA -> "Manga"
    MediaContentType.ANIME -> "Anime"
    MediaContentType.NOVEL -> "Novel"
}

private fun RepositoryEntity.title() = name ?: runCatching { Uri.parse(indexUrl).pathSegments.firstOrNull() ?: Uri.parse(indexUrl).host }.getOrNull() ?: indexUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MokuTheme.colors
    val manager = remember { LocalRepositoryManager(context) }

    var installed by remember { mutableStateOf(emptyList<InstalledExtensionEntity>()) }
    var repositories by remember { mutableStateOf(emptyList<RepositoryEntity>()) }
    var available by remember { mutableStateOf(emptyMap<String, AvailableExtensionEntity>()) }
    var openRepo by remember { mutableStateOf<RepositoryEntity?>(null) }
    var addingRepo by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var installingFile by remember { mutableStateOf(false) }
    var openExt by remember { mutableStateOf<ExtensionRef?>(null) }

    LaunchedEffect(reload) {
        repositories = manager.getRepositories()
        installed = manager.getInstalledExtensions()
        available = repositories.flatMap { manager.getAvailableExtensions(it.id) }.associateBy { it.packageName }
    }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        installingFile = true
        scope.launch {
            runCatching { manager.installFromFile(uri) }
                .onSuccess { MokuToast.show("${it.name} installed") }
                .onFailure { MokuToast.show(it.message ?: "Couldn't install that APK", long = true) }
            installingFile = false
            reload++
        }
    }

    BackHandler(enabled = openExt != null) { openExt = null; reload++ }
    BackHandler(enabled = openRepo != null && openExt == null) { openRepo = null; reload++ }

    val activeType = ContentTypeFilterController.current.mediaType
    val shownInstalled = installed.filter { activeType == null || it.contentType == activeType }.sortedBy { it.name.lowercase() }

    PushSwitcher(ExtensionsNav(openRepo, openExt), depth = { it.depth }) { nav ->
        when {
            nav.ext != null -> ExtensionDetailPage(
                nav.ext,
                repositories.firstOrNull { it.id == (nav.ext.installed?.repositoryId ?: nav.ext.available?.repositoryId) },
                manager,
                onBack = { openExt = null; reload++ },
                onChanged = { reload++ },
            )
            nav.repo != null -> RepoPage(nav.repo, manager, installed, onBack = { openRepo = null; reload++ }, onChanged = { reload++ }, onOpen = { openExt = it })
            else -> SettingsPage("Extensions", onBack = onBack, trailing = {
                MokuHeaderAction(R.drawable.ph_file_arrow_up_light, "Install APK", active = installingFile) {
                    apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
                }
                MokuHeaderAction(R.drawable.ph_plus_light, "Add repository") { addingRepo = true }
            }) {
                item {
                    SettingsGroup("Repositories") {
                        if (repositories.isEmpty()) {
                            Text("No repositories yet. Add one with +, or install an extension from a file.", style = MokuText.sub, color = colors.textFaint, modifier = Modifier.padding(MokuSpacing.Sp4))
                        }
                        repositories.forEachIndexed { i, repo ->
                            if (i > 0) GroupDivider()
                            val count = available.values.count { it.repositoryId == repo.id }
                            SettingsRow(
                                repo.title(),
                                icon = R.drawable.ph_package_light,
                                subtitle = "${repo.contentType.label()} · " + if (repo.lastSyncedAt == null) "Not synced yet" else "$count extensions",
                                chevron = true,
                            ) { openRepo = repo }
                        }
                    }
                }
                item {
                    SettingsGroup(
                        "Installed",
                        footer = if (installed.isNotEmpty() && shownInstalled.isEmpty()) "Nothing installed for this content mode." else null,
                    ) {
                        if (installed.isEmpty()) {
                            Text("Open a repository above, or install an APK from the top right.", style = MokuText.meta, color = colors.textFaint, modifier = Modifier.padding(MokuSpacing.Sp4))
                        }
                        shownInstalled.forEachIndexed { i, ext ->
                            if (i > 0) GroupDivider()
                            val latest = available[ext.packageName]
                            val update = latest != null && latest.versionName != ext.versionName
                            SettingsRow(
                                ext.name,
                                subtitle = listOf(ext.lang.uppercase(), "v${ext.versionName}", ext.contentType.label()).filter { it.isNotBlank() }.joinToString(" · "),
                                leading = { ExtensionIcon(latest?.iconUrl, ext.name) },
                                onClick = { openExt = ExtensionRef(ext.packageName, ext, latest, ext.contentType) },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                                        if (update) {
                                            ActionChip("Update", accent = true) {
                                                scope.launch {
                                                    runCatching { manager.installExtension(latest, ext.contentType) }
                                                        .onFailure { MokuToast.show(it.message ?: "Update failed") }
                                                    reload++
                                                }
                                            }
                                        }
                                        Box(Modifier.clip(RoundedCornerShape(MokuRadius.Lg)).clickable {
                                            scope.launch { manager.setEnabled(ext.packageName, !ext.enabled); reload++ }
                                        }) { MokuSwitch(ext.enabled) }
                                        Icon(
                                            painterResource(R.drawable.ph_trash_light),
                                            contentDescription = "Uninstall ${ext.name}",
                                            tint = colors.textMuted,
                                            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(MokuRadius.Md)).clickable {
                                                scope.launch {
                                                    manager.uninstallExtension(ext.packageName)
                                                    MokuToast.show("${ext.name} removed")
                                                    reload++
                                                }
                                            }.padding(7.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (addingRepo) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { addingRepo = false }, sheetState = sheetState, containerColor = colors.bgSurface) {
            AddRepoSheet(onAdd = { url, name, type ->
                val result = runCatching { manager.addRepository(url, name, type) }
                reload++
                result.fold(
                    onSuccess = { addingRepo = false; null },
                    onFailure = { "Added, but it didn't load: ${it.message ?: it.javaClass.simpleName}" },
                )
            })
        }
    }
}

@Composable
private fun ExtensionIcon(url: String?, name: String) {
    val colors = MokuTheme.colors
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(MokuRadius.Md)).background(colors.bgOverlay), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = MokuText.sub, color = colors.textMuted)
        if (url != null) AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun AddRepoSheet(onAdd: suspend (String, String?, MediaContentType) -> String?) {
    val colors = MokuTheme.colors
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(MediaContentType.MANGA) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = MokuSpacing.Sp4, end = MokuSpacing.Sp4, bottom = MokuSpacing.Sp6),
        verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
    ) {
        Text("Add repository", style = MokuText.heading, color = colors.textPrimary)
        Text("Paste a Mihon or Aniyomi repo link, or an LNReader plugins.min.json and pick Novel.", style = MokuText.meta, color = colors.textMuted)
        TextInput(url, { url = it; error = null }, "https://…/index.min.json", Modifier.fillMaxWidth(), mono = true)
        TextInput(name, { name = it }, "Name (optional)", Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
            MediaContentType.entries.forEach { t -> MokuChip(t.label(), selected = type == t) { type = t } }
        }
        if (error != null) Text(error!!, style = MokuText.meta, color = Danger)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
            ActionChip(if (busy) "Adding…" else "Add repository", icon = R.drawable.ph_plus_light, accent = url.isNotBlank()) {
                if (url.isBlank() || busy) return@ActionChip
                busy = true
                scope.launch {
                    error = onAdd(url.trim(), name.trim().ifBlank { null }, type)
                    busy = false
                }
            }
            if (busy) CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun RepoPage(
    repo: RepositoryEntity,
    manager: LocalRepositoryManager,
    installed: List<InstalledExtensionEntity>,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onOpen: (ExtensionRef) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MokuTheme.colors
    val installedEntities = installed
    var entries by remember { mutableStateOf(emptyList<AvailableExtensionEntity>()) }
    var syncing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(emptySet<String>()) }
    var installedPkgs by remember { mutableStateOf(installed.associate { it.packageName to it.versionName }) }
    var confirmRemove by remember { mutableStateOf(false) }

    suspend fun sync() {
        syncing = true
        error = null
        runCatching { manager.syncRepository(repo) }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
        entries = manager.getAvailableExtensions(repo.id)
        syncing = false
    }

    LaunchedEffect(repo.id) {
        entries = manager.getAvailableExtensions(repo.id)
        if (entries.isEmpty() || repo.lastSyncedAt == null) sync()
    }

    val shown = entries.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.lang.equals(query.trim(), ignoreCase = true) }

    SettingsPage(repo.title(), onBack = onBack, trailing = {
        MokuHeaderAction(R.drawable.ph_arrows_clockwise_light, "Refresh", active = syncing) { scope.launch { sync() } }
        MokuHeaderAction(R.drawable.ph_trash_light, if (confirmRemove) "Confirm remove" else "Remove repository", active = confirmRemove) {
            if (confirmRemove) scope.launch { manager.removeRepository(repo.id); onBack() } else confirmRemove = true
        }
    }) {
        item { MokuSearchField(query, { query = it }, "Search ${entries.size} extensions") }
        if (confirmRemove) {
            item { Text("Tap the trash button again to remove this repository.", style = MokuText.meta, color = Danger) }
        }
        when {
            syncing && entries.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().padding(MokuSpacing.Sp6), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }
            error != null && entries.isEmpty() -> item {
                SettingsGroup {
                    SettingsRow("Couldn't load this repository", icon = R.drawable.ph_x_light, subtitle = error, danger = true)
                    GroupDivider()
                    SettingsRow("Try again", icon = R.drawable.ph_arrows_clockwise_light) { scope.launch { sync() } }
                }
            }
            shown.isEmpty() -> item { Text("No matches", style = MokuText.sub, color = colors.textFaint, modifier = Modifier.padding(MokuSpacing.Sp2)) }
            else -> item {
                SettingsGroup(footer = repo.indexUrl) {
                    shown.forEachIndexed { i, ext ->
                        if (i > 0) GroupDivider()
                        val have = installedPkgs[ext.packageName]
                        SettingsRow(
                            ext.name,
                            subtitle = "${ext.lang.uppercase()} · v${ext.versionName}" + if (ext.isNsfw) " · 18+" else "",
                            leading = { ExtensionIcon(ext.iconUrl, ext.name) },
                            onClick = { onOpen(ExtensionRef(ext.packageName, installedEntities.firstOrNull { it.packageName == ext.packageName }, ext, repo.contentType)) },
                            trailing = {
                                when {
                                    ext.packageName in busy -> CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                    have == ext.versionName -> Text("Installed", style = MokuText.meta, color = colors.textFaint)
                                    else -> ActionChip(if (have != null) "Update" else "Install", accent = true) {
                                        busy = busy + ext.packageName
                                        scope.launch {
                                            runCatching { manager.installExtension(ext, repo.contentType) }
                                                .onSuccess { installedPkgs = installedPkgs + (ext.packageName to ext.versionName); onChanged() }
                                                .onFailure { MokuToast.show(it.message ?: "Install failed") }
                                            busy = busy - ext.packageName
                                        }
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

private data class ExtensionsNav(val repo: RepositoryEntity?, val ext: ExtensionRef?) {
    val depth: Int get() = (if (repo != null) 1 else 0) + (if (ext != null) 1 else 0)
}
