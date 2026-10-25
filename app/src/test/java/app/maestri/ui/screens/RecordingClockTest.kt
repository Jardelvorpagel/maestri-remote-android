package app.maestri.ui.screens

import app.maestri.live.voiceFileName
import app.maestri.ui.Style.formatRecordingClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingClockTest {

    @Test
    fun theRecordingLabelCountsMinutesAndPadsSeconds() {
        assertEquals("0:00", formatRecordingClock(0L))
        assertEquals("0:05", formatRecordingClock(5_000L))
        assertEquals("1:05", formatRecordingClock(65_000L))
        assertEquals("5:00", formatRecordingClock(300_000L))
        assertEquals("0:00", formatRecordingClock(-1L))
    }

    @Test
    fun theVoiceFileNameIsStampedAndCarriesTheAacContainer() {
        val name = voiceFileName(0L)
        assertTrue(name, Regex("""voice-\d{8}-\d{6}\.m4a""").matches(name))
    }
}
