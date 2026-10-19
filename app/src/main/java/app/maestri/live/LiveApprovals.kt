package app.maestri.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.cardShape
import app.maestri.ui.Style.monoHud
import app.maestri.ui.Style.warmRing
import app.maestri.ui.Style.MaestriRadii
import java.time.Instant
import kotlinx.coroutines.delay

enum class LiveApprovalState { AWAITING, DECIDED, RESOLVED, EXPIRED }

data class LiveApprovalView(
    val callId: String,
    val toolName: String,
    val argsPreview: String,
    val reason: String,
    val expiresAtMs: Long?,
    val state: LiveApprovalState,
    val decidedApprove: Boolean? = null,
)

fun parseApprovalRequest(event: PiEvent): LiveApprovalView? {
    if (event.kind != LiveKinds.APPROVAL_REQUEST) return null
    val tool = event.tool
    val callId = tool?.callId?.takeIf { it.isNotBlank() } ?: event.entryId.takeIf { it.isNotBlank() }
        ?: return null
    var reason = event.text.orEmpty()
    var expiresAtMs: Long? = null
    if (reason.trimStart().startsWith("{")) {
        runCatching {
            val obj = org.json.JSONObject(reason)
            obj.optString("reason", "").takeIf { it.isNotBlank() }?.let { reason = it }
            val raw = obj.optString("expiresAt", "")
            expiresAtMs = parseApprovalExpiry(raw)
        }
    }
    return LiveApprovalView(
        callId = callId,
        toolName = tool?.name?.takeIf { it.isNotBlank() } ?: "tool",
        argsPreview = tool?.argsPreview.orEmpty().take(500),
        reason = reason.take(500),
        expiresAtMs = expiresAtMs,
        state = LiveApprovalState.AWAITING,
    )
}

fun parseApprovalExpiry(raw: String): Long? {
    if (raw.isBlank()) return null
    raw.toLongOrNull()?.let { return it }
    return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
}

fun buildApprovalViews(
    approvals: Map<String, Approval>,
    events: List<PiEvent>,
    decided: Map<String, LiveDecision>,
    expired: Set<String>,
): List<LiveApprovalView> {
    val resolvedIds = events
        .filter { it.kind == LiveKinds.APPROVAL_RESOLVED }
        .map { it.tool?.callId?.takeIf { c -> c.isNotBlank() } ?: it.entryId }
        .toSet()
    val seen = mutableSetOf<String>()
    val out = mutableListOf<LiveApprovalView>()
    approvals.values.forEach { approval ->
        if (approval.callId.isBlank() || !seen.add(approval.callId)) return@forEach
        val state = when {
            approval.status == "resolved" || resolvedIds.contains(approval.callId) -> LiveApprovalState.RESOLVED
            expired.contains(approval.callId) -> LiveApprovalState.EXPIRED
            decided.containsKey(approval.callId) -> LiveApprovalState.DECIDED
            else -> LiveApprovalState.AWAITING
        }
        out += LiveApprovalView(
            callId = approval.callId,
            toolName = approval.tool.takeIf { it.isNotBlank() } ?: "tool",
            argsPreview = approval.argsPreview.take(500),
            reason = approval.summary.take(500),
            expiresAtMs = null,
            state = state,
            decidedApprove = decided[approval.callId]?.approved,
        )
    }
    events
        .filter { it.kind == LiveKinds.APPROVAL_REQUEST }
        .mapNotNull { parseApprovalRequest(it) }
        .filter { seen.add(it.callId) }
        .forEach { view ->
            val state = when {
                resolvedIds.contains(view.callId) -> LiveApprovalState.RESOLVED
                expired.contains(view.callId) -> LiveApprovalState.EXPIRED
                decided.containsKey(view.callId) -> LiveApprovalState.DECIDED
                else -> LiveApprovalState.AWAITING
            }
            out += view.copy(state = state, decidedApprove = decided[view.callId]?.approved)
        }
    return out
}

@Composable
fun LiveApprovalCard(
    view: LiveApprovalView,
    commandsReady: Boolean,
    commandsReason: String?,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAlways: () -> Unit,
    onExpire: () -> Unit,
) {
    val colors = LocalMaestriColors.current
    var nowMs by remember(view.callId) { mutableStateOf(System.currentTimeMillis()) }
    val expiry = view.expiresAtMs
    if (expiry != null && view.state == LiveApprovalState.AWAITING) {
        LaunchedEffect(view.callId, expiry) {
            while (true) {
                val now = System.currentTimeMillis()
                if (now >= expiry) {
                    onExpire()
                    break
                }
                delay(1000L)
                nowMs = System.currentTimeMillis()
            }
        }
    }
    val interactive = view.state == LiveApprovalState.AWAITING && commandsReady
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .warmRing(colors.warning.copy(alpha = 0.45f), radius = MaestriRadii.Card)
            .background(colors.canvasSurface, cardShape())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "approval · ",
                style = monoHud(10, color = colors.warning),
            )
            Text(
                view.toolName,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (view.state) {
                    LiveApprovalState.AWAITING -> if (expiry != null) {
                        val left = ((expiry - nowMs) / 1000).coerceAtLeast(0)
                        "expires in ${left}s"
                    } else "no expiry stated"
                    LiveApprovalState.DECIDED -> if (view.decidedApprove == true) "approved — sent" else "denied — sent"
                    LiveApprovalState.RESOLVED -> "resolved by host"
                    LiveApprovalState.EXPIRED -> "expired – denied"
                },
                style = monoHud(10, color = colors.secondaryLabel),
            )
        }
        if (view.reason.isNotBlank()) {
            Text(view.reason, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        if (view.argsPreview.isNotBlank()) {
            Text(
                view.argsPreview.take(240),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.cardSunken, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            )
        }
        if (view.state == LiveApprovalState.AWAITING) {
            if (!commandsReady) {
                Text(
                    commandsReason ?: "Decision channel unavailable.",
                    fontSize = 12.sp,
                    color = colors.warning,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDeny, enabled = interactive) {
                        Text("Deny", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.errorText)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onApprove, enabled = interactive) {
                        Text("Approve", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.positive)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onAlways, enabled = interactive) {
                        Text("Always this session", fontSize = 12.sp, color = colors.secondaryLabel)
                    }
                }
            }
        }
    }
}
