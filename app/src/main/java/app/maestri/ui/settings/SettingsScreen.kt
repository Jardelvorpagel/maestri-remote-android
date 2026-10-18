package app.maestri.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.Motion
import app.maestri.ui.Style.MaestriRadii
import app.maestri.ui.Style.RootTitle
import app.maestri.ui.Style.Palette
import app.maestri.ui.Style.warmRing
import app.maestri.wire.session.WireSession
import kotlinx.coroutines.launch

/**
 * Settings v1 — the Privacy section ONLY (the fuller surface folds in when another
 * lane seeds one; flagged W-2/W-4 in the report). Reachable from an unlocked session
 * only (the gear affordance sits behind the lock gate by construction).
 *
 * Rulings honored: (A) neutral toggle label; (C) no custom keypad — the toggle engages
 * BiometricPrompt(STRONG | DEVICE_CREDENTIAL), never an in-app code entry.
 */
@Composable
fun SettingsScreen(
    session: WireSession,
    appLock: AppLockStore,
    onBack: () -> Unit,
    /** P4-A owns the real row; navigate via a existence-checked callback (W-4). */
    openNotificationsRoute: ((String) -> Unit)? = null,
) {
    val colors = app.maestri.ui.Style.LocalMaestriColors.current
    val scope = rememberCoroutineScope()
    val enabled by appLock.enabled.collectAsState()
    val grace by appLock.graceSeconds.collectAsState()
    val context = LocalContext.current as? ComponentActivity
    var pickerOpen by remember { mutableStateOf(false) }
    val canBiometrics = remember { context != null && AppLockGate.hasStrongBiometry(context) }
    // real terminal font size (design "JetBrains Mono NF · 13") — same DataStore the
    // Aa stepper writes; a mirror instance, nothing user-identifying stored/changed.
    val fontStore = remember(context) { context?.let { TerminalFontStore(it) } }
    val fontSp =
        (fontStore?.sizeSp?.collectAsState())?.value ?: TerminalFontStore.DEFAULT_SP

    BoxWithConstraints {
        val compact = maxWidth < 480.dp
        if (compact) {
            // v2.1 L9 phone arrangement (<480dp): serif root title, rows 52, tog 52x32
            SettingsPhoneBody(
                enabled = enabled,
                grace = grace,
                fontSp = fontSp,
                onBack = onBack,
                onToggle = { v -> scope.launch { appLock.setEnabled(v) } },
                onOpenGrace = { pickerOpen = true },
                onNotifications = { openNotificationsRoute?.invoke("notifications-settings") },
            )
        } else     Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                fontSize = 20.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 12.dp),
            )
            RootTitle(
                SettingsStrings.settings,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))
        // design-v2 09: grouped ring cards — h2sec group labels, sgroup cards,
        // 56pt rows, mono values (WarmSilver), 51x31 terracotta toggle.
        GroupLabel("SECURITY")
        SettingsGroup {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(SettingsStrings.requireBiometrics, fontSize = 13.5.sp, color = Palette.Ivory)
                    Text(
                        SettingsStrings.explainer,
                        fontSize = 12.sp,
                        color = Palette.WarmSilver,
                    )
                }
                Spacer(Modifier.width(12.dp))
                SettingsToggle(checked = enabled, onCheckedChange = { v -> scope.launch { appLock.setEnabled(v) } })
            }
            if (enabled) {
                DividerHair()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable { pickerOpen = true },
                ) {
                    Text("Lock after…", fontSize = 13.5.sp, color = Palette.Ivory, modifier = Modifier.weight(1f)) // CANDIDATE label
                    Text(
                        when (grace) {
                            0 -> SettingsStrings.graceImmediately
                            300 -> SettingsStrings.graceAfter5Minutes
                            else -> SettingsStrings.graceAfter1Minute
                        },
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Palette.WarmSilver,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("›", fontSize = 18.sp, color = Palette.WarmSilver)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        GroupLabel("APP")
        SettingsGroup {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { openNotificationsRoute?.invoke("notifications-settings") },
            ) {
                Text(SettingsStrings.notifications, fontSize = 13.5.sp, color = Palette.Ivory, modifier = Modifier.weight(1f))
                Text("›", fontSize = 18.sp, color = Palette.WarmSilver)
            }
            DividerHair()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Terminal font", fontSize = 13.5.sp, color = Palette.Ivory, modifier = Modifier.weight(1f))
                Text(
                    "JetBrains Mono NF · " + fontSp,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.WarmSilver,
                )
            }
            DividerHair()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Push", fontSize = 13.5.sp, color = Palette.Ivory, modifier = Modifier.weight(1f)) // CANDIDATE label
                Text(
                    "unavailable · Foreground WSS", // honest: our push lane is foreground WSS (Option A)
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.WarmSilver,
                )
            }
        }
    }
    }

    if (pickerOpen) {
        GracePickerDialog(
            current = grace,
            onPick = { v ->
                pickerOpen = false
                scope.launch { appLock.setGraceSeconds(v) }
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/** Grace picker: iOS order, verbatim labels → 0/60/300 s (spec 2.2; default 60 CANDIDATE). */
@Composable
private fun GracePickerDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SettingsStrings.explainer, fontSize = 16.sp) },
        text = {
            Column {
                GraceRow(SettingsStrings.graceImmediately, 0, current, onPick)
                GraceRow(SettingsStrings.graceAfter1Minute, 60, current, onPick)
                GraceRow(SettingsStrings.graceAfter5Minutes, 300, current, onPick)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GraceRow(label: String, seconds: Int, current: Int, onPick: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(seconds) }
            .padding(vertical = 6.dp),
    ) {
        RadioButton(selected = current == seconds, onClick = { onPick(seconds) })
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** design-v2 09 h2sec: 10sp/500, 1px tracking, uppercase, warm silver. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
        color = Palette.WarmSilver,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp),
    )
}

