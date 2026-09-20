package dev.moku.mobile.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuTheme

enum class ContentTypeFilter(val label: String, val mediaType: MediaContentType?) {
    ALL("All", null),
    MANGA("Manga", MediaContentType.MANGA),
    NOVEL("Novel", MediaContentType.NOVEL),
    ANIME("Anime", MediaContentType.ANIME),
}

private const val PREFS_NAME = "moku_content_type_prefs"
private const val KEY_TYPE = "content_type_filter"

/** App-wide content-type filter, read by every screen that lists media. */
object ContentTypeFilterController {
    var current by mutableStateOf(ContentTypeFilter.ALL)
        private set

    fun init(context: Context) {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_TYPE, null)
        current = stored?.let { runCatching { ContentTypeFilter.valueOf(it) }.getOrNull() } ?: ContentTypeFilter.ALL
    }

    fun set(context: Context, value: ContentTypeFilter) {
        current = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_TYPE, value.name).apply()
    }
}

private fun ContentTypeFilter.icon(): ImageVector = when (this) {
    ContentTypeFilter.ALL -> Icons.Filled.Apps
    ContentTypeFilter.MANGA -> Icons.AutoMirrored.Filled.MenuBook
    ContentTypeFilter.NOVEL -> Icons.Filled.AutoStories
    ContentTypeFilter.ANIME -> Icons.Filled.Movie
}

/** Icon-only switch for [ContentTypeFilterController] — sits in a [MokuTopBar] trailing slot. */
@Composable
fun ContentTypeSwitch(modifier: Modifier = Modifier) {
    val colors = MokuTheme.colors
    val context = LocalContext.current
    val active = ContentTypeFilterController.current
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ContentTypeFilter.entries.forEach { type ->
            val selected = type == active
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(MokuRadius.Md))
                    .background(if (selected) colors.accentDim else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { ContentTypeFilterController.set(context, type) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    type.icon(),
                    contentDescription = type.label,
                    tint = if (selected) colors.accentBright else colors.textFaint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
