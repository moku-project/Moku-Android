package dev.moku.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Ported from `--radius-*` in Moku desktop's app.css — small, tight corners, not soft/pill mockup style. */
object MokuRadius {
    val Sm = 3.dp
    val Md = 5.dp
    val Lg = 7.dp
    val Xl = 10.dp
    val Xxl = 14.dp
    // Larger rounded-square corner for hero/spotlight cards and icon badges — everything in
    // the UI reads as a rounded square (never a circle or full pill), this is the "big" end
    // of that scale.
    val Card = 24.dp
    val Full = 9999.dp
}

val MokuShapes = Shapes(
    extraSmall = RoundedCornerShape(MokuRadius.Sm),
    small = RoundedCornerShape(MokuRadius.Md),
    medium = RoundedCornerShape(MokuRadius.Lg),
    large = RoundedCornerShape(MokuRadius.Xl),
    extraLarge = RoundedCornerShape(MokuRadius.Xxl),
)

/** Ported from `--sp-*` in app.css (4/8/12/16/20/24/32/40px). */
object MokuSpacing {
    val Sp1 = 4.dp
    val Sp2 = 8.dp
    val Sp3 = 12.dp
    val Sp4 = 16.dp
    val Sp5 = 20.dp
    val Sp6 = 24.dp
    val Sp8 = 32.dp
    val Sp10 = 40.dp
}
