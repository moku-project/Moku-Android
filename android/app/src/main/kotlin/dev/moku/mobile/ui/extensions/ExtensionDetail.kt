package dev.moku.mobile.ui.extensions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.moku.mobile.R
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.extension.ExtensionInstaller
import dev.moku.mobile.extension.ExtensionLoader
import dev.moku.mobile.extension.RuntimeBootstrap
import dev.moku.mobile.library.DownloadStatus
import dev.moku.mobile.library.LocalLibraryRepository
import dev.moku.mobile.novel.NovelPluginCache
import dev.moku.mobile.novel.NovelPluginLoader
import dev.moku.mobile.repository.AvailableExtensionEntity
import dev.moku.mobile.repository.InstalledExtensionEntity
import dev.moku.mobile.repository.LocalRepositoryManager
import dev.moku.mobile.repository.RepositoryEntity
import dev.moku.mobile.ui.components.MokuToast
import dev.moku.mobile.ui.components.SkeletonRows
import dev.moku.mobile.ui.components.pressable
import dev.moku.mobile.ui.components.rememberHaptics
import dev.moku.mobile.ui.settings.ActionChip
import dev.moku.mobile.ui.settings.Danger
import dev.moku.mobile.ui.settings.GroupDivider
import dev.moku.mobile.ui.settings.SettingsGroup
import dev.moku.mobile.ui.settings.SettingsPage
import dev.moku.mobile.ui.settings.SettingsRow
import dev.moku.mobile.ui.settings.SettingsToggleRow
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/** What the page is about: an installed extension, a repository listing, or both. */
data class ExtensionRef(
    val packageName: String,
    val installed: InstalledExtensionEntity?,
    val available: AvailableExtensionEntity?,
    val contentType: MediaContentType,
)

private data class SourceInfo(val name: String, val lang: String, val id: String, val baseUrl: String?, val latest: Boolean?)

private data class ExtensionFacts(
    val sources: List<SourceInfo>,
    val fingerprint: String?,
    val sizeBytes: Long?,
    val libraryTitles: Int,
    val downloaded: Int,
    val error: String? = null,
)

private suspend fun loadFacts(context: Context, ref: ExtensionRef): ExtensionFacts = withContext(Dispatchers.IO) {
    val kind = ref.contentType.name.lowercase()
    val origin = "local:$kind:${ref.packageName}"
    val repo = LocalLibraryRepository(context)
    val libraryTitles = repo.getLibrary().count { it.originId == origin }
    val downloaded = repo.getDownloads().count { it.originId == origin && it.status == DownloadStatus.DONE }
    val loaded = runCatching {
        RuntimeBootstrap.ensure(context)
        val apk = ExtensionInstaller(context).installedFile(ref.packageName)
        when (ref.contentType) {
            MediaContentType.MANGA -> ExtensionLoader.loadManga(context, apk).let { ext ->
                Triple(
                    ext.sources.map { s -> SourceInfo(s.name, s.lang, s.id.toString(), (s as? HttpSource)?.baseUrl, (s as? CatalogueSource)?.supportsLatest) },
                    ext.signingFingerprint,
                    apk.length(),
                )
            }
            MediaContentType.ANIME -> ExtensionLoader.loadAnime(context, apk).let { ext ->
                Triple(
                    ext.sources.map { s -> SourceInfo(s.name, s.lang, s.id.toString(), (s as? AnimeHttpSource)?.baseUrl, (s as? AnimeCatalogueSource)?.supportsLatest) },
                    ext.signingFingerprint,
                    apk.length(),
                )
            }
            MediaContentType.NOVEL -> {
                val js = NovelPluginCache.load(context, ref.packageName) ?: error("Plugin script is missing")
                val ext = NovelPluginLoader.load(context, ref.packageName, js)
                Triple(listOf(SourceInfo(ext.name, ext.lang, ext.id, ext.site, null)), null, js.length.toLong())
            }
        }
    }
    loaded.fold(
        onSuccess = { (sources, fp, size) -> ExtensionFacts(sources, fp, size, libraryTitles, downloaded) },
        onFailure = { ExtensionFacts(emptyList(), null, null, libraryTitles, downloaded, error = it.message ?: it.javaClass.simpleName) },
    )
}

