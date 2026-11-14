package dev.moku.mobile.ui.settings

import dev.moku.mobile.ui.components.pressable
import android.content.Intent
import android.net.Uri
import dev.moku.mobile.ui.components.MokuToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.moku.mobile.R
import dev.moku.mobile.ui.components.MokuChip
import dev.moku.mobile.ui.components.MokuHeaderAction
import dev.moku.mobile.ui.components.MokuSectionLabel
import dev.moku.mobile.ui.theme.CustomTheme
import dev.moku.mobile.ui.theme.MokuPalette
import dev.moku.mobile.ui.theme.MokuPalettes
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme
import dev.moku.mobile.ui.theme.MokuThemeId
import dev.moku.mobile.ui.theme.ThemeController
import dev.moku.mobile.ui.theme.ThemeToken
import dev.moku.mobile.ui.theme.ThemeTokenGroups
import dev.moku.mobile.ui.theme.parseHexColor
import dev.moku.mobile.ui.theme.toHex
import dev.moku.mobile.ui.theme.withDerivedLightness

@Composable
fun ThemePreview(p: MokuPalette, modifier: Modifier = Modifier) {
    @Composable
    fun Line(width: Dp, color: Color) = Box(Modifier.width(width).height(5.dp).clip(RoundedCornerShape(2.dp)).background(color))

    Column(modifier.background(p.bgBase).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Line(34.dp, p.textPrimary)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(p.bgRaised).border(1.dp, p.borderDim, RoundedCornerShape(3.dp)))
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(p.bgSurface).border(1.dp, p.borderDim, RoundedCornerShape(4.dp)).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(width = 15.dp, height = 22.dp).clip(RoundedCornerShape(2.dp)).background(p.bgOverlay))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Line(40.dp, p.textSecondary)
                Line(26.dp, p.textFaint)
                Box(Modifier.size(width = 22.dp, height = 7.dp).clip(RoundedCornerShape(2.dp)).background(p.accentMuted).border(1.dp, p.accentDim, RoundedCornerShape(2.dp)))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(p.bgRaised).border(1.dp, p.borderDim, RoundedCornerShape(4.dp)).padding(3.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            repeat(4) { i ->
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(if (i == 0) p.accent else p.textFaint.copy(alpha = 0.35f)))
            }
        }
    }
}

@Composable
private fun ThemeCard(name: String, palette: MokuPalette, selected: Boolean, onClick: () -> Unit, onEdit: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Xl)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
        ThemePreview(
            palette,
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .clip(shape)
                .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.borderDim, shape)
                .clickable(onClick = onClick),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = MokuText.caption, color = if (selected) colors.textPrimary else colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (selected) Icon(painterResource(R.drawable.ph_check_bold), contentDescription = "Selected", tint = colors.accentFg, modifier = Modifier.size(12.dp))
            if (onEdit != null) {
                Icon(
                    painterResource(R.drawable.ph_pencil_simple_light),
                    contentDescription = "Edit $name",
                    tint = colors.textMuted,
                    modifier = Modifier.size(26.dp).clip(RoundedCornerShape(MokuRadius.Sm)).clickable(onClick = onEdit).padding(5.dp),
                )
            }
        }
    }
}

@Composable
private fun NewThemeCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Xl)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
        Column(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .pressable(shape, onClick = onClick)
                .background(colors.bgSurface)
                .border(1.dp, colors.borderBase, shape),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            IconTile(R.drawable.ph_plus_light, tint = colors.accentFg, background = colors.accentMuted)
            Text("New theme", style = MokuText.meta, color = colors.textMuted)
        }
        Text(" ", style = MokuText.caption)
    }
}

private sealed interface Tile {
    data class BuiltIn(val id: MokuThemeId) : Tile
    data class Custom(val theme: CustomTheme) : Tile
    data object New : Tile
}

