package com.example.data.engine

import com.example.data.model.AgentMode
import java.io.File
import java.io.IOException

/**
 * File tools for an explicitly supplied, app-owned workspace directory.
 *
 * This is not a GitHub writer or a shell. Callers must provide a workspace root
 * that the app is authorized to modify. All paths are resolved and checked against
 * the canonical root to reject absolute paths and traversal/symlink escapes.
 */
class WorkspaceFileTools(
    workspaceRoot: File,
    private val dispatcher: PolicyGatedToolDispatcher = PolicyGatedToolDispatcher()
) {
    private val root: File = workspaceRoot.canonicalFile

    init {
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Não foi possível criar o diretório de trabalho.")
        }
        if (!root.isDirectory) throw IOException("A raiz do workspace não é um diretório.")
    }

    fun listFiles(
        relativePath: String = ".",
        mode: AgentMode,
        intent: ChatIntent
    ): ToolDispatchResult<List<String>> = dispatcher.dispatch(
        mode = mode,
        intent = intent,
        risk = ToolRisk.READ_ONLY
    ) {
        val directory = resolve(relativePath)
        if (!directory.exists() || !directory.isDirectory) {
            throw IOException("O diretório solicitado não existe.")
        }
        directory.walkTopDown()
            .onEnter { it.canonicalFile == root || it.canonicalFile.toPath().startsWith(root.toPath()) }
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()
    }

    fun readFile(
        relativePath: String,
        mode: AgentMode,
        intent: ChatIntent
    ): ToolDispatchResult<String> = dispatcher.dispatch(
        mode = mode,
        intent = intent,
        risk = ToolRisk.READ_ONLY
    ) {
        val file = resolve(relativePath)
        if (!file.exists() || !file.isFile) throw IOException("O arquivo solicitado não existe.")
        file.readText(Charsets.UTF_8)
    }

    /**
     * Writes only after the existing policy confirms a concrete BUILD implementation
     * request and the caller passes a separate confirmation signal.
     */
    fun writeFile(
        relativePath: String,
        content: String,
        mode: AgentMode,
        intent: ChatIntent,
        userConfirmedMutation: Boolean
    ): ToolDispatchResult<String> = dispatcher.dispatch(
        mode = mode,
        intent = intent,
        risk = ToolRisk.MUTATING,
        userConfirmedMutation = userConfirmedMutation
    ) {
        val file = resolve(relativePath)
        val parent = file.parentFile ?: throw IOException("Caminho de arquivo inválido.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Não foi possível criar a pasta de destino.")
        if (!parent.isDirectory) throw IOException("A pasta de destino não é um diretório.")
        file.writeText(content, Charsets.UTF_8)
        "Arquivo salvo: ${file.relativeTo(root).invariantSeparatorsPath}"
    }

    private fun resolve(relativePath: String): File {
        if (relativePath.isBlank()) throw IOException("O caminho está vazio.")
        val supplied = File(relativePath)
        if (supplied.isAbsolute) throw IOException("Caminhos absolutos não são permitidos.")
        val resolved = File(root, relativePath).canonicalFile
        if (resolved != root && !resolved.toPath().startsWith(root.toPath())) {
            throw IOException("O caminho solicitado sai dos limites do workspace.")
        }
        return resolved
    }
}
