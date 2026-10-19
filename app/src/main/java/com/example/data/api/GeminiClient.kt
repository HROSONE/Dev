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

object GeminiClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    const val DEFAULT_MODEL = "gemini-3.8-flash"

    @Volatile
    private var runtimeApiKey: String = ""

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Define a chave usada nas próximas chamadas (vinda das Configurações). */
    fun setApiKey(key: String) {
        runtimeApiKey = key.trim()
    }

    fun isApiKeyConfigured(): Boolean {
        val key = effectiveApiKey()
        return key.isNotBlank() && key != "MY_GEMINI_API_KEY"
    }

    private fun effectiveApiKey(): String {
        if (runtimeApiKey.isNotBlank()) return runtimeApiKey
        return try {
            com.example.BuildConfig.GEMINI_API_KEY.orEmpty().trim()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Streaming via `streamGenerateContent?alt=sse`. Com [includeThoughts], os resumos de
     * pensamento do modelo (parts com `thought=true`) chegam em [LlmStreamListener.onReasoning].
     */
    suspend fun streamChat(
        messages: List<LlmMessage>,
        systemInstruction: String,
        modelName: String = DEFAULT_MODEL,
        includeThoughts: Boolean = false,
        listener: LlmStreamListener
    ) {
        val apiKey = effectiveApiKey()
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            throw IllegalStateException("API key do Gemini não configurada. Abra Configurações no app e cole sua chave.")
        }
        val contents = JSONArray()
        for (msg in mergeConsecutive(messages)) {
            contents.put(JSONObject().apply {
                put("role", if (msg.role == LlmMessage.Role.USER) "user" else "model")
                put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
            })
        }
        val payload = JSONObject().apply {
            put("contents", contents)
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("topP", 0.95)
                if (includeThoughts) {
                    put("thinkingConfig", JSONObject().put("includeThoughts", true))
                }
            })
        }
        val request = Request.Builder()
            .url("$BASE_URL$modelName:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        SseClient.stream(
            client = okHttpClient,
            request = request,
            describeError = { code, body -> describeHttpError(code, body, modelName) }
        ) { data ->
            val json = runCatching { JSONObject(data) }.getOrNull() ?: return@stream
            json.optJSONObject("error")?.let { err ->
                throw LlmHttpException(err.optInt("code", 500), "Gemini: ${err.optString("message")}")
            }
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: return@stream
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return@stream
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val text = part.optString("text")
                if (text.isEmpty()) continue
                if (part.optBoolean("thought", false)) listener.onReasoning(text) else listener.onText(text)
            }
        }
    }

    private fun describeHttpError(code: Int, body: String, modelName: String): String {
        val detail = SseClient.errorMessage(body, "HTTP $code")
        val hint = when (code) {
            400 -> " Verifique o nome do modelo ($modelName)."
            401, 403 -> " Chave inválida ou sem permissão."
            404 -> " Modelo '$modelName' não encontrado."
            429 -> " Limite de requisições; tente de novo em instantes."
            else -> ""
        }
        return "Gemini HTTP $code: $detail.$hint"
    }

    private fun mergeConsecutive(messages: List<LlmMessage>): List<LlmMessage> {
        val merged = mutableListOf<LlmMessage>()
        for (msg in messages) {
            val last = merged.lastOrNull()
            if (last != null && last.role == msg.role) {
                merged[merged.lastIndex] = last.copy(content = last.content + "\n\n" + msg.content)
            } else {
                merged += msg
            }
        }
        if (merged.firstOrNull()?.role == LlmMessage.Role.ASSISTANT) {
            merged.add(0, LlmMessage.user("(continuação da conversa)"))
        }
        return merged
    }

    suspend fun queryGemini(
        prompt: String,
        modelName: String = DEFAULT_MODEL,
        systemInstruction: String = "Você é um Engenheiro de Software Sênior especialista em IA e Agentes Autônomos de Código."
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = effectiveApiKey()
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext Result.failure(
                IllegalStateException(
                    "API key do Gemini não configurada. Abra Configurações no app e cole sua chave."
                )
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
                    400 -> " Verifique o nome do modelo ($modelName)."
                    401, 403 -> " Chave inválida ou sem permissão."
                    404 -> " Modelo '$modelName' não encontrado."
                    429 -> " Limite de requisições; tente de novo em instantes."
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
                Result.failure(Exception("Resposta vazia do modelo ($modelName)."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
