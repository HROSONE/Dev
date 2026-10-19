package com.example.data.api

import com.example.data.model.LlmProvider
import com.example.data.model.QwenModelVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * Qwen no DevAgent:
 * 1) OpenRouter free (recomendado) se a key estiver salva — funciona sem motor nativo.
 * 2) Motor local llama.cpp (libllama_server.so + GGUF) se existirem no device.
 * 3) Hugging Face / Pollinations / Gemini como fallback.
 */
object QwenCoderClient {
    const val DEFAULT_LOCAL_ENDPOINT = "http://127.0.0.1:8080/v1/chat/completions"
    private const val HEALTH_URL = "http://127.0.0.1:8080/health"
    private const val HF_ROUTER_URL = "https://router.huggingface.co/hf-inference/models/"
    private const val FREE_QWEN_ENDPOINT = "https://text.pollinations.ai/openai"

    /** Modelo free bom para código na OpenRouter */
    private const val OPENROUTER_QWEN_FREE = "qwen/qwen-2.5-coder-32b-instruct:free"

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

    @Volatile
    private var localServerProcess: Process? = null

    @Volatile
    private var loadedModelInProcess: String = ""

    private val healthHttp = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private val localHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val cloudHttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean =
        OpenRouterClient.isApiKeyConfigured() ||
            GeminiClient.isApiKeyConfigured() ||
            hfApiKey.isNotBlank() ||
            (downloadedModelPath.isNotBlank() && File(downloadedModelPath).isFile)

    private fun isLocalServerHealthy(): Boolean {
        return try {
            val req = Request.Builder().url(HEALTH_URL).get().build()
            healthHttp.newCall(req).execute().use { resp -> resp.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun hasNativeEngine(): Boolean {
        if (nativeLibraryDir.isBlank()) return false
        return File(nativeLibraryDir, "libllama_server.so").isFile
    }

    private suspend fun startNativeEngineIfAvailable(diagnostics: MutableList<String>) {
        val modelPath = downloadedModelPath.trim()
        if (modelPath.isBlank() || !File(modelPath).isFile) {
            diagnostics += "GGUF local não baixado"
            return
        }

        if (isLocalServerHealthy() && (loadedModelInProcess == modelPath || loadedModelInProcess.isBlank())) {
            loadedModelInProcess = modelPath
            return
        }

        localServerProcess?.let { proc ->
            runCatching { proc.destroyForcibly() }
            localServerProcess = null
        }

        if (!hasNativeEngine()) {
            diagnostics += "libllama_server.so ausente (APK sem motor nativo)"
            return
        }
        val serverBin = File(nativeLibraryDir, "libllama_server.so")

        try {
            val pb = ProcessBuilder(
                serverBin.absolutePath,
                "-m", modelPath,
                "--host", "127.0.0.1",
                "--port", "8080",
                "-c", "2048",
                "-t", "4"
            ).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = nativeLibraryDir
            pb.environment()["GGML_BACKEND_SEARCH_PATH"] = nativeLibraryDir
            val proc = pb.start()
            localServerProcess = proc
            loadedModelInProcess = modelPath

            repeat(70) {
                delay(500)
                if (isLocalServerHealthy()) return
                if (!proc.isAlive) {
                    val exit = runCatching { proc.exitValue() }.getOrNull()
                    diagnostics += "libllama_server.so encerrou precocemente (exit=$exit)"
                    return
                }
            }
            diagnostics += "timeout aguardando GGUF na porta 8080"
        } catch (e: Exception) {
            diagnostics += "falha ao iniciar libllama_server.so: ${e.message}"
        }
    }

    suspend fun chat(
        prompt: String,
        systemInstruction: String = "Você é o Qwen operando como agente autônomo do DevAgent. Responda em português quando o usuário escrever em português."
    ): Result<String> = withContext(Dispatchers.IO) {
        val diagnostics = mutableListOf<String>()
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

        // 1. OpenRouter free primeiro (funciona sem SO nativo)
        if (OpenRouterClient.isApiKeyConfigured()) {
            val preferred = LlmProvider.DEFAULT_OPENROUTER_MODEL.ifBlank { OPENROUTER_QWEN_FREE }
            val modelId = when {
                preferred.contains("qwen", ignoreCase = true) -> preferred
                else -> OPENROUTER_QWEN_FREE
            }
            val orResult = OpenRouterClient.chat(
                prompt = prompt,
                modelId = modelId,
                systemInstruction = systemInstruction
            )
            if (orResult.isSuccess) return@withContext orResult
            diagnostics += "OpenRouter($modelId): ${orResult.exceptionOrNull()?.message}"

            // tenta outro free se o preferido falhar
            if (modelId != OPENROUTER_QWEN_FREE) {
                val fallback = OpenRouterClient.chat(
                    prompt = prompt,
                    modelId = OPENROUTER_QWEN_FREE,
                    systemInstruction = systemInstruction
                )
                if (fallback.isSuccess) return@withContext fallback
                diagnostics += "OpenRouter($OPENROUTER_QWEN_FREE): ${fallback.exceptionOrNull()?.message}"
            }
        } else {
            diagnostics += "OpenRouter sem API key"
        }

        // 2. Motor local (só se SO + GGUF existirem)
        startNativeEngineIfAvailable(diagnostics)
        val endpoint = localEndpointUrl.trim().ifBlank { DEFAULT_LOCAL_ENDPOINT }
        try {
            val localPayload = JSONObject().apply {
                put("model", activeVariant.fileName)
                put("messages", messages)
                put("temperature", 0.25)
                put("max_tokens", 1024)
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
                } else {
                    diagnostics += "Local($endpoint): HTTP ${resp.code}"
                }
            }
        } catch (e: Exception) {
            diagnostics += "Local($endpoint): ${e.javaClass.simpleName}"
        }

        // 3. Hugging Face
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
                        diagnostics += "HuggingFace: HTTP ${resp.code}"
                    }
                }
            } catch (e: Exception) {
                diagnostics += "HuggingFace: ${e.message}"
            }
        }

        // 4. Pollinations free
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
                } else {
                    diagnostics += "FreeCloud: HTTP ${resp.code}"
                }
            }
        } catch (e: Exception) {
            diagnostics += "FreeCloud: ${e.javaClass.simpleName}"
        }

        // 5. Gemini
        if (GeminiClient.isApiKeyConfigured()) {
            return@withContext GeminiClient.queryGemini(
                prompt = prompt,
                systemInstruction = systemInstruction
            )
        }

        Result.failure(
            IllegalStateException(
                "Qwen não respondeu (${diagnostics.joinToString(" · ")}). " +
                    "Solução rápida: Configurações → OpenRouter → cole a key (sk-or-…) → " +
                    "modelo free (Qwen 2.5 Coder 32B free) → Salvar. " +
                    "Motor local só funciona com libllama_server.so no APK + GGUF baixado."
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
