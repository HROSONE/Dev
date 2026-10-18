package com.example.data.engine

import com.example.data.model.RepoFile
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceChangeTrackerTest {
    @Test
    fun unchangedFilesAreNotMarkedModified() {
        val files = listOf(RepoFile("src/Main.kt", "kotlin", "same", isModified = true))

        val result = WorkspaceChangeTracker.markChanges(
            files,
            mapOf("src/Main.kt" to "same")
        )

        assertEquals(false, result.single().isModified)
    }

    @Test
    fun changedAndNewFilesAreMarkedModified() {
        val files = listOf(
            RepoFile("src/Main.kt", "kotlin", "new content"),
            RepoFile("src/New.kt", "kotlin", "new file")
        )

        val result = WorkspaceChangeTracker.markChanges(
            files,
            mapOf("src/Main.kt" to "old content")
        )

        assertEquals(listOf(true, true), result.map { it.isModified })
    }

    @Test
    fun missingRemoteFilesAreNotInventedInWorkspaceListing() {
        val files = listOf(RepoFile("README.md", "markdown", "unchanged"))

        val result = WorkspaceChangeTracker.markChanges(files, emptyMap())

        assertEquals(true, result.single().isModified)
    }

    @Test
    fun noRepositoryBaselineDoesNotInferChanges() {
        val files = listOf(RepoFile("src/Main.kt", "kotlin", "content"))

        val result = WorkspaceChangeTracker.markChanges(files, null)

        assertEquals(files, result)
    }
}
