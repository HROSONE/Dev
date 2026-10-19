package com.example.data.engine

import com.example.data.model.RepoFile
import java.io.File

/**
 * Indexador de símbolos e gerador de RepoMap (estilo Aider / Claude Code / SWE-agent).
 * Extrai assinaturas de classes, interfaces, objetos, funções e estados de todo o workspace
 * para fornecer ao modelo de linguagem um mapa arquitetural de alta densidade e baixo custo de tokens.
 */
object RepoSymbolIndexer {

    private val declarationRegex = Regex(
        """^\s*(?:(?:public|private|protected|internal|open|abstract|sealed|data|enum|value|inline|suspend|override|actual|expect|Composable|@Composable)\s+)*(class|interface|object|fun|val\s+\w+\s*=|def|function|export\s+(?:default\s+)?(?:class|function|interface|type|const))\s+([A-Za-z_][A-Za-z0-9_<>]*)"""
    )

    /**
     * Constrói o RepoMap compacto de todo o projeto com linhas exatas de cada símbolo.
     */
    fun buildRepoMap(files: List<RepoFile>, maxChars: Int = 24_000): String {
        if (files.isEmpty()) return "(Workspace vazio — pronto para criar novo projeto do zero)"
        val sb = StringBuilder()
        sb.append("MAPA ARQUITETURAL DO PROJETO (RepoMap — ${files.size} arquivos):\n")

        for (file in files) {
            if (sb.length >= maxChars) {
                sb.append("… (demais arquivos disponíveis via list_files / find_symbol)\n")
                break
            }
            val lines = file.content.lines()
            val modBadge = if (file.isModified) " [MODIFICADO]" else ""
            sb.append("• ").append(file.path).append(" (").append(lines.size).append("L)").append(modBadge).append("\n")

            var symbolsInFile = 0
            lines.forEachIndexed { idx, rawLine ->
                if (symbolsInFile < 18) {
                    val trimmed = rawLine.trim()
                    if (declarationRegex.containsMatchIn(trimmed) && !trimmed.startsWith("//") && !trimmed.startsWith("*")) {
                        val signature = trimmed
                            .substringBefore("{")
                            .substringBefore("=")
                            .trim()
                            .take(95)
                        if (signature.isNotBlank()) {
                            sb.append("    L").append(idx + 1).append(": ").append(signature).append("\n")
                            symbolsInFile++
                        }
                    }
                }
            }
        }
        return sb.toString().take(maxChars)
    }

    /**
     * Busca definições e referências de um símbolo (classe, função, método, propriedade) no workspace.
     */
    fun findSymbolsInDirectory(root: File, query: String, maxResults: Int = 80): String {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return "Informe o nome do símbolo para buscar."
        val definitions = mutableListOf<String>()
        val references = mutableListOf<String>()

        val files = root.walkTopDown()
            .onEnter { it.canonicalFile == root || it.canonicalFile.toPath().startsWith(root.toPath()) }
            .filter { it.isFile }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .toList()

        for (file in files) {
            val text = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { continue }
            if (text.indexOf('\u0000') >= 0) continue
            val relPath = file.relativeTo(root).invariantSeparatorsPath
            text.lines().forEachIndexed { idx, rawLine ->
                val trimmed = rawLine.trim()
                if (trimmed.contains(cleanQuery, ignoreCase = true)) {
                    val entry = "$relPath:${idx + 1}: ${trimmed.take(160)}"
                    if (declarationRegex.containsMatchIn(trimmed)) {
                        if (definitions.size < maxResults) definitions += entry
                    } else {
                        if (references.size < maxResults) references += entry
                    }
                }
            }
        }

        if (definitions.isEmpty() && references.isEmpty()) {
            return "Nenhum símbolo ou referência encontrado para '$cleanQuery'."
        }

        return buildString {
            if (definitions.isNotEmpty()) {
                append("=== DEFINIÇÕES (${definitions.size}) ===\n")
                append(definitions.take(40).joinToString("\n"))
                append("\n")
            }
            if (references.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("=== REFERÊNCIAS (${references.size}) ===\n")
                append(references.take(40).joinToString("\n"))
            }
        }
    }
}
