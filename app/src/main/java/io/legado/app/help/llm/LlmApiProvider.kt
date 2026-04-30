package io.legado.app.help.llm

import kotlinx.coroutines.flow.Flow

interface LlmApiProvider {

    fun streamMessage(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        config: LlmConfig
    ): Flow<String>

    suspend fun sendMessage(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        config: LlmConfig
    ): String

    companion object {
        fun create(config: LlmConfig = LlmConfig()): LlmApiProvider {
            return when (config.format) {
                ApiFormat.ANTHROPIC -> AnthropicProvider()
                ApiFormat.OPENAI_COMPAT -> OpenAiCompatProvider()
            }
        }
    }
}
