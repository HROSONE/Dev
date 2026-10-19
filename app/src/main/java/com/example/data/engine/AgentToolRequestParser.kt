package com.example.data.engine

import com.squareup.moshi.JsonReader
import okio.Buffer

/**
 * Parser de ferramentas do Harness do DevAgent.
 *
 * Suporta:
 * - Ferramentas clássicas (list_files, read_file, search_files, edit_file, write_file, delete_file, run_command)
 * - Novas ferramentas SWE-Agent de alta precisão (read_file_lines, find_symbol, patch_lines, verify_syntax)
 * - Extração de chamadas únicas ou em lote (multi-tool execution em um mesmo turno)
 * - Extração de raciocínio pré-ferramenta (<thought> ou texto antes do JSON)
 */
sealed class AgentToolRequest {
    data class ListFiles(val path: String = ".") : AgentToolRequest()
    data class ReadFile(val path: String) : AgentToolRequest()
    data class ReadFileLines(
        val path: String,
        val startLine: Int = 1,
        val endLine: Int = 150
    ) : AgentToolRequest()
    data class SearchFiles(val query: String, val path: String = ".") : AgentToolRequest()
    data class FindSymbol(val query: String) : AgentToolRequest()
    data class EditFile(
        val path: String,
        val oldText: String,
        val newText: String,
        val replaceAll: Boolean = false
    ) : AgentToolRequest()
    data class PatchLines(
        val path: String,
        val startLine: Int,
        val endLine: Int,
        val newText: String
    ) : AgentToolRequest()
    data class WriteFile(val path: String, val content: String) : AgentToolRequest()
    data class DeleteFile(val path: String) : AgentToolRequest()
    data class VerifySyntax(val path: String) : AgentToolRequest()
    data class RunCommand(val command: String) : AgentToolRequest()
}

object AgentToolRequestParser {
    private const val MAX_PATH_LENGTH = 1_024
    private const val MAX_CONTENT_LENGTH = 500_000
    private const val MAX_QUERY_LENGTH = 512

    fun parse(response: String): AgentToolRequest? {
        val trimmed = response.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            parseStrictJsonObject(trimmed)?.let { return it }
        }

