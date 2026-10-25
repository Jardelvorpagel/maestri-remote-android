package app.maestri.ui.Style

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

private val RecordRed = Color(0xFFFF5A5A)

/**
 * Voice memo toggle for a composer: the mic glyph while idle, then the red dot and the take's
 * `m:ss` while recording. Shared so both composer faces draw the same affordance.
 */
@Composable
fun RecordButton(
    recording: Boolean,
    elapsedMs: Long,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = Color(0xFFC3C7D6),
    size: Dp = 48.dp,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .clickable(enabled = enabled) { onClick() }
                .semantics {
                    contentDescription = if (recording) "Stop recording" else "Record a voice note"
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            if (recording) {
                Box(Modifier.size(17.dp).clip(CircleShape).background(RecordRed))
            } else {
                Canvas(Modifier.size(17.dp)) {
                    val stroke = 1.6.dp.toPx()
                    val bodyWidth = this.size.width * 0.44f
                    val bodyHeight = this.size.height * 0.58f
                    drawRoundRect(
                        tint,
                        topLeft = Offset((this.size.width - bodyWidth) / 2, 0f),
                        size = Size(bodyWidth, bodyHeight),
                        cornerRadius = CornerRadius(bodyWidth / 2),
                        style = Stroke(stroke),
                    )
                    drawLine(
                        tint,
                        Offset(this.size.width / 2, bodyHeight),
                        Offset(this.size.width / 2, this.size.height),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        if (recording) {
            Spacer(Modifier.width(2.dp))
            Box(Modifier.size(7.dp).clip(CircleShape).background(RecordRed))
            Spacer(Modifier.width(5.dp))
            Text(formatRecordingClock(elapsedMs), fontSize = 11.5.sp, color = RecordRed)
        }
    }
}

internal fun formatRecordingClock(elapsedMs: Long): String {
    val total = (elapsedMs / 1000L).coerceAtLeast(0L)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
