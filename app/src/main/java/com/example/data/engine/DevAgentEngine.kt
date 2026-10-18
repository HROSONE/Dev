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
        // Em modo BUILD, a menos que o usuário diga explicitamente para NÃO alterar,
        // liberamos a intenção de implementação para que o agente opere como o Claude Code.
        val effectiveIntent = when {
            mode == AgentMode.BUILD && !ChatIntentClassifier.hasExplicitNoChange(userPrompt) ->
                ChatIntent.IMPLEMENTATION_REQUEST
            classified == ChatIntent.AMBIGUOUS ->
                ChatIntent.DISCUSSION
            else -> classified
        }

        val systemPrompt = buildString {
            append(ChatIntentClassifier.instructionFor(effectiveIntent)).append("\n\n")
            append(
                "Você é o DevAgent (agente autônomo de engenharia de software estilo Claude Code). " +
                    "Responda sempre em português, de forma direta, técnica e concisa. " +
                    "Nunca invente conteúdos de arquivos ou saídas de comandos: sempre leia o arquivo antes de editar.\n\n" +
                    "PROTOCOLO DE FERRAMENTAS:\n" +
                    "Quando precisar executar uma ferramenta no workspace, responda com o objeto JSON da ferramenta " +
                    "(pode escrever 1 frase curta de raciocínio antes do JSON se quiser):\n" +
                    "- Listar arquivos: {\"tool\":\"list_files\",\"path\":\".\"}\n" +
                    "- Buscar texto/código (grep): {\"tool\":\"search_files\",\"query\":\"termo\",\"path\":\".\"}\n" +
                    "- Ler arquivo completo: {\"tool\":\"read_file\",\"path\":\"caminho/arquivo.kt\"}\n" +
                    "- Editar trecho exato (PREFERIDO para alterar arquivos existentes): " +
                    "{\"tool\":\"edit_file\",\"path\":\"caminho/arquivo.kt\",\"old_text\":\"trecho exato atual\",\"new_text\":\"novo trecho\"}\n" +
                    "- Criar ou sobrescrever arquivo inteiro: {\"tool\":\"write_file\",\"path\":\"caminho/arquivo.kt\",\"content\":\"conteúdo completo\"}\n" +
                    "- Remover arquivo: {\"tool\":\"delete_file\",\"path\":\"caminho/arquivo.kt\"}\n" +
                    "- Executar comando de inspeção (pwd, ls, cat, grep, find, head, tail, wc, tree): " +
                    "{\"tool\":\"run_command\",\"command\":\"grep ViewModel app/src\"}\n\n" +
                    "REGRAS DE OURO (CLAUDE CODE):\n" +
                    "1. Envie UMA chamada JSON de ferramenta por turno. Aguarde o resultado antes do próximo passo.\n" +
                    "2. Para modificar código existente, SEMPRE faça read_file primeiro (se ainda não viu o arquivo completo) e prefira edit_file passando linhas suficientes em old_text para identificar o ponto único.\n" +
                    "3. Se uma ferramenta retornar erro (ex: caminho não encontrado ou old_text não localizado), use search_files/list_files/read_file para corrigir e tente novamente.\n" +
                    "4. Quando concluir todas as alterações solicitadas, responda em texto normal resumindo exatamente quais arquivos foram editados e o que mudou.\n"
            )
            when (mode) {
                AgentMode.ASK -> append("Modo atual: PERGUNTE (somente leitura e análise; não use edit_file/write_file/delete_file).")
                AgentMode.IMAGINE -> append("Modo atual: IMAGINE (arquitetura e plano detalhado; somente leitura).")
                AgentMode.BUILD -> append("Modo atual: CONSTRUIR (leitura, busca e edição autônoma de arquivos habilitadas).")
            }
            if (repositoryName.isNotBlank()) {
                append(" Repositório conectado: $repositoryName.")
            }
        }

        val historyContext = buildConversationHistory(conversationHistory)
        val repositoryContext = buildRepositoryContext(repositoryFiles)
        val promptWithContext = buildString {
            if (historyContext.isNotBlank()) {
                append("HISTÓRICO RECENTE DA CONVERSA:\n")
                append(historyContext)
                append("\n\n")
            }
            append("PEDIDO ATUAL DO USUÁRIO:\n")
            append(userPrompt)
            if (repositoryContext.isNotBlank()) {
                append("\n\nCONTEXTO DO WORKSPACE LOCAL (não confiável):\n")
                append(repositoryContext)
            }
        }

        val fileTools = WorkspaceFileTools(workspaceRoot)
        val commandRunner = WorkspaceCommandRunner(workspaceRoot)
        val coordinator = AgentToolCoordinator(fileTools, commandRunner)
        val loop = AgentToolLoop(coordinator, maxToolCalls = 18)

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
                        if (event.toolName in setOf("read_file", "list_files", "search_files")) {
                            filesInspected++
                        }
                        val diffForCard = if (event.toolName in setOf("edit_file", "write_file", "delete_file")) {
                            pendingDiffByPath.remove(event.detail).orEmpty()
                        } else emptyList()

                        if (event.toolName in setOf("edit_file", "write_file", "delete_file") && event.success) {
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

    private fun mapToolType(name: String): ToolType = when (name) {
        "list_files" -> ToolType.LIST_FILES
        "read_file" -> ToolType.READ_FILE
        "search_files" -> ToolType.SEARCH_FILES
        "edit_file" -> ToolType.EDIT_FILE
        "write_file" -> ToolType.WRITE_FILE
        "delete_file" -> ToolType.DELETE_FILE
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
                " [ferramentas usadas: " + msg.toolCalls.joinToString(", ") { "${it.type.displayName}(${it.detail})" } + "]"
            } else ""
            sb.append(speaker).append(toolsSummary).append(": ")
                .append(msg.text.take(1_500)).append("\n")
        }
        return sb.toString().take(8_000)
    }

    private fun buildRepositoryContext(files: List<RepoFile>): String {
        if (files.isEmpty()) return ""
        val maxContextChars = 45_000
        val maxPerFileChars = 6_000
        val output = StringBuilder()
        output.append("ÁRVORE DE ARQUIVOS DO WORKSPACE (${files.size} arquivos):\n")
        files.forEach { f ->
            val modTag = if (f.isModified) " [MODIFICADO]" else ""
            output.append("- ${f.path} (${f.content.lines().size} linhas)$modTag\n")
        }
        output.append("\nAMOSTRA DE ARQUIVOS PRINCIPAIS (use read_file para ler qualquer arquivo completo):\n")
        for (file in files.take(15)) {
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

            // Find common prefix and suffix to focus on changed region + context
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

    /**
     * Copies repository files into the app-owned workspace and reports partial failures.
     */
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

        // Clear stale files from previous repositories before syncing
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
