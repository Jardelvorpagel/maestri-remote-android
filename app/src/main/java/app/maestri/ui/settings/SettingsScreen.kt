package app.maestri.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
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
    /** P5 usage lane: the Usage screen lives at its own route ("usage"). */
    openUsage: (() -> Unit)? = null,
    /** P5 routines lane: the Routines screen lives at its own route ("routines"). */
    openRoutines: (() -> Unit)? = null,
    /** P5 partituras lane: the Partituras screen lives at its own route ("partituras"). */
    openPartituras: (() -> Unit)? = null,
    /** Live Pi event-stream lane ("live"): caption states the relay state honestly. */
    openLive: (() -> Unit)? = null,
    liveRelayCaption: String = "not configured",
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
    // todo #56: Pi-agent terminal settings mirror (client-side DataStore; applying a
    // choice to the live session is wire scope and stays deferred — disclosed in-row).
    val agentStore = remember(context) { context?.let { AgentSettingsStore(it) } }
    val agentProvider = (agentStore?.provider?.collectAsState())?.value
        ?: AgentSettingsStore.DEFAULT_PROVIDER
    val agentModel = (agentStore?.model?.collectAsState())?.value
        ?: AgentSettingsStore.DEFAULT_MODEL
    val agentThinking = (agentStore?.thinkingLevel?.collectAsState())?.value
        ?: AgentSettingsStore.DEFAULT_THINKING
    var agentPicker by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints {
        val compact = maxWidth < 480.dp
        if (compact) {
            // v2.1 L9 phone arrangement (<480dp): serif root title, rows 52, tog 52x32
            SettingsPhoneBody(
                enabled = enabled,
                grace = grace,
                fontSp = fontSp,
                agentProvider = agentProvider,
                agentModel = agentModel,
                agentThinking = agentThinking,
                onBack = onBack,
                onToggle = { v -> scope.launch { appLock.setEnabled(v) } },
                onOpenGrace = { pickerOpen = true },
                onNotifications = { openNotificationsRoute?.invoke("notifications-settings") },
                onUsage = { openUsage?.invoke() },
                onRoutines = { openRoutines?.invoke() },
                onPartituras = { openPartituras?.invoke() },
                onLive = { openLive?.invoke() },
                liveRelayCaption = liveRelayCaption,
                onOpenAgentPicker = { agentPicker = it },
            )
        } else     Column(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp)) {
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
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        // design-v2 09: grouped ring cards — h2sec group labels, sgroup cards,
        // 56pt rows, mono values (WarmSilver), 51x31 terracotta toggle.
        GroupLabel("SECURITY")
        SettingsGroup {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(SettingsStrings.requireBiometrics, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        SettingsStrings.explainer,
                        fontSize = 12.sp,
                        color = LocalMaestriColors.current.secondaryLabel,
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
                    Text("Lock after…", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f)) // CANDIDATE label
                    Text(
                        when (grace) {
                            0 -> SettingsStrings.graceImmediately
                            300 -> SettingsStrings.graceAfter5Minutes
                            else -> SettingsStrings.graceAfter1Minute
                        },
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = LocalMaestriColors.current.secondaryLabel,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("›", fontSize = 18.sp, color = LocalMaestriColors.current.secondaryLabel)
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
                Text(SettingsStrings.notifications, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text("›", fontSize = 18.sp, color = LocalMaestriColors.current.secondaryLabel)
            }
            DividerHair()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(enabled = openUsage != null) { openUsage?.invoke() },
            ) {
                Text("Usage", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(
                    "provider limits · live wss",
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = LocalMaestriColors.current.secondaryLabel,
                )
                Spacer(Modifier.width(8.dp))
                Text("›", fontSize = 18.sp, color = LocalMaestriColors.current.secondaryLabel)
            }
            DividerHair()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(enabled = openRoutines != null) { openRoutines?.invoke() },
            ) {
                Text("Routines", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(
                    "scheduled prompts · host scheduler",
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = LocalMaestriColors.current.secondaryLabel,
                )
                Spacer(Modifier.width(8.dp))
                Text("›", fontSize = 18.sp, color = LocalMaestriColors.current.secondaryLabel)
            }
            DividerHair()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(enabled = openPartituras != null) { openPartituras?.invoke() },
            ) {
                Text("Partituras", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(
                    "canvas library · preview + stamp",
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = LocalMaestriColors.current.secondaryLabel,
                )
                Spacer(Modifier.width(8.dp))
                Text("›", fontSize = 18.sp, color = LocalMaestriColors.current.secondaryLabel)
            }
            DividerHair()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(enabled = openLive != null) { openLive?.invoke() },
            ) {
                Text("Live", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(
                    liveRelayCaption,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = LocalMaestriColors.current.secondaryLabel,
                )
                Spacer(Modifier.width(8.dp))
                Text("›", fontSize = 18.sp, color = LocalMaestriColors.current.secondaryLabel)
            }
            DividerHair()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Terminal font", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(
                    "JetBrains Mono NF · " + fontSp,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = LocalMaestriColors.current.secondaryLabel,
                )
            }
            DividerHair()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Push", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f)) // CANDIDATE label
                Text(
                    "unavailable · Foreground WSS", // honest: our push lane is foreground WSS (Option A)
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = LocalMaestriColors.current.secondaryLabel,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        AgentGroup(
            provider = agentProvider,
            model = agentModel,
            thinking = agentThinking,
            onOpenPicker = { agentPicker = it },
        )
        Spacer(Modifier.height(12.dp))
        HostManagedGroup()
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

    // todo #56 pickers (GracePickerDialog pattern: AlertDialog + RadioButton rows).
    when (agentPicker) {
        "provider" -> AgentPickerDialog(
            title = SettingsStrings.agentProviderTitle,
            options = AgentSettingsStore.PROVIDERS,
            selected = agentProvider,
            onPick = { v -> agentStore?.setProvider(v); agentPicker = null },
            onDismiss = { agentPicker = null },
        )
        "thinking" -> AgentPickerDialog(
            title = SettingsStrings.agentThinkingTitle,
            options = AgentSettingsStore.THINKING_LEVELS,
            selected = agentThinking,
            onPick = { v -> agentStore?.setThinkingLevel(v); agentPicker = null },
            onDismiss = { agentPicker = null },
        )
        "model" -> ModelPickerDialog(
            provider = agentProvider,
            selected = agentModel,
            onPick = { v -> agentStore?.setModel(v); agentPicker = null },
            onDismiss = { agentPicker = null },
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
        color = LocalMaestriColors.current.secondaryLabel,
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
    agentProvider: String,
    agentModel: String,
    agentThinking: String,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onOpenGrace: () -> Unit,
    onNotifications: () -> Unit,
    onUsage: (() -> Unit)?,
    onRoutines: (() -> Unit)?,
    onPartituras: (() -> Unit)?,
    onLive: (() -> Unit)?,
    liveRelayCaption: String,
    onOpenAgentPicker: (String) -> Unit,
) {
    val colors = LocalMaestriColors.current
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 20.sp, modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
            Text("Settings", style = app.maestri.ui.Style.RootTitleStyle)
        }
        Spacer(Modifier.height(16.dp))

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        GroupLabel("SECURITY")
        SettingsGroup {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(SettingsStrings.requireBiometrics, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(SettingsStrings.explainer, fontSize = 12.sp, color = LocalMaestriColors.current.secondaryLabel)
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
                    Text("Lock after…", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text(
                        when (grace) {
                            0 -> SettingsStrings.graceImmediately
                            300 -> SettingsStrings.graceAfter5Minutes
                            else -> SettingsStrings.graceAfter1Minute
                        },
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = LocalMaestriColors.current.secondaryLabel,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("›", fontSize = 18.sp, color = LocalMaestriColors.current.secondaryLabel)
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
                Text(SettingsStrings.notifications, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text("›", fontSize = 18.sp, color = LocalMaestriColors.current.secondaryLabel)
            }
            DividerHair()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(enabled = onUsage != null) { onUsage?.invoke() },
            ) {
                Text("Usage", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(
                    "provider limits · live wss",
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = LocalMaestriColors.current.secondaryLabel,
                )
                Spacer(Modifier.width(8.dp))
                Text("›", fontSize = 18.sp, color = LocalMaestriColors.current.secondaryLabel)
            }
            DividerHair()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(enabled = onRoutines != null) { onRoutines?.invoke() },
            ) {
                Text("Routines", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(
                    "scheduled prompts · host scheduler",
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = LocalMaestriColors.current.secondaryLabel,
                )
                Spacer(Modifier.width(8.dp))
                Text("›", fontSize = 18.sp, color = LocalMaestriColors.current.secondaryLabel)
            }
            DividerHair()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(enabled = onPartituras != null) { onPartituras?.invoke() },
            ) {
                Text("Partituras", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(
                    "canvas library · preview + stamp",
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = LocalMaestriColors.current.secondaryLabel,
                )
                Spacer(Modifier.width(8.dp))
                Text("›", fontSize = 18.sp, color = LocalMaestriColors.current.secondaryLabel)
            }
            DividerHair()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(enabled = onLive != null) { onLive?.invoke() },
            ) {
                Text("Live", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(
                    liveRelayCaption,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = LocalMaestriColors.current.secondaryLabel,
                )
                Spacer(Modifier.width(8.dp))
                Text("›", fontSize = 18.sp, color = LocalMaestriColors.current.secondaryLabel)
            }
            DividerHair()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Terminal font", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(
                    "JetBrains Mono NF · " + fontSp,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = LocalMaestriColors.current.secondaryLabel,
                )
            }
            DividerHair()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Push", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(
                    "unavailable · Foreground WSS",
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = LocalMaestriColors.current.secondaryLabel,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        AgentGroup(
            provider = agentProvider,
            model = agentModel,
            thinking = agentThinking,
            onOpenPicker = onOpenAgentPicker,
        )
        Spacer(Modifier.height(12.dp))
        HostManagedGroup()
        }
    }
}

/** todo #56 AGENT group — shared by the tablet + compact (<480dp) branches, so both
 *  stay byte-identical in structure (L9 pattern: shared code only, no branch forks). */
@Composable
private fun AgentGroup(
    provider: String,
    model: String,
    thinking: String,
    onOpenPicker: (String) -> Unit,
) {
    GroupLabel(SettingsStrings.agentGroup)
    SettingsGroup {
        AgentLinkRow(SettingsStrings.agentModel, model, onOpen = { onOpenPicker("model") })
        DividerHair()
        AgentLinkRow(SettingsStrings.agentProvider, provider, onOpen = { onOpenPicker("provider") })
        DividerHair()
        AgentLinkRow(SettingsStrings.agentThinking, thinking, onOpen = { onOpenPicker("thinking") })
    }
}

/** 56dp link row: sans label + mono value + chevron (APP-group pattern). */
@Composable
private fun AgentLinkRow(label: String, value: String, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onOpen() },
    ) {
        Text(label, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            color = LocalMaestriColors.current.secondaryLabel,
        )
        Spacer(Modifier.width(8.dp))
        Text("›", fontSize = 18.sp, color = LocalMaestriColors.current.secondaryLabel)
    }
}

