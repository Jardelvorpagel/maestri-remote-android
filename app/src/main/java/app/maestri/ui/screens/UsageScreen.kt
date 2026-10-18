package app.maestri.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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

/**
 * Design-v2 frame 08 — Usage ring HUD sheet (px-perfect, render-only).
 *
 * Evidence: knowledge/sources/2026-10-16-maestri-design-v2-spec.md §4 frame 08 +
 * §6 rings; mock /tmp/design-v2-mobile.html (08 · USAGE): .rings gap 26 centred
 * (padding 8/0/4), .ringbox 86×86, .lab centred JBM 600 17, .rcap mono 10.5/1.5
 * centred UNDER the ring inside the ring box (the structural fix of v2's
 * `.ringbox .cap` selector bug — captions were left-aligned sans), .json mono
 * 11/1.8, .card r12 + hairline box-shadow + 14/12 padding, meter row: dot 8 +
 * sans 12.5 flex text + chip (inline 10px sans).
 *
 * Data: the arcs read the REAL captured agentUsage reading (wire contract, probe
 * bank: knowledge/sources/2026-10-16-maestri-wire-write-schemas.md §5ter — Meter
 * carries `windows[].ring: 0|1|2` + `windows[].usedFraction 0…1`; frame capture
 * 130/226=0.58 · 68/226=0.30 · 160/226=0.71 — r36 circumference ≈ 226). The wss
 * stream client is NOT wired in this wave (render-only); any NaN/out-of-range or
 * absent fraction renders honest-empty (track only + "—"), never an invented value.
 *
 * Pixel note: the mock's SVG arc starts at 3 o'clock (circle dasharray, no
 * rotation) → drawArc(startAngle = 0f); the track paints butt-capped, the vendor
 * arc round-capped.
 */
object UsageNav {
    const val pattern = "usage"
    fun route(): String = pattern
    /* Additive registration is a later wiring pass (render-only wave): Root composes
       this screen directly; no NavGraphBuilder extension here by design. */
}

/** One captured meter reading (schema §5ter). [usedFraction] null = honest empty. */
private data class ProviderMeterReading(
    val id: String,
    val chipRing: Int?,
    val usedFraction: Float?,
    val capTop: String,
    val capBottom: String,
)

/** REAL captured readings — spec §4 frame 08 (never invented; see header data note). */
private val capturedMeters = listOf(
    ProviderMeterReading(
        id = "claude", chipRing = 2, usedFraction = 0.58f,
        capTop = "claude · 5h window", capBottom = "resets 19:00",
    ),
    ProviderMeterReading(
        id = "codex", chipRing = 1, usedFraction = 0.30f,
        capTop = "codex · weekly", capBottom = "resets Mon",
    ),
    ProviderMeterReading(
        id = "gemini", chipRing = 2, usedFraction = 0.71f,
        capTop = "gemini · daily", capBottom = "limits apply",
    ),
)

/** Normalises a usedFraction per the meter rules: finite 0…1 only. */
private fun validFraction(raw: Float?): Float? =
    if (raw != null && raw.isFinite() && raw >= 0f && raw <= 1f) raw else null

/** Vendor stroke for the data arc (data role only — spec §2 vendor palette). */
private fun vendorStroke(id: String): Color = when (id) {
    "claude" -> Palette.Claude
    "codex" -> Palette.Codex
    else -> Palette.Gemini
}

/**
 * The ring: 7dp butt-cap track + vendor round-cap arc + centred numeric.
 * Defaults = the a90fa76 tablet/HUD values (86×86, r36, JBM 600 17, .rcap
 * 10.5/1.5 w110, cap gap 8); [compact] = true switches to the v2.1 frame-08
 * phone values (92×92, r38, JBM 700 17, .rcap 10.5/1.55 w112, cap gap 10).
 */
