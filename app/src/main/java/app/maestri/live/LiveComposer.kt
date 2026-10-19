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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.monoHud

@Composable
fun LiveComposerBar(vm: LiveViewModel) {
    val colors = LocalMaestriColors.current
    val draft by vm.draft.collectAsState()
    val staged by vm.staged.collectAsState()
    val composeState by vm.composeState.collectAsState()
    val disabledReason = (composeState as? LiveComposeState.Disabled)?.reason
    val ready = composeState is LiveComposeState.Ready
    val canSend = ready && (draft.isNotBlank() || staged.isNotEmpty())
    val onPicked = rememberLiveAttachHandler(vm)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        LiveStagedChips(files = staged, onRemove = { vm.removeStaged(it) })
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveAttachButton(enabled = ready, onPicked = onPicked)
            OutlinedTextField(
                value = draft,
                onValueChange = { vm.updateDraft(it) },
                placeholder = { Text("Message Pi…", fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = ready,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (canSend) vm.send() },
                ),
            )
            Button(
                onClick = { vm.send() },
                enabled = canSend,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.onAccent,
                    disabledContainerColor = colors.cardSunken,
                    disabledContentColor = colors.secondaryLabel,
                ),
            ) {
                Text("Send", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                    if (failed) colors.warning.copy(alpha = 0.12f) else colors.bubbleMine,
                    RoundedCornerShape(14.dp),
                )
                .border(
                    1.dp,
                    if (failed) colors.warning.copy(alpha = 0.45f) else colors.hairline,
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (failed) "failed — not sent" else "sending…",
                    style = monoHud(
                        10,
                        color = if (failed) colors.warning else colors.bubbleMineText.copy(alpha = 0.75f),
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
                    color = if (failed) MaterialTheme.colorScheme.onSurface else colors.bubbleMineText,
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
