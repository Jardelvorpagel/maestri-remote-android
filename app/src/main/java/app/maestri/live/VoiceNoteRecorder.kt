package app.maestri.live

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Records one voice memo with the platform encoder and hands the bytes back in memory, so the
 * memo rides the same staged-file upload as any other attachment.
 */
class VoiceNoteRecorder {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun elapsedMs(): Long = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedAt

    /** Starts a mono AAC memo in a cacheDir temp file; false means the mic was never armed. */
    fun start(context: Context): Boolean {
        release()
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val target = File(dir, voiceFileName(System.currentTimeMillis()))
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        val armed = runCatching {
            builder.setAudioSource(MediaRecorder.AudioSource.MIC)
            builder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            builder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            builder.setAudioChannels(1)
            builder.setAudioSamplingRate(44_100)
            builder.setAudioEncodingBitRate(64_000)
            builder.setMaxDuration(MAX_MS.toInt())
            builder.setOutputFile(target.absolutePath)
            builder.prepare()
            builder.start()
        }.isSuccess
        if (!armed) {
            runCatching { builder.release() }
            target.delete()
            return false
        }
        recorder = builder
        file = target
        startedAt = SystemClock.elapsedRealtime()
        return true
    }

    /** Finalizes the memo; null when nothing usable was captured. */
    fun stop(): Pair<String, ByteArray>? {
        val active = recorder ?: return null
        val target = file
        recorder = null
        file = null
        // The encoder stops itself at MAX_MS, and a second stop() throws — either way the
        // file on disk is the memo.
        runCatching { active.stop() }
        runCatching { active.release() }
        if (target == null) return null
        val bytes = runCatching { target.readBytes() }.getOrNull()
        target.delete()
        if (bytes == null || bytes.isEmpty()) return null
        return target.name to bytes
    }

    fun cancel() {
        val active = recorder
        recorder = null
        if (active != null) {
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        file?.delete()
        file = null
    }

    fun release() = cancel()

    companion object {
        const val MAX_MS = 5L * 60 * 1000
    }
}

internal fun voiceFileName(nowMs: Long): String =
    "voice-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs)) + ".m4a"

/** The one shape of a staged memo, shared by both composer faces. */
fun voiceNoteStagedFile(name: String, bytes: ByteArray): LiveStagedFile = LiveStagedFile(
    key = UUID.randomUUID().toString(),
    name = name,
    mime = "audio/mp4",
    sizeBytes = bytes.size.toLong(),
    bytes = bytes,
)
