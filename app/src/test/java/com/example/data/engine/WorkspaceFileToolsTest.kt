package com.example.data.engine

import com.example.data.model.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceFileToolsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readAndListUseWorkspaceFiles() {
        val root = temporaryFolder.newFolder("workspace")
        java.io.File(root, "src").mkdirs()
        java.io.File(root, "src/Hello.kt").writeText("fun hello() = \"hi\"")
        val tools = WorkspaceFileTools(root)

        val listed = tools.listFiles(mode = AgentMode.ASK, intent = ChatIntent.EXPLANATION)
        assertTrue(listed is ToolDispatchResult.Executed)
        assertEquals(listOf("src/Hello.kt"), (listed as ToolDispatchResult.Executed).value)

        val read = tools.readFile("src/Hello.kt", AgentMode.ASK, ChatIntent.EXPLANATION)
        assertEquals("fun hello() = \"hi\"", (read as ToolDispatchResult.Executed).value)
    }

    @Test
    fun mutationRequiresExplicitConfirmation() {
        val root = temporaryFolder.newFolder("workspace")
        val tools = WorkspaceFileTools(root)

        val result = tools.writeFile(
            "new.txt", "content", AgentMode.BUILD,
            ChatIntent.IMPLEMENTATION_REQUEST, userConfirmedMutation = false
        )

        assertTrue(result is ToolDispatchResult.ConfirmationRequired)
        assertFalse(java.io.File(root, "new.txt").exists())
    }

    @Test
    fun confirmedImplementationWritesFile() {
        val root = temporaryFolder.newFolder("workspace")
        val tools = WorkspaceFileTools(root)

        val result = tools.writeFile(
            "src/new.txt", "content", AgentMode.BUILD,
            ChatIntent.IMPLEMENTATION_REQUEST, userConfirmedMutation = true
        )

        assertTrue(result is ToolDispatchResult.Executed)
        assertEquals("content", java.io.File(root, "src/new.txt").readText())
    }

    @Test
    fun traversalIsRejected() {
        val root = temporaryFolder.newFolder("workspace")
        val tools = WorkspaceFileTools(root)

        try {
            tools.readFile("../outside.txt", AgentMode.ASK, ChatIntent.EXPLANATION)
            throw AssertionError("Traversal should be rejected")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("sai dos limites"))
        }
    }

    @Test
    fun ambiguousMutationIsDeniedEvenWhenConfirmed() {
        val root = temporaryFolder.newFolder("workspace")
        val tools = WorkspaceFileTools(root)

        val result = tools.writeFile(
            "new.txt", "content", AgentMode.BUILD,
            ChatIntent.AMBIGUOUS, userConfirmedMutation = true
        )

        assertTrue(result is ToolDispatchResult.Denied)
        assertFalse(java.io.File(root, "new.txt").exists())
    }
}
