package app.maestri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import android.graphics.Typeface
import app.maestri.remote.R
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import app.maestri.wire.Geometry
import app.maestri.wire.SocketState
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.MaestriRadii
import app.maestri.ui.Style.MaestriType
import app.maestri.ui.Style.Palette
import app.maestri.ui.Style.TerminalThemes
import app.maestri.ui.Style.controlShape
import app.maestri.ui.Style.pillShape

/**
 * Live terminal renderer on the REAL libvterm engine (ConnectBot termlib, todo #62).
 *
 * Host PTY bytes flow verbatim into `writeInput` — ANSI colors, block cursor,
 * alt-screen and genuine in-session scrollback all render. Input goes out as JSON
 * frames; resize is clamped client-side to 20..250 × 5..120. The MiniTerm path still
 * feeds the HUD readouts (geometry, cursor) but is no longer drawn here.
 *
 * Follow behavior is native to the library: new output pins to the tail while the
 * user is at the bottom and holds position while scrolled up (defect-3 core). The
 * #40 pill has no public API behind it in 0.3.13 (ScrollController is internal-only
 * and plain Terminal hardcodes its callback to null), so the pill is gone — the
 * report carries the ruling request.
 *
 * Design v2 (§4-03): Tokyo Night container (bg `#1A1B26`, border `#23233A`), JBM NF at
 * the persisted Aa size (13 default, clamp 10–20, 1.55 leading), a 44 dp Tokyo-blue
 * quickkey rail on the warm keyboard tray, and a fullscreen state that hides the HUD row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveTerminalView(
    state: SocketState,
    error: String?,
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
     * L3 phone-compact leg (<480dp, passed by the screen): enables the viewport-fit
     * report (defect 2) and the native hold-while-away follow (defect 3 core). The
     * defect-1 row-grid snap is structural now (integral transcript rows). False (tablet)
     * keeps the previous always-follow behavior exactly.
     */
    compact: Boolean = false,
    /** Compact only: measured phone-fit (cols, rows) for the screen's refit wiring. */
    onViewportFit: ((cols: Int, rows: Int) -> Unit)? = null,
    /**
     * todo #62: the live termlib emulator (one per terminal, owned by the screen).
     * Null before creation — the viewport shows the waiting placeholder until then.
     */
    termlib: TerminalEmulator? = null,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }

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
    // JBM NF for the renderer (the same face the fit math measures, so advances agree).
    val ctx = LocalContext.current
    val jbmTypeface = remember {
        runCatching { ResourcesCompat.getFont(ctx, R.font.jetbrainsmono_nf_regular) }.getOrNull()
            ?: Typeface.MONOSPACE
    }

    // ---- auto-size (todo #62): forcedSize is the grid contract. Phone-compact takes
    // the measured viewport fit (Aa metrics, same numbers as the dialog-prefill report);
    // tablet takes the host geometry verbatim (dialog-owned, byte-identical path).
    // Fallbacks always resolve to the host grid — never below it — so the library can
    // never show a window the host isn't drawing. The emulator reports its actual grid
    // through onResize, which refits the host; that converges instead of looping because
    // the host echo matches lastSentGrid. Grow freely, never shrink below the host, so
    // keyboard transients (smaller box, same host) keep the grid stable with no resend.
    val liveForced: Pair<Int, Int>? = geometry?.let { host ->
        if (!compact) {
            host.rows to host.cols
        } else if (viewportPx != IntSize.Zero && fitCols >= 20 && fitRows >= 5) {
            maxOf(fitRows.coerceIn(5, 120), host.rows) to maxOf(fitCols.coerceIn(20, 250), host.cols)
        } else {
            host.rows to host.cols
        }
    }

    val cols = geometry?.cols ?: 80

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
        // ---- keyboard-aware viewport (todo #57): with Gboard open the fixed 300dp box no
        // longer fits above the composer, so the viewport flexes (weight) while the IME
        // padding at the screen root lifts the whole bottom bar. Keyboard closed — or
        // fullscreen, which already flexes — resolves to the exact previous modifiers.
        val imeOpen = WindowInsets.isImeVisible
        val bodyModifier = if (fullscreen || imeOpen) {
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
            if (termlib != null && liveForced != null) {
                // The real grid (libvterm Canvas). Pinch zoom is frozen at the Aa size
                // (min == max == initial) so the stepper stays the single type control
                // — the component still applies it live on every step. keyboardEnabled
                // is false: our composer + rail own all input, so the library never
                // takes the IME. Focus stays on our own input field.
                Terminal(
                    terminalEmulator = termlib,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(MaestriRadii.Card)),
                    typeface = jbmTypeface,
                    initialFontSize = fontSizeSp.sp,
                    minFontSize = fontSizeSp.sp,
                    maxFontSize = fontSizeSp.sp,
                    backgroundColor = TerminalThemes.TokyoNight.background,
                    foregroundColor = TerminalThemes.TokyoNight.foreground,
                    keyboardEnabled = false,
                    forcedSize = liveForced,
                    onHyperlinkClick = {},
                )
            } else {
                Text(
                    if (state == SocketState.OPEN) "(empty screen)" else "…waiting for PTY bytes",
                    fontFamily = MaestriType.Mono,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * 1.55f).sp,
                    color = TerminalThemes.TokyoNight.dim,
                    modifier = Modifier.padding(8.dp),
                )
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
