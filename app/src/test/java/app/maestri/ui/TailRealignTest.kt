package app.maestri.ui

import app.maestri.live.TailRealignState
import app.maestri.live.feedAtTail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The re-anchor trigger: when the newest row is put back on the tail, and when it is left alone. */
class TailRealignTest {

    private val imeClosed = 0
    private val imeOpen = 700
    private val padPlain = 254
    private val padChipStaged = 616

    @Test
    fun theFirstSampleIsNeverATrigger() {
        val state = TailRealignState()
        assertFalse(state.decision(imeClosed, padPlain, following = true))
        assertFalse(state.decision(imeOpen, padPlain, following = false))
    }

    @Test
    fun openingTheKeyboardWhileFollowingRealignsExactlyOnce() {
        val state = TailRealignState()
        state.decision(imeClosed, padPlain, following = true)
        assertTrue(state.decision(imeOpen, padPlain, following = true))
        // the very same trigger must not scroll again on every following frame
        assertFalse(state.decision(imeOpen, padPlain, following = true))
        assertFalse(state.decision(imeOpen, padPlain, following = true))
        // and closing it realigns once more, so the tail survives the round trip
        assertTrue(state.decision(imeClosed, padPlain, following = true))
        assertFalse(state.decision(imeClosed, padPlain, following = true))
    }

    @Test
    fun stagingAChipWhileFollowingRealignsOnceWithTheNewReserve() {
        val state = TailRealignState()
        state.decision(imeClosed, padPlain, following = true)
        // the chip row grows the block: the reserve jumps before anything scrolls
        assertTrue(state.decision(imeClosed, padChipStaged, following = true))
        assertFalse(state.decision(imeClosed, padChipStaged, following = true))
        // un-staging shrinks it again, and that re-anchor is just as necessary
        assertTrue(state.decision(imeClosed, padPlain, following = true))
        assertFalse(state.decision(imeClosed, padPlain, following = true))
    }

    @Test
    fun aReaderWhoScrolledAwayIsNeverYanked() {
        val state = TailRealignState()
        state.decision(imeClosed, padPlain, following = false)
        assertFalse(state.decision(imeOpen, padPlain, following = false))
        assertFalse(state.decision(imeClosed, padPlain, following = false))
        // a chip staged while reading further up must not drag them to the tail either
        assertFalse(state.decision(imeClosed, padChipStaged, following = false))
    }

    @Test
    fun theSequenceEndsWithTheTailTestAgreeing() {
        val rows = 12
        val reservePx = 230
        val state = TailRealignState()

        // 1. IME closed, following, at the tail
        state.decision(imeClosed, padPlain, following = true)
        assertTrue(feedAtTail(rows, rows - 1, 1600 - reservePx, viewportEndOffsetPx = 1600))

        // 2. the keyboard opens: the list kept its top anchor, so the newest row is below the edge
        assertTrue(state.decision(imeOpen, padPlain, following = true))
        assertFalse(feedAtTail(rows, rows - 1, 1600 - reservePx, viewportEndOffsetPx = 900))

        // 3. after the re-anchor the newest row clears the reserve again — 190's band, not 191's 1.3 dp
        assertTrue(feedAtTail(rows, rows - 1, 900 - reservePx, viewportEndOffsetPx = 900))
        assertFalse(state.decision(imeOpen, padPlain, following = true))
    }
}