@Composable
private fun MeterRing(
    fraction: Float?,
    arcColor: Color,
    lab: String,
    capTop: String,
    capBottom: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val track = Palette.DarkWarm // spec §6: track #3d3d3a (delta: ring tracks stay)
    val stroke = 7.dp
    val boxSize: Dp = if (compact) 92.dp else 86.dp // .ringbox (frame 08: 92)
    val radius: Dp = if (compact) 38.dp else 36.dp // circle r (frame 08: 38)
    val capGap = if (compact) 10.dp else 8.dp // .ring column gap
    val capWidth: Dp = if (compact) 112.dp else 110.dp // .rcap width
    val capLineHeight: TextUnit = if (compact) 16.275.sp else 15.75.sp // 1.55 vs 1.5
    val capColor: Color =
        Palette.StoneGray // --meta #87867F both (StoneGray == --meta bytewise)
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally, // .ring
    ) {
        Box(Modifier.size(boxSize)) { // .ringbox
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
                val f = validFraction(fraction)
                if (f != null && f > 0f) {
                    drawArc(
                        color = arcColor,
                        startAngle = 0f,
                        sweepAngle = f * 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            // .lab: absolutely-centred numeric (tablet JBM 600 17; phone 700 17)
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
        // .rcap: mono 10.5, centred, fixed width (tablet 110 · phone 112), meta colour.
        Column(Modifier.width(capWidth), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                capTop,
                fontFamily = MaestriType.Mono,
                fontSize = 10.5.sp,
                lineHeight = capLineHeight,
                color = capColor,
                textAlign = TextAlign.Center,
            )
            Text(
                capBottom,
                fontFamily = MaestriType.Mono,
                fontSize = 10.5.sp,
                lineHeight = capLineHeight,
                color = capColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The frame-08 sheet. Render-only: consumes the captured readings; no wire edits. */
@Composable
fun UsageScreen() {
    BoxWithConstraints {
        if (maxWidth < 480.dp) {
            // todo #50 L8 — v2.1 frame 08 phone arrangement below 480dp.
            UsageCompact()
            return@BoxWithConstraints
        }
        // >=480dp: the a90fa76 tablet/HUD arrangement — the body below is untouched
        // bytewise (no re-indent on purpose: tablet-branch byte-identity by construction).
    val c = LocalMaestriColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp) // .col
            .semantics {
                contentDescription = "Usage — provider limits, live wss agent usage"
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // .lnav (54): serif root title + 44 ⟳ affordance (stream un-wired = honest,
        // rendered inert in meta colour with an explanatory contentDescription).
        Row(
            Modifier.fillMaxWidth().height(54.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RootTitle("Usage", modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(44.dp)
                    .semantics {
                        contentDescription =
                            "Refresh — unavailable while the wss agentUsage stream is not wired"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("⟳", fontSize = 20.sp, color = Palette.StoneGray)
            }
        }

        // .h2sec (margin-top 4): the overline.
        Column {
            Spacer(Modifier.height(4.dp))
            Overline("Provider limits · live (wss agentUsage)")
        }

        // .rings: gap 26, centred, padding 8/0/4.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally),
        ) {
            capturedMeters.forEach { m ->
                val f = validFraction(m.usedFraction)
                MeterRing(
                    fraction = f,
                    arcColor = vendorStroke(m.id),
                    lab = if (f == null) "—" else "${(f * 100).toInt()}%",
                    capTop = m.capTop,
                    capBottom = m.capBottom,
                )
            }
        }

        // .card .json: agentUsage payload card, JBM 11/1.8 (the captured frame excerpt).
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.canvasSurface, RoundedCornerShape(MaestriRadii.Card))
                .border(1.dp, c.hairline, RoundedCornerShape(MaestriRadii.Card))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            PayloadJsonLine("{\"type\":\"agentUsage\",\"usage\":{\"canRefresh\":true,")
            PayloadJsonLine("  \"providers\":[{\"id\":\"claude\",\"colorHex\":\"D97757\",")
            PayloadJsonLine("    \"reading\":{\"meters\":[{\"ring\":2,\"usedFraction\":0.58}]}}]}")
        }

        // Meter row: warn dot + sans 12.5 flex text + meter chip (inline 10px sans).
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
            Text(
                "gemini daily limit nears 71% — meter pinned live",
                modifier = Modifier.weight(1f),
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(
                Modifier
                    .border(1.dp, c.hairline, RoundedCornerShape(99.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text("meter 2", fontSize = 10.sp, color = c.secondaryLabel)
            }
        }
    }
        } // BoxWithConstraints
}

/**
 * One .json card line in the dim warm tone (.json color:var(--dim)):
 * defaults = the tablet 11/1.8 (a90fa76); the frame-08 phone call passes 10.5/1.75.
 */
@Composable
private fun PayloadJsonLine(
    text: String,
    fontSize: TextUnit = 11.sp,
    lineHeight: TextUnit = 19.8.sp,
) {
    val c = LocalMaestriColors.current
    Text(
        text,
        fontFamily = MaestriType.Mono,
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = c.secondaryLabel,
    )
}


/**
 * todo #50 L8 — the v2.1 frame 08 phone arrangement (below 480dp), artifact
 * `maestri-remote-android-v2-84f9` 08 · USAGE: .rings gap 22 (padding 10/0/4),
 * .ringbox 92×92 (circle r38, stroke 7), .lab 700 17px JBM, .ring column gap 10,
 * .rcap 10.5/1.55 w112, .json 10.5/1.75. Track stays DarkWarm and meter data is
 * the shared captured bank (v2.1 delta verdict: numerics/HUD tokens same, ring
 * tracks stay); scaffold text mirrors the tablet branch deliberately — the
 * tablet body keeps byte-identity, the compact branch tunes independently.
 */
@Composable
private fun UsageCompact() {
    val c = LocalMaestriColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp) // .col
            .semantics {
                contentDescription = "Usage — provider limits, live wss agent usage"
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // .lnav (54): serif root title + inert ⟳ (stream un-wired, honest).
        Row(
            Modifier.fillMaxWidth().height(54.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RootTitle("Usage", modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(44.dp)
                    .semantics {
                        contentDescription =
                            "Refresh — unavailable while the wss agentUsage stream is not wired"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("⟳", fontSize = 20.sp, color = Palette.StoneGray)
            }
        }
        // .h2sec overline.
        Column {
            Spacer(Modifier.height(4.dp))
            Overline("Provider limits · live (wss agentUsage)")
        }
        // .rings: gap 22, centred, padding 10/0/4 (frame 08).
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
        ) {
            capturedMeters.forEach { m ->
                val f = validFraction(m.usedFraction)
                MeterRing(
                    compact = true,
                    fraction = f,
                    arcColor = vendorStroke(m.id),
                    lab = if (f == null) "—" else "${(f * 100).toInt()}%",
                    capTop = m.capTop,
                    capBottom = m.capBottom,
                )
            }
        }
        // .card .well .json: 10.5/1.75 at phone width (frame 08).
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.canvasSurface, RoundedCornerShape(MaestriRadii.Card))
                .border(1.dp, c.hairline, RoundedCornerShape(MaestriRadii.Card))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            PayloadJsonLine(
                "{\"type\":\"agentUsage\",\"usage\":{\"canRefresh\":true,",
                fontSize = 10.5.sp,
                lineHeight = 18.375.sp,
            )
            PayloadJsonLine(
                "  \"providers\":[{\"id\":\"claude\",\"colorHex\":\"D97757\",",
                fontSize = 10.5.sp,
                lineHeight = 18.375.sp,
            )
            PayloadJsonLine(
                "    \"reading\":{\"meters\":[{\"ring\":2,\"usedFraction\":0.58}]}}]}",
                fontSize = 10.5.sp,
                lineHeight = 18.375.sp,
            )
        }
        // Meter row: same arrangement both widths (v2.1 shows it unchanged).
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
            Text(
                "gemini daily limit nears 71% — meter pinned live",
                modifier = Modifier.weight(1f),
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(
                Modifier
                    .border(1.dp, c.hairline, RoundedCornerShape(99.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text("meter 2", fontSize = 10.sp, color = c.secondaryLabel)
            }
        }
    }
}
