package com.example.data.api

import com.example.data.model.LlmProvider
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Ponto único de chamada LLM. Roteia para Gemini, OpenRouter ou Qwen 2.5 Coder conforme o provedor ativo.
 */
object LlmGateway {

    @Volatile
    var activeProvider: LlmProvider = LlmProvider.GEMINI

    @Volatile
    var openRouterModelId: String = LlmProvider.DEFAULT_OPENROUTER_MODEL

    /** Transporte substituto para testes (modelo falso com streaming). Nunca definido em produção. */
    @Volatile
    internal var testTransport: (suspend (messages: List<LlmMessage>, system: String, listener: LlmStreamListener) -> Unit)? = null

    fun isConfigured(): Boolean = testTransport != null || when (activeProvider) {
        LlmProvider.GEMINI -> GeminiClient.isApiKeyConfigured()
        LlmProvider.OPENROUTER -> OpenRouterClient.isApiKeyConfigured()
        LlmProvider.QWEN_CODER -> QwenCoderClient.isConfigured()
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
        LlmProvider.QWEN_CODER -> QwenCoderClient.chat(
            prompt = prompt,
            systemInstruction = systemInstruction
        )
    }

    /**
     * Chamada com streaming: entrega raciocínio e texto token a token em [listener] e devolve o
     * texto visível completo. Repete automaticamente (até 2x, com backoff) em 429/5xx/erros de rede
     * enquanto nenhum token tiver sido emitido.
     */
    suspend fun stream(
        messages: List<LlmMessage>,
        systemInstruction: String,
        geminiModelName: String,
        deepThinking: Boolean,
        listener: LlmStreamListener
    ): Result<String> {
        var attempt = 0
        var thinkingSupported = true
        while (true) {
            val collector = CollectingListener(listener)
            try {
                val fake = testTransport
                if (fake != null) fake(messages, systemInstruction, collector) else when (activeProvider) {
                    LlmProvider.GEMINI -> GeminiClient.streamChat(
                        messages = messages,
                        systemInstruction = systemInstruction,
                        modelName = geminiModelName,
                        includeThoughts = deepThinking && thinkingSupported,
                        listener = collector
                    )
                    LlmProvider.OPENROUTER -> OpenRouterClient.streamChat(
                        messages = messages,
                        modelId = openRouterModelId,
                        systemInstruction = systemInstruction,
                        includeReasoning = deepThinking,
                        listener = collector
                    )
                    LlmProvider.QWEN_CODER -> QwenCoderClient.streamChat(
                        messages = messages,
                        systemInstruction = systemInstruction,
                        listener = collector
                    )
                }
                val text = collector.finish()
                if (text.isBlank()) {
                    if (!collector.emitted && attempt < MAX_RETRIES) {
                        attempt++
                        listener.onRetry(attempt, "resposta vazia")
                        delay(backoffMs(attempt))
                        continue
                    }
                    return Result.failure(IOException("O modelo devolveu uma resposta vazia."))
                }
                return Result.success(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Modelos sem suporte a thinkingConfig respondem 400: tenta de novo sem pedir pensamentos.
                if (e is LlmHttpException && e.code == 400 && activeProvider == LlmProvider.GEMINI &&
                    deepThinking && thinkingSupported && !collector.emitted
                ) {
                    thinkingSupported = false
                    continue
                }
                val retryable = when (e) {
                    is LlmHttpException -> e.code == 408 || e.code == 429 || e.code >= 500
                    is IOException -> true
                    else -> false
                }
                if (retryable && !collector.emitted && attempt < MAX_RETRIES) {
                    attempt++
                    listener.onRetry(attempt, e.message ?: e.javaClass.simpleName)
                    delay(backoffMs(attempt))
                    continue
                }
                return Result.failure(e)
            }
        }
    }

    private const val MAX_RETRIES = 2

    private fun backoffMs(attempt: Int): Long = if (attempt <= 1) 1_500L else 4_000L

    fun statusLabel(): String = when (activeProvider) {
        LlmProvider.GEMINI -> if (GeminiClient.isApiKeyConfigured()) "Gemini OK" else "Gemini sem key"
        LlmProvider.OPENROUTER -> if (OpenRouterClient.isApiKeyConfigured()) {
            "OpenRouter · $openRouterModelId"
        } else {
            "OpenRouter sem key"
        }
        LlmProvider.QWEN_CODER -> {
            val tag = QwenCoderClient.activeVariant.fileName.substringBefore("-instruct")
            if (QwenCoderClient.downloadedModelPath.isNotBlank()) "Qwen Local · $tag"
            else "Qwen 2.5 Coder · $tag"
        }
    }
}
