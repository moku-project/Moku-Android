package dev.moku.mobile.ui.library

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.moku.mobile.R
import dev.moku.mobile.library.FolderEntity
import dev.moku.mobile.ui.components.MokuSectionLabel
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme

private val Danger = Color(0xFFE5675F)

@Composable
internal fun SheetBody(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = MokuTheme.colors
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = MokuSpacing.Sp4, end = MokuSpacing.Sp4, bottom = MokuSpacing.Sp6),
        verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MokuText.heading, color = colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing?.invoke()
        }
        content()
    }
}

@Composable
internal fun SheetRow(
    text: String,
    selected: Boolean,
    @DrawableRes icon: Int? = null,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = MokuTheme.colors
    val tint = if (danger) Danger else if (selected) colors.accentFg else colors.textSecondary
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(MokuRadius.Md))
            .clickable(onClick = onClick)
            .padding(horizontal = MokuSpacing.Sp2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = if (danger) tint else colors.textMuted, modifier = Modifier.size(18.dp))
        Text(text, style = MokuText.body, color = tint, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
        if (selected) Icon(painterResource(R.drawable.ph_check_bold), contentDescription = null, tint = colors.accentFg, modifier = Modifier.size(14.dp))
    }
}

/** Which folders one title sits in; tapping a row toggles membership. */
@Composable
internal fun FolderPicker(
    folders: List<FolderEntity>,
    memberOf: Set<Long>,
    onToggle: (FolderEntity, Boolean) -> Unit,
    onCreate: (String) -> Unit,
) {
    MokuSectionLabel("Folders")
    Column {
        folders.forEach { folder ->
            val member = folder.id in memberOf
            SheetRow(folder.name, selected = member, icon = R.drawable.ph_folder_simple_light) { onToggle(folder, member) }
        }
        NameField(placeholder = "New folder", action = "Add", icon = R.drawable.ph_folder_simple_plus_light, onSubmit = onCreate)
    }
}

/** Create, rename, reorder and delete folders. Deleting a folder never removes its titles from the library. */
@Composable
internal fun ManageFolders(
    folders: List<FolderEntity>,
    counts: Map<Long, Int>,
    onCreate: (String) -> Unit,
    onRename: (FolderEntity, String) -> Unit,
    onDelete: (FolderEntity) -> Unit,
    onMove: (FolderEntity, Int) -> Unit,
) {
    val colors = MokuTheme.colors
    var editing by remember { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    SheetBody("Folders") {
        if (folders.isEmpty()) {
            Text("Folders group titles into tabs at the top of your library.", style = MokuText.meta, color = colors.textFaint)
        }
        Column {
            folders.forEachIndexed { index, folder ->
                if (editing == folder.id) {
                    NameField(
                        placeholder = "Folder name",
                        action = "Save",
                        icon = R.drawable.ph_pencil_simple_light,
                        initial = folder.name,
                        autoFocus = true,
                        onSubmit = { onRename(folder, it); editing = null },
                    )
                    return@forEachIndexed
                }
                Row(
                    Modifier.fillMaxWidth().height(46.dp).padding(start = MokuSpacing.Sp2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2),
                ) {
                    Icon(painterResource(R.drawable.ph_folder_simple_light), contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
                    Text(folder.name, style = MokuText.body, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = MokuSpacing.Sp1))
                    Text("${counts[folder.id] ?: 0}", style = MokuText.meta, color = colors.textFaint)
                    RowIcon(R.drawable.ph_caret_up_bold, "Move up", enabled = index > 0) { onMove(folder, -1) }
                    RowIcon(R.drawable.ph_caret_down_bold, "Move down", enabled = index < folders.lastIndex) { onMove(folder, 1) }
                    RowIcon(R.drawable.ph_pencil_simple_light, "Rename") { editing = folder.id; confirmDelete = null }
                    if (confirmDelete == folder.id) {
                        Text(
                            "Delete",
                            style = MokuText.meta,
                            color = Danger,
                            modifier = Modifier
                                .clip(RoundedCornerShape(MokuRadius.Md))
                                .background(Danger.copy(alpha = 0.12f))
                                .clickable { onDelete(folder); confirmDelete = null }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    } else {
                        RowIcon(R.drawable.ph_trash_light, "Delete folder") { confirmDelete = folder.id }
                    }
                }
            }
            NameField(placeholder = "New folder", action = "Add", icon = R.drawable.ph_folder_simple_plus_light, onSubmit = onCreate)
        }
    }
}

@Composable
private fun RowIcon(@DrawableRes icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(MokuRadius.Md)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = if (enabled) colors.textMuted else colors.textFaint.copy(alpha = 0.4f), modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun NameField(
    placeholder: String,
    action: String,
    @DrawableRes icon: Int,
    initial: String = "",
    autoFocus: Boolean = false,
    onSubmit: (String) -> Unit,
) {
    val colors = MokuTheme.colors
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun submit() {
        val name = value.trim()
        if (name.isEmpty()) return
        onSubmit(name)
        value = ""
    }
    val shape = RoundedCornerShape(MokuRadius.Md)
    Row(
        Modifier.fillMaxWidth().height(46.dp).padding(horizontal = MokuSpacing.Sp2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MokuText.body, color = colors.textFaint)
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = MokuText.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (value.isNotBlank()) {
            Text(
                action,
                style = MokuText.meta,
                color = colors.accentFg,
                modifier = Modifier
                    .clip(shape)
                    .background(colors.accentMuted)
                    .border(1.dp, colors.accentDim, shape)
                    .clickable { submit() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
