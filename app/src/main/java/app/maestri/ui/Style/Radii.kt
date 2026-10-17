package app.maestri.ui.Style

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * One radius scale for the whole app — v2.1 re-derivation to the claude ramp
 * 8 / 12 / 16 / pill (spec §8.3 dev 2; supersedes the 10/12 iOS pair).
 */
object MaestriRadii {
    /** Controls, chips, list rows, icons. */
    val Control = 8.dp

    /** Cards, menus, popovers, bubbles. */
    val Card = 12.dp

    /** Sheets, containers, the phone frame. */
    val Container = 16.dp
}

val MaestriShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(MaestriRadii.Control),
    small = RoundedCornerShape(MaestriRadii.Control),
    medium = RoundedCornerShape(MaestriRadii.Card),
    large = RoundedCornerShape(MaestriRadii.Container),
    extraLarge = RoundedCornerShape(MaestriRadii.Container),
)

/** Fully-rounded pill (iOS capsule): composer, chips, status pills, spine tabs. */
fun pillShape(): RoundedCornerShape = RoundedCornerShape(percent = 50)

/** Card/menus/bubbles corner at the single scale. */
fun cardShape(): RoundedCornerShape = RoundedCornerShape(MaestriRadii.Card)

/** Control corner at the single scale. */
fun controlShape(): RoundedCornerShape = RoundedCornerShape(MaestriRadii.Control)

/** Sheets and container corner. */
fun containerShape(): RoundedCornerShape = RoundedCornerShape(MaestriRadii.Container)