        val candidates = extractAllCandidateJsonObjects(trimmed)
        for (candidate in candidates) {
            parseStrictJsonObject(candidate)?.let { return it }
        }
        return null
    }

    /**
     * Extrai todas as solicitações válidas de ferramentas presentes na resposta do modelo,
     * permitindo execução em lote (batch) quando o modelo gera vários arquivos ou edições de uma só vez.
     */
    fun parseAll(response: String): List<AgentToolRequest> {
        val trimmed = response.trim()
        if (trimmed.isEmpty()) return emptyList()

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            parseStrictJsonObject(trimmed)?.let { return listOf(it) }
        }

        val candidates = extractAllCandidateJsonObjects(trimmed)
        if (candidates.isEmpty()) return emptyList()
        return candidates.mapNotNull { parseStrictJsonObject(it) }
    }

    fun extractThoughtStep(response: String): String? {
        val trimmed = response.trim()
        if (trimmed.startsWith("{")) return null

        // Suporta tags explícitas <thought>...</thought> ou <plan>...</plan>
        val tagRegex = Regex("""<(?:thought|plan)>([\s\S]*?)</(?:thought|plan)>""", RegexOption.IGNORE_CASE)
        tagRegex.find(trimmed)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let {
            return it.take(350)
        }

        val fenceIdx = trimmed.indexOf("```")
        val braceIdx = Regex("""\{\s*"tool"\s*:""").find(trimmed)?.range?.first ?: -1
        val cutIdx = when {
            fenceIdx > 0 && (braceIdx < 0 || fenceIdx < braceIdx) -> fenceIdx
            braceIdx > 0 -> braceIdx
            else -> -1
        }
        if (cutIdx <= 0) return null
        return trimmed.substring(0, cutIdx)
            .replace(Regex("""</?(?:thought|plan)>""", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.take(350)
    }

    private fun extractAllCandidateJsonObjects(text: String): List<String> {
        val results = mutableListOf<String>()
        val toolStarts = Regex("""\{\s*"tool"\s*:""").findAll(text)
        for (match in toolStarts) {
            val startIdx = match.range.first
            var depth = 0
            var inString = false
            var escaped = false
            for (i in startIdx until text.length) {
                val ch = text[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\' && inString) {
                    escaped = true
                    continue
                }
                if (ch == '"') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (ch == '{') depth++
                    else if (ch == '}') {
                        depth--
                        if (depth == 0) {
                            results += text.substring(startIdx, i + 1)
                            break
                        }
                    }
                }
            }
        }
        return results
    }

    /**
     * Converte um objeto JSON em [AgentToolRequest]. Tolerante a variações comuns de modelos
     * pequenos: quebras de linha cruas dentro de strings, números como string ("10"),
     * argumentos aninhados em "args"/"arguments"/"parameters"/"input" e aliases de campos
     * ("file_path", "old_string", "new_string", "cmd"…). Continua rejeitando caminhos
     * absolutos, ferramentas desconhecidas e conteúdo acima dos limites.
     */
    private fun parseStrictJsonObject(jsonText: String): AgentToolRequest? {
        val root = readJsonObject(jsonText) ?: readJsonObject(escapeRawControlCharsInStrings(jsonText)) ?: return null
        val fields = LinkedHashMap<String, Any?>()
        for (nestedKey in listOf("args", "arguments", "parameters", "input")) {
            (root[nestedKey] as? Map<*, *>)?.forEach { (k, v) -> if (k is String) fields[k] = v }
        }
        root.forEach { (k, v) -> fields[k] = v }

        fun str(vararg keys: String): String? {
            for (key in keys) {
                when (val value = fields[key]) {
                    null -> continue
                    is String -> return value
                    else -> return null
                }
            }
            return null
        }
        fun int(vararg keys: String): Int? {
            for (key in keys) {
                when (val value = fields[key]) {
                    null -> continue
                    is Number -> return value.toInt()
                    is String -> return value.trim().toIntOrNull()
                    else -> return null
                }
            }
            return null
        }
        fun bool(key: String): Boolean = when (val value = fields[key]) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> false
        }

        val tool = (root["tool"] as? String)?.trim()?.lowercase() ?: return null
        val path = str("path", "file_path", "filepath", "file")
        val content = str("content", "text", "file_content")
        val command = str("command", "cmd")
        val query = str("query", "pattern", "symbol")
        val oldText = str("old_text", "old_string", "old")
        val newText = str("new_text", "new_string", "new")
        val replaceAll = bool("replace_all")
        val startLine = int("start_line", "start", "line_start")
        val endLine = int("end_line", "end", "line_end")

        return when (tool) {
            "list_files", "ls", "list_dir" -> {
                val resolvedPath = path ?: "."
                if (!validPath(resolvedPath)) null else AgentToolRequest.ListFiles(resolvedPath)
            }
            "read_file", "read", "view_file" -> {
                if (path == null || !validPath(path)) null
                else if (startLine != null || endLine != null) {
                    val s = (startLine ?: 1).coerceAtLeast(1)
                    AgentToolRequest.ReadFileLines(path, s, (endLine ?: (s + 120)).coerceAtLeast(s))
                } else AgentToolRequest.ReadFile(path)
            }
            "read_file_lines" -> {
                val s = (startLine ?: 1).coerceAtLeast(1)
                val e = (endLine ?: (s + 120)).coerceAtLeast(s)
                if (path == null || !validPath(path)) null else AgentToolRequest.ReadFileLines(path, s, e)
            }
            "search_files", "grep", "search" -> {
                val resolvedPath = path ?: "."
                if (query.isNullOrBlank() || query.length > MAX_QUERY_LENGTH || !validPath(resolvedPath)) null
                else AgentToolRequest.SearchFiles(query, resolvedPath)
            }
            "find_symbol" -> {
                val resolvedQuery = query ?: path
                if (resolvedQuery.isNullOrBlank() || resolvedQuery.length > MAX_QUERY_LENGTH) null
                else AgentToolRequest.FindSymbol(resolvedQuery)
            }
            "edit_file", "edit", "str_replace" -> {
                if (path == null || !validPath(path) ||
                    oldText.isNullOrEmpty() || oldText.length > MAX_CONTENT_LENGTH ||
                    newText == null || newText.length > MAX_CONTENT_LENGTH
                ) null else AgentToolRequest.EditFile(path, oldText, newText, replaceAll)
            }
            "patch_lines" -> {
                val resolvedNew = newText ?: content
                if (path == null || !validPath(path) ||
                    startLine == null || endLine == null || startLine < 1 || endLine < startLine ||
                    resolvedNew == null || resolvedNew.length > MAX_CONTENT_LENGTH
                ) null else AgentToolRequest.PatchLines(path, startLine, endLine, resolvedNew)
            }
            "write_file", "write", "create_file" -> {
                if (path == null || !validPath(path) || content == null || content.length > MAX_CONTENT_LENGTH) null
                else AgentToolRequest.WriteFile(path, content)
            }
            "delete_file", "delete", "remove_file" -> {
                if (path == null || !validPath(path)) null else AgentToolRequest.DeleteFile(path)
            }
            "verify_syntax" -> {
                if (path == null || !validPath(path)) null else AgentToolRequest.VerifySyntax(path)
            }
            "run_command", "bash", "shell" -> {
                if (command == null || command.isBlank() || command.length > 2_048) null
                else AgentToolRequest.RunCommand(command)
            }
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readJsonObject(jsonText: String): Map<String, Any?>? = try {
        val reader = JsonReader.of(Buffer().writeUtf8(jsonText))
        reader.isLenient = false
        val value = reader.readJsonValue()
        if (reader.peek() != JsonReader.Token.END_DOCUMENT) null else value as? Map<String, Any?>
    } catch (_: Exception) {
        null
    }

    /** Escapa \n, \r e \t literais que modelos pequenos às vezes deixam dentro de strings JSON. */
    private fun escapeRawControlCharsInStrings(text: String): String {
        val sb = StringBuilder(text.length + 32)
        var inString = false
        var escaped = false
        for (ch in text) {
            if (inString) {
                when {
                    escaped -> { sb.append(ch); escaped = false }
                    ch == '\\' -> { sb.append(ch); escaped = true }
                    ch == '"' -> { sb.append(ch); inString = false }
                    ch == '\n' -> sb.append("\\n")
                    ch == '\r' -> sb.append("\\r")
                    ch == '\t' -> sb.append("\\t")
                    else -> sb.append(ch)
                }
            } else {
                if (ch == '"') inString = true
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Texto que deve aparecer para o usuário: remove os objetos JSON de ferramenta (completos ou
     * ainda sendo transmitidos), cercas ```json vazias e tags <thought>/<plan>.
     */
    fun visibleProse(text: String): String {
        if (text.isBlank()) return ""
        var result = text
        val spans = toolObjectSpans(result)
        for (range in spans.asReversed()) {
            result = result.removeRange(range)
        }
        // Objeto de ferramenta ainda incompleto (streaming): corta do início dele até o fim.
        toolStartRegex.findAll(result).lastOrNull()?.let { m ->
            result = result.substring(0, m.range.first)
        }
        result = result
            .replace(Regex("""```(?:json|tool|tool_call)?\s*```""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""</?(?:thought|plan|tool_call)>""", RegexOption.IGNORE_CASE), "")
        // Cerca ```json aberta no final (o JSON ainda está chegando).
        result = result.replace(Regex("""```(?:json|tool|tool_call)?\s*$""", RegexOption.IGNORE_CASE), "")
        return result.replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /**
     * Descreve a ferramenta que o modelo está escrevendo neste momento (JSON ainda aberto), para a
     * UI mostrar progresso ao vivo, ex.: "write_file → index.html · 4.2k caracteres".
     */
    fun describePendingTool(text: String): String? {
        val last = toolStartRegex.findAll(text).lastOrNull() ?: return null
        val tail = text.substring(last.range.first)
        if (toolObjectSpans(tail).isNotEmpty()) return null
        val name = Regex(""""tool"\s*:\s*"([a-z_]+)""").find(tail)?.groupValues?.get(1) ?: return null
        val path = Regex(""""(?:path|file_path)"\s*:\s*"([^"]{1,200})"""").find(tail)?.groupValues?.get(1)
        val contentIdx = Regex(""""(?:content|new_text|new_string)"\s*:\s*"""").find(tail)?.range?.last
        val size = if (contentIdx != null) tail.length - contentIdx - 1 else 0
        return buildString {
            append(name)
            if (path != null) append(" → ").append(path)
            if (size > 0) {
                append(" · ")
                append(if (size >= 1000) String.format(java.util.Locale.US, "%.1fk", size / 1000.0) else size.toString())
                append(" caracteres")
            }
        }
    }

    private val toolStartRegex = Regex("""\{\s*"tool"\s*:""")

    private fun toolObjectSpans(text: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        for (match in toolStartRegex.findAll(text)) {
            val startIdx = match.range.first
            if (spans.any { startIdx in it }) continue
            var depth = 0
            var inString = false
            var escaped = false
            for (i in startIdx until text.length) {
                val ch = text[i]
                if (escaped) { escaped = false; continue }
                if (ch == '\\' && inString) { escaped = true; continue }
                if (ch == '"') { inString = !inString; continue }
                if (inString) continue
                if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) {
                        spans += startIdx..i
                        break
                    }
                }
            }
        }
        return spans
    }

    private fun validPath(path: String): Boolean =
        path.isNotBlank() && path.length <= MAX_PATH_LENGTH &&
            !path.startsWith("/") && !path.startsWith("\\\\") &&
            !Regex("^[A-Za-z]:").containsMatchIn(path)
}
