package com.example.data.api

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.LlmProvider
import com.example.data.model.QwenModelVariant

/**
 * Segredos e preferências de LLM (SharedPreferences privado).
 */
class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Gemini ---
    fun getGeminiKey(): String =
        prefs.getString(KEY_GEMINI, "").orEmpty().trim()

    fun setGeminiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI, key.trim()).apply()
    }

    fun clearGeminiKey() {
        prefs.edit().remove(KEY_GEMINI).apply()
    }

    /** @deprecated use getGeminiKey */
    fun getUserKey(): String = getGeminiKey()

    /** @deprecated use setGeminiKey */
    fun setUserKey(key: String) = setGeminiKey(key)

    fun clearUserKey() = clearGeminiKey()

    fun resolveGeminiKey(): String {
        val user = getGeminiKey()
        if (user.isNotBlank()) return user
        return try {
            val build = com.example.BuildConfig.GEMINI_API_KEY.orEmpty().trim()
            if (build.isNotBlank() && build != "MY_GEMINI_API_KEY") build else ""
        } catch (_: Exception) {
            ""
        }
    }

    // --- OpenRouter ---
    fun getOpenRouterKey(): String =
        prefs.getString(KEY_OPENROUTER, "").orEmpty().trim()

    fun setOpenRouterKey(key: String) {
        prefs.edit().putString(KEY_OPENROUTER, key.trim()).apply()
    }

    fun clearOpenRouterKey() {
        prefs.edit().remove(KEY_OPENROUTER).apply()
    }

    // --- Qwen 2.5 Coder (Local GGUF / HuggingFace) ---
    fun getQwenVariant(): QwenModelVariant {
        val raw = prefs.getString(KEY_QWEN_VARIANT, QwenModelVariant.QWEN_1_5B.name)
            ?: QwenModelVariant.QWEN_1_5B.name
        return runCatching { QwenModelVariant.valueOf(raw) }.getOrDefault(QwenModelVariant.QWEN_1_5B)
    }

    fun setQwenVariant(variant: QwenModelVariant) {
        prefs.edit().putString(KEY_QWEN_VARIANT, variant.name).apply()
    }

    fun getQwenLocalEndpoint(): String =
        prefs.getString(KEY_QWEN_ENDPOINT, QwenCoderClient.DEFAULT_LOCAL_ENDPOINT)
            .orEmpty()
            .ifBlank { QwenCoderClient.DEFAULT_LOCAL_ENDPOINT }

    fun setQwenLocalEndpoint(endpoint: String) {
        prefs.edit().putString(
            KEY_QWEN_ENDPOINT,
            endpoint.trim().ifBlank { QwenCoderClient.DEFAULT_LOCAL_ENDPOINT }
        ).apply()
    }

    fun getHuggingFaceKey(): String =
        prefs.getString(KEY_HF_TOKEN, "").orEmpty().trim()

    fun setHuggingFaceKey(token: String) {
        prefs.edit().putString(KEY_HF_TOKEN, token.trim()).apply()
    }

    // --- Provider / model ---
    fun getProvider(): LlmProvider {
        val raw = prefs.getString(KEY_PROVIDER, LlmProvider.GEMINI.name) ?: LlmProvider.GEMINI.name
        return runCatching { LlmProvider.valueOf(raw) }.getOrDefault(LlmProvider.GEMINI)
    }

    fun setProvider(provider: LlmProvider) {
        prefs.edit().putString(KEY_PROVIDER, provider.name).apply()
    }

    fun getOpenRouterModelId(): String =
        prefs.getString(KEY_OR_MODEL, LlmProvider.DEFAULT_OPENROUTER_MODEL)
            .orEmpty()
            .ifBlank { LlmProvider.DEFAULT_OPENROUTER_MODEL }

    fun setOpenRouterModelId(modelId: String) {
        prefs.edit().putString(KEY_OR_MODEL, modelId.trim()).apply()
    }

    fun isGeminiConfigured(): Boolean = resolveGeminiKey().isNotBlank()

    fun isOpenRouterConfigured(): Boolean = getOpenRouterKey().isNotBlank()

    fun isConfigured(): Boolean = when (getProvider()) {
        LlmProvider.GEMINI -> isGeminiConfigured()
        LlmProvider.OPENROUTER -> isOpenRouterConfigured()
        LlmProvider.QWEN_CODER -> QwenCoderClient.isConfigured()
    }

    companion object {
        private const val PREFS_NAME = "devagent_secrets"
        private const val KEY_GEMINI = "gemini_api_key"
        private const val KEY_OPENROUTER = "openrouter_api_key"
        private const val KEY_PROVIDER = "llm_provider"
        private const val KEY_OR_MODEL = "openrouter_model_id"
        private const val KEY_QWEN_VARIANT = "qwen_coder_variant"
        private const val KEY_QWEN_ENDPOINT = "qwen_local_endpoint"
        private const val KEY_HF_TOKEN = "hf_api_token"
    }
}
