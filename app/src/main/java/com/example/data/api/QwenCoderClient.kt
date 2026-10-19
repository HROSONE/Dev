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
 * 1) Motor local (GGUF + libllama_server.so) se o modelo estiver baixado — prioridade.
 * 2) OpenRouter free se houver key.
 * 3) HF / Pollinations / Gemini como fallback.
 */
object QwenCoderClient {
    const val DEFAULT_LOCAL_ENDPOINT = "http://127.0.0.1:8080/v1/chat/completions"
    private const val HEALTH_URL = "http://127.0.0.1:8080/health"
    private const val HF_ROUTER_URL = "https://router.huggingface.co/hf-inference/models/"
    private const val FREE_QWEN_ENDPOINT = "https://text.pollinations.ai/openai"
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val cloudHttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean =
        (downloadedModelPath.isNotBlank() && File(downloadedModelPath).isFile) ||
            OpenRouterClient.isApiKeyConfigured() ||
            GeminiClient.isApiKeyConfigured() ||
            hfApiKey.isNotBlank()

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

    private fun hasLocalModel(): Boolean {
        val path = downloadedModelPath.trim()
        return path.isNotBlank() && File(path).isFile && File(path).length() > 10_000_000L
    }

    private suspend fun startNativeEngineIfAvailable(diagnostics: MutableList<String>): Boolean {
        if (!hasLocalModel()) {
            diagnostics += "GGUF local não baixado"
            return false
        }
        val modelPath = downloadedModelPath.trim()

        if (isLocalServerHealthy() && (loadedModelInProcess == modelPath || loadedModelInProcess.isBlank())) {
            loadedModelInProcess = modelPath
            return true
        }

        localServerProcess?.let { proc ->
            runCatching { proc.destroyForcibly() }
            localServerProcess = null
        }

        if (!hasNativeEngine()) {
            diagnostics += "libllama_server.so ausente no APK"
            return false
        }
        val serverBin = File(nativeLibraryDir, "libllama_server.so")

        try {
            val pb = ProcessBuilder(
                serverBin.absolutePath,
                "-m", modelPath,
                "--host", "127.0.0.1",
                "--port", "8080",
                "-c", "4096",
                "-t", "4",
                "--temp", "0.7"
            ).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = nativeLibraryDir
            pb.environment()["GGML_BACKEND_SEARCH_PATH"] = nativeLibraryDir
            val proc = pb.start()
            localServerProcess = proc
            loadedModelInProcess = modelPath

            repeat(90) {
                delay(500)
                if (isLocalServerHealthy()) return true
                if (!proc.isAlive) {
                    val exit = runCatching { proc.exitValue() }.getOrNull()
                    diagnostics += "libllama_server.so saiu (exit=$exit) — modelo grande demais ou SO inválido"
                    return false
                }
            }
            diagnostics += "timeout carregando GGUF (~45s)"
            return false
        } catch (e: Exception) {
            diagnostics += "falha ao iniciar motor: ${e.message}"
            return false
        }
    }

    suspend fun chat(
        prompt: String,
        systemInstruction: String = DEFAULT_CHAT_SYSTEM
    ): Result<String> = withContext(Dispatchers.IO) {
        val diagnostics = mutableListOf<String>()
        val system = systemInstruction.ifBlank { DEFAULT_CHAT_SYSTEM }
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", system)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        // 1. LOCAL primeiro se o GGUF estiver baixado
        if (hasLocalModel()) {
            val started = startNativeEngineIfAvailable(diagnostics)
            if (started) {
                val endpoint = localEndpointUrl.trim().ifBlank { DEFAULT_LOCAL_ENDPOINT }
                try {
                    val localPayload = JSONObject().apply {
                        put("model", activeVariant.fileName)
                        put("messages", messages)
                        put("temperature", 0.7)
                        put("max_tokens", 2048)
                    }
                    val req = Request.Builder()
                        .url(endpoint)
                        .header("Content-Type", "application/json")
                        .post(localPayload.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    localHttp.newCall(req).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val text = parseChatCompletionText(raw)
                            if (text.isNotBlank()) return@withContext Result.success(text.trim())
                            diagnostics += "Local: resposta vazia"
                        } else {
                            diagnostics += "Local HTTP ${resp.code}: ${raw.take(200)}"
                        }
                    }
                } catch (e: Exception) {
                    diagnostics += "Local: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        } else {
            diagnostics += "sem GGUF local"
        }

        // 2. OpenRouter
        if (OpenRouterClient.isApiKeyConfigured()) {
            val preferred = LlmProvider.DEFAULT_OPENROUTER_MODEL.ifBlank { OPENROUTER_QWEN_FREE }
            val modelId = if (preferred.contains("qwen", ignoreCase = true)) preferred else OPENROUTER_QWEN_FREE
            val orResult = OpenRouterClient.chat(
                prompt = prompt,
                modelId = modelId,
                systemInstruction = system
            )
            if (orResult.isSuccess) return@withContext orResult
            diagnostics += "OpenRouter: ${orResult.exceptionOrNull()?.message}"
        }

        // 3. Hugging Face
        if (hfApiKey.isNotBlank()) {
            try {
                val hfEndpoint = "$HF_ROUTER_URL${activeVariant.hfModelId}/v1/chat/completions"
                val hfPayload = JSONObject().apply {
                    put("model", activeVariant.hfModelId)
                    put("messages", messages)
                    put("temperature", 0.7)
                    put("max_tokens", 2048)
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
                        if (text.isNotBlank()) return@withContext Result.success(text.trim())
                    } else {
                        diagnostics += "HF HTTP ${resp.code}"
                    }
                }
            } catch (e: Exception) {
                diagnostics += "HF: ${e.message}"
            }
        }

        // 4. Gemini
        if (GeminiClient.isApiKeyConfigured()) {
            return@withContext GeminiClient.queryGemini(
                prompt = prompt,
                systemInstruction = system
            )
        }

        Result.failure(
            IllegalStateException(
                "Qwen não respondeu (${diagnostics.joinToString(" · ")}). " +
                    "Se o modelo local travar, use um GGUF menor (0.5B/1.5B) ou OpenRouter free."
            )
        )
    }

    private fun parseChatCompletionText(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            val msg = choices?.optJSONObject(0)?.optJSONObject("message")
            val content = msg?.optString("content").orEmpty()
            if (content.isNotBlank()) return content
            // alguns builds devolvem text direto
            choices?.optJSONObject(0)?.optString("text").orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private const val DEFAULT_CHAT_SYSTEM =
        "Você é o Qwen, assistente útil e amigável no app DevAgent. " +
            "Converse naturalmente em português. Responda perguntas, explique e ajude. " +
            "Não force geração de código nem JSON de ferramentas se o usuário só estiver conversando."
}
