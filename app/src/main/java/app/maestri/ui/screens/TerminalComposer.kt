package app.maestri.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.live.LiveStagedFile
import app.maestri.ui.HostRequests
import app.maestri.ui.Style.Palette
import app.maestri.ui.Style.pillShape
import app.maestri.ui.Style.softHalo

private val ComposerFill = Color(0xFF10111A)
private const val ComposerFillAlpha = 0.97f
private val ComposerRing = Color(0xFFA9B1D6).copy(alpha = 0.14f)
private val ComposerIcon = Color(0xFFC3C7D6)
private val ComposerMuted = Color(0xFF8B90A8)
private val ComposerText = Color(0xFFE8EAF2)
private val SegmentOn = Color(0xFF3D4150)
private val SegmentOnText = Color(0xFFF2F3F7)
private val SegmentOffText = Color(0xFF9AA0B4)

private val panelShape = RoundedCornerShape(26.dp)

@Composable
fun TerminalComposer(
    placeholder: String,
    value: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: (List<Uri>) -> Unit,
    canSend: Boolean,
    busy: Boolean,
    meta: String?,
    chatSelected: Boolean,
    onChat: () -> Unit,
    onRun: () -> Unit,
    staged: List<LiveStagedFile>,
    onRemoveStaged: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val fieldStyle = TextStyle(fontSize = 13.5.sp, color = ComposerText)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier
                .fillMaxWidth()
                .softHalo(radius = 26.dp, elevation = 14.dp)
                .background(ComposerFill.copy(alpha = ComposerFillAlpha), panelShape)
                .border(1.dp, ComposerRing, panelShape)
                .padding(start = 12.dp, end = 12.dp, top = 13.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (staged.isNotEmpty()) TerminalStagedChips(staged, onRemoveStaged)
            Box(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 1.dp)) {
                if (value.isEmpty()) {
                    Text(placeholder, fontSize = 13.5.sp, color = ComposerMuted, maxLines = 1)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    textStyle = fieldStyle,
                    singleLine = true,
                    cursorBrush = SolidColor(Palette.ComposerAccent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AttachButton(enabled = !busy, onPicked = onAttach)
                Spacer(Modifier.weight(1f))
                if (meta != null) {
                    Text(meta, fontSize = 11.5.sp, color = ComposerMuted)
                    Spacer(Modifier.width(8.dp))
                }
                SendButton(enabled = canSend, busy = busy, onSend = onSend)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .background(ComposerFill.copy(alpha = ComposerFillAlpha), pillShape())
                .border(1.dp, ComposerRing, pillShape())
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SegmentButton("Chat", chatSelected, onChat)
            SegmentButton("Terminal", !chatSelected, onRun)
        }
    }
}

@Composable
private fun AttachButton(enabled: Boolean, onPicked: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled) {
                val host = context as? HostRequests ?: return@clickable
                runCatching { host.pickDocuments { uris -> if (uris.isNotEmpty()) onPicked(uris) } }
            }
            .semantics {
                contentDescription = "Add context"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("+", fontSize = 19.sp, color = ComposerIcon)
    }
}

@Composable
private fun SendButton(enabled: Boolean, busy: Boolean, onSend: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                Palette.ComposerAccent.copy(alpha = if (enabled) 1f else 0.3f),
                CircleShape,
            )
            .clickable(enabled = enabled) { onSend() }
            .semantics {
                contentDescription = if (busy) "Uploading" else "Run"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (busy) "\u22EF" else "\u2192",
            fontSize = 16.sp,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.6f),
        )
    }
}

@Composable
private fun SegmentButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(28.dp)
            .clip(pillShape())
            .background(if (selected) SegmentOn else Color.Transparent, pillShape())
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp)
            .semantics {
                this.selected = selected
                role = Role.Tab
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) SegmentOnText else SegmentOffText,
        )
    }
}
