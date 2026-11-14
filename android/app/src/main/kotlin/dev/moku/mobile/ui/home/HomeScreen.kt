package dev.moku.mobile.ui.home

import dev.moku.mobile.ui.components.rememberReselectListState
import dev.moku.mobile.ui.components.pressable
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import dev.moku.mobile.remote.ServerSession
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.moku.mobile.R
import dev.moku.mobile.ui.components.MokuHeaderAction
import dev.moku.mobile.ui.components.MokuSectionLabel
import dev.moku.mobile.ui.components.MokuTopBar
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme

private val Streak = Color(0xFFFB923C)
private const val SHOWN_WEEKS = 18

@Composable
fun HomeScreen(
    onSearchClick: () -> Unit = {},
    onExtensionsClick: () -> Unit = {},
    onOpenMedia: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(HomeState()) }
    var reloadToken by remember { mutableIntStateOf(0) }
    val colors = MokuTheme.colors

    val serverMode by ServerSession.serverMode.collectAsState()
    LaunchedEffect(reloadToken, serverMode) {
        state = HomeDataLoader.load(context)
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        MokuTopBar(
            title = "Home",
            trailing = {
                MokuHeaderAction(R.drawable.ph_magnifying_glass_light, "Search", onClick = onSearchClick)
                MokuHeaderAction(R.drawable.ph_puzzle_piece_light, "Extensions", onClick = onExtensionsClick)
            },
        )

        if (state.error != null) {
            Column(
                Modifier.fillMaxSize().padding(MokuSpacing.Sp6),
                verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.error!!, style = MokuText.sub, color = colors.textFaint)
                OutlineAction("Retry") { reloadToken++ }
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = rememberReselectListState(),
            contentPadding = PaddingValues(start = MokuSpacing.Sp4, end = MokuSpacing.Sp4, top = MokuSpacing.Sp1, bottom = MokuSpacing.Sp6),
            verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp5),
        ) {
            item {
                val featured = state.continueReading.firstOrNull()
                if (featured != null) ContinueCard(featured, onOpenMedia) else WelcomeCard(onSearchClick, onExtensionsClick)
            }
            item { Section("Your stats", R.drawable.ph_trend_up_bold) { StatsStrip(state) } }
            item {
                val shown = state.heatmap.takeLast(SHOWN_WEEKS * 7)
                val total = shown.sumOf { it.coerceAtLeast(0) }
                Section("Activity", R.drawable.ph_clock_bold, trailing = "$total in $SHOWN_WEEKS weeks") { Heatmap(shown) }
            }
            item { Section("Up next") { UpNext(state.upNext, onSearchClick, onOpenMedia) } }
            val recent = state.continueReading.drop(1).filter { it.media != state.upNext?.media }.take(5)
            if (recent.isNotEmpty()) {
                item {
                    Section("Recent") {
                        Card(padding = PaddingValues(horizontal = MokuSpacing.Sp3, vertical = MokuSpacing.Sp1)) {
                            recent.forEachIndexed { index, item ->
                                if (index > 0) Divider()
                                MediaRow(item, onOpenMedia)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, @DrawableRes icon: Int? = null, trailing: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            MokuSectionLabel(title, Modifier.weight(1f), icon = icon)
            if (trailing != null) Text(trailing, style = MokuText.label, color = MokuTheme.colors.textFaint)
        }
        content()
    }
}

@Composable
private fun Card(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(MokuSpacing.Sp4),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Xl)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.bgSurface)
            .border(1.dp, colors.borderDim, shape)
            .padding(padding),
        content = content,
    )
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MokuTheme.colors.borderDim))
}

@Composable
private fun ContinueCard(item: ContinueReadingItem, onOpenMedia: (String, String) -> Unit) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Xl)
    val open = { onOpenMedia(item.media.originId, item.media.mediaId) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(172.dp)
            .pressable(shape, onClick = open)
            .background(colors.bgSurface)
            .border(1.dp, colors.borderDim, shape),
    ) {
        if (item.media.thumbnailUrl != null) {
            AsyncImage(
                model = item.media.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(28.dp).alpha(0.45f),
                contentScale = ContentScale.Crop,
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(colors.bgVoid.copy(alpha = 0.35f), colors.bgVoid.copy(alpha = 0.85f)))))
        Row(Modifier.fillMaxSize().padding(MokuSpacing.Sp3), horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp4)) {
            Box(
                Modifier.fillMaxHeight().aspectRatio(2f / 3f).clip(RoundedCornerShape(MokuRadius.Lg)).background(colors.bgOverlay),
            ) {
                if (item.media.thumbnailUrl != null) {
                    AsyncImage(
                        model = item.media.thumbnailUrl,
                        contentDescription = item.media.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = MokuSpacing.Sp1), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp1)) {
                MokuSectionLabel("Continue reading")
                Text(item.media.title, style = MokuText.heading, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.unitLabel, style = MokuText.meta, color = colors.textMuted)
                Spacer(Modifier.weight(1f))
                ProgressBar(item.progress)
                Spacer(Modifier.height(MokuSpacing.Sp2))
                AccentAction("Resume", R.drawable.ph_play_fill, open)
            }
        }
    }
}

