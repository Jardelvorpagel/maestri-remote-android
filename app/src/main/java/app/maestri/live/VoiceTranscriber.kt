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
import android.util.Log
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

private const val TAG = "VoiceTranscriber"

/** Retry the on-device take only if the first one failed fast; slow legs get no second chance. */
private const val RETRY_IF_FASTER_THAN_MS = 10_000L

/** What the retry still gets to use, so both attempts stay inside the caller's budget. */
private const val MIN_RETRY_BUDGET_MS = 2_000L

/** Letters/digits a transcript must carry before it is worth showing as words. */
private const val MIN_TRANSCRIPT_WORDS = 2

/**
 * The rate on-device recognizers want. AiAi/SODA-class engines returned an empty hypothesis
 * list twice on 44.1 kHz audio the host transcribed perfectly, and 16 kHz mono is what their
 * acoustic model was trained on.
 */
internal const val RECOGNIZER_SAMPLE_RATE = 16_000

/**
 * The usable transcript, or null. Trimming is not enough: a silent memo makes Whisper
 * answer "." and a one-character blip answers "-", which rendered as `[transcript: .]`
 * on the tablet. So punctuation and whitespace alone count as absent — the memo then
 * sends exactly as it did before transcription existed.
 */
fun usableTranscript(transcript: String?): String? {
    val heard = transcript?.trim().orEmpty()
    if (heard.isEmpty()) return null
    return heard.takeIf { chunk -> chunk.count { it.isLetterOrDigit() } >= MIN_TRANSCRIPT_WORDS }
}

/**
 * Message text for a memo: the caption, then the transcript in the single agreed marker.
 * A blank/missing/junk transcript leaves the caption exactly as the user typed it, and a
 * blank caption with a transcript is the marker alone.
 */
fun composeMemoText(caption: String, transcript: String?, separator: String = "\n\n"): String {
    val heard = usableTranscript(transcript) ?: return caption
    val marker = TRANSCRIPT_MARKER_OPEN + heard + TRANSCRIPT_MARKER_CLOSE
    if (caption.isBlank()) return marker
    return caption.trimEnd() + separator + marker
}

/**
 * Transcript preference: the on-device take when ASR answered with words, the host's
 * /upload field otherwise. Junk counts as absent on both sides, so neither leg's "." wins.
 */
fun resolveTranscript(onDevice: String?, host: String?): String? =
    usableTranscript(onDevice) ?: usableTranscript(host)

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
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Log.d(TAG, "skip: api=${Build.VERSION.SDK_INT} < 33")
        return null
    }
    if (bytes.isEmpty()) {
        Log.d(TAG, "skip: empty memo")
        return null
    }
    val app = context.applicationContext
    val pcm = runCatching { withContext(Dispatchers.Default) { decodePcm(app, bytes) } }
        .onFailure { Log.w(TAG, "decode threw for ${bytes.size} bytes", it) }
        .getOrNull()
    if (pcm == null || pcm.bytes.isEmpty()) {
        Log.w(TAG, "decode produced no pcm from ${bytes.size} bytes")
        return null
    }
    Log.d(
        TAG,
        "decoded sampleRate=${pcm.sampleRate} channels=${pcm.channels} bytes=${pcm.bytes.size} " +
            "frames=${pcm.bytes.size / (2 * pcm.channels)}",
    )
    val feed = recognizerPcm(pcm)
    val heard = runCatching { recognizePcm(app, feed, timeoutMs) }
        .onFailure { Log.w(TAG, "recognize threw", it) }
        .getOrNull()
    return heard?.trim()?.takeIf { it.isNotBlank() }
}

// ----------------------------------------------------------------- decode

private class Pcm(val bytes: ByteArray, val sampleRate: Int, val channels: Int)

/**
 * What the recognizer is fed: 16-bit mono at [RECOGNIZER_SAMPLE_RATE]. The WAV header and the
 * `EXTRA_AUDIO_SOURCE_*` extras both read this object, so the payload and what we declare about
 * it can never disagree.
 */
private fun recognizerPcm(pcm: Pcm): Pcm {
    val resampled = resample16kMono(pcm.bytes, pcm.sampleRate, pcm.channels)
    if (resampled.isEmpty()) return pcm
    Log.d(
        TAG,
        "feed rate=${RECOGNIZER_SAMPLE_RATE} channels=1 bytes=${resampled.size} " +
            "(from ${pcm.sampleRate}Hz ch=${pcm.channels} ${pcm.bytes.size}B)",
    )
    return Pcm(resampled, RECOGNIZER_SAMPLE_RATE, 1)
}

/**
 * 16-bit little-endian PCM at [sampleRate]/[channels] to 16 kHz mono, linear interpolation.
 * A no-op when the input already is 16 kHz mono; returns empty when there is nothing to read.
 */
