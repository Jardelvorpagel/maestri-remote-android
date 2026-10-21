package app.maestri.update

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
}
