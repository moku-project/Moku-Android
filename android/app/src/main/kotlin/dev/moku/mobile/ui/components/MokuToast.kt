package dev.moku.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme
import kotlinx.coroutines.delay

object MokuToast {
    data class Message(val id: Long, val text: String, val long: Boolean, val action: String?, val onAction: (() -> Unit)?)

    var current by mutableStateOf<Message?>(null)
        private set
    private var counter = 0L

    fun show(text: String, long: Boolean = false, action: String? = null, onAction: (() -> Unit)? = null) {
        current = Message(++counter, text, long || action != null, action, onAction)
    }

    fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }
}

@Composable
fun MokuToastHost(bottomPadding: Dp, modifier: Modifier = Modifier) {
    val message = MokuToast.current
    var last by remember { mutableStateOf(message) }
    if (message != null) last = message
    val haptics = rememberHaptics()

    LaunchedEffect(message?.id) {
        val m = message ?: return@LaunchedEffect
        delay(if (m.long) 4000 else 2400)
        MokuToast.dismiss(m.id)
    }

    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = bottomPadding), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = slideInVertically(MokuMotion.bouncy()) { it } + fadeIn(MokuMotion.enter(200)) + scaleIn(MokuMotion.enter(260), initialScale = 0.92f),
            exit = slideOutVertically(MokuMotion.exit(220)) { it / 2 } + fadeOut(MokuMotion.exit(180)),
        ) {
            val m = last ?: return@AnimatedVisibility
            val colors = MokuTheme.colors
            val shape = RoundedCornerShape(MokuRadius.Xl)
            Row(
                Modifier
                    .padding(horizontal = MokuSpacing.Sp4)
                    .widthIn(max = 480.dp)
                    .shadow(12.dp, shape, ambientColor = colors.bgVoid, spotColor = colors.bgVoid)
                    .background(colors.bgOverlay, shape)
                    .border(1.dp, colors.borderBase, shape)
                    .pointerInput(m.id) { detectVerticalDragGestures { _, drag -> if (drag > 6) MokuToast.dismiss(m.id) } }
                    .padding(start = MokuSpacing.Sp4, end = if (m.action != null) MokuSpacing.Sp1 else MokuSpacing.Sp4)
                    .padding(vertical = if (m.action != null) MokuSpacing.Sp1 else MokuSpacing.Sp3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
            ) {
                Text(m.text, style = MokuText.sub, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (m.action != null) {
                    Text(
                        m.action,
                        style = MokuText.label,
                        color = colors.accentFg,
                        modifier = Modifier
                            .pressable(RoundedCornerShape(MokuRadius.Lg)) { haptics.click(); m.onAction?.invoke(); MokuToast.dismiss(m.id) }
                            .padding(horizontal = MokuSpacing.Sp3, vertical = MokuSpacing.Sp2 + 2.dp),
                    )
                }
            }
        }
    }
}
