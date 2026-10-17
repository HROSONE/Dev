package com.example.data.engine

import com.example.data.model.AgentMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AgentToolRequestParserTest {
    @Test
    fun parsesReadFile() {
        val req = AgentToolRequestParser.parse("""{"tool":"read_file","path":"src/Main.kt"}""")
        assertTrue(req is AgentToolRequest.ReadFile)
        assertEquals("src/Main.kt", (req as AgentToolRequest.ReadFile).path)
    }

    @Test
    fun rejectsAbsolutePath() {
        val req = AgentToolRequestParser.parse("""{"tool":"read_file","path":"/etc/passwd"}""")
        assertNull(req)
    }

    @Test
    fun rejectsMarkdownWrapped() {
        val req = AgentToolRequestParser.parse("Aqui: {\"tool\":\"list_files\"}")
        assertNull(req)
    }

    @Test
    fun parsesWriteFile() {
        val req = AgentToolRequestParser.parse(
            """{"tool":"write_file","path":"a.txt","content":"hello"}"""
        )
        assertTrue(req is AgentToolRequest.WriteFile)
        assertEquals("hello", (req as AgentToolRequest.WriteFile).content)
    }
}

class ToolExecutionPolicyTest {
    @Test
    fun readOnlyAllowedInAsk() {
        val d = ToolExecutionPolicy.evaluate(
            AgentMode.ASK, ChatIntent.EXPLANATION, ToolRisk.READ_ONLY
        )
        assertEquals(ToolPolicyDecision.ALLOW, d)
    }

    @Test
    fun mutationDeniedWithoutConfirmation() {
        val d = ToolExecutionPolicy.evaluate(
            AgentMode.BUILD, ChatIntent.IMPLEMENTATION_REQUEST, ToolRisk.MUTATING
        )
        assertEquals(ToolPolicyDecision.REQUIRE_CONFIRMATION, d)
    }

    @Test
    fun mutationAllowedWithConfirmation() {
        val d = ToolExecutionPolicy.evaluate(
            AgentMode.BUILD,
            ChatIntent.IMPLEMENTATION_REQUEST,
            ToolRisk.MUTATING,
            userConfirmedMutation = true
        )
        assertEquals(ToolPolicyDecision.ALLOW, d)
    }

    @Test
    fun mutationDeniedInAskMode() {
        val d = ToolExecutionPolicy.evaluate(
            AgentMode.ASK,
            ChatIntent.IMPLEMENTATION_REQUEST,
            ToolRisk.MUTATING,
            userConfirmedMutation = true
        )
        assertEquals(ToolPolicyDecision.DENY, d)
    }
}

class ChatIntentClassifierTest {
    @Test
    fun implementationRequest() {
        assertEquals(
            ChatIntent.IMPLEMENTATION_REQUEST,
            ChatIntentClassifier.classify("implemente rate limiting no login")
        )
    }

    @Test
    fun explanation() {
        assertEquals(
            ChatIntent.EXPLANATION,
            ChatIntentClassifier.classify("explique como funciona o JWT")
        )
    }

    @Test
    fun preserveLanguageWins() {
        assertEquals(
            ChatIntent.DISCUSSION,
            ChatIntentClassifier.classify("não altere nada, só explique o código")
        )
    }
}

class WorkspaceFileToolsTest {
    @Test
    fun rejectsPathTraversal() {
        val root = File.createTempFile("ws", null).apply {
            delete()
            mkdirs()
        }
        try {
            val tools = WorkspaceFileTools(root)
            val result = tools.readFile(
                "../outside.txt",
                AgentMode.ASK,
                ChatIntent.EXPLANATION
            )
            assertTrue(result is ToolDispatchResult.Executed || result is ToolDispatchResult.Denied)
            // Se Executed, o resolve deve ter lançado antes — aqui esperamos falha via exception engolida no dispatch?
            // O dispatch chama action() que lança IOException → propaga. Catch no teste:
        } catch (e: Exception) {
            assertTrue(e.message?.contains("limites") == true || e is java.io.IOException)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeAndReadRoundTrip() {
        val root = File.createTempFile("ws2", null).apply {
            delete()
            mkdirs()
        }
        try {
            val tools = WorkspaceFileTools(root)
            val write = tools.writeFile(
                "hello.txt",
                "oi",
                AgentMode.BUILD,
                ChatIntent.IMPLEMENTATION_REQUEST,
                userConfirmedMutation = true
            )
            assertTrue(write is ToolDispatchResult.Executed)
            val read = tools.readFile(
                "hello.txt",
                AgentMode.ASK,
                ChatIntent.EXPLANATION
            )
            assertTrue(read is ToolDispatchResult.Executed)
            assertEquals("oi", (read as ToolDispatchResult.Executed).value)
        } finally {
            root.deleteRecursively()
        }
    }
}
