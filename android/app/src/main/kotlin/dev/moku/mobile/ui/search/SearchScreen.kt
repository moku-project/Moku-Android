package dev.moku.mobile.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.moku.mobile.backend.FilteredContentBackend
import dev.moku.mobile.library.ContentFilterDao
import dev.moku.mobile.library.LocalLibraryDatabase
import dev.moku.mobile.ui.components.ContentTypeFilter
import dev.moku.mobile.ui.components.ContentTypeFilterController
import dev.moku.mobile.ui.components.ContentTypeSwitch
import dev.moku.mobile.ui.components.MediaPosterCard
import dev.moku.mobile.ui.components.MokuTopBar
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun SearchScreen(onOpenMedia: (String, String) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(SearchState()) }
    val colors = MokuTheme.colors
    val filterDao = remember { ContentFilterDao(LocalLibraryDatabase.get(context)) }

    val activeType = ContentTypeFilterController.current

    LaunchedEffect(activeType, query) {
        state = state.copy(loading = true, unavailableReason = null)
        if (query.isNotBlank()) delay(400)
        val rules = filterDao.getAll()
        val level = ContentFilterController.level

        val typesToQuery = if (activeType == ContentTypeFilter.ALL) {
            listOf(SearchTab.MANGA, SearchTab.NOVEL, SearchTab.ANIME)
        } else {
            listOf(SearchTab.entries.first { it.contentType == activeType.mediaType })
        }

        val results = mutableListOf<dev.moku.mobile.backend.BackendMedia>()
        var lastUnavailable: String? = null
        for (searchTab in typesToQuery) {
            when (val result = SearchBackends.forTab(context, searchTab)) {
                is SearchBackendResult.Unavailable -> lastUnavailable = result.reason
                is SearchBackendResult.Ready -> {
                    val filtered = FilteredContentBackend(result.backend, rules, level)
                    val page = try {
                        withContext(Dispatchers.IO) {
                            if (query.isBlank()) filtered.popularMedia(1) else filtered.search(query, page = 1)
                        }
                    } catch (t: Throwable) {
                        android.util.Log.e("SearchScreen", "search/popularMedia failed for ${result.backend.originId}", t)
                        null
                    }
                    results += page?.items.orEmpty()
                }
            }
        }
        state = if (results.isEmpty() && typesToQuery.size == 1 && lastUnavailable != null) {
            SearchState(loading = false, unavailableReason = lastUnavailable)
        } else {
            SearchState(loading = false, results = results)
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        MokuTopBar(title = "Discover", trailing = { ContentTypeSwitch() })

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp3),
            placeholder = { Text("Search ${activeType.label.lowercase()}...", color = colors.textFaint) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = colors.textMuted) },
            singleLine = true,
            shape = RoundedCornerShape(MokuRadius.Lg),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.borderBase,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                cursorColor = colors.accent,
            ),
        )

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            state.unavailableReason != null -> Box(Modifier.fillMaxSize().padding(MokuSpacing.Sp6), contentAlignment = Alignment.Center) {
                Text(state.unavailableReason!!, color = colors.textMuted, style = MaterialTheme.typography.bodyMedium)
            }
            state.results.isEmpty() -> Box(Modifier.fillMaxSize().padding(MokuSpacing.Sp6), contentAlignment = Alignment.Center) {
                Text("No results.", color = colors.textMuted, style = MaterialTheme.typography.bodyMedium)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(MokuSpacing.Sp4),
                horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
                verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp4),
            ) {
                items(state.results) { media ->
                    MediaPosterCard(
                        title = media.title,
                        thumbnailUrl = media.thumbnailUrl,
                        subtitle = media.genres.firstOrNull(),
                        onClick = { onOpenMedia(media.originId, media.id) },
                    )
                }
            }
        }
    }
}
