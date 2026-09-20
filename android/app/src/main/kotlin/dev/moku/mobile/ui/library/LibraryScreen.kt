package dev.moku.mobile.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.library.LibraryMediaEntity
import dev.moku.mobile.library.LocalLibraryRepository
import dev.moku.mobile.ui.components.ContentTypeFilterController
import dev.moku.mobile.ui.components.ContentTypeSwitch
import dev.moku.mobile.ui.components.MokuTopBar
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun MediaContentType.label() = when (this) {
    MediaContentType.MANGA -> "Manga"
    MediaContentType.ANIME -> "Anime"
    MediaContentType.NOVEL -> "Novel"
}

@Composable
fun LibraryScreen(onOpenMedia: (String, String) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf(emptyList<LibraryMediaEntity>()) }
    val colors = MokuTheme.colors
    val activeType = ContentTypeFilterController.current.mediaType

    LaunchedEffect(activeType) {
        loading = true
        val repo = LocalLibraryRepository(context)
        items = withContext(Dispatchers.IO) {
            if (activeType == null) repo.getLibrary() else repo.getLibrary(activeType)
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        MokuTopBar(title = "Library", trailing = { ContentTypeSwitch() })

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize().padding(MokuSpacing.Sp6), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nothing here yet", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    Text(
                        "Titles you add from Search will show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        modifier = Modifier.padding(top = MokuSpacing.Sp2),
                    )
                }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp2),
                verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
            ) {
                items(items, key = { "${it.originId}:${it.mediaId}" }) { entry -> LibraryRow(entry, onClick = { onOpenMedia(entry.originId, entry.mediaId) }) }
            }
        }
    }
}

@Composable
private fun LibraryRow(entry: LibraryMediaEntity, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MokuRadius.Lg))
            .background(colors.bgSurface)
            .clickable(onClick = onClick)
            .padding(MokuSpacing.Sp3),
        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
    ) {
        Box(
            modifier = Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(MokuRadius.Md)).background(colors.bgRaised),
        ) {
            if (entry.thumbnailUrl != null) {
                AsyncImage(
                    model = entry.thumbnailUrl,
                    contentDescription = entry.title,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(MokuRadius.Md)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(entry.contentType.label(), entry.status).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
