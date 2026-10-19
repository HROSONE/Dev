package com.example.data.engine

import com.example.data.api.LlmMessage
import com.example.data.model.AgentMode
import com.example.data.model.FileChangeKind
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentToolLoopTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun coordinator(): Pair<AgentToolCoordinator, File> {
        val root = temporaryFolder.newFolder()
        File(root, "src").mkdirs()
        File(root, "src/Main.kt").writeText("fun main() = Unit")
        return AgentToolCoordinator(WorkspaceFileTools(root)) to root
    }

    private fun List<LlmMessage>.joined() = joinToString("\n") { it.content }

    @Test fun readResultReturnsToModelAsSeparateTurn() = runBlocking {
        val (coordinator) = coordinator()
        var calls = 0
        var secondTurn: List<LlmMessage> = emptyList()
        val result = AgentToolLoop(coordinator).run(
            listOf(LlmMessage.user("Read the file")), AgentMode.ASK, ChatIntent.EXPLANATION,
            requestModel = { messages ->
                calls++
                if (calls == 1) Result.success("""{"tool":"read_file","path":"src/Main.kt"}""")
                else { secondTurn = messages; Result.success("The file defines an empty main function.") }
            }
        )
        assertEquals(AgentLoopResult.FinalAnswer("The file defines an empty main function.", 1), result)
        assertEquals(
            listOf(LlmMessage.Role.USER, LlmMessage.Role.ASSISTANT, LlmMessage.Role.USER),
            secondTurn.map { it.role }
        )
        assertTrue(secondTurn.last().content.contains("fun main() = Unit"))
        assertTrue(secondTurn.last().content.contains("não confiáveis"))
    }

    @Test fun rejectedWriteIsReportedToModelAndNothingIsWritten() = runBlocking {
        val (coordinator, root) = coordinator()
        var calls = 0
        var feedback = ""
        val result = AgentToolLoop(coordinator).run(
            listOf(LlmMessage.user("Create file")), AgentMode.BUILD, ChatIntent.IMPLEMENTATION_REQUEST,
            requestModel = { messages ->
                calls++
                if (calls == 1) Result.success("""{"tool":"write_file","path":"new.txt","content":"hello"}""")
                else { feedback = messages.last().content; Result.success("Ok, não gravei.") }
            }
        )
        assertEquals(AgentLoopResult.FinalAnswer("Ok, não gravei.", 1), result)
        assertTrue(feedback.contains("RECUSOU"))
        assertFalse(File(root, "new.txt").exists())
    }

    @Test fun approvedWriteReportsFileChangeAndContinues() = runBlocking {
        val (coordinator, root) = coordinator()
        var calls = 0
        val events = mutableListOf<ToolLoopEvent>()
        val result = AgentToolLoop(coordinator).run(
            listOf(LlmMessage.user("Create file")), AgentMode.BUILD, ChatIntent.IMPLEMENTATION_REQUEST,
            requestModel = {
                calls++
                Result.success(
                    if (calls == 1) """{"tool":"write_file","path":"new.txt","content":"hello\nworld"}"""
                    else "File created."
                )
            },
            confirmMutation = { name, path, _ -> name == "write_file" && path == "new.txt" },
            onToolEvent = { events += it }
        )
        assertEquals(AgentLoopResult.FinalAnswer("File created.", 1), result)
        assertEquals("hello\nworld", File(root, "new.txt").readText())
        val completed = events.filterIsInstance<ToolLoopEvent.Completed>().single()
        val change = assertNotNullAndGet(completed.change)
        assertEquals(FileChangeKind.CREATED, change.info.kind)
        assertEquals(2, change.info.linesAdded)
    }

    @Test fun editOfExistingFileIsMarkedAsModifiedWithDiff() = runBlocking {
        val (coordinator, root) = coordinator()
        var calls = 0
        val events = mutableListOf<ToolLoopEvent>()
        AgentToolLoop(coordinator).run(
            listOf(LlmMessage.user("Edit")), AgentMode.BUILD, ChatIntent.IMPLEMENTATION_REQUEST,
            requestModel = {
                calls++
                Result.success(
                    if (calls == 1) """{"tool":"edit_file","path":"src/Main.kt","old_text":"= Unit","new_text":"{ println(1) }"}"""
                    else "Done."
                )
            },
            confirmMutation = { _, _, _ -> true },
            onToolEvent = { events += it }
        )
        assertEquals("fun main() { println(1) }", File(root, "src/Main.kt").readText())
        val change = assertNotNullAndGet(events.filterIsInstance<ToolLoopEvent.Completed>().single().change)
        assertEquals(FileChangeKind.MODIFIED, change.info.kind)
        assertEquals(1, change.info.linesAdded)
        assertEquals(1, change.info.linesRemoved)
        assertTrue(change.diff.isNotEmpty())
    }

    @Test fun toolErrorsAreFedBackInsteadOfAborting() = runBlocking {
        val (coordinator) = coordinator()
        var calls = 0
        var feedback = ""
        val result = AgentToolLoop(coordinator).run(
            listOf(LlmMessage.user("Read")), AgentMode.ASK, ChatIntent.EXPLANATION,
            requestModel = { messages ->
                calls++
                when (calls) {
                    1 -> Result.success("""{"tool":"read_file","path":"missing.kt"}""")
                    else -> { feedback = messages.last().content; Result.success("Arquivo não existe.") }
                }
            }
        )
        assertTrue(feedback.contains("status=\"erro\""))
        assertEquals(AgentLoopResult.FinalAnswer("Arquivo não existe.", 1), result)
    }

    @Test fun mutationInAskModeIsDeniedButLoopContinues() = runBlocking {
        val (coordinator, root) = coordinator()
        var calls = 0
        var feedback = ""
        val result = AgentToolLoop(coordinator).run(
            listOf(LlmMessage.user("Change")), AgentMode.ASK, ChatIntent.EXPLANATION,
            requestModel = { messages ->
                calls++
                if (calls == 1) Result.success("""{"tool":"write_file","path":"x.txt","content":"a"}""")
                else { feedback = messages.last().content; Result.success("Sugestão: criar x.txt.") }
            },
            confirmMutation = { _, _, _ -> true }
        )
        assertTrue(result is AgentLoopResult.FinalAnswer)
        assertTrue(feedback.contains("somente leitura"))
        assertFalse(File(root, "x.txt").exists())
    }

    @Test fun toolLimitStillAllowsOneFinalAnswer() = runBlocking {
        val (coordinator) = coordinator()
        var calls = 0
        val result = AgentToolLoop(coordinator, maxToolCalls = 1).run(
            listOf(LlmMessage.user("Read file")), AgentMode.ASK, ChatIntent.EXPLANATION,
            requestModel = { messages ->
                calls++
                if (calls == 1) Result.success("""{"tool":"read_file","path":"src/Main.kt"}""")
                else {
                    assertTrue(messages.joined().contains("Limite de ferramentas atingido"))
                    Result.success("Done.")
                }
            }
        )
        assertEquals(AgentLoopResult.FinalAnswer("Done.", 1), result)
        assertEquals(2, calls)
    }

    @Test fun toolRequestAfterLimitIsNotExecuted() = runBlocking {
        val (coordinator) = coordinator()
        var calls = 0
        var executed = 0
        val result = AgentToolLoop(coordinator, maxToolCalls = 1).run(
            listOf(LlmMessage.user("Read file")), AgentMode.ASK, ChatIntent.EXPLANATION,
            requestModel = { calls++; Result.success("Vou ler.\n" + """{"tool":"read_file","path":"src/Main.kt"}""") },
            onToolEvent = { if (it is ToolLoopEvent.Completed) executed++ }
        )
        assertTrue(result is AgentLoopResult.FinalAnswer)
        assertEquals("Vou ler.", (result as AgentLoopResult.FinalAnswer).text)
        assertEquals(2, calls)
        assertEquals(1, executed)
    }

    @Test fun largeWriteContentIsElidedFromLaterTurns() = runBlocking {
        val (coordinator) = coordinator()
        val big = "x".repeat(5_000)
        var calls = 0
        var secondTurn = ""
        AgentToolLoop(coordinator).run(
            listOf(LlmMessage.user("Create")), AgentMode.BUILD, ChatIntent.IMPLEMENTATION_REQUEST,
            requestModel = { messages ->
                calls++
                if (calls == 1) Result.success("""{"tool":"write_file","path":"big.txt","content":"$big"}""")
                else { secondTurn = messages.joined(); Result.success("ok") }
            },
            confirmMutation = { _, _, _ -> true }
        )
        assertFalse(secondTurn.contains(big))
        assertTrue(secondTurn.contains("5000 caracteres omitidos"))
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
