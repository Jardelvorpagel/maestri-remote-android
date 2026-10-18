package app.maestri.ui.screens

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Termux view binding for the REMOTE stream (todo #61).
 *
 * Upstream `TerminalView` is hard-wired to `TerminalSession.updateSize()`, which spawns
 * a local PTY through JNI. Maestri has no local PTY — the bytes arrive from the host —
 * so this subclass binds the view WITHOUT `attachSession`:
 *
 * - [bindRemote] assigns the view's public `mTermSession` (the engine's never-spawned
 *   dummy, whose `write()` is a no-op while `mShellPid <= 0`, so every key/paste/mouse
 *   path inside the view is NPE-safe) and `mEmulator` directly. Rebinding the SAME
 *   emulator is a no-op; a fresh emulator (host geometry frame) resets the top row and
 *   restarts the cursor blinker against it.
 * - [updateSize] is overridden so view-geometry changes (keyboard toggle, rotation)
 *   NEVER reach `session.updateSize()` → JNI. Local emulator geometry always tracks
 *   HOST geometry frames (the engine recreates the emulator per frame); view dims only
 *   decide which window of rows is visible, exactly like the MiniTerm path.
 * - [setTopRow] funnels every scroll mutation (finger drag, fling, key scroll,
 *   `onScreenUpdated`) into [onTopRow], which drives the Compose scroll-governor pill.
 *
 * Row-integral `mTopRow` makes the L3 top-clip defect structurally impossible; the
 * governor calls `onScreenUpdated(away)` — follow at bottom, hold position while away.
 */
class RemoteTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TerminalView(context, attrs) {

    /** Fires on the main thread after every top-row mutation. */
    var onTopRow: ((Int) -> Unit)? = null

    override fun updateSize() {
        if (width == 0 || height == 0 || mTermSession == null) return
        val em = mEmulator ?: return
        val hist = em.screen.activeTranscriptRows
        if (getTopRow() < -hist) setTopRow(-hist)
        invalidate()
    }

    override fun setTopRow(topRow: Int, updateEmulator: Boolean) {
        super.setTopRow(topRow, updateEmulator)
        onTopRow?.invoke(getTopRow())
    }

    fun bindRemote(session: TerminalSession, emulator: TerminalEmulator) {
        mTermSession = session
        if (mEmulator !== emulator) {
            mEmulator = emulator
            setTopRow(0, false)
            setTerminalCursorBlinkerState(true, true)
        }
    }

    /** Refresh cell pixels (Aa size change); same-grid resize is allocation-free. */
    fun refreshMetrics(cellW: Int, cellH: Int) {
        val em = mEmulator ?: return
        if (cellW > 0 && cellH > 0) em.resize(em.mColumns, em.mRows, cellW, cellH)
    }
}

/** The Compose side's snapshot of the engine: stable session, grid identity, redraw clock. */
data class TermuxHandle(val session: TerminalSession, val emulator: TerminalEmulator?, val revision: Long)

/**
 * `TerminalViewClient` for the remote stream. Deliberate deviations from a stock
 * Termux activity: the view never owns the IME (`isTerminalViewSelected` false — our
 * `OutlinedTextField` does), pinch-zoom is a no-op (Aa owns sizing), and all input
 * callbacks return false (quickkeys + composer own input; the dummy session drops
 * anything the view itself generates).
 */
class TermuxViewClient : TerminalViewClient {
    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: MotionEvent) = Unit
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = false
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String, message: String) { Log.e(TAG, "$tag: $message") }
    override fun logWarn(tag: String, message: String) { Log.w(TAG, "$tag: $message") }
    override fun logInfo(tag: String, message: String) { Log.i(TAG, "$tag: $message") }
    override fun logDebug(tag: String, message: String) { Log.d(TAG, "$tag: $message") }
    override fun logVerbose(tag: String, message: String) { Log.v(TAG, "$tag: $message") }

    private companion object {
        const val TAG = "TermuxView"
    }
}
