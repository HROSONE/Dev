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
 * Cliente dedicado ao Qwen 2.5 Coder:
 * 1) Tenta primeiro o motor local no celular (llama-server / Ollama / LocalAI em 127.0.0.1)
 *    usando o arquivo GGUF baixado pelo LocalModelManager.
 * 2) Caso o daemon local não esteja ouvindo na porta configurada, faz fallback transparente
 *    para Hugging Face Inference Router / OpenRouter com o Qwen 2.5 Coder.
 */
object QwenCoderClient {
    const val DEFAULT_LOCAL_ENDPOINT = "http://127.0.0.1:8080/v1/chat/completions"
    private const val HF_ROUTER_URL = "https://router.huggingface.co/hf-inference/models/"

    @Volatile
    var activeVariant: QwenModelVariant = QwenModelVariant.QWEN_1_5B

    @Volatile
    var localEndpointUrl: String = DEFAULT_LOCAL_ENDPOINT

    @Volatile
    var hfApiKey: String = ""

    @Volatile
    var downloadedModelPath: String = ""

    private val localHttp = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val cloudHttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean {
        val modelReady = downloadedModelPath.isNotBlank() && File(downloadedModelPath).isFile
        return modelReady ||
            hfApiKey.isNotBlank() ||
            OpenRouterClient.isApiKeyConfigured() ||
            localEndpointUrl.isNotBlank()
    }

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

        val endpoint = localEndpointUrl.trim().ifBlank { DEFAULT_LOCAL_ENDPOINT }
        try {
            val localPayload = JSONObject().apply {
                put("model", activeVariant.fileName)
                if (downloadedModelPath.isNotBlank()) {
                    put("model_path", downloadedModelPath)
                }
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
            // Daemon local na porta configurada não está ativo; tenta rota cloud do Qwen 2.5 Coder
        }

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
                    } else {
                        return@withContext Result.failure(
                            Exception("Qwen HF HTTP ${resp.code}: ${raw.take(200)}")
                        )
                    }
                }
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }

        if (OpenRouterClient.isApiKeyConfigured()) {
            return@withContext OpenRouterClient.chat(
                prompt = prompt,
                modelId = "qwen/qwen-2.5-coder-32b-instruct",
                systemInstruction = systemInstruction
            )
        }

        val statusHint = if (downloadedModelPath.isNotBlank() && File(downloadedModelPath).isFile) {
            "O arquivo GGUF (${activeVariant.fileName}) já está baixado em $downloadedModelPath. " +
                "Inicie o servidor local em $endpoint (ex.: llama-server -m $downloadedModelPath --port 8080) " +
                "ou informe um token Hugging Face / OpenRouter nas Configurações do Qwen 2.5 Coder."
        } else {
            "Baixe o modelo GGUF do Qwen 2.5 Coder em Configurações (e inicie o servidor local em $endpoint) " +
                "ou adicione um token Hugging Face / OpenRouter para usar o Qwen 2.5 Coder."
        }
        Result.failure(IllegalStateException(statusHint))
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
