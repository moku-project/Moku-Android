package dev.moku.mobile.ui.reader

import dev.moku.mobile.ui.components.pressable
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.moku.mobile.R
import dev.moku.mobile.backend.BackendUnit
import dev.moku.mobile.ui.settings.ActionChip
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme

internal class ReaderNav(
    val title: String,
    val word: String,
    val current: BackendUnit?,
    val prev: BackendUnit?,
    val next: BackendUnit?,
    val onPrev: () -> Unit,
    val onNext: () -> Unit,
    val onBack: () -> Unit,
) {
    val unitLabel get() = current?.label(word) ?: word
    fun short(unit: BackendUnit?) = unit?.num()?.let { "$word ${formatNumber(it)}" } ?: unit?.label(word)
}

internal enum class ReadingMode(val label: String, @DrawableRes val icon: Int) {
    WEBTOON("Vertical", R.drawable.ph_rows_light),
    LTR("Left to right", R.drawable.ph_arrow_right_light),
    RTL("Right to left", R.drawable.ph_arrow_left_light),
}

internal enum class NovelFont(val label: String) { SANS("Sans"), SERIF("Serif") }

internal enum class NovelTheme(val label: String, val background: Color, val text: Color) {
    PAPER("Paper", Color(0xFFF5F2E9), Color(0xFF2B2622)),
    SEPIA("Sepia", Color(0xFFEFE3C8), Color(0xFF4A3C28)),
    DARK("Dark", Color(0xFF14140F), Color(0xFFCBC6BD)),
}

internal enum class NovelAlign(val label: String) { LEFT("Left"), JUSTIFY("Justify") }

internal object ReaderPrefs {
    private var prefs: SharedPreferences? = null
    private var defaultMode by mutableStateOf(ReadingMode.WEBTOON)
    private val modes = mutableStateOf(emptyMap<String, ReadingMode>())
    var textSize by mutableFloatStateOf(17f)
        private set
    var lineHeight by mutableFloatStateOf(1.7f)
        private set
    var paraSpacing by mutableFloatStateOf(1.1f)
        private set
    var font by mutableStateOf(NovelFont.SERIF)
        private set
    var theme by mutableStateOf(NovelTheme.PAPER)
        private set
    var align by mutableStateOf(NovelAlign.LEFT)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("moku_reader_prefs", Context.MODE_PRIVATE)
        prefs = p
        defaultMode = p.enum("mode", ReadingMode.WEBTOON)
        modes.value = p.all.filterKeys { it.startsWith("mode|") }.mapNotNull { (k, v) ->
            runCatching { k.removePrefix("mode|") to ReadingMode.valueOf(v as String) }.getOrNull()
        }.toMap()
        textSize = p.getFloat("textSize", 17f)
        lineHeight = p.getFloat("lineHeight", 1.7f)
        paraSpacing = p.getFloat("paraSpacing", 1.1f)
        font = p.enum("font", NovelFont.SERIF)
        theme = p.enum("novelTheme", NovelTheme.PAPER)
        align = p.enum("align", NovelAlign.LEFT)
    }

    private inline fun <reified E : Enum<E>> SharedPreferences.enum(key: String, default: E): E =
        getString(key, null)?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    fun mode(series: String): ReadingMode = modes.value[series] ?: defaultMode

    fun setMode(series: String, mode: ReadingMode) {
        defaultMode = mode
        modes.value = modes.value + (series to mode)
        prefs?.edit()?.putString("mode", mode.name)?.putString("mode|$series", mode.name)?.apply()
    }

    fun updateTextSize(value: Float) {
        textSize = value.coerceIn(12f, 28f)
        prefs?.edit()?.putFloat("textSize", textSize)?.apply()
    }

    fun updateLineHeight(value: Float) {
        lineHeight = (Math.round(value.coerceIn(1.2f, 2.4f) * 10f) / 10f)
        prefs?.edit()?.putFloat("lineHeight", lineHeight)?.apply()
    }

    fun updateParaSpacing(value: Float) {
        paraSpacing = (Math.round(value.coerceIn(0f, 2.5f) * 10f) / 10f)
        prefs?.edit()?.putFloat("paraSpacing", paraSpacing)?.apply()
    }

    fun updateFont(value: NovelFont) {
        font = value
        prefs?.edit()?.putString("font", value.name)?.apply()
    }

    fun updateTheme(value: NovelTheme) {
        theme = value
        prefs?.edit()?.putString("novelTheme", value.name)?.apply()
    }

    fun updateAlign(value: NovelAlign) {
        align = value
        prefs?.edit()?.putString("align", value.name)?.apply()
    }
}