internal fun resample16kMono(bytes: ByteArray, sampleRate: Int, channels: Int): ByteArray {
    val ch = channels.coerceAtLeast(1)
    val frames = bytes.size / (2 * ch)
    if (frames <= 0 || sampleRate <= 0) return ByteArray(0)
    if (sampleRate == RECOGNIZER_SAMPLE_RATE && ch == 1) return bytes
    fun sampleAt(frame: Int, channel: Int): Double {
        val at = (frame * ch + channel) * 2
        if (at + 1 >= bytes.size) return 0.0
        return (((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort()).toDouble()
    }
    val outFrames = (frames.toLong() * RECOGNIZER_SAMPLE_RATE / sampleRate).toInt()
    val out = ByteArray(outFrames * 2)
    val step = sampleRate.toDouble() / RECOGNIZER_SAMPLE_RATE
    for (i in 0 until outFrames) {
        val pos = i * step
        val base = pos.toInt()
        val frac = pos - base
        val next = if (base + 1 < frames) base + 1 else base
        var mixed = 0.0
        for (c in 0 until ch) mixed += sampleAt(base, c) * (1 - frac) + sampleAt(next, c) * frac
        val value = (mixed / ch).toInt().coerceIn(-32768, 32767)
        out[i * 2] = (value and 0xFF).toByte()
        out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
    }
    return out
}

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
 * bound, so each attempt (create, listen, await, destroy) stays on Main; its timeout
 * cancels the listen and a failure on any step yields an empty attempt.
 *
 * The tablet's AiAi answers intermittently (3 hypotheses once, empty twice, and once a
 * service that never started), so an empty or NO_MATCH take is retried exactly once, and
 * only while the first attempt was quick: a slow leg would eat the caller's whole budget.
 */
private suspend fun recognizePcm(context: Context, pcm: Pcm, timeoutMs: Long): String? {
    val available = runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
        .getOrDefault(false)
    Log.d(TAG, "availability api=${Build.VERSION.SDK_INT} onDevice=$available")
    if (!available) return null
    val startedAt = System.currentTimeMillis()
    val first = attemptRecognition(context, pcm, timeoutMs, number = 1)
    val spent = System.currentTimeMillis() - startedAt
    val empty = first.text.isNullOrBlank()
    val retryable = empty || first.error == SpeechRecognizer.ERROR_NO_MATCH
    if (retryable && spent < RETRY_IF_FASTER_THAN_MS) {
        Log.d(TAG, "retrying once · first=${if (empty) "empty" else "no-match"} spent=${spent}ms")
        val remaining = (timeoutMs - spent).coerceAtLeast(MIN_RETRY_BUDGET_MS)
        return chosen(attemptRecognition(context, pcm, remaining, number = 2))
    }
    if (retryable) Log.d(TAG, "no retry · first attempt spent ${spent}ms")
    return chosen(first)
}

/** What one attempt produced: the first non-blank hypothesis, or the recognizer's error code. */
private class Attempt(val text: String?, val error: Int?)

/** Latest point the leg is decided: on-device answered with words, or the host will. */
private fun chosen(attempt: Attempt): String? {
    val text = attempt.text?.trim()?.takeIf { it.isNotBlank() }
    if (text == null) {
        Log.d(TAG, "final leg=host · on-device gave nothing (error=${attempt.error})")
    } else {
        Log.d(TAG, "final leg=on-device chars=${text.length}")
    }
    return text
}

private suspend fun attemptRecognition(
    context: Context,
    pcm: Pcm,
    timeoutMs: Long,
    number: Int,
): Attempt = withContext(Dispatchers.Main) {
    val recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
        .onFailure { Log.w(TAG, "attempt #$number createOnDeviceSpeechRecognizer failed", it) }
        .getOrNull()
        ?: return@withContext Attempt(null, null)
    val wav = File(context.cacheDir, "asr/voice-" + UUID.randomUUID() + ".wav")
    var descriptor: ParcelFileDescriptor? = null
    try {
        wavFile(pcm, wav)
        val open = ParcelFileDescriptor.open(wav, ParcelFileDescriptor.MODE_READ_ONLY)
        descriptor = open
        val heard = CompletableDeferred<Attempt>()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val first = all.firstOrNull { it.isNotBlank() }
                Log.d(TAG, "attempt #$number results=${all.size} firstLen=${first?.length ?: 0}")
                heard.complete(Attempt(first, null))
            }

            override fun onError(error: Int) {
                Log.w(TAG, "attempt #$number onError=${errorName(error)} ($error)")
                heard.complete(Attempt(null, error))
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val started = runCatching { recognizer.startListening(recognizerIntent(open, pcm)) }
        if (started.isFailure) {
            Log.w(TAG, "attempt #$number startListening threw", started.exceptionOrNull())
            return@withContext Attempt(null, null)
        }
        val result = withTimeoutOrNull(timeoutMs) { heard.await() }
        if (result == null) {
            Log.w(TAG, "attempt #$number timed out after ${timeoutMs}ms")
            runCatching { recognizer.cancel() }
            return@withContext Attempt(null, null)
        }
        result
    } finally {
        runCatching { descriptor?.close() }
        runCatching { recognizer.destroy() }
        wav.delete()
    }
}

private fun errorName(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
    SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
    SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
    SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
    SpeechRecognizer.ERROR_SERVER -> "SERVER"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
    else -> "unknown"
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
