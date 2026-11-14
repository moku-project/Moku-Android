package dev.moku.mobile.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.moku.mobile.R
import dev.moku.mobile.backend.MediaContentType
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuTheme

enum class ContentTypeFilter(val label: String, val mediaType: MediaContentType?) {
    ALL("All", null),
    MANGA("Manga", MediaContentType.MANGA),
    NOVEL("Novel", MediaContentType.NOVEL),
    ANIME("Anime", MediaContentType.ANIME),
}

private const val PREFS_NAME = "moku_content_type_prefs"
private const val KEY_TYPE = "content_type_filter"

object ContentTypeFilterController {
    var current by mutableStateOf(ContentTypeFilter.ALL)
        private set

    fun init(context: Context) {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_TYPE, null)
        current = stored?.let { runCatching { ContentTypeFilter.valueOf(it) }.getOrNull() } ?: ContentTypeFilter.ALL
    }

    fun set(context: Context, value: ContentTypeFilter) {
        current = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_TYPE, value.name).apply()
    }
}

@DrawableRes
private fun ContentTypeFilter.icon(fill: Boolean): Int = when (this) {
    ContentTypeFilter.ALL -> if (fill) R.drawable.ph_squares_four_fill else R.drawable.ph_squares_four_light
    ContentTypeFilter.MANGA -> if (fill) R.drawable.ph_image_square_fill else R.drawable.ph_image_square_light
    ContentTypeFilter.NOVEL -> if (fill) R.drawable.ph_book_open_text_fill else R.drawable.ph_book_open_text_light
    ContentTypeFilter.ANIME -> if (fill) R.drawable.ph_film_slate_fill else R.drawable.ph_film_slate_light
}

@Composable
fun ContentModeButton(size: Dp, iconSize: Dp, modifier: Modifier = Modifier) {
    val colors = MokuTheme.colors
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val active = ContentTypeFilterController.current
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(MokuRadius.Xxl)
    val options = ContentTypeFilter.entries
    val background by animateColorAsState(if (open) colors.accentDim else colors.bgRaised, MokuMotion.move(), label = "mode-bg")

    Box(modifier) {
        Box(
            modifier = Modifier
                .size(size)
                .pressable(
                    shape = shape,
                    pressedScale = 0.9f,
                    onLongClick = { open = true },
                ) {
                    haptics.tick()
                    ContentTypeFilterController.set(context, options[(active.ordinal + 1) % options.size])
                }
                .background(background)
                .border(1.dp, colors.borderDim, shape),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = active,
                transitionSpec = {
                    (scaleIn(MokuMotion.bouncy(), initialScale = 0.4f) + fadeIn(MokuMotion.enter(160)))
                        .togetherWith(scaleOut(MokuMotion.exit(140), targetScale = 0.4f) + fadeOut(MokuMotion.exit(120)))
                },
                label = "mode-icon",
            ) { type ->
                Icon(painterResource(type.icon(fill = true)), contentDescription = "Mode: ${type.label}", tint = colors.accentBright, modifier = Modifier.size(iconSize))
            }
        }

        if (open) {
            val lift = with(density) { (size + MokuSpacing.Sp2).roundToPx() }
            val appear = remember { MutableTransitionState(false).apply { targetState = true } }
            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(0, -lift),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                AnimatedVisibility(
                    visibleState = appear,
                    enter = scaleIn(MokuMotion.bouncy(), initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 1f)) + fadeIn(MokuMotion.enter(160)),
                ) {
                    Column(
                        modifier = Modifier
                            .clip(shape)
                            .background(colors.bgRaised)
                            .border(1.dp, colors.borderDim, shape)
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        options.forEach { type ->
                            val selected = type == active
                            Box(
                                modifier = Modifier
                                    .size(size - 12.dp)
                                    .pressable(RoundedCornerShape(MokuRadius.Xl)) {
                                        haptics.tick()
                                        ContentTypeFilterController.set(context, type)
                                        open = false
                                    }
                                    .background(if (selected) colors.accentDim else Color.Transparent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(type.icon(fill = selected)),
                                    contentDescription = type.label,
                                    tint = if (selected) colors.accentBright else colors.textMuted,
                                    modifier = Modifier.size(iconSize),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
