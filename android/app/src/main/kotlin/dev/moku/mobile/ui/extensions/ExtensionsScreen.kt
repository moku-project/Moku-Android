package dev.moku.mobile.ui.extensions

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.repository.AvailableExtensionEntity
import dev.moku.mobile.repository.InstalledExtensionEntity
import dev.moku.mobile.repository.LocalRepositoryManager
import dev.moku.mobile.repository.RepositoryEntity
import dev.moku.mobile.ui.components.ContentTypeFilterController
import dev.moku.mobile.ui.components.ContentTypeSwitch
import dev.moku.mobile.ui.components.MokuTopBar
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.launch

private fun MediaContentType.label() = when (this) {
    MediaContentType.MANGA -> "Manga"
    MediaContentType.ANIME -> "Anime"
    MediaContentType.NOVEL -> "Novel"
}

/**
 * Extension + repository management — real data from [LocalRepositoryManager], the same store
 * the desktop-parity extension pipeline (installer/loader) already writes to. Two sections:
 * what's installed on-device right now, and the repositories you can browse/install more from.
 */
@Composable
fun ExtensionsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MokuTheme.colors
    val manager = remember { LocalRepositoryManager(context) }

    var installed by remember { mutableStateOf(emptyList<InstalledExtensionEntity>()) }
    var repositories by remember { mutableStateOf(emptyList<RepositoryEntity>()) }
    var availableByRepo by remember { mutableStateOf(emptyMap<Long, List<AvailableExtensionEntity>>()) }
    var expandedRepoId by remember { mutableStateOf<Long?>(null) }
    var showAddRepo by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    suspend fun reload() {
        installed = manager.getInstalledExtensions()
        repositories = manager.getRepositories()
    }

    LaunchedEffect(Unit) {
        loading = true
        // First run: seed the two real community repos so there's something to browse without
        // hand-typing a URL.
        if (manager.getRepositories().isEmpty()) {
            manager.addRepository("https://raw.githubusercontent.com/keiyoushi/extensions/repo/", "Keiyoushi", MediaContentType.MANGA)
            manager.addRepository("https://raw.githubusercontent.com/aniyomiorg/aniyomi-extensions/repo/", "Aniyomi", MediaContentType.ANIME)
        }
        reload()
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        LazyColumn(contentPadding = PaddingValues(bottom = MokuSpacing.Sp8)) {
            item { MokuTopBar(title = "Extensions", onBack = onBack, trailing = { ContentTypeSwitch() }) }

            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(MokuSpacing.Sp6), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                }
            } else {
                val activeType = ContentTypeFilterController.current.mediaType
                val visibleInstalled = installed.filter { activeType == null || it.contentType == activeType }
                val visibleRepositories = repositories.filter { activeType == null || it.contentType == activeType }

                item { SectionLabel("Installed") }
                if (visibleInstalled.isEmpty()) {
                    item {
                        Text(
                            if (installed.isEmpty()) "Nothing installed yet — add a repository below to browse extensions." else "Nothing installed for this content type.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                            modifier = Modifier.padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp2),
                        )
                    }
                }
                items(visibleInstalled, key = { it.packageName }) { ext ->
                    InstalledExtensionRow(
                        ext = ext,
                        onToggle = { enabled ->
                            scope.launch { manager.setEnabled(ext.packageName, enabled); reload() }
                        },
                        onUninstall = {
                            scope.launch {
                                manager.uninstallExtension(ext.packageName)
                                reload()
                                Toast.makeText(context, "${ext.name} removed", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }

                item { SectionLabel("Repositories") }
                items(visibleRepositories, key = { it.id }) { repo ->
                    Column {
                        RepositoryRow(
                            repo = repo,
                            expanded = expandedRepoId == repo.id,
                            onClick = {
                                scope.launch {
                                    if (expandedRepoId == repo.id) {
                                        expandedRepoId = null
                                    } else {
                                        val synced = manager.syncRepository(repo)
                                        availableByRepo = availableByRepo + (repo.id to manager.getAvailableExtensions(synced.id))
                                        expandedRepoId = repo.id
                                    }
                                }
                            },
                            onRemove = {
                                scope.launch { manager.removeRepository(repo.id); reload() }
                            },
                        )
                        if (expandedRepoId == repo.id) {
                            val available = availableByRepo[repo.id].orEmpty()
                            val installedPkgs = installed.map { it.packageName }.toSet()
                            if (available.isEmpty()) {
                                Text(
                                    "No extensions found in this repository.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                    modifier = Modifier.padding(horizontal = MokuSpacing.Sp6, vertical = MokuSpacing.Sp2),
                                )
                            }
                            available.forEach { ext ->
                                AvailableExtensionRow(
                                    ext = ext,
                                    alreadyInstalled = ext.packageName in installedPkgs,
                                    onInstall = {
                                        scope.launch {
                                            manager.installExtension(ext, repo.contentType)
                                            reload()
                                            Toast.makeText(context, "${ext.name} installed", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                item {
                    if (showAddRepo) {
                        AddRepositoryForm(
                            onCancel = { showAddRepo = false },
                            onAdd = { url, name, type ->
                                scope.launch {
                                    manager.addRepository(url, name.ifBlank { null }, type)
                                    reload()
                                    showAddRepo = false
                                }
                            },
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp3)
                                .clip(RoundedCornerShape(MokuRadius.Lg))
                                .background(colors.bgRaised)
                                .clickable { showAddRepo = true }
                                .padding(MokuSpacing.Sp3),
                            horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = colors.accentBright)
                            Text("Add repository", style = MaterialTheme.typography.bodyLarge, color = colors.accentBright)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledExtensionRow(ext: InstalledExtensionEntity, onToggle: (Boolean) -> Unit, onUninstall: () -> Unit) {
    val colors = MokuTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(MokuRadius.Md))
                .background(colors.accentDim)
                .padding(horizontal = MokuSpacing.Sp2, vertical = 4.dp),
        ) {
            Text(ext.contentType.label().uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.accentFg)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(ext.name, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("v${ext.versionName} · ${ext.lang}", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        Switch(
            checked = ext.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.bgVoid),
        )
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
                .clip(RoundedCornerShape(MokuRadius.Md))
                .clickable(onClick = onUninstall),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Delete, contentDescription = "Uninstall", tint = colors.textFaint)
        }
    }
}

@Composable
private fun RepositoryRow(repo: RepositoryEntity, expanded: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val colors = MokuTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(repo.name ?: repo.indexUrl, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${repo.contentType.label()} · ${if (expanded) "tap to collapse" else "tap to browse"}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
                .clip(RoundedCornerShape(MokuRadius.Md))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove repository", tint = colors.textFaint)
        }
    }
}

@Composable
private fun AvailableExtensionRow(ext: AvailableExtensionEntity, alreadyInstalled: Boolean, onInstall: () -> Unit) {
    val colors = MokuTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MokuSpacing.Sp6, vertical = MokuSpacing.Sp2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(ext.name, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("v${ext.versionName} · ${ext.lang}", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        if (alreadyInstalled) {
            Text("Installed", style = MaterialTheme.typography.labelLarge, color = colors.textFaint)
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MokuRadius.Md))
                    .background(colors.accent)
                    .clickable(onClick = onInstall)
                    .padding(horizontal = MokuSpacing.Sp3, vertical = MokuSpacing.Sp2),
            ) {
                Text("Install", style = MaterialTheme.typography.labelLarge, color = colors.bgVoid)
            }
        }
    }
}

@Composable
private fun AddRepositoryForm(onCancel: () -> Unit, onAdd: (url: String, name: String, type: MediaContentType) -> Unit) {
    val colors = MokuTheme.colors
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(MediaContentType.MANGA) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp2)
            .clip(RoundedCornerShape(MokuRadius.Lg))
            .background(colors.bgRaised)
            .padding(MokuSpacing.Sp4),
        verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
    ) {
        Text("New repository", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            placeholder = { Text("Repo index URL", color = colors.textFaint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(MokuRadius.Md),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.borderBase,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                cursorColor = colors.accent,
            ),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("Display name (optional)", color = colors.textFaint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(MokuRadius.Md),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.borderBase,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                cursorColor = colors.accent,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
            MediaContentType.values().forEach { candidate ->
                val selected = candidate == type
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(MokuRadius.Md))
                        .background(if (selected) colors.accent else colors.bgSubtle)
                        .clickable { type = candidate }
                        .padding(horizontal = MokuSpacing.Sp3, vertical = MokuSpacing.Sp2),
                ) {
                    Text(candidate.label(), style = MaterialTheme.typography.labelLarge, color = if (selected) colors.bgVoid else colors.textSecondary)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MokuRadius.Md))
                    .background(colors.bgSubtle)
                    .clickable(onClick = onCancel)
                    .padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp2),
            ) { Text("Cancel", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary) }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MokuRadius.Md))
                    .background(if (url.isNotBlank()) colors.accent else colors.bgSubtle)
                    .clickable(enabled = url.isNotBlank()) { onAdd(url.trim(), name.trim(), type) }
                    .padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp2),
            ) { Text("Add", style = MaterialTheme.typography.labelLarge, color = if (url.isNotBlank()) colors.bgVoid else colors.textFaint) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MokuTheme.colors.textFaint,
        modifier = Modifier.padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp2),
    )
}
