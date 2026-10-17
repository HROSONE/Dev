package com.example.data.engine

import org.json.JSONException
import org.json.JSONObject

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
            val json = JSONObject(jsonText)
            when (json.optString("tool")) {
                "list_files" -> {
                    val path = json.optString("path", ".")
                    if (!validPath(path)) null else AgentToolRequest.ListFiles(path)
                }
                "read_file" -> {
                    val path = json.optString("path")
                    if (!validPath(path)) null else AgentToolRequest.ReadFile(path)
                }
                "write_file" -> {
                    val path = json.optString("path")
                    val content = json.optString("content")
                    if (!validPath(path) || content.length > MAX_CONTENT_LENGTH ||
                        !json.has("content") || json.isNull("content")
                    ) null else AgentToolRequest.WriteFile(path, content)
                }
                else -> null
            }
        } catch (_: JSONException) {
            null
        }
    }

    private fun validPath(path: String): Boolean =
        path.isNotBlank() && path.length <= MAX_PATH_LENGTH &&
            !path.startsWith("/") && !path.startsWith("\\\\") &&
            !Regex("^[A-Za-z]:").containsMatchIn(path)
}