@Composable
private fun WelcomeCard(onSearchClick: () -> Unit, onExtensionsClick: () -> Unit) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Xl)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.bgSurface)
            .background(Brush.linearGradient(listOf(colors.accentMuted, Color.Transparent)))
            .border(1.dp, colors.borderDim, shape)
            .padding(MokuSpacing.Sp4),
        verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(MokuRadius.Lg)).background(colors.accentMuted).border(1.dp, colors.accentDim, RoundedCornerShape(MokuRadius.Lg)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ph_book_open_light), contentDescription = null, tint = colors.accentFg, modifier = Modifier.size(22.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Start your library", style = MokuText.heading, color = colors.textPrimary)
                Text("Add a source, then pick something to read", style = MokuText.meta, color = colors.textMuted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
            AccentAction("Browse", R.drawable.ph_magnifying_glass_light, onSearchClick, Modifier.weight(1f))
            OutlineAction("Extensions", Modifier.weight(1f), R.drawable.ph_puzzle_piece_light, onExtensionsClick)
        }
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    val colors = MokuTheme.colors
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(MokuRadius.Sm)).background(colors.borderBase)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(colors.accent))
    }
}

@Composable
private fun AccentAction(text: String, @DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Md)
    Row(
        modifier = modifier
            .height(36.dp)
            .pressable(shape, onClick = onClick)
            .background(colors.accentMuted)
            .border(1.dp, colors.accentDim, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = colors.accentFg, modifier = Modifier.size(14.dp))
        Text(text, style = MokuText.meta, color = colors.accentFg)
    }
}

@Composable
private fun OutlineAction(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Md)
    Row(
        modifier = modifier
            .height(36.dp)
            .pressable(shape, onClick = onClick)
            .background(colors.bgRaised)
            .border(1.dp, colors.borderDim, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(14.dp))
        Text(text, style = MokuText.meta, color = colors.textMuted)
    }
}

@Composable
private fun StatsStrip(state: HomeState) {
    val colors = MokuTheme.colors
    Card(padding = PaddingValues(vertical = MokuSpacing.Sp3)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Stat(R.drawable.ph_fire_fill, "${state.streakDays}d", "Streak", Streak, Modifier.weight(1f))
            VDivider()
            Stat(R.drawable.ph_book_open_light, "${state.totalChapters}", "Chapters", colors.textFaint, Modifier.weight(1f))
            VDivider()
            Stat(R.drawable.ph_clock_light, formatDuration(state.readTimeSeconds), "Read time", colors.textFaint, Modifier.weight(1f))
            VDivider()
            Stat(R.drawable.ph_calendar_blank_light, "${state.chaptersThisWeek}", "This week", colors.textFaint, Modifier.weight(1f))
        }
    }
}

@Composable
private fun VDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = MokuSpacing.Sp1).background(MokuTheme.colors.borderDim))
}

@Composable
private fun Stat(@DrawableRes icon: Int, value: String, label: String, tint: Color, modifier: Modifier = Modifier) {
    val colors = MokuTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp1)) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(value, style = MokuText.value, color = colors.textPrimary, maxLines = 1)
        Text(label, style = MokuText.label, color = colors.textFaint, maxLines = 1)
    }
}

@Composable
private fun Heatmap(days: List<Int>) {
    val colors = MokuTheme.colors
    Card(padding = PaddingValues(MokuSpacing.Sp3)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val weeks = days.chunked(7)
            val gap = 3.dp
            val density = LocalDensity.current
            val cell = with(density) { ((maxWidth - gap * (weeks.size - 1)) / weeks.size).roundToPx().toDp() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                weeks.forEach { week ->
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        week.forEach { count -> HeatCell(count, Modifier.size(cell)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(MokuSpacing.Sp2))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Less", style = MokuText.label, color = colors.textFaint)
            listOf(0, 1, 2, 4, 7).forEach { HeatCell(it, Modifier.size(9.dp)) }
            Text("More", style = MokuText.label, color = colors.textFaint)
        }
    }
}

@Composable
private fun HeatCell(count: Int, modifier: Modifier) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(2.dp)
    val (fill, edge, alpha) = when {
        count < 0 -> Triple(Color.Transparent, Color.Transparent, 1f)
        count == 0 -> Triple(colors.bgSubtle, colors.borderDim, 1f)
        count == 1 -> Triple(colors.accentMuted, colors.accentDim, 1f)
        count <= 3 -> Triple(colors.accentDim, colors.accent, 0.7f)
        count <= 6 -> Triple(colors.accent, colors.accentBright, 0.85f)
        else -> Triple(colors.accentBright, colors.accentFg, 1f)
    }
    Box(modifier.alpha(alpha).clip(shape).background(fill).border(1.dp, edge, shape))
}

@Composable
private fun UpNext(item: ContinueReadingItem?, onSearchClick: () -> Unit, onOpenMedia: (String, String) -> Unit) {
    val colors = MokuTheme.colors
    if (item == null) {
        Card(padding = PaddingValues(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp3)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
                Text("Nothing queued yet", style = MokuText.sub, color = colors.textFaint, modifier = Modifier.weight(1f))
                OutlineAction("Find something", onClick = onSearchClick)
            }
        }
    } else {
        Card(padding = PaddingValues(horizontal = MokuSpacing.Sp3, vertical = MokuSpacing.Sp1)) { MediaRow(item, onOpenMedia) }
    }
}

@Composable
private fun MediaRow(item: ContinueReadingItem, onOpenMedia: (String, String) -> Unit) {
    val colors = MokuTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenMedia(item.media.originId, item.media.mediaId) }
            .padding(vertical = MokuSpacing.Sp2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
    ) {
        Box(Modifier.size(width = 36.dp, height = 52.dp).clip(RoundedCornerShape(MokuRadius.Sm)).background(colors.bgOverlay)) {
            if (item.media.thumbnailUrl != null) {
                AsyncImage(
                    model = item.media.thumbnailUrl,
                    contentDescription = item.media.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.media.title, style = MokuText.body, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.unitLabel, style = MokuText.sub, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(painterResource(R.drawable.ph_arrow_right_bold), contentDescription = null, tint = colors.accentFg, modifier = Modifier.size(14.dp))
    }
}

private fun formatDuration(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m"
    else -> "${seconds}s"
}
