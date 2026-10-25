package app.maestri.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.maestri.ui.HostRequests
import kotlinx.coroutines.delay

/**
 * One memo per composer: the mic toggle, the elapsed label, the 250 ms tick, the 5 minute cap,
 * the permission round-trip and the release on dispose. Hosts only stage what it hands back.
 */
@Stable
class VoiceNoteHandle internal constructor(
    private val context: Context,
    private val onClip: (name: String, bytes: ByteArray) -> Unit,
    private val onNotice: (String) -> Unit,
) {
    private val recorder = VoiceNoteRecorder()
    private var active by mutableStateOf(false)
    private var elapsed by mutableStateOf(0L)
    // A permission dialog can outlive the screen; a recorder armed after that has no owner.
    private var alive = true

    val recording: Boolean get() = active
    val elapsedMs: Long get() = elapsed

    fun toggle() {
        if (active) {
            finish()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            start()
            return
        }
        val host = context as? HostRequests
        if (host == null) {
            onNotice("The microphone is not available on this screen.")
            return
        }
        host.requestRecordAudioPermission { granted ->
            if (granted) start() else onNotice("Microphone permission denied — voice notes need it.")
        }
    }

    /** One tick: repaints the label and answers true when the take ended. */
    fun tick(): Boolean {
        if (!active) return true
        elapsed = recorder.elapsedMs()
        if (elapsed < VoiceNoteRecorder.MAX_MS) return false
        finish()
        return true
    }

    fun release() {
        alive = false
        recorder.release()
    }

    private fun start() {
        if (!alive) return
        if (!recorder.start(context.applicationContext)) {
            onNotice("Couldn't start recording — the microphone is unavailable.")
            return
        }
        elapsed = 0L
        active = true
    }

    private fun finish() {
        if (!active) return
        active = false
        elapsed = 0L
        val clip = recorder.stop()
        if (clip == null) {
            onNotice("Recording failed — nothing captured.")
            return
        }
        onClip(clip.first, clip.second)
    }
}

@Composable
fun rememberVoiceNote(
    onClip: (name: String, bytes: ByteArray) -> Unit,
    onNotice: (String) -> Unit,
): VoiceNoteHandle {
    val context = LocalContext.current
    val clip = rememberUpdatedState(onClip)
    val notice = rememberUpdatedState(onNotice)
    val handle = remember {
        VoiceNoteHandle(
            context = context,
            onClip = { name, bytes -> clip.value(name, bytes) },
            onNotice = { notice.value(it) },
        )
    }
    DisposableEffect(handle) {
        onDispose { handle.release() }
    }
    val recording = handle.recording
    LaunchedEffect(handle, recording) {
        if (!recording) return@LaunchedEffect
        while (true) {
            delay(250)
            if (handle.tick()) break
        }
    }
    return handle
}
