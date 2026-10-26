package app.maestri.live

/**
 * The Gemini model and thinking-level picker's data, kept out of the Compose file so the sync and
 * label logic is testable on the JVM.
 */
data class GeminiModel(val id: String, val title: String, val short: String, val subtitle: String)

data class GeminiThinking(val id: String, val title: String, val subtitle: String)

/** What the trigger shows and what the card marks as selected. */
data class GeminiPreset(val modelId: String, val thinkingId: String)

object GeminiCatalog {

    val models = listOf(
        GeminiModel("3.5-flash-lite", "3.5 Flash-Lite", "Flash-Lite", "Fastest answers"),
        GeminiModel("3.8-flash", "3.8 Flash", "Flash", "All-around help"),
        GeminiModel("3.1-pro", "3.1 Pro", "Pro", "Advanced reasoning"),
        GeminiModel("4-argon", "4 Argon", "Argon", "Complex tasks"),
    )

    val thinking = listOf(
        GeminiThinking("low", "Low", "Quick and efficient"),
        GeminiThinking("medium", "Medium", "Balanced depth"),
        GeminiThinking("high", "High", "Extra thorough"),
    )

    val Default = GeminiPreset(modelId = "3.8-flash", thinkingId = "medium")

    fun model(id: String): GeminiModel? = models.firstOrNull { it.id == id }

    fun thinkingLevel(id: String): GeminiThinking? = thinking.firstOrNull { it.id == id }

    /** Header first half: the model's short name (`Flash`, `Flash-Lite`), or its raw id. */
    fun headerModel(preset: GeminiPreset): String =
        model(preset.modelId)?.short ?: rawShort(preset.modelId)

    /** Header second half: the thinking level the way the reference prints it. */
    fun headerThinking(preset: GeminiPreset): String =
        thinkingLevel(preset.thinkingId)?.title ?: preset.thinkingId

    /**
     * Folds a relay notice into the selection: `model <provider>/<modelId>` (relay.mjs 500) and
     * `thinking level: <level>` (relay.mjs 503). Anything else is left alone, so the chat's other
     * notices cannot disturb the picker.
     */
    fun applyNotice(preset: GeminiPreset, notice: String): GeminiPreset {
        val text = notice.trim()
        val lower = text.lowercase()
        if (lower.startsWith("thinking level:")) {
            val level = text.substringAfter(':').trim().lowercase()
            return thinking.firstOrNull { it.id == level }?.let { preset.copy(thinkingId = it.id) } ?: preset
        }
        if (lower.startsWith("model ")) {
            val raw = text.removePrefix("model ").trim().substringAfterLast('/').trim()
            if (raw.isEmpty()) return preset
            val key = raw.lowercase().filter { it.isLetterOrDigit() }
            val matched = models.firstOrNull { candidate ->
                val id = candidate.id.filter { it.isLetterOrDigit() }
                key.isNotEmpty() && (key.contains(id) || id.contains(key))
            }
            return preset.copy(modelId = matched?.id ?: raw)
        }
        return preset
    }

    /** A model the relay named but the catalog does not carry: show what the notice said. */
    private fun rawShort(id: String): String = id.substringAfterLast('/').trim().ifBlank { "Model" }
}
