package com.example.data.engine

import com.example.data.model.AgentMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentToolCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun coordinator(): Pair<AgentToolCoordinator, File> {
        val root = temporaryFolder.newFolder()
        File(root, "src").mkdirs()
        File(root, "src/Main.kt").writeText("fun main() = Unit")
        return AgentToolCoordinator(WorkspaceFileTools(root)) to root
    }

    @Test
    fun executesAllowedReadRequestAndReturnsActualWorkspaceContent() {
        val (coordinator) = coordinator()

        val result = coordinator.execute(
            """{"tool":"read_file","path":"src/Main.kt"}""",
            AgentMode.ASK,
            ChatIntent.EXPLANATION
        )

        assertEquals(
            AgentToolExecution.Completed("read_file", "fun main() = Unit"),
            result
        )
    }

    @Test
    fun dispatchesSafeCommandAndReturnsRealOutput() {
        val root = temporaryFolder.newFolder("command-workspace")
        val coordinator = AgentToolCoordinator(WorkspaceFileTools(root), WorkspaceCommandRunner(root))
        val result = coordinator.execute(
            """{"tool":"run_command","command":"pwd"}""",
            AgentMode.ASK,
            ChatIntent.EXPLANATION
        )
        assertTrue(result is AgentToolExecution.Completed)
        assertTrue((result as AgentToolExecution.Completed).output.contains(root.canonicalPath))
    }

    @Test
    fun readOnlyCommandIsAllowedEvenForAmbiguousIntent() {
        val root = temporaryFolder.newFolder("ambiguous-workspace")
        val coordinator = AgentToolCoordinator(WorkspaceFileTools(root), WorkspaceCommandRunner(root))
        val result = coordinator.execute(
            """{"tool":"run_command","command":"pwd"}""",
            AgentMode.ASK,
            ChatIntent.AMBIGUOUS
        )
        assertTrue(result is AgentToolExecution.Completed)
    }

    @Test
    fun ordinaryModelTextIsNotExecutedAsATool() {
        val (coordinator) = coordinator()
        val result = coordinator.execute("A resposta é 391.", AgentMode.ASK, ChatIntent.DISCUSSION)
        assertEquals(AgentToolExecution.NotToolRequest("A resposta é 391."), result)
    }

    @Test
    fun writeRequestRequiresSeparateConfirmationAndDoesNotWriteBeforeIt() {
        val (coordinator, root) = coordinator()
        val request = """{"tool":"write_file","path":"new.txt","content":"safe"}"""

        val result = coordinator.execute(
            request,
            AgentMode.BUILD,
            ChatIntent.IMPLEMENTATION_REQUEST
        )

        assertEquals(AgentToolExecution.NeedsConfirmation("write_file", "new.txt", "safe"), result)
        assertFalse(File(root, "new.txt").exists())
    }

    @Test
    fun confirmedWriteStillRequiresBuildModeAndImplementationIntent() {
        val (coordinator, root) = coordinator()
        val result = coordinator.execute(
            """{"tool":"write_file","path":"new.txt","content":"unsafe"}""",
            AgentMode.ASK,
            ChatIntent.EXPLANATION,
            userConfirmedMutation = true
        )

        assertTrue(result is AgentToolExecution.Rejected)
        assertFalse(File(root, "new.txt").exists())
    }

    @Test
    fun confirmedImplementationCanWriteWithinWorkspace() {
        val (coordinator, root) = coordinator()
        val result = coordinator.execute(
            """{"tool":"write_file","path":"src/New.kt","content":"fun newFile() = Unit"}""",
            AgentMode.BUILD,
            ChatIntent.IMPLEMENTATION_REQUEST,
            userConfirmedMutation = true
        )

        assertEquals(
            AgentToolExecution.Completed("write_file", "Arquivo salvo: src/New.kt"),
            result
        )
        assertEquals("fun newFile() = Unit", File(root, "src/New.kt").readText())
    }

    @Test
    fun readOutputIsBounded() {
        val (coordinator, root) = coordinator()
        File(root, "large.txt").writeText("x".repeat(100))

        val result = coordinator.execute(
            """{"tool":"read_file","path":"large.txt"}""",
            AgentMode.ASK,
            ChatIntent.EXPLANATION
        )

        assertEquals(
            AgentToolExecution.Completed(
                "read_file",
                "xxxxxxxxxxxxxxxxxxxx\n[Conteúdo truncado pelo limite de saída.]"
            ),
            AgentToolCoordinator(WorkspaceFileTools(root), maxOutputChars = 20).execute(
                """{"tool":"read_file","path":"large.txt"}""",
                AgentMode.ASK,
                ChatIntent.EXPLANATION
            )
        )
        assertTrue(result is AgentToolExecution.Completed)
    }
}
