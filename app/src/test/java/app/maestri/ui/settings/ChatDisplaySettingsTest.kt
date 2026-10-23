package app.maestri.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chat display-menu migration contract: stored values outside the menu steps must
 * clamp to the nearest valid option, never crash or silently reset.
 */
class ChatDisplaySettingsTest {

    @Test
    fun chatTextSizeStepsAreHonest() {
        assertEquals(listOf(12, 13, 15), ChatTextSizeStore.STEPS)
        assertEquals(13, ChatTextSizeStore.DEFAULT_SP)
        assertEquals(12, ChatTextSizeStore.nearestStep(12))
        assertEquals(13, ChatTextSizeStore.nearestStep(13))
        assertEquals(15, ChatTextSizeStore.nearestStep(15))
    }

    @Test
    fun terminalScaleValuesMigrateToNearestChatStep() {
        assertEquals(12, ChatTextSizeStore.nearestStep(10))
        assertEquals(12, ChatTextSizeStore.nearestStep(11))
        assertEquals(15, ChatTextSizeStore.nearestStep(20))
        assertEquals(15, ChatTextSizeStore.nearestStep(99))
        assertEquals(12, ChatTextSizeStore.nearestStep(1))
    }

    @Test
    fun chatTextSizeTieResolvesToLowerStep() {
        assertEquals(13, ChatTextSizeStore.nearestStep(14))
    }

    @Test
    fun densityNormalizeKeepsContract() {
        assertEquals(ChatDensityStore.COMPACT, ChatDensityStore.DEFAULT)
        assertEquals("roomy", ChatDensityStore.normalize("roomy"))
        assertEquals("compact", ChatDensityStore.normalize("compact"))
        assertEquals("compact", ChatDensityStore.normalize(null))
        assertEquals("compact", ChatDensityStore.normalize("cozy"))
        assertEquals("compact", ChatDensityStore.normalize(""))
    }
}
