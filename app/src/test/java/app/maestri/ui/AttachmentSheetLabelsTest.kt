package app.maestri.ui

import app.maestri.ui.Style.GeminiAttachmentSheetLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentSheetLabelsTest {

    @Test
    fun theSheetAnnouncesItselfAsAttachmentsNotSpreadsheet() {
        assertEquals("Anexos", GeminiAttachmentSheetLabels.Title)
        assertEquals("Fechar anexos", GeminiAttachmentSheetLabels.Dismiss)
        listOf(GeminiAttachmentSheetLabels.Title, GeminiAttachmentSheetLabels.Dismiss).forEach {
            assertTrue(it.isNotBlank())
            assertFalse(it.contains("planilha", ignoreCase = true))
        }
    }

    @Test
    fun theThreeTilesKeepTheirLabels() {
        assertEquals(listOf("Photos", "Camera", "Files"), GeminiAttachmentSheetLabels.tiles)
        assertEquals(3, GeminiAttachmentSheetLabels.tiles.distinct().size)
    }
}
