package app.maestri.ui.Style

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Toolbar affordances. The iOS client uses real SF Symbols here, never text glyphs, so these
 * replace the invented `‹ ＋ × ⋯ ⎘` decorations in buttons and toolbars. User-visible content
 * (terminal geometry `80×24`, file paths, labels that come from the host) keeps its text.
 */
@Composable
fun IconOnly(
    icon: ImageVector,
    contentDescription: String? = null,
    size: Dp = 18.dp,
    tint: Color = LocalContentColor.current,
) {
    Icon(icon, contentDescription, Modifier.size(size), tint = tint)
}

@Composable
fun IconLabel(
    icon: ImageVector,
    text: String,
    contentDescription: String? = null,
    size: Dp = 18.dp,
    tint: Color = LocalContentColor.current,
) {
    Icon(icon, contentDescription, Modifier.size(size), tint = tint)
    Text(text)
}

/** Back affordance: real arrow + the same word the text glyph used to carry. */
@Composable
fun BackLabel(text: String) {
    Icon(Icons.Filled.ArrowBack, null, Modifier.size(18.dp))
    Text(text)
}
