package app.maestri.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.Overline
import app.maestri.ui.Style.RootTitle
import app.maestri.ui.Style.MaestriRadii
import app.maestri.ui.Style.warmRing
import app.maestri.ui.Style.cardShape
import app.maestri.ui.iconGlyph
import app.maestri.ui.Style.monoHud
import app.maestri.wire.ApiException
import app.maestri.wire.Partitura
import app.maestri.wire.PartiturasApi
import app.maestri.wire.SnapshotMedia
import app.maestri.wire.WorkspaceMeta
import app.maestri.wire.session.WireSession
import kotlinx.coroutines.launch

object PartiturasNav {
    const val pattern = "partituras"
}

/** Registers the Partituras root; reachable via the Settings APP group. */
fun NavGraphBuilder.partiturasDestination(
    session: WireSession,
    pop: () -> Unit,
) {
    composable(PartiturasNav.pattern) {
        PartiturasScreen(session = session, onBack = pop)
    }
}

/**
 * Partituras — the host's library of saved canvas arrangements (docs §232; corpus §2/§5ter,
 * CONFIRMED battery). Browse + preview from any device; the STAMP is a workspace write, so
 * it is triple-gated in this replica: the destination workspace is chosen explicitly, the
 * write itself sits behind a confirm dialog, and a guest pairing is refused in the UI with
 * the honest "owner-only" note (the host answers 403 anyway).
 *
 * Honest residues on this screen: the stamp sends {partituraId, x, y} ONLY — docs-typed
 * floorId/mutationId never leave here, and the confirm copy names that the replica stamps
 * onto the GROUND floor (the CONFIRMED live path). Preview `appearance` is the documented
 * light|dark choice; this dark app sends "dark".
 */
