package app.maestri.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiCatalogTest {

    @Test
    fun theCatalogMatchesTheReferenceCard() {
        assertEquals(
            listOf("3.5 Flash-Lite", "3.8 Flash", "3.1 Pro", "4 Argon"),
            GeminiCatalog.models.map { it.title },
        )
        assertEquals(
            listOf("Fastest answers", "All-around help", "Advanced reasoning", "Complex tasks"),
            GeminiCatalog.models.map { it.subtitle },
        )
        assertEquals(listOf("Low", "Medium", "High"), GeminiCatalog.thinking.map { it.title })
        assertEquals(
            listOf("Quick and efficient", "Balanced depth", "Extra thorough"),
            GeminiCatalog.thinking.map { it.subtitle },
        )
        assertEquals(GeminiPreset("3.8-flash", "medium"), GeminiCatalog.Default)
    }

    @Test
    fun theHeaderReadsModelThenThinking() {
        assertEquals("Flash", GeminiCatalog.headerModel(GeminiCatalog.Default))
        assertEquals("Medium", GeminiCatalog.headerThinking(GeminiCatalog.Default))
        assertEquals("Flash-Lite", GeminiCatalog.headerModel(GeminiPreset("3.5-flash-lite", "high")))
        assertEquals("High", GeminiCatalog.headerThinking(GeminiPreset("3.5-flash-lite", "high")))
    }

    @Test
    fun aModelNoticeCarriesTheProviderPrefixAndSelectsThePreset() {
        val picked = GeminiCatalog.applyNotice(GeminiCatalog.Default, "model google/3.5-flash-lite")
        assertEquals("3.5-flash-lite", picked.modelId)
        assertEquals("medium", picked.thinkingId)

        val suffix = GeminiCatalog.applyNotice(GeminiCatalog.Default, "model google/3.1-pro-001")
        assertEquals("3.1-pro", suffix.modelId)
    }

    @Test
    fun aModelOutsideTheCatalogStillShowsItsOwnName() {
        val picked = GeminiCatalog.applyNotice(GeminiCatalog.Default, "model openai/gpt-5.1")
        assertEquals("gpt-5.1", picked.modelId)
        assertNull(GeminiCatalog.model(picked.modelId))
        assertEquals("gpt-5.1", GeminiCatalog.headerModel(picked))
    }

    @Test
    fun aThinkingNoticeMovesOnlyTheLevel() {
        val picked = GeminiCatalog.applyNotice(GeminiCatalog.Default, "thinking level: High")
        assertEquals("high", picked.thinkingId)
        assertEquals(GeminiCatalog.Default.modelId, picked.modelId)
    }

    @Test
    fun unrelatedNoticesLeaveTheSelectionAlone() {
        listOf("usage (turn)", "compaction done", "model", "thinking level: unknown", "thinking level:")
            .forEach { notice ->
                assertEquals(notice, GeminiCatalog.Default, GeminiCatalog.applyNotice(GeminiCatalog.Default, notice))
            }
    }
}
