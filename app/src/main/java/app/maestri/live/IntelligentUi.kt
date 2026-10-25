package app.maestri.live

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.cardShape
import app.maestri.ui.Style.controlShape
import app.maestri.ui.Style.monoHud
import app.maestri.ui.Style.pillShape
import java.io.ByteArrayInputStream

// ----------------------------------------------------------------- models

/** One ordered piece of an assistant message: prose, a native spec card, or an HTML widget. */
sealed interface UiSegment {
    data class Prose(val text: String) : UiSegment
    data class NativeCard(val spec: NativeUiSpec) : UiSegment
    data class HtmlWidget(val title: String?, val html: String) : UiSegment
}

data class UiMetric(val label: String, val value: String, val ratio: Float?)

data class UiStep(val label: String, val done: Boolean)

/** `prompt` goes back to Pi as a chat message when the pill is tapped. */
data class UiAction(val label: String, val prompt: String)

data class NativeUiSpec(
    val title: String? = null,
    val subtitle: String? = null,
    val metrics: List<UiMetric> = emptyList(),
    val steps: List<UiStep> = emptyList(),
    val actions: List<UiAction> = emptyList(),
) {
    val isEmpty: Boolean
        get() = title == null && subtitle == null && metrics.isEmpty() &&
            steps.isEmpty() && actions.isEmpty()
}

// ----------------------------------------------------------------- parser

private const val FENCE = "```"

/**
 * Splits one assistant message into ordered segments. Fenced ```ui blocks become
 * [UiSegment.NativeCard], fenced ```ui-html blocks become [UiSegment.HtmlWidget],
 * everything else is prose. Malformed JSON, an empty spec and unclosed fences all
 * degrade to prose so a bad model answer can never blank out or crash the bubble.
 */
fun parseIntelligentSegments(text: String): List<UiSegment> {
    val out = mutableListOf<UiSegment>()
    val prose = StringBuilder()
    fun flush() {
        val body = prose.toString().trim()
        if (body.isNotEmpty()) out += UiSegment.Prose(body)
        prose.setLength(0)
    }
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val info = fenceInfo(lines[i])
        if (info == null) {
            prose.append(lines[i]).append('\n')
            i++
            continue
        }
        val close = (i + 1 until lines.size).firstOrNull { lines[it].trim().startsWith(FENCE) }
        if (close == null) {
            prose.append(lines[i]).append('\n')
            i++
            continue
        }
        flush()
        val body = lines.subList(i + 1, close).joinToString("\n")
        out += fencedSegment(info, body)
        i = close + 1
    }
    flush()
    return out
}

/** Fence info string for an opening line (`ui`, `ui-html Title`); null for prose lines. */
private fun fenceInfo(line: String): String? {
    val trimmed = line.trim()
    if (!trimmed.startsWith(FENCE)) return null
    val info = trimmed.removePrefix(FENCE).trim()
    return info.ifBlank { null }
}

private fun fencedSegment(info: String, body: String): UiSegment {
    val lang = info.substringBefore(' ').trim().lowercase()
    val tail = info.substringAfter(' ', "").substringBefore("<!--").trim()
    return when (lang) {
        "ui-html" -> {
            val titleFromComment = htmlTitleComment(body)
            UiSegment.HtmlWidget(
                title = tail.ifBlank { titleFromComment?.first },
                html = titleFromComment?.second ?: body,
            )
        }
        "ui" -> parseNativeUiSpec(body)?.takeUnless { it.isEmpty }?.let(UiSegment::NativeCard)
            ?: UiSegment.Prose(body.trim())
        else -> UiSegment.Prose("$FENCE$info\n$body\n$FENCE")
    }
}

private val HTML_TITLE = Regex("""^\s*<!--\s*title\s*:\s*(.+?)\s*-->\s*$""", RegexOption.IGNORE_CASE)

/** `<!-- title: … -->` on the first non-blank line → title plus the html without it. */
private fun htmlTitleComment(body: String): Pair<String, String>? {
    val lines = body.lines()
    val at = lines.indexOfFirst { it.isNotBlank() }
    if (at < 0) return null
    val match = HTML_TITLE.matchEntire(lines[at]) ?: return null
    return match.groupValues[1] to (lines.filterIndexed { index, _ -> index != at }.joinToString("\n"))
}

