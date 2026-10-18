package app.maestri.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.monoHud
import app.maestri.ui.Style.warmRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Dependency-free image loading for the chat face (design-v2 05 — Mac parity:
 * RemoteChatFace's ChatMarkdownImage + attachment thumbnails). Stdlib only —
 * BitmapFactory + HttpURLConnection + a small in-memory LRU. No Coil/Glide
 * (dependency adds need user approval). Empty/loading/failed renders are honest
 * placeholders, never a broken box.
 */

private object ChatImageCache {
    private const val MAX_CACHE_BYTES = 32L * 1024 * 1024
    private var usedBytes = 0L
    private val lru = object : LinkedHashMap<String, android.graphics.Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>): Boolean {
            if (usedBytes <= MAX_CACHE_BYTES) return false
            usedBytes -= eldest.value.byteCount
            return true
        }
    }

    @Synchronized
    fun get(url: String): android.graphics.Bitmap? = lru[url]

    @Synchronized
    fun put(url: String, bmp: android.graphics.Bitmap) {
        lru.remove(url)?.let { usedBytes -= it.byteCount }
        lru[url] = bmp
        usedBytes += bmp.byteCount
    }
}

private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

private fun decodeSampled(bytes: ByteArray, requestW: Int, requestH: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options()
    bounds.inJustDecodeBounds = true
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= requestW && bounds.outHeight / (sample * 2) >= requestH) sample *= 2
    val opts = android.graphics.BitmapFactory.Options()
    opts.inSampleSize = sample
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

/** GET the image and decode; null on any failure (honest fallback at the call site). */
internal suspend fun chatImageBitmap(url: String, maxDisplayW: Int, maxDisplayH: Int): android.graphics.Bitmap? {
    if (url.isBlank()) return null
    ChatImageCache.get(url)?.let { return it }
    return withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MaestriAndroid/chat-images")
            if (conn.responseCode !in 200..299) return@runCatching null
            val declared = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
            if (declared > MAX_IMAGE_BYTES) {
                conn.disconnect()
                return@runCatching null
            }
            val out = java.io.ByteArrayOutputStream()
            conn.inputStream.use {
                val buf = ByteArray(16 * 1024)
                var read = 0L
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    read += n
                    if (read > MAX_IMAGE_BYTES) {
                        conn.disconnect()
                        return@runCatching null
                    }
                    out.write(buf, 0, n)
                }
            }
            conn.disconnect()
            decodeSampled(out.toByteArray(), maxDisplayW, maxDisplayH)
        }.getOrNull()
    }?.also { ChatImageCache.put(url, it) }
}

/** Inline markdown image — ![alt](url) — the Mac ChatMarkdownImage parity block. */
@Composable
internal fun ChatMarkdownImage(url: String, alt: String, modifier: Modifier = Modifier) {
    val chrome = LocalMaestriColors.current
    // 0 = loading, 1 = ok, 2 = failed — honest states, never a broken box
    var state by remember(url) { mutableStateOf(0) }
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    LaunchedEffect(url) {
        state = 0
        val maxW = (configuration.screenWidthDp * density.density).toInt().coerceAtMost(1600)
        val maxH = with(density) { 260.dp.roundToPx() }
        val b = chatImageBitmap(url, maxW, maxH)?.asImageBitmap()
        if (b != null) {
            bmp = b
            state = 1
        } else {
            state = 2
        }
    }
    val imgShape = RoundedCornerShape(12.dp)
    when (state) {
        0 -> Box(
            modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .background(chrome.canvasSurface, imgShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "loading image…",
                style = monoHud(11, color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        2 -> Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(chrome.canvasSurface, imgShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (if (alt.isBlank()) "Image unavailable" else "Image unavailable: $alt").take(80),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b,
                    contentDescription = alt.ifBlank { "image" },
                    contentScale = ContentScale.FillWidth,
                    modifier = modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(imgShape)
                        .warmRing(chrome.hairline, radius = 12.dp),
                )
            } else {
                Text("…", style = monoHud(11))
            }
        }
    }
}

/**
 * Small rounded attachment thumbnail inside a bubble — thumb + name + size,
 * tap keeps the existing preview dialog (Mac attachment-pill parity).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatImageThumb(
    bmp: android.graphics.Bitmap?,
    filename: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    val chrome = LocalMaestriColors.current
    Row(
        modifier = modifier
            .background(chrome.canvasSurface, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress ?: {})
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(chrome.canvasChromeElevated),
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = filename,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text("…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                filename,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
