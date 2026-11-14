package dev.moku.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

enum class MokuThemeId(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    MIDNIGHT("Midnight"),
    ORIGINAL("Original"),
    WARM("Warm"),
}

data class MokuPalette(
    val isLight: Boolean,
    val bgVoid: Color,
    val bgBase: Color,
    val bgSurface: Color,
    val bgRaised: Color,
    val bgOverlay: Color,
    val bgSubtle: Color,
    val borderDim: Color,
    val borderBase: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val accentDim: Color,
    val accentMuted: Color,
    val accentFg: Color,
    val accentBright: Color,
)

object MokuPalettes {
    val Dark = MokuPalette(
        isLight = false,
        bgVoid = Color(0xFF000000), bgBase = Color(0xFF080808), bgSurface = Color(0xFF0D0D0D),
        bgRaised = Color(0xFF111111), bgOverlay = Color(0xFF171717), bgSubtle = Color(0xFF1E1E1E),
        borderDim = Color(0xFF252525), borderBase = Color(0xFF303030), borderStrong = Color(0xFF3E3E3E),
        textPrimary = Color(0xFFFFFFFF), textSecondary = Color(0xFFE8E6E0), textMuted = Color(0xFFB0AEA8), textFaint = Color(0xFF6E6C68),
        accent = Color(0xFF7AAA7A), accentDim = Color(0xFF2E4A2E), accentMuted = Color(0xFF1E2E1E), accentFg = Color(0xFFBCD8BC), accentBright = Color(0xFF9FCF9F),
    )

    val Light = MokuPalette(
        isLight = true,
        bgVoid = Color(0xFFD8D4CE), bgBase = Color(0xFFE2DEDA), bgSurface = Color(0xFFECE8E2),
        bgRaised = Color(0xFFF5F2EC), bgOverlay = Color(0xFFFFFFFF), bgSubtle = Color(0xFFE4E0D8),
        borderDim = Color(0xFFC4C0B8), borderBase = Color(0xFFB0ACA4), borderStrong = Color(0xFF989490),
        textPrimary = Color(0xFF080806), textSecondary = Color(0xFF181612), textMuted = Color(0xFF38342E), textFaint = Color(0xFF706C64),
        accent = Color(0xFF3F9142), accentDim = Color(0xFFA8D0A8), accentMuted = Color(0xFFCFE6CF), accentFg = Color(0xFF2F8A34), accentBright = Color(0xFF46A94B),
    )

    val Midnight = MokuPalette(
        isLight = false,
        bgVoid = Color(0xFF050810), bgBase = Color(0xFF080C18), bgSurface = Color(0xFF0C1020),
        bgRaised = Color(0xFF101428), bgOverlay = Color(0xFF151A30), bgSubtle = Color(0xFF1A2038),
        borderDim = Color(0xFF1A2035), borderBase = Color(0xFF222840), borderStrong = Color(0xFF2C3450),
        textPrimary = Color(0xFFEEEEF8), textSecondary = Color(0xFFC0C4D8), textMuted = Color(0xFF808498), textFaint = Color(0xFF404860),
        accent = Color(0xFF6A7AB8), accentDim = Color(0xFF252D50), accentMuted = Color(0xFF181E38), accentFg = Color(0xFFA8B4E8), accentBright = Color(0xFF8896D0),
    )

    val Original = MokuPalette(
        isLight = false,
        bgVoid = Color(0xFF080808), bgBase = Color(0xFF0C0C0C), bgSurface = Color(0xFF101010),
        bgRaised = Color(0xFF151515), bgOverlay = Color(0xFF1A1A1A), bgSubtle = Color(0xFF202020),
        borderDim = Color(0xFF1C1C1C), borderBase = Color(0xFF242424), borderStrong = Color(0xFF2E2E2E),
        textPrimary = Color(0xFFF0EFEC), textSecondary = Color(0xFFC8C6C0), textMuted = Color(0xFF8A8880), textFaint = Color(0xFF4E4D4A),
        accent = Color(0xFF6B8F6B), accentDim = Color(0xFF2A3D2A), accentMuted = Color(0xFF1A251A), accentFg = Color(0xFFA8C4A8), accentBright = Color(0xFF8FB88F),
    )

    val Warm = MokuPalette(
        isLight = false,
        bgVoid = Color(0xFF0C0A06), bgBase = Color(0xFF100E08), bgSurface = Color(0xFF16130C),
        bgRaised = Color(0xFF1C1810), bgOverlay = Color(0xFF221E14), bgSubtle = Color(0xFF28241A),
        borderDim = Color(0xFF201C10), borderBase = Color(0xFF2C2818), borderStrong = Color(0xFF3A3420),
        textPrimary = Color(0xFFF5F0E0), textSecondary = Color(0xFFD8D0B0), textMuted = Color(0xFF988C60), textFaint = Color(0xFF584E30),
        accent = Color(0xFFC0902A), accentDim = Color(0xFF3A2C10), accentMuted = Color(0xFF261E0C), accentFg = Color(0xFFE0B860), accentBright = Color(0xFFD0A040),
    )

