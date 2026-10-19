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
import java.io.IOException
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
                    "Configure a API key em Configurações (ícone de engrenagem)."
                )
            )
            return@flow
        }

        val classified = ChatIntentClassifier.classify(userPrompt)
        val effectiveIntent = when {
            mode == AgentMode.BUILD && !ChatIntentClassifier.hasExplicitNoChange(userPrompt) ->
                ChatIntent.IMPLEMENTATION_REQUEST
            classified == ChatIntent.AMBIGUOUS ->
                ChatIntent.DISCUSSION
            else -> classified
        }

        val systemPrompt = buildHarnessSystemPrompt(mode, effectiveIntent, repositoryName, repositoryFiles.isEmpty())

        val historyContext = buildConversationHistory(conversationHistory)
        val repositoryContext = buildRepositoryContext(repositoryFiles)
        val promptWithContext = buildString {
            if (historyContext.isNotBlank()) {
                append("HISTÓRICO DA SESSÃO ATUAL:\n")
                append(historyContext)
                append("\n\n")
            }
            append("TAREFA DO USUÁRIO:\n")
            append(userPrompt)
            if (repositoryContext.isNotBlank()) {
                append("\n\n")
                append(repositoryContext)
            }
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
        isWorkspaceEmpty: Boolean
    ): String = buildString {
        append(ChatIntentClassifier.instructionFor(intent)).append("\n\n")
        append(
            "Você é o DevAgent Studio, um sistema autônomo de engenharia de software de elite (arquitetura SWE-Agent / Claude Code). " +
                "Seu foco absoluto é escrever código limpo, completo, modular, sem placeholders e pronto para produção.\n\n" +
                "CATÁLOGO DE FERRAMENTAS DO HARNESS (JSON):\n" +
                "Para acionar ferramentas, escreva 1 linha de raciocínio técnico (<thought>...</thought>) seguida do JSON da ferramenta:\n" +
                "1. Buscar símbolo (classe/função/método): {\"tool\":\"find_symbol\",\"query\":\"NomeDoSimbolo\"}\n" +
                "2. Buscar texto/regex (grep): {\"tool\":\"search_files\",\"query\":\"padrao\",\"path\":\".\"}\n" +
                "3. Listar arquivos: {\"tool\":\"list_files\",\"path\":\".\"}\n" +
                "4. Ler linhas numeradas (ideal para arquivos grandes): {\"tool\":\"read_file_lines\",\"path\":\"arq.kt\",\"start_line\":1,\"end_line\":140}\n" +
                "5. Ler arquivo inteiro: {\"tool\":\"read_file\",\"path\":\"arq.kt\"}\n" +
                "6. Edição cirúrgica por trecho (PREFERIDA): {\"tool\":\"edit_file\",\"path\":\"arq.kt\",\"old_text\":\"trecho atual\",\"new_text\":\"novo trecho\"}\n" +
                "7. Edição cirúrgica por números de linha: {\"tool\":\"patch_lines\",\"path\":\"arq.kt\",\"start_line\":10,\"end_line\":20,\"new_text\":\"novo bloco\"}\n" +
                "8. Criar arquivo novo ou reescrever: {\"tool\":\"write_file\",\"path\":\"arq.kt\",\"content\":\"código completo\"}\n" +
                "9. Verificar sintaxe e balanço de chaves: {\"tool\":\"verify_syntax\",\"path\":\"arq.kt\"}\n" +
                "10. Remover arquivo: {\"tool\":\"delete_file\",\"path\":\"arq.kt\"}\n" +
                "11. Terminal sandbox (pwd, ls, cat, grep, find, head, tail, wc, tree): {\"tool\":\"run_command\",\"command\":\"tree\"}\n\n" +
                "DISCIPLINA DE ENGENHARIA (HARNESS RULES):\n" +
                "• NUNCA abrevie código com '// ... existing code' ou '// resto do código'. Todo código gravado deve ser 100% funcional e compilável.\n" +
                "• CRIAÇÃO DO ZERO: Se o usuário pedir para criar um projeto/app do zero (ou o workspace estiver vazio), crie uma arquitetura limpa e completa usando write_file para cada arquivo essencial.\n" +
                "• EVOLUÇÃO DE PROJETOS EXISTENTES: Consulte o RepoMap abaixo, use find_symbol / read_file_lines para inspecionar o código exato antes de editar, e aplique mudanças cirúrgicas com edit_file ou patch_lines.\n" +
                "• AUTO-VERIFICAÇÃO (SyntaxGuard): Após cada edição, o Harness valida automaticamente chaves {}, parênteses () e sintaxe. Se aparecer [⚠️ HARNESS SYNTAX ERROR], corrija o arquivo imediatamente antes de encerrar.\n"
        )
        when (mode) {
            AgentMode.ASK -> append("Modo ativo: ASK (somente leitura/análise; responda com explicações técnicas e blocos de código claros).")
            AgentMode.IMAGINE -> append("Modo ativo: PLAN (projete a arquitetura, contratos de dados e passos de implementação; somente leitura).")
            AgentMode.BUILD -> append("Modo ativo: BUILD (autonomia total para criar projetos do zero, editar múltiplos arquivos e validar sintaxe).")
        }
        if (repositoryName.isNotBlank()) {
            append(" Projeto ativo: $repositoryName.")
        } else if (isWorkspaceEmpty) {
            append(" Workspace pronto para criação de projeto do zero.")
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
            .takeLast(8)
        if (recent.isEmpty()) return ""
        val sb = StringBuilder()
        for (msg in recent) {
            val speaker = if (msg.role == MessageRole.USER) "USUÁRIO" else "DEVAGENT"
            val toolsSummary = if (msg.toolCalls.isNotEmpty()) {
                " [tools: " + msg.toolCalls.joinToString(", ") { "${it.type.displayName}(${it.detail})" } + "]"
            } else ""
            sb.append(speaker).append(toolsSummary).append(": ")
                .append(msg.text.take(1_500)).append("\n")
        }
        return sb.toString().take(8_000)
    }

    private fun buildRepositoryContext(files: List<RepoFile>): String {
        if (files.isEmpty()) {
            return "STATUS DO WORKSPACE: Vazio (0 arquivos). Se o usuário pedir para criar um projeto ou app do zero, use write_file para criar a estrutura completa de arquivos."
        }
        val maxContextChars = 48_000
        val maxPerFileChars = 5_000
        val output = StringBuilder()
        output.append(RepoSymbolIndexer.buildRepoMap(files, maxChars = 20_000))
        output.append("\nCONTEÚDO DOS ARQUIVOS-CHAVE (use read_file / read_file_lines para ver qualquer arquivo completo):\n")
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

        root.listFiles()?.forEach { child ->
            runCatching { child.deleteRecursively() }
        }

        var copied = 0
        val failed = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for (file in files) {
            try {
                val target = File(root, file.path).canonicalFile
                if (target == root || !target.toPath().startsWith(root.toPath())) {
                    throw IOException("Caminho fora dos limites do workspace.")
                }
                if (!target.parentFile.exists() && !target.parentFile.mkdirs()) {
                    throw IOException("Não foi possível criar a pasta de destino.")
                }
                target.writeText(file.content, Charsets.UTF_8)
                copied++
            } catch (e: Exception) {
                failed += file.path
                errors += "${file.path}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return WorkspaceSyncResult(copiedFiles = copied, failedFiles = failed, errors = errors)
    }
}

sealed class ToolLoopEvent {
    data class Started(
        val toolName: String,
        val detail: String,
        var toolCallId: String? = null
    ) : ToolLoopEvent()

    data class Completed(
        val toolName: String,
        val detail: String,
        val output: String,
        val success: Boolean,
        val durationMs: Long,
        val toolCallId: String? = null
    ) : ToolLoopEvent()
}