@Composable
fun AppearancePage(onBack: () -> Unit, onEditTheme: (String?) -> Unit) {
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val json = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val theme = ThemeController.importJson(json)
            ThemeController.saveCustom(context, theme)
            ThemeController.select(context, theme.id)
            MokuToast.show("Imported ${theme.name}")
        }.onFailure { MokuToast.show(it.message ?: "Couldn't read that theme file") }
    }

    SettingsPage("Appearance", onBack = onBack, trailing = {
        MokuHeaderAction(R.drawable.ph_download_simple_light, "Import theme") { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }
    }) {
        item {
            SettingsGroup(footer = "Uses Light while your phone is in light mode, and your theme below otherwise.") {
                SettingsToggleRow(
                    title = "Match system",
                    icon = R.drawable.ph_device_mobile_light,
                    checked = ThemeController.followSystem,
                    onCheckedChange = { ThemeController.setFollowSystem(context, it) },
                )
            }
        }
        item { MokuSectionLabel("Built-in", Modifier.padding(horizontal = 2.dp)) }
        themeGrid(MokuThemeId.entries.map { Tile.BuiltIn(it) }, onEditTheme)
        item { MokuSectionLabel("Custom", Modifier.padding(horizontal = 2.dp)) }
        themeGrid(ThemeController.customThemes.map { Tile.Custom(it) } + Tile.New, onEditTheme)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.themeGrid(tiles: List<Tile>, onEditTheme: (String?) -> Unit) {
    items(tiles.chunked(2).size) { row ->
        val context = LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
            tiles.chunked(2)[row].forEach { tile ->
                val mod = Modifier.weight(1f)
                when (tile) {
                    is Tile.BuiltIn -> ThemeCard(
                        name = tile.id.label,
                        palette = MokuPalettes.of(tile.id),
                        selected = ThemeController.selectedId == tile.id.name,
                        onClick = { ThemeController.select(context, tile.id.name) },
                        modifier = mod,
                    )
                    is Tile.Custom -> ThemeCard(
                        name = tile.theme.name,
                        palette = tile.theme.palette,
                        selected = ThemeController.selectedId == tile.theme.id,
                        onClick = { ThemeController.select(context, tile.theme.id) },
                        onEdit = { onEditTheme(tile.theme.id) },
                        modifier = mod,
                    )
                    Tile.New -> NewThemeCard(onClick = { onEditTheme(null) }, modifier = mod)
                }
            }
            if (tiles.chunked(2)[row].size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThemeEditorPage(editingId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MokuTheme.colors
    val existing = remember(editingId) { ThemeController.customThemes.firstOrNull { it.id == editingId } }
    var name by remember { mutableStateOf(existing?.name ?: "My theme") }
    var palette by remember { mutableStateOf(existing?.palette ?: ThemeController.paletteFor(ThemeController.selectedId)) }
    var picking by remember { mutableStateOf<ThemeToken?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() {
        val theme = CustomTheme(existing?.id ?: ThemeController.newId(), name.trim().ifBlank { "My theme" }, palette.withDerivedLightness())
        ThemeController.saveCustom(context, theme)
        ThemeController.select(context, theme.id)
        onBack()
    }

    SettingsPage(if (existing == null) "New theme" else "Edit theme", onBack = onBack, trailing = {
        MokuHeaderAction(R.drawable.ph_check_bold, "Save", active = true) { save() }
    }) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
                TextInput(name, { name = it }, "Theme name", Modifier.fillMaxWidth())
                ThemePreview(
                    palette,
                    Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(MokuRadius.Xl))
                        .border(1.dp, colors.borderDim, RoundedCornerShape(MokuRadius.Xl)),
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                MokuSectionLabel("Start from", Modifier.padding(horizontal = 2.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                    MokuThemeId.entries.forEach { id -> MokuChip(id.label, selected = false) { palette = MokuPalettes.of(id) } }
                }
            }
        }
        ThemeTokenGroups.forEach { group ->
            item {
                SettingsGroup(group.label) {
                    group.tokens.forEachIndexed { i, token ->
                        if (i > 0) GroupDivider()
                        val color = token.get(palette)
                        SettingsRow(
                            title = token.label,
                            value = color.toHex(),
                            onClick = { picking = token },
                            leading = {
                                Box(Modifier.size(30.dp).clip(RoundedCornerShape(MokuRadius.Md)).background(color).border(1.dp, colors.borderBase, RoundedCornerShape(MokuRadius.Md)))
                            },
                        )
                    }
                }
            }
        }
        item {
            SettingsGroup {
                SettingsRow("Share theme", icon = R.drawable.ph_share_network_light, subtitle = "Send it as a file desktop Moku can import too", onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, ThemeController.exportJson(name, palette))
                    context.startActivity(Intent.createChooser(send, "Share theme"))
                })
                if (existing != null) {
                    GroupDivider()
                    SettingsRow(
                        if (confirmDelete) "Tap again to delete" else "Delete theme",
                        icon = R.drawable.ph_trash_light,
                        danger = true,
                        onClick = {
                            if (confirmDelete) {
                                ThemeController.deleteCustom(context, existing.id)
                                onBack()
                            } else confirmDelete = true
                        },
                    )
                }
            }
        }
    }

    picking?.let { token ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { picking = null }, sheetState = sheetState, containerColor = colors.bgSurface) {
            ColorPicker(
                title = token.label,
                initial = token.get(palette),
                onChange = { palette = token.set(palette, it) },
                onDone = { picking = null },
            )
        }
    }
}

