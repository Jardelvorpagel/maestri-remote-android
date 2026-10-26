package app.maestri.ui.Style

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import app.maestri.live.GeminiCatalog
import app.maestri.live.GeminiPreset
import kotlin.math.sin

/**
 * The Gemini-app reading surface: pure OLED black, the indigo aurora under the composer, the
 * four-point sparkle and the royal-blue controls. Scoped to the chat face and its composer —
 * the rest of the app keeps its own chrome tokens.
 */
object Gemini {
    val Canvas = Color(0xFF000000)
    /** The attachment sheet sits one step above the canvas. */
    val Sheet = Color(0xFF131314)
    val AuroraDeep = Color(0xFF081238)
    val AuroraTop = Color(0xFF0D1B4C)
    /** Bubbles, the composer pill and the active mode pill. */
    val Surface = Color(0xFF1E1F22)
    /** Inline code, the scroll-to-bottom button. */
    val SurfaceRaised = Color(0xFF282A2E)
    val Text = Color(0xFFE3E3E3)
    val TextStrong = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFC4C7C5)
    val TextMuted = Color(0xFF8E918F)
    val Outline = Color(0xFF444746)
    val Divider = Color(0xFF333538)
    val Accent = Color(0xFF2848A8)
    val AccentDeep = Color(0xFF1B3A8A)
    val StarBlue = Color(0xFF4285F4)
    val StarGreen = Color(0xFF34A853)
    val StarYellow = Color(0xFFFBBC05)
    val StarRed = Color(0xFFEA4335)
}

/** The hero line of an empty chat. One constant: the reference's wording, change it here. */
const val GeminiHeroPrompt = "What can I help with, Jardel?"

/** The assistant footer the reference puts under the last answer. */
const val GeminiAiCaption = "Maestri is AI and can make mistakes."

/** Distance the shimmer travels before it reaches the text; larger than any phone width. */
private const val SHIMMER_SPAN = 900f

private val StarBrush = listOf(Gemini.StarBlue, Gemini.StarGreen, Gemini.StarYellow, Gemini.StarRed)

/**
 * The four-point sparkle: concave sides between four tips, painted with the four Google colors.
 * [pulse] adds the slow breathing turn; [scaleIn] plays the one-shot settle for a launch.
 */
@Composable
fun GeminiStar(
    size: Dp,
    modifier: Modifier = Modifier,
    pulse: Boolean = true,
    scaleIn: Boolean = false,
) {
    val settle = remember { Animatable(if (scaleIn) 0.55f else 1f) }
    LaunchedEffect(scaleIn) {
        if (scaleIn) settle.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 90f))
    }
    val breath = rememberInfiniteTransition(label = "gemini-star")
    val wave by breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "gemini-breath",
    )
    Canvas(modifier.size(size)) {
        val grow = settle.value * if (pulse) 0.94f + 0.06f else 1f
        val spin = if (pulse) (wave - 0.5f) * 10f else 0f
        val r = (size.toPx() / 2f) * grow
        val path = sparklePath(Offset(this.size.width / 2f, this.size.height / 2f), r)
        rotate(spin) {
            drawPath(
                path,
                Brush.linearGradient(
                    StarBrush,
                    start = Offset(0f, 0f),
                    end = Offset(this.size.width, this.size.height),
                ),
            )
        }
    }
}

/** Four concave-sided points: the Gemini sparkle outline. */
private fun sparklePath(center: Offset, r: Float): Path = Path().apply {
    val c = r * 0.22f
    moveTo(center.x, center.y - r)
    quadraticTo(center.x + c, center.y - c, center.x + r, center.y)
    quadraticTo(center.x + c, center.y + c, center.x, center.y + r)
    quadraticTo(center.x - c, center.y + c, center.x - r, center.y)
    quadraticTo(center.x - c, center.y - c, center.x, center.y - r)
    close()
}

