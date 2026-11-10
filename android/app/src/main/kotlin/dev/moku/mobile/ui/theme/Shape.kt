package dev.moku.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Mirrors desktop's --radius-* and --sp-* tokens.
object MokuRadius {
    val Sm = 3.dp
    val Md = 5.dp
    val Lg = 7.dp
    val Xl = 10.dp
    val Xxl = 14.dp
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
