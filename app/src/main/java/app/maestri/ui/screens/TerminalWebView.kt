package app.maestri.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.maestri.ui.Style.TerminalThemes
import app.maestri.wire.SocketState
import app.maestri.wire.session.WireSession
import org.json.JSONObject
import java.io.ByteArrayInputStream

private const val TERMINAL_PAGE = "file:///android_asset/terminal/index.html"
private const val ASSET_PREFIX = "file:///android_asset/terminal/"

/** Frames held between the socket reader and the page before the oldest are dropped. */
private const val PENDING_CAP = 512

/** One evaluateJavascript call carries at most this many frames / bytes. */
private const val BATCH_FRAMES = 32
private const val BATCH_BYTES = 128 * 1024

/** WebView that reports the first non-empty layout, so the page never boots into a 0x0 viewport. */
private class SizedWebView(context: Context) : WebView(context) {
    var onSized: (() -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val once = onSized ?: return
            onSized = null
            once()
        }
    }
}

private fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

private fun framesScript(batch: List<ByteArray>): String {
    val js = StringBuilder("window.__pushFrames([")
    batch.forEachIndexed { i, bytes ->
        if (i > 0) js.append(',')
        js.append('"').append(Base64.encodeToString(bytes, Base64.NO_WRAP)).append('"')
    }
    return js.append("])").toString()
}

/** One item of the session stream, in the socket's read order: PTY bytes or a host grid. */
private sealed interface Stream {
    class Bytes(val data: ByteArray) : Stream
    class Grid(val cols: Int, val rows: Int) : Stream
}

/**
 * Stream queue between the socket reader and the page: one batch in flight at a time, the page
 * acknowledges with `TermBridge.framesConsumed(n)`, so a flood cannot outgrow the JS buffer.
 * The host grid travels in this same queue because the geometry frame shares the socket with
 * the bytes: the reset that comes with it can then never land after the snapshot it precedes.
 * Overflow drops the queue and asks for a new snapshot instead of feeding half an ANSI
 * sequence the renderer can never resync from.
 */
private class Feed(private val main: Handler) {
    private val pending = ArrayDeque<Stream>()
    private var awaiting = 0
    private var posted = false
    private var dropped = false
    private var gridInFlight = false

    var sendFrames: ((List<ByteArray>) -> Boolean)? = null
    var sendGrid: ((Int, Int) -> Boolean)? = null
    var resync: (() -> Unit)? = null

    private val run = object : Runnable {
        override fun run() {
            posted = false
            if (awaiting > 0 || gridInFlight) return
            if (dropped) {
                dropped = false
                synchronized(pending) { pending.clear() }
                resync?.invoke()
                return
            }
            val batch = ArrayList<ByteArray>(BATCH_FRAMES)
            var size = 0
            var head: Stream.Grid? = null
            synchronized(pending) {
                while (pending.isNotEmpty()) {
                    when (val next = pending.first()) {
                        is Stream.Grid -> {
                            head = next
                            pending.removeFirst()
                            return@synchronized
                        }

                        is Stream.Bytes -> {
                            if (batch.size >= BATCH_FRAMES) return@synchronized
                            if (batch.isNotEmpty() && size + next.data.size > BATCH_BYTES) {
                                return@synchronized
                            }
                            size += next.data.size
                            batch.add(next.data)
                            pending.removeFirst()
                        }
                    }
                }
            }
            val grid = head
            if (batch.isNotEmpty()) {
                if (grid != null) synchronized(pending) { pending.addFirst(grid) }
                awaiting = batch.size
                if (sendFrames?.invoke(batch) != true) {
                    awaiting = 0
                    requeueFrames(batch)
                }
                return
            }
            val next = grid ?: return
            gridInFlight = true
            if (sendGrid?.invoke(next.cols, next.rows) != true) {
                gridInFlight = false
                synchronized(pending) { pending.addFirst(next) }
            }
        }
    }

    fun schedule(ready: Boolean) {
        if (!ready || posted) return
        posted = true
        main.post(run)
    }

    fun offerFrame(bytes: ByteArray) {
        synchronized(pending) {
            pending.addLast(Stream.Bytes(bytes))
            if (pending.size + awaiting > PENDING_CAP) {
                pending.clear()
                dropped = true
            }
        }
    }

    fun offerGrid(cols: Int, rows: Int) {
        synchronized(pending) { pending.addLast(Stream.Grid(cols, rows)) }
    }

    fun consumed(count: Int, ready: Boolean) {
        awaiting = (awaiting - count).coerceAtLeast(0)
        schedule(ready)
    }

    fun gridApplied(ready: Boolean) {
        gridInFlight = false
        schedule(ready)
    }

    /** Clears the queue, reporting whether anything was lost and needs a fresh snapshot. */
    fun reset(): Boolean {
        var lost = false
        synchronized(pending) {
            lost = pending.isNotEmpty()
            pending.clear()
        }
        awaiting = 0
        gridInFlight = false
        dropped = false
        return lost
    }

    private fun requeueFrames(batch: List<ByteArray>) {
        synchronized(pending) {
            for (i in batch.indices.reversed()) pending.addFirst(Stream.Bytes(batch[i]))
        }
    }
}