/** .sgroup: rounded ring card, padding 4x16. */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalMaestriColors.current
    Surface(
        color = colors.canvasSurface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(MaestriRadii.Card),
        modifier = Modifier
            .fillMaxWidth()
            .warmRing(colors.hairline, radius = MaestriRadii.Card),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            content = content,
        )
    }
}

/** .rrow hairline separator. */
@Composable
private fun DividerHair() {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 1.dp)
            .height(1.dp)
            .background(LocalMaestriColors.current.hairline),
    )
}

/** .tog: 51x31 pill; ON = terracotta (inset white 10% + glow .30); OFF = surface + ring; 25dp ivory knob. */
@Composable
private fun SettingsToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    // tablet defaults (v2.0 task-ruled); compact passes the v2.1 .tog 52x32/28 values
    width: Dp = 51.dp,
    height: Dp = 31.dp,
    knob: Dp = 25.dp,
    knobOn: Dp = 24.dp,
    knobOff: Dp = 2.dp,
) {
    val colors = LocalMaestriColors.current
    val knobStart by animateDpAsState(
        targetValue = if (checked) knobOn else knobOff,
        animationSpec = tween(Motion.Fast, easing = Motion.Ease),
        label = "lock-toggle-knob",
    )
    // Round-3 gate fix: background() ships a brush-form and a color-form overload;
    // the inline if-else here mixed Brush and Color branches so neither overload
    // matched, and the :293 shadow errors were inference cascades. Branch hoisted:
    // both sides now produce a Brush (solid = same-color linear gradient, visuals
    // identical to the flat fill — chat-bubble precedent). Offset import verified:
    // androidx.compose.ui.geometry.
    val toggleFill = if (checked) {
        Brush.linearGradient(
            0f to Palette.TerracottaLight,
            1f to Palette.Terracotta,
            start = Offset(0f, 0f),
            end = Offset(1f, 1f),
        )
    } else {
        Brush.linearGradient(0f to colors.canvasSurface, 1f to colors.canvasSurface)
    }
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(CircleShape)
            .background(toggleFill, CircleShape)
            .let {
                if (checked) {
                    it.shadow(
                            elevation = 6.dp,
                            shape = CircleShape,
                            ambientColor = Palette.Terracotta.copy(alpha = 0.30f),
                            spotColor = Palette.Terracotta.copy(alpha = 0.30f),
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                } else {
                    it.border(1.dp, colors.hairline, CircleShape)
                }
            }
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = knobStart)
                .size(knob)
                .clip(CircleShape)
                .background(Palette.Ivory, CircleShape)
                .shadow(
                    elevation = 2.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.35f),
                    spotColor = Color.Black.copy(alpha = 0.35f),
                ),
        )
    }
}


/** v2.1 L9 phone arrangement (<480dp): serif root title (RootTitleStyle 28/30.8/500),
 *  sgroup rows 52, .tog 52x32/28 gradient toggle. Glass/blur untouched (L10 held). */
@Composable
private fun SettingsPhoneBody(
    enabled: Boolean,
    grace: Int,
    fontSp: Int,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onOpenGrace: () -> Unit,
    onNotifications: () -> Unit,
) {
    val colors = LocalMaestriColors.current
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 20.sp, modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
            Text("Settings", style = app.maestri.ui.Style.RootTitleStyle)
        }
        Spacer(Modifier.height(16.dp))

        GroupLabel("SECURITY")
        SettingsGroup {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(SettingsStrings.requireBiometrics, fontSize = 13.5.sp, color = Palette.Ivory)
                    Text(SettingsStrings.explainer, fontSize = 12.sp, color = Palette.WarmSilver)
                }
                Spacer(Modifier.width(12.dp))
                SettingsToggle(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    width = 52.dp,
                    height = 32.dp,
                    knob = 28.dp,
                    knobOn = 22.dp,
                    knobOff = 2.dp,
                )
            }
            if (enabled) {
                DividerHair()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable { onOpenGrace() },
                ) {
                    Text("Lock after…", fontSize = 13.5.sp, color = Palette.Ivory, modifier = Modifier.weight(1f))
                    Text(
                        when (grace) {
                            0 -> SettingsStrings.graceImmediately
                            300 -> SettingsStrings.graceAfter5Minutes
                            else -> SettingsStrings.graceAfter1Minute
                        },
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Palette.WarmSilver,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("›", fontSize = 18.sp, color = Palette.WarmSilver)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        GroupLabel("APP")
        SettingsGroup {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable { onNotifications() },
            ) {
                Text(SettingsStrings.notifications, fontSize = 13.5.sp, color = Palette.Ivory, modifier = Modifier.weight(1f))
                Text("›", fontSize = 18.sp, color = Palette.WarmSilver)
            }
            DividerHair()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Terminal font", fontSize = 13.5.sp, color = Palette.Ivory, modifier = Modifier.weight(1f))
                Text(
                    "JetBrains Mono NF · " + fontSp,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.WarmSilver,
                )
            }
            DividerHair()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Push", fontSize = 13.5.sp, color = Palette.Ivory, modifier = Modifier.weight(1f))
                Text(
                    "unavailable · Foreground WSS",
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.WarmSilver,
                )
            }
        }
    }
}
