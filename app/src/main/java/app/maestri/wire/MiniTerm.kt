package app.maestri.wire

/**
 * Minimal ANSI/VT terminal emulator — enough to read what an agent is doing in the
 * terminal stream ({@code WS /api/terminals/{id}/stream}) without a full xterm engine.
 *
 * Handles: printable chars & wrapping, CR/LF/BEL/BS/TAB, CSI cursor moves (H/f/A/B/C/D/G),
 * erase (K for line, J for display), reverse index (ESC M), OSC/DCS/APC swallow, SGR no-op.
 * Colors and exotic modes are consumed but not rendered — an honest degrade path, per
 * TASK.md ("degrade gracefully ... if no emulator lib fits").
 *
 * On every {"type":"geometry"} frame the app resets the emulator (per contract) and the
 * next binary frame carries a fresh full-screen dump.
 */
class MiniTerm(cols: Int, rows: Int) {

    private var cols: Int = 0
    private var rows: Int = 0
    private var grid: CharArray = CharArray(0)
    private var cx = 0
    private var cy = 0

    /** Bumped on every feed; UI observes to re-render. */
    var revision: Long = 0
        private set

    val geometry: Geometry
        get() = Geometry(cols, rows)

    init {
        reset(Geometry.clamp(cols, rows))
    }

    fun reset(g: Geometry) {
        val clamped = Geometry.clamp(g.cols, g.rows)
        cols = clamped.cols
        rows = clamped.rows
        grid = CharArray(cols * rows) { ' ' }
        cx = 0
        cy = 0
        bump()
    }

    fun cursor(): Pair<Int, Int> = cx to cy

    private fun bump() {
        revision++
    }

    private fun line(r: Int): Int = r * cols

    private fun scrollUp() {
        System.arraycopy(grid, cols, grid, 0, cols * (rows - 1))
        for (i in (cols * (rows - 1)) until cols * rows) grid[i] = ' '
    }

    private fun scrollDown() {
        System.arraycopy(grid, 0, grid, cols, cols * (rows - 1))
        for (i in 0 until cols) grid[i] = ' '
    }

    private fun newline() {
        if (cy >= rows - 1) {
            scrollUp()
            cy = rows - 1
        } else {
            cy++
        }
    }

    private fun put(ch: Char) {
        grid[line(cy) + cx] = ch
        cx++
        if (cx >= cols) {
            cx = 0
            newline()
        }
    }

