package app.maestri.ui.Style

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.maestri.remote.R

/**
 * Bundled typefaces. [Mono] is the Nerd-Font-patched JetBrains Mono and is SCOPED by
 * ruling: terminal text, HUD numerics (ring %, times, ids, shas, JSON) and code spans.
 * Chrome stays on the system sans — never put [Mono] on a nav title, tab label or button.
 */
object MaestriType {
    val Mono: FontFamily = FontFamily(Font(R.font.maestri_mono))
    val MonoName: String = "JetBrains Mono NF"
}

/** Terminal stream text at a caller-supplied size (the Aa stepper persists 10–20). */
@Composable
fun monoBody(sizeSp: Int, lineHeightSp: Float = sizeSp * 1.55f, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) =
    TextStyle(fontFamily = MaestriType.Mono, fontSize = sizeSp.sp, lineHeight = lineHeightSp.sp, color = color)

/** HUD numerics: ring percentages, mono times, ids, shas. */
@Composable
fun monoHud(sizeSp: Int = 11, weight: FontWeight = FontWeight.Normal, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    TextStyle(fontFamily = MaestriType.Mono, fontSize = sizeSp.sp, fontWeight = weight, color = color)
