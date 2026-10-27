package app.maestri.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "MaestriJourney"
private const val MAX_BYTES = 256L * 1024
private const val KEEP_LINES = 400
private const val DETAIL_MAX = 160

internal fun journeyLine(ts: Long, area: String, action: String, outcome: String, detail: String?): String =
    buildString {
        append(Instant.ofEpochMilli(ts).toString())
        append(' ').append(journeyField(area))
        append(' ').append(journeyField(action))
        append(' ').append(journeyField(outcome))
        val tail = detail?.let { journeyField(it) }.orEmpty()
        if (tail.isNotEmpty()) append(' ').append(tail)
    }

private fun journeyField(value: String): String =
    value.replace(Regex("\\p{Cntrl}+"), " ").trim().take(DETAIL_MAX)

internal fun journeyTail(lines: List<String>, keep: Int): List<String> =
    if (keep <= 0) emptyList() else lines.takeLast(keep)

/**
 * Bounded journey log: one line per event, appended to [file] and mirrored to the [sink],
 * rotated down to its newest [keepLines] once it passes [maxBytes]. Appends run on one
 * background thread so a caller on the UI thread only pays for formatting, and every
 * failure is swallowed — tracking never breaks or blocks the user flow.
 */
class JourneyLog(
    private val file: File,
    private val maxBytes: Long = MAX_BYTES,
    private val keepLines: Int = KEEP_LINES,
    private val now: () -> Long = System::currentTimeMillis,
    private val sink: (String) -> Unit = {},
) {
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "maestri-journey").apply { isDaemon = true }
    }

    fun record(area: String, action: String, outcome: String, detail: String? = null) {
        runCatching {
            val line = journeyLine(now(), area, action, outcome, detail)
            sink(line)
            writer.execute { runCatching { append(line) } }
        }
    }

    /** The newest [keepLines] events as text — queued appends are drained first, briefly. */
    fun read(): String = runCatching {
        runCatching { writer.submit(Runnable { }).get(2, TimeUnit.SECONDS) }
        file.readLines().takeLast(keepLines).joinToString("\n")
    }.getOrDefault("")

    private fun append(line: String) {
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
        if (file.length() > maxBytes) rotate()
    }

    private fun rotate() {
        val kept = journeyTail(file.readLines(), keepLines).toMutableList()
        while (kept.size > 1 && kept.sumOf { it.length.toLong() + 1 } > maxBytes) kept.removeAt(0)
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }
}

/**
 * The app's single journey entry point: what the user did, in which area, with what
 * outcome. Ids and enum-like outcomes only — never tokens, terminal/chat or note content,
 * and never a raw server response.
 */
object Journey {
    private var log: JourneyLog? = null

    fun install(context: Context) {
        if (log != null) return
        runCatching {
            log = JourneyLog(File(context.filesDir, "journey.log"), sink = { Log.i(TAG, it) })
        }
    }

    fun update(action: String, outcome: String, detail: String? = null) =
        event("update", action, outcome, detail)

    fun feed(action: String, outcome: String, detail: String? = null) =
        event("feed", action, outcome, detail)

    fun event(area: String, action: String, outcome: String, detail: String? = null) {
        runCatching { log?.record(area, action, outcome, detail) }
    }

    fun export(): String = runCatching { log?.read() ?: "" }.getOrDefault("")
}