@Composable
private fun ColorPicker(title: String, initial: Color, onChange: (Color) -> Unit, onDone: () -> Unit) {
    val colors = MokuTheme.colors
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val current = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    var hex by remember { mutableStateOf(initial.toHex()) }

    fun push() {
        val c = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
        hex = c.toHex()
        onChange(c)
    }

    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = MokuSpacing.Sp4, end = MokuSpacing.Sp4, bottom = MokuSpacing.Sp6),
        verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
            Row(Modifier.clip(RoundedCornerShape(MokuRadius.Lg)).border(1.dp, colors.borderBase, RoundedCornerShape(MokuRadius.Lg))) {
                Box(Modifier.size(width = 28.dp, height = 40.dp).background(initial))
                Box(Modifier.size(width = 40.dp, height = 40.dp).background(current))
            }
            Text(title, style = MokuText.heading, color = colors.textPrimary, modifier = Modifier.weight(1f))
            ActionChip("Done", accent = true, onClick = onDone)
        }
        GradientSlider("Hue", hue / 360f, Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }), current) {
            hue = it * 360f; push()
        }
        GradientSlider(
            "Saturation",
            sat,
            Brush.horizontalGradient(listOf(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0f, value))), Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, value))))),
            current,
        ) { sat = it; push() }
        GradientSlider(
            "Brightness",
            value,
            Brush.horizontalGradient(listOf(Color.Black, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 1f))))),
            current,
        ) { value = it; push() }
        TextInput(
            value = hex,
            onValueChange = { input ->
                hex = input
                parseHexColor(input)?.let { c ->
                    val out = FloatArray(3)
                    android.graphics.Color.colorToHSV(c.toArgb(), out)
                    hue = out[0]; sat = out[1]; value = out[2]
                    onChange(c)
                }
            },
            placeholder = "#RRGGBB",
            mono = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GradientSlider(label: String, fraction: Float, brush: Brush, thumb: Color, onChange: (Float) -> Unit) {
    val colors = MokuTheme.colors
    val update by rememberUpdatedState(onChange)
    val shape = RoundedCornerShape(MokuRadius.Md)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MokuSectionLabel(label)
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(shape)
                .background(brush)
                .border(1.dp, colors.borderBase, shape)
                .pointerInput(Unit) { detectTapGestures { update((it.x / size.width).coerceIn(0f, 1f)) } }
                .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> update((change.position.x / size.width).coerceIn(0f, 1f)) } },
        ) {
            val knob = 14.dp
            Box(
                Modifier
                    .offset(x = (maxWidth - knob) * fraction.coerceIn(0f, 1f))
                    .width(knob)
                    .fillMaxHeight()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(MokuRadius.Sm))
                    .background(Color.White)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(thumb),
            )
        }
    }
}
