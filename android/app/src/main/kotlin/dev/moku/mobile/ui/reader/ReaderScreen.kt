package dev.moku.mobile.ui.reader

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.moku.mobile.R
import dev.moku.mobile.backend.BackendMedia
import dev.moku.mobile.backend.BackendPageImage
import dev.moku.mobile.backend.BackendUnit
import dev.moku.mobile.backend.BackendUnitContent
import dev.moku.mobile.backend.ContentBackend
import dev.moku.mobile.backend.LocalBackendFactory
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.backend.SubtitleTrack
import dev.moku.mobile.library.DownloadEntity
import dev.moku.mobile.library.DownloadStatus
import dev.moku.mobile.library.LocalLibraryRepository
import dev.moku.mobile.library.Libraries
import dev.moku.mobile.library.MediaCache
import dev.moku.mobile.library.ReadingProgressEntity
import dev.moku.mobile.tracking.TrackSync
import dev.moku.mobile.ui.settings.ActionChip
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private sealed interface ReaderState {
    data object Loading : ReaderState
    data class Error(val message: String) : ReaderState
    data class Ready(val loaded: LoadedUnit) : ReaderState
}

@Composable
fun ReaderScreen(originId: String, mediaId: String, unitId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(originId) { Libraries.forOrigin(context, originId) }
    val device = remember { LocalLibraryRepository(context) }
    val trackSync = remember { TrackSync(context) }
    remember { ReaderPrefs.init(context) }

    var openId by remember(unitId) { mutableStateOf(unitId) }
    var visibleId by remember(unitId) { mutableStateOf(unitId) }
    var backend by remember { mutableStateOf<ContentBackend?>(null) }
    var backendError by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf<BackendMedia?>(null) }
    var units by remember { mutableStateOf(emptyList<BackendUnit>()) }
    var state by remember { mutableStateOf<ReaderState>(ReaderState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    val seriesKey = "$originId|$mediaId"

    BackHandler(onBack = onBack)

    suspend fun loadUnit(id: String): LoadedUnit? = withContext(Dispatchers.IO) {
        val progress = repo.getProgress(originId, mediaId, id)
        device.getDownload(originId, mediaId, id)?.takeIf { it.status == DownloadStatus.DONE }?.let(::localContent)
            ?.let { return@withContext LoadedUnit(id, it, offline = true, progress) }
        val b = backend ?: return@withContext null
        val content = b.unitContent(mediaId, id)
        emptyReason(content)?.let { throw IllegalStateException(it) }
        LoadedUnit(id, content, offline = false, progress)
    }

    val loadQuietly: suspend (String) -> LoadedUnit? = { id ->
        runCatching { loadUnit(id) }.onFailure { Log.w("ReaderScreen", "couldn't load $id", it) }.getOrNull()
    }

    LaunchedEffect(originId) {
        withContext(Dispatchers.IO) {
            runCatching { LocalBackendFactory.reconstruct(context, originId) }
                .onSuccess { backend = it }
                .onFailure { backendError = it.message ?: "This source isn't installed" }
        }
    }

    LaunchedEffect(mediaId) {
        MediaCache(context).get(originId, mediaId)?.let { cached ->
            if (units.isEmpty()) units = cached.units.readingOrder()
            if (media == null) media = cached.media
        }
    }

    LaunchedEffect(backend, mediaId) {
        val b = backend ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            launch { runCatching { b.unitList(mediaId) }.onSuccess { units = it.readingOrder() } }
            launch { runCatching { b.mediaDetails(mediaId) }.onSuccess { media = it } }
        }
    }

    LaunchedEffect(openId, attempt, backend, backendError) {
        val shown = state
        if (shown is ReaderState.Ready && shown.loaded.unitId == openId && shown.loaded.offline) return@LaunchedEffect
        if (shown !is ReaderState.Ready || shown.loaded.unitId != openId) state = ReaderState.Loading
        val result = runCatching { loadUnit(openId) }
        result.exceptionOrNull()?.let { Log.e("ReaderScreen", "load failed for $originId/$mediaId/$openId", it) }
        val loaded = result.getOrNull()
        val next = when {
            loaded != null -> ReaderState.Ready(loaded)
            result.isFailure -> ReaderState.Error(result.exceptionOrNull()?.message?.take(160) ?: "Unknown error")
            backendError != null -> ReaderState.Error(backendError!!)
            else -> null
        }
        if (next == null) return@LaunchedEffect
        if (shown is ReaderState.Ready && next is ReaderState.Ready && shown.loaded.unitId == openId) return@LaunchedEffect
        state = next
        if (next is ReaderState.Ready) repo.touchLastViewed(originId, mediaId)
    }

    val mode = ReaderPrefs.mode(seriesKey)
    LaunchedEffect(mode) { if (visibleId != openId) openId = visibleId }

    val contentType = backend?.contentType ?: when {
        originId.contains(":anime:") -> MediaContentType.ANIME
        originId.contains(":novel:") -> MediaContentType.NOVEL
        else -> MediaContentType.MANGA
    }
    val word = if (contentType == MediaContentType.ANIME) "Episode" else "Chapter"
    val current = units.firstOrNull { it.id == visibleId }
    val (prev, next) = neighbours(units, current)
    fun open(target: BackendUnit?) {
        target ?: return
        openId = target.id
        visibleId = target.id
    }
    val nav = ReaderNav(
        title = media?.title.orEmpty(),
        word = word,
        current = current,
        prev = prev,
        next = next,
        onPrev = { open(prev) },
        onNext = { open(next) },
        onBack = onBack,
    )

    fun save(id: String, fraction: Float, completed: Boolean, position: Double? = null, duration: Double? = null) {
        scope.launch {
            repo.updateProgress(originId, mediaId, id, fraction, completed, position, duration)
            if (completed) trackSync.push(originId, mediaId, units)
        }
    }

    when (val s = state) {
        is ReaderState.Loading -> StatusScreen(nav) {
            CircularProgressIndicator(color = MokuTheme.colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            Text("Loading ${nav.short(current) ?: word.lowercase()}", style = MokuText.sub, color = Color.White.copy(alpha = 0.6f))
        }
        is ReaderState.Error -> StatusScreen(nav) {
            Icon(painterResource(R.drawable.ph_warning_circle_light), contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(30.dp))
            Text("Couldn't load this ${word.lowercase()}", style = MokuText.heading, color = Color.White)
            Text(s.message, style = MokuText.meta, color = Color.White.copy(alpha = 0.55f), textAlign = TextAlign.Center)
            Row(Modifier.padding(top = MokuSpacing.Sp2), horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                ActionChip("Back", onClick = onBack)
                ActionChip("Retry", icon = R.drawable.ph_arrow_clockwise_light, accent = true) { attempt++ }
            }
        }
        is ReaderState.Ready -> key(s.loaded.unitId) {
            val loaded = s.loaded
            val resume = loaded.progress?.takeIf { !it.completed }
            when (val content = loaded.content) {
                is BackendUnitContent.Pages -> {
                    val count = content.pages.size
                    PageReaderScreen(
                        nav = nav,
                        seriesKey = seriesKey,
                        initial = loaded,
                        startPage = resume?.let { (it.progress * count).roundToInt() - 1 }?.coerceAtLeast(0) ?: 0,
                        units = units,
                        load = loadQuietly,
                    ) { id, index, total ->
                        visibleId = id
                        save(id, (index + 1f) / total, index >= total - 1)
                    }
                }
                is BackendUnitContent.Text -> NovelReaderScreen(
                    nav = nav,
                    initial = loaded,
                    startFraction = resume?.progress ?: 0f,
                    units = units,
                    load = loadQuietly,
                    onFinished = { id -> save(id, 1f, true) },
                ) { id, fraction, completed ->
                    visibleId = id
                    save(id, fraction, completed)
                }
                is BackendUnitContent.Video -> VideoPlayerScreen(nav, content, resume?.positionSeconds, loaded.offline) { position, duration ->
                    val fraction = (position / duration).toFloat().coerceIn(0f, 1f)
                    save(loaded.unitId, fraction, fraction >= 0.9f, position, duration)
                }
            }
        }
    }
}

@Composable
private fun StatusScreen(nav: ReaderNav, content: @Composable () -> Unit) {
    ReaderSystemBars(hidden = false)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ReaderTopBar(true, nav.title, nav.unitLabel, nav.onBack, Modifier.align(Alignment.TopCenter))
        Column(
            Modifier.align(Alignment.Center).padding(MokuSpacing.Sp6),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2),
        ) { content() }
    }
}

private fun emptyReason(content: BackendUnitContent): String? = when (content) {
    is BackendUnitContent.Pages -> "The source returned no pages".takeIf { content.pages.isEmpty() }
    is BackendUnitContent.Video -> "The source returned no playable video".takeIf { content.streamUrl.isBlank() }
    is BackendUnitContent.Text -> "The source returned an empty chapter".takeIf { content.html.isBlank() }
}

private fun localContent(download: DownloadEntity): BackendUnitContent? {
    val file = download.filePath?.let(::File)?.takeIf { it.exists() } ?: return null
    return when {
        file.isDirectory -> file.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?.takeIf { it.isNotEmpty() }
            ?.mapIndexed { i, f -> BackendPageImage(index = i, imageUrl = f.absolutePath) }
            ?.let { BackendUnitContent.Pages(it) }
        file.name == "content.html" -> BackendUnitContent.Text(file.readText())
        else -> BackendUnitContent.Video(
            streamUrl = Uri.fromFile(file).toString(),
            subtitles = File(file.parentFile, "subtitle.vtt").takeIf { it.exists() }
                ?.let { listOf(SubtitleTrack("Subtitles", Uri.fromFile(it).toString())) }
                .orEmpty(),
        )
    }
}