/** ```ui spec parser; null on malformed JSON or a non-object root. */
fun parseNativeUiSpec(json: String): NativeUiSpec? {
    val root = MiniJson.parse(json) as? Map<*, *> ?: return null
    return NativeUiSpec(
        title = stringOf(root["title"]),
        subtitle = stringOf(root["subtitle"]),
        metrics = listOfMaps(root["metrics"]).mapNotNull { m ->
            val label = stringOf(m["label"])
            val value = stringOf(m["value"])
            if (label == null && value == null) return@mapNotNull null
            UiMetric(
                label = label.orEmpty(),
                value = value.orEmpty(),
                ratio = (m["ratio"] as? Number)?.toFloat()?.coerceIn(0f, 1f),
            )
        },
        steps = listOfMaps(root["steps"]).mapNotNull { m ->
            val label = stringOf(m["label"]) ?: return@mapNotNull null
            UiStep(label = label, done = m["done"] as? Boolean ?: false)
        },
        actions = listOfMaps(root["actions"]).mapNotNull { m ->
            val label = stringOf(m["label"]) ?: return@mapNotNull null
            UiAction(label = label, prompt = stringOf(m["prompt"]) ?: label)
        },
    )
}

private fun stringOf(v: Any?): String? = when (v) {
    is String -> v.trim().ifBlank { null }
    is Number -> if (v.toDouble() % 1.0 == 0.0) v.toLong().toString() else v.toString()
    is Boolean -> v.toString()
    else -> null
}

private fun listOfMaps(v: Any?): List<Map<*, *>> =
    (v as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()

// ----------------------------------------------------------------- tool-call grouping

enum class ToolGroupState { RUNNING, ERROR, OK }

/** A feed row as rendered: one row, or a collapsed run of consecutive tool calls. */
internal sealed interface FeedRenderItem {
    data class Single(val row: DisplayRow) : FeedRenderItem
    data class ToolGroup(
        val rows: List<DisplayRow.Tool>,
        val count: Int,
        val summary: String,
        val state: ToolGroupState,
    ) : FeedRenderItem
}

/**
 * Collapses each run of consecutive tool rows into one [FeedRenderItem.ToolGroup];
 * every other row stays a [FeedRenderItem.Single]. A run of one is still a group so
 * the feed has exactly one tool presentation, and any interleaved message splits it.
 */
internal fun groupFeedRows(rows: List<DisplayRow>): List<FeedRenderItem> {
    val out = mutableListOf<FeedRenderItem>()
    var i = 0
    while (i < rows.size) {
        val row = rows[i]
        if (row !is DisplayRow.Tool) {
            out += FeedRenderItem.Single(row)
            i++
            continue
        }
        var end = i
        while (end < rows.size && rows[end] is DisplayRow.Tool) end++
        val run = rows.subList(i, end).filterIsInstance<DisplayRow.Tool>()
        out += FeedRenderItem.ToolGroup(
            rows = run,
            count = run.size,
            summary = toolGroupSummary(run),
            state = toolGroupState(run),
        )
        i = end
    }
    return out
}

/** `bash ×4`, or `bash, read ×4` when the run mixes tools. */
internal fun toolGroupSummary(run: List<DisplayRow.Tool>): String {
    val names = run.map { it.call.tool?.name?.takeIf { name -> name.isNotBlank() } ?: "tool" }.distinct()
    return names.joinToString(", ") + " ×" + run.size
}

internal fun toolGroupState(run: List<DisplayRow.Tool>): ToolGroupState {
    val statuses = run.map { (it.result?.tool?.status ?: it.call.tool?.status).orEmpty().lowercase() }
    return when {
        statuses.any { it == "error" || it == "failed" } -> ToolGroupState.ERROR
        statuses.all { it == "ok" || it == "success" || it == "done" } -> ToolGroupState.OK
        else -> ToolGroupState.RUNNING
    }
}

/**
 * Compact collapsed stand-in for a run of tool calls: one header line with count,
 * tool summary and overall state; tapping unfolds the individual [rowContent] rows.
 */
@Composable
internal fun ToolGroupCard(
    group: FeedRenderItem.ToolGroup,
    rowContent: @Composable (DisplayRow.Tool) -> Unit,
) {
    val colors = LocalMaestriColors.current
    var expanded by remember(group.rows.firstOrNull()?.call?.entryId) { mutableStateOf(false) }
    val stateColor = when (group.state) {
        ToolGroupState.OK -> colors.positive
        ToolGroupState.ERROR -> colors.errorText
        ToolGroupState.RUNNING -> colors.warning
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.canvasSurface, cardShape())
            .border(1.dp, colors.hairline, cardShape())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        ) {
            Text(if (expanded) "▾" else "▸", fontSize = 11.sp, color = colors.secondaryLabel)
            Spacer(Modifier.width(6.dp))
            Text(
                "${group.count} tool calls",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "(${group.summary})",
                fontSize = 12.sp,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(7.dp).background(stateColor, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(group.state.name.lowercase(), style = monoHud(10, color = colors.secondaryLabel))
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                group.rows.forEach { rowContent(it) }
            }
        }
    }
}

