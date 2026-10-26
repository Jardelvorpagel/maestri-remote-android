package app.maestri.ui

import androidx.compose.ui.unit.dp
import app.maestri.live.ChatComposerGap
import app.maestri.live.chatFeedEndPadding
import app.maestri.ui.Style.ChatHeaderMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 189 layout contract: the feed pads by the measured composer, and the header got smaller. */
class ChatLayoutTest {

    @Test
    fun theFeedEndsOneGapBelowTheMeasuredComposer() {
        assertEquals(ChatComposerGap, chatFeedEndPadding(0.dp))
        assertEquals(96.dp + ChatComposerGap, chatFeedEndPadding(96.dp))
        // staged chips and the run-mode toggle grow the block; the padding must follow it
        assertTrue(chatFeedEndPadding(140.dp) > chatFeedEndPadding(96.dp))
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
