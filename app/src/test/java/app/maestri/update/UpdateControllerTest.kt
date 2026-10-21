package app.maestri.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
