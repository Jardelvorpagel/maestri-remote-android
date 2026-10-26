package app.maestri.ui

import androidx.compose.ui.unit.dp
import app.maestri.live.ChatComposerGap
import app.maestri.live.chatClearance
import app.maestri.live.chatFeedEndPadding
import app.maestri.ui.Style.ChatHeaderMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 189/190 layout contract: the feed reserves the floating block's top edge, not its height. */
class ChatLayoutTest {

    // The four block heights Prumo measured on 189, in dp (238 / 443 / 237 / 228 px at 2.25 px/dp).
    private val plain = 105.8.dp
    private val chipStaged = 196.9.dp
    private val ime = 105.3.dp
    private val typedWithIme = 101.3.dp

    @Test
    fun everyStateReservesTheBlockPlusOneGap() {
        listOf(plain, chipStaged, ime, typedWithIme).forEach { block ->
            val reserve = chatFeedEndPadding(block)
            assertEquals(block + ChatComposerGap, reserve)
            // the clearance the user sees is the gap itself, flat across all four states
            assertEquals(ChatComposerGap, chatClearance(reserve, block))
        }
        assertEquals(ChatComposerGap, chatFeedEndPadding(0.dp))
    }

    @Test
    fun theChipStateReservesAtLeastWhatItAdds() {
        val grown = chipStaged - plain
        val reserved = chatFeedEndPadding(chipStaged) - chatFeedEndPadding(plain)
        assertTrue("chip adds $grown but reserves $reserved", reserved >= grown)
        assertEquals(grown, reserved)
        // and the 189 regression is gone: the chip case is no longer ~1 dp of air
        assertTrue(chatClearance(chatFeedEndPadding(chipStaged), chipStaged) >= ChatComposerGap)
    }

    @Test
    fun theCompactHeaderShrinksTheTextNotTheTarget() {
        assertEquals(48.dp, ChatHeaderMetrics.BarHeight)
        assertEquals(14f, ChatHeaderMetrics.ModelSp.value, 0f)
        assertEquals(13f, ChatHeaderMetrics.ThinkingSp.value, 0f)
        assertEquals(12f, ChatHeaderMetrics.ChevronSp.value, 0f)
        // a shrunken label must never shrink the tap target below the platform minimum
        assertTrue(ChatHeaderMetrics.MinTouchTarget >= 48.dp)
        assertTrue(ChatHeaderMetrics.BarHeight >= ChatHeaderMetrics.MinTouchTarget)
        // the trigger stays visibly smaller than the 188 header it replaces
        assertTrue(ChatHeaderMetrics.ModelSp.value < 17f)
        assertTrue(ChatHeaderMetrics.ThinkingSp.value < 17f)
    }
}
