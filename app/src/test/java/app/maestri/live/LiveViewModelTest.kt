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

    @Test
    fun rapidDoubleConnectDialsOnce() {
        val store = InMemoryPiRelayStore()
        store.setUrl("ws://127.0.0.1:9/stream")
        var calls = 0
        var created: PiRelayClient? = null
        val vm = LiveViewModel(
            store = store,
            clientFactory = { url, token ->
                calls++
                PiRelayClient(url, token).also { created = it }
            },
            scope = testScope(),
        )
        // Duplicate effect firing / auto+manual race while the attempt is in flight
        // must collapse to one dial (device showed two `connecting` lines 275ms apart).
        vm.connect()
        vm.connect()
        assertEquals(1, calls)
        // After a real Stop the next Connect redials.
        vm.disconnect()
        vm.connect()
        assertEquals(2, calls)
        created?.disconnect()
        vm.disconnect()
    }

    @Test
    fun sweepStalePendingFailsOnlyOldSendingRows() {
        val old = LivePendingSend("m1", "hi", emptyList(), LiveSendStatus.SENDING, createdAtMs = 0L)
        val fresh = LivePendingSend("m2", "hi", emptyList(), LiveSendStatus.SENDING, createdAtMs = 1_999_000L)
        val failed = LivePendingSend("m3", "hi", emptyList(), LiveSendStatus.FAILED, "x", createdAtMs = 0L)
        val out = sweepStalePending(listOf(old, fresh, failed), nowMs = 2_000_000L)
        assertEquals(LiveSendStatus.FAILED, out.first { it.clientMsgId == "m1" }.status)
        assertEquals("no send_ack from the relay — tap retry", out.first { it.clientMsgId == "m1" }.detail)
        assertEquals(LiveSendStatus.SENDING, out.first { it.clientMsgId == "m2" }.status)
        assertEquals(LiveSendStatus.FAILED, out.first { it.clientMsgId == "m3" }.status)
        assertEquals("x", out.first { it.clientMsgId == "m3" }.detail)
    }
}
