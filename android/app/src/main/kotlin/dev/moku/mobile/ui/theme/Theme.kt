package dev.moku.mobile.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val PREFS_NAME = "moku_theme_prefs"
private const val KEY_THEME = "theme_id"
private const val KEY_FOLLOW_SYSTEM = "follow_system"
private const val KEY_CUSTOM = "custom_themes"

data class CustomTheme(val id: String, val name: String, val palette: MokuPalette)

object ThemeController {
    var selectedId by mutableStateOf(MokuThemeId.ORIGINAL.name)
        private set
    var followSystem by mutableStateOf(false)
        private set
    val customThemes = mutableStateListOf<CustomTheme>()

    fun init(context: Context) {
        val prefs = prefs(context)
        followSystem = prefs.getBoolean(KEY_FOLLOW_SYSTEM, false)
        customThemes.clear()
        runCatching {
            val array = JSONArray(prefs.getString(KEY_CUSTOM, "[]"))
            for (i in 0 until array.length()) customThemes += themeFromJson(array.getJSONObject(i)) ?: continue
        }
        selectedId = prefs.getString(KEY_THEME, null)?.takeIf { exists(it) } ?: MokuThemeId.ORIGINAL.name
    }

    fun select(context: Context, id: String) {
        selectedId = id
        prefs(context).edit().putString(KEY_THEME, id).apply()
    }

    fun setFollowSystem(context: Context, value: Boolean) {
        followSystem = value
        prefs(context).edit().putBoolean(KEY_FOLLOW_SYSTEM, value).apply()
    }

    fun newId(): String = "custom:" + UUID.randomUUID().toString().take(8)

    fun saveCustom(context: Context, theme: CustomTheme) {
        val index = customThemes.indexOfFirst { it.id == theme.id }
        if (index >= 0) customThemes[index] = theme else customThemes += theme
        persistCustom(context)
    }

    fun deleteCustom(context: Context, id: String) {
        customThemes.removeAll { it.id == id }
        persistCustom(context)
        if (selectedId == id) select(context, MokuThemeId.ORIGINAL.name)
    }

    fun paletteFor(id: String): MokuPalette =
        builtIn(id)?.let(MokuPalettes::of) ?: customThemes.firstOrNull { it.id == id }?.palette ?: MokuPalettes.Original

    fun nameFor(id: String): String = builtIn(id)?.label ?: customThemes.firstOrNull { it.id == id }?.name ?: "Original"

    fun active(systemDark: Boolean): MokuPalette =
        if (followSystem && !systemDark) MokuPalettes.Light else paletteFor(selectedId)

    fun exportJson(name: String, palette: MokuPalette): String =
        JSONObject().put("name", name).put("tokens", JSONObject(palette.toTokens())).toString(2)

    fun importJson(json: String): CustomTheme {
        val obj = JSONObject(json)
        val tokens = obj.optJSONObject("tokens") ?: error("Not a Moku theme file: no tokens")
        val name = obj.optString("name").ifBlank { "Imported theme" }
        val map = tokens.keys().asSequence().associateWith { tokens.optString(it) }
        return CustomTheme(newId(), name, MokuPalettes.Original.withTokens(map))
    }

    private fun builtIn(id: String) = MokuThemeId.entries.firstOrNull { it.name == id }

    private fun exists(id: String) = builtIn(id) != null || customThemes.any { it.id == id }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun persistCustom(context: Context) {
        val array = JSONArray()
        customThemes.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("tokens", JSONObject(it.palette.toTokens()))) }
        prefs(context).edit().putString(KEY_CUSTOM, array.toString()).apply()
    }

    private fun themeFromJson(obj: JSONObject): CustomTheme? {
        val tokens = obj.optJSONObject("tokens") ?: return null
        val map = tokens.keys().asSequence().associateWith { tokens.optString(it) }
        return CustomTheme(obj.optString("id").ifBlank { newId() }, obj.optString("name", "Custom"), MokuPalettes.Original.withTokens(map))
    }
}

val LocalMokuPalette = compositionLocalOf { MokuPalettes.Original }

object MokuTheme {
    val colors: MokuPalette
        @Composable get() = LocalMokuPalette.current
}

private fun colorSchemeFor(palette: MokuPalette) = if (palette.isLight) {
    lightColorScheme(
        primary = palette.accent, onPrimary = palette.bgVoid,
        primaryContainer = palette.accentDim, onPrimaryContainer = palette.accentFg,
        secondary = palette.accentBright, onSecondary = palette.bgVoid,
        background = palette.bgVoid, onBackground = palette.textPrimary,
        surface = palette.bgSurface, onSurface = palette.textPrimary,
        surfaceVariant = palette.bgRaised, onSurfaceVariant = palette.textSecondary,
        surfaceContainer = palette.bgSurface, surfaceContainerLow = palette.bgBase,
        surfaceContainerHigh = palette.bgRaised, surfaceContainerHighest = palette.bgOverlay,
        outline = palette.borderBase, outlineVariant = palette.borderDim,
    )
} else {
    darkColorScheme(
        primary = palette.accent, onPrimary = palette.bgVoid,
        primaryContainer = palette.accentDim, onPrimaryContainer = palette.accentFg,
        secondary = palette.accentBright, onSecondary = palette.bgVoid,
        background = palette.bgVoid, onBackground = palette.textPrimary,
        surface = palette.bgSurface, onSurface = palette.textPrimary,
        surfaceVariant = palette.bgRaised, onSurfaceVariant = palette.textSecondary,
        surfaceContainer = palette.bgSurface, surfaceContainerLow = palette.bgBase,
        surfaceContainerHigh = palette.bgRaised, surfaceContainerHighest = palette.bgOverlay,
        outline = palette.borderBase, outlineVariant = palette.borderDim,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MokuAppTheme(content: @Composable () -> Unit) {
    val palette = ThemeController.active(isSystemInDarkTheme())
    CompositionLocalProvider(LocalMokuPalette provides palette) {
        MaterialTheme(
            colorScheme = colorSchemeFor(palette),
            typography = MokuTypography,
            shapes = MokuShapes,
        ) {
            CompositionLocalProvider(
                LocalRippleConfiguration provides RippleConfiguration(
                    color = palette.textPrimary,
                    rippleAlpha = RippleAlpha(draggedAlpha = 0.14f, focusedAlpha = 0.1f, hoveredAlpha = 0.06f, pressedAlpha = 0.14f),
                ),
                content = content,
            )
        }
    }
}
