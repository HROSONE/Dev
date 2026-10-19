package com.example.data.engine

import com.example.data.api.LlmGateway
import com.example.data.api.LlmMessage
import com.example.data.api.LlmStreamListener
import com.example.data.model.AgentMode
import com.example.data.model.ChatMessage
import com.example.data.model.MessageRole
import com.example.data.model.ExecutionStatus
import com.example.data.model.FileChangeKind
import com.example.data.model.ReasoningSpeed
import com.example.data.model.StepKind
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Executa o motor de ponta a ponta com um modelo falso que transmite tokens aos poucos,
 * verificando a linha do tempo ao vivo, as ferramentas e as métricas de eficiência.
 */
class DevAgentEngineTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @After fun tearDown() {
        LlmGateway.testTransport = null
    }

    /** Modelo roteirizado: cada rodada transmite (pensamento, texto) em pedaços pequenos. */
    private fun scriptedModel(vararg rounds: Pair<String, String>, seen: MutableList<List<LlmMessage>>? = null) {
        var index = 0
        LlmGateway.testTransport = { messages, _, listener: LlmStreamListener ->
            seen?.add(messages)
            val (reasoning, text) = rounds[minOf(index, rounds.lastIndex)]
            index++
            reasoning.chunked(7).forEach { listener.onReasoning(it); delay(15) }
            text.chunked(9).forEach { listener.onText(it); delay(15) }
        }
    }

    @Test fun createsNewFileWithLiveReasoningNarrationAndMetrics() = runBlocking {
        val root = temporaryFolder.newFolder("ws")
        scriptedModel(
            "O usuário quer uma página. Workspace vazio, então crio index.html." to
                "Vou criar o index.html.\n```json\n{\"tool\":\"write_file\",\"path\":\"index.html\",\"content\":\"<!DOCTYPE html>\\n<html><body>Oi</body></html>\"}\n```",
            "" to "Pronto: criei `index.html`."
        )

        val events = DevAgentEngine(root).executeTask(
            userPrompt = "crie uma página simples",
            mode = AgentMode.BUILD,
            speed = ReasoningSpeed.DEEP_THINKING,
            confirmMutation = { _, _, _ -> true }
        ).toList()

        val upserts = events.filterIsInstance<AgentExecutionEvent.StepUpsert>().map { it.step }
        assertTrue("pensamento ao vivo", upserts.any { it.kind == StepKind.REASONING && it.isLive })
        assertTrue("pensamento finalizado", upserts.any { it.kind == StepKind.REASONING && !it.isLive && it.text.contains("Workspace vazio") })
        assertTrue("narração ao vivo", upserts.any { it.kind == StepKind.NARRATION && it.text == "Vou criar o index.html." })
        assertFalse("JSON nunca aparece na narração", upserts.any { it.kind == StepKind.NARRATION && it.text.contains("\"tool\"") })
        assertTrue("progresso da ferramenta sendo escrita", upserts.any { it.kind == StepKind.STATUS && it.text.startsWith("Preparando write_file → index.html") })

        val toolDone = events.filterIsInstance<AgentExecutionEvent.ToolCallUpdated>().single().toolCall
        assertEquals(ExecutionStatus.SUCCESS, toolDone.status)
        assertEquals(FileChangeKind.CREATED, toolDone.fileChange?.kind)
        assertTrue(toolDone.diffLines.isNotEmpty())
        assertTrue(File(root, "index.html").readText().contains("Oi"))

        val final = events.filterIsInstance<AgentExecutionEvent.FinalResponseChunk>().single()
        assertTrue(final.text.startsWith("Pronto: criei `index.html`."))
        assertTrue("card com Play anexado", final.text.contains("```html index.html"))
        assertEquals(1, final.metrics.filesCreated)
        assertEquals(0, final.metrics.filesModified)
        assertEquals(2, final.metrics.modelRounds)
        // A narração da rodada final vira o texto da mensagem, não fica duplicada como passo.
        val removed = events.filterIsInstance<AgentExecutionEvent.StepRemoved>().map { it.stepId }
        assertTrue(removed.contains("n2"))
    }

    @Test fun editsExistingFileInsteadOfCreatingAndReportsReuse() = runBlocking {
        val root = temporaryFolder.newFolder("ws2")
        File(root, "app.js").writeText("const total = 1;\nconsole.log(total);\n")
        val seen = mutableListOf<List<LlmMessage>>()
        scriptedModel(
            "" to "Vou ajustar o valor.\n```json\n{\"tool\":\"edit_file\",\"path\":\"app.js\",\"old_text\":\"const total = 1;\",\"new_text\":\"const total = 2;\"}\n```",
            "" to "Atualizei `app.js`.",
            seen = seen
        )

        val events = DevAgentEngine(root).executeTask(
            userPrompt = "mude o total para 2",
            mode = AgentMode.BUILD,
            speed = ReasoningSpeed.FAST,
            confirmMutation = { _, _, _ -> true }
        ).toList()

        // O contexto inicial traz a árvore e o conteúdo do workspace pequeno.
        val firstTurn = seen.first().last().content
        assertTrue(firstTurn.contains("app.js"))
        assertTrue(firstTurn.contains("const total = 1;"))

        val final = events.filterIsInstance<AgentExecutionEvent.FinalResponseChunk>().single()
        assertEquals(0, final.metrics.filesCreated)
        assertEquals(1, final.metrics.filesModified)
        assertEquals(1, final.metrics.linesAdded)
        assertEquals(1, final.metrics.linesRemoved)
        assertEquals("const total = 2;\nconsole.log(total);\n", File(root, "app.js").readText())
    }

    @Test fun conversationalMessageAnswersWithoutTools() = runBlocking {
        val root = temporaryFolder.newFolder("ws3")
        scriptedModel("" to "Olá! Como posso ajudar no seu projeto?")
        val events = DevAgentEngine(root).executeTask(
            userPrompt = "oi",
            mode = AgentMode.BUILD,
            speed = ReasoningSpeed.FAST
        ).toList()
        assertTrue(events.none { it is AgentExecutionEvent.ToolCallStarted })
        val final = events.filterIsInstance<AgentExecutionEvent.FinalResponseChunk>().single()
        assertEquals("Olá! Como posso ajudar no seu projeto?", final.text)
    }

    @Test fun askModeCanReadButNeverWrites() = runBlocking {
        val root = temporaryFolder.newFolder("ws4")
        File(root, "README.md").writeText("# Projeto\n")
        scriptedModel(
            "" to "{\"tool\":\"read_file\",\"path\":\"README.md\"}\n{\"tool\":\"write_file\",\"path\":\"x.txt\",\"content\":\"a\"}",
            "" to "O README só tem o título."
        )
        val events = DevAgentEngine(root).executeTask(
            userPrompt = "o que tem no readme?",
            mode = AgentMode.ASK,
            speed = ReasoningSpeed.FAST,
            confirmMutation = { _, _, _ -> true }
        ).toList()
        val tools = events.filterIsInstance<AgentExecutionEvent.ToolCallUpdated>().map { it.toolCall }
        assertEquals(ExecutionStatus.SUCCESS, tools.first { it.title == "read_file" }.status)
        assertEquals(ExecutionStatus.FAILED, tools.first { it.title == "write_file" }.status)
        assertFalse(File(root, "x.txt").exists())
        assertTrue(events.any { it is AgentExecutionEvent.FinalResponseChunk })
    }

    @Test fun oldFailureMessagesAreNotSentAsHistoryAndBuildAlwaysWrites() = runBlocking {
        val root = temporaryFolder.newFolder("ws5")
        val seen = mutableListOf<List<LlmMessage>>()
        scriptedModel(
            "" to "{\"tool\":\"write_file\",\"path\":\"index.html\",\"content\":\"<!DOCTYPE html><html></html>\"}",
            "" to "Criei a página.",
            seen = seen
        )
        val history = listOf(
            ChatMessage("u1", MessageRole.USER, "faz um jogo"),
            ChatMessage("a1", MessageRole.AGENT, "A política de segurança negou a alteração solicitada. Nenhuma alteração não confirmada foi aplicada."),
            ChatMessage("u2", MessageRole.USER, "de novo"),
            ChatMessage("a2", MessageRole.AGENT, "⚠️ Falha ao consultar o modelo: timeout")
        )
        val events = DevAgentEngine(root).executeTask(
            // "não quero … projeto" era classificado como pedido de não alterar nada.
            userPrompt = "não quero que o projeto fique feio, faz uma página bonita",
            mode = AgentMode.BUILD,
            speed = ReasoningSpeed.FAST,
            conversationHistory = history,
            confirmMutation = { _, _, _ -> true }
        ).toList()
        val sent = seen.first().joinToString("\n") { it.content }
        assertFalse(sent.contains("política de segurança negou"))
        assertFalse(sent.contains("Falha ao consultar"))
        assertTrue(sent.contains("faz um jogo"))
        assertTrue(File(root, "index.html").isFile)
        assertEquals(1, events.filterIsInstance<AgentExecutionEvent.FinalResponseChunk>().single().metrics.filesCreated)
    }

    @Test fun completeHtmlWithoutFileNameIsSavedAsIndexHtml() = runBlocking {
        val root = temporaryFolder.newFolder("ws6")
        scriptedModel("" to "Aqui está:\n```html\n<!DOCTYPE html>\n<html><body>Oi</body></html>\n```")
        val events = DevAgentEngine(root).executeTask(
            userPrompt = "faz uma página",
            mode = AgentMode.BUILD,
            speed = ReasoningSpeed.FAST,
            confirmMutation = { _, _, _ -> true }
        ).toList()
        assertTrue(File(root, "index.html").readText().contains("Oi"))
        assertEquals(1, events.filterIsInstance<AgentExecutionEvent.FinalResponseChunk>().single().metrics.filesCreated)
    }
}
