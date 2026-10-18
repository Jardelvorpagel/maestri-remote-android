package app.maestri.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.agentSettingsDataStore by preferencesDataStore(name = "maestri_agent_settings")

/**
 * Pi-agent terminal settings, ported to mobile (todo #56, design frame 09 + chat
 * settings card in `maestri-remote-android-v2-84f9/index.html`).
 *
 * Desktop source of truth: `~/.pi/agent/settings.json` — `defaultProvider`,
 * `defaultModel`, `defaultThinkingLevel`. The phone keeps a client-side mirror
 * (TerminalFontStore precedent): selection UI + persistence live here; applying
 * a choice to the live agent session is wire scope and stays deferred — the rows
 * say so via the host-managed group, never silently.
 *
 * Defaults mirror the observed desktop values (ollama-cloud / glm-5.3-flash /
 * medium). Only strings are stored, never credentials or user-identifying data.
 */
class AgentSettingsStore(private val context: Context) {

    private object Keys {
        val PROVIDER = stringPreferencesKey("agent_provider")
        val MODEL = stringPreferencesKey("agent_model")
        val THINKING = stringPreferencesKey("agent_thinking_level")
    }

    companion object {
        const val DEFAULT_PROVIDER = "ollama-cloud"
        const val DEFAULT_MODEL = "glm-5.3-flash"
        const val DEFAULT_THINKING = "medium"

        /** Desktop thinking-level keys, verbatim (`off` … `max`, docs/models.md). */
        val THINKING_LEVELS: List<String> =
            listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

        // Desktop enabledModels snapshot (79) — provider picker source of truth.
        val MODELS_BY_PROVIDER: Map<String, List<String>> = mapOf(
            "github-copilot" to listOf("gemini-3.8-flash", "gpt-5.3-codex", "gpt-6-luna", "gpt-6.1-sol", "grok-4.7", "kimi-k3"),
            "meta" to listOf("muse-spark-1.1", "muse-spark-1.2", "muse-spark-1.2-contributor", "muse-spark-1.3", "muse-spark-1.3-contributor", "muse-wonder-1.0"),
            "modal-dau" to listOf("qwen3.8-27b-dau"),
            "ollama-cloud" to listOf("deepseek-v4-pro:0813", "deepseek-v4.1-flash", "gemma4:31b", "glm-5.2", "glm-5.3", "glm-5.3-flash", "gpt-oss:120b", "gpt-oss:20b", "kimi-k2.6", "kimi-k2.7-code", "kimi-k3", "minimax-m2.7", "minimax-m3", "mistral-large-3:675b", "mistral-large-4", "nemotron-3-nano:30b", "nemotron-3-super", "nemotron-3-ultra"),
            "omp-antigravity" to listOf("google-antigravity/gemini-3.1-flash-image", "google-antigravity/gemini-3.8-flash-high", "google-antigravity/gemini-3.8-flash-medium"),
            "opencode" to listOf("gemini-4-argon", "muse-spark-1.3-contributor-free", "nemotron-3-ultra-free", "nemotron-3.5-lightning-free", "space-bunny-free"),
            "opencode-go" to listOf("claude-haiku-5-5", "deepseek-v4-pro", "deepseek-v4.1-flash", "gemini-4-argon-contributor", "glm-5.2", "glm-5.3", "glm-5.3-flash", "gpt-5.6-luna", "gpt-6-luna", "grok-4.7", "hy3", "hy4-preview", "kimi-k2.7-code", "kimi-k3", "longcat-2.0", "longcat-2.5-preview-free", "mimo-v2.5", "mimo-v2.5-pro", "mimo-v2.6-flash", "mimo-v2.6-pro", "minimax-m3", "muse-spark-1.2-contributor", "muse-spark-1.3-contributor", "qwen3.7-plus", "qwen3.8-flash", "qwen3.8-max", "space-bunny", "step-5-preview-free"),
            "openrouter" to listOf("xiaomi/mimo-v2.6-pro", "xiaomi/mimo-v2.6-flash", "inclusionai/ling-3.0-flash", "deepseek/deepseek-v4.1-flash-1017", "deepseek/deepseek-v4.1-flash:batch", "deepseek/deepseek-v4.1-flash"),
            "portkey-foundry" to listOf("@bedrock-aifoundry-use1-001/us.moonshotai.kimi-k3", "@bedrock-aifoundry-use1-001/zai.glm-5", "@bedrockmantle-aifoundry-use1-001/openai.gpt-6-sol", "@openai-aifoundry-swc-001/gpt-5.4", "@vertexai-aifoundry-global-001/gemini-3.5-flash", "@vertexai-aifoundry-global-001/gemma-4-26b-a4b-it-maas"),
        )

        val PROVIDERS: List<String> = MODELS_BY_PROVIDER.keys.sorted()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val providerState = MutableStateFlow(DEFAULT_PROVIDER)
    private val modelState = MutableStateFlow(DEFAULT_MODEL)
    private val thinkingState = MutableStateFlow(DEFAULT_THINKING)

    val provider: StateFlow<String> =
        providerState.stateIn(scope, SharingStarted.Eagerly, DEFAULT_PROVIDER)

    /**
     * Model follows the provider: a stored model that is not offered by the
     * current provider falls back to that provider's first model (never a
     * stale cross-provider value in the row).
     */
    val model: StateFlow<String> =
        combine(providerState, modelState) { provider, model ->
            val offered = MODELS_BY_PROVIDER[provider].orEmpty()
            if (offered.contains(model)) model else offered.firstOrNull() ?: DEFAULT_MODEL
        }.stateIn(scope, SharingStarted.Eagerly, DEFAULT_MODEL)

    val thinkingLevel: StateFlow<String> =
        thinkingState.stateIn(scope, SharingStarted.Eagerly, DEFAULT_THINKING)

    init {
        scope.launch {
            context.agentSettingsDataStore.data.collect { prefs ->
                providerState.value = prefs[Keys.PROVIDER] ?: DEFAULT_PROVIDER
                modelState.value = prefs[Keys.MODEL] ?: DEFAULT_MODEL
                val thinking = prefs[Keys.THINKING] ?: DEFAULT_THINKING
                thinkingState.value = if (THINKING_LEVELS.contains(thinking)) thinking else DEFAULT_THINKING
            }
        }
    }

    fun setProvider(provider: String) {
        if (!PROVIDERS.contains(provider)) return
        providerState.value = provider
        scope.launch { context.agentSettingsDataStore.edit { it[Keys.PROVIDER] = provider } }
    }

    fun setModel(model: String) {
        modelState.value = model
        scope.launch { context.agentSettingsDataStore.edit { it[Keys.MODEL] = model } }
    }

    fun setThinkingLevel(level: String) {
        if (!THINKING_LEVELS.contains(level)) return
        thinkingState.value = level
        scope.launch { context.agentSettingsDataStore.edit { it[Keys.THINKING] = level } }
    }
}
