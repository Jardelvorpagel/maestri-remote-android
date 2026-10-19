package app.maestri.live

/** Maximum inbound events kept in [PiRelayClient.events]: oldest seqs drop first. */
const val MAX_BUFFERED_EVENTS = 2000

/** Connection lifecycle surfaced by [PiRelayClient.state]. */
sealed interface RelayState {
    data object Disconnected : RelayState
    data object Connecting : RelayState
    data object Open : RelayState
    data class Error(val message: String) : RelayState
}

/**
 * Contract `tool{...}` block, shaped to the screen model (non-null, defaulted):
 * absent fields arrive as "", previews truncated to 500 chars.
 */
data class ToolInfo(
    val name: String = "",
    val callId: String = "",
    val status: String = "",
    val summary: String = "",
    val argsPreview: String = "",
    val resultPreview: String = "",
)

/** Contract envelope: unknown `kind` values are preserved, never dropped. */
data class PiEvent(
    val v: Int,
    val seq: Long,
    val sessionId: String,
    val entryId: String,
    val ts: String,
    val kind: String,
    val role: String?,
    val text: String?,
    val tool: ToolInfo?,
)

/** Inbound WS frames: content events plus the contract control kinds. */
sealed interface RelayFrame {
    data class Hello(
        val sessionId: String,
        val replayFrom: Long?,
        val replayTo: Long?,
    ) : RelayFrame

    data class Event(val event: PiEvent) : RelayFrame
    data object Ping : RelayFrame
    data object Pong : RelayFrame
    data class ResumeGap(val oldestSeq: Long?) : RelayFrame
}

/**
 * Minimal JSON reader (objects, arrays, strings, numbers, booleans, null) so the
 * relay parser stays dependency-free and JVM-unit-testable; org.json is an Android
 * stub that throws outside instrumented tests. Returns maps, lists, strings, longs,
 * doubles, booleans, or null; null on any malformed input, never throws.
 */
internal object MiniJson {
    private class Malformed : Exception()

    fun parse(text: String): Any? =
        try {
            Parser(text).parseTopLevel()
        } catch (_: Malformed) {
            null
        } catch (_: StackOverflowError) {
            null
        }

    private class Parser(val s: String) {
        var i = 0
        var depth = 0

        fun parseTopLevel(): Any? {
            val v = parseValue()
            skipWs()
            if (i != s.length) throw Malformed()
            return v
        }

        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) throw Malformed()
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> parseNumber()
            }
        }

        fun parseObject(): Map<String, Any?> {
            if (depth++ > 64) throw Malformed()
            i++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                depth--
                return out
            }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw Malformed()
                val key = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw Malformed()
                i++
                out[key] = parseValue()
                skipWs()
                if (i >= s.length) throw Malformed()
                if (s[i] == ',') {
                    i++
                    continue
                }
                if (s[i] == '}') {
                    i++
                    depth--
                    return out
                }
                throw Malformed()
            }
        }

        fun parseArray(): List<Any?> {
            if (depth++ > 64) throw Malformed()
            i++
            val out = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                depth--
                return out
            }
            while (true) {
                out.add(parseValue())
                skipWs()
                if (i >= s.length) throw Malformed()
                if (s[i] == ',') {
                    i++
                    continue
                }
                if (s[i] == ']') {
                    i++
                    depth--
                    return out
                }
                throw Malformed()
            }
        }

        fun parseString(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw Malformed()
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                if (i >= s.length) throw Malformed()
                when (val e = s[i++]) {
                    '"', '\\', '/' -> sb.append(e)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) throw Malformed()
                        val hex = s.substring(i, i + 4)
                        val code = hex.toIntOrNull(16) ?: throw Malformed()
                        i += 4
                        sb.append(code.toChar())
                    }
                    else -> throw Malformed()
                }
            }
        }

        fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw Malformed()
            i += word.length
            return value
        }

        fun parseNumber(): Number {
            val start = i
            while (i < s.length && s[i] in "-+0123456789.eE") i++
            if (start == i) throw Malformed()
            val raw = s.substring(start, i)
            if (raw.matches(Regex("-?\\d+"))) {
                raw.toLongOrNull()?.let { return it }
            }
            return raw.toDoubleOrNull() ?: throw Malformed()
        }

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }
    }
}

