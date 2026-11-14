package dev.moku.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.moku.mobile.R
import dev.moku.mobile.ui.theme.MokuRadius
import dev.moku.mobile.ui.theme.MokuSpacing
import dev.moku.mobile.ui.theme.MokuText
import dev.moku.mobile.ui.theme.MokuTheme

private val Warning = Color(0xFFE5675F)

/** Stays up for as long as the connected server can't be reached, on every screen but the reader. */
@Composable
fun ServerUnreachableBanner(visible: Boolean, server: String, retrying: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(MokuMotion.bouncy()) { it } + fadeIn(MokuMotion.enter(200)),
        exit = slideOutVertically(MokuMotion.exit(220)) { it / 2 } + fadeOut(MokuMotion.exit(180)),
    ) {
        val colors = MokuTheme.colors
        val shape = RoundedCornerShape(MokuRadius.Xl)
        Row(
            Modifier
                .padding(horizontal = MokuSpacing.Sp4)
                .widthIn(max = 480.dp)
                .shadow(12.dp, shape, ambientColor = colors.bgVoid, spotColor = colors.bgVoid)
                .background(colors.bgOverlay, shape)
                .border(1.dp, Warning.copy(alpha = 0.5f), shape)
                .padding(start = MokuSpacing.Sp4, end = MokuSpacing.Sp1)
                .padding(vertical = MokuSpacing.Sp2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MokuSpacing.Sp3),
        ) {
            Icon(painterResource(R.drawable.ph_cloud_slash_light), null, tint = Warning, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text("Can't connect to $server", style = MokuText.sub, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Its titles can't load right now", style = MokuText.meta, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                if (retrying) "Trying…" else "Retry",
                style = MokuText.label,
                color = colors.accentFg,
                modifier = Modifier
                    .pressable(RoundedCornerShape(MokuRadius.Lg)) { if (!retrying) { haptics.click(); onRetry() } }
                    .padding(horizontal = MokuSpacing.Sp3, vertical = MokuSpacing.Sp2 + 2.dp),
            )
        }
    }
}