internal fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()

@Composable
internal fun ReaderSystemBars(hidden: Boolean, lightContent: Boolean = true) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window, lightContent) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) } ?: return@DisposableEffect onDispose {}
        val lightBars = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = !lightContent
        controller.isAppearanceLightNavigationBars = !lightContent
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        view.keepScreenOn = true
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = lightBars
            controller.isAppearanceLightNavigationBars = lightBars
            view.keepScreenOn = false
        }
    }
    LaunchedEffect(window, hidden) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) } ?: return@LaunchedEffect
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
internal fun ReaderTopBar(
    visible: Boolean,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = MokuTheme.colors
    AnimatedVisibility(
        visible,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Column(Modifier.fillMaxWidth().background(colors.bgSurface.copy(alpha = 0.96f)).statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                    .height(60.dp)
                    .padding(horizontal = MokuSpacing.Sp3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
            ) {
                ChromeButton(R.drawable.ph_arrow_left_light, "Back", onClick = onBack)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(subtitle, style = MokuText.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (title.isNotBlank()) Text(title, style = MokuText.meta, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                actions()
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderDim))
        }
    }
}

@Composable
internal fun ReaderBottomBar(visible: Boolean, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Xl)
    AnimatedVisibility(
        visible,
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .padding(MokuSpacing.Sp3),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.bgSurface.copy(alpha = 0.96f))
                    .border(1.dp, colors.borderDim, shape)
                    .padding(horizontal = MokuSpacing.Sp1, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp1),
                content = content,
            )
        }
    }
}

@Composable
internal fun ChromeButton(
    @DrawableRes icon: Int,
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    size: Dp = 40.dp,
    onClick: () -> Unit,
) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Lg)
    Box(
        Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.35f)
            .pressable(shape, enabled = enabled, onClick = onClick)
            .background(if (active) colors.accentMuted else colors.bgRaised)
            .border(1.dp, if (active) colors.accentDim else colors.borderDim, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = if (active) colors.accentFg else colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun ChapterEnd(nav: ReaderNav, modifier: Modifier = Modifier) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Xxl)
    Box(modifier.padding(MokuSpacing.Sp5), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(colors.bgSurface)
                .border(1.dp, colors.borderDim, shape)
                .padding(MokuSpacing.Sp5),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2),
        ) {
            Text("FINISHED", style = MokuText.label, color = colors.textFaint)
            Text(nav.unitLabel, style = MokuText.heading, color = colors.textPrimary, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(Modifier.padding(vertical = MokuSpacing.Sp2).fillMaxWidth().height(1.dp).background(colors.borderDim))
            if (nav.next != null) {
                Text("UP NEXT", style = MokuText.label, color = colors.textFaint)
                Text(nav.next.label(nav.word), style = MokuText.body, color = colors.textSecondary, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = MokuSpacing.Sp2), horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                    ActionChip("Back to series", onClick = nav.onBack)
                    ActionChip("Next ${nav.word.lowercase()}", icon = R.drawable.ph_caret_right_bold, accent = true, onClick = nav.onNext)
                }
            } else {
                Text("You're all caught up", style = MokuText.body, color = colors.textSecondary)
                Box(Modifier.padding(top = MokuSpacing.Sp2)) { ActionChip("Back to series", onClick = nav.onBack) }
            }
        }
    }
}

internal fun neighbours(units: List<BackendUnit>, current: BackendUnit?): Pair<BackendUnit?, BackendUnit?> {
    if (current == null) return null to null
    val n = current.num()
    if (n == null) {
        val i = units.indexOfFirst { it.id == current.id }
        return units.getOrNull(i - 1) to units.getOrNull(i + 1)
    }
    val group = current.group()
    fun pick(candidates: List<BackendUnit>) = candidates.firstOrNull { it.group() == group } ?: candidates.firstOrNull()
    val numbered = units.filter { it.num() != null }
    val prevNum = numbered.mapNotNull { it.num() }.filter { it < n }.maxOrNull()
    val nextNum = numbered.mapNotNull { it.num() }.filter { it > n }.minOrNull()
    return prevNum?.let { p -> pick(numbered.filter { it.num() == p }) } to nextNum?.let { x -> pick(numbered.filter { it.num() == x }) }
}

internal fun List<BackendUnit>.readingOrder(): List<BackendUnit> =
    withIndex().sortedWith(compareBy({ it.value.num() ?: Float.MAX_VALUE }, { -it.index })).map { it.value }
