package com.example.data.engine

import com.squareup.moshi.JsonReader
import okio.Buffer

/**
 * Parses the explicit JSON envelope used by the model to request a workspace tool.
 *
 * Parsing is deliberately separate from execution: a parsed request is untrusted data.
 * Callers must validate intent, mode, confirmation, workspace scope and tool policy
 * immediately before dispatching the operation.
 */
sealed class AgentToolRequest {
    data class ListFiles(val path: String = ".") : AgentToolRequest()
    data class ReadFile(val path: String) : AgentToolRequest()
    data class WriteFile(val path: String, val content: String) : AgentToolRequest()
    data class RunCommand(val command: String) : AgentToolRequest()
}

object AgentToolRequestParser {
    private const val MAX_PATH_LENGTH = 1_024
    private const val MAX_CONTENT_LENGTH = 500_000

    /**
     * Accepts a JSON object with a tool name and arguments, or null when the response
     * is ordinary prose / invalid JSON. It never executes a tool.
     *
     * Example: {"tool":"read_file","path":"src/Main.kt"}
     */
    fun parse(response: String): AgentToolRequest? {
        val jsonText = response.trim()
        if (!jsonText.startsWith("{") || !jsonText.endsWith("}")) return null

        return try {
            val reader = JsonReader.of(Buffer().writeUtf8(jsonText))
            reader.isLenient = false
            reader.beginObject()

            val seenFields = mutableSetOf<String>()
            var tool: String? = null
            var path: String? = null
            var content: String? = null
            var invalid = false

            while (reader.hasNext()) {
                val name = reader.nextName()
                if (!seenFields.add(name)) invalid = true

                when (name) {
                    "tool", "path", "content" -> {
                        if (reader.peek() == JsonReader.Token.STRING) {
                            val value = reader.nextString()
                            when (name) {
                                "tool" -> tool = value
                                "path" -> path = value
                                "content" -> content = value
                            }
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
                "write_file" -> {
                    val resolvedPath = path
                    val resolvedContent = content
                    if (resolvedPath == null || !validPath(resolvedPath) ||
                        resolvedContent == null || resolvedContent.length > MAX_CONTENT_LENGTH
                    ) null else AgentToolRequest.WriteFile(resolvedPath, resolvedContent)
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
