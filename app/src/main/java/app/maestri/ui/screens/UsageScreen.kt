package app.maestri.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.MaestriRadii
import app.maestri.ui.Style.MaestriType
import app.maestri.ui.Style.Overline
import app.maestri.ui.Style.Palette
import app.maestri.ui.Style.RootTitle
import app.maestri.wire.AgentUsageMeter
import app.maestri.wire.AgentUsageProvider
import app.maestri.wire.AgentUsageReading
import app.maestri.wire.AgentUsageSocket
import app.maestri.wire.AgentUsageWindow
import app.maestri.wire.session.WireSession
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Design-v2 frame 08 — Usage ring HUD sheet, now LIVE.
 *
 * The arcs read the real agentUsage stream (corpus §5ter): the screen owns one
 * [AgentUsageSocket] subscription — opened while composed, closed on leave — whose
 * ServerMessage {"type":"agentUsage","usage":{…}} replaces its state whole per frame.
 * Any absent/out-of-range/NaN usedFraction renders honest-empty (track + "—").
 * The ⟳ affordance stays inert by contract: the host's canRefresh flag has no
 * client-reachable trigger (every agent-usage REST route probed 404) — its
 * contentDescription states that instead of pretending.
 *
 * Pixel geometry is the existing sheet's: ring boxes 86×86 (compact 92×92),
 * radius 36 (compact 38), stroke 7 dp, JBM mono 17 sp labels, mono 10.5 captions,
 * `.rings` gap 26 (compact 22) centred, overline, `.json` mono card, meter row.
 */
object UsageNav {
    const val pattern = "usage"
    fun route(): String = pattern
}

/** Registers the Usage root in the app's NavHost; reachable via the Settings APP group. */
fun NavGraphBuilder.usageDestination(session: WireSession, pop: () -> Unit) {
    composable(UsageNav.pattern) {
        UsageScreen(session = session, onBack = pop)
    }
}

/** One (provider, meter, window) triple — every live window renders as a ring. */
private data class LiveRing(
    val provider: AgentUsageProvider,
    val meter: AgentUsageMeter,
    val window: AgentUsageWindow,
)

private fun liveRings(usage: AgentUsageReading?): List<LiveRing> =
    usage?.providers?.flatMap { p ->
        p.meters.flatMap { m -> m.windows.map { w -> LiveRing(p, m, w) } }
    } ?: emptyList()

private fun validFraction(raw: Double?): Double? =
    if (raw != null && raw.isFinite() && raw >= 0.0 && raw <= 1.0) raw else null

/** Data-role stroke from the packet's colorHex (plain 6-hex); unmapped → neutral warm silver. */
private fun providerColor(p: AgentUsageProvider): Color =
    p.colorHex?.let {
        runCatching { Color((it.toLong(16) and 0xFFFFFF).toInt() or 0xFF000000.toInt()) }.getOrNull()
    } ?: Palette.WarmSilver

private fun meterLabel(m: AgentUsageMeter): String = m.labelText ?: m.labelKey ?: m.id

private val resetsTime = DateTimeFormatter.ofPattern("HH:mm")
private val resetsDate = DateTimeFormatter.ofPattern("MMM d HH:mm")

private fun formatResets(raw: String): String =
    runCatching {
        val t = Instant.parse(raw).atZone(ZoneId.systemDefault())
        val now = java.time.LocalDateTime.now(ZoneId.systemDefault())
        if (t.toLocalDate() == now.toLocalDate()) t.format(resetsTime) else t.format(resetsDate)
    }.getOrDefault(raw.take(24))

private fun capBottom(w: AgentUsageWindow, p: AgentUsageProvider): String =
    w.resetsAt?.let { "resets ${formatResets(it)}" }
        ?: if (p.limitsApply == true) "limits apply" else ""

private fun ringLab(w: AgentUsageWindow): String {
    val f = validFraction(w.usedFraction)
    return if (f == null) "—" else "${(f * 100).toInt()}%"
}

/** The single meter-summary row's data: the fullest live window, or null when none. */
private fun fullestRing(usage: AgentUsageReading?): LiveRing? =
    liveRings(usage).maxByOrNull { r -> validFraction(r.window.usedFraction) ?: -1.0 }

