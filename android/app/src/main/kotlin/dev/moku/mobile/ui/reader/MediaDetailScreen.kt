package dev.moku.mobile.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.moku.mobile.backend.BackendMedia
import dev.moku.mobile.backend.BackendUnit
import dev.moku.mobile.backend.LocalBackendFactory
import dev.moku.mobile.library.LocalLibraryRepository
import dev.moku.mobile.ui.components.MokuTopBar
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DetailState(
    val loading: Boolean = true,
    val error: String? = null,
    val media: BackendMedia? = null,
    val units: List<BackendUnit> = emptyList(),
    val inLibrary: Boolean = false,
)

/**
 * Cover, synopsis, and the chapter/episode list for one title. Resolves its backend purely from
 * `originId` via [LocalBackendFactory], same mechanism [dev.moku.mobile.library.DownloadWorker]
 * uses, so it works for a title opened straight from the on-device library too, not just one
 * just browsed.
 */
@Composable
fun MediaDetailScreen(originId: String, mediaId: String, onBack: () -> Unit, onOpenUnit: (BackendUnit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MokuTheme.colors
    var state by remember { mutableStateOf(DetailState()) }

    LaunchedEffect(originId, mediaId) {
        state = try {
            withContext(Dispatchers.IO) {
                val backend = LocalBackendFactory.reconstruct(context, originId)
                val media = backend.mediaDetails(mediaId)
                val units = backend.unitList(mediaId)
                val inLibrary = LocalLibraryRepository(context).isInLibrary(originId, mediaId)
                DetailState(loading = false, media = media, units = units, inLibrary = inLibrary)
            }
        } catch (t: Throwable) {
            android.util.Log.e("MediaDetailScreen", "load failed for originId=$originId mediaId=$mediaId", t)
            DetailState(loading = false, error = "Couldn't load this title.")
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        MokuTopBar(title = state.media?.title ?: "Loading...", onBack = onBack)

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
            state.error != null -> Box(Modifier.fillMaxSize().padding(MokuSpacing.Sp6), contentAlignment = Alignment.Center) {
                Text(state.error!!, color = colors.textMuted, style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                val media = state.media!!
                LazyColumn(contentPadding = PaddingValues(bottom = MokuSpacing.Sp8)) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = MokuSpacing.Sp4), horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp4)) {
                            Box(modifier = Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(MokuRadius.Lg)).background(colors.bgRaised)) {
                                if (media.thumbnailUrl != null) {
                                    AsyncImage(
                                        model = media.thumbnailUrl,
                                        contentDescription = media.title,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(MokuRadius.Lg)),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                                if (media.status != null) Text(media.status.uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.accentBright)
                                if (media.authors.isNotEmpty()) Text(media.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                                LibraryButton(
                                    inLibrary = state.inLibrary,
                                    onClick = {
                                        scope.launch {
                                            val repo = LocalLibraryRepository(context)
                                            if (state.inLibrary) repo.removeFromLibrary(originId, mediaId) else repo.addToLibrary(media)
                                            state = state.copy(inLibrary = !state.inLibrary)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (media.genres.isNotEmpty()) {
                        item {
                            LazyRow(
                                modifier = Modifier.padding(top = MokuSpacing.Sp4),
                                contentPadding = PaddingValues(horizontal = MokuSpacing.Sp4),
                                horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2),
                            ) {
                                items(media.genres) { genre ->
                                    Box(modifier = Modifier.clip(RoundedCornerShape(MokuRadius.Md)).background(colors.bgRaised).padding(horizontal = MokuSpacing.Sp3, vertical = MokuSpacing.Sp1)) {
                                        Text(genre, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                                    }
                                }
                            }
                        }
                    }
                    if (!media.description.isNullOrBlank()) {
                        item {
                            Text(
                                media.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp4),
                            )
                        }
                    }
                    item {
                        Text(
                            "${state.units.size} chapters",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.textPrimary,
                            modifier = Modifier.padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp2),
                        )
                    }
                    items(state.units, key = { it.id }) { unit ->
                        UnitRow(unit, onClick = { onOpenUnit(unit) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryButton(inLibrary: Boolean, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 36.dp)
            .clip(RoundedCornerShape(MokuRadius.Md))
            .background(if (inLibrary) colors.accentMuted else colors.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = MokuSpacing.Sp3, vertical = MokuSpacing.Sp2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp1),
    ) {
        Icon(
            if (inLibrary) Icons.Filled.Check else Icons.Filled.Add,
            contentDescription = null,
            tint = if (inLibrary) colors.accentFg else colors.bgVoid,
            modifier = Modifier.size(16.dp),
        )
        Text(
            if (inLibrary) "In library" else "Add to library",
            style = MaterialTheme.typography.labelMedium,
            color = if (inLibrary) colors.accentFg else colors.bgVoid,
        )
    }
}

@Composable
private fun UnitRow(unit: BackendUnit, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp3),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            unit.title ?: unit.number?.let { "Chapter ${if (it == it.toInt().toFloat()) it.toInt().toString() else it.toString()}" } ?: unit.id,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
