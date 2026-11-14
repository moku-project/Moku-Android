package dev.moku.mobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuTheme

// One clock per skeleton group so every bone sweeps in phase instead of each running its own timer.
private val LocalShimmer = compositionLocalOf<(() -> Float)?> { null }

@Composable
fun SkeletonGroup(content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-phase",
    )
    CompositionLocalProvider(LocalShimmer provides { phase }) {
        Box(Modifier.riseIn()) { content() }
    }
}

@Composable
fun Modifier.shimmer(shape: Shape = RoundedCornerShape(MokuRadius.Md)): Modifier {
    val colors = MokuTheme.colors
    val base = colors.bgRaised
    val shine = colors.bgOverlay
    val phase = LocalShimmer.current
    return clip(shape).drawWithCache {
        onDrawBehind {
            val p = phase?.invoke() ?: 0.5f
            val x = size.width * p
            drawRect(base)
            drawRect(
                Brush.linearGradient(
                    colors = listOf(base, shine, base),
                    start = Offset(x - size.width * 0.5f, 0f),
                    end = Offset(x + size.width * 0.5f, size.height),
                ),
            )
        }
    }
}

@Composable
fun Bone(width: Dp, height: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(MokuRadius.Sm)) {
    Box(modifier.size(width, height).shimmer(shape))
}

@Composable
fun BoneLine(modifier: Modifier = Modifier, height: Dp = 12.dp) {
    Box(modifier.height(height).shimmer(RoundedCornerShape(MokuRadius.Sm)))
}

/** Rows shaped like SettingsRow / list items: leading tile, two text lines. */
@Composable
fun SkeletonRows(count: Int = 6, modifier: Modifier = Modifier, leading: Dp = 30.dp) {
    val colors = MokuTheme.colors
    val shape = RoundedCornerShape(MokuRadius.Xl)
    SkeletonGroup {
        Column(modifier.fillMaxWidth().clip(shape).background(colors.bgSurface).border(1.dp, colors.borderDim, shape)) {
            repeat(count) { i ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = MokuSpacing.Sp4 - 2.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
                ) {
                    Bone(leading, leading, shape = RoundedCornerShape(MokuRadius.Md))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        BoneLine(Modifier.fillMaxWidth(listOf(0.62f, 0.48f, 0.7f, 0.55f)[i % 4]), 13.dp)
                        BoneLine(Modifier.fillMaxWidth(listOf(0.34f, 0.4f, 0.28f, 0.45f)[i % 4]), 10.dp)
                    }
                }
            }
        }
    }
}

/** Poster card placeholder matching MediaPosterCard / library cards. */
@Composable
fun SkeletonPoster(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).shimmer(RoundedCornerShape(MokuRadius.Lg)))
        BoneLine(Modifier.fillMaxWidth(0.85f), 11.dp)
        BoneLine(Modifier.fillMaxWidth(0.5f), 9.dp)
    }
}

@Composable
fun SkeletonPosterGrid(columns: Int = 3, rows: Int = 4, modifier: Modifier = Modifier) {
    SkeletonGroup {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp4)) {
            repeat(rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3)) {
                    repeat(columns) { SkeletonPoster(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Placeholder for the title detail page: cover, title lines, action buttons and chapter rows. */
@Composable
fun SkeletonDetail(modifier: Modifier = Modifier) {
    SkeletonGroup {
        Column(modifier.fillMaxWidth().padding(MokuSpacing.Sp4), verticalArrangement = Arrangement.spacedBy(MokuSpacing.Sp4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp4)) {
                Box(Modifier.width(118.dp).aspectRatio(2f / 3f).shimmer(RoundedCornerShape(MokuRadius.Lg)))
                Column(Modifier.weight(1f).padding(top = MokuSpacing.Sp2), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BoneLine(Modifier.fillMaxWidth(0.9f), 20.dp)
                    BoneLine(Modifier.fillMaxWidth(0.6f), 20.dp)
                    BoneLine(Modifier.fillMaxWidth(0.45f), 11.dp)
                    BoneLine(Modifier.fillMaxWidth(0.35f), 11.dp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2)) {
                repeat(4) { Bone(64.dp, 24.dp) }
            }
            BoneLine(Modifier.fillMaxWidth(), 11.dp)
            BoneLine(Modifier.fillMaxWidth(0.92f), 11.dp)
            BoneLine(Modifier.fillMaxWidth(0.7f), 11.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp2), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(44.dp).shimmer(RoundedCornerShape(MokuRadius.Lg)))
                Bone(44.dp, 44.dp, shape = RoundedCornerShape(MokuRadius.Lg))
            }
            repeat(7) { i ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BoneLine(Modifier.fillMaxWidth(if (i % 2 == 0) 0.5f else 0.62f), 13.dp)
                        BoneLine(Modifier.fillMaxWidth(0.3f), 10.dp)
                    }
                    Bone(28.dp, 28.dp, shape = RoundedCornerShape(MokuRadius.Md))
                }
            }
        }
    }
}
