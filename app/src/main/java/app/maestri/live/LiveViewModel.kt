package app.maestri.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object LiveKinds {
    const val USER_MESSAGE = "user_message"
    const val ASSISTANT_MESSAGE = "assistant_message"
    const val TOOL_CALL = "tool_call"
    const val TOOL_RESULT = "tool_result"
    const val RUN_START = "run_start"
    const val RUN_END = "run_end"
    const val ERROR = "error"
    const val NOTICE = "notice"

    val known: Set<String> = setOf(
        USER_MESSAGE, ASSISTANT_MESSAGE, TOOL_CALL, TOOL_RESULT,
        RUN_START, RUN_END, ERROR, NOTICE,
    )
}

interface PiRelayStore {
    val url: StateFlow<String>
    val token: StateFlow<String>
    fun setUrl(value: String)
    fun setToken(value: String)
}

class InMemoryPiRelayStore : PiRelayStore {
    private val _url = MutableStateFlow("")
    override val url: StateFlow<String> = _url
    private val _token = MutableStateFlow("")
    override val token: StateFlow<String> = _token
    override fun setUrl(value: String) { _url.value = value }
    override fun setToken(value: String) { _token.value = value }
}

class LiveViewModel(
    private val store: PiRelayStore,
    private val clientFactory: (url: String, token: String) -> PiRelayClient = { url, token -> PiRelayClient(url, token) },
) : ViewModel() {

    val url: StateFlow<String> = store.url
    val token: StateFlow<String> = store.token

    private val _relayState = MutableStateFlow<RelayState>(RelayState.Disconnected)
    val relayState: StateFlow<RelayState> = _relayState

    private val _events = MutableStateFlow<List<PiEvent>>(emptyList())
    val events: StateFlow<List<PiEvent>> = _events

    private val _following = MutableStateFlow(true)
    val following: StateFlow<Boolean> = _following

    private val _newWhileAway = MutableStateFlow(0)
    val newWhileAway: StateFlow<Int> = _newWhileAway

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    private var client: PiRelayClient? = null
    private var collectJob: Job? = null
    private var lastDeliveredSeq: Long = -1L

    val configured: Boolean get() = store.url.value.isNotBlank()

    fun setUrl(value: String) {
        store.setUrl(value.trim())
        if (value.isBlank()) disconnect()
    }

    fun setToken(value: String) {
        store.setToken(value.trim())
    }

    fun connect() {
        val urlValue = store.url.value
        if (urlValue.isBlank()) {
            _notice.value = "Set the relay URL first — no connection attempted."
            return
        }
        if (store.token.value.isBlank()) {
            _notice.value = "Set the relay token first — no connection attempted."
            return
        }
        if (client != null) return
        _notice.value = null
        val made = clientFactory(urlValue, store.token.value)
        client = made
        _relayState.value = RelayState.Connecting
        collectJob = viewModelScope.launch {
            launch { made.state.collect { _relayState.value = it } }
            launch {
                made.events.collect { list ->
                    val wasFollowing = _following.value
                    if (!wasFollowing && list.size > _events.value.size) {
                        _newWhileAway.value += list.size - _events.value.size
                    }
                    _events.value = list
                    list.lastOrNull()?.seq?.let { lastDeliveredSeq = it }
                }
            }
        }
        if (lastDeliveredSeq >= 0) runCatching { made.resumeAfter(lastDeliveredSeq) }
        made.connect()
    }

    fun disconnect() {
        collectJob?.cancel()
        collectJob = null
        runCatching { client?.disconnect() }
        client = null
        if (_relayState.value != RelayState.Disconnected) {
            _relayState.value = RelayState.Disconnected
        }
    }

    fun setFollowing(value: Boolean) {
        _following.value = value
        if (value) _newWhileAway.value = 0
    }

    fun resumeTail() {
        _following.value = true
        _newWhileAway.value = 0
    }

    fun dismissNotice() {
        _notice.value = null
    }

    override fun onCleared() {
        runCatching { client?.disconnect() }
    }
}

class LiveViewModelFactory(
    private val store: PiRelayStore,
    private val clientFactory: (url: String, token: String) -> PiRelayClient = { url, token -> PiRelayClient(url, token) },
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return LiveViewModel(store, clientFactory) as T
    }
}
