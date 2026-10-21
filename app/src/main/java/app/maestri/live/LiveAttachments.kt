package app.maestri.live

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.HostRequests
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.monoHud
import app.maestri.ui.Style.pillShape
import java.util.UUID

fun formatAttachmentSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

private data class PickedMeta(
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val bytes: ByteArray,
)

private fun readPickedFile(context: Context, uri: Uri): PickedMeta? {
    val resolver = context.contentResolver
    var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
    var size = -1L
    var mime = resolver.getType(uri) ?: "application/octet-stream"
    runCatching {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
    }
    if (mime == "application/octet-stream") {
        val lower = name.lowercase()
        mime = when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".txt") || lower.endsWith(".md") -> "text/plain"
            lower.endsWith(".json") -> "application/json"
            else -> mime
        }
    }
    if (size < 0) {
        size = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }.takeIf { it ?: -1 >= 0 } ?: -1L
        }.getOrDefault(-1L)
    }
    if (size > MAX_ATTACH_BYTES) {
        return PickedMeta(name, mime, size, ByteArray(0))
    }
    val bytes = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            val cap = MAX_ATTACH_BYTES + 1
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > cap) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull() ?: return null
    if (bytes.size > MAX_ATTACH_BYTES) return PickedMeta(name, mime, bytes.size.toLong(), ByteArray(0))
    return PickedMeta(name, mime, bytes.size.toLong(), bytes)
}

fun stagePickedUris(context: Context, uris: List<Uri>): Pair<List<LiveStagedFile>, List<String>> {
    val staged = mutableListOf<LiveStagedFile>()
    val unreadable = mutableListOf<String>()
    uris.forEach { uri ->
        val label = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        val meta = runCatching { readPickedFile(context, uri) }.getOrNull()
        if (meta == null || (meta.bytes.isEmpty() && meta.sizeBytes <= 0)) {
            unreadable += label
            return@forEach
        }
        staged += LiveStagedFile(
            key = UUID.randomUUID().toString(),
            name = meta.name,
            mime = meta.mime,
            sizeBytes = meta.sizeBytes,
            bytes = meta.bytes,
        )
    }
    return staged to unreadable
}

@Composable
fun LiveAttachButton(enabled: Boolean, onPicked: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    val colors = LocalMaestriColors.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(pillShape())
            .background(colors.canvasSurface, pillShape())
            .border(1.dp, colors.hairline, pillShape())
            .clickable(enabled = enabled) {
                val host = context as? HostRequests
                if (host == null) {
                    android.util.Log.d("QAFeed", "attach: no host for the document picker")
                    return@clickable
                }
                runCatching {
                    host.pickDocuments { uris -> if (uris.isNotEmpty()) onPicked(uris) }
                }.onFailure {
                    android.util.Log.d("QAFeed", "attach launch failed: ${it.javaClass.simpleName}: ${it.message}", it)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("+", fontSize = 20.sp, color = colors.secondaryLabel)
    }
}

@Composable
fun LiveStagedChips(files: List<LiveStagedFile>, onRemove: (String) -> Unit) {
    if (files.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        files.forEach { file ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                LiveFileChip(
                    name = file.name,
                    sizeBytes = file.sizeBytes,
                    trailing = when {
                        file.uploading -> "…"
                        file.attachmentId != null -> "✓"
                        else -> "×"
                    },
                    onTrail = { onRemove(file.key) },
                )
            }
            if (file.mime.startsWith("image/") && file.bytes.isNotEmpty()) {
                LiveInlineImage(bytes = file.bytes, contentDescription = file.name)
            }
        }
    }
}

@Composable
fun LiveAttachmentChips(attachments: List<LivePendingAttachment>) {
    if (attachments.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { attachment ->
            LiveFileChip(
                name = attachment.name,
                sizeBytes = attachment.sizeBytes,
                trailing = null,
                onTrail = null,
            )
            if (attachment.mime.startsWith("image/") && attachment.bytes != null && attachment.bytes.isNotEmpty()) {
                LiveInlineImage(bytes = attachment.bytes, contentDescription = attachment.name)
            }
        }
    }
}

@Composable
private fun LiveFileChip(
    name: String,
    sizeBytes: Long,
    trailing: String?,
    onTrail: (() -> Unit)?,
) {
    val colors = LocalMaestriColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 32.dp)
            .background(colors.cardSunken, pillShape())
            .border(1.dp, colors.hairline, pillShape())
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            name,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Text(formatAttachmentSize(sizeBytes), style = monoHud(10, color = colors.secondaryLabel))
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            val mod = if (onTrail != null) Modifier.clickable { onTrail() } else Modifier
            Text(trailing, fontSize = 13.sp, color = colors.secondaryLabel, modifier = mod)
        }
    }
}

@Composable
fun LiveInlineImage(bytes: ByteArray, contentDescription: String?) {
    var bitmap by remember(bytes) {
        mutableStateOf(
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            }.getOrNull()
        )
    }
    bitmap?.let { image ->
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}

@Composable
fun rememberLiveAttachHandler(vm: LiveViewModel): (List<Uri>) -> Unit {
    val context = LocalContext.current
    return remember(context, vm) {
        { uris: List<Uri> ->
            val (staged, unreadable) = stagePickedUris(context.applicationContext, uris)
            vm.stageFiles(staged)
            if (unreadable.isNotEmpty()) {
                vm.note("Couldn't read: " + unreadable.take(3).joinToString(", ") + " — draft kept.")
            }
        }
    }
}