/**
 * todo #56 HOST-MANAGED group — every desktop Pi-agent setting with no mobile
 * counterpart gets an honest-disabled row here (WarmSilver label, mono reason,
 * no click target). Nothing is silently dropped; see the spec table in
 * `2026-10-17-open-design-terminal-settings.md`.
 */
@Composable
private fun HostManagedGroup() {
    GroupLabel(SettingsStrings.hostGroup)
    SettingsGroup {
        DisabledRow(SettingsStrings.rowTerminalProgress, SettingsStrings.hostDriven)
        DividerHair()
        DisabledRow(SettingsStrings.rowSubagentRouting, SettingsStrings.managedByHost)
        DividerHair()
        DisabledRow(SettingsStrings.rowCompaction, SettingsStrings.managedByHost)
        DividerHair()
        DisabledRow(SettingsStrings.rowToolPolicy, SettingsStrings.managedByHost)
        DividerHair()
        DisabledRow(SettingsStrings.rowRetryPolicy, SettingsStrings.managedByHost)
        DividerHair()
        DisabledRow(SettingsStrings.rowTuiMode, SettingsStrings.desktopOnly)
        DividerHair()
        DisabledRow(SettingsStrings.rowSkillCommands, SettingsStrings.desktopOnly)
        DividerHair()
        DisabledRow(SettingsStrings.rowHardwareCursor, SettingsStrings.desktopOnly)
        DividerHair()
        DisabledRow(SettingsStrings.rowCacheNotices, SettingsStrings.managedByHost)
        DividerHair()
        DisabledRow(SettingsStrings.rowProjectTrust, SettingsStrings.managedByHost)
        DividerHair()
        DisabledRow(SettingsStrings.rowAgentCards, SettingsStrings.serverSide)
        DividerHair()
        DisabledRow(SettingsStrings.rowComposerChrome, SettingsStrings.desktopOnly)
        DividerHair()
        DisabledRow(SettingsStrings.rowExtensions, SettingsStrings.managedByHost)
    }
}

