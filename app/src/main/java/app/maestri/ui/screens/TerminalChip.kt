package app.maestri.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import app.maestri.ui.Style.MaestriType
import app.maestri.ui.Style.TerminalThemes.TokyoNight
import app.maestri.ui.Style.pillShape

data class TerminalChipStatus(
    val label: String,
    val dotColor: Color,
)

class QuickChipKey(
    val label: String,
    val description: String,
    val bytes: ByteArray?,
    val kind: Kind = Kind.Byte,
) {
    enum class Kind { Byte, Chat }

    companion object {
        val DEFAULT: List<QuickChipKey> = listOf(
            QuickChipKey("Ctrl-C", "Control C — interrupt", byteArrayOf(0x03)),
            // ⌘ has no PTY encoding; maps ⌘K to Ctrl-K (0x0B, kill-line).
            QuickChipKey("\u2318K", "Command K — kill line", byteArrayOf(0x0B)),
            QuickChipKey("\u21E5", "Tab key", byteArrayOf(0x09)),
            QuickChipKey("esc", "Escape key", byteArrayOf(0x1B)),
            QuickChipKey("\u2191", "Arrow up", byteArrayOf(0x1B, 0x5B, 0x41)),
            QuickChipKey("\u2193", "Arrow down", byteArrayOf(0x1B, 0x5B, 0x42)),
            QuickChipKey("Ctrl-L", "Control L — clear screen", byteArrayOf(0x0C)),
            QuickChipKey("Ctrl-D", "Control D — end of file", byteArrayOf(0x04)),
            QuickChipKey("Ctrl-R", "Control R — reverse search", byteArrayOf(0x12)),
            QuickChipKey("Chat \u2192", "Switch to chat mode", null, Kind.Chat),
        )
    }
}

@Composable
fun TerminalChip(
    status: TerminalChipStatus,
    expanded: Boolean,
    imeVisible: Boolean,
    onToggle: () -> Unit,
    onKey: (QuickChipKey) -> Unit,
    onShowKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "chipChevron",
    )

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.BottomEnd) {
        val panelWidth = min(336.dp, maxWidth)
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(160)) +
                    slideInVertically(tween(160)) { with(density) { 6.dp.roundToPx() } } +
                    scaleIn(
                        animationSpec = tween(160),
                        initialScale = 0.98f,
                        transformOrigin = TransformOrigin(1f, 1f),
                    ),
                exit = fadeOut(tween(120)),
            ) {
                val panelShape = RoundedCornerShape(14.dp)
                Column(
                    modifier = Modifier
                        .width(panelWidth)
                        .shadow(14.dp, panelShape)
                        .clip(panelShape)
                        .background(TokyoNight.panel, panelShape)
                        .border(1.dp, TokyoNight.border, panelShape)
                        .padding(10.dp)
                        .semantics {
                            isTraversalGroup = true
                            liveRegion = LiveRegionMode.Polite
                        },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(status.dotColor, CircleShape),
                        )
                        Text(
                            text = status.label,
                            fontFamily = MaestriType.Mono,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TokyoNight.foreground,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    QuickChipKey.DEFAULT.chunked(5).forEach { rowKeys ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rowKeys.forEach { key ->
                                QuickChipKeyCell(
                                    key = key,
                                    onClick = { onKey(key) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                )
                            }
                        }
                    }

                    if (!imeVisible) {
                        val kbShape = RoundedCornerShape(10.dp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(kbShape)
                                .background(TokyoNight.foreground.copy(alpha = 0.10f), kbShape)
                                .border(1.dp, TokyoNight.foreground.copy(alpha = 0.20f), kbShape)
                                .semantics { contentDescription = "Show keyboard" }
                                .clickable(role = Role.Button, onClick = onShowKeyboard),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Show keyboard",
                                fontFamily = MaestriType.Mono,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TokyoNight.foreground,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            val pill = pillShape()
            Row(
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .shadow(8.dp, pill)
                    .clip(pill)
                    .background(TokyoNight.panel, pill)
                    .border(1.dp, TokyoNight.border, pill)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription =
                            "Terminal status, ${status.label}, ${if (expanded) "expanded" else "collapsed"}"
                        stateDescription = if (expanded) "Open" else "Closed"
                    }
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Open quick keys",
                        onClick = onToggle,
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(status.dotColor, CircleShape),
                )
                Text(
                    text = status.label,
                    fontFamily = MaestriType.Mono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TokyoNight.foreground,
                    maxLines = 1,
                    softWrap = false,
                )
                Canvas(
                    modifier = Modifier
                        .size(13.dp)
                        .graphicsLayer { rotationZ = chevronRotation },
                ) {
                    val w = size.width
                    val h = size.height
                    val path = Path().apply {
                        moveTo(w * 0.25f, h * 0.625f)
                        lineTo(w * 0.50f, h * 0.375f)
                        lineTo(w * 0.75f, h * 0.625f)
                    }
                    drawPath(
                        path = path,
                        color = TokyoNight.foreground,
                        style = Stroke(
                            width = 1.8.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickChipKeyCell(
    key: QuickChipKey,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val isChat = key.kind == QuickChipKey.Kind.Chat
    val bgModifier = if (isChat) {
        Modifier
            .background(
                brush = Brush.linearGradient(listOf(TokyoNight.blue, TokyoNight.blueDeep)),
                shape = shape,
            )
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
    } else {
        Modifier
            .background(TokyoNight.foreground.copy(alpha = 0.08f), shape)
            .border(1.dp, TokyoNight.foreground.copy(alpha = 0.16f), shape)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .then(bgModifier)
            .semantics { contentDescription = key.description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            fontFamily = MaestriType.Mono,
            fontSize = if (isChat) 11.sp else 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isChat) TokyoNight.background else TokyoNight.foreground,
            maxLines = 1,
            softWrap = false,
        )
    }
}
