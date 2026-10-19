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
    fun readFileLinesReturnsNumberedWindow() {
        val root = temporaryFolder.newFolder("workspace")
        java.io.File(root, "src").mkdirs()
        java.io.File(root, "src/Lines.kt").writeText("line1\nline2\nline3\nline4\n")
        val tools = WorkspaceFileTools(root)

        val readLines = tools.readFileLines("src/Lines.kt", 2, 3, AgentMode.ASK, ChatIntent.EXPLANATION)
        assertTrue(readLines is ToolDispatchResult.Executed)
        val output = (readLines as ToolDispatchResult.Executed).value
        assertTrue(output.contains("   2 | line2"))
        assertTrue(output.contains("   3 | line3"))
    }

    @Test
    fun findSymbolAndSyntaxVerifierWorkOnWorkspace() {
        val root = temporaryFolder.newFolder("workspace")
        java.io.File(root, "src").mkdirs()
        java.io.File(root, "src/Engine.kt").writeText(
            "package demo\n\nclass SuperEngine {\n    fun executeTask(): Boolean {\n        return true\n    }\n}\n"
        )
        val tools = WorkspaceFileTools(root)

        val symbolSearch = tools.findSymbol("executeTask", ".", AgentMode.ASK, ChatIntent.EXPLANATION)
        assertTrue(symbolSearch is ToolDispatchResult.Executed)
        assertTrue((symbolSearch as ToolDispatchResult.Executed).value.contains("src/Engine.kt:4"))

        val syntaxOk = tools.verifySyntax("src/Engine.kt", AgentMode.ASK, ChatIntent.EXPLANATION)
        assertTrue(syntaxOk is ToolDispatchResult.Executed)
        assertTrue((syntaxOk as ToolDispatchResult.Executed).value.contains("Sintaxe verificada OK"))
    }

    @Test
    fun patchLinesReplacesTargetRangeAndRunsSyntaxCheck() {
        val root = temporaryFolder.newFolder("workspace")
        java.io.File(root, "src").mkdirs()
        java.io.File(root, "src/Patch.kt").writeText("fun a() = 1\nfun b() = 2\nfun c() = 3\n")
        val tools = WorkspaceFileTools(root)

        val patched = tools.patchLines(
            relativePath = "src/Patch.kt",
            startLine = 2,
            endLine = 2,
            newText = "fun b() = 99",
            mode = AgentMode.BUILD,
            intent = ChatIntent.IMPLEMENTATION_REQUEST,
            userConfirmedMutation = true
        )
        assertTrue(patched is ToolDispatchResult.Executed)
        assertEquals("fun a() = 1\nfun b() = 99\nfun c() = 3\n", java.io.File(root, "src/Patch.kt").readText())
    }

    @Test
    fun searchFilesFindsMatchingLinesAcrossWorkspace() {
        val root = temporaryFolder.newFolder("workspace")
        java.io.File(root, "src").mkdirs()
        java.io.File(root, "src/Hello.kt").writeText("package demo\nfun greetUser() = \"olá\"")
        val tools = WorkspaceFileTools(root)

        val search = tools.searchFiles("greetUser", ".", AgentMode.ASK, ChatIntent.EXPLANATION)
        assertTrue(search is ToolDispatchResult.Executed)
        assertTrue((search as ToolDispatchResult.Executed).value.contains("src/Hello.kt:2:"))
    }

    @Test
    fun surgicalEditFileReplacesExactTargetWhenConfirmed() {
        val root = temporaryFolder.newFolder("workspace")
        java.io.File(root, "src").mkdirs()
        java.io.File(root, "src/Hello.kt").writeText("fun a() = 1\nfun b() = 2\n")
        val tools = WorkspaceFileTools(root)

        val result = tools.editFile(
            relativePath = "src/Hello.kt",
            oldText = "fun b() = 2",
            newText = "fun b() = 42",
            mode = AgentMode.BUILD,
            intent = ChatIntent.IMPLEMENTATION_REQUEST,
            userConfirmedMutation = true
        )

        assertTrue(result is ToolDispatchResult.Executed)
        assertEquals("fun a() = 1\nfun b() = 42\n", java.io.File(root, "src/Hello.kt").readText())
    }

    @Test
    fun surgicalEditFileHandlesIndentationDifferenceGracefully() {
        val root = temporaryFolder.newFolder("workspace")
        java.io.File(root, "src").mkdirs()
        java.io.File(root, "src/Indented.kt").writeText("class Demo {\n        fun compute() = 10\n}\n")
        val tools = WorkspaceFileTools(root)

        val result = tools.editFile(
            relativePath = "src/Indented.kt",
            oldText = "  fun compute() = 10",
            newText = "    fun compute() = 20",
            mode = AgentMode.BUILD,
            intent = ChatIntent.IMPLEMENTATION_REQUEST,
            userConfirmedMutation = true
        )

        assertTrue(result is ToolDispatchResult.Executed)
        assertTrue(java.io.File(root, "src/Indented.kt").readText().contains("fun compute() = 20"))
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
