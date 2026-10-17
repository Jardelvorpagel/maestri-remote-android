package app.maestri.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

/** The 86×86 ring: 7dp butt-cap track + vendor round-cap arc + centred numeric. */
@Composable
private fun MeterRing(
    fraction: Float?,
    arcColor: Color,
    lab: String,
    capTop: String,
    capBottom: String,
    modifier: Modifier = Modifier,
) {
    val track = Palette.DarkWarm // spec §6: track #3d3d3a (fixed literal)
    val stroke = 7.dp
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally, // .ring, gap 8
    ) {
        Box(Modifier.size(86.dp)) { // .ringbox
            Canvas(Modifier.fillMaxSize()) {
                val r = 36.dp.toPx() // mock circle r=36
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
            // .lab: absolutely-centred numeric, JBM 600 17 (HUD role ruling)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    lab,
                    fontFamily = MaestriType.Mono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // .rcap: mono 10.5/1.5, centred, fixed 110 width, meta colour.
        Column(Modifier.width(110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                capTop,
                fontFamily = MaestriType.Mono,
                fontSize = 10.5.sp,
                lineHeight = 15.75.sp,
                color = Palette.StoneGray,
                textAlign = TextAlign.Center,
            )
            Text(
                capBottom,
                fontFamily = MaestriType.Mono,
                fontSize = 10.5.sp,
                lineHeight = 15.75.sp,
                color = Palette.StoneGray,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The frame-08 sheet. Render-only: consumes the captured readings; no wire edits. */
@Composable
fun UsageScreen() {
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
}

/** One .json card line: mono 11/1.8 in the dim warm tone (.json color:var(--dim)). */
@Composable
private fun PayloadJsonLine(text: String) {
    val c = LocalMaestriColors.current
    Text(
        text,
        fontFamily = MaestriType.Mono,
        fontSize = 11.sp,
        lineHeight = 19.8.sp,
        color = c.secondaryLabel,
    )
}

