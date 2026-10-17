package com.example.data.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceCommandRunnerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun pwdRunsInsideWorkspace() {
        val root = temporaryFolder.newFolder("workspace")
        val result = WorkspaceCommandRunner(root).run("pwd")
        assertEquals(0, result.exitCode)
        assertEquals(root.canonicalPath, result.output.trim())
    }

    @Test
    fun lsAndCatAreLimitedToWorkspaceFiles() {
        val root = temporaryFolder.newFolder("workspace")
        File(root, "hello.txt").writeText("hello workspace")
        val runner = WorkspaceCommandRunner(root)

        assertTrue(runner.run("ls").output.contains("hello.txt"))
        assertEquals("hello workspace", runner.run("cat hello.txt").output.trim())
    }

    @Test
    fun rejectsShellAndUnknownCommands() {
        val runner = WorkspaceCommandRunner(temporaryFolder.newFolder("workspace"))
        assertFalse(runCatching { runner.run("sh -c id") }.isSuccess)
        assertFalse(runCatching { runner.run("pwd; cat secret") }.isSuccess)
        assertFalse(runCatching { runner.run("rm -rf workspace") }.isSuccess)
    }

    @Test
    fun rejectsPathTraversal() {
        val root = temporaryFolder.newFolder("workspace")
        val outside = File(temporaryFolder.root, "outside.txt")
        outside.writeText("secret")
        val runner = WorkspaceCommandRunner(root)
        assertFalse(runCatching { runner.run("cat ../outside.txt") }.isSuccess)
    }

    @Test
    fun outputIsBounded() {
        val root = temporaryFolder.newFolder("workspace")
        File(root, "large.txt").writeText("x".repeat(100_000))
        val result = WorkspaceCommandRunner(root, maxOutputBytes = 100).run("cat large.txt")
        assertTrue(result.output.length <= 100)
        assertTrue(result.outputTruncated)
    }
}
