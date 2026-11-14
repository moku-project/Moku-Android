package dev.moku.mobile.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

object MokuMotion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> enter(ms: Int = 380, delay: Int = 0): FiniteAnimationSpec<T> = tween(ms, delay, EmphasizedDecelerate)
    fun <T> exit(ms: Int = 200): FiniteAnimationSpec<T> = tween(ms, easing = EmphasizedAccelerate)
    fun <T> move(ms: Int = 340): FiniteAnimationSpec<T> = tween(ms, easing = Emphasized)
    fun <T> snappy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 520f)

    /** Material shared-axis X: both screens travel a fraction of the width in the same direction while crossfading. */
    fun sharedAxisX(forward: Boolean, distance: Float = 0.3f): ContentTransform {
        val dir = if (forward) 1 else -1
        return (slideInHorizontally(move()) { (it * distance * dir).toInt() } + fadeIn(enter(260, delay = 70)))
            .togetherWith(slideOutHorizontally(move()) { (-it * distance * dir).toInt() } + fadeOut(exit(110)))
    }

    fun push(): ContentTransform =
        (slideInHorizontally(move(360)) { it } + fadeIn(enter(200)))
            .togetherWith(slideOutHorizontally(move(360)) { -it / 5 } + fadeOut(exit(300)))

    fun pop(): ContentTransform =
        (slideInHorizontally(move(320)) { -it / 5 } + fadeIn(enter(240)))
            .togetherWith(slideOutHorizontally(move(320)) { it } + fadeOut(exit(260)))

    fun zoom(): ContentTransform =
        (scaleIn(enter(340), initialScale = 0.94f) + fadeIn(enter(240)))
            .togetherWith(scaleOut(exit(200), targetScale = 1.04f) + fadeOut(exit(200)))
}

class Haptics(private val view: View) {
    fun tick() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    fun click() = perform(HapticFeedbackConstants.VIRTUAL_KEY)
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)
    fun confirm() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    fun reject() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    fun gestureEnd() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CLOCK_TICK)

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** Clickable with a clipped ripple and a press-in. Put it first so what follows scales and clips with it. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    haptic: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptics = rememberHaptics()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = if (pressed) spring(stiffness = 1400f) else spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "press",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.shape = shape
        clip = true
    }.combinedClickable(
        interactionSource = source,
        indication = ripple(),
        enabled = enabled,
        onLongClick = onLongClick?.let { { haptics.longPress(); it() } },
        onClick = { if (haptic) haptics.click(); onClick() },
    )
}

/** Animated tab host that slides along the x axis in the direction of travel. */
@Composable
fun <T> SlideSwitcher(
    target: T,
    order: (T) -> Int,
    modifier: Modifier = Modifier,
    distance: Float = 0.3f,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = { MokuMotion.sharedAxisX(order(targetState) >= order(initialState), distance) },
        label = "tabs",
    ) { content(it) }
}

/** Forward/back screen stack transition for nested pages. Depth decides direction. */
@Composable
fun <T> PushSwitcher(
    target: T,
    depth: (T) -> Int,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = { if (depth(targetState) >= depth(initialState)) MokuMotion.push() else MokuMotion.pop() },
        label = "push",
    ) { content(it) }
}

enum class OverlayStyle { Push, Zoom }

/** Overlay that keeps showing its last target while it animates out; [predictiveBack] follows the back swipe. */
@Composable
fun <T : Any> MotionOverlay(
    target: T?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    style: OverlayStyle = OverlayStyle.Push,
    predictiveBack: Boolean = true,
    content: @Composable (T) -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val edge = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    PredictiveBackHandler(enabled = predictiveBack && target != null) { events ->
        try {
            events.collect {
                edge.snapTo(if (it.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f)
                progress.snapTo(it.progress)
            }
            onBack()
        } catch (e: CancellationException) {
            scope.launch { progress.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 600f)) }
            throw e
        }
    }

    AnimatedContent(
        targetState = target,
        modifier = modifier.graphicsLayer {
            val p = progress.value
            if (p > 0f) {
                val s = 1f - 0.1f * p
                scaleX = s
                scaleY = s
                translationX = edge.value * 16.dp.toPx() * p
                transformOrigin = TransformOrigin(if (edge.value > 0) 1f else 0f, 0.5f)
                shape = RoundedCornerShape(28.dp * p)
                clip = true
            }
        },
        transitionSpec = {
            when {
                initialState == null || targetState == null -> when (style) {
                    OverlayStyle.Push -> if (targetState != null) {
                        slideInHorizontally(MokuMotion.move(380)) { it } togetherWith fadeOut(MokuMotion.exit(380))
                    } else {
                        fadeIn(MokuMotion.enter(200)) togetherWith
                            (slideOutHorizontally(MokuMotion.move(300)) { it } + fadeOut(MokuMotion.exit(300)))
                    }
                    OverlayStyle.Zoom -> MokuMotion.zoom()
                }
                else -> MokuMotion.push()
            }
        },
        contentKey = { it },
        label = "overlay",
    ) { value ->
        if (value != null) {
            Box(Modifier.fillMaxSize()) { content(value) }
            LaunchedEffect(Unit) { progress.snapTo(0f) }
        }
    }
}

/** Fades and lifts in once on first composition; use for cards and rows appearing in sequence. */
fun Modifier.riseIn(index: Int = 0, step: Int = 35, maxDelay: Int = 280): Modifier = composed {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        anim.animateTo(1f, tween(360, delayMillis = (index * step).coerceAtMost(maxDelay), easing = MokuMotion.EmphasizedDecelerate))
    }
    graphicsLayer {
        alpha = anim.value
        translationY = (1f - anim.value) * 14.dp.toPx()
    }
}

/** Bumped when the current bottom-nav tab is tapped again. */
val LocalReselect = compositionLocalOf { 0 }

@Composable
fun rememberReselectListState(): LazyListState {
    val state = rememberLazyListState()
    val reselect = LocalReselect.current
    val start = remember { reselect }
    LaunchedEffect(reselect) { if (reselect != start) state.animateScrollToItem(0) }
    return state
}

@Composable
fun rememberReselectGridState(): LazyGridState {
    val state = rememberLazyGridState()
    val reselect = LocalReselect.current
    val start = remember { reselect }
    LaunchedEffect(reselect) { if (reselect != start) state.animateScrollToItem(0) }
    return state
}
