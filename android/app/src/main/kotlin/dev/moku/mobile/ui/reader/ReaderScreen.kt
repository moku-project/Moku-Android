package dev.moku.mobile.ui.reader

import android.app.Application
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import dev.moku.mobile.backend.BackendUnitContent
import dev.moku.mobile.backend.LocalBackendFactory
import dev.moku.mobile.library.LocalLibraryRepository
import dev.moku.mobile.playback.HeaderMediaSourceFactory
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.injectLazy

private sealed interface ReaderState {
    data object Loading : ReaderState
    data class Error(val message: String) : ReaderState
    data class Loaded(val content: BackendUnitContent) : ReaderState
}

/**
 * Displays one [dev.moku.mobile.backend.BackendUnit]'s content — image pages (manga/novel-with-
 * images sources), plain text (novel), or video (anime, via a real [ExoPlayer] using
 * [HeaderMediaSourceFactory] so extension-provided auth headers actually reach every HLS
 * segment request). Reading progress is written for real as the reader is used — a page-scroll
 * fraction for Pages, and 100%-on-open for Text (there's no finer-grained position to track for
 * a single HTML blob) — through the same [LocalLibraryRepository] the Home dashboard's stats
 * already read from, so "Continue reading"/streak numbers move for real once this screen exists.
 */
@Composable
fun ReaderScreen(originId: String, mediaId: String, unitId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MokuTheme.colors
    var state by remember { mutableStateOf<ReaderState>(ReaderState.Loading) }

    LaunchedEffect(originId, mediaId, unitId) {
        state = try {
            withContext(Dispatchers.IO) {
                val backend = LocalBackendFactory.reconstruct(context, originId)
                val content = backend.unitContent(mediaId, unitId)
                LocalLibraryRepository(context).touchLastViewed(originId, mediaId)
                ReaderState.Loaded(content)
            }
        } catch (t: Throwable) {
            android.util.Log.e("ReaderScreen", "load failed for originId=$originId mediaId=$mediaId unitId=$unitId", t)
            ReaderState.Error("Couldn't load this chapter.")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            is ReaderState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
            is ReaderState.Error -> Box(Modifier.fillMaxSize().statusBarsPadding().padding(MokuSpacing.Sp6), contentAlignment = Alignment.Center) {
                Text(s.message, color = colors.textMuted, style = MaterialTheme.typography.bodyMedium)
            }
            is ReaderState.Loaded -> when (val content = s.content) {
                is BackendUnitContent.Pages -> PagesReader(content, onProgress = { fraction, completed ->
                    scope.launch { LocalLibraryRepository(context).updateProgress(originId, mediaId, unitId, fraction, completed) }
                })
                is BackendUnitContent.Text -> TextReader(content, onShown = {
                    scope.launch { LocalLibraryRepository(context).updateProgress(originId, mediaId, unitId, 1f, true) }
                })
                is BackendUnitContent.Video -> VideoReader(content)
            }
        }

        Box(
            modifier = Modifier.statusBarsPadding().padding(MokuSpacing.Sp3).defaultMinSize(minWidth = 44.dp, minHeight = 44.dp).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = androidx.compose.ui.graphics.Color.White)
        }
    }
}

@Composable
private fun PagesReader(content: BackendUnitContent.Pages, onProgress: (Float, Boolean) -> Unit) {
    val listState = rememberLazyListState()
    val lastIndexSeen by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
    }
    LaunchedEffect(lastIndexSeen) {
        val total = content.pages.size
        if (total > 0) {
            val fraction = ((lastIndexSeen + 1).toFloat() / total).coerceIn(0f, 1f)
            onProgress(fraction, lastIndexSeen >= total - 1)
        }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(content.pages, key = { it.index }) { page ->
            AsyncImage(
                model = page.imageUrl,
                contentDescription = "Page ${page.index + 1}",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

@Composable
private fun TextReader(content: BackendUnitContent.Text, onShown: () -> Unit) {
    val colors = MokuTheme.colors
    val plainText = remember(content.html) { HtmlCompat.fromHtml(content.html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString() }
    LaunchedEffect(content.html) { onShown() }
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(MokuSpacing.Sp5)) {
        Text(plainText, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoReader(content: BackendUnitContent.Video) {
    val application: Application by injectLazy()
    val player = remember { ExoPlayer.Builder(application).build() }
    DisposableEffect(content.streamUrl) {
        player.setMediaSource(HeaderMediaSourceFactory.create(content))
        player.prepare()
        player.playWhenReady = true
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
        modifier = Modifier.fillMaxSize().aspectRatio(16f / 9f),
    )
}