/**
 * Contract v1 frame/envelope parsing. Pure Kotlin, no Android APIs: unknown object
 * fields are ignored; any non-control kind carrying a seq surfaces as an event so
 * the screen can render a neutral row; malformed input yields null, never throws.
 */
object PiRelayParser {
    fun parseFrame(json: String): RelayFrame? {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val kind = root["kind"] as? String ?: return null
        return when (kind) {
            "hello" -> RelayFrame.Hello(
                sessionId = root["sessionId"] as? String ?: "",
                replayFrom = asLong(root["replayFrom"]),
                replayTo = asLong(root["replayTo"]),
            )
            "ping" -> RelayFrame.Ping
            "pong" -> RelayFrame.Pong
            "resume_gap" -> RelayFrame.ResumeGap(oldestSeq = asLong(root["oldestSeq"]))
            "resume" -> null
            else -> parseEvent(root, kind)?.let(RelayFrame::Event)
        }
    }

    fun parseEvent(json: String): PiEvent? {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val kind = root["kind"] as? String ?: return null
        if (kind == "hello" || kind == "ping" || kind == "pong" || kind == "resume_gap" || kind == "resume") return null
        return parseEvent(root, kind)
    }

    private fun parseEvent(root: Map<*, *>, kind: String): PiEvent? {
        val seq = asLong(root["seq"]) ?: return null
        val toolRaw = root["tool"] as? Map<*, *>
        return PiEvent(
            v = asInt(root["v"]) ?: 1,
            seq = seq,
            sessionId = root["sessionId"] as? String ?: "",
            entryId = root["entryId"] as? String ?: "",
            ts = root["ts"] as? String ?: "",
            kind = kind,
            role = root["role"] as? String,
            text = root["text"] as? String,
            tool = toolRaw?.let {
                ToolInfo(
                    name = it["name"] as? String ?: "",
                    callId = it["callId"] as? String ?: "",
                    status = it["status"] as? String ?: "",
                    summary = it["summary"] as? String ?: "",
                    argsPreview = (it["argsPreview"] as? String ?: "").take(500),
                    resultPreview = (it["resultPreview"] as? String ?: "").take(500),
                )
            },
        )
    }

    private fun asLong(v: Any?): Long? = when (v) {
        is Long -> v
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    private fun asInt(v: Any?): Int? = when (v) {
        is Long -> v.toInt()
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
}

/**
 * Pure event-buffer step: dedup by seq, keep ascending seq order, bound to the last
 * [maxSize] entries (lowest seqs drop first).
 */
fun insertRelayEvent(
    current: List<PiEvent>,
    event: PiEvent,
    maxSize: Int = MAX_BUFFERED_EVENTS,
): List<PiEvent> {
    if (current.any { it.seq == event.seq }) return current
    val next = (current + event).sortedBy { it.seq }
    return if (next.size > maxSize) next.drop(next.size - maxSize) else next
}

/** Backoff base before jitter: 1s, 2s, 4s … capped at 30s. Attempt counts failures. */
fun backoffBaseMs(attempt: Int): Long {
    val shift = attempt.coerceIn(0, 5)
    return minOf(30_000L, 1_000L shl shift)
}

/** Client→server frames. */
fun pingFrame(): String = """{"kind":"ping"}"""
fun pongFrame(): String = """{"kind":"pong"}"""
fun resumeFrame(afterSeq: Long): String = """{"kind":"resume","afterSeq":$afterSeq}"""

/** http(s) relay URLs normalize to ws(s); surrounding whitespace is trimmed. */
fun normalizeRelayUrl(url: String): String {
    val t = url.trim()
    return when {
        t.startsWith("http://", ignoreCase = true) -> "ws://" + t.substring(7)
        t.startsWith("https://", ignoreCase = true) -> "wss://" + t.substring(8)
        else -> t
    }
}

/** `?token=` query fallback appended with the correct separator, value encoded. */
fun withTokenQuery(url: String, token: String): String {
    val sep = if (url.contains("?")) "&" else "?"
    val encoded = try {
        java.net.URLEncoder.encode(token, "UTF-8")
    } catch (_: Exception) {
        token
    }
    return url + sep + "token=" + encoded
}
