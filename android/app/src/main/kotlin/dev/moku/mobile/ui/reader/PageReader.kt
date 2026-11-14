package dev.moku.mobile.ui.reader

import dev.moku.mobile.ui.components.pressable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import dev.moku.mobile.R
import dev.moku.mobile.backend.BackendPageImage
import dev.moku.mobile.backend.BackendUnit
import dev.moku.mobile.backend.BackendUnitContent
import dev.moku.mobile.ui.components.MokuSectionLabel
import dev.moku.mobile.ui.settings.ActionChip
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageReaderScreen(
    nav: ReaderNav,
    seriesKey: String,
    initial: LoadedUnit,
    startPage: Int,
    units: List<BackendUnit>,
    load: suspend (String) -> LoadedUnit?,
    onPosition: (unitId: String, index: Int, count: Int) -> Unit,
) {
    val pages = (initial.content as BackendUnitContent.Pages).pages
    val mode = ReaderPrefs.mode(seriesKey)
    var chrome by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(Triple(initial.unitId, startPage.coerceIn(0, pages.size - 1), pages.size)) }
    var offline by remember { mutableStateOf(initial.offline) }
    var seek by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }

    ReaderSystemBars(hidden = !chrome)
    LaunchedEffect(position) { onPosition(position.first, position.second, position.third) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val onTap = { chrome = !chrome }
        if (mode == ReadingMode.WEBTOON) {
            LongStrip(
                initial = initial,
                start = startPage,
                units = units,
                load = load,
                seek = seek,
                onSeekDone = { seek = null },
                onTap = onTap,
                onScroll = { chrome = false },
            ) { unitId, index, count, isOffline ->
                position = Triple(unitId, index, count)
                offline = isOffline
            }
        } else {
            val context = LocalContext.current
            PagedPages(nav, pages, mode == ReadingMode.RTL, startPage, seek?.first, onSeekDone = { seek = null }, onTap = onTap) {
                position = Triple(initial.unitId, it, pages.size)
                ReaderImages.prefetch(context, pages.drop(it + 1).take(4))
            }
        }

        ReaderTopBar(chrome, nav.title, nav.unitLabel, nav.onBack, Modifier.align(Alignment.TopCenter)) {
            if (offline) {
                Icon(painterResource(R.drawable.ph_download_simple_fill), contentDescription = "Downloaded", tint = MokuTheme.colors.accent, modifier = Modifier.size(14.dp))
            }
            Text("${position.second + 1} / ${position.third}", style = MokuText.sub, color = MokuTheme.colors.textMuted)
            ChromeButton(R.drawable.ph_sliders_horizontal_light, "Reader settings") { settings = true }
        }

        ReaderBottomBar(chrome, Modifier.align(Alignment.BottomCenter)) {
            val rtl = mode == ReadingMode.RTL
            NavGhostButton(R.drawable.ph_arrow_left_light, if (rtl) "Next chapter" else "Previous chapter", enabled = if (rtl) nav.next != null else nav.prev != null) {
                if (rtl) nav.onNext() else nav.onPrev()
            }
            SegmentedProgress(position.second, position.third, rtl, Modifier.weight(1f)) { index, commit ->
                position = position.copy(second = index)
                seek = index to commit
            }
            NavGhostButton(R.drawable.ph_arrow_right_light, if (rtl) "Previous chapter" else "Next chapter", enabled = if (rtl) nav.prev != null else nav.next != null) {
                if (rtl) nav.onPrev() else nav.onNext()
            }
        }
    }

    if (settings) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { settings = false }, sheetState = sheetState, containerColor = MokuTheme.colors.bgSurface) {
            SheetColumn("Reader") {
                MokuSectionLabel("Reading mode")
                Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                    ReadingMode.entries.forEach { m ->
                        ModeTile(m, selected = m == mode, modifier = Modifier.weight(1f)) { ReaderPrefs.setMode(seriesKey, m) }
                    }
                }
                Text("Saved for this series and used as the default for new ones", style = MokuText.meta, color = MokuTheme.colors.textFaint)
            }
        }
    }
}

