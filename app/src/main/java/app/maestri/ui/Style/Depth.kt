package app.maestri.ui.Style

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ring depth, the v2 replacement for hard borders: `box-shadow: 0 0 0 1px <hair>`
 * plus a whisper ambient. Compose has no ring-shadow primitive, so a ring is a
 * 1 dp border at the hairline colour with a shadow carrying the SAME colour at low
 * alpha — visually the warm halo the spec asks for, no cool gray border anywhere.
 */
fun Modifier.warmRing(
    hairline: Color,
    radius: Dp = MaestriRadii.Card,
    ringWidth: Dp = 1.dp,
): Modifier = this
    .shadow(
        elevation = 2.dp,
        shape = RoundedCornerShape(radius),
        ambientColor = hairline.copy(alpha = 0.55f),
        spotColor = hairline.copy(alpha = 0.55f),
    )
    .border(BorderStroke(ringWidth, hairline), RoundedCornerShape(radius))

/** Soft ambient only (no ring) — for floats above the board. */
fun Modifier.softHalo(
    radius: Dp = MaestriRadii.Card,
    color: Color = Color.Black,
    elevation: Dp = 6.dp,
): Modifier = this.shadow(
    elevation = elevation,
    shape = RoundedCornerShape(radius),
    ambientColor = color.copy(alpha = 0.28f),
    spotColor = color.copy(alpha = 0.18f),
)

/** BorderStroke form for Card/OutlinedCard call sites. */
fun ringStroke(hairline: Color, width: Dp = 1.dp): BorderStroke = BorderStroke(width, hairline)
