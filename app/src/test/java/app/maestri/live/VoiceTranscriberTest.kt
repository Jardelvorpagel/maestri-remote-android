package app.maestri.live

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTranscriberTest {

    @Test
    fun captionAndTranscriptGetTheMarkerAfterABlankLine() {
        assertEquals(
            "ship it\n\n[transcript: olá, sobe a release]",
            composeMemoText("ship it", "olá, sobe a release"),
        )
    }

    @Test
    fun transcriptOnlyIsTheMarkerAlone() {
        assertEquals("[transcript: sobe a release]", composeMemoText("", "sobe a release"))
        assertEquals("[transcript: sobe a release]", composeMemoText("   ", "  sobe a release  "))
    }

    @Test
    fun withoutTranscriptTheCaptionIsUntouched() {
        assertEquals("ship it", composeMemoText("ship it", null))
        assertEquals("ship it", composeMemoText("ship it", "   "))
        assertEquals("", composeMemoText("", null))
    }

    @Test
    fun separatorOverrideKeepsTheLineSingleForThePty() {
        assertEquals("ship it [transcript: go]", composeMemoText("ship it", "go", " "))
        assertEquals("[transcript: go]", composeMemoText("", "go", " "))
    }

    @Test
    fun existingCallersAppendTheOnDeviceTranscript() {
        assertEquals("caption\n\n[transcript: heard]", composeMemoText("caption", "heard"))
        assertTrue(composeMemoText("caption", "heard").endsWith(TRANSCRIPT_MARKER_CLOSE))
    }

    @Test
    fun transcriptPreferenceIsOnDeviceThenHostThenNone() {
        assertEquals("heard here", resolveTranscript("heard here", "host guess"))
        assertEquals("host guess", resolveTranscript(null, "host guess"))
        assertEquals("host guess", resolveTranscript("   ", "host guess"))
        assertNull(resolveTranscript(null, null))
        assertNull(resolveTranscript("", "  "))
    }

    @Test
    fun bothFacesComposeTheResolvedTranscriptTheSameWay() {
        assertEquals(
            "caption\n\n[transcript: heard here]",
            composeMemoText("caption", resolveTranscript("heard here", "host guess")),
        )
        assertEquals(
            "caption [transcript: host guess]",
            composeMemoText("caption", resolveTranscript(null, "host guess"), " "),
        )
        assertEquals("caption", composeMemoText("caption", resolveTranscript(null, null), " "))
    }

    @Test
    fun punctuationOnlyTranscriptIsAbsent() {
        assertNull(usableTranscript("."))
        assertNull(usableTranscript(" . "))
        assertNull(usableTranscript("-"))
        assertNull(usableTranscript("..."))
        assertNull(usableTranscript("?"))
        assertNull(usableTranscript("a"))
    }

    @Test
    fun realWordsSurviveTheFloor() {
        assertEquals("ok", usableTranscript("ok"))
        assertEquals("sim", usableTranscript("  sim  "))
        assertEquals(
            "transcrição no tablet e mandando para o Pi.",
            usableTranscript("transcrição no tablet e mandando para o Pi."),
        )
        assertEquals("42", usableTranscript("42"))
    }

    @Test
    fun aSilentMemoSendsWithoutTheMarker() {
        assertEquals("", composeMemoText("", "."))
        assertEquals("caption", composeMemoText("caption", " . "))
        assertNull(resolveTranscript(".", "-"))
        assertEquals("host guess", resolveTranscript(".", "host guess"))
        assertEquals("heard here", resolveTranscript("heard here", "."))
    }

    @Test
    fun uploadResponseCarriesTheHostTranscriptWhenPresent() {
        val withTranscript = parseUploadResponse(
            """{"id":"a1","name":"voice-1.m4a","bytes":42,"path":"/tmp/voice-1.m4a",""" +
                """"transcript":"host heard this"}""",
        )
        assertEquals("host heard this", withTranscript?.transcript)
        assertEquals("/tmp/voice-1.m4a", withTranscript?.path)

        val withoutTranscript = parseUploadResponse(
            """{"id":"a1","name":"voice-1.m4a","bytes":42,"path":"/tmp/voice-1.m4a"}""",
        )
        assertNull(withoutTranscript?.transcript)

        val blankTranscript = parseUploadResponse(
            """{"id":"a1","name":"voice-1.m4a","transcript":"   "}""",
        )
        assertNull(blankTranscript?.transcript)
        assertNull(parseUploadResponse("not json"))
    }

    private fun pcm16(values: IntArray): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun shortsOf(bytes: ByteArray): List<Int> =
        (0 until bytes.size / 2).map { i ->
            ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toInt()
        }

    @Test
    fun fortyFourOneKilohertzIsFedToTheRecognizerAtSixteen() {
        val input = pcm16(IntArray(4410) { 1200 })
        val out = resample16kMono(input, 44100, 1)
        assertEquals(1600 * 2, out.size)
        assertTrue(shortsOf(out).all { kotlin.math.abs(it - 1200) <= 2 })
    }

    @Test
    fun silenceAndFullScaleSurviveTheResample() {
        val silence = resample16kMono(pcm16(IntArray(4800)), 48000, 1)
        assertEquals(1600 * 2, silence.size)
        assertTrue(shortsOf(silence).all { it == 0 })
        val loud = resample16kMono(pcm16(IntArray(4800) { 32767 }), 48000, 1)
        assertTrue(shortsOf(loud).all { it in 32760..32767 })
    }

    @Test
    fun stereoIsMixedDownToMono() {
        val frames = 4800
        val interleaved = IntArray(frames * 2) { i -> if (i % 2 == 0) 1000 else 3000 }
        val out = resample16kMono(pcm16(interleaved), 48000, 2)
        assertEquals(frames / 3 * 2, out.size)
        assertTrue(shortsOf(out).all { kotlin.math.abs(it - 2000) <= 3 })
    }

    @Test
    fun alreadySixteenKilohertzMonoIsUntouched() {
        val input = pcm16(IntArray(100) { 500 })
        assertArrayEquals(input, resample16kMono(input, 16000, 1))
    }

    @Test
    fun unusablePcmResamplesToNothing() {
        assertEquals(0, resample16kMono(ByteArray(0), 44100, 1).size)
        assertEquals(0, resample16kMono(pcm16(IntArray(10)), 0, 1).size)
    }
}
