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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.HostRequests
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.cardShape
import app.maestri.ui.Style.controlShape
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
            LiveAttachmentCard(
                name = file.name,
                mime = file.mime,
                sizeBytes = file.sizeBytes,
                bytes = file.bytes,
                trailing = when {
                    file.uploading -> "\u22EF"
                    file.attachmentId != null -> "\u2713"
                    else -> "\u00D7"
                },
                onTrail = { onRemove(file.key) },
            )
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
            LiveAttachmentCard(
                name = attachment.name,
                mime = attachment.mime,
                sizeBytes = attachment.sizeBytes,
                bytes = attachment.bytes,
            )
        }
    }
}

/** 'Imagem · JPG' — the kind and extension the reference puts under the file name. */
fun attachmentKindLabel(name: String, mime: String): String {
    val ext = name.substringAfterLast('.', "")
        .uppercase()
        .takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
    val kind = when {
        mime.startsWith("image/") -> "Imagem"
        mime.startsWith("audio/") -> "Áudio"
        mime.startsWith("video/") -> "Vídeo"
        mime.startsWith("text/") || mime == "application/json" -> "Texto"
        else -> "Arquivo"
    }
    return if (ext == null) kind else "$kind · $ext"
}

/**
 * The single attachment presentation, used by a delivered/pending turn and by the composer's
 * staged rows: rounded card, thumbnail plate (the real image when the bytes decode, a kind
 * glyph otherwise), bold file name and a muted kind/size line. [trailing] is the staged-state
 * glyph the composer needs; [onTrail] makes it the remove action.
 */
@Composable
fun LiveAttachmentCard(
    name: String,
    mime: String,
    sizeBytes: Long,
    bytes: ByteArray? = null,
    trailing: String? = null,
    onTrail: (() -> Unit)? = null,
) {
    val colors = LocalMaestriColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.canvasSurface, cardShape())
            .border(1.dp, colors.hairline, cardShape())
            .padding(10.dp),
    ) {
        AttachmentPlate(bytes = bytes, mime = mime, description = name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                attachmentKindLabel(name, mime) + " · " + formatAttachmentSize(sizeBytes),
                fontSize = 12.sp,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                trailing,
                fontSize = 15.sp,
                color = colors.secondaryLabel,
                modifier = if (onTrail != null) {
                    Modifier.clickable { onTrail() }.padding(horizontal = 4.dp, vertical = 6.dp)
                } else {
                    Modifier
                },
            )
        }
    }
}

/** The card's left plate: the image itself when it decodes, otherwise the kind's glyph. */
@Composable
private fun AttachmentPlate(bytes: ByteArray?, mime: String, description: String) {
    val colors = LocalMaestriColors.current
    val bitmap = remember(bytes) { decodeDownsampled(bytes, maxSide = 160) }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(controlShape())
            .background(colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(kindGlyph(mime), fontSize = 20.sp, color = colors.accent)
        }
    }
}

private fun kindGlyph(mime: String): String = when {
    mime.startsWith("image/") -> "\u25A3"
    mime.startsWith("audio/") -> "\u266A"
    mime.startsWith("video/") -> "\u25B6"
    mime.startsWith("text/") || mime == "application/json" -> "\u2261"
    else -> "\u25A4"
}

/** Downsampled bitmap for a plate or a preview; null when the bytes are not a decodable image. */
private fun decodeDownsampled(bytes: ByteArray?, maxSide: Int = 512): ImageBitmap? {
    if (bytes == null || bytes.isEmpty()) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()
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
