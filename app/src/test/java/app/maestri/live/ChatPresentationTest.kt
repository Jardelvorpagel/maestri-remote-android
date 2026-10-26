package app.maestri.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPresentationTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

    @Test
    fun aBareImageStartsAtZero() {
        assertEquals(0, imagePayloadStart(jpeg))
        assertEquals(0, imagePayloadStart(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D) + jpeg))
    }

    @Test
    fun aWrappedImageIsFoundPastItsEnvelope() {
        val envelope = "--7001ca10-6a23\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\n"
        val bytes = envelope.toByteArray() + jpeg
        assertEquals(envelope.length, imagePayloadStart(bytes))
    }

    @Test
    fun audioAndTextCarryNoImageAtAll() {
        assertEquals(-1, imagePayloadStart("OggS\u0000\u0002\u0000\u0000".toByteArray()))
        assertEquals(-1, imagePayloadStart(ByteArray(0)))
        assertFalse(imagePayloadStart("# hello".toByteArray()) >= 0)
    }

    @Test
    fun aWrapperBiggerThanTheSearchWindowStaysAGlyph() {
        val far = ByteArray(200) + jpeg
        assertTrue(imagePayloadStart(far, window = 64) < 0)
        assertEquals(200, imagePayloadStart(far, window = 4096))
    }

    @Test
    fun attachmentOnlyTurnNeedsNoBubble() {
        assertFalse(showsTurnBubble(body = "", attachmentCount = 1))
        assertFalse(showsTurnBubble(body = "   ", attachmentCount = 2))
    }

    @Test
    fun typedTurnsAndBareTurnsKeepTheirChrome() {
        assertTrue(showsTurnBubble(body = "olha o print", attachmentCount = 1))
        assertTrue(showsTurnBubble(body = "", attachmentCount = 0))
    }

    private fun event(kind: String, text: String? = null) = PiEvent(
        v = 1,
        seq = 1,
        sessionId = "s",
        entryId = "e",
        ts = "",
        kind = kind,
        role = null,
        text = text,
        tool = null,
    )

    @Test
    fun noticesAloneAreNotAConversation() {
        val rows = listOf(
            DisplayRow.StatusLine(event(LiveKinds.RUN_START, "run started session x")),
            DisplayRow.StatusLine(event(LiveKinds.NOTICE, "model google/3.8-flash")),
        )
        assertFalse(hasConversation(rows))
        assertFalse(hasConversation(emptyList()))
    }

    @Test
    fun aMessageIsAConversation() {
        assertTrue(hasConversation(listOf(DisplayRow.Message(event(LiveKinds.ASSISTANT_MESSAGE, "oi")))))
    }
}
