package dev.moku.mobile.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.moku.mobile.R
import dev.moku.mobile.backend.BackendMedia
import dev.moku.mobile.backend.FilteredContentBackend
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.library.ContentFilterDao
import dev.moku.mobile.library.LocalLibraryDatabase
import dev.moku.mobile.remote.ServerSession
import dev.moku.mobile.ui.components.ContentTypeFilter
import dev.moku.mobile.ui.components.ContentTypeFilterController
import dev.moku.mobile.ui.components.MediaPosterCard
import dev.moku.mobile.ui.components.MokuHeaderAction
import dev.moku.mobile.ui.components.MokuSearchField
import dev.moku.mobile.ui.components.MokuTopBar
import dev.moku.mobile.ui.components.SkeletonPosterGrid
import dev.moku.mobile.ui.components.rememberReselectGridState
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** One source's answer; null while it's still working. */
private data class SourceAnswer(val key: String, val items: List<BackendMedia>?)

// Each on-device source runs its extension's code; more than a few at once makes a phone crawl.
private const val PARALLEL_SOURCES = 4

/** Popular titles, or search results, from every source at once, with duplicates merged. */
@Composable
fun SearchScreen(
    onOpenMedia: (String, String) -> Unit = { _, _ -> },
    onOpenExtensions: () -> Unit = {},
) {
    val context = LocalContext.current
    val filterDao = remember { ContentFilterDao(LocalLibraryDatabase.get(context)) }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var noSources by remember { mutableStateOf(false) }
    val answers = remember { mutableStateListOf<SourceAnswer>() }

    val activeType = ContentTypeFilterController.current
    val serverMode by ServerSession.serverMode.collectAsState()
    val types = activeType.mediaType?.let(::listOf) ?: listOf(MediaContentType.MANGA, MediaContentType.NOVEL, MediaContentType.ANIME)

    LaunchedEffect(activeType, query, refresh, serverMode) {
        val q = query.trim()
        if (q.isNotEmpty()) delay(500)
        val rules = filterDao.getAll()
        val level = ContentFilterController.level
        val targets = types.flatMap { type -> SearchBackends.options(context, type).map { it to type } }
        noSources = targets.isEmpty()
        answers.clear()
        answers.addAll(targets.map { SourceAnswer(it.first.key, null) })
        val gate = Semaphore(PARALLEL_SOURCES)
        coroutineScope {
            targets.forEachIndexed { i, (option, type) ->
                launch {
                    val items = gate.withPermit {
                        try {
                            val backend = FilteredContentBackend(SearchBackends.backend(context, option, type), rules, level)
                            withContext(Dispatchers.IO) { if (q.isEmpty()) backend.popularMedia(1) else backend.search(q, page = 1) }.items
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            android.util.Log.w("SearchScreen", "${option.name} failed", t)
                            emptyList()
                        }
                    }
                    if (i < answers.size && answers[i].key == option.key) answers[i] = SourceAnswer(option.key, items)
                }
            }
        }
    }

    val results = dedupeMedia(interleave(answers.mapNotNull { it.items }))
    val loading = answers.any { it.items == null }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        MokuTopBar(
            title = "Discover",
            trailing = {
                MokuHeaderAction(R.drawable.ph_arrows_clockwise_light, "Refresh", active = loading) { refresh++ }
                MokuHeaderAction(R.drawable.ph_puzzle_piece_light, "Extensions", onClick = onOpenExtensions)
            },
        )

        MokuSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = if (activeType == ContentTypeFilter.ALL) "Search everything" else "Search ${activeType.label.lowercase()}",
            modifier = Modifier.padding(start = MokuSpacing.Sp4, end = MokuSpacing.Sp4, bottom = MokuSpacing.Sp2),
        )

        when {
            noSources -> Message(if (ServerSession.active) "The server has no sources for this yet." else "No sources installed. Add some from Extensions.")
            results.isEmpty() && loading -> SkeletonPosterGrid(modifier = Modifier.padding(MokuSpacing.Sp4))
            results.isEmpty() -> Message("No results.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                state = rememberReselectGridState(),
                contentPadding = PaddingValues(MokuSpacing.Sp4),
                horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
                verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp4),
            ) {
                items(results, key = { it.originId + "\u0000" + it.id }) { media ->
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

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(MokuSpacing.Sp6), contentAlignment = Alignment.Center) {
        Text(text, color = MokuTheme.colors.textMuted, style = MaterialTheme.typography.bodyMedium)
    }
}
