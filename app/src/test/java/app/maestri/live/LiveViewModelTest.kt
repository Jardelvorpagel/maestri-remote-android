package app.maestri.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-proven gap: LiveViewModel.connect() used to refuse blank tokens before the
 * socket, so the token-optional client never got to dial. These tests drive the real
 * viewmodel with a capturing factory: blank token must reach client construction
 * with no notice, blank URL must attempt nothing. Reintroducing a token guard fails
 * [connectWithBlankTokenReachesClient].
 */
class LiveViewModelTest {

    private fun testScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun connectWithBlankTokenReachesClient() {
        val store = InMemoryPiRelayStore()
        store.setUrl("ws://127.0.0.1:9/stream")
        store.setToken("")
        var captured: Pair<String, String>? = null
        var created: PiRelayClient? = null
        val vm = LiveViewModel(
            store = store,
            clientFactory = { url, token ->
                captured = url to token
                PiRelayClient(url, token).also { created = it }
            },
            scope = testScope(),
        )
        assertTrue(vm.configured)

        vm.connect()

        assertEquals("ws://127.0.0.1:9/stream" to "", captured)
        assertNull(vm.notice.value)
        created?.disconnect()
        vm.disconnect()
    }

    @Test
    fun connectWithBlankUrlAttemptsNothing() {
        val store = InMemoryPiRelayStore()
        store.setUrl("")
        store.setToken("")
        var factoryCalled = false
        val vm = LiveViewModel(
            store = store,
            clientFactory = { url, token ->
                factoryCalled = true
                PiRelayClient(url, token)
            },
            scope = testScope(),
        )
        assertFalse(vm.configured)

        vm.connect()

        assertFalse(factoryCalled)
        assertEquals("Set the relay URL first — no connection attempted.", vm.notice.value)
        assertTrue(vm.relayState.value is RelayState.Disconnected)
        vm.disconnect()
    }
}
