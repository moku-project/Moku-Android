package dev.moku.mobile.ui.downloads

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
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
import dev.moku.mobile.library.DownloadEntity
import dev.moku.mobile.library.DownloadQueueManager
import dev.moku.mobile.library.DownloadStatus
import dev.moku.mobile.library.LibraryMediaEntity
import dev.moku.mobile.library.LocalLibraryRepository
import dev.moku.mobile.ui.components.MokuTopBar
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class DownloadRow(val entry: DownloadEntity, val media: LibraryMediaEntity?)

/** Real downloads from [LocalLibraryRepository] — grouped by status, backed by [DownloadQueueManager]
 * for retry and plain file deletion for removal. No mock rows: an empty download queue is shown as such. */
@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MokuTheme.colors

    var loading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf(emptyList<DownloadRow>()) }

    suspend fun reload() {
        val repo = LocalLibraryRepository(context)
        val downloads = repo.getDownloads().sortedByDescending { it.createdAt }
        val library = repo.getLibrary().associateBy { it.originId to it.mediaId }
        rows = downloads.map { DownloadRow(it, library[it.originId to it.mediaId]) }
    }

    LaunchedEffect(Unit) {
        loading = true
        reload()
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        MokuTopBar(title = "Downloads")

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(MokuSpacing.Sp6), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No downloads yet", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    Text(
                        "Chapters/episodes you download will show up here.",
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
                items(rows, key = { "${it.entry.originId}:${it.entry.mediaId}:${it.entry.unitId}" }) { row ->
                    DownloadRowItem(
                        row = row,
                        onRetry = {
                            scope.launch {
                                val repo = LocalLibraryRepository(context)
                                DownloadQueueManager(context, repo).enqueue(row.entry.originId, row.entry.mediaId, row.entry.unitId)
                                reload()
                            }
                        },
                        onRemove = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    row.entry.filePath?.let { File(it).deleteRecursively() }
                                    LocalLibraryRepository(context).deleteDownload(row.entry)
                                }
                                reload()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRowItem(row: DownloadRow, onRetry: () -> Unit, onRemove: () -> Unit) {
    val colors = MokuTheme.colors
    val entry = row.entry
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MokuRadius.Lg))
            .background(colors.bgSurface)
            .padding(MokuSpacing.Sp3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
    ) {
        Box(modifier = Modifier.width(48.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(MokuRadius.Md)).background(colors.bgRaised)) {
            if (row.media?.thumbnailUrl != null) {
                AsyncImage(
                    model = row.media.thumbnailUrl,
                    contentDescription = row.media.title,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(MokuRadius.Md)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.media?.title ?: entry.unitId,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(statusLabel(entry), style = MaterialTheme.typography.bodySmall, color = statusColor(entry.status, colors))
        }
        if (entry.status == DownloadStatus.FAILED) {
            Box(
                modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp).clip(RoundedCornerShape(MokuRadius.Md)).clickable(onClick = onRetry),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Retry", tint = colors.accentBright)
            }
        }
        Box(
            modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp).clip(RoundedCornerShape(MokuRadius.Md)).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = colors.textFaint)
        }
    }
}

private fun statusLabel(entry: DownloadEntity): String = when (entry.status) {
    DownloadStatus.QUEUED -> "Queued"
    DownloadStatus.DOWNLOADING -> "Downloading..."
    DownloadStatus.DONE -> "Downloaded · ${formatBytes(entry.bytes)}"
    DownloadStatus.FAILED -> "Failed" + (entry.error?.let { " · $it" } ?: "")
}

private fun statusColor(status: DownloadStatus, colors: dev.moku.mobile.ui.theme.MokuPalette) = when (status) {
    DownloadStatus.DONE -> colors.accentBright
    DownloadStatus.FAILED -> colors.textFaint
    else -> colors.textMuted
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) "%.1f MB".format(mb) else "${bytes / 1024} KB"
}