    fun of(id: MokuThemeId): MokuPalette = when (id) {
        MokuThemeId.DARK -> Dark
        MokuThemeId.LIGHT -> Light
        MokuThemeId.MIDNIGHT -> Midnight
        MokuThemeId.ORIGINAL -> Original
        MokuThemeId.WARM -> Warm
    }
}

data class ThemeToken(val key: String, val label: String, val get: (MokuPalette) -> Color, val set: (MokuPalette, Color) -> MokuPalette)

data class ThemeTokenGroup(val label: String, val tokens: List<ThemeToken>)

// Keys match desktop Moku's ThemeTokens, so theme files move between the two apps.
val ThemeTokenGroups = listOf(
    ThemeTokenGroup("Backgrounds", listOf(
        ThemeToken("bg-void", "Void", { it.bgVoid }) { p, c -> p.copy(bgVoid = c) },
        ThemeToken("bg-base", "Base", { it.bgBase }) { p, c -> p.copy(bgBase = c) },
        ThemeToken("bg-surface", "Surface", { it.bgSurface }) { p, c -> p.copy(bgSurface = c) },
        ThemeToken("bg-raised", "Raised", { it.bgRaised }) { p, c -> p.copy(bgRaised = c) },
        ThemeToken("bg-overlay", "Overlay", { it.bgOverlay }) { p, c -> p.copy(bgOverlay = c) },
        ThemeToken("bg-subtle", "Subtle", { it.bgSubtle }) { p, c -> p.copy(bgSubtle = c) },
    )),
    ThemeTokenGroup("Borders", listOf(
        ThemeToken("border-dim", "Dim", { it.borderDim }) { p, c -> p.copy(borderDim = c) },
        ThemeToken("border-base", "Base", { it.borderBase }) { p, c -> p.copy(borderBase = c) },
        ThemeToken("border-strong", "Strong", { it.borderStrong }) { p, c -> p.copy(borderStrong = c) },
    )),
    ThemeTokenGroup("Text", listOf(
        ThemeToken("text-primary", "Primary", { it.textPrimary }) { p, c -> p.copy(textPrimary = c) },
        ThemeToken("text-secondary", "Secondary", { it.textSecondary }) { p, c -> p.copy(textSecondary = c) },
        ThemeToken("text-muted", "Muted", { it.textMuted }) { p, c -> p.copy(textMuted = c) },
        ThemeToken("text-faint", "Faint", { it.textFaint }) { p, c -> p.copy(textFaint = c) },
    )),
    ThemeTokenGroup("Accent", listOf(
        ThemeToken("accent", "Accent", { it.accent }) { p, c -> p.copy(accent = c) },
        ThemeToken("accent-dim", "Dim", { it.accentDim }) { p, c -> p.copy(accentDim = c) },
        ThemeToken("accent-muted", "Muted", { it.accentMuted }) { p, c -> p.copy(accentMuted = c) },
        ThemeToken("accent-fg", "Foreground", { it.accentFg }) { p, c -> p.copy(accentFg = c) },
        ThemeToken("accent-bright", "Bright", { it.accentBright }) { p, c -> p.copy(accentBright = c) },
    )),
)

private val AllTokens = ThemeTokenGroups.flatMap { it.tokens }

fun MokuPalette.toTokens(): Map<String, String> = AllTokens.associate { it.key to it.get(this).toHex() }

fun MokuPalette.withTokens(tokens: Map<String, String>): MokuPalette =
    AllTokens.fold(this) { palette, token -> tokens[token.key]?.let(::parseHexColor)?.let { token.set(palette, it) } ?: palette }
        .withDerivedLightness()

fun MokuPalette.withDerivedLightness(): MokuPalette = copy(isLight = bgBase.luminance() > 0.5f)

fun Color.toHex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

// CSS hex: #rgb, #rrggbb or #rrggbbaa.
fun parseHexColor(raw: String): Color? {
    val hex = raw.trim().removePrefix("#")
    if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    val full = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("")
        6, 8 -> hex
        else -> return null
    }
    val rgb = full.substring(0, 6).toLong(16)
    val alpha = if (full.length == 8) full.substring(6, 8).toInt(16) else 0xFF
    return Color(((alpha.toLong() shl 24) or rgb).toInt())
}
