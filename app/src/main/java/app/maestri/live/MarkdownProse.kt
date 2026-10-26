package app.maestri.live

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.MaestriType

// ----------------------------------------------------------------- models

/**
 * Assistant prose as blocks. Deliberately small: the markdown an assistant actually emits
 * (headings, lists, quotes, fences, paragraphs) and nothing else. Unknown syntax stays
 * literal — the parser never throws and never drops text.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullets(val ordered: Boolean, val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val code: String) : MdBlock
}

enum class MdStyle { BOLD, ITALIC, CODE, STRIKE }

/** One run of text with the inline styles that apply to it. */
data class InlineSpan(val text: String, val styles: Set<MdStyle> = emptySet())

private const val MAX_INLINE_DEPTH = 4
private val MARKERS = listOf("**", "__", "~~", "`", "*", "_")
private val HEADING = Regex("^(#{1,3})\\s+(.+)$")
private val BULLET = Regex("^([-*•+])\\s+(.+)$")
private val ORDERED = Regex("^(\\d{1,3})[.)]\\s+(.+)$")

// ----------------------------------------------------------------- parser

/** Splits prose into blocks; a fence with no closer degrades to the literal text it is. */
fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    val paragraph = StringBuilder()
    fun flushParagraph() {
        val body = paragraph.toString().trim()
        if (body.isNotEmpty()) blocks += MdBlock.Paragraph(body)
        paragraph.setLength(0)
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                val close = (i + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("```") }
                flushParagraph()
                if (close == null) {
                    blocks += MdBlock.Paragraph(lines.subList(i, lines.size).joinToString("\n").trim())
                    i = lines.size
                } else {
                    blocks += MdBlock.Code(lines.subList(i + 1, close).joinToString("\n"))
                    i = close + 1
                }
            }
            trimmed.isEmpty() -> {
                flushParagraph()
                i++
            }
            else -> {
                val heading = HEADING.matchEntire(trimmed)
                val bullet = BULLET.matchEntire(trimmed)
                val ordered = ORDERED.matchEntire(trimmed)
                when {
                    heading != null -> {
                        flushParagraph()
                        blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
                        i++
                    }
                    bullet != null || ordered != null -> {
                        flushParagraph()
                        val isOrdered = ordered != null
                        val matcher = if (isOrdered) ORDERED else BULLET
                        val items = mutableListOf<String>()
                        while (i < lines.size) {
                            val row = matcher.matchEntire(lines[i].trim()) ?: break
                            items += row.groupValues[2].trim()
                            i++
                        }
                        blocks += MdBlock.Bullets(ordered = isOrdered, items = items)
                    }
                    trimmed.startsWith(">") -> {
                        flushParagraph()
                        val quoted = mutableListOf<String>()
                        while (i < lines.size) {
                            val row = lines[i].trim()
                            if (!row.startsWith(">")) break
                            quoted += row.removePrefix(">").trim()
                            i++
                        }
                        blocks += MdBlock.Quote(quoted.joinToString("\n").trim())
                    }
                    else -> {
                        paragraph.append(line.trimEnd()).append('\n')
                        i++
                    }
                }
            }
        }
    }
    flushParagraph()
    return blocks
}

/**
 * Inline runs for one line of prose: code is taken verbatim (nothing inside it is
 * interpreted), bold/italic/strike nest, and an opener with no closer stays literal.
 */
fun parseInline(text: String, base: Set<MdStyle> = emptySet(), depth: Int = 0): List<InlineSpan> {
    val out = mutableListOf<InlineSpan>()
    val run = StringBuilder()
    fun flush() {
        if (run.isNotEmpty()) {
            out += InlineSpan(run.toString(), base)
            run.setLength(0)
        }
    }
    var i = 0
    while (i < text.length) {
        val marker = MARKERS.firstOrNull { text.startsWith(it, i) }
        if (marker == null || depth >= MAX_INLINE_DEPTH) {
            run.append(text[i])
            i++
            continue
        }
        val closerAt = if (marker.length == 1) {
            findCloser(text, marker[0], i + 1)
        } else {
            text.indexOf(marker, i + marker.length)
        }
        val inner = if (closerAt > i + marker.length) {
            text.substring(i + marker.length, closerAt)
        } else {
            ""
        }
        if (closerAt < 0 || inner.isEmpty()) {
            run.append(marker)
            i += marker.length
            continue
        }
        flush()
        val style = styleFor(marker)
        out += if (style == MdStyle.CODE) {
            listOf(InlineSpan(inner, base + style))
        } else {
            parseInline(inner, base + style, depth + 1)
        }
        i = closerAt + marker.length
    }
    flush()
    return out
}

