package com.example.data.engine

import com.example.data.model.AgentMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun readResultReturnsToModelBeforeFinalAnswer() = runBlocking {
        val (coordinator) = coordinator()
        var calls = 0
        var secondPrompt = ""
        val result = AgentToolLoop(coordinator).run(
            "Read the file", AgentMode.ASK, ChatIntent.EXPLANATION,
            requestModel = { prompt ->
                calls++
                if (calls == 1) Result.success("""{"tool":"read_file","path":"src/Main.kt"}""")
                else { secondPrompt = prompt; Result.success("The file defines an empty main function.") }
            }
        )
        assertEquals(AgentLoopResult.FinalAnswer("The file defines an empty main function.", 1), result)
        assertTrue(secondPrompt.contains("fun main() = Unit"))
        assertTrue(secondPrompt.contains("não confiável"))
    }

    @Test fun writeRequiresExplicitApproval() = runBlocking {
        val (coordinator, root) = coordinator()
        val result = AgentToolLoop(coordinator).run(
            "Create file", AgentMode.BUILD, ChatIntent.IMPLEMENTATION_REQUEST,
            requestModel = { Result.success("""{"tool":"write_file","path":"new.txt","content":"hello"}""") }
        )
        assertEquals(AgentLoopResult.ConfirmationRequired("write_file", "new.txt", 0), result)
        assertFalse(File(root, "new.txt").exists())
    }

    @Test fun approvedWriteContinuesToFinalAnswer() = runBlocking {
        val (coordinator, root) = coordinator()
        var calls = 0
        val result = AgentToolLoop(coordinator).run(
            "Create file", AgentMode.BUILD, ChatIntent.IMPLEMENTATION_REQUEST,
            requestModel = {
                calls++
                Result.success(
                    if (calls == 1) """{"tool":"write_file","path":"new.txt","content":"hello"}"""
                    else "File created."
                )
            },
            confirmMutation = { name, path, _ -> name == "write_file" && path == "new.txt" }
        )
        assertEquals(AgentLoopResult.FinalAnswer("File created.", 1), result)
        assertEquals("hello", File(root, "new.txt").readText())
    }

    @Test fun toolLimitStillAllowsOneFinalAnswer() = runBlocking {
        val (coordinator) = coordinator()
        var calls = 0
        val result = AgentToolLoop(coordinator, maxToolCalls = 1).run(
            "Read file", AgentMode.ASK, ChatIntent.EXPLANATION,
            requestModel = { prompt ->
                calls++
                if (calls == 1) Result.success("""{"tool":"read_file","path":"src/Main.kt"}""")
                else {
                    assertTrue(prompt.contains("Limite de ferramentas atingido"))
                    Result.success("Done.")
                }
            }
        )
        assertEquals(AgentLoopResult.FinalAnswer("Done.", 1), result)
        assertEquals(2, calls)
    }

    @Test fun anotherToolRequestAfterLimitFailsClosed() = runBlocking {
        val (coordinator) = coordinator()
        var calls = 0
        val result = AgentToolLoop(coordinator, maxToolCalls = 1).run(
            "Read file", AgentMode.ASK, ChatIntent.EXPLANATION,
            requestModel = { calls++; Result.success("""{"tool":"read_file","path":"src/Main.kt"}""") }
        )
        assertTrue(result is AgentLoopResult.Failed)
        assertEquals(2, calls)
    }
}