@Composable
fun ExtensionDetailPage(
    ref: ExtensionRef,
    repository: RepositoryEntity?,
    manager: LocalRepositoryManager,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MokuTheme.colors
    val haptics = rememberHaptics()
    var installed by remember(ref) { mutableStateOf(ref.installed) }
    var facts by remember { mutableStateOf<ExtensionFacts?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val latest = ref.available
    val name = installed?.name ?: latest?.name ?: ref.packageName
    val update = installed != null && latest != null && latest.versionName != installed?.versionName

    var allSources by remember { mutableStateOf(false) }

    LaunchedEffect(installed?.versionName) {
        facts = if (installed != null) loadFacts(context, ref) else null
    }

    fun install() {
        val target = latest ?: return
        busy = true
        scope.launch {
            runCatching { manager.installExtension(target, ref.contentType) }
                .onSuccess { installed = it; haptics.confirm(); MokuToast.show("${it.name} ${if (update) "updated" else "installed"}"); onChanged() }
                .onFailure { haptics.reject(); MokuToast.show(it.message ?: "Install failed", long = true) }
            busy = false
        }
    }

    SettingsPage(name, onBack = onBack) {
        item { Header(name, latest?.iconUrl, installed, latest, ref.contentType, busy, update, onInstall = ::install) }

        installed?.let { ext ->
            item {
                SettingsGroup {
                    SettingsToggleRow(
                        "Enabled",
                        checked = ext.enabled,
                        icon = R.drawable.ph_shield_check_light,
                        subtitle = if (ext.enabled) "Shows up in Discover and search" else "Hidden from Discover and search",
                        onCheckedChange = { on ->
                            installed = ext.copy(enabled = on)
                            scope.launch { manager.setEnabled(ext.packageName, on); onChanged() }
                        },
                    )
                }
            }
        }

        if (installed != null) {
            val f = facts
            item {
                when {
                    f == null -> SkeletonRows(2)
                    f.error != null -> SettingsGroup("Sources") {
                        SettingsRow("Couldn't load this extension", icon = R.drawable.ph_warning_circle_light, subtitle = f.error, danger = true)
                    }
                    else -> SettingsGroup(if (f.sources.size == 1) "Source" else "Sources · ${f.sources.size}") {
                        val visible = if (allSources || f.sources.size <= 6) f.sources else f.sources.take(5)
                        visible.forEachIndexed { i, s ->
                            if (i > 0) GroupDivider()
                            SettingsRow(
                                s.name,
                                icon = R.drawable.ph_globe_light,
                                subtitle = listOfNotNull(s.lang.uppercase().takeIf { it.isNotBlank() }, s.baseUrl?.removePrefix("https://")?.removePrefix("http://"))
                                    .joinToString(" · "),
                                trailing = if (s.baseUrl != null) {
                                    { Icon(painterResource(R.drawable.ph_arrow_square_out_light), contentDescription = "Open website", tint = colors.textFaint, modifier = Modifier.size(16.dp)) }
                                } else null,
                                onClick = s.baseUrl?.let { url -> { openUrl(context, url) } },
                            )
                        }
                        if (visible.size < f.sources.size) {
                            GroupDivider()
                            SettingsRow("Show all ${f.sources.size}", icon = R.drawable.ph_list_bullets_light, chevron = true) { allSources = true }
                        }
                    }
                }
            }
            if (f != null && f.error == null) {
                item {
                    SettingsGroup("In your library") {
                        SettingsRow(
                            "${f.libraryTitles} ${if (f.libraryTitles == 1) "title" else "titles"}",
                            icon = R.drawable.ph_books_light,
                            subtitle = "${f.downloaded} downloaded ${if (ref.contentType == MediaContentType.ANIME) "episode" else "chapter"}${if (f.downloaded == 1) "" else "s"}",
                        )
                    }
                }
            }
        }

        item {
            val f = facts
            SettingsGroup("Details") {
                InfoRow("Package", ref.packageName, mono = true, copy = true)
                GroupDivider()
                InfoRow("Version", installed?.versionName?.let { v -> if (update) "$v → ${latest.versionName}" else v } ?: latest?.versionName ?: "—")
                GroupDivider()
                InfoRow("Type", ref.contentType.name.lowercase().replaceFirstChar { it.uppercase() })
                GroupDivider()
                InfoRow("Language", (installed?.lang ?: latest?.lang)?.uppercase()?.ifBlank { null } ?: "—")
                if (repository != null) {
                    GroupDivider()
                    InfoRow("Repository", repository.name ?: Uri.parse(repository.indexUrl).host ?: repository.indexUrl)
                }
                installed?.let {
                    GroupDivider()
                    InfoRow("Installed", DateFormat.getMediumDateFormat(context).format(Date(it.installedAt)))
                }
                f?.sizeBytes?.let {
                    GroupDivider()
                    InfoRow("Size", Formatter.formatShortFileSize(context, it))
                }
                f?.fingerprint?.let {
                    GroupDivider()
                    InfoRow("Signature", it.take(23), mono = true, copy = true, copyValue = it)
                }
                if (latest?.isNsfw == true) {
                    GroupDivider()
                    InfoRow("Content", "18+")
                }
            }
        }

        installed?.let { ext ->
            item {
                SettingsGroup(footer = if (confirmRemove && (facts?.libraryTitles ?: 0) > 0) "Your library titles from this source stay, but won't update until it's installed again." else null) {
                    SettingsRow(
                        if (confirmRemove) "Tap again to uninstall" else "Uninstall",
                        icon = R.drawable.ph_trash_light,
                        danger = true,
                    ) {
                        if (!confirmRemove) {
                            haptics.longPress()
                            confirmRemove = true
                            return@SettingsRow
                        }
                        scope.launch {
                            manager.uninstallExtension(ext.packageName)
                            MokuToast.show("${ext.name} removed")
                            onChanged()
                            onBack()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    name: String,
    iconUrl: String?,
    installed: InstalledExtensionEntity?,
    latest: AvailableExtensionEntity?,
    type: MediaContentType,
    busy: Boolean,
    update: Boolean,
    onInstall: () -> Unit,
) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Xl)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(colors.bgSurface).border(1.dp, colors.borderDim, shape).padding(MokuSpacing.Sp4),
        verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp4)) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(MokuRadius.Xxl)).background(colors.bgOverlay),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1).uppercase(), style = MokuText.title, color = colors.textMuted)
                if (iconUrl != null) AsyncImage(model = iconUrl, contentDescription = null, modifier = Modifier.size(64.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MokuText.heading, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        type.name.lowercase().replaceFirstChar { it.uppercase() },
                        (installed?.lang ?: latest?.lang)?.uppercase()?.ifBlank { null },
                        "v" + (installed?.versionName ?: latest?.versionName ?: "?"),
                    ).joinToString(" · "),
                    style = MokuText.meta,
                    color = colors.textMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp1)) {
                    when {
                        installed == null -> Pill("Not installed", colors.textFaint)
                        !installed.enabled -> Pill("Disabled", colors.textFaint)
                        else -> Pill("Installed", colors.accentFg)
                    }
                    if (update) Pill("Update available", colors.accentBright)
                    if (latest?.isNsfw == true) Pill("18+", Danger)
                }
            }
        }
        if (installed == null || update) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
                ActionChip(
                    when {
                        busy -> if (update) "Updating…" else "Installing…"
                        update -> "Update to v${latest?.versionName}"
                        else -> "Install"
                    },
                    icon = R.drawable.ph_download_simple_light,
                    accent = true,
                ) { if (!busy) onInstall() }
                if (busy) CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: androidx.compose.ui.graphics.Color) {
    val shape = RoundedCornerShape(MokuRadius.Sm)
    Text(
        text,
        style = MokuText.label,
        color = color,
        modifier = Modifier.clip(shape).background(color.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false, copy: Boolean = false, copyValue: String = value) {
    val colors = MokuTheme.colors
    val context = LocalContext.current
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .let { m ->
                if (!copy) m else m.pressable(pressedScale = 1f) {
                    haptics.click()
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, copyValue))
                    MokuToast.show("$label copied")
                }
            }
            .padding(horizontal = MokuSpacing.Sp4, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
    ) {
        Text(label, style = MokuText.body, color = colors.textMuted)
        Text(
            value,
            style = if (mono) MokuText.meta else MokuText.sub,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
        if (copy) Icon(painterResource(R.drawable.ph_copy_light), contentDescription = null, tint = colors.textFaint, modifier = Modifier.size(13.dp))
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { MokuToast.show("No app can open that link") }
}
