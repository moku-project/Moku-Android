package dev.moku.mobile.ui.theme

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val PREFS_NAME = "moku_theme_prefs"
private const val KEY_THEME = "theme_id"

object ThemeController {
    var current by mutableStateOf(MokuThemeId.ORIGINAL)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_THEME, null)
        current = stored?.let { runCatching { MokuThemeId.valueOf(it) }.getOrNull() } ?: MokuThemeId.ORIGINAL
    }

    fun setTheme(context: Context, id: MokuThemeId) {
        current = id
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME, id.name)
            .apply()
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

@Composable
fun MokuAppTheme(themeId: MokuThemeId = ThemeController.current, content: @Composable () -> Unit) {
    val palette = MokuPalettes.of(themeId)
    CompositionLocalProvider(LocalMokuPalette provides palette) {
        MaterialTheme(
            colorScheme = colorSchemeFor(palette),
            typography = MokuTypography,
            shapes = MokuShapes,
            content = content,
        )
    }
}