@Composable
private fun ModeTile(mode: ReadingMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Xl)
    Column(
        modifier
            .pressable(shape, onClick = onClick)
            .background(if (selected) colors.accentMuted else colors.bgRaised)
            .border(1.dp, if (selected) colors.accentDim else colors.borderDim, shape)
            .padding(vertical = MokuSpacing.Sp4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2),
    ) {
        Icon(painterResource(mode.icon), contentDescription = null, tint = if (selected) colors.accentFg else colors.textMuted, modifier = Modifier.size(22.dp))
        Text(mode.label, style = MokuText.meta, color = if (selected) colors.accentFg else colors.textMuted)
    }
}

@Composable
private fun LongStrip(
    initial: LoadedUnit,
    start: Int,
    units: List<BackendUnit>,
    load: suspend (String) -> LoadedUnit?,
    seek: Pair<Int, Boolean>?,
    onSeekDone: () -> Unit,
    onTap: () -> Unit,
    onScroll: () -> Unit,
    onPosition: (unitId: String, index: Int, count: Int, offline: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val colors = MokuTheme.colors
    val strip = remember(initial.unitId) {
        StripState(Chunk(initial.unitId, (initial.content as BackendUnitContent.Pages).pages, initial.offline)) {
            (it as? BackendUnitContent.Pages)?.pages?.takeIf { p -> p.isNotEmpty() }
        }
    }
    val entries by remember { derivedStateOf { strip.entries(headers = false) } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)

    val center by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val idx = info.visibleItemsInfo.firstOrNull { it.offset <= middle && it.offset + it.size >= middle }?.index
                ?: listState.firstVisibleItemIndex
            entries.getOrNull(idx)
        }
    }
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            val remaining = (info.totalItemsCount - 1 - last.index)
            remaining <= 4 && (last.offset + last.size - info.viewportEndOffset) < info.viewportEndOffset * 1.5f
        }
    }

    LaunchedEffect(center) {
        val e = center ?: return@LaunchedEffect
        val chunk = strip.chunks.getOrNull(e.chunk) ?: return@LaunchedEffect
        val local = (e as? StripEntry.Item)?.local ?: 0
        onPosition(chunk.unitId, local, chunk.items.size, chunk.offline)
        val flat = strip.chunks.drop(e.chunk).flatMap { it.items }
        ReaderImages.prefetch(context, flat.drop(local + 1).take(5))
    }
    LaunchedEffect(nearEnd, units, strip.chunks.size) { if (nearEnd) strip.append(units, load) }
    LaunchedEffect(seek) {
        val (index, _) = seek ?: return@LaunchedEffect
        val chunk = center?.chunk ?: 0
        val target = entries.indexOfFirst { it is StripEntry.Item && it.chunk == chunk && it.local == index }
        if (target >= 0) listState.scrollToItem(target)
        onSeekDone()
    }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress && seek == null) onScroll() }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
    ) {
        items(entries.size, key = { i ->
            when (val e = entries[i]) {
                is StripEntry.Header -> "h:${strip.chunks[e.chunk].unitId}"
                is StripEntry.Item -> "${strip.chunks[e.chunk].unitId}:${e.local}"
            }
        }) { i ->
            when (val e = entries[i]) {
                is StripEntry.Header -> ChapterDivider(units.firstOrNull { it.id == strip.chunks[e.chunk].unitId })
                is StripEntry.Item -> PageImage(strip.chunks[e.chunk].items[e.local], fit = false, modifier = Modifier.fillMaxWidth())
            }
        }
        item(key = "tail") {
            Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
                when {
                    strip.appending -> CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    strip.atEnd -> Text("You're all caught up", style = MokuText.sub, color = Color.White.copy(alpha = 0.5f))
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun ChapterDivider(unit: BackendUnit?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = MokuSpacing.Sp5, vertical = MokuSpacing.Sp8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
        Text(
            (unit?.num()?.let { "Ch. ${formatNumber(it)}" } ?: unit?.title ?: "Next chapter").uppercase(),
            style = MokuText.label,
            color = Color.White.copy(alpha = 0.45f),
        )
        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedPages(
    nav: ReaderNav,
    pages: List<BackendPageImage>,
    rtl: Boolean,
    start: Int,
    seekRequest: Int?,
    onSeekDone: () -> Unit,
    onTap: () -> Unit,
    onCurrent: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start) { pages.size + 1 }
    LaunchedEffect(pager.currentPage) { onCurrent(pager.currentPage.coerceAtMost(pages.size - 1)) }
    LaunchedEffect(seekRequest) { seekRequest?.let { pager.scrollToPage(it); onSeekDone() } }

    fun step(forward: Boolean) = scope.launch {
        val target = pager.currentPage + if (forward) 1 else -1
        if (target in 0 until pager.pageCount) pager.animateScrollToPage(target)
    }

    HorizontalPager(
        state = pager,
        reverseLayout = rtl,
        beyondViewportPageCount = 1,
        key = { it },
        modifier = Modifier.fillMaxSize(),
    ) { index ->
        if (index < pages.size) {
            ZoomablePage(
                page = pages[index],
                settled = pager.settledPage == index,
                onTap = { x, width ->
                    when {
                        x < width * 0.3f -> step(forward = rtl)
                        x > width * 0.7f -> step(forward = !rtl)
                        else -> onTap()
                    }
                },
            )
        } else {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }, contentAlignment = Alignment.Center) {
                ChapterEnd(nav, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ZoomablePage(page: BackendPageImage, settled: Boolean, onTap: (x: Float, width: Float) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    LaunchedEffect(settled) {
        if (!settled) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                size = this.size
                detectTapGestures(
                    onTap = { onTap(it.x, this.size.width.toFloat()) },
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            val center = Offset(this.size.width / 2f, this.size.height / 2f)
                            offset = clamp((center - tap) * 1.5f, 2.5f)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.size > 1
                        if (multi || scale > 1.01f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            val wanted = offset + event.calculatePan()
                            val clamped = clamp(wanted, scale)
                            val pastEdge = !multi && kotlin.math.abs(wanted.x - clamped.x) > 0.5f
                            offset = clamped
                            if (!pastEdge) event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale < 1.05f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center,
    ) {
        PageImage(page, fit = true, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun PageImage(page: BackendPageImage, fit: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val colors = MokuTheme.colors
    val loader = remember { ReaderImages.loader(context) }
    var attempt by remember(page.imageUrl) { mutableIntStateOf(0) }
    var state by remember(page.imageUrl) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    val request = remember(page.imageUrl, attempt) {
        ImageRequest.Builder(context).data(page).setParameter("attempt", attempt, memoryCacheKey = null).build()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            imageLoader = loader,
            contentDescription = "Page ${page.index + 1}",
            contentScale = if (fit) ContentScale.Fit else ContentScale.FillWidth,
            onState = { state = it },
            modifier = if (fit) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        )
        val placeholder = if (fit) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(520.dp)
        when (val s = state) {
            is AsyncImagePainter.State.Success -> Unit
            is AsyncImagePainter.State.Error -> Column(
                placeholder.padding(MokuSpacing.Sp5),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2, Alignment.CenterVertically),
            ) {
                Icon(painterResource(R.drawable.ph_warning_circle_light), contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(26.dp))
                Text("Page ${page.index + 1} didn't load", style = MokuText.body, color = colors.textSecondary)
                s.result.throwable.message?.let { Text(it, style = MokuText.meta, color = colors.textFaint, maxLines = 2) }
                Box(Modifier.padding(top = MokuSpacing.Sp1)) { ActionChip("Retry", icon = R.drawable.ph_arrow_clockwise_light, accent = true) { attempt++ } }
            }
            else -> Column(
                placeholder,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2, Alignment.CenterVertically),
            ) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Text("${page.index + 1}", style = MokuText.sub, color = Color.White.copy(alpha = 0.4f))
            }
        }
    }
}
