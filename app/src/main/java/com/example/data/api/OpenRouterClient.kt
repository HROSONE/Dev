package com.example.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object OpenRouterClient {
    private const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"

    @Volatile
    private var runtimeApiKey: String = ""

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun setApiKey(key: String) {
        runtimeApiKey = key.trim()
    }

    fun isApiKeyConfigured(): Boolean = runtimeApiKey.isNotBlank()

    suspend fun chat(
        prompt: String,
        modelId: String,
        systemInstruction: String = "Você é um Engenheiro de Software Sênior especialista em IA e Agentes Autônomos de Código."
    ): Result<String> = withContext(Dispatchers.IO) {
        if (runtimeApiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("API key da OpenRouter não configurada. Cole em Configurações.")
            )
        }
        if (modelId.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Modelo OpenRouter não selecionado."))
        }

        try {
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
            val payload = JSONObject().apply {
                put("model", modelId)
                put("messages", messages)
                put("temperature", 0.3)
            }

            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(BASE_URL)
                .header("Authorization", "Bearer $runtimeApiKey")
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", "https://github.com/HROSONE/Dev")
                .header("X-Title", "DevAgent")
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try {
                    JSONObject(responseBody).optString("error").ifBlank {
                        JSONObject(responseBody).optJSONObject("error")?.optString("message").orEmpty()
                    }.ifBlank { response.message }
                } catch (_: Exception) {
                    response.message
                }
                val hint = when (response.code) {
                    401, 403 -> " Chave OpenRouter inválida."
                    404 -> " Modelo '$modelId' não encontrado."
                    429 -> " Rate limit; aguarde e tente de novo."
                    else -> ""
                }
                return@withContext Result.failure(
                    Exception("OpenRouter HTTP ${response.code}: $detail.$hint")
                )
            }

            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            val message = choices?.optJSONObject(0)?.optJSONObject("message")
            val text = message?.optString("content").orEmpty()
            if (text.isNotBlank()) {
                Result.success(text)
            } else {
                Result.failure(Exception("Resposta vazia da OpenRouter ($modelId)."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