/**
 * The indigo wash the reference keeps under the composer: a vertical fade into the deep blue
 * plus a radial bloom, with a slow digital band sweeping across it.
 *
 * [progress] 0..1 scales the whole thing in (launch); 1f is the resting empty-state look.
 */
@Composable
fun GeminiAurora(
    modifier: Modifier = Modifier,
    progress: Float = 1f,
    animate: Boolean = true,
) {
    val glow = rememberInfiniteTransition(label = "gemini-aurora")
    val sweep by glow.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "gemini-sweep",
    )
    Canvas(modifier.fillMaxWidth()) {
        drawAurora(progress, if (animate) sweep else 0.35f)
    }
}

private fun DrawScope.drawAurora(progress: Float, sweep: Float) {
    if (progress <= 0f) return
    val h = size.height
    val w = size.width
    val p = progress.coerceIn(0f, 1f)
    // Four gentle stops, the first two at zero alpha: on 182 the wash began at 0.45 with a
    // visible horizontal cut where the box started, so the gradient now fades in from the top.
    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.30f to Color.Transparent,
            0.55f to Gemini.AuroraDeep.copy(alpha = 0.18f * p),
            0.80f to Gemini.AuroraDeep.copy(alpha = 0.55f * p),
            1f to Gemini.AuroraTop.copy(alpha = 0.90f * p),
        ),
    )
    drawRect(
        Brush.radialGradient(
            listOf(Gemini.StarBlue.copy(alpha = 0.26f * p), Color.Transparent),
            center = Offset(w / 2f, h * 1.05f),
            radius = w * 0.95f,
        ),
    )
    // The digital wave: a soft diagonal band crossing the wash, twice per cycle.
    val band = ((sweep * 1.8f) % 1f) * (w * 1.6f) - w * 0.3f
    drawRect(
        Brush.linearGradient(
            listOf(
                Color.Transparent,
                Gemini.StarBlue.copy(alpha = 0.16f * p),
                Gemini.StarGreen.copy(alpha = 0.10f * p),
                Color.Transparent,
            ),
            start = Offset(band - w * 0.35f, h),
            end = Offset(band + w * 0.35f, h * 0.35f),
        ),
    )
    // Scan lines keep the sweep reading as digital light rather than a soft blob.
    for (i in 0 until 3) {
        val y = h * (0.62f + 0.12f * i)
        val shift = (sin((sweep * 6.28318f) + i) * 0.5f + 0.5f) * w
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Gemini.StarBlue.copy(alpha = 0.10f * p), Color.Transparent),
                start = Offset(shift - w * 0.4f, y),
                end = Offset(shift + w * 0.4f, y),
            ),
            topLeft = Offset(0f, y),
            size = androidx.compose.ui.geometry.Size(w, 1.2f),
        )
    }
}

/**
 * Empty-state hero: the sparkle above a slowly shimmering prompt, over the aurora.
 * [caption] is the app's own line about what will land here, kept in the reference's muted tone.
 */
