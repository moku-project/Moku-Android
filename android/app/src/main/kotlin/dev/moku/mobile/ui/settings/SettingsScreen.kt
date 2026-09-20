package dev.moku.mobile.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import dev.moku.mobile.library.ContentFilterDao
import dev.moku.mobile.library.ContentFilterRule
import dev.moku.mobile.library.LocalBackupManager
import dev.moku.mobile.library.LocalLibraryDatabase
import dev.moku.mobile.library.LocalStorageManager
import dev.moku.mobile.library.StorageCategoryInfo
import dev.moku.mobile.remote.TrackerAuthLauncher
import dev.moku.mobile.tracking.LocalTrackingRepository
import dev.moku.mobile.tracking.TrackerInfo
import dev.moku.mobile.ui.components.MokuTopBar
import dev.moku.mobile.ui.components.SettingsRow
import dev.moku.mobile.ui.components.ThemeSwatch
import dev.moku.mobile.ui.search.ContentFilterController
import dev.moku.mobile.ui.search.ContentFilterSheet
import dev.moku.mobile.ui.theme.MokuPalettes
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuTheme
import dev.moku.mobile.ui.theme.MokuThemeId
import dev.moku.mobile.ui.theme.ThemeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LEVEL_NAMES = listOf("Off", "Moderate", "Strict")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenExtensions: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MokuTheme.colors

    var trackers by remember { mutableStateOf(emptyList<TrackerInfo>()) }
    var storageCategories by remember { mutableStateOf(emptyList<StorageCategoryInfo>()) }
    var usedBytes by remember { mutableStateOf(0L) }
    val filterDao = remember { ContentFilterDao(LocalLibraryDatabase.get(context)) }
    var filterRules by remember { mutableStateOf(emptyList<ContentFilterRule>()) }
    var showContentFilterSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    fun reloadTrackers() { trackers = LocalTrackingRepository(context).getTrackers() }
    suspend fun reloadStorage() {
        val info = withContext(Dispatchers.IO) { LocalStorageManager(context).storageInfo() }
        storageCategories = info.categories
        usedBytes = info.usedBytes
    }

    LaunchedEffect(Unit) {
        ContentFilterController.init(context)
        reloadTrackers()
        reloadStorage()
        filterRules = filterDao.getAll()
    }

    if (showContentFilterSheet) {
        ContentFilterSheet(
            sheetState = sheetState,
            rules = filterRules,
            onDismiss = { showContentFilterSheet = false },
            onLevelChange = { newLevel -> ContentFilterController.setLevel(context, newLevel) },
            onAddRule = { field, keyword, level ->
                scope.launch {
                    filterDao.add(ContentFilterRule(category = "custom", field = field, keyword = keyword.lowercase(), blockLevel = level))
                    filterRules = filterDao.getAll()
                }
            },
            onRemoveRule = { id ->
                scope.launch {
                    filterDao.remove(id)
                    filterRules = filterDao.getAll()
                }
            },
        )
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { LocalBackupManager(context).exportTo(uri) }
            Toast.makeText(context, "Backed up $bytes bytes", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { LocalBackupManager(context).importFrom(uri) }
            reloadStorage()
            Toast.makeText(context, "Library restored", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        LazyColumn(
            contentPadding = PaddingValues(vertical = MokuSpacing.Sp2),
            verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp5),
        ) {
            item { MokuTopBar(title = "Settings") }

            item {
                SettingsGroup("Library") {
                    SettingsRow(
                        title = "Extensions",
                        subtitle = "Manage repositories and installed sources",
                        onClick = onOpenExtensions,
                    )
                    SettingsRow(
                        title = "Content filter",
                        subtitle = "${LEVEL_NAMES[ContentFilterController.level]} · ${filterRules.size} rule${if (filterRules.size == 1) "" else "s"}",
                        onClick = { showContentFilterSheet = true },
                    )
                }
            }

            item {
                SettingsGroup("Appearance") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = MokuSpacing.Sp3, vertical = MokuSpacing.Sp3),
                        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
                    ) {
                        MokuThemeId.values().forEach { id ->
                            val palette = MokuPalettes.of(id)
                            ThemeSwatch(
                                name = id.label,
                                bgColor = palette.bgBase,
                                accentColor = palette.accent,
                                textColor = palette.bgVoid,
                                selected = ThemeController.current == id,
                                onClick = { ThemeController.setTheme(context, id) },
                            )
                        }
                    }
                }
            }

            if (trackers.isNotEmpty()) {
                item {
                    SettingsGroup("Trackers") {
                        trackers.forEach { tracker ->
                            SettingsRow(
                                title = tracker.name,
                                subtitle = if (tracker.isLoggedIn) "Connected as ${tracker.username}" else "Not connected",
                                onClick = {
                                    if (tracker.isLoggedIn) {
                                        LocalTrackingRepository(context).logout(tracker.key)
                                        reloadTrackers()
                                    } else {
                                        TrackerAuthLauncher.launch(context, tracker.authUrl)
                                    }
                                },
                                trailing = {
                                    Text(
                                        if (tracker.isLoggedIn) "Disconnect" else "Connect",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (tracker.isLoggedIn) colors.textMuted else colors.accentBright,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            item {
                SettingsGroup("Storage") {
                    storageCategories.forEach { category ->
                        SettingsRow(
                            title = category.label,
                            subtitle = "${category.fileCount} files",
                            trailing = { Text(formatBytes(category.bytes), style = MaterialTheme.typography.labelLarge, color = colors.textMuted) },
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { LocalStorageManager(context).clearCategory(category.key) }
                                    reloadStorage()
                                }
                            },
                        )
                    }
                    SettingsRow(title = "Total used", subtitle = "Tap a category above to clear it", trailing = {
                        Text(formatBytes(usedBytes), style = MaterialTheme.typography.labelLarge, color = colors.accentBright)
                    })
                }
            }

            item {
                SettingsGroup("Backup") {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = MokuSpacing.Sp1, vertical = MokuSpacing.Sp1)) {
                        TextButton(onClick = { exportLauncher.launch("moku_backup.db") }) { Text("Export library") }
                        TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Restore library") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MokuTheme.colors
    Column(
        modifier = Modifier.padding(horizontal = MokuSpacing.Sp4),
        verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = colors.textMuted, modifier = Modifier.padding(horizontal = MokuSpacing.Sp2))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MokuRadius.Lg))
                .background(colors.bgSurface),
            content = content,
        )
    }
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1 -> "%.1f MB".format(mb)
        kb >= 1 -> "%.0f KB".format(kb)
        else -> "$bytes B"
    }
}
