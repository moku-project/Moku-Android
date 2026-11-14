package dev.moku.mobile.ui.reader

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.moku.mobile.R
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.tracking.LocalTrackingRepository
import dev.moku.mobile.tracking.TrackLinkEntity
import dev.moku.mobile.tracking.TrackSearchResult
import dev.moku.mobile.tracking.TrackStatus
import dev.moku.mobile.tracking.TrackSync
import dev.moku.mobile.tracking.TrackerInfo
import dev.moku.mobile.ui.components.MokuChip
import dev.moku.mobile.ui.components.MokuSectionLabel
import dev.moku.mobile.ui.components.MokuToast
import dev.moku.mobile.ui.components.SkeletonRows
import dev.moku.mobile.ui.components.pressable
import dev.moku.mobile.ui.components.rememberHaptics
import dev.moku.mobile.ui.settings.ActionChip
import dev.moku.mobile.ui.settings.GroupDivider
import dev.moku.mobile.ui.settings.SettingsGroup
import dev.moku.mobile.ui.settings.SettingsRow
import dev.moku.mobile.ui.settings.TextInput
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun TrackStatus.label(anime: Boolean) = when (this) {
    TrackStatus.READING -> if (anime) "Watching" else "Reading"
    TrackStatus.PLAN_TO_READ -> "Planning"
    TrackStatus.COMPLETED -> "Completed"
    TrackStatus.ON_HOLD -> "Paused"
    TrackStatus.DROPPED -> "Dropped"
    TrackStatus.REREADING -> if (anime) "Rewatching" else "Rereading"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackingSheet(
    originId: String,
    mediaId: String,
    title: String,
    contentType: MediaContentType,
    tracks: List<TrackLinkEntity>,
    onTracksChanged: (List<TrackLinkEntity>) -> Unit,
    onLocalProgressChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MokuTheme.colors
    val tracking = remember { LocalTrackingRepository(context) }
    val sync = remember { TrackSync(context) }
    val trackers = remember { tracking.getTrackers().filter { it.isLoggedIn } }
    var searching by remember { mutableStateOf<TrackerInfo?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.bgSurface) {
        val target = searching
        if (target != null) {
            TrackSearch(
                tracker = target,
                initialQuery = title,
                contentType = contentType,
                onBack = { searching = null },
                onPick = { result ->
                    runCatching {
                        tracking.bindTrack(originId, mediaId, target.key, result.remoteId, result.title)
                        sync.sync(originId, mediaId)
                    }.onSuccess {
                        onTracksChanged(it)
                        onLocalProgressChanged()
                        searching = null
                        MokuToast.show("Tracking on ${target.name}")
                    }.onFailure { MokuToast.show("Couldn't bind: ${it.message}") }
                },
            )
            return@ModalBottomSheet
        }

        SheetColumn("Tracking") {
            if (trackers.isEmpty()) {
                Text(
                    "Sign in to AniList or MyAnimeList under Settings → Tracking to sync progress both ways.",
                    style = MokuText.sub,
                    color = colors.textMuted,
                )
                return@SheetColumn
            }
            trackers.forEach { tracker ->
                val link = tracks.firstOrNull { it.trackerKey == tracker.key }
                if (link == null) {
                    SettingsGroup {
                        SettingsRow(
                            "Track on ${tracker.name}",
                            subtitle = tracker.username?.let { "Signed in as $it" },
                            leading = { TrackerLogo(tracker.iconUrl) },
                            chevron = true,
                        ) { searching = tracker }
                    }
                } else {
                    TrackEditor(
                        tracker = tracker,
                        link = link,
                        anime = contentType == MediaContentType.ANIME,
                        scoreOptions = remember(tracker.key) { tracking.scoreOptions(tracker.key) },
                        onSave = { status, progress, score ->
                            runCatching {
                                sync.edit(link, status, progress, score)
                                // Moving ahead on the tracker should mark those chapters read here too.
                                if (progress > link.lastChapterRead) sync.sync(originId, mediaId) else tracking.getTrackLinks(originId, mediaId)
                            }.onSuccess {
                                onTracksChanged(it)
                                onLocalProgressChanged()
                                MokuToast.show("Saved to ${tracker.name}")
                            }.onFailure { MokuToast.show("Couldn't save: ${it.message}") }
                        },
                        onResync = {
                            runCatching { sync.sync(originId, mediaId) }
                                .onSuccess { onTracksChanged(it); onLocalProgressChanged(); MokuToast.show("Synced with ${tracker.name}") }
                                .onFailure { MokuToast.show("Couldn't sync: ${it.message}") }
                        },
                        onUnbind = {
                            tracking.unbindTrack(originId, mediaId, tracker.key)
                            onTracksChanged(tracking.getTrackLinks(originId, mediaId))
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackEditor(
    tracker: TrackerInfo,
    link: TrackLinkEntity,
    anime: Boolean,
    scoreOptions: List<String>,
    onSave: suspend (TrackStatus, Double, Double) -> Unit,
    onResync: suspend () -> Unit,
    onUnbind: suspend () -> Unit,
) {
    val context = LocalContext.current
    val colors = MokuTheme.colors
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val linkStatus = TrackStatus.entries[link.status - 1]
    var status by remember(link) { mutableStateOf(linkStatus) }
    var progress by remember(link) { mutableStateOf(link.lastChapterRead.toInt()) }
    var score by remember(link) { mutableStateOf(link.score) }
    var busy by remember { mutableStateOf(false) }
    var confirmUnbind by remember(link) { mutableStateOf(false) }
    val dirty = status != linkStatus || progress != link.lastChapterRead.toInt() || score != link.score
    val total = link.totalChapters
    val word = if (anime) "Episodes" else "Chapters"

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { try { block() } finally { busy = false } }
    }

    SettingsGroup {
        SettingsRow(
            link.title,
            subtitle = tracker.name + (tracker.username?.let { " · $it" } ?: ""),
            leading = { TrackerLogo(tracker.iconUrl) },
            trailing = {
                if (busy) CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                else Icon(painterResource(R.drawable.ph_arrow_square_out_light), contentDescription = "Open on ${tracker.name}", tint = colors.textFaint, modifier = Modifier.size(16.dp))
            },
            onClick = link.url?.takeIf { it.isNotBlank() }?.let { url -> { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } } },
        )
        GroupDivider()
        Column(Modifier.fillMaxWidth().padding(MokuSpacing.Sp4 - 2.dp), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
            MokuSectionLabel("Status")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TrackStatus.entries.forEach { s -> MokuChip(s.label(anime), selected = status == s) { status = s } }
            }
            MokuSectionLabel(word)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
                StepButton(R.drawable.ph_minus_light, "Fewer", enabled = progress > 0) { haptics.tick(); progress-- }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (total > 0) "$progress / $total" else "$progress",
                        style = MokuText.heading,
                        color = colors.textPrimary,
                    )
                    if (total > 0) {
                        Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.7f).height(3.dp).clip(RoundedCornerShape(MokuRadius.Sm)).background(colors.borderBase)) {
                            Box(Modifier.fillMaxWidth((progress.toFloat() / total).coerceIn(0f, 1f)).height(3.dp).background(colors.accent))
                        }
                    }
                }
                StepButton(R.drawable.ph_plus_light, "More", enabled = total <= 0 || progress < total) { haptics.tick(); progress++ }
            }
            MokuSectionLabel("Score")
            val values = scoreOptions.mapIndexed { i, s -> s.toDoubleOrNull() ?: i.toDouble() }
            val selected = values.indexOfFirst { it == score }.coerceAtLeast(0)
            val rowState = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 2).coerceAtLeast(0))
            LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(scoreOptions) { i, option ->
                    MokuChip(if (values[i] == 0.0 && i == 0) "–" else option, selected = i == selected) { score = values[i] }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2), verticalAlignment = Alignment.CenterVertically) {
        if (dirty) ActionChip("Save", icon = R.drawable.ph_check_bold, accent = true) { run { onSave(status, progress.toDouble(), score) } }
        ActionChip("Sync now", icon = R.drawable.ph_arrows_clockwise_light) { run { onResync() } }
        Box(Modifier.weight(1f))
        ActionChip(if (confirmUnbind) "Tap again to remove" else "Remove", icon = R.drawable.ph_link_simple_break_light, danger = true) {
            if (confirmUnbind) run { onUnbind() } else confirmUnbind = true
        }
    }
}

