package com.example.data.api

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    const val DEFAULT_MODEL = "gemini-3.8-flash"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isApiKeyConfigured(): Boolean {
        val key = BuildConfig.GEMINI_API_KEY
        return !key.isNullOrBlank() && key != "MY_GEMINI_API_KEY"
    }

    suspend fun queryGemini(
        prompt: String,
        modelName: String = DEFAULT_MODEL,
        systemInstruction: String = "Você é um Engenheiro de Software Sênior especialista em IA e Agentes Autônomos de Código."
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (!isApiKeyConfigured()) {
            return@withContext Result.failure(
                IllegalStateException("GEMINI_API_KEY não configurada. Defina no .env ou no painel de Secrets.")
            )
        }

        try {
            val endpoint = "$BASE_URL$modelName:generateContent"
            val jsonPayload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemInstruction) })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.3)
                    put("topP", 0.95)
                })
            }

            val body = jsonPayload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(endpoint)
                .header("x-goog-api-key", apiKey)
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try {
                    JSONObject(responseBody).optJSONObject("error")?.optString("message")
                        ?: response.message
                } catch (_: Exception) {
                    response.message
                }
                val hint = when (response.code) {
                    400 -> " Verifique o nome do modelo ($modelName) e o formato do prompt."
                    401, 403 -> " Chave de API inválida ou sem permissão."
                    404 -> " Modelo '$modelName' não encontrado. Confirme se gemini-3.8-flash está disponível na sua conta."
                    429 -> " Limite de requisições atingido; tente novamente em instantes."
                    else -> ""
                }
                return@withContext Result.failure(
                    Exception("Gemini HTTP ${response.code}: $detail.$hint")
                )
            }

            val parsedJson = JSONObject(responseBody)
            val candidates = parsedJson.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text")

            if (text != null && text.isNotEmpty()) {
                Result.success(text)
            } else {
                val blockReason = firstCandidate?.optJSONObject("finishReason")
                    ?: parsedJson.optJSONObject("promptFeedback")?.optString("blockReason")
                Result.failure(
                    Exception("Resposta vazia do modelo ($modelName). Motivo: ${blockReason ?: "desconhecido"}")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
