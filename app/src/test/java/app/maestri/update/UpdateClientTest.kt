package app.maestri.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateClientTest {

    @Test
    fun onlyGreaterVersionCodesCount() {
        assertTrue(isNewer(2, 1))
        assertFalse(isNewer(1, 1))
        assertFalse(isNewer(0, 1))
    }

    @Test
    fun digestCompareIgnoresCaseAndBlanks() {
        assertTrue(digestMatches("53671b10af5cbb8ad2ae8048ed158d5a", "53671B10AF5CBB8AD2AE8048ED158D5A"))
        assertTrue(digestMatches(" ab12 ", "ab12"))
        assertFalse(digestMatches("ab12", "ab13"))
    }

    @Test
    fun thePairedHostIsTriedBeforeThePublicUrl() {
        val urls = manifestUrls(UpdateClient.LATEST_URL, "100.95.36.90")
        assertEquals(
            listOf("http://100.95.36.90:7487/release/latest.json", UpdateClient.LATEST_URL),
            urls,
        )
    }

    @Test
    fun withoutAPairedHostOnlyThePublicUrlRemains() {
        assertEquals(listOf(UpdateClient.LATEST_URL), manifestUrls(UpdateClient.LATEST_URL, null))
        assertEquals(listOf(UpdateClient.LATEST_URL), manifestUrls(UpdateClient.LATEST_URL, "   "))
    }
}