/** Next stand-alone occurrence of a one-char marker: runs of two or more are a pair, not a closer. */
private fun findCloser(text: String, marker: Char, from: Int): Int {
    var i = from
    while (i < text.length) {
        if (text[i] == marker) {
            var end = i
            while (end < text.length && text[end] == marker) end++
            if (end - i == 1) return i
            i = end
        } else {
            i++
        }
    }
    return -1
}

private fun styleFor(marker: String): MdStyle = when (marker) {
    "`" -> MdStyle.CODE
    "~~" -> MdStyle.STRIKE
    "**", "__" -> MdStyle.BOLD
    else -> MdStyle.ITALIC
}

// ----------------------------------------------------------------- renderer

/**
 * The reading measure: on the tablet the bare feed gave lines of 120-130 characters, which is
 * a wall, not a page. A cap keeps the prose column readable without shrinking the type, and
 * code fences scroll inside it instead of running the full width.
 */
private val ProseMaxWidth = 740.dp

/**
 * Renders assistant prose as markdown: headings, lists with a hanging indent, quotes,
 * horizontally scrolling code fences and inline emphasis.
 *
 * Typography follows the reading style of the reference (Claude chat): the platform serif at
 * [MaestriType.SerifBodyStep] above the composer text, 1.5 leading and real space between
 * blocks, so a long answer reads as a page instead of a status pill. Colors and families stay
 * on the app tokens; user turns keep the system sans of their bubble.
 */
@Composable
fun MarkdownProse(
    text: String,
    bodySizeSp: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    if (blocks.isEmpty()) return
    val proseSize = bodySizeSp + MaestriType.SerifBodyStep
    val leading = (proseSize * MaestriType.SerifBodyLeading).sp
    Column(modifier.widthIn(max = ProseMaxWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    inlineText(block.text, bodySizeSp, color),
                    fontFamily = MaestriType.Serif,
                    fontSize = (proseSize + if (block.level == 1) 5 else if (block.level == 2) 3 else 1).sp,
                    lineHeight = leading,
                    color = color,
                )
                is MdBlock.Paragraph -> Text(
                    inlineText(block.text, bodySizeSp, color),
                    fontFamily = MaestriType.Serif,
                    fontSize = proseSize.sp,
                    lineHeight = leading,
                    color = color,
                )
                is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                if (block.ordered) "${index + 1}." else "•",
                                fontFamily = MaestriType.Serif,
                                fontSize = proseSize.sp,
                                lineHeight = leading,
                                color = color.copy(alpha = 0.7f),
                                modifier = Modifier.widthIn(min = 22.dp),
                            )
                            Text(
                                inlineText(item, bodySizeSp, color),
                                fontFamily = MaestriType.Serif,
                                fontSize = proseSize.sp,
                                lineHeight = leading,
                                color = color,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(LocalMaestriColors.current.accent, RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        inlineText(block.text, bodySizeSp, color.copy(alpha = 0.85f)),
                        fontFamily = MaestriType.Serif,
                        fontSize = proseSize.sp,
                        lineHeight = leading,
                        color = color.copy(alpha = 0.85f),
                    )
                }
                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .background(LocalMaestriColors.current.codeSurface, RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(
                        block.code,
                        fontFamily = FontFamily.Monospace,
                        fontSize = (bodySizeSp - 1).sp,
                        color = color,
                    )
                }
            }
        }
    }
}

/** Annotated string for one line: bold/italic/strike/code become SpanStyles, links stay text. */
@Composable
private fun inlineText(text: String, bodySizeSp: Int, color: Color): AnnotatedString {
    val colors = LocalMaestriColors.current
    val spans = remember(text) { parseInline(text) }
    return remember(spans, color, bodySizeSp) {
        buildAnnotatedString {
            spans.forEach { span ->
                when {
                    MdStyle.CODE in span.styles -> withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = colors.cardSunken,
                            fontSize = (bodySizeSp + MaestriType.SerifBodyStep - 0.5f).sp,
                        ),
                    ) { append(span.text) }
                    else -> withStyle(
                        SpanStyle(
                            fontWeight = if (MdStyle.BOLD in span.styles) FontWeight.SemiBold else null,
                            fontStyle = if (MdStyle.ITALIC in span.styles) FontStyle.Italic else null,
                            textDecoration = if (MdStyle.STRIKE in span.styles) TextDecoration.LineThrough else null,
                        ),
                    ) { append(span.text) }
                }
            }
        }
    }
}