@Composable
private fun TrackSearch(
    tracker: TrackerInfo,
    initialQuery: String,
    contentType: MediaContentType,
    onBack: () -> Unit,
    onPick: suspend (TrackSearchResult) -> Unit,
) {
    val context = LocalContext.current
    val colors = MokuTheme.colors
    val scope = rememberCoroutineScope()
    val tracking = remember { LocalTrackingRepository(context) }
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<TrackSearchResult>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var binding by remember { mutableStateOf<String?>(null) }
    val kind = when (contentType) {
        MediaContentType.ANIME -> "anime"
        MediaContentType.NOVEL -> "novel"
        else -> "manga"
    }

    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        delay(350)
        results = null
        error = null
        runCatching { tracking.search(tracker.key, query.trim(), kind) }
            .onSuccess { results = it }
            .onFailure { error = it.message ?: "Search failed" }
    }

    SheetColumn("Find on ${tracker.name}", trailing = { ActionChip("Back") { onBack() } }) {
        TextInput(query, { query = it }, placeholder = "Search ${tracker.name}", modifier = Modifier.fillMaxWidth())
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
            val list = results
            when {
                error != null -> Text(error.orEmpty(), style = MokuText.sub, color = colors.textMuted)
                list == null -> SkeletonRows(4, leading = 44.dp)
                list.isEmpty() -> Text("No matches. Try a shorter or romaji title.", style = MokuText.sub, color = colors.textMuted)
                else -> list.forEach { r ->
                    val shape = RoundedCornerShape(MokuRadius.Lg)
                    Row(
                        Modifier
                            .pressable(shape, enabled = binding == null) {
                                binding = r.remoteId
                                scope.launch { try { onPick(r) } finally { binding = null } }
                            }
                            .fillMaxWidth()
                            .background(colors.bgRaised, shape)
                            .border(1.dp, colors.borderDim, shape)
                            .padding(MokuSpacing.Sp2),
                        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = r.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(44.dp).height(64.dp).clip(RoundedCornerShape(MokuRadius.Md)).background(colors.bgOverlay),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(r.title, style = MokuText.body, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val meta = listOfNotNull(
                                r.mediaType?.lowercase()?.replaceFirstChar { it.uppercase() },
                                r.totalChapters.takeIf { it > 0 }?.let { "$it ${if (contentType == MediaContentType.ANIME) "eps" else "ch"}" },
                                r.publishingStatus?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.uppercase() },
                            ).joinToString(" · ")
                            if (meta.isNotEmpty()) Text(meta, style = MokuText.meta, color = colors.textMuted, maxLines = 1)
                        }
                        if (binding == r.remoteId) CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackerLogo(url: String) {
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(MokuRadius.Md)).background(MokuTheme.colors.bgOverlay),
    )
}

@Composable
private fun StepButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Md)
    Box(
        Modifier
            .pressable(shape, enabled = enabled, onClick = onClick)
            .size(40.dp)
            .background(colors.bgRaised, shape)
            .border(1.dp, colors.borderBase, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = if (enabled) colors.textSecondary else colors.textFaint, modifier = Modifier.size(16.dp))
    }
}
