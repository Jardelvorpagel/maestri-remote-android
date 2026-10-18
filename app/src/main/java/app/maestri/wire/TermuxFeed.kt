package app.maestri.wire

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Termux-backed live terminal engine (todo #61).
 *
 * The host speaks raw PTY bytes in binary WS frames (see [TerminalStreamSocket]); this
 * engine feeds those bytes — verbatim, no UTF-8 round-trip — into a real Termux
 * [TerminalEmulator] and bumps [revision] so the Compose viewport redraws. There is NO
 * local PTY: upstream [TerminalSession] is used as a never-spawned dummy (its `write()`
 * is a no-op while `mShellPid <= 0`, so every view input path is NPE-safe) purely to
 * satisfy `TerminalView.attach`-shaped binding, and the view subclass overrides
 * `updateSize()` so no call path ever reaches JNI (libtermux.so is never loaded).
 *
 * The [MiniTerm] path in [TerminalStreamSocket] is untouched: `screenText` / `cursor` /
 * `geometry` keep flowing exactly as before (tablet + Preview + HUD byte-identical).
 * What the Termux engine adds is the REAL grid: ANSI colors, block cursor, alt-screen,
 * and — via [TRANSCRIPT_ROWS] — genuine in-session scrollback. Anything scrolled off
 * before Live opened is still wire backlog (needs a host history route, not faked).
 *
 * Threading: the socket calls [reset]/[feed]/[setCells]/[clear] on the OkHttp thread;
 * every emulator mutation is marshalled through one main-thread [Handler], so mutation
 * and `onDraw` never race and frame order is preserved.
 */
class TermuxEngine {
    companion object {
        const val TRANSCRIPT_ROWS = 1000
        private const val TAG = "TermuxEngine"
    }

    private val main = Handler(Looper.getMainLooper())

    /** Bumped on the main thread after every feed/reset/cells/clear. Thread-safe to read. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    /**
     * The live emulator. Written on the main thread only; the Compose side reads it on
     * the main thread (recomposed via [revision]), so no lock is needed.
     */
    @Volatile
    var emulator: TerminalEmulator? = null
        private set

    /** Never-spawned dummy session: stable identity for the view binding. */
    val session: TerminalSession = TerminalSession("remote", "/", emptyArray(), emptyArray(), TRANSCRIPT_ROWS, Client())

    /** Wired by the screen (has a Context): long-press selection copy lands here. */
    var onCopyText: ((String) -> Unit)? = null

    private var cellW = 0
    private var cellH = 0

    /** Geometry frame: fresh emulator at the host grid (mirrors the MiniTerm reset). */
    fun reset(cols: Int, rows: Int) {
        main.post {
            val g = Geometry.clamp(cols, rows)
            emulator = TerminalEmulator(session, g.cols, g.rows, cellW, cellH, TRANSCRIPT_ROWS, Client())
                .also { applyTokyoNight(it) }
            _revision.value++
        }
    }

    /** Binary PTY frame: verbatim bytes into the emulator. */
    fun feed(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        main.post {
            emulator?.append(bytes, bytes.size)
            _revision.value++
        }
    }

    /** Measured mono cells from the Compose side (L3 TextMeasurer numbers). */
    fun setCells(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        main.post {
            cellW = w
            cellH = h
            emulator?.let {
                it.resize(it.mColumns, it.mRows, w, h)
                _revision.value++
            }
        }
    }

    fun clear() {
        main.post {
            emulator = null
            _revision.value++
        }
    }

    /**
     * Tokyo Night on the 16 ANSI slots + FG/BG/cursor. The renderer reads
     * `mColors.mCurrentColors` live every frame, so no notification is needed; a later
     * host OSC 10/11/12 wins, as it should.
     */
    private fun applyTokyoNight(em: TerminalEmulator) {
        val c = em.mColors
        c.reset()
        val slots = intArrayOf(
            0xFF1A1B26.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
            0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFA9B1D6.toInt(),
            0xFF565F89.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
            0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFC0CAF5.toInt(),
        )
        for (i in 0..15) c.mCurrentColors[i] = slots[i]
        c.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = 0xFFA9B1D6.toInt()
        c.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = 0xFF1A1B26.toInt()
        c.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = 0xFFC0CAF5.toInt()
    }

    private inner class Client : TerminalSessionClient {
        override fun onTextChanged(@NonNull changedSession: TerminalSession) = Unit
        override fun onTitleChanged(@NonNull changedSession: TerminalSession) = Unit
        override fun onSessionFinished(@NonNull finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(@NonNull session: TerminalSession, text: String) {
            onCopyText?.invoke(text)
        }
        override fun onPasteTextFromClipboard(@Nullable session: TerminalSession?) = Unit
        override fun onBell(@NonNull session: TerminalSession) = Unit
        override fun onColorsChanged(@NonNull session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun setTerminalShellPid(@NonNull session: TerminalSession, pid: Int) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) { Log.e(TAG, "$tag: $message") }
        override fun logWarn(tag: String, message: String) { Log.w(TAG, "$tag: $message") }
        override fun logInfo(tag: String, message: String) { Log.i(TAG, "$tag: $message") }
        override fun logDebug(tag: String, message: String) { Log.d(TAG, "$tag: $message") }
        override fun logVerbose(tag: String, message: String) { Log.v(TAG, "$tag: $message") }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(TAG, "$tag: $message", e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(TAG, tag, e) }
    }
}
