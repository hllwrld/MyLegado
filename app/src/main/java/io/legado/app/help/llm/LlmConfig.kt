package io.legado.app.help.llm

import io.legado.app.help.config.AppConfig

enum class ApiFormat {
    ANTHROPIC,
    OPENAI_COMPAT
}

data class LlmConfig(
    val baseUrl: String = AppConfig.aiChatBaseUrl,
    val apiKey: String = AppConfig.aiChatApiKey,
    val model: String = AppConfig.aiChatModel,
    val format: ApiFormat = if (AppConfig.aiChatApiFormat == "openai") ApiFormat.OPENAI_COMPAT else ApiFormat.ANTHROPIC
)

data class LlmMessage(
    val role: String,
    val content: String
)
