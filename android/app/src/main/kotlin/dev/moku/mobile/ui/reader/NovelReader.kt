package dev.moku.mobile.ui.reader

import dev.moku.mobile.ui.components.pressable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.moku.mobile.R
import dev.moku.mobile.backend.BackendUnit
import dev.moku.mobile.backend.BackendUnitContent
import dev.moku.mobile.ui.components.MokuChip
import dev.moku.mobile.ui.components.MokuSectionLabel
import dev.moku.mobile.ui.theme.MokuMonoFamily
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSansFamily
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelReaderScreen(
    nav: ReaderNav,
    initial: LoadedUnit,
    startFraction: Float,
    units: List<BackendUnit>,
    load: suspend (String) -> LoadedUnit?,
    onFinished: (unitId: String) -> Unit,
    onPosition: (unitId: String, fraction: Float, completed: Boolean) -> Unit,
) {
    val colors = MokuTheme.colors
    val scope = rememberCoroutineScope()
    val theme = ReaderPrefs.theme
    val strip = remember(initial.unitId) {
        StripState(Chunk(initial.unitId, parseNovel((initial.content as BackendUnitContent.Text).html), initial.offline)) {
            (it as? BackendUnitContent.Text)?.html?.let(::parseNovel)?.takeIf { b -> b.isNotEmpty() }
        }
    }
    val entries by remember { derivedStateOf { strip.entries(headers = true) } }
    val firstSize = strip.chunks.first().items.size.coerceAtLeast(1)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (startFraction > 0f) 1 + (startFraction * firstSize).toInt().coerceIn(0, firstSize - 1) else 0,
    )
    var chrome by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(initial.unitId to startFraction) }
    val finished = remember { mutableSetOf<String>() }

    val reading by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val idx = info.visibleItemsInfo.firstOrNull { it.offset <= middle && it.offset + it.size >= middle }?.index
                ?: listState.firstVisibleItemIndex
            val offset = if (strip.prepending) 1 else 0
            val entry = entries.getOrNull(idx - offset) ?: return@derivedStateOf null
            val chunk = strip.chunks.getOrNull(entry.chunk) ?: return@derivedStateOf null
            val lastIdx = entries.indexOfLast { it is StripEntry.Item && it.chunk == entry.chunk } + offset
            val lastVisible = info.visibleItemsInfo.any { it.index == lastIdx && it.offset + it.size <= info.viewportEndOffset }
            val local = (entry as? StripEntry.Item)?.local ?: 0
            val fraction = if (lastVisible) 1f else (local.toFloat() / chunk.items.size.coerceAtLeast(1)).coerceIn(0f, 1f)
            Triple(entry.chunk, chunk.unitId, fraction)
        }
    }
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            info.totalItemsCount - 1 - last.index <= 12
        }
    }
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.lastScrolledBackward } }

    ReaderSystemBars(hidden = !chrome, lightContent = theme == NovelTheme.DARK)

    LaunchedEffect(reading) {
        val (chunkIdx, unitId, fraction) = reading ?: return@LaunchedEffect
        position = unitId to fraction
        onPosition(unitId, fraction, fraction >= 0.98f)
        strip.chunks.take(chunkIdx).forEach { passed ->
            if (finished.add(passed.unitId)) onFinished(passed.unitId)
        }
    }
    LaunchedEffect(nearEnd, units, strip.chunks.size) { if (nearEnd) strip.append(units, load) }
    LaunchedEffect(atTop, units) { if (atTop) strip.prepend(units, load) }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) chrome = false }

    val body = TextStyle(
        fontFamily = if (ReaderPrefs.font == NovelFont.SERIF) FontFamily.Serif else MokuSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = ReaderPrefs.textSize.sp,
        lineHeight = ReaderPrefs.lineHeight.em,
        color = theme.text,
        textAlign = if (ReaderPrefs.align == NovelAlign.JUSTIFY) TextAlign.Justify else TextAlign.Start,
    )
    val paraGap = (ReaderPrefs.textSize * ReaderPrefs.paraSpacing).dp

    Box(Modifier.fillMaxSize().background(theme.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().statusBarsPadding().pointerInput(Unit) { detectTapGestures(onTap = { chrome = !chrome }) },
            contentPadding = PaddingValues(start = MokuSpacing.Sp6, end = MokuSpacing.Sp6, top = 44.dp + MokuSpacing.Sp8, bottom = 50.dp + MokuSpacing.Sp8),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (strip.prepending) {
                item(key = "loading-prev") { Notice("Loading previous chapter…", theme) }
            }
            items(entries.size, key = { i ->
                when (val e = entries[i]) {
                    is StripEntry.Header -> "h:${strip.chunks[e.chunk].unitId}"
                    is StripEntry.Item -> "${strip.chunks[e.chunk].unitId}:${e.local}"
                }
            }) { i ->
                val e = entries[i]
                val chunk = strip.chunks[e.chunk]
                val column = Modifier.widthIn(max = 672.dp).fillMaxWidth()
                when (e) {
                    is StripEntry.Header -> SectionHead(units.firstOrNull { it.id == chunk.unitId }, nav.word, body, theme, column)
                    is StripEntry.Item -> {
                        val last = e.local == chunk.items.lastIndex
                        Box(column.padding(bottom = if (last) paraGap + MokuSpacing.Sp10 else paraGap)) {
                            Block(chunk.items[e.local], body, theme)
                        }
                    }
                }
            }
            item(key = "tail") {
                when {
                    strip.appending -> Notice("Loading next chapter…", theme)
                    strip.atEnd -> Notice("You're all caught up", theme)
                    else -> Unit
                }
            }
        }

        ReaderTopBar(chrome, nav.title, nav.unitLabel, nav.onBack, Modifier.align(Alignment.TopCenter)) {
            if (strip.chunks.any { it.unitId == position.first && it.offline }) Text("OFFLINE", style = MokuText.label, color = colors.accent)
            Text("${(position.second * 100).roundToInt()}%", style = MokuText.sub, color = colors.textMuted)
            ChromeButton(R.drawable.ph_text_aa_light, "Text options") { settings = true }
        }

        ReaderBottomBar(chrome, Modifier.align(Alignment.BottomCenter)) {
            NavGhostButton(R.drawable.ph_arrow_left_light, "Previous chapter", enabled = nav.prev != null, onClick = nav.onPrev)
            ThinSlider(position.second, Modifier.weight(1f)) { f, _ ->
                val chunkIdx = strip.chunks.indexOfFirst { it.unitId == position.first }.coerceAtLeast(0)
                val size = strip.chunks[chunkIdx].items.size
                val local = (f * (size - 1)).roundToInt().coerceIn(0, (size - 1).coerceAtLeast(0))
                val target = entries.indexOfFirst { it is StripEntry.Item && it.chunk == chunkIdx && it.local == local }
                if (target >= 0) scope.launch { listState.scrollToItem(target + if (strip.prepending) 1 else 0) }
            }
            NavGhostButton(R.drawable.ph_arrow_right_light, "Next chapter", enabled = nav.next != null, onClick = nav.onNext)
        }
    }

    if (settings) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { settings = false }, sheetState = sheetState, containerColor = colors.bgSurface) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SheetColumn("Text") {
                    MokuSectionLabel("Theme")
                    Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                        NovelTheme.entries.forEach { t -> ThemeSwatch(t, selected = theme == t, modifier = Modifier.weight(1f)) { ReaderPrefs.updateTheme(t) } }
                    }
                    Stepper("Size", "${ReaderPrefs.textSize.roundToInt()}", { ReaderPrefs.updateTextSize(ReaderPrefs.textSize - 1f) }) { ReaderPrefs.updateTextSize(ReaderPrefs.textSize + 1f) }
                    Stepper("Line height", "%.1f".format(ReaderPrefs.lineHeight), { ReaderPrefs.updateLineHeight(ReaderPrefs.lineHeight - 0.1f) }) { ReaderPrefs.updateLineHeight(ReaderPrefs.lineHeight + 0.1f) }
                    Stepper("Paragraph gap", "%.1f".format(ReaderPrefs.paraSpacing), { ReaderPrefs.updateParaSpacing(ReaderPrefs.paraSpacing - 0.1f) }) { ReaderPrefs.updateParaSpacing(ReaderPrefs.paraSpacing + 0.1f) }
                    MokuSectionLabel("Font")
                    Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                        NovelFont.entries.forEach { f -> MokuChip(f.label, selected = ReaderPrefs.font == f) { ReaderPrefs.updateFont(f) } }
                    }
                    MokuSectionLabel("Alignment")
                    Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                        NovelAlign.entries.forEach { a -> MokuChip(a.label, selected = ReaderPrefs.align == a) { ReaderPrefs.updateAlign(a) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHead(unit: BackendUnit?, word: String, body: TextStyle, theme: NovelTheme, modifier: Modifier) {
    val label = unit?.let { u ->
        val n = u.num()?.let { "Ch. ${formatNumber(it)}" }
        val name = u.cleanTitle().takeIf { it.isNotBlank() }
        listOfNotNull(n, name).joinToString(" — ").ifBlank { u.title ?: word }
    } ?: word
    val size = body.fontSize * 0.72f
    val ink = theme.text.copy(alpha = 0.45f)
    Column(modifier.padding(bottom = (size.value * 1.4f).dp)) {
        Text(
            label.uppercase(),
            style = TextStyle(fontFamily = MokuMonoFamily, fontSize = size, letterSpacing = 0.1.em, color = ink),
            modifier = Modifier.padding(bottom = (size.value * 0.5f).dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(ink))
    }
}

@Composable
private fun Block(block: NovelBlock, body: TextStyle, theme: NovelTheme) {
    val unit = body.fontSize.value
    when (block) {
        is NovelBlock.Para -> Text(block.text, style = body)
        is NovelBlock.Heading -> Text(
            block.text,
            style = body.copy(fontWeight = FontWeight.Bold, fontSize = body.fontSize * 1.15f, lineHeight = 1.3.em),
            modifier = Modifier.padding(top = (unit * 0.6f).dp),
        )
        is NovelBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(theme.text.copy(alpha = 0.8f)))
            Text(block.text, style = body.copy(color = theme.text.copy(alpha = 0.8f)), modifier = Modifier.padding(start = unit.dp))
        }
        is NovelBlock.ListItem -> Row {
            Text(block.marker, style = body, modifier = Modifier.width((unit * 1.4f).dp))
            Text(block.text, style = body)
        }
        NovelBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = unit.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth(0.4f).height(1.dp).background(theme.text.copy(alpha = 0.3f)))
        }
    }
}

@Composable
private fun Notice(text: String, theme: NovelTheme) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = MokuSpacing.Sp6),
        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (text.startsWith("Loading")) CircularProgressIndicator(color = theme.text.copy(alpha = 0.6f), strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
        Text(text, style = MokuText.sub, color = theme.text.copy(alpha = 0.6f))
    }
}

@Composable
private fun ThemeSwatch(theme: NovelTheme, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Xl)
    Box(
        modifier
            .height(56.dp)
            .pressable(shape, onClick = onClick)
            .background(theme.background)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.borderDim, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(theme.label, style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 14.sp, color = theme.text))
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val colors = MokuTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
        Text(label, style = MokuText.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
        ChromeButton(R.drawable.ph_minus_light, "Decrease $label", size = 34.dp, onClick = onMinus)
        Text(value, style = MokuText.value, color = colors.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp))
        ChromeButton(R.drawable.ph_plus_light, "Increase $label", size = 34.dp, onClick = onPlus)
    }
}