/** Honest-disabled row: no clickable modifier by construction — cannot be tapped. */
@Composable
private fun DisabledRow(label: String, reason: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
    ) {
        Text(label, fontSize = 13.5.sp, color = LocalMaestriColors.current.secondaryLabel, modifier = Modifier.weight(1f))
        Text(
            reason,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            color = LocalMaestriColors.current.secondaryLabel.copy(alpha = 0.7f),
        )
    }
}

/** Flat option picker (GracePickerDialog pattern: AlertDialog + RadioButton rows). */
@Composable
private fun AgentPickerDialog(
    title: String,
    options: List<String>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option) }
                            .padding(vertical = 6.dp),
                    ) {
                        RadioButton(selected = option == selected, onClick = { onPick(option) })
                        Spacer(Modifier.width(8.dp))
                        Text(option, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Model picker, grouped under the current provider header (desktop catalog order). */
@Composable
private fun ModelPickerDialog(
    provider: String,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val models = AgentSettingsStore.MODELS_BY_PROVIDER[provider].orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SettingsStrings.agentModelTitle, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    provider,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                    color = LocalMaestriColors.current.secondaryLabel,
                    modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
                )
                models.forEach { model ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(model) }
                            .padding(vertical = 6.dp),
                    ) {
                        RadioButton(selected = model == selected, onClick = { onPick(model) })
                        Spacer(Modifier.width(8.dp))
                        Text(model, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
