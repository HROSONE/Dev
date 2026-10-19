package com.example.data.api

import org.json.JSONArray
import org.json.JSONObject

/** Utilitários para APIs compatíveis com OpenAI Chat Completions (OpenRouter, llama-server, HF router). */
internal object OpenAiCompat {

    fun payload(
        model: String,
        systemInstruction: String,
        messages: List<LlmMessage>,
        temperature: Double,
        maxTokens: Int? = null,
        stream: Boolean = true
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("messages", JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemInstruction))
            for (msg in messages) {
                put(
                    JSONObject()
                        .put("role", if (msg.role == LlmMessage.Role.USER) "user" else "assistant")
                        .put("content", msg.content)
                )
            }
        })
        put("temperature", temperature)
        if (maxTokens != null) put("max_tokens", maxTokens)
        put("stream", stream)
    }

    /** Interpreta um chunk SSE (`delta`) ou uma resposta completa (`message`). */
    fun dispatchChunk(data: String, providerLabel: String, listener: LlmStreamListener) {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return
        val error = json.opt("error")
        if (error != null && error != JSONObject.NULL) {
            val message = (error as? JSONObject)?.optString("message") ?: error.toString()
            val code = (error as? JSONObject)?.optInt("code", 500) ?: 500
            throw LlmHttpException(code, "$providerLabel: $message")
        }
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return
        val node = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
        if (node == null) {
            choice.optString("text").takeIf { it.isNotEmpty() }?.let(listener::onText)
            return
        }
        stringOrEmpty(node, "reasoning").ifEmpty { stringOrEmpty(node, "reasoning_content") }
            .takeIf { it.isNotEmpty() }
            ?.let(listener::onReasoning)
        stringOrEmpty(node, "content").takeIf { it.isNotEmpty() }?.let(listener::onText)
    }

    private fun stringOrEmpty(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        return obj.optString(key)
    }
}
