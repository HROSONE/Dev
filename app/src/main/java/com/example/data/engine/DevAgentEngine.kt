package com.example.data.engine

import com.example.data.api.LlmGateway
import com.example.data.model.AgentMetrics
import com.example.data.model.AgentMode
import com.example.data.model.ChatMessage
import com.example.data.model.DiffLine
import com.example.data.model.DiffLineType
import com.example.data.model.ExecutionStatus
import com.example.data.model.MessageRole
import com.example.data.model.ReasoningSpeed
import com.example.data.model.RepoFile
import com.example.data.model.ToolCallInfo
import com.example.data.model.ToolType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed class AgentExecutionEvent {
    data class ThinkingTick(val seconds: Double) : AgentExecutionEvent()
    data class ThoughtStepAdded(val step: String) : AgentExecutionEvent()
    data class ToolCallStarted(val toolCall: ToolCallInfo) : AgentExecutionEvent()
    data class ToolCallUpdated(val toolCall: ToolCallInfo) : AgentExecutionEvent()
    data class FinalResponseChunk(val text: String, val metrics: AgentMetrics) : AgentExecutionEvent()
    data class Error(val message: String) : AgentExecutionEvent()
    data class MutationProposed(
        val toolName: String,
        val path: String,
        val contentPreview: String
    ) : AgentExecutionEvent()
}

data class WorkspaceSyncResult(
    val copiedFiles: Int,
    val failedFiles: List<String>,
    val errors: List<String>
) {
    val succeeded: Boolean get() = failedFiles.isEmpty()
}

