package dev.moku.mobile.ui.reader

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme
import kotlin.math.roundToInt

private val TRACK_HEIGHT = 34.dp
private val CELL_HEIGHT = 3.dp

@Composable
internal fun NavGhostButton(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MokuTheme.colors
    Box(
        Modifier
            .size(34.dp)
            .alpha(if (enabled) 1f else 0.25f)
            .clip(RoundedCornerShape(MokuRadius.Md))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = colors.textMuted, modifier = Modifier.size(15.dp))
    }
}

@Composable
internal fun SegmentedProgress(
    current: Int,
    count: Int,
    rtl: Boolean,
    modifier: Modifier = Modifier,
    onSeek: (index: Int, commit: Boolean) -> Unit,
) {
    val colors = MokuTheme.colors
    val density = LocalDensity.current
    var width by remember { mutableStateOf(0f) }
    var hover by remember { mutableStateOf<Int?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val total by rememberUpdatedState(count)
    val reversed by rememberUpdatedState(rtl)

    fun slotAt(x: Float): Int {
        if (width <= 0f || total <= 0) return 0
        val f = (x / width).coerceIn(0f, 0.9999f)
        val i = (f * total).toInt()
        return if (reversed) total - 1 - i else i
    }

    val shown = hover ?: current
    Box(modifier.height(TRACK_HEIGHT), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(TRACK_HEIGHT)
                .onSizeChanged { width = it.width.toFloat() }
                .pointerInput(Unit) {
                    detectTapGestures { seek(slotAt(it.x), true) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { hover = slotAt(it.x); seek(hover!!, false) },
                        onDragEnd = { hover?.let { seek(it, true) }; hover = null },
                        onDragCancel = { hover = null },
                    ) { change, _ ->
                        val slot = slotAt(change.position.x)
                        if (slot != hover) {
                            hover = slot
                            seek(slot, false)
                        }
                    }
                },
        ) {
            if (count <= 0) return@Canvas
            val cellH = (if (hover != null) 5.dp else CELL_HEIGHT).toPx()
            val y = (size.height - cellH) / 2f
            val step = size.width / count
            val gap = if (step >= 4.dp.toPx()) 1.dp.toPx() else 0f
            val done = colors.accentFg
            val rest = colors.borderStrong
            if (gap == 0f) {
                val filled = (shown + 1f) / count * size.width
                drawRoundRect(rest, Offset(0f, y), Size(size.width, cellH), CornerRadius(1.dp.toPx()))
                val x = if (rtl) size.width - filled else 0f
                drawRoundRect(done, Offset(x, y), Size(filled, cellH), CornerRadius(1.dp.toPx()))
            } else {
                for (i in 0 until count) {
                    val slot = if (rtl) count - 1 - i else i
                    drawRoundRect(
                        if (slot <= shown) done else rest,
                        Offset(i * step, y),
                        Size(step - gap, cellH),
                        CornerRadius(1.dp.toPx()),
                    )
                }
            }
        }
        hover?.let { slot ->
            val visual = if (rtl) count - 1 - slot else slot
            val centerPx = (visual + 0.5f) / count.coerceAtLeast(1) * width
            Tooltip("${slot + 1} / $count", centerPx, width)
        }
    }
}

@Composable
internal fun ThinSlider(
    fraction: Float,
    modifier: Modifier = Modifier,
    buffered: Float = 0f,
    onSeek: (fraction: Float, commit: Boolean) -> Unit,
) {
    val colors = MokuTheme.colors
    var width by remember { mutableStateOf(0f) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)

    fun at(x: Float) = if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)

    val shown = (drag ?: fraction).coerceIn(0f, 1f)
    Box(modifier.height(TRACK_HEIGHT), contentAlignment = Alignment.CenterStart) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(TRACK_HEIGHT)
                .onSizeChanged { width = it.width.toFloat() }
                .pointerInput(Unit) { detectTapGestures { seek(at(it.x), true) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { drag = at(it.x); seek(drag!!, false) },
                        onDragEnd = { drag?.let { seek(it, true) }; drag = null },
                        onDragCancel = { drag = null },
                    ) { change, _ ->
                        drag = at(change.position.x)
                        seek(drag!!, false)
                    }
                },
        ) {
            val h = (if (drag != null) 5.dp else CELL_HEIGHT).toPx()
            val y = (size.height - h) / 2f
            val r = CornerRadius(2.dp.toPx())
            drawRoundRect(colors.borderStrong, Offset(0f, y), Size(size.width, h), r)
            if (buffered > 0f) drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(0f, y), Size(size.width * buffered.coerceIn(0f, 1f), h), r)
            drawRoundRect(colors.accent, Offset(0f, y), Size(size.width * shown, h), r)
            val thumb = (if (drag != null) 7.8f else 6f).dp.toPx()
            val cx = (size.width * shown).coerceIn(thumb, size.width - thumb)
            drawCircle(Color.Black.copy(alpha = 0.5f), thumb + 2.dp.toPx(), Offset(cx, size.height / 2f))
            drawCircle(colors.accent, thumb, Offset(cx, size.height / 2f))
        }
    }
}

@Composable
private fun Tooltip(text: String, centerPx: Float, widthPx: Float) {
    val colors = MokuTheme.colors
    val density = LocalDensity.current
    var tipWidth by remember { mutableStateOf(0) }
    val x = (centerPx - tipWidth / 2f).coerceIn(0f, (widthPx - tipWidth).coerceAtLeast(0f))
    Box(Modifier.fillMaxSize()) {
        Text(
            text,
            style = MokuText.label,
            color = colors.textSecondary,
            modifier = Modifier
                .onSizeChanged { tipWidth = it.width }
                .offset { IntOffset(x.roundToInt(), with(density) { -22.dp.roundToPx() }) }
                .clip(RoundedCornerShape(MokuRadius.Sm))
                .background(colors.bgRaised)
                .border(1.dp, colors.borderBase, RoundedCornerShape(MokuRadius.Sm))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