/**
 * The terminal face renderer: xterm.js in a WebView fed by [WireSession.terminalStream].
 *
 * Bytes cross only through [app.maestri.wire.TerminalStreamSocket.frameSink] and the
 * [TermBridge] JS interface, so the session token stays in Kotlin — the page is a local
 * asset that never learns it and cannot load anything from the network. Lifecycle mirrors
 * the socket's contract: tap attached before `open`, detached and closed on dispose, and the
 * WebView destroyed with the composition so a recreated screen cannot be fed by a stale page.
 *
 * [rendererGen] counts page boots: after a cold reload only `rendererReady` can raise it, and
 * every page-facing effect is keyed on it, so a reloaded renderer gets its init and its status
 * again instead of sitting at the defaults forever.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalWebView(
    session: WireSession,
    terminalId: String,
    fontSizeSp: Int,
    canResize: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val feed = remember(main) { Feed(main) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var rendererGen by remember { mutableStateOf(0) }
    val streamState by session.terminalStream.state.collectAsState()

    fun ready(): Boolean = rendererGen > 0 && webView?.parent != null
    fun pump() = feed.schedule(ready())

    feed.sendFrames = { batch ->
        val wv = webView
        if (wv == null || wv.parent == null) {
            false
        } else {
            wv.evaluateJavascript(framesScript(batch), null)
            true
        }
    }
    feed.sendGrid = { cols, rows ->
        val wv = webView
        if (wv == null || wv.parent == null) {
            false
        } else {
            wv.evaluateJavascript("window.__set_host($cols,$rows,$canResize)", null)
            true
        }
    }
    feed.resync = { if (enabled) session.terminalStream.reopen() }

    val bridge = remember(main, context, session) {
        object {
            @JavascriptInterface
            fun sendInput(text: String) {
                main.post { session.terminalStream.sendInput(text) }
            }

            @JavascriptInterface
            fun sendResize(cols: Int, rows: Int) {
                main.post { session.terminalStream.sendResize(cols, rows) }
            }

            @JavascriptInterface
            fun copy(text: String) {
                if (text.isEmpty()) return
                main.post {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                        ?.setPrimaryClip(ClipData.newPlainText("terminal", text))
                    webView?.evaluateJavascript("window.__showCopied()", null)
                }
            }

            @JavascriptInterface
            fun rendererReady() {
                main.post {
                    rendererGen += 1
                    if (feed.reset() && enabled) session.terminalStream.reopen()
                    pump()
                }
            }

            @JavascriptInterface
            fun hostApplied() {
                main.post { feed.gridApplied(ready()) }
            }

            @JavascriptInterface
            fun framesConsumed(count: Int) {
                main.post { feed.consumed(count, ready()) }
            }
        }
    }

    val frameSink: (ByteArray) -> Unit = remember(feed) {
        { bytes ->
            feed.offerFrame(bytes)
            main.post { pump() }
        }
    }

    val gridSink: (Int, Int) -> Unit = remember(feed) {
        { cols, rows ->
            feed.offerGrid(cols, rows)
            main.post { pump() }
        }
    }

    DisposableEffect(terminalId) {
        session.terminalStream.frameSink = frameSink
        session.terminalStream.gridSink = gridSink
        onDispose {
            if (session.terminalStream.frameSink === frameSink) session.terminalStream.frameSink = null
            if (session.terminalStream.gridSink === gridSink) session.terminalStream.gridSink = null
            feed.reset()
            webView = null
        }
    }

    LaunchedEffect(terminalId, enabled) {
        if (!enabled) {
            session.terminalStream.close(terminalId)
            return@LaunchedEffect
        }
        val st = session.terminalStream.state.value
        if (st != SocketState.CONNECTING && st != SocketState.OPEN) {
            session.openTerminalStream(terminalId)
        }
    }

    LaunchedEffect(rendererGen, terminalId, fontSizeSp) {
        if (rendererGen == 0) return@LaunchedEffect
        val bg = hex(TerminalThemes.TokyoNight.background.toArgb())
        val fg = hex(TerminalThemes.TokyoNight.foreground.toArgb())
        val id = JSONObject.quote(terminalId)
        webView?.evaluateJavascript(
            "window.__init({id:$id,fontSize:$fontSizeSp," +
                "theme:{background:\"$bg\",foreground:\"$fg\"}})",
            null,
        )
    }

    LaunchedEffect(rendererGen, streamState) {
        if (rendererGen == 0) return@LaunchedEffect
        val label = when (streamState) {
            SocketState.OPEN -> "live"
            SocketState.CONNECTING -> "connecting"
            SocketState.CLOSED -> "closed"
            SocketState.IDLE -> "idle"
        }
        webView?.evaluateJavascript("window.__set_status(\"$label\")", null)
    }

    AndroidView(
        factory = { ctx ->
            SizedWebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.loadWithOverviewMode = false
                settings.useWideViewPort = false
                settings.setSupportZoom(false)
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(TerminalThemes.TokyoNight.background.toArgb())
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = !request.url.toString().startsWith(ASSET_PREFIX)

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? =
                        if (request.url.toString().startsWith(ASSET_PREFIX)) null
                        else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
                addJavascriptInterface(bridge, "TermBridge")
                onSized = { post { loadUrl(TERMINAL_PAGE) } }
            }
        },
        update = { if (webView !== it) webView = it },
        onRelease = { wv ->
            wv.removeJavascriptInterface("TermBridge")
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        },
        modifier = modifier,
    )
}
