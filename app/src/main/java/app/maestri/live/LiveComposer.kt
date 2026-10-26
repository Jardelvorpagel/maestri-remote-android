package app.maestri.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.Style.Gemini
import app.maestri.ui.Style.GeminiAttachmentSheet
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.RecordButton
import app.maestri.ui.Style.containerShape
import app.maestri.ui.Style.monoHud

/**
 * The chat face's composer, shaped like the reference: one rounded container that holds the
 * field on top and the controls inside it — attach and mic on the left, a round send button
 * trailing. Behaviour is unchanged: the mic stays TAP-to-record (hold-to-speak does not exist
 * in this app, so the placeholder says "toque", not "segure").
 */
@Composable
fun LiveComposerBar(
    vm: LiveViewModel,
    recording: Boolean = false,
    recordElapsedMs: Long = 0L,
    onToggleRecord: (() -> Unit)? = null,
) {
    val colors = LocalMaestriColors.current
    val draft by vm.draft.collectAsState()
    val staged by vm.staged.collectAsState()
    val composeState by vm.composeState.collectAsState()
    val disabledReason = (composeState as? LiveComposeState.Disabled)?.reason
    val ready = composeState is LiveComposeState.Ready
    val canSend = ready && (draft.isNotBlank() || staged.isNotEmpty()) && !recording
    val onPicked = rememberLiveAttachHandler(vm)
    val pickPhotos = rememberImagePick(onPicked)
    val capturePhoto = rememberPhotoCapture(onPicked)
    val pickFiles = rememberDocumentPick(onPicked)
    var sheetOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        LiveStagedChips(files = staged, onRemove = { vm.removeStaged(it) })
        Column(
            // The Gemini pill: one #1E1F22 container at 32 dp, no border, controls inside it.
            modifier = Modifier
                .fillMaxWidth()
                .background(Gemini.Surface, RoundedCornerShape(32.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                if (draft.isEmpty()) {
                    Text(
                        "Ask Maestri...",
                        fontSize = 15.sp,
                        color = Gemini.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { vm.updateDraft(it) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = ready,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = Gemini.Text),
                    cursorBrush = SolidColor(Gemini.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { if (canSend) vm.send() },
                    ),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                LiveAttachButton(enabled = ready && !recording, onOpenSheet = { sheetOpen = true })
                // The mic follows the attach button's gate: hidden while the face is not Ready,
                // but still drawn during a take so it can always be stopped.
                if (onToggleRecord != null && (ready || recording)) {
                    Spacer(Modifier.width(6.dp))
                    RecordButton(
                        recording = recording,
                        elapsedMs = recordElapsedMs,
                        enabled = ready || recording,
                        onClick = onToggleRecord,
                        tint = Gemini.TextSecondary,
                        size = 44.dp,
                    )
                }
                Spacer(Modifier.weight(1f))
                SendPillButton(enabled = canSend, onClick = { vm.send() })
            }
        }
        if (disabledReason != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                disabledReason,
                fontSize = 12.sp,
                color = colors.secondaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (sheetOpen) {
            GeminiAttachmentSheet(
                onPhotos = pickPhotos,
                onCamera = capturePhoto,
                onFiles = pickFiles,
                onPrompt = { prompt -> vm.updateDraft(prompt) },
                onDismiss = { sheetOpen = false },
            )
        }
    }
}

/** Round trailing send: the pill's only filled control, so the eye lands on it. */
@Composable
private fun SendPillButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (enabled) Gemini.Accent else Gemini.SurfaceRaised, CircleShape)
            .clickable(enabled = enabled) { onClick() }
            .semantics { contentDescription = "Send" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "\u2191",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Gemini.TextStrong else Gemini.TextMuted,
        )
    }
}

@Composable
fun LivePendingRow(row: LivePendingSend, onRetry: () -> Unit) {
    val colors = LocalMaestriColors.current
    val failed = row.status == LiveSendStatus.FAILED
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .background(
                    if (failed) colors.warning.copy(alpha = 0.12f) else Gemini.Surface,
                    RoundedCornerShape(24.dp),
                )
                .border(
                    1.dp,
                    if (failed) colors.warning.copy(alpha = 0.45f) else Color.Transparent,
                    RoundedCornerShape(24.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (failed) "failed — not sent" else "sending…",
                    style = monoHud(
                        10,
                        color = if (failed) colors.warning else Gemini.TextSecondary,
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (failed) {
                    TextButton(onClick = onRetry) {
                        Text(
                            "Retry",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent,
                        )
                    }
                }
            }
            if (row.text.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    row.text,
                    fontSize = 13.5.sp,
                    color = if (failed) MaterialTheme.colorScheme.onSurface else Gemini.Text,
                )
            }
            LiveAttachmentChips(attachments = row.attachments)
            if (failed && row.detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    row.detail.take(200),
                    fontSize = 12.sp,
                    color = colors.warning,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