// ----------------------------------------------------------------- message body

/**
 * Assistant side of a bubble: prose plus any ```ui / ```ui-html cards the model
 * emitted, in message order. A single prose segment renders as one Text — the
 * pre-Intelligent-UI face, byte-identical for messages without fenced blocks.
 */
@Composable
fun IntelligentMessageBody(
    text: String,
    bodySizeSp: Int,
    color: Color,
    composerReady: Boolean,
    onSendPrompt: (String) -> Unit,
) {
    val segments = remember(text) { parseIntelligentSegments(text) }
    if (isPlainProseOnly(segments)) {
        val prose = (segments.firstOrNull() as? UiSegment.Prose)?.text ?: text
        Text(prose, fontSize = bodySizeSp.sp, color = color)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is UiSegment.Prose -> Text(segment.text, fontSize = bodySizeSp.sp, color = color)
                is UiSegment.NativeCard -> NativeUiCard(segment.spec, composerReady, onSendPrompt)
                is UiSegment.HtmlWidget -> HtmlWidgetCard(segment.title, segment.html, onSendPrompt)
            }
        }
    }
}

/** Plain-`Text` fast path: no segments at all, or exactly one prose segment. */
internal fun isPlainProseOnly(segments: List<UiSegment>): Boolean =
    segments.isEmpty() || (segments.size == 1 && segments[0] is UiSegment.Prose)

@Composable
fun NativeUiCard(
    spec: NativeUiSpec,
    composerReady: Boolean,
    onSendPrompt: (String) -> Unit,
) {
    val colors = LocalMaestriColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.cardSunken, cardShape())
            .border(1.dp, colors.hairline, cardShape())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        spec.title?.let {
            Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        spec.subtitle?.let {
            Text(it, fontSize = 12.sp, color = colors.secondaryLabel)
        }
        spec.metrics.forEach { metric -> MetricRow(metric) }
        if (spec.steps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                spec.steps.forEach { step -> StepRow(step) }
            }
        }
        if (spec.actions.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                spec.actions.forEach { action ->
                    TextButton(
                        onClick = { onSendPrompt(action.prompt) },
                        enabled = composerReady,
                        modifier = Modifier
                            .background(colors.accent.copy(alpha = if (composerReady) 0.14f else 0.05f), pillShape())
                            .border(1.dp, colors.accent.copy(alpha = if (composerReady) 0.5f else 0.2f), pillShape()),
                    ) {
                        Text(
                            action.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (composerReady) colors.accent else colors.secondaryLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricRow(metric: UiMetric) {
    val colors = LocalMaestriColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                metric.label,
                fontSize = 12.sp,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(metric.value, style = monoHud(11, color = MaterialTheme.colorScheme.onSurface))
        }
        metric.ratio?.let { ratio ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(colors.hairline.copy(alpha = 0.45f), pillShape()),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(ratio)
                        .height(5.dp)
                        .background(colors.accent, pillShape()),
                )
            }
        }
    }
}

