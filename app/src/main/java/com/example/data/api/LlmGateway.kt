package com.example.data.api

import com.example.data.model.LlmProvider

/**
 * Ponto único de chamada LLM. Roteia para Gemini ou OpenRouter conforme o provedor ativo.
 */
object LlmGateway {

    @Volatile
    var activeProvider: LlmProvider = LlmProvider.GEMINI

    @Volatile
    var openRouterModelId: String = LlmProvider.DEFAULT_OPENROUTER_MODEL

    fun isConfigured(): Boolean = when (activeProvider) {
        LlmProvider.GEMINI -> GeminiClient.isApiKeyConfigured()
        LlmProvider.OPENROUTER -> OpenRouterClient.isApiKeyConfigured()
    }

    suspend fun query(
        prompt: String,
        geminiModelName: String,
        systemInstruction: String
    ): Result<String> = when (activeProvider) {
        LlmProvider.GEMINI -> GeminiClient.queryGemini(
            prompt = prompt,
            modelName = geminiModelName,
            systemInstruction = systemInstruction
        )
        LlmProvider.OPENROUTER -> OpenRouterClient.chat(
            prompt = prompt,
            modelId = openRouterModelId,
            systemInstruction = systemInstruction
        )
    }

    fun statusLabel(): String = when (activeProvider) {
        LlmProvider.GEMINI -> if (GeminiClient.isApiKeyConfigured()) "Gemini OK" else "Gemini sem key"
        LlmProvider.OPENROUTER -> if (OpenRouterClient.isApiKeyConfigured()) {
            "OpenRouter · $openRouterModelId"
        } else {
            "OpenRouter sem key"
        }
    }
}
