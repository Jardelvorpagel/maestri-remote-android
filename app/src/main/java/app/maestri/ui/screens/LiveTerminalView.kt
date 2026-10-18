package app.maestri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.rememberTextMeasurer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.wire.Geometry
import app.maestri.wire.SocketState
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.MaestriRadii
import app.maestri.ui.Style.MaestriType
import app.maestri.ui.Style.Palette
import app.maestri.ui.Style.Rhythm
import app.maestri.ui.Style.TerminalThemes
import app.maestri.ui.Style.controlShape
import app.maestri.ui.Style.monoHud
import app.maestri.ui.Style.pillShape
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Live terminal renderer on top of the tiny ANSI emulator ([app.maestri.wire.MiniTerm]).
 *
 * The emulator renders plain text (colors consumed, not drawn) — the documented degrade
 * path for an MVP without a full xterm engine. Input goes out as JSON frames; resize is
 * clamped client-side to 20..250 × 5..120.
 *
 * Design v2 (§4-03): Tokyo Night container (bg `#1A1B26`, border `#23233A`, ink `#A9B1D6`,
 * prompt blue `#7AA2F7` at weight 700), JBM NF at the persisted Aa size (13 default,
 * clamp 10–20, 1.55 leading), a 7 px block cursor at the reported cell, a 44 dp Tokyo-blue
 * quickkey rail on the warm keyboard tray, and a fullscreen state that hides the HUD row.
 */
