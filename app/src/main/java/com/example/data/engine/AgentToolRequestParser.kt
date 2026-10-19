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

    private fun parseStrictJsonObject(jsonText: String): AgentToolRequest? {
        return try {
            val reader = JsonReader.of(Buffer().writeUtf8(jsonText))
            reader.isLenient = false
            reader.beginObject()

            val seenFields = mutableSetOf<String>()
            var tool: String? = null
            var path: String? = null
            var content: String? = null
            var command: String? = null
            var query: String? = null
            var oldText: String? = null
            var newText: String? = null
            var replaceAll = false
            var startLine: Int? = null
            var endLine: Int? = null
            var invalid = false

            while (reader.hasNext()) {
                val name = reader.nextName()
                if (!seenFields.add(name)) invalid = true

                when (name) {
                    "tool", "path", "content", "command", "query", "old_text", "new_text" -> {
                        if (reader.peek() == JsonReader.Token.STRING) {
                            val value = reader.nextString()
                            when (name) {
                                "tool" -> tool = value
                                "path" -> path = value
                                "content" -> content = value
                                "command" -> command = value
                                "query" -> query = value
                                "old_text" -> oldText = value
                                "new_text" -> newText = value
                            }
                        } else {
                            invalid = true
                            reader.skipValue()
                        }
                    }
                    "replace_all" -> {
                        if (reader.peek() == JsonReader.Token.BOOLEAN) {
                            replaceAll = reader.nextBoolean()
                        } else {
                            invalid = true
                            reader.skipValue()
                        }
                    }
                    "start_line", "end_line" -> {
                        if (reader.peek() == JsonReader.Token.NUMBER) {
                            val num = reader.nextInt()
                            if (name == "start_line") startLine = num else endLine = num
                        } else {
                            invalid = true
                            reader.skipValue()
                        }
                    }
                    else -> reader.skipValue()
                }
            }

            reader.endObject()
            if (reader.peek() != JsonReader.Token.END_DOCUMENT || invalid) return null

            when (tool) {
                "list_files" -> {
                    val resolvedPath = path ?: "."
                    if (!validPath(resolvedPath)) null
                    else AgentToolRequest.ListFiles(resolvedPath)
                }
                "read_file" -> {
                    val resolvedPath = path
                    if (resolvedPath == null || !validPath(resolvedPath)) null
                    else AgentToolRequest.ReadFile(resolvedPath)
                }
                "read_file_lines" -> {
                    val resolvedPath = path
                    val s = (startLine ?: 1).coerceAtLeast(1)
                    val e = (endLine ?: (s + 120)).coerceAtLeast(s)
                    if (resolvedPath == null || !validPath(resolvedPath)) null
                    else AgentToolRequest.ReadFileLines(resolvedPath, s, e)
                }
                "search_files" -> {
                    val resolvedQuery = query
                    val resolvedPath = path ?: "."
                    if (resolvedQuery.isNullOrBlank() ||
                        resolvedQuery.length > MAX_QUERY_LENGTH ||
                        !validPath(resolvedPath)
                    ) null else AgentToolRequest.SearchFiles(resolvedQuery, resolvedPath)
                }
                "find_symbol" -> {
                    val resolvedQuery = query ?: path
                    if (resolvedQuery.isNullOrBlank() || resolvedQuery.length > MAX_QUERY_LENGTH) null
                    else AgentToolRequest.FindSymbol(resolvedQuery)
                }
                "edit_file" -> {
                    val resolvedPath = path
                    val resolvedOld = oldText
                    val resolvedNew = newText
                    if (resolvedPath == null || !validPath(resolvedPath) ||
                        resolvedOld.isNullOrEmpty() || resolvedOld.length > MAX_CONTENT_LENGTH ||
                        resolvedNew == null || resolvedNew.length > MAX_CONTENT_LENGTH
                    ) null else AgentToolRequest.EditFile(resolvedPath, resolvedOld, resolvedNew, replaceAll)
                }
                "patch_lines" -> {
                    val resolvedPath = path
                    val s = startLine
                    val e = endLine
                    val resolvedNew = newText ?: content
                    if (resolvedPath == null || !validPath(resolvedPath) ||
                        s == null || e == null || s < 1 || e < s ||
                        resolvedNew == null || resolvedNew.length > MAX_CONTENT_LENGTH
                    ) null else AgentToolRequest.PatchLines(resolvedPath, s, e, resolvedNew)
                }
                "write_file" -> {
                    val resolvedPath = path
                    val resolvedContent = content
                    if (resolvedPath == null || !validPath(resolvedPath) ||
                        resolvedContent == null || resolvedContent.length > MAX_CONTENT_LENGTH
                    ) null else AgentToolRequest.WriteFile(resolvedPath, resolvedContent)
                }
                "delete_file" -> {
                    val resolvedPath = path
                    if (resolvedPath == null || !validPath(resolvedPath)) null
                    else AgentToolRequest.DeleteFile(resolvedPath)
                }
                "verify_syntax" -> {
                    val resolvedPath = path
                    if (resolvedPath == null || !validPath(resolvedPath)) null
                    else AgentToolRequest.VerifySyntax(resolvedPath)
                }
                "run_command" -> {
                    val resolvedCommand = command
                    if (resolvedCommand == null || resolvedCommand.isBlank() || resolvedCommand.length > 2_048) null
                    else AgentToolRequest.RunCommand(resolvedCommand)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun validPath(path: String): Boolean =
        path.isNotBlank() && path.length <= MAX_PATH_LENGTH &&
            !path.startsWith("/") && !path.startsWith("\\\\") &&
            !Regex("^[A-Za-z]:").containsMatchIn(path)
}