/**
 * The ring: 7dp butt-cap track + vendor round-cap arc + centred numeric. Defaults =
 * the tablet values (86×86, r36, JBM 600 17, .rcap 10.5/1.5 w110, cap gap 8);
 * [compact] = the v2.1 frame-08 phone values (92×92, r38, JBM 700 17, .rcap
 * 10.5/1.55 w112, cap gap 10).
 */
@Composable
private fun MeterRing(
    fraction: Double?,
    arcColor: Color,
    lab: String,
    capTop: String,
    capBottom: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val track = Palette.DarkWarm
    val stroke = 7.dp
    val boxSize: Dp = if (compact) 92.dp else 86.dp
    val radius: Dp = if (compact) 38.dp else 36.dp
    val capGap = if (compact) 10.dp else 8.dp
    val capWidth: Dp = if (compact) 112.dp else 110.dp
    val capLineHeight: TextUnit = if (compact) 16.275.sp else 15.75.sp
    val capColor: Color = Palette.StoneGray
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(boxSize)) {
            Canvas(Modifier.fillMaxSize()) {
                val r = radius.toPx()
                val topLeft = Offset(
                    size.width / 2f - r - stroke.toPx() / 2f,
                    size.height / 2f - r - stroke.toPx() / 2f,
                )
                val arcSize = Size((r + stroke.toPx() / 2f) * 2f, (r + stroke.toPx() / 2f) * 2f)
                drawArc(
                    color = track,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke.toPx(), cap = StrokeCap.Butt),
                )
                val f = fraction
                if (f != null && f > 0.0) {
                    drawArc(
                        color = arcColor,
                        startAngle = 0f,
                        sweepAngle = (f * 360.0).toFloat(),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    lab,
                    fontFamily = MaestriType.Mono,
                    fontWeight = if (compact) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(capGap))
        Column(Modifier.width(capWidth), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                capTop,
                fontFamily = MaestriType.Mono,
                fontSize = 10.5.sp,
                lineHeight = capLineHeight,
                color = capColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                capBottom,
                fontFamily = MaestriType.Mono,
                fontSize = 10.5.sp,
                lineHeight = capLineHeight,
                color = capColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun UsageScreen(
    session: WireSession,
    onBack: () -> Unit,
) {
    val usage by session.agentUsage.usage.collectAsState()
    val streamState by session.agentUsage.state.collectAsState()
    val streamError by session.agentUsage.error.collectAsState()
    val lastFrame by session.agentUsage.lastFrame.collectAsState()

    DisposableEffect(Unit) {
        onDispose { session.agentUsage.close() }
    }
    LaunchedEffect(Unit) {
        session.pairing.filterNotNull().first()
        val url = session.agentUsageStreamUrl()
        if (url.isNotBlank()) session.agentUsage.open(url)
    }

    BoxWithConstraints {
        if (maxWidth < 480.dp) {
            UsageCompact(usage, lastFrame, streamState, streamError, onBack)
        } else {
            UsageTablet(usage, lastFrame, streamState, streamError, onBack)
        }
    }
}

/** The .rings row: every live window gets a ring; empty reading = honest placeholder. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RingsRow(
    usage: AgentUsageReading?,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier.fillMaxWidth().padding(top = if (compact) 10.dp else 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 22.dp else 26.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        liveRings(usage).forEach { r ->
            MeterRing(
                compact = compact,
                fraction = validFraction(r.window.usedFraction),
                arcColor = providerColor(r.provider),
                lab = ringLab(r.window),
                capTop = "${r.provider.name} · ${meterLabel(r.meter)}",
                capBottom = capBottom(r.window, r.provider),
            )
        }
    }
    if (liveRings(usage).isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            Text(
                if (usage == null) "no agentUsage frame yet — the stream pushes one on connect"
                else "no provider meters in the current reading",
                fontSize = 12.5.sp,
                color = Palette.StoneGray,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Header row: back affordance (Settings idiom) + serif root title + the honest-inert ⟳. */
@Composable
private fun UsageHeaderRow(
    usage: AgentUsageReading?,
    onBack: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "‹",
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
        )
        RootTitle("Usage", modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(44.dp)
                .semantics {
                    contentDescription =
                        "Refresh — inert: the host's canRefresh flag (${usage?.canRefresh ?: "unknown"}) has no client-reachable trigger in the probed wire; every agent-usage REST route answers 404, so refresh stays awaiting confirmation"
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("⟳", fontSize = 20.sp, color = Palette.StoneGray)
        }
    }
}

/** The payload + meter rows: the real latest raw frame, then the live summary line. */
@Composable
private fun UsageDataCards(
    usage: AgentUsageReading?,
    lastFrame: String?,
    streamState: app.maestri.wire.SocketState,
    streamError: String?,
    jsonSize: TextUnit,
    jsonLineHeight: TextUnit,
) {
    val c = LocalMaestriColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.canvasSurface, RoundedCornerShape(MaestriRadii.Card))
            .border(1.dp, c.hairline, RoundedCornerShape(MaestriRadii.Card))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (streamError != null && streamState != app.maestri.wire.SocketState.OPEN) {
            PayloadJsonLine(
                "stream error: $streamError",
                fontSize = jsonSize,
                lineHeight = jsonLineHeight,
                color = LocalMaestriColors.current.errorText,
            )
        }
        PayloadJsonLine(
            lastFrame ?: "waiting for the first agentUsage frame…",
            fontSize = jsonSize,
            lineHeight = jsonLineHeight,
            maxLines = 5,
        )
    }
    // Meter row: warn dot + sans 12.5 flex text + ring chip — all values live.
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.canvasSurface, RoundedCornerShape(MaestriRadii.Card))
            .border(1.dp, c.hairline, RoundedCornerShape(MaestriRadii.Card))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).background(c.warning, CircleShape))
        val top = fullestRing(usage)
        val u = usage
        val f = top?.let { validFraction(it.window.usedFraction) }
        val label = when {
            u == null -> "awaiting the first agentUsage frame"
            u.isEnabled == false -> "usage reporting is disabled on the host"
            top == null -> "no provider meters in the current reading"
            else -> "${top.provider.name} ${meterLabel(top.meter)} — " +
                (f?.let { "${(it * 100).toInt()}% used" } ?: "fraction unavailable")
        }
        Text(
            label,
            modifier = Modifier.weight(1f),
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        top?.window?.ring?.let { ring ->
            Box(
                Modifier
                    .border(1.dp, c.hairline, RoundedCornerShape(99.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text("ring $ring", fontSize = 10.sp, color = c.secondaryLabel)
            }
        }
    }
}

@Composable
private fun UsageTablet(
    usage: AgentUsageReading?,
    lastFrame: String?,
    streamState: app.maestri.wire.SocketState,
    streamError: String?,
    onBack: () -> Unit,
) {
    val c = LocalMaestriColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
            .semantics {
                contentDescription = "Usage — provider limits, live wss agent usage"
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UsageHeaderRow(usage, onBack)
        Column {
            Spacer(Modifier.height(4.dp))
            Overline("Provider limits · live (wss agentUsage)")
        }
        RingsRow(usage, compact = false)
        UsageDataCards(
            usage = usage,
            lastFrame = lastFrame,
            streamState = streamState,
            streamError = streamError,
            jsonSize = 11.sp,
            jsonLineHeight = 19.8.sp,
        )
    }
}

/** todo #50 L8 — the v2.1 frame 08 phone arrangement (below 480dp): .rings gap 22,
 * .ringbox 92×92 (r38, stroke 7), .lab JBM 700 17px, .rcap 10.5/1.55 w112, .json 10.5/1.75. */
@Composable
private fun UsageCompact(
    usage: AgentUsageReading?,
    lastFrame: String?,
    streamState: app.maestri.wire.SocketState,
    streamError: String?,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
            .semantics {
                contentDescription = "Usage — provider limits, live wss agent usage"
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UsageHeaderRow(usage, onBack)
        Column {
            Spacer(Modifier.height(4.dp))
            Overline("Provider limits · live (wss agentUsage)")
        }
        RingsRow(usage, compact = true)
        UsageDataCards(
            usage = usage,
            lastFrame = lastFrame,
            streamState = streamState,
            streamError = streamError,
            jsonSize = 10.5.sp,
            jsonLineHeight = 18.375.sp,
        )
    }
}

@Composable
private fun PayloadJsonLine(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    maxLines: Int = -1,
    color: Color = LocalMaestriColors.current.secondaryLabel,
) {
    Text(
        text,
        fontFamily = MaestriType.Mono,
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = color,
        maxLines = if (maxLines < 0) Int.MAX_VALUE else maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}