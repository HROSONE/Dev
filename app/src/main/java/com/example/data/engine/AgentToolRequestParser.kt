package com.example.data.engine

import com.squareup.moshi.JsonReader
import okio.Buffer

/**
 * Parses the explicit JSON envelope used by the model to request a workspace tool.
 *
 * Supports both raw JSON envelopes and JSON tool blocks embedded in markdown fences or
 * preceded by brief reasoning steps, while keeping strict validation of tool names,
 * arguments, and workspace-relative paths.
 */
sealed class AgentToolRequest {
    data class ListFiles(val path: String = ".") : AgentToolRequest()
    data class ReadFile(val path: String) : AgentToolRequest()
    data class SearchFiles(val query: String, val path: String = ".") : AgentToolRequest()
    data class EditFile(
        val path: String,
        val oldText: String,
        val newText: String,
        val replaceAll: Boolean = false
    ) : AgentToolRequest()
    data class WriteFile(val path: String, val content: String) : AgentToolRequest()
    data class DeleteFile(val path: String) : AgentToolRequest()
    data class RunCommand(val command: String) : AgentToolRequest()
}

object AgentToolRequestParser {
    private const val MAX_PATH_LENGTH = 1_024
    private const val MAX_CONTENT_LENGTH = 500_000
    private const val MAX_QUERY_LENGTH = 512

    /**
     * Accepts a JSON object with a tool name and arguments (raw, fenced in ```json,
     * or preceded by a short reasoning sentence), or null when the response is ordinary prose
     * or invalid JSON. It never executes a tool.
     */
    fun parse(response: String): AgentToolRequest? {
        val trimmed = response.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            parseStrictJsonObject(trimmed)?.let { return it }
        }

        val extracted = extractCandidateJsonObject(trimmed) ?: return null
        return parseStrictJsonObject(extracted)
    }

    /**
     * Extracts optional reasoning text that appears before a tool JSON block.
     */
    fun extractThoughtStep(response: String): String? {
        val trimmed = response.trim()
        if (trimmed.startsWith("{")) return null
        val fenceIdx = trimmed.indexOf("```")
        val braceIdx = trimmed.indexOf("{\"tool\"")
            .takeIf { it >= 0 }
            ?: trimmed.indexOf("{ \"tool\"")
            ?: -1
        val cutIdx = when {
            fenceIdx > 0 && (braceIdx < 0 || fenceIdx < braceIdx) -> fenceIdx
            braceIdx > 0 -> braceIdx
            else -> -1
        }
        if (cutIdx <= 0) return null
        return trimmed.substring(0, cutIdx).trim().takeIf { it.isNotBlank() }?.take(300)
    }

    private fun extractCandidateJsonObject(text: String): String? {
        // 1. Try fenced block ```json ... ``` or ``` ... ```
        val fenceRegex = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```", RegexOption.IGNORE_CASE)
        fenceRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.let { candidate ->
            if (candidate.contains("\"tool\"")) return candidate
        }

        // 2. Try balanced JSON object starting at {"tool" or { "tool"
        val startMatch = Regex("\\{\\s*\"tool\"\\s*:").find(text) ?: return null
        val startIdx = startMatch.range.first
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
                        return text.substring(startIdx, i + 1)
                    }
                }
            }
        }
        return null
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
                "search_files" -> {
                    val resolvedQuery = query
                    val resolvedPath = path ?: "."
                    if (resolvedQuery.isNullOrBlank() ||
                        resolvedQuery.length > MAX_QUERY_LENGTH ||
                        !validPath(resolvedPath)
                    ) null else AgentToolRequest.SearchFiles(resolvedQuery, resolvedPath)
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
