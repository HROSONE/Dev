package com.example.data.engine

import android.content.Context
import com.example.data.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Armazena e recupera o histórico completo de conversas (sessões de chat) no disco local do app,
 * garantindo que ao fechar e abrir o DevAgent todas as mensagens, passos de raciocínio,
 * chamadas de ferramentas e diffs sejam preservados na memória.
 */
class ChatHistoryStore(context: Context) {
    private val historyDir = File(context.applicationContext.filesDir, "chat_history").apply { mkdirs() }
    private val prefs = context.applicationContext.getSharedPreferences("devagent_chat_prefs", Context.MODE_PRIVATE)

    fun getActiveSessionId(): String =
        prefs.getString(KEY_ACTIVE_SESSION, "").orEmpty()

    fun setActiveSessionId(sessionId: String) {
        prefs.edit().putString(KEY_ACTIVE_SESSION, sessionId).apply()
    }

    fun saveSession(
        sessionId: String,
        activeRepo: String,
        messages: List<ChatMessage>
    ) {
        if (sessionId.isBlank()) return
        if (!historyDir.exists()) historyDir.mkdirs()
        val file = File(historyDir, "$sessionId.json")
        if (messages.isEmpty()) {
            if (file.exists()) file.delete()
            return
        }
        val title = messages.firstOrNull { it.role == MessageRole.USER }?.text
            ?.replace("\n", " ")
            ?.trim()
            ?.take(60)
            ?.ifBlank { "Conversa sem título" }
            ?: "Nova Conversa"

        val root = JSONObject().apply {
            put("id", sessionId)
            put("title", title)
            put("updatedAt", System.currentTimeMillis())
            put("activeRepo", activeRepo)
            put("messages", JSONArray().apply {
                messages.forEach { msg ->
                    put(serializeMessage(msg))
                }
            })
        }
        runCatching {
            file.writeText(root.toString(), Charsets.UTF_8)
            setActiveSessionId(sessionId)
        }
    }