    /** Ingest decoded PTY bytes. Tolerates any garbage. */
    fun feed(text: String) {
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when (c.code) {
                0x07 -> { /* BEL */ i++ }
                0x7F -> i++ // DEL
                0x08 -> { if (cx > 0) cx--; i++ }
                0x09 -> { // TAB → next multiple of 8
                    cx = ((cx / 8) + 1) * 8
                    if (cx >= cols) cx = cols - 1
                    i++
                }
                0x0A, 0x0B, 0x0C -> { newline(); cx = 0; i++ }
                0x0D -> { cx = 0; i++ }
                0x1B -> i = escape(text, i)
                else -> {
                    if (c.code < 0x20) {
                        // other C0 controls: ignore
                        i++
                    } else {
                        put(c)
                        i++
                    }
                }
            }
        }
        bump()
    }

    /** Returns the new index after consuming the whole escape at {@code text[i] == ESC}. */
    private fun escape(text: String, start: Int): Int {
        val n = text.length
        var i = start + 1
        if (i >= n) return n
        val c = text[i]
        when {
            c == '[' -> { // CSI
                var j = ++i
                while (j < n) {
                    val cc = text[j].code
                    if (cc in 0x30..0x3F || cc in 0x20..0x2F) j++
                    else break
                }
                if (j >= n) return n
                val final = text[j]
                val paramsRaw = text.substring(i, j)
                return csi(paramsRaw, final, j + 1)
            }
            c == ']' -> { // OSC … BEL | ST
                var j = i + 1
                while (j < n) {
                    val cc = text[j]
                    if (cc.code == 0x07) return j + 1
                    if (cc.code == 0x1B && j + 1 < n && text[j + 1] == '\\') return j + 2
                    j++
                }
                return n
            }
            // ESC P (DCS), ESC X (SOS), ESC ^ (PM), ESC _ (APC): swallow till ST
            (c.code == 'P'.code) || (c.code == 'X'.code) || (c.code == '^'.code) || (c.code == '_'.code) -> {
                var j = i + 1
                while (j < n) {
                    if (text[j].code == 0x1B && j + 1 < n && text[j + 1] == '\\') return j + 2
                    j++
                }
                return n
            }
            c.code == 'M'.code -> { // reverse index
                if (cy == 0) scrollDown() else cy--
                return i + 1
            }
            else -> return i + 1 // unknown two-char escape: consume
        }
    }

    private fun csi(params: String, final: Char, next: Int): Int {
        val priv = params.startsWith("?")
        val nums = params.replace("?", "").split(';').map {
            it.toIntOrNull() ?: 0
        }
        fun p(index: Int, def: Int): Int {
            val z = nums.getOrNull(index) ?: return def
            return if (z == 0) def else z
        }
        if (priv) return next // private modes (e.g. ?25l cursor hide): ignore
        when (final) {
            'A' -> cy = (cy - p(0, 1)).coerceAtLeast(0)
            'B', 'e' -> cy = (cy + p(0, 1)).coerceAtMost(rows - 1)
            'C', 'a' -> cx = (cx + p(0, 1)).coerceAtMost(cols - 1)
            'D' -> cx = (cx - p(0, 1)).coerceAtLeast(0)
            'E' -> { cy = (cy + p(0, 1)).coerceAtMost(rows - 1); cx = 0 }
            'F' -> { cy = (cy - p(0, 1)).coerceAtLeast(0); cx = 0 }
            'G', '`' -> cx = (p(0, 1) - 1).coerceAtLeast(0).coerceAtMost(cols - 1)
            'H', 'f' -> {
                cy = (p(0, 1) - 1).coerceAtLeast(0).coerceAtMost(rows - 1)
                cx = (p(1, 1) - 1).coerceAtLeast(0).coerceAtMost(cols - 1)
            }
            'd' -> cy = (p(0, 1) - 1).coerceAtLeast(0).coerceAtMost(rows - 1)
            'J' -> {
                when (nums.getOrNull(0) ?: 0) {
                    0 -> { // cursor → EOL
                        grid[line(cy) + cx] = ' '
                        for (k in line(cy) + cx + 1 until line(cy) + cols) grid[k] = ' '
                        for (r in cy + 1 until rows)
                            for (k in line(r) until line(r) + cols) grid[k] = ' '
                    }
                    1 -> { // SOL → cursor
                        for (k in line(cy) until line(cy) + cx + 1) grid[k] = ' '
                        for (r in 0 until cy)
                            for (k in line(r) until line(r) + cols) grid[k] = ' '
                    }
                    else -> { // 2 (and 3: wipe scrollback too)
                        for (k in grid.indices) grid[k] = ' '
                    }
                }
            }
            'K' -> {
                when (nums.getOrNull(0) ?: 0) {
                    1 -> for (k in line(cy) until line(cy) + cx + 1) grid[k] = ' '
                    2 -> for (k in line(cy) until line(cy) + cols) grid[k] = ' '
                    else -> for (k in line(cy) + cx until line(cy) + cols) grid[k] = ' '
                }
            }
            'm' -> Unit // SGR: consumed, not rendered (plain-text rendering)
            'r' -> Unit // DECSTBM margins: ignored (whole-screen region)
            else -> Unit // ignore everything else (h/l/n/c/s/u/t…)
        }
        return next
    }

    /** Screen as plain text: rows joined by \n, trailing spaces trimmed per row. */
    fun render(): String {
        val sb = StringBuilder(rows * (cols + 1))
        for (r in 0 until rows) {
            val st = line(r)
            var end = st + cols
            while (end > st && grid[end - 1] == ' ') end--
            sb.append(grid, st, end - st)
            if (r != rows - 1) sb.append('\n')
        }
        return sb.toString()
    }
}