package app.maestri.ui

import app.maestri.ui.Style.GeminiAttachmentSheetLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentSheetLabelsTest {

    @Test
    fun theRootIsTheContainerAndTheDismissTargetIsTheScrim() {
        assertEquals("Anexos", GeminiAttachmentSheetLabels.Root)
        assertEquals("Fechar anexos", GeminiAttachmentSheetLabels.DismissTarget)
        assertNotEquals(GeminiAttachmentSheetLabels.Root, GeminiAttachmentSheetLabels.DismissTarget)
        // the container is a plain label: it must not also read as the dismiss action
        assertFalse(GeminiAttachmentSheetLabels.Root.contains("Fechar", ignoreCase = true))
    }

    @Test
    fun neitherLabelCallsTheSheetASpreadsheet() {
        listOf(GeminiAttachmentSheetLabels.Root, GeminiAttachmentSheetLabels.DismissTarget).forEach {
            assertTrue(it.isNotBlank())
            assertFalse(it.contains("planilha", ignoreCase = true))
        }
    }

    @Test
    fun theScrimIsOneLayerAtSixtyPercent() {
        assertEquals(0.6f, GeminiAttachmentSheetLabels.ScrimAlpha, 0.0001f)
        // two 0.6 scrims composite to 0.84 — the value 185 measured, one layer too many
        val stacked = 1f - (1f - GeminiAttachmentSheetLabels.ScrimAlpha) * (1f - GeminiAttachmentSheetLabels.ScrimAlpha)
        assertEquals(0.84f, stacked, 0.0001f)
        assertTrue(stacked > GeminiAttachmentSheetLabels.ScrimAlpha)
    }

    @Test
    fun theThreeTilesKeepTheirLabels() {
        assertEquals(listOf("Photos", "Camera", "Files"), GeminiAttachmentSheetLabels.tiles)
        assertEquals(3, GeminiAttachmentSheetLabels.tiles.distinct().size)
    }
}
