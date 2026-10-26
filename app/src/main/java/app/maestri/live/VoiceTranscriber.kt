package app.maestri.live

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Contract marker for a transcript folded into the message text. */
const val TRANSCRIPT_MARKER_OPEN = "[transcript: "
const val TRANSCRIPT_MARKER_CLOSE = "]"

/** ASR ceiling: a memo that takes longer than this is sent unlabelled instead of stalled. */
const val TRANSCRIBE_TIMEOUT_MS = 20_000L

/**
 * Message text for a memo: the caption, then the transcript in the single agreed marker.
 * A blank/missing transcript leaves the caption exactly as the user typed it, and a blank
 * caption with a transcript is the marker alone.
 */
fun composeMemoText(caption: String, transcript: String?, separator: String = "\n\n"): String {
    val heard = transcript?.trim().orEmpty()
    if (heard.isEmpty()) return caption
    val marker = TRANSCRIPT_MARKER_OPEN + heard + TRANSCRIPT_MARKER_CLOSE
    if (caption.isBlank()) return marker
    return caption.trimEnd() + separator + marker
}

/**
 * Transcript preference: the on-device take when ASR answered, the host's /upload field
 * otherwise. Blank counts as absent on both sides, so a memo never carries an empty marker.
 */
fun resolveTranscript(onDevice: String?, host: String?): String? =
    onDevice?.trim()?.takeIf { it.isNotBlank() } ?: host?.trim()?.takeIf { it.isNotBlank() }

/**
 * On-device transcription of a recorded m4a: decode to 16-bit PCM with the platform
 * decoder, write a temp WAV and hand it to the on-device recognition service through
 * EXTRA_AUDIO_SOURCE so the memo is never re-recorded from the microphone.
 *
 * Returns null — never throws — whenever the device cannot do it: API < 33, no on-device
 * recognizer, decode failure, timeout, empty result. Callers then fall back to the host's
 * `transcript` field from POST /upload, or to no transcript at all.
 */
suspend fun transcribeMemo(
    context: Context,
    bytes: ByteArray,
    timeoutMs: Long = TRANSCRIBE_TIMEOUT_MS,
): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    if (bytes.isEmpty()) return null
    val app = context.applicationContext
    val pcm = runCatching { withContext(Dispatchers.Default) { decodePcm(app, bytes) } }.getOrNull()
        ?: return null
    if (pcm.bytes.isEmpty()) return null
    val heard = runCatching { recognizePcm(app, pcm, timeoutMs) }.getOrNull() ?: return null
    return heard.trim().takeIf { it.isNotBlank() }
}

// ----------------------------------------------------------------- decode

private class Pcm(val bytes: ByteArray, val sampleRate: Int, val channels: Int)

private const val WAV_HEADER_BYTES = 44

private fun decodePcm(context: Context, bytes: ByteArray): Pcm? {
    val dir = File(context.cacheDir, "asr").apply { mkdirs() }
    val source = File(dir, "memo-" + UUID.randomUUID() + ".m4a")
    return try {
        source.writeBytes(bytes)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            drainDecoder(codec, extractor)
        } finally {
            codec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            extractor.release()
        }
    } finally {
        source.delete()
    }
}

/** Feeds the extractor's samples through the decoder and returns the raw PCM payload. */
private fun drainDecoder(codec: MediaCodec, extractor: MediaExtractor): Pcm? {
    val out = ByteArrayOutputStream()
    var sampleRate = 0
    var channels = 0
    val info = MediaCodec.BufferInfo()
    var sawInputEos = false
    var sawOutputEos = false
    while (!sawOutputEos) {
        if (!sawInputEos) {
            val inIndex = codec.dequeueInputBuffer(10_000L)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex) ?: return null
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    sawInputEos = true
                } else {
                    codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        when (val outIndex = codec.dequeueOutputBuffer(info, 10_000L)) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val format = codec.outputFormat
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            }
            else -> if (outIndex >= 0) {
                codec.getOutputBuffer(outIndex)?.let { buffer ->
                    if (info.size > 0) {
                        val chunk = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(chunk)
                        out.write(chunk)
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
            }
        }
    }
    val pcm = out.toByteArray()
    if (pcm.isEmpty() || sampleRate <= 0) return null
    return Pcm(pcm, sampleRate, channels.coerceAtLeast(1))
}

/** Minimal 16-bit PCM WAV: header plus the decoded payload, so the recognizer can seek it. */
private fun wavFile(pcm: Pcm, target: File): File {
    val dataSize = pcm.bytes.size
    val header = ByteArrayOutputStream(WAV_HEADER_BYTES).apply {
        fun le32(value: Int) {
            write(value and 0xFF)
            write((value shr 8) and 0xFF)
            write((value shr 16) and 0xFF)
            write((value shr 24) and 0xFF)
        }
        fun le16(value: Int) {
            write(value and 0xFF)
            write((value shr 8) and 0xFF)
        }
        write("RIFF".toByteArray())
        le32(36 + dataSize)
        write("WAVE".toByteArray())
        write("fmt ".toByteArray())
        le32(16)
        le16(1)
        le16(pcm.channels)
        le32(pcm.sampleRate)
        le32(pcm.sampleRate * pcm.channels * 2)
        le16(pcm.channels * 2)
        le16(16)
        write("data".toByteArray())
        le32(dataSize)
    }
    target.writeBytes(header.toByteArray() + pcm.bytes)
    return target
}

// ----------------------------------------------------------------- recognize

/**
 * Runs the on-device recognizer over the decoded audio. SpeechRecognizer is main-thread
 * bound, so the whole exchange (create, listen, await, destroy) stays on Main; the timeout
 * cancels the listen and a failure on any step yields null instead of hanging the caller.
 */
private suspend fun recognizePcm(context: Context, pcm: Pcm, timeoutMs: Long): String? {
    val onDevice = runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
        .getOrDefault(false)
    if (!onDevice) return null
    return withContext(Dispatchers.Main) {
        val recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            ?: return@withContext null
        val wav = File(context.cacheDir, "asr/voice-" + UUID.randomUUID() + ".wav")
        var descriptor: ParcelFileDescriptor? = null
        try {
            wavFile(pcm, wav)
            descriptor = ParcelFileDescriptor.open(wav, ParcelFileDescriptor.MODE_READ_ONLY)
            val heard = CompletableDeferred<String?>()
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val first = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull { it.isNotBlank() }
                    heard.complete(first)
                }

                override fun onError(error: Int) {
                    heard.complete(null)
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val started = runCatching { recognizer.startListening(recognizerIntent(descriptor, pcm)) }
            if (started.isFailure) return@withContext null
            val result = withTimeoutOrNull(timeoutMs) { heard.await() }
            if (result == null) runCatching { recognizer.cancel() }
            result
        } finally {
            runCatching { descriptor?.close() }
            runCatching { recognizer.destroy() }
            wav.delete()
        }
    }
}

private fun recognizerIntent(
    descriptor: ParcelFileDescriptor,
    pcm: Pcm,
): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptor)
    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, pcm.channels)
    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pcm.sampleRate)
    val locale = Locale.getDefault()
    if (locale.language.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
}
