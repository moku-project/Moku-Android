package dev.moku.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.moku.mobile.ui.components.MokuIconBadge
import dev.moku.mobile.ui.components.MokuTopBar
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuTheme

/** Home as a stats dashboard — cover card, a 2x2 stat grid, a 7-day activity strip, and an "up next" row. */
@Composable
fun HomeScreen(
    onSearchClick: () -> Unit = {},
    onExtensionsClick: () -> Unit = {},
    onOpenMedia: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(HomeState()) }
    var reloadToken by remember { mutableStateOf(0) }
    val colors = MokuTheme.colors

    LaunchedEffect(reloadToken) {
        state = HomeDataLoader.load(context)
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        MokuTopBar(
            title = "Home",
            trailing = {
                MokuIconBadge(onClick = onExtensionsClick) {
                    Icon(Icons.Filled.Extension, contentDescription = "Extensions", tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                }
            },
        )

        if (state.error != null) {
            Box(Modifier.fillMaxSize().padding(MokuSpacing.Sp6), contentAlignment = Alignment.Center) {
                Text(state.error!!, color = colors.textMuted, style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp2),
            verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp5),
        ) {
            item { CoverCard(state, onSearchClick, onOpenMedia) }
            item { StatGrid(state) }
            item { ActivitySection(state) }
            item { UpNextSection(state, onOpenMedia) }
        }
    }
}

@Composable
private fun DashboardCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MokuTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MokuRadius.Xxl))
            .background(colors.bgSurface)
            .border(1.dp, colors.borderDim, RoundedCornerShape(MokuRadius.Xxl))
            .padding(MokuSpacing.Sp4),
        content = content,
    )
}

@Composable
private fun CoverCard(state: HomeState, onSearchClick: () -> Unit, onOpenMedia: (String, String) -> Unit) {
    val colors = MokuTheme.colors
    val featured = state.continueReading.firstOrNull()

    DashboardCard(
        modifier = if (featured != null) {
            Modifier.clickable { onOpenMedia(featured.media.originId, featured.media.mediaId) }
        } else Modifier,
    ) {
        if (featured == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp4)) {
                Box(
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(MokuRadius.Lg)).background(colors.bgRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = colors.textFaint, modifier = Modifier.size(28.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                    Text("Nothing here yet", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    Text("Read a manga to see it here", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                    Button(
                        onClick = onSearchClick,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.bgRaised, contentColor = colors.textPrimary),
                        contentPadding = PaddingValues(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp2),
                    ) {
                        Text("Start reading", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp4)) {
                Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(MokuRadius.Lg)).background(colors.bgRaised)) {
                    if (featured.media.thumbnailUrl != null) {
                        AsyncImage(
                            model = featured.media.thumbnailUrl,
                            contentDescription = featured.media.title,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(MokuRadius.Lg)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp1)) {
                    Text(featured.media.title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(featured.unitLabel, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                }
            }
        }

        if (state.continueReading.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = MokuSpacing.Sp3),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(state.continueReading.size.coerceAtMost(6)) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .size(if (index == 0) 8.dp else 6.dp)
                            .background(if (index == 0) colors.accent else colors.borderStrong, CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatGrid(state: HomeState) {
    Column(verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
            StatTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.LocalFireDepartment,
                value = "${state.streakDays} day${if (state.streakDays == 1) "" else "s"}",
                label = "Current streak",
            )
            StatTile(modifier = Modifier.weight(1f), value = "${state.totalChapters}", label = "Chapters")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
            StatTile(modifier = Modifier.weight(1f), value = formatDuration(state.readTimeSeconds), label = "Read time")
            StatTile(modifier = Modifier.weight(1f), value = "${state.chaptersThisWeek}", label = "This week")
        }
    }
}

@Composable
private fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val colors = MokuTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MokuRadius.Xl))
            .background(colors.bgSurface)
            .border(1.dp, colors.borderDim, RoundedCornerShape(MokuRadius.Xl))
            .padding(MokuSpacing.Sp4),
        verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp1),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = colors.textPrimary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    }
}

@Composable
private fun ActivitySection(state: HomeState) {
    val colors = MokuTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Activity, last 7 days", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
            state.activity.forEach { day ->
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp1)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(MokuRadius.Md))
                            .background(if (day.hasActivity) colors.accentDim else colors.bgSurface)
                            .border(
                                width = if (day.isToday) 2.dp else 1.dp,
                                color = if (day.isToday) colors.accent else colors.borderDim,
                                shape = RoundedCornerShape(MokuRadius.Md),
                            ),
                    )
                    Text(day.label, style = MaterialTheme.typography.labelSmall, color = if (day.isToday) colors.textPrimary else colors.textFaint)
                }
            }
        }
    }
}

@Composable
private fun UpNextSection(state: HomeState, onOpenMedia: (String, String) -> Unit) {
    val colors = MokuTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
        Text("Up next", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
        if (state.upNext == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(MokuRadius.Xl))
                    .border(1.dp, colors.borderDim, RoundedCornerShape(MokuRadius.Xl)),
                contentAlignment = Alignment.Center,
            ) {
                Text("No chapters to show", style = MaterialTheme.typography.bodyMedium, color = colors.textFaint)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(MokuRadius.Xl))
                    .background(colors.bgSurface)
                    .border(1.dp, colors.borderDim, RoundedCornerShape(MokuRadius.Xl))
                    .clickable { onOpenMedia(state.upNext.media.originId, state.upNext.media.mediaId) }
                    .padding(MokuSpacing.Sp3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
            ) {
                Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(MokuRadius.Md)).background(colors.bgRaised)) {
                    if (state.upNext.media.thumbnailUrl != null) {
                        AsyncImage(
                            model = state.upNext.media.thumbnailUrl,
                            contentDescription = state.upNext.media.title,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(MokuRadius.Md)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.upNext.media.title, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.upNext.unitLabel, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m"
    else -> "${seconds}s"
}
