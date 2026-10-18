package app.maestri.ui.screens

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Termux view binding for the REMOTE stream (todo #61).
 *
 * Upstream `TerminalView` is FINAL at this revision, so this is composition, not
 * inheritance: a `FrameLayout` holding the inner view and delegating to it. The inner
 * view's `mTermSession` stays NULL by design — `updateSize()` early-returns on a null
 * session (vendored `TerminalView.java:988`), so view-geometry changes (keyboard toggle,
 * rotation) can never reach the JNI `session.updateSize()` path. (The engine still owns
 * a never-spawned dummy session, but purely as the emulator's `TerminalOutput` carrier —
 * it is never bound to any view, never sized, never spawns.) The live grid is assigned to
 * the view's PUBLIC `mEmulator` field directly; local emulator geometry always tracks
 * HOST geometry frames (the engine recreates the emulator per frame), never view dims.
 *
 * Null-session audit (all `mTermSession` sites at @8629e63): key/IME dispatch is dead
 * (inner view is `focusable=false`, our field owns the IME); `inputCodePoint` null-guards
 * itself; `getCurrentSession()` has no internal callers; the `:1090` write sits in the
 * AutoFill path (unreachable: non-focusable view, no autofill hints); mouse events only
 * touch emulator state (inert with no PTY writer). The ONE reachable crash — finger
 * scroll while the remote alt screen is up (`doScroll` → `handleKeyCode` → unguarded
 * `getEmulator()`) — is converted into the path's original intent: while alt is active
 * the wrapper eats the gesture and emits host arrow keys, so `less`/vim scroll natively.
 * Taps and mouse reports in alt/mouse apps are inert remote-side, so eating them loses
 * nothing. Pinch-zoom stays a no-op (Aa owns sizing).
 *
 * Row-integral `mTopRow` makes the L3 top-clip defect structurally impossible; the
 * governor calls `onScreenUpdated(away)` — follow at bottom, hold position while away.
 * Plain-drag scrolls run natively inside the view; every finger lift re-syncs the pill
 * (immediate + 350 ms fling-settle posts) and every engine revision re-syncs too.
 */
class RemoteTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    val termView: TerminalView = TerminalView(context, null)

    /** Fires on the main thread after finger-scroll settle + every revision sync. */
    var onTopRow: ((Int) -> Unit)? = null

    /** Alt-screen finger scrolls become host arrow keys (stock `doScroll` intent). */
    var onAltScroll: ((up: Boolean) -> Unit)? = null

    /** Mono line height px — the alt-scroll row threshold (set from Compose metrics). */
    var rowPx = 1f

    init {
        addView(termView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        termView.isFocusable = false
        termView.isFocusableInTouchMode = false
        termView.setOnTouchListener { _, e -> onTouch(e) }
    }

    /** Live emulator bound to the inner view. */
    val bound: TerminalEmulator? get() = termView.mEmulator

    fun bindRemote(emulator: TerminalEmulator) {
        if (termView.mEmulator !== emulator) {
            termView.mEmulator = emulator
            termView.setTopRow(0, false)
            termView.setTerminalCursorBlinkerState(true, true)
        }
    }

    fun setTextSize(sp: Int) = termView.setTextSize(sp)

    /** Correct accessor at this revision is `TerminalView.setTypeface` (after text size). */
    fun setTerminalTypeface(tf: Typeface) = termView.setTypeface(tf)

    fun setTerminalViewClient(c: TerminalViewClient) = termView.setTerminalViewClient(c)

    fun getTopRow(): Int = termView.getTopRow()

    fun setTopRow(top: Int) = termView.setTopRow(top)

    fun onScreenUpdated(skipScrolling: Boolean) = termView.onScreenUpdated(skipScrolling)

    fun setTerminalCursorBlinkerState(start: Boolean, onlyIfCursor: Boolean) =
        termView.setTerminalCursorBlinkerState(start, onlyIfCursor)

    /** Refresh cell pixels (Aa size change); same-grid resize is allocation-free. */
    fun refreshMetrics(cellW: Int, cellH: Int) {
        val em = termView.mEmulator ?: return
        if (cellW > 0 && cellH > 0) em.resize(em.mColumns, em.mRows, cellW, cellH)
    }

    fun syncTopRow() {
        onTopRow?.invoke(termView.getTopRow())
    }

    private fun altEating(): Boolean {
        val em = termView.mEmulator
        return em != null && em.isAlternateBufferActive() && !em.isMouseTrackingActive()
    }

    private var lastY = 0f
    private var accY = 0f

    private fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = e.y
                accY = 0f
                return altEating()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!altEating()) return false
                accY += e.y - lastY
                lastY = e.y
                val step = rowPx.coerceAtLeast(1f)
                while (accY <= -step) {
                    onAltScroll?.invoke(true)
                    accY += step
                }
                while (accY >= step) {
                    onAltScroll?.invoke(false)
                    accY -= step
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                post { syncTopRow() }
                postDelayed({ syncTopRow() }, 350)
                return altEating()
            }
            else -> return false
        }
    }
}

/** The Compose side's snapshot of the engine: stable session, grid identity, redraw clock. */
data class TermuxHandle(val session: TerminalSession, val emulator: TerminalEmulator?, val revision: Long)

/**
 * `TerminalViewClient` for the remote stream. Deliberate deviations from a stock
 * Termux activity: the view never owns the IME (`isTerminalViewSelected` false — our
 * `OutlinedTextField` does), pinch-zoom is a no-op (Aa owns sizing), and all input
 * callbacks return false (quickkeys + composer own input; the view generates none
 * while unfocused, and alt-screen scrolls arrive via `onAltScroll` instead).
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
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(TAG, "$tag: $message", e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(TAG, tag, e) }

    private companion object {
        const val TAG = "TermuxView"
    }
}
