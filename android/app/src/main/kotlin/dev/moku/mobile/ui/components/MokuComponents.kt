package dev.moku.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuTheme

enum class MokuDestination(val label: String) {
    Home("Home"),
    Search("Discover"),
    Library("Library"),
    Downloads("Downloads"),
    Settings("Settings"),
}

/** The rounded-square icon badge header actions sit in — everything in the UI reads as a
 * rounded square, never a circle or a pill. */
@Composable
fun MokuIconBadge(
    modifier: Modifier = Modifier,
    background: Color = MokuTheme.colors.bgRaised,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val base = Modifier
        .size(40.dp)
        .clip(RoundedCornerShape(MokuRadius.Lg))
        .background(background)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Box(modifier = base.then(modifier), contentAlignment = Alignment.Center) { content() }
}

/** Flat, icon-only bottom nav — five destinations, rounded-square selection background behind
 * the active icon (never a circle or pill). */
@Composable
fun MokuBottomNav(current: MokuDestination, onSelect: (MokuDestination) -> Unit) {
    val colors = MokuTheme.colors
    val items = listOf(
        Triple(MokuDestination.Home, Icons.Filled.Home, "Home"),
        Triple(MokuDestination.Search, Icons.Filled.Search, "Discover"),
        Triple(MokuDestination.Library, Icons.AutoMirrored.Filled.LibraryBooks, "Library"),
        Triple(MokuDestination.Downloads, Icons.Filled.Download, "Downloads"),
        Triple(MokuDestination.Settings, Icons.Filled.Settings, "Settings"),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgSurface)
            .navigationBarsPadding()
            .padding(horizontal = MokuSpacing.Sp3, vertical = MokuSpacing.Sp2),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEach { (dest, icon, label) -> NavIcon(icon, label, selected = current == dest) { onSelect(dest) } }
    }
}

@Composable
private fun NavIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(MokuRadius.Lg))
            .background(if (selected) colors.accentDim else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) colors.accentBright else colors.textMuted, modifier = Modifier.size(22.dp))
    }
}

/**
 * The screen-title row used atop every tab and drill-down screen — an optional rounded-square
 * back button, the title, and an optional trailing slot (e.g. an action [MokuIconBadge]). One
 * shared component instead of every screen hand-rolling its own title `Text` + `Row`, so title
 * size/weight/spacing stays consistent app-wide.
 */
@Composable
fun MokuTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = MokuTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
            if (onBack != null) {
                Box(
                    modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp).clip(RoundedCornerShape(MokuRadius.Lg)).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textSecondary)
                }
            }
            Text(title, style = MaterialTheme.typography.headlineLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
    }
}

/** A poster-style card for a manga/anime/novel title — cover, title, one status/genre chip line. */
@Composable
fun MediaPosterCard(
    title: String,
    thumbnailUrl: String?,
    subtitle: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val colors = MokuTheme.colors
    Column(modifier = modifier.width(120.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(MokuRadius.Lg))
                .background(colors.bgRaised),
        ) {
            if (thumbnailUrl != null) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    title.take(1).uppercase(),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.accentFg,
                )
            }
        }
        Text(
            title,
            modifier = Modifier.padding(top = MokuSpacing.Sp2),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A tap-to-select swatch used in the Settings theming center. */
@Composable
fun ThemeSwatch(
    name: String,
    bgColor: Color,
    accentColor: Color,
    textColor: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val colors = MokuTheme.colors
    Column(
        modifier = modifier.width(84.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(MokuRadius.Xl))
                .background(bgColor)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) accentColor else colors.borderBase,
                    shape = RoundedCornerShape(MokuRadius.Xl),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.size(26.dp).clip(RoundedCornerShape(MokuRadius.Sm)).background(accentColor))
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = textColor,
                    modifier = Modifier.size(16.dp).align(Alignment.BottomEnd).clip(RoundedCornerShape(MokuRadius.Sm)).background(accentColor).padding(2.dp),
                )
            }
        }
        Text(
            name,
            modifier = Modifier.padding(top = MokuSpacing.Sp2),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.accentBright else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A labeled row for Settings — title, optional subtitle/value, optional trailing content. */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = MokuTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
        if (trailing != null) trailing()
    }
}
