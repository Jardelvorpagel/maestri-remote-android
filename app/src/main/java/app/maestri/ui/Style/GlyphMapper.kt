package app.maestri.ui.Style

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.maestri.ui.iconGlyph
import androidx.compose.ui.unit.dp

/**
 * The SF-Symbol vocabulary the Mac/iOS clients actually request (32 recovered names) mapped to
 * their best-fit official Material icons, so workspace/terminal icon fields render a real symbol
 * instead of one neutral glyph. Emoji names keep passing through untouched.
 */
object GlyphMapper {
    /** The recovered SF-Symbol names, in doc order. */
    val names: List<String> = listOf(
        "terminal", "sparkle", "brain", "seal", "gearshape", "server.rack", "globe.americas",
        "hammer", "wrench", "bolt", "cpu", "memorychip", "laptopcomputer", "desktopcomputer",
        "paintbrush", "folder", "doc.text", "cube", "wand.and.stars", "theatermasks", "lightbulb",
        "leaf", "flask", "graduationcap", "books.vertical", "chart.bar", "testtube.2",
        "stethoscope", "music.note", "camera", "gamecontroller", "flag",
    )

    val byName: Map<String, ImageVector> = mapOf(
        "terminal" to Icons.Filled.Terminal,
        "globe" to Icons.Filled.Public,   // corpus §5ter: portal node payload icon
        "sparkle" to Icons.Filled.AutoAwesome,
        "brain" to Icons.Filled.Psychology,
        "seal" to Icons.Filled.Verified,
        "gearshape" to Icons.Filled.Settings,
        "server.rack" to Icons.Filled.Dns,
        "globe.americas" to Icons.Filled.Public,
        "hammer" to Icons.Filled.Build,
        "wrench" to Icons.Filled.Handyman,
        "bolt" to Icons.Filled.Bolt,
        "cpu" to Icons.Filled.DeveloperBoard,
        "memorychip" to Icons.Filled.Memory,
        "laptopcomputer" to Icons.Filled.LaptopMac,
        "desktopcomputer" to Icons.Filled.Computer,
        "paintbrush" to Icons.Filled.Brush,
        "folder" to Icons.Filled.Folder,
        "doc.text" to Icons.Filled.Description,
        "cube" to Icons.Filled.ViewInAr,
        "wand.and.stars" to Icons.Filled.AutoFixHigh,
        "theatermasks" to Icons.Filled.TheaterComedy,
        "lightbulb" to Icons.Filled.Lightbulb,
        "leaf" to Icons.Filled.Eco,
        "flask" to Icons.Filled.Science,
        "graduationcap" to Icons.Filled.School,
        "books.vertical" to Icons.Filled.MenuBook,
        "chart.bar" to Icons.Filled.BarChart,
        "testtube.2" to Icons.Filled.Biotech,
        "stethoscope" to Icons.Filled.MedicalServices,
        "music.note" to Icons.Filled.MusicNote,
        "camera" to Icons.Filled.PhotoCamera,
        "gamecontroller" to Icons.Filled.SportsEsports,
        "flag" to Icons.Filled.Flag,
    )

    /** Material icon for an SF-Symbol name; null when the name is not in the vocabulary. */
    fun icon(raw: String?): ImageVector? = raw?.trim()?.lowercase()?.let { byName[it] }

    fun isSfSymbolName(raw: String?): Boolean = icon(raw) != null
}

/**
 * Renders an icon field: the real Material symbol when the SF-Symbol name is in [GlyphMapper]'s
 * vocabulary, otherwise the same text glyph the screens used before (emoji pass through). The
 * caller's modifier keeps its width, so rows do not reflow when the icon appears.
 */
@Composable
fun GlyphOrIcon(
    raw: String?,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 18.sp,
    tint: Color = LocalContentColor.current,
    fallback: String = "▤",
) {
    val icon = GlyphMapper.icon(raw)
    if (icon != null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(fontSize.value.dp), tint = tint)
        }
    } else {
        Text(iconGlyph(raw, fallback), modifier = modifier, fontSize = fontSize, color = tint)
    }
}
