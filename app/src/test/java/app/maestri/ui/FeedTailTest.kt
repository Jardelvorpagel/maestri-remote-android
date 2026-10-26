package app.maestri.ui

import app.maestri.live.feedAtTail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The floating ↓ chip's rule, pinned so the keyboard case is covered without a device. */
class FeedTailTest {

    private val rows = 12

    @Test
    fun emptyFeedIsAlwaysAtTheTail() {
        assertTrue(feedAtTail(totalItems = 0, lastVisibleIndex = null, lastVisibleBottomPx = null, viewportEndOffsetPx = 1600))
    }

    @Test
    fun newestRowInsideTheViewportIsTheTail() {
        assertTrue(feedAtTail(rows, rows - 1, lastVisibleBottomPx = 1200, viewportEndOffsetPx = 1600))
        // the reserve pushes the newest row up the screen; that is still the tail
        assertTrue(feedAtTail(rows, rows - 1, lastVisibleBottomPx = 1370, viewportEndOffsetPx = 1600))
        // rounding slack only
        assertTrue(feedAtTail(rows, rows - 1, lastVisibleBottomPx = 1608, viewportEndOffsetPx = 1600))
    }

    @Test
    fun anOlderRowAsTheLastVisibleIsNotTheTail() {
        assertFalse(feedAtTail(rows, rows - 2, lastVisibleBottomPx = 1590, viewportEndOffsetPx = 1600))
        assertFalse(feedAtTail(rows, null, null, viewportEndOffsetPx = 1600))
        assertFalse(feedAtTail(rows, rows - 1, lastVisibleBottomPx = 1620, viewportEndOffsetPx = 1600))
    }

    @Test
    fun theKeyboardShrinkOnlyEscapesTheTailWhenTheListIsNotReanchored() {
        val reservePx = 230
        // 1. IME closed, at the tail: newest row ends one reserve above the viewport edge
        assertTrue(feedAtTail(rows, rows - 1, lastVisibleBottomPx = 1600 - reservePx, viewportEndOffsetPx = 1600))
        // 2. the IME takes 700 px: the list keeps its top anchor, so the newest row is now below
        //    the visible edge and the previous row is the last visible one — chip up, correctly
        //    for that layout, which is exactly why the screen re-anchors instead
        assertFalse(feedAtTail(rows, rows - 2, lastVisibleBottomPx = 900, viewportEndOffsetPx = 900))
        // 3. after the re-anchor the tail test agrees again and the chip goes away
        assertTrue(feedAtTail(rows, rows - 1, lastVisibleBottomPx = 900 - reservePx, viewportEndOffsetPx = 900))
    }
}
