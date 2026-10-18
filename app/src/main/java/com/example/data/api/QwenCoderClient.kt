package com.example.data.api

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
 * Cliente de inferência do Qwen 2.5 Coder com suporte a execução nativa local (llama.cpp) e fallback cloud:
 * 1) Inicia automaticamente o motor nativo Android arm64 (libllama_server.so em nativeLibraryDir)
 *    carregando o arquivo GGUF baixado no armazenamento privado do app (filesDir/models/) e
 *    aguarda o endpoint /health ficar pronto na porta 8080 para inferência 100% offline.
 * 2) Caso o dispositivo não possua o binário nativo ou o modelo ainda não tenha sido baixado,
 *    tenta Hugging Face Router, OpenRouter, Pollinations e Gemini, registrando o diagnóstico exato.
 */
object QwenCoderClient {
    const val DEFAULT_LOCAL_ENDPOINT = "http://127.0.0.1:8080/v1/chat/completions"
    private const val HEALTH_URL = "http://127.0.0.1:8080/health"
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

    fun isConfigured(): Boolean = true

    private fun isLocalServerHealthy(): Boolean {
        return try {
            val req = Request.Builder().url(HEALTH_URL).get().build()
            healthHttp.newCall(req).execute().use { resp -> resp.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Inicia o binário nativo libllama_server.so (empacotado em jniLibs/arm64-v8a)
     * passando o arquivo .gguf baixado em filesDir/models/ e aguarda o carregamento na RAM.
     */
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

        if (nativeLibraryDir.isBlank()) {
            diagnostics += "nativeLibraryDir vazio"
            return
        }
        val serverBin = File(nativeLibraryDir, "libllama_server.so")
        if (!serverBin.isFile) {
            diagnostics += "libllama_server.so ausente em $nativeLibraryDir"
            return
        }

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
            diagnostics += "timeout aguardando carregamento do GGUF na porta 8080"
        } catch (e: Exception) {
            diagnostics += "falha ao iniciar libllama_server.so: ${e.message}"
        }
    }

    suspend fun chat(
        prompt: String,
        systemInstruction: String = "Você é o Qwen 2.5 Coder operando como agente autônomo do DevAgent."
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

        // 1. Inicia automaticamente o motor nativo llama.cpp (libllama_server.so) se o GGUF estiver baixado
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
                    } else {
                        diagnostics += "HuggingFace: HTTP ${resp.code}"
                    }
                }
            } catch (e: Exception) {
                diagnostics += "HuggingFace: ${e.message}"
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
            diagnostics += "OpenRouter: ${orResult.exceptionOrNull()?.message}"
        }

        // 4. Endpoint Qwen 2.5 Coder gratuito sem chave
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

        // 5. Fallback para Gemini caso a chave Gemini esteja configurada no app
        if (GeminiClient.isApiKeyConfigured()) {
            return@withContext GeminiClient.queryGemini(
                prompt = prompt,
                systemInstruction = systemInstruction
            )
        }

        Result.failure(
            IllegalStateException(
                "Não foi possível executar o Qwen 2.5 Coder (${diagnostics.joinToString(" · ")}). " +
                    "Baixe o modelo GGUF em Configurações (no APK com libllama_server.so) ou configure uma chave HF / OpenRouter / Gemini."
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
