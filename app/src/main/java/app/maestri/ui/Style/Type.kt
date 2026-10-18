package app.maestri.ui.Style

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import app.maestri.remote.R

/**
 * Bundled typefaces + the root-title rule.
 *
 * [Mono] is the Nerd-Font-patched JetBrains Mono and is SCOPED by ruling: terminal text,
 * HUD numerics (ring %, times, ids, shas, JSON) and code spans. Chrome stays on the system
 * sans — never put [Mono] on a nav title, tab label or button.
 *
 * [Serif] carries the large root titles at weight 500 ONLY (spec §4/§5.6; v2.1-modern
 * artifact .lnav .lt = 28px/1.1, Chat root inline 24px) — Workspaces, Feed, Usage, Floors,
 * Settings at 28 sp, Chat at 24 sp — and never terminal/HUD/data.
 */
object MaestriType {
    val Mono: FontFamily = FontFamily(Font(R.font.maestri_mono))
    val MonoName: String = "JetBrains Mono NF"

    val Serif: FontFamily = FontFamily.Serif
    val RootTitleSize = 28.sp
    val ChatTitleSize = 24.sp
    val RootTitleWeight = FontWeight.Medium
    val RootTitleLineHeight = 30.8.sp
}

/** Root title style: serif, weight 500, 1.1 leading. */
val RootTitleStyle: TextStyle = TextStyle(
    fontFamily = MaestriType.Serif,
    fontSize = MaestriType.RootTitleSize,
    lineHeight = MaestriType.RootTitleLineHeight,
    fontWeight = MaestriType.RootTitleWeight,
)

/**
 * The root large title. Use ONLY on screen roots (Workspaces/Feed/Usage/Floors/Settings, and
 * Chat at [chat] = true for its 26 sp step) — never on pushed-screen nav titles, terminal or data.
 */
@Composable
fun RootTitle(
    text: String,
    modifier: Modifier = Modifier,
    chat: Boolean = false,
    maxLines: Int = 1,
) {
    Text(
        text,
        modifier = modifier,
        fontFamily = MaestriType.Serif,
        fontSize = if (chat) MaestriType.ChatTitleSize else MaestriType.RootTitleSize,
        lineHeight = if (chat) 26.4.sp else MaestriType.RootTitleLineHeight,
        fontWeight = MaestriType.RootTitleWeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Terminal stream text at a caller-supplied size (the Aa stepper persists 10–20). */
@Composable
fun monoBody(sizeSp: Int, lineHeightSp: Float = sizeSp * 1.55f, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) =
    TextStyle(fontFamily = MaestriType.Mono, fontSize = sizeSp.sp, lineHeight = lineHeightSp.sp, color = color)

/** HUD numerics: ring percentages, mono times, ids, shas. */
@Composable
fun monoHud(sizeSp: Int = 11, weight: FontWeight = FontWeight.Normal, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    TextStyle(fontFamily = MaestriType.Mono, fontSize = sizeSp.sp, fontWeight = weight, color = color)
