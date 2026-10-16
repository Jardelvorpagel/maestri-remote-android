package app.maestri.ui.Style

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * One radius scale for the whole app. iOS ships its rhythm as compiled SwiftUI constants
 * (UNRECOVERABLE); this mirrors the recovered pair the doc allows — pills fully rounded,
 * cards and menus 10–12 — and replaces the ad-hoc 8/10/12/14/16/18 scatter.
 */
object MaestriRadii {
    /** Controls, chips, list rows. */
    val Control = 10.dp

    /** Cards, menus, popovers, dialogs, sheets. */
    val Card = 12.dp
}

val MaestriShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(MaestriRadii.Control),
    small = RoundedCornerShape(MaestriRadii.Control),
    medium = RoundedCornerShape(MaestriRadii.Card),
    large = RoundedCornerShape(MaestriRadii.Card),
    extraLarge = RoundedCornerShape(MaestriRadii.Card),
)

/** Fully-rounded pill (iOS capsule): composer, mode banner, status pills. */
fun pillShape(): RoundedCornerShape = RoundedCornerShape(percent = 50)

/** Card/menus corner at the single scale. */
fun cardShape(): RoundedCornerShape = RoundedCornerShape(MaestriRadii.Card)

/** Control corner at the single scale. */
fun controlShape(): RoundedCornerShape = RoundedCornerShape(MaestriRadii.Control)
