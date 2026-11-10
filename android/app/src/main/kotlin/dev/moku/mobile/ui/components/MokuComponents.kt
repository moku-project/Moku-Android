package dev.moku.mobile.ui.components

import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.moku.mobile.R
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme

enum class MokuDestination(val label: String) {
    Home("Home"),
    Search("Discover"),
    Library("Library"),
    Downloads("Downloads"),
    Settings("Settings"),
}

@Composable
fun MokuIconBadge(
    modifier: Modifier = Modifier,
    background: Color = MokuTheme.colors.bgRaised,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(MokuRadius.Lg)
    val base = Modifier
        .size(36.dp)
        .clip(shape)
        .background(background)
        .border(1.dp, MokuTheme.colors.borderDim, shape)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Box(modifier = base.then(modifier), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun MokuHeaderAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    MokuIconBadge(onClick = onClick) {
        Icon(painterResource(icon), contentDescription = label, tint = MokuTheme.colors.textMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun MokuSectionLabel(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    val colors = MokuTheme.colors
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = colors.textFaint, modifier = Modifier.size(11.dp))
        Text(text.uppercase(), style = MokuText.label, color = colors.textFaint)
    }
}

@Composable
fun MokuBottomNav(current: MokuDestination, onSelect: (MokuDestination) -> Unit) {
    val colors = MokuTheme.colors
    val items = listOf(
        Triple(MokuDestination.Home, R.drawable.ph_house_light, "Home"),
        Triple(MokuDestination.Search, R.drawable.ph_magnifying_glass_light, "Discover"),
        Triple(MokuDestination.Library, R.drawable.ph_books_light, "Library"),
        Triple(MokuDestination.Downloads, R.drawable.ph_download_simple_light, "Downloads"),
        Triple(MokuDestination.Settings, R.drawable.ph_gear_six_light, "Settings"),
    )
    val shape = RoundedCornerShape(MokuRadius.Xxl)
    val button = 44.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = MokuSpacing.Sp4, end = MokuSpacing.Sp4, top = MokuSpacing.Sp2, bottom = MokuSpacing.Sp3),
        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .background(colors.bgRaised)
                .border(1.dp, colors.borderDim, shape)
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            items.forEach { (dest, icon, label) ->
                NavIcon(icon, label, selected = current == dest, size = button) { onSelect(dest) }
            }
        }
        ContentModeButton(size = button + 12.dp, iconSize = 22.dp)
    }
}

@Composable
private fun NavIcon(
    @DrawableRes icon: Int,
    label: String,
    selected: Boolean,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val colors = MokuTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(MokuRadius.Xl))
            .background(if (selected) colors.accentDim else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = if (selected) colors.accentBright else colors.textMuted, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun MokuTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = MokuTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = MokuSpacing.Sp4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
        ) {
            if (onBack != null) MokuHeaderAction(R.drawable.ph_arrow_left_light, "Back", onBack)
            Text(title, style = MokuText.title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2), verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
    }
}

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
