package dev.moku.mobile.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
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
        .let { if (onClick != null) it.pressable(shape, pressedScale = 0.9f, onClick = onClick) else it.clip(shape) }
        .background(background)
        .border(1.dp, MokuTheme.colors.borderDim, shape)
    Box(modifier = base.then(modifier), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun MokuHeaderAction(@DrawableRes icon: Int, label: String, active: Boolean = false, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    Box {
        MokuIconBadge(onClick = onClick, background = if (active) colors.accentMuted else colors.bgRaised) {
            Icon(painterResource(icon), contentDescription = label, tint = if (active) colors.accentFg else colors.textMuted, modifier = Modifier.size(18.dp))
        }
        if (active) {
            Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(6.dp).clip(RoundedCornerShape(2.dp)).background(colors.accentBright))
        }
    }
}

@Composable
fun MokuChip(text: String, selected: Boolean, modifier: Modifier = Modifier, count: Int? = null, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Md)
    val chipBg by animateColorAsState(if (selected) colors.accentMuted else colors.bgRaised, MokuMotion.move(), label = "chip-bg")
    val chipBorder by animateColorAsState(if (selected) colors.accentDim else colors.borderDim, MokuMotion.move(), label = "chip-border")
    Row(
        modifier = modifier
            .height(32.dp)
            .pressable(shape, pressedScale = 0.94f, onClick = onClick)
            .background(chipBg)
            .border(1.dp, chipBorder, shape)
            .padding(horizontal = MokuSpacing.Sp3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = MokuText.meta, color = if (selected) colors.accentFg else colors.textMuted, maxLines = 1)
        if (count != null) Text("$count", style = MokuText.label, color = if (selected) colors.accentFg else colors.textFaint)
    }
}

@Composable
fun MokuSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onSearch: (() -> Unit)? = null,
) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Lg)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(colors.bgRaised)
            .border(1.dp, colors.borderDim, shape)
            .padding(horizontal = MokuSpacing.Sp3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2),
    ) {
        Icon(painterResource(R.drawable.ph_magnifying_glass_light), contentDescription = null, tint = colors.textFaint, modifier = Modifier.size(16.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MokuText.sub, color = colors.textFaint, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MokuText.sub.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                modifier = Modifier.fillMaxWidth().let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                painterResource(R.drawable.ph_x_light),
                contentDescription = "Clear",
                tint = colors.textMuted,
                modifier = Modifier.size(28.dp).pressable(RoundedCornerShape(MokuRadius.Sm), pressedScale = 0.85f) { onValueChange("") }.padding(6.dp),
            )
        }
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

private val MokuDestination.icons: Pair<Int, Int>
    get() = when (this) {
        MokuDestination.Home -> R.drawable.ph_house_light to R.drawable.ph_house_fill
        MokuDestination.Search -> R.drawable.ph_magnifying_glass_light to R.drawable.ph_magnifying_glass_fill
        MokuDestination.Library -> R.drawable.ph_books_light to R.drawable.ph_books_fill
        MokuDestination.Downloads -> R.drawable.ph_download_simple_light to R.drawable.ph_download_simple_fill
        MokuDestination.Settings -> R.drawable.ph_gear_six_light to R.drawable.ph_gear_six_fill
    }

@Composable
fun MokuBottomNav(
    current: MokuDestination,
    onSelect: (MokuDestination) -> Unit,
    onReselect: (MokuDestination) -> Unit = {},
) {
    val colors = MokuTheme.colors
    val haptics = rememberHaptics()
    val items = MokuDestination.entries
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
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .background(colors.bgRaised)
                .border(1.dp, colors.borderDim, shape)
                .padding(6.dp),
        ) {
            val step = (maxWidth - button) / (items.size - 1)
            val target = step * current.ordinal
            var previous by remember { mutableStateOf(current.ordinal) }
            val forward = current.ordinal >= previous
            SideEffect { previous = current.ordinal }
            // The leading edge runs ahead of the trailing one so the pill stretches while it travels.
            val fast = spring<Dp>(dampingRatio = 0.8f, stiffness = 900f)
            val slow = spring<Dp>(dampingRatio = 0.8f, stiffness = 300f)
            val left by animateDpAsState(target, if (forward) slow else fast, label = "nav-left")
            val right by animateDpAsState(target + button, if (forward) fast else slow, label = "nav-right")
            Box(
                Modifier
                    .offset(x = left)
                    .size(width = right - left, height = button)
                    .clip(RoundedCornerShape(MokuRadius.Xl))
                    .background(colors.accentDim),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                items.forEach { dest ->
                    NavIcon(dest.icons, dest.label, selected = current == dest, size = button) {
                        haptics.tick()
                        if (current == dest) onReselect(dest) else onSelect(dest)
                    }
                }
            }
        }
        ContentModeButton(size = button + 12.dp, iconSize = 22.dp)
    }
}

@Composable
private fun NavIcon(
    icons: Pair<Int, Int>,
    label: String,
    selected: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    val colors = MokuTheme.colors
    val tint by animateColorAsState(if (selected) colors.accentBright else colors.textMuted, MokuMotion.move(), label = "nav-tint")
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            pop.snapTo(0.82f)
            pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 600f))
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .pressable(RoundedCornerShape(MokuRadius.Xl), pressedScale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(selected, animationSpec = MokuMotion.move(220), label = "nav-icon") { on ->
            Icon(
                painterResource(if (on) icons.second else icons.first),
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(24.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
            )
        }
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
            if (onBack != null) MokuHeaderAction(R.drawable.ph_arrow_left_light, "Back", onClick = onBack)
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
    Column(modifier = modifier.width(120.dp).pressable(RoundedCornerShape(MokuRadius.Lg), onClick = onClick)) {
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
