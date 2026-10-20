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

/**
 * Frame queue between the socket reader and the page: one batch in flight at a time, the page
 * acknowledges with `TermBridge.framesConsumed(n)`, so a flood cannot outgrow the JS buffer.
 * Overflow drops the queue and asks for a new snapshot instead of feeding half an ANSI
 * sequence the renderer can never resync from.
 */
private class Feed(private val main: Handler) {
    private val pending = ArrayDeque<ByteArray>()
    private var awaiting = 0
    private var posted = false
    private var dropped = false

    var send: ((List<ByteArray>) -> Unit)? = null
    var resync: (() -> Unit)? = null

    private val run = object : Runnable {
        override fun run() {
            posted = false
            if (awaiting > 0) return
            if (dropped) {
                dropped = false
                synchronized(pending) { pending.clear() }
                resync?.invoke()
                return
            }
            val batch = ArrayList<ByteArray>(BATCH_FRAMES)
            var size = 0
            synchronized(pending) {
                while (pending.isNotEmpty() && batch.size < BATCH_FRAMES) {
                    val next = pending.first()
                    if (batch.isNotEmpty() && size + next.size > BATCH_BYTES) break
                    size += next.size
                    batch.add(pending.removeFirst())
                }
            }
            if (batch.isEmpty()) return
            awaiting = batch.size
            send?.invoke(batch) ?: requeue(batch)
        }
    }

    fun schedule(ready: Boolean) {
        if (!ready || posted) return
        posted = true
        main.post(run)
    }

    fun offer(bytes: ByteArray) {
        synchronized(pending) {
            pending.addLast(bytes)
            if (pending.size + awaiting > PENDING_CAP) {
                pending.clear()
                dropped = true
            }
        }
    }

    fun consumed(count: Int, ready: Boolean) {
        awaiting = (awaiting - count).coerceAtLeast(0)
        schedule(ready)
    }

    fun requeue(batch: List<ByteArray>) {
        synchronized(pending) {
            for (i in batch.indices.reversed()) pending.addFirst(batch[i])
        }
        awaiting = 0
    }

    fun reset() {
        synchronized(pending) { pending.clear() }
        awaiting = 0
        dropped = false
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
 * every page-facing effect is keyed on it, so a reloaded renderer gets its init, its host
 * grid and its status again instead of sitting at the defaults forever.
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
    var hostSynced by remember { mutableStateOf(true) }
    var hostGen by remember { mutableStateOf(0) }
    val streamState by session.terminalStream.state.collectAsState()
    val geometry by session.terminalStream.geometry.collectAsState()

    fun ready(): Boolean = rendererGen > 0 && hostSynced && webView?.parent != null
    fun pump() = feed.schedule(ready())

    feed.send = { batch ->
        val wv = webView
        if (!hostSynced || rendererGen == 0 || wv == null || wv.parent == null) {
            feed.requeue(batch)
        } else {
            wv.evaluateJavascript(framesScript(batch), null)
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
                    hostSynced = false
                    feed.reset()
                    pump()
                }
            }

            @JavascriptInterface
            fun hostApplied() {
                main.post {
                    hostSynced = true
                    pump()
                }
            }

            @JavascriptInterface
            fun framesConsumed(count: Int) {
                main.post { feed.consumed(count, ready()) }
            }
        }
    }

    val sink: (ByteArray) -> Unit = remember(feed) {
        { bytes ->
            feed.offer(bytes)
            main.post { pump() }
        }
    }

    DisposableEffect(terminalId) {
        session.terminalStream.frameSink = sink
        onDispose {
            if (session.terminalStream.frameSink === sink) session.terminalStream.frameSink = null
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

    LaunchedEffect(rendererGen, geometry, canResize) {
        if (rendererGen == 0) return@LaunchedEffect
        // Frames wait for the page to apply the host grid: a snapshot must never land in the
        // buffer before the reset that accompanies every geometry frame.
        hostSynced = false
        val gen = hostGen + 1
        hostGen = gen
        val g = geometry
        webView?.evaluateJavascript(
            "window.__set_host(${g?.cols ?: -1},${g?.rows ?: -1},$canResize)",
            null,
        )
        main.postDelayed({
            if (hostGen == gen && !hostSynced) {
                hostSynced = true
                pump()
            }
        }, 400)
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
