package dev.moku.mobile.ui.settings

import dev.moku.mobile.ui.components.rememberReselectListState
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.moku.mobile.R
import dev.moku.mobile.ui.components.MokuSectionLabel
import dev.moku.mobile.ui.components.MokuTopBar
import dev.moku.mobile.ui.components.pressable
import dev.moku.mobile.ui.components.rememberHaptics
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme

val Danger = Color(0xFFE5675F)

@Composable
fun SettingsPage(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        MokuTopBar(title = title, onBack = onBack, trailing = trailing)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = rememberReselectListState(),
            contentPadding = PaddingValues(start = MokuSpacing.Sp4, end = MokuSpacing.Sp4, top = MokuSpacing.Sp1, bottom = MokuSpacing.Sp6),
            verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp5),
            content = content,
        )
    }
}

@Composable
fun SettingsGroup(label: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Xl)
    Column(verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
        if (label != null) MokuSectionLabel(label, Modifier.padding(horizontal = 2.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.bgSurface)
                .border(1.dp, colors.borderDim, shape),
            content = content,
        )
        if (footer != null) Text(footer, style = MokuText.meta, color = colors.textFaint, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
fun GroupDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 58.dp).height(1.dp).background(MokuTheme.colors.borderDim))
}

@Composable
fun IconTile(@DrawableRes icon: Int, tint: Color = MokuTheme.colors.textMuted, background: Color = MokuTheme.colors.bgOverlay) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(MokuRadius.Md)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun SettingsRow(
    title: String,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    value: String? = null,
    danger: Boolean = false,
    chevron: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = MokuTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = MokuSpacing.Sp4 - 2.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
    ) {
        when {
            leading != null -> leading()
            icon != null -> IconTile(icon, tint = if (danger) Danger else colors.textMuted, background = if (danger) Danger.copy(alpha = 0.12f) else colors.bgOverlay)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MokuText.body, color = if (danger) Danger else colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MokuText.meta, color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) Text(value, style = MokuText.sub, color = colors.textMuted, maxLines = 1)
        trailing?.invoke()
        if (chevron) Icon(painterResource(R.drawable.ph_caret_right_bold), contentDescription = null, tint = colors.textFaint, modifier = Modifier.size(12.dp))
    }
}

@Composable
fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
) {
    val haptics = rememberHaptics()
    SettingsRow(title = title, icon = icon, subtitle = subtitle, onClick = { haptics.tick(); onCheckedChange(!checked) }, trailing = { MokuSwitch(checked) })
}

@Composable
fun MokuSwitch(checked: Boolean) {
    val colors = MokuTheme.colors
    val track by animateColorAsState(if (checked) colors.accentDim else colors.bgOverlay, label = "track")
    val edge by animateColorAsState(if (checked) colors.accent else colors.borderBase, label = "edge")
    val thumb by animateColorAsState(if (checked) colors.accentBright else colors.textFaint, label = "thumb")
    val offset by animateDpAsState(if (checked) 18.dp else 0.dp, spring(dampingRatio = 0.6f, stiffness = 700f), label = "offset")
    val shape = RoundedCornerShape(MokuRadius.Lg)
    Box(
        Modifier
            .width(42.dp)
            .height(24.dp)
            .clip(shape)
            .background(track)
            .border(1.dp, edge, shape)
            .padding(3.dp),
    ) {
        Box(Modifier.offset(x = offset).size(18.dp).clip(RoundedCornerShape(MokuRadius.Md)).background(thumb))
    }
}

@Composable
fun ActionChip(text: String, @DrawableRes icon: Int? = null, accent: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Md)
    val (bg, edge, fg) = when {
        danger -> Triple(Danger.copy(alpha = 0.12f), Danger.copy(alpha = 0.4f), Danger)
        accent -> Triple(colors.accentMuted, colors.accentDim, colors.accentFg)
        else -> Triple(colors.bgRaised, colors.borderDim, colors.textMuted)
    }
    Row(
        Modifier
            .height(32.dp)
            .pressable(shape, pressedScale = 0.94f, onClick = onClick)
            .background(bg)
            .border(1.dp, edge, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
        Text(text, style = MokuText.meta, color = fg, maxLines = 1)
    }
}

@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Lg)
    val style = if (mono) MokuText.sub else MokuText.body
    Box(
        modifier
            .height(42.dp)
            .clip(shape)
            .background(colors.bgRaised)
            .border(1.dp, colors.borderDim, shape)
            .padding(horizontal = MokuSpacing.Sp3),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = style, color = colors.textFaint, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
