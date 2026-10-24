package app.maestri.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateControllerTest {

    @Test
    fun aFailedAttemptIsRetriedUntilItWorks() = runBlocking {
        var calls = 0
        val release = retrying(attempts = 3, delayMs = 1L) {
            calls++
            if (calls < 3) null else ReleaseInfo(152, "0.2.1", "", "", 0L, "", "")
        }
        assertEquals(3, calls)
        assertEquals(152, release?.versionCode)
    }

    @Test
    fun theRetryGivesUpAfterTheLastAttempt() = runBlocking {
        var calls = 0
        val release = retrying(attempts = 3, delayMs = 1L) {
            calls++
            null
        }
        assertEquals(3, calls)
        assertNull(release)
    }

    @Test
    fun aForegroundReturnChecksOncePerWindow() {
        assertTrue(dueForCheck(lastCheckAt = 0L, now = 5_000L, windowMs = 15_000L))
        assertFalse(dueForCheck(lastCheckAt = 5_000L, now = 10_000L, windowMs = 15_000L))
        assertTrue(dueForCheck(lastCheckAt = 5_000L, now = 20_000L, windowMs = 15_000L))
    }
}
