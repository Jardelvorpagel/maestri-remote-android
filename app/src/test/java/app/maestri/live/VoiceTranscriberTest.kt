package app.maestri.live

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
}