@Composable
fun GeminiHero(caption: String?, modifier: Modifier = Modifier) {
    val shimmer = rememberInfiniteTransition(label = "gemini-hero")
    val shift by shimmer.animateFloat(
        initialValue = -SHIMMER_SPAN,
        targetValue = SHIMMER_SPAN * 2f,
        animationSpec = infiniteRepeatable(tween(3400, easing = LinearEasing)),
        label = "gemini-shimmer",
    )
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            GeminiStar(size = 52.dp, scaleIn = true)
            Text(
                GeminiHeroPrompt,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    textAlign = TextAlign.Center,
                    brush = Brush.linearGradient(
                        listOf(Gemini.Text, Gemini.TextStrong, Gemini.Text),
                        start = Offset(shift - 220f, 0f),
                        end = Offset(shift, 0f),
                    ),
                ),
            )
            if (caption != null) {
                Text(
                    caption,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 13.sp,
                    color = Gemini.TextMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The launch moment: black canvas, the aurora rising in, the sparkle settling with its first
 * turn, then the whole overlay fades off the live feed. Runs once per chat-face mount.
 */
@Composable
fun GeminiLaunchOverlay(visible: Boolean, onFinished: () -> Unit) {
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        rise.animateTo(1f, tween(900))
        kotlinx.coroutines.delay(650)
        onFinished()
    }
    AnimatedVisibility(visible = visible, exit = fadeOut(tween(420))) {
        Box(Modifier.fillMaxSize().background(Gemini.Canvas)) {
            Box(Modifier.fillMaxSize()) {
                GeminiAurora(modifier = Modifier.fillMaxSize(), progress = rise.value)
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GeminiStar(size = 64.dp, scaleIn = true)
            }
            Box(Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
                Text(
                    "Maestri",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Gemini.TextMuted.copy(alpha = rise.value),
                )
            }
        }
    }
}

/**
 * The model / thinking trigger and its floating card, after the Gemini app: the trigger reads
 * `<model short> <thinking> ⌄` in two tones, the card lists the four models over a hairline
 * divider and the thinking-level accordion underneath.
 */
@Composable
fun GeminiModelSelector(
    preset: GeminiPreset,
    onModel: (String) -> Unit,
    onThinking: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var thinkingExpanded by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable { open = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text(
                GeminiCatalog.headerModel(preset),
                fontFamily = FontFamily.SansSerif,
                fontSize = 17.sp,
                color = Gemini.Text,
            )
            Text(
                " " + GeminiCatalog.headerThinking(preset),
                fontFamily = FontFamily.SansSerif,
                fontSize = 17.sp,
                color = Gemini.TextMuted,
            )
            Text(" \u2304", fontSize = 15.sp, color = Gemini.TextMuted)
        }
        if (open) {
            Popup(
                onDismissRequest = { open = false },
                offset = IntOffset(0, with(density) { 34.dp.roundToPx() }),
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .width(300.dp)
                        .background(Gemini.Surface, RoundedCornerShape(28.dp))
                        .padding(vertical = 10.dp),
                ) {
                    GeminiCatalog.models.forEach { candidate ->
                        GeminiPickerRow(
                            title = candidate.title,
                            subtitle = candidate.subtitle,
                            selected = candidate.id == preset.modelId,
                            onClick = {
                                onModel(candidate.id)
                                open = false
                            },
                        )
                    }
                    Box(
                        Modifier
                            .padding(horizontal = 18.dp, vertical = 6.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Gemini.Divider),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { thinkingExpanded = !thinkingExpanded }
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Text(
                            "Thinking level",
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 17.sp,
                            color = Gemini.Text,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (thinkingExpanded) "\u2303" else "\u2304",
                            fontSize = 15.sp,
                            color = Gemini.TextSecondary,
                        )
                    }
                    AnimatedVisibility(
                        visible = thinkingExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column {
                            GeminiCatalog.thinking.forEach { level ->
                                GeminiPickerRow(
                                    title = level.title,
                                    subtitle = level.subtitle,
                                    selected = level.id == preset.thinkingId,
                                    onClick = {
                                        onThinking(level.id)
                                        open = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One selectable line of the card: `✓` gutter, title, muted subtitle. */
@Composable
private fun GeminiPickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Box(Modifier.width(28.dp)) {
            if (selected) {
                Text("\u2713", fontSize = 16.sp, color = Gemini.Text)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = FontFamily.SansSerif, fontSize = 17.sp, color = Gemini.Text)
            Text(
                subtitle,
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                color = Gemini.TextMuted,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/**
 * The attachment sheet's contract surface: what its accessibility tree says and how dark its scrim
 * is. Kept as plain constants so both are asserted off-device instead of living inside lambdas.
 */
object GeminiAttachmentSheetLabels {
    /** The sheet container itself — the root of its subtree. */
    const val Root = "Anexos"

    /** The tap-to-dismiss target behind the sheet: the scrim, and nothing else. */
    const val DismissTarget = "Fechar anexos"

    /**
     * Single-layer scrim alpha. Everything darkening the screen behind the sheet is this one Box:
     * 185 also carried the platform Dialog dim (the theme's 0.6), which stacked to 0.84.
     */
    const val ScrimAlpha = 0.6f

    const val Photos = "Photos"
    const val Camera = "Camera"
    const val Files = "Files"
    val tiles = listOf(Photos, Camera, Files)
}

/** The three tile glyphs, drawn as line art instead of font characters. */
internal enum class AttachmentIconKind { Photos, Camera, Files }

/**
 * The `+` sheet, after the Gemini app: a #131314 panel docked flush to the bottom edge, 28 dp
 * corners on the top only, a drag handle, three equal tiles (Photos / Camera / Files) and two
 * prompt shortcuts underneath. Tiles only launch what the host can actually do; every action closes
 * the sheet first so the picker is never behind it.
 *
 * Hand-rolled on a Dialog rather than ModalBottomSheet: the Material sheet caps itself at
 * `BottomSheetDefaults.SheetMaxWidth` (640 dp) and labels its scrim with Material's own translated
 * string, which on a tablet read as a floating 640 dp panel over an undimmed chat, announcing
 * "Fechar planilha".
 */
@Composable
fun GeminiAttachmentSheet(
    onPhotos: () -> Unit,
    onCamera: () -> Unit,
    onFiles: () -> Unit,
    onPrompt: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val rise by animateFloatAsState(if (shown) 1f else 0f, tween(durationMillis = 220), label = "sheet")
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The dialog window dims the screen behind it on its own (the theme's windowDimAmount,
        // 0.6): with our scrim on top that measured 0.84 on 185, which is exactly 1 - 0.4^2.
        val dialogView = LocalView.current
        val dialogWindow = (dialogView as? DialogWindowProvider)?.window
            ?: (dialogView.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setDimAmount(0f) }
        Box(
            Modifier.fillMaxSize().semantics { contentDescription = GeminiAttachmentSheetLabels.Root },
        ) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = GeminiAttachmentSheetLabels.ScrimAlpha * rise))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        onDismiss()
                    }
                    .semantics { contentDescription = GeminiAttachmentSheetLabels.DismissTarget },
            )
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer { translationY = (1f - rise) * size.height }
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(Gemini.Sheet)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(top = 10.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).background(Gemini.TextMuted, RoundedCornerShape(2.dp)))
                Column(
                    Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(top = 18.dp, start = 16.dp, end = 16.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AttachmentTile(AttachmentIconKind.Photos, GeminiAttachmentSheetLabels.Photos, Modifier.weight(1f)) {
                            onDismiss()
                            onPhotos()
                        }
                        AttachmentTile(AttachmentIconKind.Camera, GeminiAttachmentSheetLabels.Camera, Modifier.weight(1f)) {
                            onDismiss()
                            onCamera()
                        }
                        AttachmentTile(AttachmentIconKind.Files, GeminiAttachmentSheetLabels.Files, Modifier.weight(1f)) {
                            onDismiss()
                            onFiles()
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    AttachmentAction(
                        glyph = "\u29C9",
                        title = "Canvas",
                        subtitle = "Build an interactive widget in chat",
                    ) {
                        onDismiss()
                        onPrompt(
                            "Build me an interactive widget for this chat: reply with a single `ui-html` " +
                                "fenced block (self-contained HTML + CSS + JS, no network) and one short line " +
                                "saying what it does.",
                        )
                    }
                    AttachmentAction(
                        glyph = "\u25A4",
                        title = "Visual card",
                        subtitle = "Summarize with metrics and quick actions",
                    ) {
                        onDismiss()
                        onPrompt(
                            "Summarize this conversation as a `ui` card: a headline, up to four metrics and " +
                                "two quick actions, in one fenced `ui` JSON block.",
                        )
                    }
                }
            }
        }
    }
}

/** One of the three equal tiles: line-art glyph on top, 14 sp label below. */
@Composable
private fun AttachmentTile(
    kind: AttachmentIconKind,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .height(96.dp)
            .background(Gemini.Surface, RoundedCornerShape(22.dp))
            .clickable { onClick() }
            .padding(8.dp),
    ) {
        AttachmentIcon(kind)
        Spacer(Modifier.height(10.dp))
        Text(label, fontFamily = FontFamily.SansSerif, fontSize = 14.sp, color = Gemini.Text)
    }
}

/**
 * The tile glyphs, drawn the way GeminiStar is: paths on a 24 dp canvas, 1.6 dp round strokes.
 * No icon dependency and no bundled asset, and they read as icons instead of boxed characters.
 */
@Composable
private fun AttachmentIcon(kind: AttachmentIconKind, tint: Color = Gemini.TextSecondary) {
    Canvas(Modifier.size(24.dp)) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height
        when (kind) {
            AttachmentIconKind.Photos -> {
                drawRoundRect(
                    tint,
                    topLeft = Offset(w * 0.08f, h * 0.16f),
                    size = Size(w * 0.84f, h * 0.68f),
                    cornerRadius = CornerRadius(w * 0.10f),
                    style = stroke,
                )
                drawCircle(tint, radius = w * 0.085f, center = Offset(w * 0.35f, h * 0.37f), style = stroke)
                drawPath(
                    Path().apply {
                        moveTo(w * 0.13f, h * 0.78f)
                        lineTo(w * 0.40f, h * 0.50f)
                        lineTo(w * 0.58f, h * 0.68f)
                        lineTo(w * 0.70f, h * 0.57f)
                        lineTo(w * 0.87f, h * 0.78f)
                    },
                    tint,
                    style = stroke,
                )
            }
            AttachmentIconKind.Camera -> {
                drawRoundRect(
                    tint,
                    topLeft = Offset(w * 0.06f, h * 0.28f),
                    size = Size(w * 0.88f, h * 0.52f),
                    cornerRadius = CornerRadius(w * 0.12f),
                    style = stroke,
                )
                drawPath(
                    Path().apply {
                        moveTo(w * 0.32f, h * 0.28f)
                        lineTo(w * 0.38f, h * 0.17f)
                        lineTo(w * 0.62f, h * 0.17f)
                        lineTo(w * 0.68f, h * 0.28f)
                    },
                    tint,
                    style = stroke,
                )
                drawCircle(tint, radius = w * 0.145f, center = Offset(w * 0.50f, h * 0.545f), style = stroke)
            }
            AttachmentIconKind.Files -> {
                drawPath(
                    Path().apply {
                        moveTo(w * 0.28f, h * 0.20f)
                        lineTo(w * 0.60f, h * 0.20f)
                        lineTo(w * 0.74f, h * 0.34f)
                        lineTo(w * 0.74f, h * 0.72f)
                    },
                    tint,
                    style = stroke,
                )
                drawRoundRect(
                    tint,
                    topLeft = Offset(w * 0.24f, h * 0.28f),
                    size = Size(w * 0.50f, h * 0.56f),
                    cornerRadius = CornerRadius(w * 0.06f),
                    style = stroke,
                )
                drawPath(
                    Path().apply {
                        moveTo(w * 0.40f, h * 0.46f)
                        lineTo(w * 0.58f, h * 0.46f)
                        moveTo(w * 0.40f, h * 0.60f)
                        lineTo(w * 0.58f, h * 0.60f)
                    },
                    tint,
                    style = stroke,
                )
            }
        }
    }
}

/** The Gemini action row: glyph, title, muted subtitle. */
@Composable
private fun AttachmentAction(glyph: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
    ) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            Text(glyph, fontSize = 19.sp, color = Gemini.TextSecondary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = FontFamily.SansSerif, fontSize = 16.sp, color = Gemini.Text)
            Text(
                subtitle,
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                color = Gemini.TextMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