@Composable
fun PartiturasScreen(
    session: WireSession,
    onBack: () -> Unit,
) {
    val api = remember { PartiturasApi(session) }
    val scope = rememberCoroutineScope()

    var lib by remember { mutableStateOf<List<Partitura>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var libErr by remember { mutableStateOf<String?>(null) }
    var wsList by remember { mutableStateOf<List<WorkspaceMeta>?>(null) }

    var stampWsId by remember { mutableStateOf<String?>(null) }
    var confirmFor by remember { mutableStateOf<Partitura?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var stampErr by remember { mutableStateOf<String?>(null) }

    var previewFor by remember { mutableStateOf<Partitura?>(null) }
    var previewMedia by remember(previewFor?.id) { mutableStateOf<SnapshotMedia.Result?>(null) }
    var previewLoading by remember(previewFor?.id) { mutableStateOf(true) }

    // S4-consistent gate (mirrors CanvasScreen's caps/canWrite pattern): the STAMP is a
    // workspace write → owner role on a host whose partituras family is installed; the
    // library GET itself stays browsable for every paired device (leitura).
    val caps = session.serverInfo
    val canStamp = caps?.has("partituras") == true && caps?.role == "owner"
    val canBrowse = caps?.has("partituras") != false // absent caps set: browse and let the host answer

    fun load() {
        loading = true
        if (!canBrowse) {
            // A host that predates the family: no route exists — say so instead of probing it.
            lib = emptyList()
            libErr = "this host predates the partituras family — nothing to browse or stamp"
            loading = false
            return
        }
        scope.launch {
            loading = true
            try {
                lib = api.list()
                libErr = null
            } catch (e: Exception) {
                libErr = errLinePt(e)
            } finally {
                loading = false
            }
        }
        scope.launch {
            try {
                wsList = session.workspaces()
            } catch (e: Exception) {
                // The library still browses fine; only stamping needs the workspace list.
                wsList = emptyList()
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    LaunchedEffect(previewFor?.id) {
        val p = previewFor ?: return@LaunchedEffect
        previewLoading = true
        previewMedia = runCatching { api.preview(p.id) }
            .getOrElse { SnapshotMedia.Result.Failure(errLinePt(it)) }
        previewLoading = false
    }

    fun stamp(p: Partitura) {
        if (!canStamp) return     // hard guard: no confirm for a guest (pattern parity with S4)
        val wsId = stampWsId ?: return
        scope.launch {
            busy = true
            stampErr = null
            try {
                api.stamp(wsId, p.id, STAMP_X, STAMP_Y)
                notice = "Stamped “${p.name}” onto the chosen workspace's ground floor — " +
                    "the layout arrives as ordinary nodes"
            } catch (e: Exception) {
                stampErr = errLinePt(e)
            } finally {
                busy = false
                confirmFor = null
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = "Partituras — the host's saved canvas arrangements" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                fontSize = 20.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 12.dp),
            )
            RootTitle("Partituras", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "the host's canvas arrangement library — browse + preview; stamping is owner-only",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        libErr?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        when {
            loading && lib == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    item {
                        Overline(if (canStamp) "Library · stamp destination below" else "Library · read-only pairing")
                    }
                    val l = lib ?: emptyList()
                    if (l.isEmpty() && !loading) {
                        item {
                            Text(
                                "the host's library is empty (or the route answered nothing listable)",
                                style = monoHud(11, color = MaterialTheme.colorScheme.onSurfaceVariant),
                            )
                        }
                    }
                    items(l, key = { it.id }) { p ->
                        PartituraRow(
                            partitura = p,
                            onOpen = { previewFor = p },
                            onStamp = { confirmFor = p },
                            stampEnabled = canStamp && !busy && stampWsId != null,
                            stampHint = when {
                                !canStamp -> app.maestri.ui.CanvasStrings.guestReadsOnly
                                stampWsId == null -> "pick a stamp destination first"
                                else -> null
                            },
                        )
                    }

                    item {
                        Spacer(Modifier.height(8.dp))
                        Overline("Stamp destination · workspace")
                        Text(
                            "the replica stamps {partituraId, x, y} with no floorId — the CONFIRMED path stamps the ground floor; floorId rides the docs-typed residue",
                            style = monoHud(11, color = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (wsList == null) {
                        item {
                            Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            }
                        }
                    } else {
                        items(wsList ?: emptyList(), key = { it.id }) { ws ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable { stampWsId = ws.id }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (stampWsId == ws.id) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline
                                        )
                                )
                                Text(ws.name, modifier = Modifier.padding(start = 12.dp), fontSize = 15.sp)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    ws.id.takeLast(8).uppercase(),
                                    style = monoHud(11, color = MaterialTheme.colorScheme.onSurfaceVariant),
                                )
                            }
                        }
                    }
                    notice?.let {
                        item {
                            Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                        }
                    }
                    stampErr?.let {
                        item {
                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    confirmFor?.let { p ->
        StampDialog(
            partitura = p,
            wsName = (wsList ?: emptyList()).firstOrNull { it.id == stampWsId }?.name ?: stampWsId ?: "",
            busy = busy,
            onConfirm = { stamp(p) },
            onDismiss = { if (!busy) confirmFor = null },
        )
    }

    previewFor?.let { p ->
        PartituraPreviewDialog(
            partitura = p,
            media = previewMedia,
            loading = previewLoading,
            onDismiss = { previewFor = null; previewMedia = null },
        )
    }
}

/** Confirmed stamp anchor: the layout is centered at this canvas point (points ≙ dp at 1×). */
private const val STAMP_X = 400.0
private const val STAMP_Y = 300.0

/** #RRGGBB only (corpus partitura color style); anything else falls back to the theme primary. */
@Composable
private fun partituraTint(raw: String?): androidx.compose.ui.graphics.Color {
    val hex = raw?.takeIf { it.length == 7 && it[0] == '#' }
        ?.let { runCatching { it.substring(1).toLong(16) }.getOrNull() }
        ?.takeIf { v -> v in 0L..0xFFFFFFL }
    return hex?.let { androidx.compose.ui.graphics.Color(0xFF000000L or it) }
        ?: MaterialTheme.colorScheme.primary
}

private fun errLinePt(e: Throwable): String = when (e) {
    is ApiException -> e.message.ifBlank { "HTTP ${e.status}" }
    else -> e.message ?: "network error"
}

@Composable
private fun PartituraRow(
    partitura: Partitura,
    onOpen: () -> Unit,
    onStamp: () -> Unit,
    stampEnabled: Boolean,
    stampHint: String?,
) {
    val c = LocalMaestriColors.current
    val glyph = iconGlyph(partitura.icon ?: "rectangle.portrait")
    val tint = partituraTint(partitura.color)
    Box(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .warmRing(c.hairline, radius = MaestriRadii.Card)
            .background(c.canvasSurface, RoundedCornerShape(MaestriRadii.Card))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(partitura.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(9.dp).clip(CircleShape).background(tint))
                }
                partitura.summary.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = c.secondaryLabel,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Row {
                    Text(
                        "tap for preview —",
                        style = monoHud(11, color = c.secondaryLabel),
                    )
                    partitura.workspaceId?.let {
                        Text(
                            " workspace-scoped · ${it.takeLast(8).uppercase()}",
                            style = monoHud(11, color = c.secondaryLabel),
                        )
                    }
                }
                stampHint?.let {
                    Text(it, style = monoHud(11, color = c.hairline))
                }
            }
            Spacer(Modifier.weight(1f))
            Text(glyph, fontSize = 26.sp, color = tint)
            if (stampEnabled) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "Stamp…",
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onStamp() }
                        .padding(6.dp),
                )
            }
        }
    }
}

@Composable
private fun StampDialog(
    partitura: Partitura,
    wsName: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stamp “${partitura.name}”?", fontSize = 16.sp) },
        text = {
            Text(
                "POST /api/workspaces/…/partituras — the layout is stamped onto “$wsName”'s ground floor, " +
                    "centered at canvas point (${"%.0f".format(STAMP_X)} · ${"%.0f".format(STAMP_Y)}). " +
                    "Everything arrives as ordinary nodes; terminals arrive filled but inert (isRunning:false, they don't auto-start), " +
                    "and there is no special partitura node kind. The replica's stamp sends no floorId.",
                fontSize = 13.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(if (busy) "Stamping…" else "Stamp")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Preview: the host's own thumbnail (GET …/preview?appearance=dark) with the ETag-backed
 * fetch; 404 = "no preview served" honest state; the meta card carries name/summary/roles
 * but every edit affordance stays on the host (docs: "creating and editing stay on the host").
 */
@Composable
private fun PartituraPreviewDialog(
    partitura: Partitura,
    media: SnapshotMedia.Result?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(partitura.name, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                partitura.summary.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp)
                }
                Text(
                    buildString {
                        append("colors: ${partitura.color ?: "–"}")
                        append(" · roles: ${partitura.roles.size}")
                        partitura.workspaceId?.let { append(" · workspace-scoped") }
                    },
                    style = monoHud(11, color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp)
                        .clip(cardShape())
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val m = media) {
                        null, is SnapshotMedia.Result.Fresh -> {
                            val bytes = (m as? SnapshotMedia.Result.Fresh)?.bytes
                            val bmp = bytes?.let {
                                runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
                            }
                            if (loading && bmp == null) Text("fetching preview…", style = monoHud(11))
                            else if (bmp != null) Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "partitura preview — ${partitura.name}",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            else Text("no preview decoded", style = monoHud(11))
                        }
                        SnapshotMedia.Result.Unavailable ->
                            Text("the host serves no preview for this partitura", style = monoHud(11))
                        is SnapshotMedia.Result.Failure ->
                            Text(
                                "preview failed: ${m.message}",
                                style = monoHud(11),
                                color = MaterialTheme.colorScheme.error,
                            )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}