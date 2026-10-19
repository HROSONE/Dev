package com.example.data.engine

import com.example.data.model.AgentMode
import java.io.File
import java.io.IOException

/**
 * Ferramentas de arquivo do Harness SWE-Agent para o diretório de trabalho isolado.
 *
 * Inclui:
 * - list_files, read_file, read_file_lines (leitura paginada com números de linha)
 * - search_files (grep regex/texto), find_symbol (indexador de definições e referências)
 * - edit_file (busca e substituição cirúrgica com fallback tolerante a indentação)
 * - patch_lines (edição cirúrgica por intervalo de linhas start_line..end_line)
 * - write_file, delete_file e verify_syntax (validação estática de delimitadores e truncamento)
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
            throw IOException("O arquivo solicitado não existe: $relativePath. Use list_files ou find_symbol para localizar o caminho correto.")
        }
        file.readText(Charsets.UTF_8)
    }

    fun readFileLines(
        relativePath: String,
        startLine: Int,
        endLine: Int,
        mode: AgentMode,
        intent: ChatIntent
    ): ToolDispatchResult<String> = dispatcher.dispatch(
        mode = mode,
        intent = intent,
        risk = ToolRisk.READ_ONLY
    ) {
        val file = resolve(relativePath)
        if (!file.exists() || !file.isFile) {
            throw IOException("O arquivo solicitado não existe: $relativePath.")
        }
        val lines = file.readText(Charsets.UTF_8).replace("\r\n", "\n").lines()
        if (lines.isEmpty()) return@dispatch "(Arquivo vazio: $relativePath)"
        val safeStart = startLine.coerceIn(1, lines.size)
        val safeEnd = endLine.coerceIn(safeStart, lines.size)
        val width = safeEnd.toString().length.coerceAtLeast(3)
        val slice = (safeStart..safeEnd).joinToString("\n") { lineNum ->
            val numStr = lineNum.toString().padStart(width, ' ')
            "$numStr | ${lines[lineNum - 1]}"
        }
        "Arquivo: $relativePath (linhas $safeStart..$safeEnd de ${lines.size})\n$slice"
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

    fun findSymbol(
        query: String,
        mode: AgentMode,
        intent: ChatIntent
    ): ToolDispatchResult<String> = dispatcher.dispatch(
        mode = mode,
        intent = intent,
        risk = ToolRisk.READ_ONLY
    ) {
        RepoSymbolIndexer.findSymbolsInDirectory(root, query)
    }

    fun verifySyntax(
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
            throw IOException("O arquivo solicitado para verificação não existe: $relativePath")
        }
        val check = SyntaxVerifier.verify(relativePath, file.readText(Charsets.UTF_8))
        check.summary
    }

    fun verifySyntaxDirect(relativePath: String): SyntaxCheckResult? {
        return runCatching {
            val file = resolve(relativePath)
            if (!file.isFile) return null
            SyntaxVerifier.verify(relativePath, file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

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

    fun previewPatchLines(
        relativePath: String,
        startLine: Int,
        endLine: Int,
        newText: String
    ): String {
        val file = resolve(relativePath)
        if (!file.exists() || !file.isFile) {
            throw IOException("O arquivo solicitado para patch_lines não existe: $relativePath")
        }
        val current = file.readText(Charsets.UTF_8)
        return applyLinePatch(current, relativePath, startLine, endLine, newText)
    }

    fun patchLines(
        relativePath: String,
        startLine: Int,
        endLine: Int,
        newText: String,
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
            throw IOException("O arquivo solicitado para patch_lines não existe: $relativePath")
        }
        val current = file.readText(Charsets.UTF_8)
        val updated = applyLinePatch(current, relativePath, startLine, endLine, newText)
        file.writeText(updated, Charsets.UTF_8)
        "Linhas $startLine..$endLine atualizadas em: ${file.relativeTo(root).invariantSeparatorsPath}"
    }

    private fun applyLinePatch(
        current: String,
        relativePath: String,
        startLine: Int,
        endLine: Int,
        newText: String
    ): String {
        val lines = current.replace("\r\n", "\n").lines().toMutableList()
        if (startLine < 1 || startLine > lines.size + 1 || endLine < startLine) {
            throw IOException("Intervalo de linhas inválido ($startLine..$endLine) para $relativePath (${lines.size} linhas).")
        }
        val safeStartIdx = (startLine - 1).coerceIn(0, lines.size)
        val safeEndIdx = endLine.coerceIn(safeStartIdx, lines.size)
        val replacementLines = if (newText.isEmpty()) emptyList() else newText.replace("\r\n", "\n").lines()
        val prefix = lines.subList(0, safeStartIdx)
        val suffix = lines.subList(safeEndIdx, lines.size)
        return (prefix + replacementLines + suffix).joinToString("\n")
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
        if (firstIndex >= 0) {
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

        // Fallback inteligente do Harness: matching tolerante a diferenças de indentação/espaços nas pontas
        val fuzzyResult = tryIndentationTolerantReplace(normalizedCurrent, normalizedOld, normalizedNew)
        if (fuzzyResult != null) return fuzzyResult

        throw IOException(
            "Trecho old_text não encontrado em $relativePath. Use read_file_lines ou read_file para conferir o conteúdo exato (ou use patch_lines com start_line/end_line)."
        )
    }

    private fun tryIndentationTolerantReplace(
        current: String,
        oldText: String,
        newText: String
    ): String? {
        val currentLines = current.lines()
        val oldLines = oldText.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (oldLines.isEmpty() || oldLines.size > currentLines.size) return null

        var matchStart = -1
        var matchEnd = -1
        var matchCount = 0

        for (i in currentLines.indices) {
            if (currentLines[i].trim() == oldLines.first()) {
                var curIdx = i
                var oldIdx = 0
                while (curIdx < currentLines.size && oldIdx < oldLines.size) {
                    val cTrim = currentLines[curIdx].trim()
                    if (cTrim.isEmpty()) {
                        curIdx++
                        continue
                    }
                    if (cTrim != oldLines[oldIdx]) break
                    oldIdx++
                    curIdx++
                }
                if (oldIdx == oldLines.size) {
                    matchStart = i
                    matchEnd = curIdx
                    matchCount++
                }
            }
        }

        if (matchCount != 1 || matchStart < 0) return null
        val before = currentLines.subList(0, matchStart)
        val after = currentLines.subList(matchEnd, currentLines.size)
        val newLines = newText.lines()
        return (before + newLines + after).joinToString("\n")
    }

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