class DevAgentEngine(
    private val workspaceRoot: File
) {

    fun executeTask(
        userPrompt: String,
        mode: AgentMode,
        speed: ReasoningSpeed,
        repositoryName: String = "",
        repositoryFiles: List<RepoFile> = emptyList(),
        conversationHistory: List<ChatMessage> = emptyList(),
        confirmMutation: suspend (toolName: String, path: String, contentPreview: String) -> Boolean =
            { _, _, _ -> false }
    ): Flow<AgentExecutionEvent> = flow {
        val startedAt = System.currentTimeMillis()
        emit(AgentExecutionEvent.ThinkingTick(0.0))

        if (!LlmGateway.isConfigured()) {
            emit(
                AgentExecutionEvent.Error(
                    "Configure a API key em Configurações (ícone de engrenagem) ou baixe o modelo Qwen local."
                )
            )
            return@flow
        }

        val classified = ChatIntentClassifier.classify(userPrompt)
        // NÃO forçar implementação só porque o modo é BUILD — conversa livre continua conversa.
        val effectiveIntent = when {
            ChatIntentClassifier.hasExplicitNoChange(userPrompt) -> ChatIntent.DISCUSSION
            classified == ChatIntent.DISCUSSION ||
                classified == ChatIntent.EXPLANATION ||
                classified == ChatIntent.PLANNING -> classified
            classified == ChatIntent.IMPLEMENTATION_REQUEST -> ChatIntent.IMPLEMENTATION_REQUEST
            mode == AgentMode.ASK -> ChatIntent.DISCUSSION
            mode == AgentMode.IMAGINE -> ChatIntent.PLANNING
            // BUILD + ambíguo: conversa, não código automático
            else -> ChatIntent.DISCUSSION
        }

        val isChatOnly = effectiveIntent == ChatIntent.DISCUSSION ||
            effectiveIntent == ChatIntent.EXPLANATION

        val systemPrompt = buildHarnessSystemPrompt(
            mode, effectiveIntent, repositoryName, repositoryFiles.isEmpty(), isChatOnly
        )

        val historyContext = buildConversationHistory(conversationHistory)
        val repositoryContext = if (isChatOnly) "" else buildRepositoryContext(repositoryFiles)
        val promptWithContext = buildString {
            if (historyContext.isNotBlank()) {
                append("HISTÓRICO DA SESSÃO:\n")
                append(historyContext)
                append("\n\n")
            }
            if (isChatOnly) {
                append("MENSAGEM DO USUÁRIO:\n")
                append(userPrompt)
                append("\n\nResponda de forma natural e útil. Não invente JSON de ferramentas.")
            } else {
                append("TAREFA DO USUÁRIO:\n")
                append(userPrompt)
                if (repositoryContext.isNotBlank()) {
                    append("\n\n")
                    append(repositoryContext)
                }
            }
        }

        // Caminho rápido de chat: uma única chamada ao modelo, sem loop de tools
        if (isChatOnly) {
            val chatResult = LlmGateway.query(
                prompt = promptWithContext,
                geminiModelName = speed.modelName,
                systemInstruction = systemPrompt
            )
            val elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000.0
            emit(AgentExecutionEvent.ThinkingTick(elapsedSeconds))
            chatResult.fold(
                onSuccess = { text ->
                    emit(
                        AgentExecutionEvent.FinalResponseChunk(
                            text = text.trim().ifBlank { "(resposta vazia do modelo)" },
                            metrics = AgentMetrics(
                                thinkingSeconds = elapsedSeconds,
                                commandsExecuted = 0,
                                filesInspected = 0,
                                linesChanged = 0,
                                sandboxLatencyMs = 0
                            )
                        )
                    )
                },
                onFailure = { err ->
                    emit(
                        AgentExecutionEvent.Error(
                            err.message ?: "Falha ao conversar com o modelo."
                        )
                    )
                }
            )
            return@flow
        }

        val fileTools = WorkspaceFileTools(workspaceRoot)
        val commandRunner = WorkspaceCommandRunner(workspaceRoot)
        val coordinator = AgentToolCoordinator(fileTools, commandRunner)
        val loop = AgentToolLoop(coordinator, maxToolCalls = 24)

        var toolsRun = 0
        var filesInspected = 0
        var linesChanged = 0
        val activeToolIds = mutableMapOf<String, String>()
        val pendingDiffByPath = mutableMapOf<String, List<DiffLine>>()

        val result = loop.run(
            initialPrompt = promptWithContext,
            mode = mode,
            intent = effectiveIntent,
            requestModel = { conversation ->
                LlmGateway.query(
                    prompt = conversation,
                    geminiModelName = speed.modelName,
                    systemInstruction = systemPrompt
                )
            },
            confirmMutation = { toolName, path, contentPreview ->
                val existingFile = File(workspaceRoot, path)
                val oldContent = if (existingFile.isFile) {
                    runCatching { existingFile.readText(Charsets.UTF_8) }.getOrDefault("")
                } else ""
                val diff = computeUnifiedDiff(oldContent, contentPreview)
                pendingDiffByPath[path] = diff
                emit(AgentExecutionEvent.MutationProposed(toolName, path, contentPreview))
                confirmMutation(toolName, path, contentPreview)
            },
            onThoughtStep = { thought ->
                emit(AgentExecutionEvent.ThoughtStepAdded(thought))
            },
            onToolEvent = { event ->
                when (event) {
                    is ToolLoopEvent.Started -> {
                        toolsRun++
                        val id = UUID.randomUUID().toString()
                        activeToolIds[event.toolName] = id
                        event.toolCallId = id
                        emit(
                            AgentExecutionEvent.ToolCallStarted(
                                ToolCallInfo(
                                    id = id,
                                    type = mapToolType(event.toolName),
                                    title = event.toolName,
                                    detail = event.detail,
                                    status = ExecutionStatus.RUNNING,
                                    isExpanded = false
                                )
                            )
                        )
                    }
                    is ToolLoopEvent.Completed -> {
                        if (event.toolName in setOf("read_file", "read_file_lines", "list_files", "search_files", "find_symbol")) {
                            filesInspected++
                        }
                        val cleanPath = event.detail.removePrefix("path=").substringBefore(":")
                        val diffForCard = if (event.toolName in setOf("edit_file", "patch_lines", "write_file", "delete_file")) {
                            pendingDiffByPath.remove(event.detail)
                                ?: pendingDiffByPath.remove(cleanPath)
                                .orEmpty()
                        } else emptyList()

                        if (event.toolName in setOf("edit_file", "patch_lines", "write_file", "delete_file") && event.success) {
                            val changedCount = diffForCard.count {
                                it.type == DiffLineType.ADDED || it.type == DiffLineType.REMOVED
                            }.coerceAtLeast(1)
                            linesChanged += changedCount
                        }
                        val id = event.toolCallId
                            ?: activeToolIds[event.toolName]
                            ?: UUID.randomUUID().toString()
                        emit(
                            AgentExecutionEvent.ToolCallUpdated(
                                ToolCallInfo(
                                    id = id,
                                    type = mapToolType(event.toolName),
                                    title = event.toolName,
                                    detail = event.detail,
                                    status = if (event.success) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
                                    durationMs = event.durationMs,
                                    output = event.output.take(8_000),
                                    diffLines = diffForCard,
                                    isExpanded = diffForCard.isNotEmpty()
                                )
                            )
                        )
                    }
                }
            }
        )

        val elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000.0
        emit(AgentExecutionEvent.ThinkingTick(elapsedSeconds))

        when (result) {
            is AgentLoopResult.FinalAnswer -> {
                emit(
                    AgentExecutionEvent.FinalResponseChunk(
                        text = result.text,
                        metrics = AgentMetrics(
                            thinkingSeconds = elapsedSeconds,
                            commandsExecuted = toolsRun,
                            filesInspected = filesInspected,
                            linesChanged = linesChanged,
                            sandboxLatencyMs = 0
                        )
                    )
                )
            }
            is AgentLoopResult.ConfirmationRequired -> {
                emit(
                    AgentExecutionEvent.Error(
                        "Gravação de '${result.path}' recusada pelo usuário. Nenhuma alteração adicional foi aplicada."
                    )
                )
            }
            is AgentLoopResult.Failed -> {
                emit(AgentExecutionEvent.Error(result.message))
            }
        }
    }

    private fun buildHarnessSystemPrompt(
        mode: AgentMode,
        intent: ChatIntent,
        repositoryName: String,
        isWorkspaceEmpty: Boolean,
        isChatOnly: Boolean
    ): String {
        if (isChatOnly) {
            return buildString {
                append(
                    "Você é o DevAgent, um assistente de programação amigável e claro. " +
                        "Converse naturalmente em português (a menos que o usuário escreva em outro idioma). " +
                        "Responda perguntas, explique ideias, tire dúvidas e converse de forma humana. " +
                        "NÃO use JSON de ferramentas, NÃO invente tags <thought>, NÃO tente editar arquivos " +
                        "a menos que o usuário peça explicitamente para implementar/criar/corrigir código.\n\n"
                )
                append(ChatIntentClassifier.instructionFor(intent))
                when (mode) {
                    AgentMode.ASK -> append("\nModo: Ask (foco em explicar e conversar).")
                    AgentMode.IMAGINE -> append("\nModo: Plan (pode sugerir planos em texto).")
                    AgentMode.BUILD -> append("\nModo: Build disponível, mas nesta mensagem só converse; use ferramentas só se o usuário pedir código.")
                }
            }
        }

        return buildString {
            append(ChatIntentClassifier.instructionFor(intent)).append("\n\n")
            append(
                "Você é o DevAgent Studio, um sistema autônomo de engenharia de software. " +
                    "Escreva código limpo, completo e pronto para uso quando for implementar.\n\n" +
                    "CATÁLOGO DE FERRAMENTAS (JSON em uma linha após <thought>...</thought> se precisar):\n" +
                    "{\"tool\":\"list_files\",\"path\":\".\"}\n" +
                    "{\"tool\":\"read_file\",\"path\":\"arq.kt\"}\n" +
                    "{\"tool\":\"write_file\",\"path\":\"arq.kt\",\"content\":\"...\"}\n" +
                    "{\"tool\":\"edit_file\",\"path\":\"arq.kt\",\"old_text\":\"...\",\"new_text\":\"...\"}\n" +
                    "{\"tool\":\"run_command\",\"command\":\"ls\"}\n\n" +
                    "Só use ferramentas quando for realmente implementar ou inspecionar arquivos.\n"
            )
            when (mode) {
                AgentMode.ASK -> append("Modo: ASK (leitura/análise).")
                AgentMode.IMAGINE -> append("Modo: PLAN (arquitetura em texto).")
                AgentMode.BUILD -> append("Modo: BUILD (pode editar arquivos com confirmação).")
            }
            if (repositoryName.isNotBlank()) append(" Projeto: $repositoryName.")
            else if (isWorkspaceEmpty) append(" Workspace vazio.")
        }
    }

    private fun mapToolType(name: String): ToolType = when (name) {
        "list_files" -> ToolType.LIST_FILES
        "read_file" -> ToolType.READ_FILE
        "read_file_lines" -> ToolType.READ_FILE_LINES
        "search_files" -> ToolType.SEARCH_FILES
        "find_symbol" -> ToolType.FIND_SYMBOL
        "edit_file" -> ToolType.EDIT_FILE
        "patch_lines" -> ToolType.PATCH_LINES
        "write_file" -> ToolType.WRITE_FILE
        "delete_file" -> ToolType.DELETE_FILE
        "verify_syntax" -> ToolType.VERIFY_SYNTAX
        "run_command" -> ToolType.RUN_COMMAND
        else -> ToolType.READ_FILE
    }

    private fun buildConversationHistory(messages: List<ChatMessage>): String {
        if (messages.isEmpty()) return ""
        val recent = messages
            .filter { it.role == MessageRole.USER || (it.role == MessageRole.AGENT && it.text.isNotBlank()) }
            .takeLast(10)
        if (recent.isEmpty()) return ""
        val sb = StringBuilder()
        for (msg in recent) {
            val speaker = if (msg.role == MessageRole.USER) "USUÁRIO" else "ASSISTENTE"
            sb.append(speaker).append(": ").append(msg.text.take(1_200)).append("\n")
        }
        return sb.toString().take(6_000)
    }

    private fun buildRepositoryContext(files: List<RepoFile>): String {
        if (files.isEmpty()) {
            return "STATUS DO WORKSPACE: Vazio (0 arquivos)."
        }
        val maxContextChars = 48_000
        val maxPerFileChars = 5_000
        val output = StringBuilder()
        output.append(RepoSymbolIndexer.buildRepoMap(files, maxChars = 20_000))
        output.append("\nCONTEÚDO DOS ARQUIVOS-CHAVE:\n")
        val prioritized = files.sortedByDescending { it.isModified }.take(10)
        for (file in prioritized) {
            if (output.length >= maxContextChars) break
            val remaining = maxContextChars - output.length
            val content = file.content.take(minOf(maxPerFileChars, remaining))
            if (content.isBlank()) continue
            output.append("\n--- FILE: ").append(file.path).append(" ---\n")
            output.append(content).append("\n")
        }
        return output.toString().take(maxContextChars)
    }

    companion object {
        fun computeUnifiedDiff(oldContent: String, newContent: String, maxDiffLines: Int = 160): List<DiffLine> {
            val oldLines = if (oldContent.isEmpty()) emptyList() else oldContent.replace("\r\n", "\n").lines()
            val newLines = if (newContent.isEmpty()) emptyList() else newContent.replace("\r\n", "\n").lines()
            if (oldLines == newLines) return emptyList()

            var prefix = 0
            while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
                prefix++
            }
            var oldSuffix = oldLines.size - 1
            var newSuffix = newLines.size - 1
            while (oldSuffix >= prefix && newSuffix >= prefix && oldLines[oldSuffix] == newLines[newSuffix]) {
                oldSuffix--
                newSuffix--
            }

            val result = mutableListOf<DiffLine>()
            val contextStart = (prefix - 3).coerceAtLeast(0)
            result += DiffLine(
                DiffLineType.HEADER,
                "@@ -${contextStart + 1},${(oldSuffix - contextStart + 1).coerceAtLeast(0)} +${contextStart + 1},${(newSuffix - contextStart + 1).coerceAtLeast(0)} @@"
            )
            for (i in contextStart until prefix) {
                if (result.size >= maxDiffLines) break
                result += DiffLine(DiffLineType.CONTEXT, "  ${oldLines[i]}", oldLineNumber = i + 1, newLineNumber = i + 1)
            }
            for (i in prefix..oldSuffix) {
                if (result.size >= maxDiffLines) break
                result += DiffLine(DiffLineType.REMOVED, "- ${oldLines[i]}", oldLineNumber = i + 1)
            }
            for (i in prefix..newSuffix) {
                if (result.size >= maxDiffLines) break
                result += DiffLine(DiffLineType.ADDED, "+ ${newLines[i]}", newLineNumber = i + 1)
            }
            val contextEnd = minOf(oldLines.size - 1, oldSuffix + 3)
            for (i in (oldSuffix + 1)..contextEnd) {
                if (result.size >= maxDiffLines) break
                result += DiffLine(DiffLineType.CONTEXT, "  ${oldLines[i]}", oldLineNumber = i + 1)
            }
            return result
        }
    }

    fun syncRepositoryToWorkspace(files: List<RepoFile>): WorkspaceSyncResult {
        val root = workspaceRoot.canonicalFile
        if (!root.exists() && !root.mkdirs()) {
            return WorkspaceSyncResult(
                copiedFiles = 0,
                failedFiles = files.map { it.path },
                errors = listOf("Não foi possível criar o workspace: ${root.path}")
            )
        }
        if (!root.isDirectory) {
            return WorkspaceSyncResult(
                copiedFiles = 0,
                failedFiles = files.map { it.path },
                errors = listOf("A raiz do workspace não é um diretório: ${root.path}")
            )
        }

        var copied = 0
        val failed = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for (file in files) {
            try {
                val target = File(root, file.path).canonicalFile
                if (!target.path.startsWith(root.path)) {
                    failed += file.path
                    errors += "Path fora do workspace: ${file.path}"
                    continue
                }
                target.parentFile?.mkdirs()
                target.writeText(file.content, Charsets.UTF_8)
                copied++
            } catch (e: Exception) {
                failed += file.path
                errors += "${file.path}: ${e.message}"
            }
        }
        return WorkspaceSyncResult(copied, failed, errors)
    }
}