@Composable
private fun StepRow(step: UiStep) {
    val colors = LocalMaestriColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (step.done) "✓" else "○",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (step.done) colors.positive else colors.secondaryLabel,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            step.label,
            fontSize = 12.5.sp,
            color = if (step.done) colors.secondaryLabel else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun HtmlWidgetCard(
    title: String?,
    html: String,
    onSendPrompt: (String) -> Unit,
) {
    val colors = LocalMaestriColors.current
    var expanded by remember(html) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.hairline, cardShape())
            .background(colors.canvasSurface, cardShape())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title ?: "Interactive Widget",
                style = monoHud(10, color = colors.secondaryLabel),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Expandir",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent,
                modifier = Modifier
                    .clickable { expanded = true }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        SandboxedWidgetWebView(
            html = html,
            modifier = Modifier.fillMaxWidth().height(260.dp).clip(controlShape()),
            onSendPrompt = onSendPrompt,
        )
    }
    if (!expanded) return
    Dialog(
        onDismissRequest = { expanded = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .background(colors.canvasChrome)
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title ?: "Interactive Widget",
                    style = monoHud(11, color = MaterialTheme.colorScheme.onSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Fechar",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent,
                    modifier = Modifier
                        .clickable { expanded = false }
                        .padding(6.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            SandboxedWidgetWebView(
                html = html,
                modifier = Modifier.fillMaxSize().clip(controlShape()),
                onSendPrompt = onSendPrompt,
            )
        }
    }
}

// ----------------------------------------------------------------- html widget sandbox

private const val WIDGET_CSS =
    "html,body{margin:0;padding:10px;background:#13161C;color:#E6EDF3;" +
        "font-family:system-ui,-apple-system,'Segoe UI',sans-serif;font-size:13px;line-height:1.4}" +
        "*{box-sizing:border-box}h1,h2,h3{margin:.2em 0 .4em;font-size:1.1em}" +
        "button{background:#262B33;color:#E6EDF3;border:1px solid #3D4350;border-radius:8px;" +
        "padding:6px 10px;font:inherit;margin:3px 4px 3px 0}" +
        "button:active{background:#39404C}" +
        "input,select,textarea{background:#1B2028;color:#E6EDF3;border:1px solid #3D4350;" +
        "border-radius:6px;padding:4px 6px;font:inherit;max-width:100%}" +
        "a{color:#7AB0FF}svg,canvas{max-width:100%;height:auto}table{border-collapse:collapse;width:100%}" +
        "td,th{border-bottom:1px solid #2C313A;padding:3px 4px;text-align:left;font-size:12px}"

/**
 * Inline HTML mini-app: JavaScript on, but no filesystem, no storage and no network
 * (CSP `default-src 'none'` plus blockNetworkLoads), so model-authored markup can only
 * draw and talk back through [WIDGET_BRIDGE]. Touch is disallowed from the parent on
 * DOWN/MOVE so sliders and canvas drags are not stolen by the outer LazyColumn.
 * shortcut: the WebView is rebuilt when the row leaves the LazyColumn viewport, so
 * in-widget state (slider position, canvas drawing) resets on long scrolls.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SandboxedWidgetWebView(
    html: String,
    modifier: Modifier,
    onSendPrompt: (String) -> Unit,
) {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onSendPrompt)
    key(html) {
        val webView = remember {
            WebView(context).apply {
                setBackgroundColor(0xFF13161C.toInt())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.loadsImagesAutomatically = true
                settings.blockNetworkLoads = true
                webViewClient = SandboxedWidgetClient()
                val handler = Handler(Looper.getMainLooper())
                addJavascriptInterface(
                    object {
                        @android.webkit.JavascriptInterface
                        fun send(prompt: String) {
                            handler.post {
                                val trimmed = prompt.trim().take(32_768)
                                if (trimmed.isNotEmpty()) latest(trimmed)
                            }
                        }
                    },
                    WIDGET_BRIDGE,
                )
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                loadWidget(this, html)
            }
        }
        DisposableEffect(webView) {
            onDispose {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }
        AndroidView(factory = { webView }, modifier = modifier)
    }
}

private const val WIDGET_BRIDGE = "maestri"

private fun loadWidget(webView: WebView, html: String) {
    webView.loadDataWithBaseURL(null, widgetDocument(html), "text/html", "utf-8", null)
}

private fun widgetDocument(html: String): String =
    "<!DOCTYPE html><html><head>" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
        "style-src 'unsafe-inline'; script-src 'unsafe-inline' 'unsafe-eval'; " +
        "img-src data:; font-src data:; connect-src 'none'\">" +
        "<style>$WIDGET_CSS</style></head><body>$html</body></html>"

/** Everything that leaves the document is refused: no navigation, no external fetches. */
private class SandboxedWidgetClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val scheme = request?.url?.scheme?.lowercase() ?: return blockedResponse()
        return if (scheme == "http" || scheme == "https") blockedResponse() else null
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
