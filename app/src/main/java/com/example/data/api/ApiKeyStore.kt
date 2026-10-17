package com.example.data.api

import android.content.Context
import android.content.SharedPreferences

/**
 * Guarda a chave Gemini no app (SharedPreferences privado).
 * Prioridade: chave salva pelo usuário > BuildConfig (build-time).
 */
class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getUserKey(): String =
        prefs.getString(KEY_GEMINI, "").orEmpty().trim()

    fun setUserKey(key: String) {
        prefs.edit().putString(KEY_GEMINI, key.trim()).apply()
    }

    fun clearUserKey() {
        prefs.edit().remove(KEY_GEMINI).apply()
    }

    fun hasUserKey(): Boolean = getUserKey().isNotBlank()

    /**
     * Chave efetiva: preferência do usuário, senão BuildConfig se válida.
     */
    fun resolveGeminiKey(): String {
        val user = getUserKey()
        if (user.isNotBlank()) return user
        return try {
            val build = com.example.BuildConfig.GEMINI_API_KEY.orEmpty().trim()
            if (build.isNotBlank() && build != "MY_GEMINI_API_KEY") build else ""
        } catch (_: Exception) {
            ""
        }
    }

    fun isConfigured(): Boolean = resolveGeminiKey().isNotBlank()

    companion object {
        private const val PREFS_NAME = "devagent_secrets"
        private const val KEY_GEMINI = "gemini_api_key"
    }
}
