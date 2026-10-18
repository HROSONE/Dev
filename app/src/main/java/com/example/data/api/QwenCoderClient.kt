package com.example.data.api

import com.example.data.model.QwenModelVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cliente dedicado ao Qwen 2.5 Coder com roteamento inteligente em 5 níveis:
 * 1) Servidor local no aparelho (127.0.0.1:8080 / 11434) caso ativo com o GGUF baixado.
 * 2) Hugging Face Router oficial do Qwen 2.5 Coder (se houver token HF).
 * 3) OpenRouter qwen/qwen-2.5-coder-32b-instruct (se houver chave OpenRouter).
 * 4) Provedor gratuito sem chave (Pollinations OpenAI-compatible Qwen Coder endpoint),
 *    permitindo conversar imediatamente após baixar o modelo sem exigir cadastro de chave.
 * 5) Fallback para Gemini (se houver chave Gemini salva).
 */
object QwenCoderClient {
    const val DEFAULT_LOCAL_ENDPOINT = "http://127.0.0.1:8080/v1/chat/completions"
    private const val HF_ROUTER_URL = "https://router.huggingface.co/hf-inference/models/"
    private const val FREE_QWEN_ENDPOINT = "https://text.pollinations.ai/openai"

    @Volatile
    var activeVariant: QwenModelVariant = QwenModelVariant.QWEN_1_5B

    @Volatile
    var localEndpointUrl: String = DEFAULT_LOCAL_ENDPOINT

    @Volatile
    var hfApiKey: String = ""

    @Volatile
    var downloadedModelPath: String = ""

    @Volatile
    var nativeLibraryDir: String = ""

    private val localHttp = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val cloudHttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean = true

    suspend fun chat(
        prompt: String,
        systemInstruction: String = "Você é o Qwen 2.5 Coder operando como agente autônomo do DevAgent."
    ): Result<String> = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemInstruction)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        // 1. Tenta servidor local no aparelho (127.0.0.1:8080) se estiver rodando
        val endpoint = localEndpointUrl.trim().ifBlank { DEFAULT_LOCAL_ENDPOINT }
        try {
            val localPayload = JSONObject().apply {
                put("model", activeVariant.fileName)
                put("messages", messages)
                put("temperature", 0.25)
            }
            val req = Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .post(localPayload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            localHttp.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val text = parseChatCompletionText(resp.body?.string().orEmpty())
                    if (text.isNotBlank()) return@withContext Result.success(text)
                }
            }
        } catch (_: Exception) {
        }

        // 2. Se houver token do Hugging Face configurado, usa o router oficial do Qwen 2.5 Coder
        if (hfApiKey.isNotBlank()) {
            try {
                val hfEndpoint = "$HF_ROUTER_URL${activeVariant.hfModelId}/v1/chat/completions"
                val hfPayload = JSONObject().apply {
                    put("model", activeVariant.hfModelId)
                    put("messages", messages)
                    put("temperature", 0.25)
                    put("max_tokens", 4096)
                }
                val req = Request.Builder()
                    .url(hfEndpoint)
                    .header("Authorization", "Bearer ${hfApiKey.trim()}")
                    .header("Content-Type", "application/json")
                    .post(hfPayload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                cloudHttp.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val text = parseChatCompletionText(raw)
                        if (text.isNotBlank()) return@withContext Result.success(text)
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3. Se houver chave OpenRouter configurada, usa qwen/qwen-2.5-coder-32b-instruct
        if (OpenRouterClient.isApiKeyConfigured()) {
            val orResult = OpenRouterClient.chat(
                prompt = prompt,
                modelId = "qwen/qwen-2.5-coder-32b-instruct",
                systemInstruction = systemInstruction
            )
            if (orResult.isSuccess) return@withContext orResult
        }

        // 4. Endpoint Qwen 2.5 Coder gratuito sem necessidade de API key (funciona imediatamente)
        try {
            val freePayload = JSONObject().apply {
                put("model", "qwen-coder")
                put("messages", messages)
                put("temperature", 0.25)
            }
            val req = Request.Builder()
                .url(FREE_QWEN_ENDPOINT)
                .header("Content-Type", "application/json")
                .header("User-Agent", "DevAgent-Android/1.2")
                .post(freePayload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            cloudHttp.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (resp.isSuccessful && raw.isNotBlank()) {
                    val parsed = parseChatCompletionText(raw).ifBlank { raw.trim() }
                    if (parsed.isNotBlank()) return@withContext Result.success(parsed)
                }
            }
        } catch (_: Exception) {
        }

        // 5. Fallback para Gemini caso a chave Gemini esteja configurada no app
        if (GeminiClient.isApiKeyConfigured()) {
            return@withContext GeminiClient.queryGemini(
                prompt = prompt,
                systemInstruction = systemInstruction
            )
        }

        Result.failure(
            IllegalStateException(
                "Não foi possível conectar ao Qwen 2.5 Coder no momento. Verifique sua conexão com a internet ou adicione uma chave em Configurações."
            )
        )
    }

    private fun parseChatCompletionText(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}
