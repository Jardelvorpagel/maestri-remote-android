package app.maestri.live

import okhttp3.Request

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
        val commands: Boolean? = null,
        /** Set when the relay has the socket open but no session bound to this terminal yet. */
        val waiting: String = "",
    ) : RelayFrame

    data class Event(val event: PiEvent) : RelayFrame
    data class SendAckFrame(val ack: SendAck) : RelayFrame
    data class ApprovalRequest(val approval: Approval) : RelayFrame
    data class ApprovalResolved(val approval: Approval) : RelayFrame
    data class AttachmentReady(val attachment: Attachment) : RelayFrame
    data class DecisionPromptFrame(val prompt: DecisionPrompt) : RelayFrame
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
                commands = asCommandFlag(root["commands"]) ?: asCommandFlag(root["commandsSupported"]),
                waiting = root["waiting"] as? String ?: "",
            )
            "approval_request" -> parseApproval(root)?.let(RelayFrame::ApprovalRequest)
            "approval_resolved" -> parseApproval(root)?.let(RelayFrame::ApprovalResolved)
            "send_ack" -> parseSendAck(root)?.let(RelayFrame::SendAckFrame)
            "attachment_ready" -> parseAttachment(root)?.let(RelayFrame::AttachmentReady)
            "decision_prompt" -> parseDecisionPrompt(root)?.let(RelayFrame::DecisionPromptFrame)
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
        if (kind == "hello" || kind == "ping" || kind == "pong" || kind == "resume_gap" || kind == "resume" || kind == "decision_prompt") return null
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

    private fun asCommandFlag(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is List<*> -> v.isNotEmpty()
        is String -> when (v.lowercase()) {
            "true", "supported", "enabled" -> true
            "false", "unsupported", "disabled" -> false
            else -> null
        }
        else -> null
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

/**
 * Handshake request: no `Authorization` header at all when the token is blank
 * (never a bare `Bearer `); `?token=` only on the post-401 fallback path.
 */
fun buildRelayRequest(url: String, token: String, queryFallback: Boolean): Request {
    val base = normalizeRelayUrl(url)
    require(base.isNotBlank()) { "blank relay URL" }
    val endpoint = if (queryFallback && token.isNotBlank()) withTokenQuery(base, token) else base
    val builder = Request.Builder().url(endpoint)
    if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
    return builder.build()
}

/** 401 copy fits both modes: tokenless relays vs a rejected token. */
fun relayAuthFailureMessage(tokenBlank: Boolean): String =
    if (tokenBlank) "This relay requires a token (HTTP 401)."
    else "Relay rejected the token (HTTP 401)."

/** `ws(s)://host:port/<path>` → `host:port`; blank when unparseable. */
fun relayHostPort(url: String): String {
    var t = url.trim()
    t = when {
        t.startsWith("ws://", ignoreCase = true) -> t.substring(5)
        t.startsWith("wss://", ignoreCase = true) -> t.substring(6)
        t.startsWith("http://", ignoreCase = true) -> t.substring(7)
        t.startsWith("https://", ignoreCase = true) -> t.substring(8)
        else -> return ""
    }
    return t.substringBefore('/').substringBefore('?').takeIf { it.isNotBlank() } ?: ""
}

/**
 * Chat empty-state body for a relay error: host:port when known, the failure
 * reason verbatim, then the retry position — scheduled automatic retry vs
 * tap-to-retry. The reason never lives in logcat alone.
 */
fun relayErrorBody(url: String, message: String, autoRetry: Boolean): String {
    val where = relayHostPort(url)
    val head = if (where.isNotBlank()) "$where — $message" else message
    val hint = if (autoRetry) "Automatic retry is scheduled — or tap Connect to retry now."
    else "Tap Connect to retry."
    return "$head $hint"
}

/**
 * Single-flight connect policy: dial fresh with no client; never interrupt an
 * attempt already Connecting/Open, and never redial a client still in its
 * startup window (client present but Disconnected — the client's own replay
 * briefly overwrites Connecting); only a terminal Error with a client redials.
 */
fun shouldRedial(hasClient: Boolean, state: RelayState): Boolean =
    !hasClient || state is RelayState.Error

/** Transport-boundary log helper. Callers pass metadata only, never the token. */
internal fun relayLog(tag: String, msg: String) {
    try {
        android.util.Log.d(tag, msg)
    } catch (_: RuntimeException) {
    }
}

// ------------------------------------------------------- v2 commands + acks

/** Outcome of one `send_message`, keyed by clientMsgId. */
data class SendOutcome(
    val clientMsgId: String,
    val accepted: Boolean,
    val detail: String = "",
)

/** Parsed `send_ack` frame. */
data class SendAck(
    val clientMsgId: String,
    val accepted: Boolean,
    val detail: String = "",
)

private val REJECTED_STATUSES = setOf("rejected", "error", "denied", "failed", "unsupported")
private val ACCEPTED_STATUSES = setOf("accepted", "ok", "received", "delivered", "acked")

/** Detail hinting the server does not implement commands (v1 daemon). */
internal fun looksUnsupported(detail: String): Boolean {
    val d = detail.lowercase()
    return d.contains("support") || d.contains("unknown kind") ||
        d.contains("not implemented") || d.contains("unrecognized")
}

/**
 * Tolerant ack rule: a `send_ack` naming our id counts as accepted unless it
 * carries an explicit rejection (error text, rejected/accepted:false/ok:false
 * flags, or a rejected status word). Bare acks accept.
 */
fun parseSendAck(root: Map<*, *>): SendAck? {
    val id = (root["clientMsgId"] as? String
        ?: root["client_msg_id"] as? String
        ?: return null).ifBlank { return null }
    val error = root["error"] as? String ?: ""
    val detailField = root["detail"] as? String ?: ""
    val message = root["message"] as? String ?: ""
    val status = (root["status"] as? String ?: "").lowercase()
    val rejected = error.isNotBlank() ||
        (root["rejected"] as? Boolean) == true ||
        (root["accepted"] as? Boolean) == false && root.containsKey("accepted") ||
        (root["ok"] as? Boolean) == false && root.containsKey("ok") ||
        status in REJECTED_STATUSES
    val detail = error.ifBlank { detailField.ifBlank { message.ifBlank { status } } }
    return SendAck(clientMsgId = id, accepted = !rejected, detail = detail)
}

/** Route one ack into the outcomes map. */
fun applySendAck(current: Map<String, SendOutcome>, ack: SendAck): Map<String, SendOutcome> =
    current + (ack.clientMsgId to SendOutcome(ack.clientMsgId, ack.accepted, ack.detail))

// ------------------------------------------------------- v2 approvals

/** One tool approval, tracked by callId from request through resolution. */
data class Approval(
    val callId: String = "",
    val tool: String = "",
    val summary: String = "",
    val argsPreview: String = "",
    val reason: String = "",
    val status: String = "",
    val decision: String = "",
)

/** Tolerant approval parse: `tool` may be a name string or a {name} object. */
fun parseApproval(root: Map<*, *>): Approval? {
    val callId = (root["callId"] as? String
        ?: root["call_id"] as? String
        ?: return null).ifBlank { return null }
    val toolRaw = root["tool"]
    val tool = (toolRaw as? String)
        ?: (toolRaw as? Map<*, *>)?.get("name") as? String
        ?: ""
    return Approval(
        callId = callId,
        tool = tool,
        summary = root["summary"] as? String ?: "",
        argsPreview = ((root["argsPreview"] as? String ?: "")).take(500),
        reason = root["reason"] as? String ?: "",
        status = root["status"] as? String ?: "",
        decision = root["decision"] as? String ?: "",
    )
}

// ------------------------------------------------------- decision mirror

/**
 * Pi's own decision box, mirrored from the host: `open` shows the banner,
 * `closed` clears it. `options` (possibly absent on old frames) renders as
 * plain chat-message shortcuts: tapping one sends its label through the normal
 * optimistic send path — no approval semantics, no resolver in the API.
 */
data class DecisionPrompt(
    val phase: String = "",
    val promptKind: String = "",
    val title: String = "",
    val at: String = "",
    val options: List<String> = emptyList(),
)

val DecisionPrompt.isOpen: Boolean get() = phase == "open"

/** Banner headline: title wins, promptKind is the fallback, never blank. */
fun DecisionPrompt.label(): String =
    title.ifBlank { promptKind.ifBlank { "decision" } }

/**
 * Tolerant `decision_prompt` parse: unknown phases yield null (ignored, the
 * banner keeps its current state); extra fields are ignored; `options` is
 * null-safe (absent/empty/non-list all mean no buttons, never a crash).
 */
fun parseDecisionPrompt(root: Map<*, *>): DecisionPrompt? {
    val inner = root["decision"] as? Map<*, *> ?: return null
    val phase = ((inner["phase"] as? String) ?: "").lowercase()
    if (phase != "open" && phase != "closed") return null
    val options = (inner["options"] as? List<*>)
        ?.mapNotNull { it as? String }
        ?.filter { it.isNotBlank() }
        ?: emptyList()
    return DecisionPrompt(
        phase = phase,
        promptKind = inner["promptKind"] as? String ?: "",
        title = inner["title"] as? String ?: "",
        at = inner["at"] as? String ?: "",
        options = options,
    )
}

// ------------------------------------------------------- v2 attachments

/** One attachment, from `attachment_ready` or an upload response. */
data class Attachment(
    val id: String = "",
    val name: String = "",
    val bytes: Long = 0L,
    val path: String = "",
    /** Host-side transcript of the audio, when the relay ran one; null when absent. */
    val transcript: String? = null,
)

private fun anyAsLong(v: Any?): Long? = when (v) {
    is Long -> v
    is Number -> v.toLong()
    is String -> v.toLongOrNull()
    else -> null
}

/** Tolerant attachment parse; byte counts may arrive as strings. */
fun parseAttachment(root: Map<*, *>): Attachment? {
    val id = (root["id"] as? String ?: return null).ifBlank { return null }
    return Attachment(
        id = id,
        name = root["name"] as? String ?: "",
        bytes = anyAsLong(root["bytes"]) ?: anyAsLong(root["size"]) ?: 0L,
        path = root["path"] as? String ?: "",
        transcript = (root["transcript"] as? String)?.trim()?.takeIf { it.isNotBlank() },
    )
}

// ------------------------------------------------------- v2 outbound framing

internal fun jsonEscape(s: String): String = buildString {
    for (c in s) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
}

/** `send_message` command frame. */
fun sendMessageFrame(text: String, attachmentIds: List<String>, clientMsgId: String): String {
    val ids = attachmentIds.joinToString(",") { "\"${jsonEscape(it)}\"" }
    return "{\"kind\":\"send_message\",\"text\":\"${jsonEscape(text)}\"," +
        "\"attachmentIds\":[$ids],\"clientMsgId\":\"${jsonEscape(clientMsgId)}\"}"
}

/** `tool_decision` command frame; values pass through for the server to judge. */
fun toolDecisionFrame(callId: String, decision: String, remember: String): String =
    "{\"kind\":\"tool_decision\",\"callId\":\"${jsonEscape(callId)}\"," +
        "\"decision\":\"${jsonEscape(decision)}\",\"remember\":\"${jsonEscape(remember)}\"}"

/**
 * Terminal-scoped stream selector: `/stream?terminal=<id>` names the terminal whose
 * Pi session this socket must serve. The relay answers with that terminal's own
 * transcript; blank keeps the legacy global (boot-session) behavior.
 */
fun withTerminalQuery(url: String, terminalId: String): String {
    if (terminalId.isBlank()) return url
    val sep = if (url.contains("?")) "&" else "?"
    val encoded = try {
        java.net.URLEncoder.encode(terminalId, "UTF-8")
    } catch (_: Exception) {
        terminalId
    }
    return url + sep + "terminal=" + encoded
}

/** `ws(s)://host:port/<path>` → `http(s)://host:port/upload`, query stripped. */
fun uploadUrlFor(relayUrl: String): String {
    var t = relayUrl.trim()
    t = when {
        t.startsWith("ws://", ignoreCase = true) -> "http://" + t.substring(5)
        t.startsWith("wss://", ignoreCase = true) -> "https://" + t.substring(6)
        else -> t
    }
    val afterScheme = t.indexOf("://") + 3
    val pathAt = t.indexOf('/', afterScheme)
    val queryAt = t.indexOf('?', afterScheme)
    val end = listOf(pathAt, queryAt).filter { it >= 0 }.minOrNull() ?: t.length
    return t.substring(0, end) + "/upload"
}

/** Upload keeps the terminal selector so the attachment lands in that session's chat. */
fun uploadUrlFor(relayUrl: String, terminalId: String): String =
    withTerminalQuery(uploadUrlFor(relayUrl), terminalId)
