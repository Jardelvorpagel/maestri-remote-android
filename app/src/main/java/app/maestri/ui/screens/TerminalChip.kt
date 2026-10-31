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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import app.maestri.ui.Style.MaestriType
import app.maestri.ui.Style.Palette
import app.maestri.ui.Style.TerminalThemes.TokyoNight
import java.util.Locale

data class TerminalChipStatus(
    val label: String,
    val dotColor: Color,
)

enum class TerminalHarness {
    PI,
    CODEX,
    GENERIC;

    companion object {
        fun fromAgentType(agentType: String?): TerminalHarness = when (agentType?.trim()?.lowercase(Locale.ROOT)) {
            "pi", "pi-agent" -> PI
            "codex" -> CODEX
            else -> GENERIC
        }
    }
}

enum class QuickChipKeyId {
    INTERRUPT,
    KILL_LINE,
    TAB,
    ESCAPE,
    UP,
    DOWN,
    CLEAR,
    EOF,
    REVERSE_SEARCH,
    CHAT,
}

data class QuickChipKey(
    val id: QuickChipKeyId,
    val label: String,
    val description: String,
    val bytes: ByteArray,
    val compactLabel: String = label,
) {
    companion object {
        val DEFAULT: List<QuickChipKey> = listOf(
            QuickChipKey(QuickChipKeyId.INTERRUPT, "Ctrl-C", "Control C — interrupt", byteArrayOf(0x03), "⌃C"),
            // shortcut: Ctrl-K sends 0x0B (kill-line), not a command-palette action.
            QuickChipKey(QuickChipKeyId.KILL_LINE, "\u2303K", "Control K — kill line", byteArrayOf(0x0B)),
            QuickChipKey(QuickChipKeyId.TAB, "\u21E5", "Tab key", byteArrayOf(0x09)),
            QuickChipKey(QuickChipKeyId.ESCAPE, "esc", "Escape key", byteArrayOf(0x1B)),
            QuickChipKey(QuickChipKeyId.UP, "\u2191", "Arrow up", byteArrayOf(0x1B, 0x5B, 0x41)),
            QuickChipKey(QuickChipKeyId.DOWN, "\u2193", "Arrow down", byteArrayOf(0x1B, 0x5B, 0x42)),
            QuickChipKey(QuickChipKeyId.CLEAR, "Ctrl-L", "Control L — clear screen", byteArrayOf(0x0C), "⌃L"),
            QuickChipKey(QuickChipKeyId.EOF, "Ctrl-D", "Control D — end of file", byteArrayOf(0x04), "⌃D"),
            QuickChipKey(QuickChipKeyId.REVERSE_SEARCH, "Ctrl-R", "Control R — reverse search", byteArrayOf(0x12), "⌃R"),
        )
    }
}

fun terminalQuickKeys(agentType: String?, codexAwayFromBottom: Boolean): List<QuickChipKey> {
    if (TerminalHarness.fromAgentType(agentType) != TerminalHarness.CODEX || !codexAwayFromBottom) {
        return QuickChipKey.DEFAULT
    }
    return QuickChipKey.DEFAULT.map { key ->
        if (key.id == QuickChipKeyId.ESCAPE) {
            key.copy(label = "\u2193 End", compactLabel = "\u2193 End", description = "Back to bottom — Escape")
        } else {
            key
        }
    }
}

