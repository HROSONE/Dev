package com.example.data.engine

import com.example.data.model.RepoFile

/**
 * Compares workspace contents against the last known remote baseline.
 *
 * A missing baseline entry means the file is new and therefore modified.
 * A null baseline means no repository is connected, so no remote change
 * status should be inferred.
 */
object WorkspaceChangeTracker {
    fun markChanges(
        files: List<RepoFile>,
        baseline: Map<String, String>?
    ): List<RepoFile> {
        if (baseline == null) return files
        return files.map { file ->
            file.copy(isModified = baseline[file.path] != file.content)
        }
    }
}
