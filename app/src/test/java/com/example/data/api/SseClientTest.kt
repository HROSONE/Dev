package com.example.data.api

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Valida o parser SSE e o formato OpenAI (delta.content / delta.reasoning) contra um servidor HTTP real. */
class SseClientTest {
    private lateinit var server: HttpServer

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sse") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                val events = listOf(
                    """{"choices":[{"delta":{"reasoning":"pensando "}}]}""",
                    """{"choices":[{"delta":{"reasoning":"mais"}}]}""",
                    """{"choices":[{"delta":{"content":"Olá"}}]}""",
                    """{"choices":[{"delta":{"content":", mundo"}}]}"""
                )
                out.write(": keep-alive\n\n".toByteArray())
                for (e in events) {
                    out.write("data: $e\n\n".toByteArray())
                    out.flush()
                }
                out.write("data: [DONE]\n\n".toByteArray())
            }
        }
        server.createContext("/json") { exchange ->
            val body = """{"choices":[{"message":{"content":"<think>plano</think>resposta"}}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/err") { exchange ->
            val body = """{"error":{"message":"quota"}}""".toByteArray()
            exchange.sendResponseHeaders(429, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun request(path: String) = Request.Builder()
        .url("http://127.0.0.1:${server.address.port}$path")
        .post("{}".toRequestBody("application/json".toMediaType()))
        .build()

    private class Recorder : LlmStreamListener {
        val reasoning = StringBuilder()
        val text = StringBuilder()
        override fun onReasoning(delta: String) { reasoning.append(delta) }
        override fun onText(delta: String) { text.append(delta) }
    }

    @Test fun parsesStreamingDeltas() = runBlocking {
        val recorder = Recorder()
        val collector = CollectingListener(recorder)
        SseClient.stream(OkHttpClient(), request("/sse"), { c, _ -> "HTTP $c" }) {
            OpenAiCompat.dispatchChunk(it, "teste", collector)
        }
        assertEquals("Olá, mundo", collector.finish())
        assertEquals("pensando mais", recorder.reasoning.toString())
    }

    @Test fun nonStreamingJsonFallbackSplitsThinkTags() = runBlocking {
        val recorder = Recorder()
        val collector = CollectingListener(recorder)
        SseClient.stream(OkHttpClient(), request("/json"), { c, _ -> "HTTP $c" }) {
            OpenAiCompat.dispatchChunk(it, "teste", collector)
        }
        assertEquals("resposta", collector.finish())
        assertEquals("plano", recorder.reasoning.toString())
    }

    @Test fun httpErrorsCarryStatusCode() = runBlocking {
        val error = runCatching {
            SseClient.stream(OkHttpClient(), request("/err"), { c, body -> "HTTP $c ${SseClient.errorMessage(body, "")}" }) {}
        }.exceptionOrNull()
        assertEquals(429, (error as LlmHttpException).code)
        assertEquals("HTTP 429 quota", error.message)
    }
}
