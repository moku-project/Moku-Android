package dev.moku.mobile.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.moku.mobile.library.ContentBlockLevel
import dev.moku.mobile.library.ContentFilterRule
import dev.moku.mobile.library.FilterField
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuTheme

private val LEVEL_LABELS = listOf("Off", "Moderate", "Strict")

/**
 * A compact "slide up" sheet for the local content filter — global block-level dial at top,
 * the keyword/genre rules it enforces below, and a one-line add form. Deliberately one sheet,
 * not a separate settings screen: this is meant to stay reachable from Search without leaving it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentFilterSheet(
    sheetState: SheetState,
    rules: List<ContentFilterRule>,
    onDismiss: () -> Unit,
    onLevelChange: (Int) -> Unit,
    onAddRule: (field: FilterField, keyword: String, level: ContentBlockLevel) -> Unit,
    onRemoveRule: (Long) -> Unit,
) {
    val colors = MokuTheme.colors
    var newKeyword by remember { mutableStateOf("") }
    var newField by remember { mutableStateOf(FilterField.GENRE) }
    val newLevel = ContentBlockLevel.MODERATE

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.bgSurface) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = MokuSpacing.Sp4, vertical = MokuSpacing.Sp2)) {
            Text("Content filter", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
            Text(
                "Hide results matching blocked genres or keywords.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(top = MokuSpacing.Sp1, bottom = MokuSpacing.Sp3),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                LEVEL_LABELS.forEachIndexed { index, label ->
                    FilterChip(label = label, selected = ContentFilterController.level == index, onClick = { onLevelChange(index) })
                }
            }

            if (rules.isNotEmpty()) {
                Text(
                    "Blocked",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textFaint,
                    modifier = Modifier.padding(top = MokuSpacing.Sp4, bottom = MokuSpacing.Sp2),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2),
                ) {
                    items(rules, key = { it.id }) { rule -> RuleRow(rule, onRemove = { onRemoveRule(rule.id) }) }
                }
            }

            Text(
                "Add rule",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textFaint,
                modifier = Modifier.padding(top = MokuSpacing.Sp4, bottom = MokuSpacing.Sp2),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                OutlinedTextField(
                    value = newKeyword,
                    onValueChange = { newKeyword = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("keyword", color = colors.textFaint) },
                    singleLine = true,
                    shape = RoundedCornerShape(MokuRadius.Md),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.borderBase,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        cursorColor = colors.accent,
                    ),
                )
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                        .clip(RoundedCornerShape(MokuRadius.Md))
                        .background(if (newKeyword.isNotBlank()) colors.accent else colors.bgRaised)
                        .clickable(enabled = newKeyword.isNotBlank()) {
                            onAddRule(newField, newKeyword.trim(), newLevel)
                            newKeyword = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add", tint = if (newKeyword.isNotBlank()) colors.bgVoid else colors.textFaint)
                }
            }
            Row(modifier = Modifier.padding(top = MokuSpacing.Sp2), horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                FilterField.entries.filter { it != FilterField.TAG }.forEach { field ->
                    FilterChip(label = field.name.lowercase().replaceFirstChar(Char::uppercase), selected = newField == field, onClick = { newField = field })
                }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: ContentFilterRule, onRemove: () -> Unit) {
    val colors = MokuTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MokuRadius.Md))
            .background(colors.bgRaised)
            .padding(horizontal = MokuSpacing.Sp3, vertical = MokuSpacing.Sp2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(rule.keyword, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${rule.field.name.lowercase()} · ${rule.blockLevel.name.lowercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        Box(
            modifier = Modifier.defaultMinSize(minWidth = 36.dp, minHeight = 36.dp).clip(RoundedCornerShape(MokuRadius.Md)).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = colors.textFaint, modifier = Modifier.padding(6.dp))
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 36.dp)
            .clip(RoundedCornerShape(MokuRadius.Full))
            .background(if (selected) colors.accent else colors.bgRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = MokuSpacing.Sp3, vertical = MokuSpacing.Sp1),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) colors.bgVoid else colors.textSecondary)
    }
}
