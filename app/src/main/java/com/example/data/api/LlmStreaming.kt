package com.example.data.api

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Uma mensagem da conversa enviada ao modelo (multi-turn real, não um prompt concatenado). */
data class LlmMessage(val role: Role, val content: String) {
    enum class Role { USER, ASSISTANT }

    companion object {
        fun user(content: String) = LlmMessage(Role.USER, content)
        fun assistant(content: String) = LlmMessage(Role.ASSISTANT, content)
    }
}

/**
 * Recebe a saída do modelo token a token. As chamadas acontecem na thread de IO,
 * portanto implementações devem ser thread-safe e não bloquear.
 */
interface LlmStreamListener {
    /** Pensamento/raciocínio do modelo (thought summaries do Gemini, `reasoning` da OpenRouter, `<think>` do Qwen). */
    fun onReasoning(delta: String) {}

    /** Texto visível da resposta. */
    fun onText(delta: String) {}

    /** Chamado antes de uma nova tentativa: o que foi recebido até aqui deve ser descartado. */
    fun onRetry(attempt: Int, reason: String) {}

    companion object {
        val NONE = object : LlmStreamListener {}
    }
}

class LlmHttpException(val code: Int, message: String) : IOException(message)

internal object SseClient {

    /**
     * Executa uma requisição e entrega cada evento `data:` de um stream SSE.
     * Se o servidor responder com JSON comum (sem streaming), entrega o corpo inteiro uma única vez.
     * O cancelamento da coroutine cancela a chamada HTTP imediatamente (botão Parar).
     */
    suspend fun stream(
        client: OkHttpClient,
        request: Request,
        describeError: (code: Int, body: String) -> String,
        onData: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        val cancelHandle = coroutineContext.job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    throw LlmHttpException(response.code, describeError(response.code, body))
                }
                val body = response.body ?: throw IOException("Resposta vazia do servidor.")
                val contentType = response.header("Content-Type").orEmpty()
                if (!contentType.contains("event-stream", ignoreCase = true)) {
                    val raw = body.string()
                    if (raw.trimStart().startsWith("data:")) {
                        raw.lineSequence()
                            .filter { it.startsWith("data:") }
                            .map { it.removePrefix("data:").trim() }
                            .filter { it.isNotEmpty() && it != "[DONE]" }
                            .forEach(onData)
                    } else if (raw.isNotBlank()) {
                        onData(raw)
                    }
                    return@use
                }
                val source = body.source()
                val pending = StringBuilder()
                while (true) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            if (pending.isNotEmpty()) {
                                val data = pending.toString()
                                pending.setLength(0)
                                if (data == "[DONE]") return@use
                                onData(data)
                            }
                        }
                        line.startsWith(":") -> Unit
                        line.startsWith("data:") -> {
                            if (pending.isNotEmpty()) pending.append('\n')
                            pending.append(line.removePrefix("data:").trimStart())
                        }
                        else -> Unit
                    }
                }
                val tail = pending.toString()
                if (tail.isNotEmpty() && tail != "[DONE]") onData(tail)
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw e
        } finally {
            cancelHandle.dispose()
        }
    }

    /** Extrai a mensagem de erro de um corpo JSON típico ({"error":{"message":…}} ou {"error":"…"}). */
    fun errorMessage(body: String, fallback: String): String = try {
        val json = JSONObject(body)
        json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json.optString("error").takeIf { it.isNotBlank() }
            ?: json.optString("message").takeIf { it.isNotBlank() }
            ?: fallback
    } catch (_: Exception) {
        body.take(200).ifBlank { fallback }
    }
}

/**
 * Separa blocos `<think>…</think>` (Qwen3, DeepSeek-R1 e afins) do texto visível,
 * mesmo quando as tags chegam quebradas entre dois chunks do stream.
 */
class ThinkTagSplitter(private val listener: LlmStreamListener) {
    private val pending = StringBuilder()
    private var inThink = false

    fun feed(delta: String) {
        pending.append(delta)
        drain(final = false)
    }

    fun flush() = drain(final = true)

    private fun drain(final: Boolean) {
        while (pending.isNotEmpty()) {
            val tag = if (inThink) CLOSE else OPEN
            val idx = pending.indexOf(tag)
            if (idx >= 0) {
                emit(pending.substring(0, idx))
                pending.delete(0, idx + tag.length)
                inThink = !inThink
                continue
            }
            val keep = if (final) 0 else partialSuffix(pending, tag)
            val emitLen = pending.length - keep
            if (emitLen <= 0) return
            emit(pending.substring(0, emitLen))
            pending.delete(0, emitLen)
            return
        }
    }

    private fun emit(text: String) {
        if (text.isEmpty()) return
        if (inThink) listener.onReasoning(text) else listener.onText(text)
    }

    private fun partialSuffix(buffer: CharSequence, tag: String): Int {
        val max = minOf(tag.length - 1, buffer.length)
        for (len in max downTo 1) {
            if (buffer.subSequence(buffer.length - len, buffer.length).toString() == tag.substring(0, len)) {
                return len
            }
        }
        return 0
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}

/** Acumula o texto completo recebido além de repassar os deltas. */
internal class CollectingListener(private val downstream: LlmStreamListener) : LlmStreamListener {
    private val text = StringBuilder()
    private val splitter = ThinkTagSplitter(object : LlmStreamListener {
        override fun onReasoning(delta: String) {
            emitted = true
            downstream.onReasoning(delta)
        }

        override fun onText(delta: String) {
            emitted = true
            text.append(delta)
            downstream.onText(delta)
        }
    })

    @Volatile
    var emitted: Boolean = false
        private set

    override fun onReasoning(delta: String) {
        if (delta.isEmpty()) return
        emitted = true
        downstream.onReasoning(delta)
    }

    override fun onText(delta: String) {
        if (delta.isEmpty()) return
        splitter.feed(delta)
    }

    fun finish(): String {
        splitter.flush()
        return text.toString()
    }
}