    fun loadSession(sessionId: String): Pair<String, List<ChatMessage>>? {
        if (sessionId.isBlank()) return null
        val file = File(historyDir, "$sessionId.json")
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val repo = json.optString("activeRepo", "")
            val arr = json.optJSONArray("messages") ?: JSONArray()
            val msgs = mutableListOf<ChatMessage>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                msgs += deserializeMessage(item)
            }
            repo to msgs
        }.getOrNull()
    }

    fun listSessions(): List<ChatSessionSummary> {
        if (!historyDir.exists()) return emptyList()
        val files = historyDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText(Charsets.UTF_8))
                    val msgs = json.optJSONArray("messages")
                    ChatSessionSummary(
                        id = json.optString("id", file.nameWithoutExtension),
                        title = json.optString("title", "Conversa"),
                        updatedAt = json.optLong("updatedAt", file.lastModified()),
                        activeRepo = json.optString("activeRepo", ""),
                        messageCount = msgs?.length() ?: 0
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }
    }

    fun deleteSession(sessionId: String) {
        val file = File(historyDir, "$sessionId.json")
        if (file.exists()) file.delete()
        if (getActiveSessionId() == sessionId) {
            val next = listSessions().firstOrNull()?.id.orEmpty()
            setActiveSessionId(next)
        }
    }

    fun newSessionId(): String = UUID.randomUUID().toString()

    private fun serializeMessage(msg: ChatMessage): JSONObject = JSONObject().apply {
        put("id", msg.id)
        put("role", msg.role.name)
        put("text", msg.text)
        put("timestamp", msg.timestamp)
        put("mode", msg.mode.name)
        put("speed", msg.speed.name)
        put("thinkingSeconds", msg.thinkingSeconds)
        put("thoughtSteps", JSONArray().apply {
            msg.thoughtSteps.forEach { step -> put(step) }
        })
        put("toolCalls", JSONArray().apply {
            msg.toolCalls.forEach { tc ->
                put(JSONObject().apply {
                    put("id", tc.id)
                    put("type", tc.type.name)
                    put("title", tc.title)
                    put("detail", tc.detail)
                    put("status", tc.status.name)
                    put("durationMs", tc.durationMs)
                    put("output", tc.output)
                    put("isExpanded", tc.isExpanded)
                    put("target", tc.target)
                    tc.fileChange?.let { fc ->
                        put("fileChange", JSONObject().apply {
                            put("path", fc.path)
                            put("kind", fc.kind.name)
                            put("added", fc.linesAdded)
                            put("removed", fc.linesRemoved)
                        })
                    }
                    put("diffLines", JSONArray().apply {
                        tc.diffLines.forEach { dl ->
                            put(JSONObject().apply {
                                put("type", dl.type.name)
                                put("text", dl.text)
                                if (dl.oldLineNumber != null) put("oldLine", dl.oldLineNumber)
                                if (dl.newLineNumber != null) put("newLine", dl.newLineNumber)
                            })
                        }
                    })
                })
            }
        })
        msg.metrics?.let { m ->
            put("metrics", JSONObject().apply {
                put("thinkingSeconds", m.thinkingSeconds)
                put("commandsExecuted", m.commandsExecuted)
                put("filesInspected", m.filesInspected)
                put("linesChanged", m.linesChanged)
                put("sandboxLatencyMs", m.sandboxLatencyMs)
                put("filesCreated", m.filesCreated)
                put("filesModified", m.filesModified)
                put("filesRead", m.filesRead)
                put("linesAdded", m.linesAdded)
                put("linesRemoved", m.linesRemoved)
                put("modelRounds", m.modelRounds)
                put("failedTools", m.failedTools)
            })
        }
        put("steps", JSONArray().apply {
            msg.steps.forEach { step ->
                put(JSONObject().apply {
                    put("id", step.id)
                    put("kind", step.kind.name)
                    put("text", step.text)
                    step.toolCallId?.let { put("toolCallId", it) }
                })
            }
        })
    }

    private fun deserializeMessage(json: JSONObject): ChatMessage {
        val stepsArr = json.optJSONArray("thoughtSteps") ?: JSONArray()
        val steps = (0 until stepsArr.length()).mapNotNull { stepsArr.optString(it) }

        val toolsArr = json.optJSONArray("toolCalls") ?: JSONArray()
        val tools = mutableListOf<ToolCallInfo>()
        for (i in 0 until toolsArr.length()) {
            val t = toolsArr.optJSONObject(i) ?: continue
            val diffArr = t.optJSONArray("diffLines") ?: JSONArray()
            val diffs = mutableListOf<DiffLine>()
            for (j in 0 until diffArr.length()) {
                val d = diffArr.optJSONObject(j) ?: continue
                diffs += DiffLine(
                    type = runCatching { DiffLineType.valueOf(d.optString("type")) }.getOrDefault(DiffLineType.CONTEXT),
                    text = d.optString("text"),
                    oldLineNumber = if (d.has("oldLine")) d.optInt("oldLine") else null,
                    newLineNumber = if (d.has("newLine")) d.optInt("newLine") else null
                )
            }
            tools += ToolCallInfo(
                id = t.optString("id", UUID.randomUUID().toString()),
                type = runCatching { ToolType.valueOf(t.optString("type")) }.getOrDefault(ToolType.READ_FILE),
                title = t.optString("title"),
                detail = t.optString("detail"),
                status = runCatching { ExecutionStatus.valueOf(t.optString("status")) }
                    .getOrDefault(ExecutionStatus.SUCCESS)
                    .let { if (it == ExecutionStatus.RUNNING || it == ExecutionStatus.PENDING) ExecutionStatus.FAILED else it },
                durationMs = t.optLong("durationMs"),
                output = t.optString("output"),
                diffLines = diffs,
                isExpanded = t.optBoolean("isExpanded", false),
                target = t.optString("target", ""),
                fileChange = t.optJSONObject("fileChange")?.let { fc ->
                    FileChangeInfo(
                        path = fc.optString("path"),
                        kind = runCatching { FileChangeKind.valueOf(fc.optString("kind")) }
                            .getOrDefault(FileChangeKind.MODIFIED),
                        linesAdded = fc.optInt("added"),
                        linesRemoved = fc.optInt("removed")
                    )
                }
            )
        }

        val metricsObj = json.optJSONObject("metrics")
        val metrics = metricsObj?.let {
            AgentMetrics(
                thinkingSeconds = it.optDouble("thinkingSeconds", 0.0),
                commandsExecuted = it.optInt("commandsExecuted", 0),
                filesInspected = it.optInt("filesInspected", 0),
                linesChanged = it.optInt("linesChanged", 0),
                sandboxLatencyMs = it.optLong("sandboxLatencyMs", 0L),
                filesCreated = it.optInt("filesCreated", 0),
                filesModified = it.optInt("filesModified", 0),
                filesRead = it.optInt("filesRead", 0),
                linesAdded = it.optInt("linesAdded", 0),
                linesRemoved = it.optInt("linesRemoved", 0),
                modelRounds = it.optInt("modelRounds", 0),
                failedTools = it.optInt("failedTools", 0)
            )
        }

        val stepsJson = json.optJSONArray("steps") ?: JSONArray()
        val timeline = (0 until stepsJson.length()).mapNotNull { idx ->
            val st = stepsJson.optJSONObject(idx) ?: return@mapNotNull null
            AgentStep(
                id = st.optString("id", UUID.randomUUID().toString()),
                kind = runCatching { StepKind.valueOf(st.optString("kind")) }.getOrDefault(StepKind.STATUS),
                text = st.optString("text"),
                toolCallId = if (st.has("toolCallId")) st.optString("toolCallId") else null,
                isLive = false
            )
        }

        return ChatMessage(
            id = json.optString("id", UUID.randomUUID().toString()),
            role = runCatching { MessageRole.valueOf(json.optString("role")) }.getOrDefault(MessageRole.AGENT),
            text = json.optString("text"),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            mode = runCatching { AgentMode.valueOf(json.optString("mode")) }.getOrDefault(AgentMode.BUILD),
            speed = runCatching { ReasoningSpeed.valueOf(json.optString("speed")) }.getOrDefault(ReasoningSpeed.DEEP_THINKING),
            isThinking = false,
            thinkingSeconds = json.optDouble("thinkingSeconds", 0.0),
            thoughtSteps = steps,
            toolCalls = tools,
            metrics = metrics,
            steps = timeline
        )
    }

    companion object {
        private const val KEY_ACTIVE_SESSION = "active_chat_session_id"
    }
}