@Composable
fun TerminalChip(
    status: TerminalChipStatus,
    expanded: Boolean,
    imeVisible: Boolean,
    keys: List<QuickChipKey> = QuickChipKey.DEFAULT,
    onToggle: () -> Unit,
    onKey: (QuickChipKey) -> Unit,
    onChat: () -> Unit,
    onShowKeyboard: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val keyHeight = if (density.fontScale >= 2f) 56.dp else 44.dp
    var announceOpen by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        announceOpen = expanded
        if (expanded) withFrameNanos { }
        announceOpen = false
    }
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
                            if (announceOpen) liveRegion = LiveRegionMode.Polite
                        },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(dragModifier)
                            .padding(horizontal = 2.dp)
                            .semantics(mergeDescendants = true) { traversalIndex = 0f },
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

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (keys + QuickChipKey(QuickChipKeyId.CHAT, "Chat →", "Chat", byteArrayOf(), "Chat")).chunked(5).forEachIndexed { rowIndex, rowKeys ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                rowKeys.forEachIndexed { columnIndex, key ->
                                    QuickChipKeyCell(
                                        key = key,
                                        enabled = enabled,
                                        onClick = { if (key.bytes.isEmpty()) onChat() else onKey(key) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(keyHeight)
                                            .semantics { traversalIndex = (1 + rowIndex * 5 + columnIndex).toFloat() },
                                    )
                                }
                            }
                        }
                    }

                    if (!imeVisible) {
                        val kbShape = RoundedCornerShape(10.dp)
                        val keyboardMeasurer = rememberTextMeasurer()
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(keyHeight)
                                .clip(kbShape)
                                .background(TokyoNight.foreground.copy(alpha = 0.10f), kbShape)
                                .border(1.dp, TokyoNight.foreground.copy(alpha = 0.20f), kbShape)
                                .terminalFocusRing(kbShape)
                                .semantics { traversalIndex = 11f }
                                .clickable(role = Role.Button, onClick = onShowKeyboard),
                            contentAlignment = Alignment.Center,
                        ) {
                            val measured = keyboardMeasurer.measure(
                                text = "Show keyboard",
                                style = TextStyle(
                                    fontFamily = MaestriType.Mono,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                constraints = constraints,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Text(
                                text = if (measured.hasVisualOverflow) "Keyboard" else "Show keyboard",
                                modifier = Modifier.clearAndSetSemantics { contentDescription = "Show keyboard" },
                                fontFamily = MaestriType.Mono,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TokyoNight.foreground,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }

            val pill = CircleShape
            Row(
                modifier = Modifier
                    .size(48.dp)
                    .then(dragModifier)
                    .shadow(8.dp, pill)
                    .terminalFocusRing(pill)
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
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(status.dotColor, CircleShape),
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
private fun Modifier.terminalFocusRing(shape: RoundedCornerShape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this.onFocusChanged { focused = it.isFocused }.drawBehind {
        if (focused) {
            val offset = 3.dp.toPx()
            val radius = shape.topStart.toPx(size, this)
            drawRoundRect(
                color = Palette.FocusBlue,
                topLeft = Offset(-offset, -offset),
                size = Size(size.width + offset * 2, size.height + offset * 2),
                cornerRadius = CornerRadius(radius + offset),
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun QuickChipKeyCell(
    key: QuickChipKey,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val chat = key.bytes.isEmpty()
    val paint = remember { android.graphics.Paint().apply { typeface = android.graphics.Typeface.MONOSPACE } }
    val label = if (paint.hasGlyph(key.label)) key.label else when (key.label) {
        "⇥" -> "Tab"
        "↑" -> "Up"
        "↓" -> "Down"
        "⌃K" -> "Ctrl-K"
        else -> key.label
    }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontFamily = MaestriType.Mono,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
    BoxWithConstraints(
        modifier = modifier
            .terminalFocusRing(shape)
            .clip(shape)
            .background(
                if (chat) Brush.horizontalGradient(listOf(TokyoNight.blue, TokyoNight.blueDeep))
                else Brush.horizontalGradient(listOf(TokyoNight.foreground.copy(alpha = 0.08f), TokyoNight.foreground.copy(alpha = 0.08f))),
                shape,
            )
            .border(1.dp, TokyoNight.foreground.copy(alpha = 0.16f), shape)
            .semantics { contentDescription = key.description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val measured = textMeasurer.measure(
            text = label,
            style = labelStyle,
            constraints = constraints,
            maxLines = 1,
            softWrap = false,
        )
        val fittedLabel = if (measured.hasVisualOverflow) key.compactLabel else key.label
        Text(
            text = fittedLabel,
            modifier = Modifier.clearAndSetSemantics {},
            fontFamily = MaestriType.Mono,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (chat) TokyoNight.background else TokyoNight.foreground,
            maxLines = 1,
            softWrap = false,
        )
    }
}
