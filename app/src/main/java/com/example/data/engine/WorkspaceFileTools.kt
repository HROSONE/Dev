package com.example.data.engine

import com.example.data.model.AgentMode
import java.io.File
import java.io.IOException

/**
 * File tools for an explicitly supplied, app-owned workspace directory.
 *
 * Supports listing, reading, searching (grep), surgical search-and-replace editing
 * (Claude Code style), full file writes, and file deletion within the canonical workspace root.
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
            throw IOException("O diretório solicitado não existe: $relativePath")
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
        if (!file.exists() || !file.isFile) {
            throw IOException("O arquivo solicitado não existe: $relativePath. Use list_files ou search_files para localizar o caminho correto.")
        }
        file.readText(Charsets.UTF_8)
    }

    fun searchFiles(
        query: String,
        relativePath: String = ".",
        mode: AgentMode,
        intent: ChatIntent,
        maxMatches: Int = 100
    ): ToolDispatchResult<String> = dispatcher.dispatch(
        mode = mode,
        intent = intent,
        risk = ToolRisk.READ_ONLY
    ) {
        if (query.isBlank()) throw IOException("A consulta de busca está vazia.")
        val target = resolve(relativePath)
        if (!target.exists()) {
            throw IOException("O caminho solicitado para busca não existe: $relativePath")
        }
        val regex = runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
        val matches = mutableListOf<String>()
        val files = if (target.isFile) listOf(target) else {
            target.walkTopDown()
                .onEnter { it.canonicalFile == root || it.canonicalFile.toPath().startsWith(root.toPath()) }
                .filter { it.isFile }
                .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        }

        for (file in files) {
            if (matches.size >= maxMatches) break
            val relPath = file.relativeTo(root).invariantSeparatorsPath
            val text = try {
                file.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                continue
            }
            if (text.indexOf('\u0000') >= 0) continue
            text.lines().forEachIndexed { idx, line ->
                if (matches.size < maxMatches) {
                    val matched = regex?.containsMatchIn(line) ?: line.contains(query, ignoreCase = true)
                    if (matched) {
                        matches += "$relPath:${idx + 1}: ${line.trim().take(200)}"
                    }
                }
            }
        }

        if (matches.isEmpty()) {
            "Nenhuma ocorrência encontrada para '$query' em $relativePath."
        } else {
            val suffix = if (matches.size >= maxMatches) "\n… (limite de $maxMatches ocorrências atingido)" else ""
            matches.joinToString("\n") + suffix
        }
    }

    /**
     * Computes the resulting content of an edit_file operation without modifying disk yet.
     * Used to generate accurate diff previews before confirmation.
     */
    fun previewEditFile(
        relativePath: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean = false
    ): String {
        val file = resolve(relativePath)
        if (!file.exists() || !file.isFile) {
            throw IOException("O arquivo solicitado para edição não existe: $relativePath")
        }
        val current = file.readText(Charsets.UTF_8)
        return applySurgicalEdit(current, relativePath, oldText, newText, replaceAll)
    }

    /**
     * Surgical search-and-replace inside an existing file (Claude Code style).
     */
    fun editFile(
        relativePath: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean = false,
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
        if (!file.exists() || !file.isFile) {
            throw IOException("O arquivo solicitado para edição não existe: $relativePath")
        }
        val current = file.readText(Charsets.UTF_8)
        val updated = applySurgicalEdit(current, relativePath, oldText, newText, replaceAll)
        file.writeText(updated, Charsets.UTF_8)
        "Arquivo editado: ${file.relativeTo(root).invariantSeparatorsPath}"
    }

    private fun applySurgicalEdit(
        current: String,
        relativePath: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean
    ): String {
        val normalizedCurrent = current.replace("\r\n", "\n")
        val normalizedOld = oldText.replace("\r\n", "\n")
        val normalizedNew = newText.replace("\r\n", "\n")

        if (normalizedOld.isEmpty()) {
            throw IOException("O parâmetro old_text não pode ser vazio.")
        }
        val firstIndex = normalizedCurrent.indexOf(normalizedOld)
        if (firstIndex < 0) {
            throw IOException(
                "Trecho old_text não encontrado em $relativePath. Use read_file para conferir o conteúdo exato antes de chamar edit_file."
            )
        }
        if (!replaceAll) {
            val secondIndex = normalizedCurrent.indexOf(normalizedOld, startIndex = firstIndex + normalizedOld.length)
            if (secondIndex >= 0) {
                throw IOException(
                    "Trecho old_text aparece mais de uma vez em $relativePath. Inclua mais linhas de contexto em old_text ou defina replace_all=true."
                )
            }
            return normalizedCurrent.replaceFirst(normalizedOld, normalizedNew)
        }
        return normalizedCurrent.replace(normalizedOld, normalizedNew)
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

    fun deleteFile(
        relativePath: String,
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
        if (!file.exists() || !file.isFile) {
            throw IOException("O arquivo solicitado para exclusão não existe: $relativePath")
        }
        if (!file.delete()) {
            throw IOException("Não foi possível remover o arquivo: $relativePath")
        }
        "Arquivo removido: ${file.relativeTo(root).invariantSeparatorsPath}"
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