@Composable
fun LiveTerminalView(
    state: SocketState,
    error: String?,
    text: String,
    cursor: Pair<Int, Int>?,
    geometry: Geometry?,
    canResize: Boolean,
    isOwner: Boolean,
    fontSizeSp: Int = 13,
    fullscreen: Boolean = false,
    onChat: (() -> Unit)? = null,
    onInput: (String) -> Unit,
    onResizeRequest: () -> Unit,
    /**
     * L3 phone-compact leg (<480dp, passed by the screen): enables the row-grid snap
     * (defect 1), the viewport-fit report (defect 2) and the scroll governor (defect 3).
     * False (tablet) keeps the previous always-follow behavior exactly.
     */
    compact: Boolean = false,
    /** Compact only: measured phone-fit (cols, rows) for the screen's refit wiring. */
    onViewportFit: ((cols: Int, rows: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val screenScroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    // ---- viewport fit (defect 2): the mono advance is MEASURED once per Aa size, so the
    // phone refit is computed from real JetBrains Mono NF glyphs, not a 0.6em guess.
    // Reno 411dp @ 13sp lands ~47 cols — inside the 48–60 band within rounding.
    val density = LocalDensity.current
    var viewportPx by remember { mutableStateOf(IntSize.Zero) }
    val measurer = rememberTextMeasurer()
    val monoStyle = TextStyle(fontFamily = MaestriType.Mono, fontSize = fontSizeSp.sp)
    val charPx = remember(monoStyle) { measurer.measure("0123456789", monoStyle).size.width / 10f }
    val linePx = with(density) { (fontSizeSp * 1.55f).sp.toPx() }
    val padPx = with(density) { 8.dp.toPx() }
    val fitCols = ((viewportPx.width - 2 * padPx) / charPx).toInt()
    val fitRows = ((viewportPx.height - 2 * padPx) / linePx).toInt()
    LaunchedEffect(fitCols, fitRows) {
        if (compact && viewportPx != IntSize.Zero && fitCols >= 20 && fitRows >= 5) {
            onViewportFit?.invoke(fitCols.coerceIn(20, 250), fitRows.coerceIn(5, 120))
        }
    }

    // ---- scroll governor (defect 3, fresh user report): the tail pins ONLY while the user
    // is at the bottom. A manual scroll-up pauses the tail and raises the back-to-bottom
    // affordance; new output while paused raises the STRICT pill instead (chat #40 parity:
    // never on a plain scroll, never while pinned). Tapping either resumes the tail.
    val atBottom by remember {
        derivedStateOf { screenScroll.value >= screenScroll.maxValue - 4 }
    }
    val atBottomNow by rememberUpdatedState(atBottom)
    var newWhileAway by remember { mutableStateOf(false) }
    var newCount by remember { mutableStateOf(0) }

    /** Defect 1: snap the rest offset to the row grid (8dp phase + whole line boxes) so the
    top edge never half-decapitates a row. Wrapped rows stay whole too — every laid-out
    line box is exactly one linePx tall on the same grid. */
    suspend fun snapToRowGrid() {
        val snapped = (padPx + ((screenScroll.value - padPx) / linePx).roundToInt() * linePx)
            .toInt().coerceIn(0, screenScroll.maxValue)
        if (snapped != screenScroll.value) screenScroll.scrollTo(snapped)
    }

    suspend fun resumeTail() {
        newWhileAway = false
        newCount = 0
        screenScroll.animateScrollTo(screenScroll.maxValue)
        if (compact) snapToRowGrid()
    }

    LaunchedEffect(text) {
        if (!compact || atBottomNow) {
            newWhileAway = false
            newCount = 0
            screenScroll.animateScrollTo(screenScroll.maxValue)
            if (compact) snapToRowGrid()
        } else {
            newWhileAway = true
            newCount++
        }
    }
    LaunchedEffect(atBottom) {
        if (atBottom) {
            newWhileAway = false
            newCount = 0
        }
    }

    val cols = geometry?.cols ?: 80
    val cursorCol: Int? = cursor?.first
    val cursorRow: Int? = cursor?.second

    Column(modifier) {
        if (!fullscreen) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(60.dp)) { ConnChip(state, error) }
                Text(
                    "$cols×${geometry?.rows ?: "?"}" + (cursor?.let { "  r${it.second + 1}c${it.first + 1}" } ?: ""),
                    fontFamily = MaestriType.Mono,
                    fontSize = 11.sp,
                    color = TerminalThemes.TokyoNight.dim,
                    modifier = Modifier.weight(1f),
                )
                if (canResize) {
                    OutlinedButton(onClick = onResizeRequest) { Text("resize", fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // ---- Tokyo Night container -------------------------------------------------
        val bodyModifier = if (fullscreen) {
            Modifier.fillMaxWidth().weight(1f)
        } else {
            Modifier.fillMaxWidth().height(300.dp)
        }
        Box(
            bodyModifier
                .background(TerminalThemes.TokyoNight.background, RoundedCornerShape(MaestriRadii.Card))
                .border(1.dp, TerminalThemes.TokyoNight.border, RoundedCornerShape(MaestriRadii.Card))
                .onSizeChanged { viewportPx = it }
        ) {
            Box(Modifier.fillMaxSize().verticalScroll(screenScroll)) {
            val shown = text.ifBlank {
                if (state == SocketState.OPEN) "(empty screen)" else "…waiting for PTY bytes"
            }
            if (text.isBlank()) {
                Text(
                    shown,
                    fontFamily = MaestriType.Mono,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * 1.55f).sp,
                    color = TerminalThemes.TokyoNight.dim,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    shown.split("\n").forEachIndexed { row, line ->
                        Text(
                            screenLine(
                                line = line,
                                cursorCol = cursorCol.takeIf { row == cursorRow },
                            ),
                            fontFamily = MaestriType.Mono,
                            fontSize = fontSizeSp.sp,
                            lineHeight = (fontSizeSp * 1.55f).sp,
                        )
                    }
                }
            }
            }
            // Scroll governor affordance (defect 3, compact only): back-to-bottom while merely
            // away, strict new-content pill once output arrived while paused. Same visual
            // pattern as the chat scroll pill (elevated glass + hairline + mono 11).
            if (compact && !atBottom) {
                TextButton(
                    onClick = { scope.launch { resumeTail() } },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .heightIn(min = Rhythm.TouchTarget.dp)
                        .background(LocalMaestriColors.current.canvasChromeElevated, pillShape())
                        .border(1.dp, LocalMaestriColors.current.hairline, pillShape()),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    Text(
                        if (newWhileAway) "↓ $newCount new" else "↓ Bottom",
                        style = monoHud(11, color = MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        // ---- input + the warm keyboard tray (real IME stays the OS's) ---------------
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Type into the terminal${if (isOwner) "" else " (discarded: guest role)"}") },
            singleLine = true,
            enabled = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                // Terracotta return key on the tray.
                Button(
                    onClick = {
                        if (input.isNotEmpty()) {
                            onInput(input)
                            input = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalMaestriColors.current.accent,
                        contentColor = LocalMaestriColors.current.onAccent,
                    ),
                ) { Text("\u23CE") }
            },
        )
        Spacer(Modifier.height(6.dp))
        // Quickkeys: 44 dp Tokyo-blue tint chips, horizontally scrolling, above the keyboard.
        // todo #42 narrow-affordance: the rail overflows ~411dp-wide phones and the last key
        // sat half-clipped with no cue — the trailing spacer + the right-edge fade (below)
        // give the horizontal scroll a visible affordance; touch targets stay 44dp.
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.Paper, controlShape())
                    .padding(6.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
            QuickKey("\u23CE", "Enter key", TerminalThemes.TokyoNight.blue) { onInput("\r") }
            QuickKey("\u2303C", "Control C — interrupt", TerminalThemes.TokyoNight.blue) { onInput("\u0003") }
            QuickKey("\u2303D", "Control D — end of file", TerminalThemes.TokyoNight.blue) { onInput("\u0004") }
            QuickKey("\u2303L", "Control L — clear screen", TerminalThemes.TokyoNight.blue) { onInput("\u000C") }
            QuickKey("\u2303R", "Control R — reverse search", TerminalThemes.TokyoNight.blue) { onInput("\u0012") }
            // ⌘ has no PTY encoding; the rail maps ⌘K to Ctrl-K (kill-line), the nearest byte.
            QuickKey("\u2318K", "Command K — kill line", TerminalThemes.TokyoNight.blue) { onInput("\u000B") }
            QuickKey("\u21E5", "Tab key", TerminalThemes.TokyoNight.blue) { onInput("\t") }
            QuickKey("esc", "Escape key", TerminalThemes.TokyoNight.blue) { onInput("\u001B") }
            QuickKey("\u2191", "Arrow up", TerminalThemes.TokyoNight.blue) { onInput("\u001B[A") }
            QuickKey("\u2193", "Arrow down", TerminalThemes.TokyoNight.blue) { onInput("\u001B[B") }
            QuickKey("\u2190", "Arrow left", TerminalThemes.TokyoNight.blue) { onInput("\u001B[D") }
            QuickKey("\u2192", "Arrow right", TerminalThemes.TokyoNight.blue) { onInput("\u001B[C") }
                onChat?.let {
                    QuickKey("Chat \u21E2", "Switch to chat mode", TerminalThemes.TokyoNight.green, onClick = it)
                }
                Spacer(Modifier.width(28.dp))
            }
            // Scroll-affordance fade at the rail's right edge (round 2: 36dp, ramp
            // starting at 0.2 so the peeked key reads as dissolving into the paper).
            // A plain background Box is pointer-transparent — key taps stay live.
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(36.dp)
                    .height(56.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Palette.Paper.copy(alpha = 0f),
                                Palette.Paper.copy(alpha = 0.6f),
                                Palette.Paper,
                            ),
                        ),
                    ),
            )
        }
        Spacer(Modifier.height(4.dp))
        val warn = when {
            state == SocketState.CLOSED && error != null -> "stream down: $error — reconnecting…"
            state == SocketState.CONNECTING -> "connecting…"
            state == SocketState.IDLE -> "stream off"
            else -> null
        }
        warn?.let {
            Text(
                it,
                color = if (state == SocketState.CLOSED) MaterialTheme.colorScheme.error else LocalMaestriColors.current.warning,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * One rendered screen line: an optional prompt prefix in Tokyo blue at weight 700, the body
 * in Tokyo ink, and — on the cursor row — the 7 px block cursor glyph at the reported column.
 */
private fun screenLine(line: String, cursorCol: Int?): AnnotatedString = buildAnnotatedString {
    val promptEnd = promptPrefixEnd(line)
    if (promptEnd > 0) {
        withStyle(SpanStyle(color = TerminalThemes.TokyoNight.prompt, fontWeight = FontWeight.Bold)) {
            append(line.substring(0, promptEnd))
        }
    }
    val body = line.substring(promptEnd.coerceAtMost(line.length))
    if (cursorCol == null) {
        withStyle(SpanStyle(color = TerminalThemes.TokyoNight.foreground)) { append(body) }
    } else {
        val at = cursorCol.coerceIn(0, body.length)
        withStyle(SpanStyle(color = TerminalThemes.TokyoNight.foreground)) { append(body.substring(0, at)) }
        withStyle(SpanStyle(color = TerminalThemes.TokyoNight.cursor)) {
            append(TerminalThemes.TokyoNight.cursorBlock)
        }
        withStyle(SpanStyle(color = TerminalThemes.TokyoNight.foreground)) { append(body.substring(at)) }
    }
}

/**
 * Length of the shell prompt prefix (`$ `, `# `, `> `, `❯ ` and `user@host:~$ ` forms), 0 when
 * the line does not open with one. Bounded to 40 chars so a prose line ending in `>` is safe.
 */
private fun promptPrefixEnd(line: String): Int {
    val m = Regex("^.{0,40}?[$#%\\u276F\\u00BB>]\\s").find(line) ?: return 0
    return m.value.length
}

@Composable
private fun QuickKey(label: String, desc: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .defaultMinSize(minWidth = 44.dp)
            .height(44.dp)
            .background(tint.copy(alpha = 0.16f), pillShape())
            .border(1.dp, tint.copy(alpha = 0.38f), pillShape())
            .semantics { contentDescription = desc }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        // label glyphs carry no text semantics — [desc] is the accessible/automation name.
        Text(label, fontFamily = MaestriType.Mono, fontSize = 13.sp, color = tint)
    }
}
