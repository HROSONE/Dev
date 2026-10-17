package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.GitHubRepositoryClient
import com.example.data.engine.AgentExecutionEvent
import com.example.data.engine.DevAgentEngine
import com.example.data.engine.WorkspaceCommandRunner
import com.example.data.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class DevAgentUiState(
    val selectedMode: AgentMode = AgentMode.BUILD,
    val selectedSpeed: ReasoningSpeed = ReasoningSpeed.DEEP_THINKING,
    val activeRepo: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val isThinking: Boolean = false,
    val thinkingSeconds: Double = 0.0,
    val commandsExecuted: Int = 0,
    val currentPrompt: String = "",
    val activeTab: Int = 0,
    val repoFiles: List<RepoFile> = emptyList(),
    val selectedFile: RepoFile? = null,
    val isConnectingRepo: Boolean = false,
    val repositoryStatus: String = "",
    val pendingMutation: PendingMutation? = null,
    val terminalLogs: List<String> = listOf(
        "Workspace local ativo. Comandos permitidos: pwd, ls, cat.",
        "Ex.: ls  |  cat README.md  |  pwd"
    ),
    val isCommitting: Boolean = false,
    val lastCommitStatus: String = "",
    val hasWriteToken: Boolean = false
)

class DevAgentViewModel(application: Application) : AndroidViewModel(application) {

    private val workspaceDir = File(getApplication<Application>().filesDir, "devagent-workspace")
    private val engine: DevAgentEngine by lazy { DevAgentEngine(workspaceDir) }
    private val githubClient = GitHubRepositoryClient()
    private val commandRunner by lazy { WorkspaceCommandRunner(workspaceDir) }
    private var activeJob: Job? = null

    /** Token só em memória nesta sessão — nunca persistido. */
    private var sessionToken: String = ""
    private var defaultBranch: String = "main"

    private var mutationDecision: CompletableDeferred<Boolean>? = null

    private val _uiState = MutableStateFlow(DevAgentUiState())
    val uiState: StateFlow<DevAgentUiState> = _uiState.asStateFlow()

    fun setMode(mode: AgentMode) {
        _uiState.update { it.copy(selectedMode = mode) }
    }

    fun setSpeed(speed: ReasoningSpeed) {
        _uiState.update { it.copy(selectedSpeed = speed) }
    }

    fun setPrompt(prompt: String) {
        _uiState.update { it.copy(currentPrompt = prompt) }
    }

    fun setTab(tab: Int) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun selectFile(file: RepoFile) {
        _uiState.update { it.copy(selectedFile = file) }
    }

    fun toggleToolExpand(messageId: String, toolId: String) {
        _uiState.update { state ->
            val updatedMessages = state.messages.map { msg ->
                if (msg.id == messageId) {
                    val updatedTools = msg.toolCalls.map { tool ->
                        if (tool.id == toolId) tool.copy(isExpanded = !tool.isExpanded) else tool
                    }
                    msg.copy(toolCalls = updatedTools)
                } else msg
            }
            state.copy(messages = updatedMessages)
        }
    }

    fun stopExecution() {
        mutationDecision?.complete(false)
        mutationDecision = null
        activeJob?.cancel()
        activeJob = null
        _uiState.update {
            it.copy(isThinking = false, pendingMutation = null)
        }
    }

    fun respondToMutation(approved: Boolean) {
        mutationDecision?.complete(approved)
        mutationDecision = null
        _uiState.update { it.copy(pendingMutation = null) }
    }

    fun connectRepository(repository: String, token: String = "") {
        if (_uiState.value.isConnectingRepo) return
        _uiState.update {
            it.copy(isConnectingRepo = true, repositoryStatus = "Conectando ao GitHub…")
        }
        viewModelScope.launch {
            val result = githubClient.readRepository(repository, token)
            result.fold(
                onSuccess = { snapshot ->
                    sessionToken = token.trim()
                    defaultBranch = snapshot.defaultBranch
                    val files = snapshot.files.map { remote ->
                        RepoFile(
                            path = remote.path,
                            language = remote.language,
                            content = remote.content
                        )
                    }
                    engine.syncRepositoryToWorkspace(files)
                    _uiState.update { state ->
                        state.copy(
                            activeRepo = snapshot.fullName,
                            repoFiles = files,
                            selectedFile = files.firstOrNull(),
                            isConnectingRepo = false,
                            hasWriteToken = sessionToken.isNotBlank(),
                            repositoryStatus = "Conectado a ${snapshot.fullName} · branch ${snapshot.defaultBranch} · " +
                                "${files.size} arquivos · token ${if (sessionToken.isBlank()) "somente leitura pública" else "em memória (escrita possível)"}"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isConnectingRepo = false,
                            repositoryStatus = error.message ?: "Não foi possível ler o repositório."
                        )
                    }
                }
            )
        }
    }

    /** Executa comando real no workspace (pwd/ls/cat). */
    fun runTerminalCommand(command: String) {
        val cmd = command.trim()
        if (cmd.isBlank()) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    commandRunner.run(cmd)
                } catch (e: Exception) {
                    null to (e.message ?: "Erro")
                }
            }
            val lines = mutableListOf("> $cmd")
            if (result is Pair<*, *>) {
                lines += result.second as String
            } else if (result is com.example.data.engine.CommandResult) {
                val status = when {
                    result.timedOut -> "TIMEOUT"
                    else -> "exit ${result.exitCode}"
                }
                if (result.output.isNotBlank()) lines += result.output
                lines += "[$status]"
            }
            _uiState.update { state ->
                state.copy(
                    terminalLogs = (state.terminalLogs + lines).takeLast(200),
                    commandsExecuted = state.commandsExecuted + 1
                )
            }
        }
    }

    /**
     * Envia um arquivo do workspace para o GitHub (Contents API).
     * Requer token com contents:write informado na conexão.
     */
    fun commitFileToGitHub(path: String, commitMessage: String = "") {
        val repo = _uiState.value.activeRepo
        if (repo.isBlank()) {
            _uiState.update { it.copy(lastCommitStatus = "Conecte um repositório antes de fazer commit.") }
            return
        }
        if (sessionToken.isBlank()) {
            _uiState.update {
                it.copy(lastCommitStatus = "Informe um token GitHub com contents:write ao conectar o repositório.")
            }
            return
        }
        val file = File(workspaceDir, path)
        if (!file.isFile) {
            _uiState.update { it.copy(lastCommitStatus = "Arquivo não encontrado no workspace: $path") }
            return
        }
        _uiState.update { it.copy(isCommitting = true, lastCommitStatus = "Enviando $path…") }
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
            val msg = commitMessage.ifBlank { "DevAgent: atualiza $path" }
            val result = githubClient.commitFile(
                repositoryFullName = repo,
                path = path,
                content = content,
                commitMessage = msg,
                token = sessionToken,
                branch = defaultBranch
            )
            result.fold(
                onSuccess = { commit ->
                    _uiState.update {
                        it.copy(
                            isCommitting = false,
                            lastCommitStatus = "Commit OK: ${commit.commitSha.take(7)} · ${commit.path}" +
                                if (commit.htmlUrl.isNotBlank()) " · ${commit.htmlUrl}" else ""
                        )
                    }
                    // Marca arquivo como sincronizado
                    _uiState.update { state ->
                        state.copy(
                            repoFiles = state.repoFiles.map { f ->
                                if (f.path == path) f.copy(isModified = false, content = content) else f
                            }
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isCommitting = false,
                            lastCommitStatus = err.message ?: "Falha no commit."
                        )
                    }
                }
            )
        }
    }

    fun submitPrompt() {
        val prompt = _uiState.value.currentPrompt.trim()
        if (prompt.isBlank() || _uiState.value.isThinking) return

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            text = prompt,
            mode = _uiState.value.selectedMode,
            speed = _uiState.value.selectedSpeed
        )

        val agentMessageId = UUID.randomUUID().toString()
        val initialAgentMessage = ChatMessage(
            id = agentMessageId,
            role = MessageRole.AGENT,
            text = "",
            mode = _uiState.value.selectedMode,
            speed = _uiState.value.selectedSpeed,
            isThinking = true
        )

        _uiState.update { state ->
            state.copy(
                messages = state.messages + listOf(userMessage, initialAgentMessage),
                currentPrompt = "",
                isThinking = true,
                thinkingSeconds = 0.0,
                pendingMutation = null
            )
        }

        activeJob = viewModelScope.launch {
            try {
                engine.executeTask(
                    userPrompt = prompt,
                    mode = _uiState.value.selectedMode,
                    speed = _uiState.value.selectedSpeed,
                    repositoryName = _uiState.value.activeRepo,
                    repositoryFiles = _uiState.value.repoFiles,
                    confirmMutation = { toolName, path, contentPreview ->
                        val oldContent = _uiState.value.repoFiles.find { it.path == path }?.content.orEmpty()
                        val diff = buildSimpleDiff(oldContent, contentPreview)
                        val deferred = CompletableDeferred<Boolean>()
                        mutationDecision = deferred
                        _uiState.update {
                            it.copy(
                                pendingMutation = PendingMutation(
                                    toolName = toolName,
                                    path = path,
                                    contentPreview = contentPreview,
                                    messageId = agentMessageId,
                                    diffLines = diff
                                )
                            )
                        }
                        deferred.await()
                    }
                ).collect { event ->
                    when (event) {
                        is AgentExecutionEvent.ThinkingTick -> {
                            _uiState.update { state ->
                                val updatedMessages = state.messages.map { msg ->
                                    if (msg.id == agentMessageId) msg.copy(thinkingSeconds = event.seconds) else msg
                                }
                                state.copy(thinkingSeconds = event.seconds, messages = updatedMessages)
                            }
                        }

                        is AgentExecutionEvent.ThoughtStepAdded -> {
                            _uiState.update { state ->
                                val updatedMessages = state.messages.map { msg ->
                                    if (msg.id == agentMessageId) {
                                        msg.copy(thoughtSteps = msg.thoughtSteps + event.step)
                                    } else msg
                                }
                                state.copy(messages = updatedMessages)
                            }
                        }

                        is AgentExecutionEvent.ToolCallStarted -> {
                            _uiState.update { state ->
                                val updatedMessages = state.messages.map { msg ->
                                    if (msg.id == agentMessageId) {
                                        msg.copy(toolCalls = msg.toolCalls + event.toolCall)
                                    } else msg
                                }
                                state.copy(
                                    commandsExecuted = state.commandsExecuted + 1,
                                    messages = updatedMessages
                                )
                            }
                        }

                        is AgentExecutionEvent.ToolCallUpdated -> {
                            _uiState.update { state ->
                                val updatedMessages = state.messages.map { msg ->
                                    if (msg.id == agentMessageId) {
                                        val existing = msg.toolCalls.any { it.id == event.toolCall.id }
                                        val updatedTools = if (existing) {
                                            msg.toolCalls.map { t ->
                                                if (t.id == event.toolCall.id) event.toolCall else t
                                            }
                                        } else {
                                            msg.toolCalls + event.toolCall
                                        }
                                        msg.copy(toolCalls = updatedTools)
                                    } else msg
                                }
                                state.copy(messages = updatedMessages)
                            }
                        }

                        is AgentExecutionEvent.MutationProposed -> { /* UI via pendingMutation */ }

                        is AgentExecutionEvent.FinalResponseChunk -> {
                            _uiState.update { state ->
                                val updatedMessages = state.messages.map { msg ->
                                    if (msg.id == agentMessageId) {
                                        msg.copy(
                                            text = event.text,
                                            isThinking = false,
                                            metrics = event.metrics
                                        )
                                    } else msg
                                }
                                state.copy(
                                    messages = updatedMessages,
                                    isThinking = false,
                                    pendingMutation = null
                                )
                            }
                            refreshWorkspaceFiles()
                        }

                        is AgentExecutionEvent.Error -> {
                            _uiState.update { state ->
                                state.copy(
                                    isThinking = false,
                                    pendingMutation = null,
                                    messages = state.messages.map { msg ->
                                        if (msg.id == agentMessageId) {
                                            msg.copy(text = event.message, isThinking = false)
                                        } else msg
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                mutationDecision?.complete(false)
                mutationDecision = null
                _uiState.update { state ->
                    state.copy(
                        isThinking = false,
                        pendingMutation = null,
                        messages = state.messages.map { msg ->
                            if (msg.id == agentMessageId) {
                                msg.copy(
                                    text = "A execução foi interrompida: ${e.message ?: "erro inesperado"}. " +
                                        "Nenhuma alteração não confirmada foi aplicada.",
                                    isThinking = false
                                )
                            } else msg
                        }
                    )
                }
            }
        }
    }

    private fun buildSimpleDiff(oldText: String, newText: String): List<DiffLine> {
        val oldLines = oldText.lines()
        val newLines = newText.lines()
        if (oldLines == newLines) {
            return listOf(DiffLine(DiffLineType.CONTEXT, "(sem diferenças)"))
        }
        val result = mutableListOf<DiffLine>()
        result += DiffLine(DiffLineType.HEADER, "--- anterior")
        result += DiffLine(DiffLineType.HEADER, "+++ proposto")
        val maxShow = 80
        var shown = 0
        val maxLen = maxOf(oldLines.size, newLines.size)
        for (i in 0 until maxLen) {
            if (shown >= maxShow) {
                result += DiffLine(DiffLineType.CONTEXT, "… (${maxLen - i} linhas restantes)")
                break
            }
            val o = oldLines.getOrNull(i)
            val n = newLines.getOrNull(i)
            when {
                o == n && o != null -> {
                    // pula contexto idêntico para economizar
                }
                o != null && n == null -> {
                    result += DiffLine(DiffLineType.REMOVED, "- $o", oldLineNumber = i + 1)
                    shown++
                }
                o == null && n != null -> {
                    result += DiffLine(DiffLineType.ADDED, "+ $n", newLineNumber = i + 1)
                    shown++
                }
                o != null && n != null && o != n -> {
                    result += DiffLine(DiffLineType.REMOVED, "- $o", oldLineNumber = i + 1)
                    result += DiffLine(DiffLineType.ADDED, "+ $n", newLineNumber = i + 1)
                    shown += 2
                }
            }
        }
        if (result.size <= 2) {
            result += DiffLine(DiffLineType.ADDED, "(arquivo novo ou alteração completa)")
        }
        return result
    }

    private fun refreshWorkspaceFiles() {
        viewModelScope.launch {
            try {
                if (!workspaceDir.exists()) return@launch
                val files = workspaceDir.walkTopDown()
                    .filter { it.isFile }
                    .mapNotNull { file ->
                        val relative = file.relativeTo(workspaceDir).invariantSeparatorsPath
                        try {
                            RepoFile(
                                path = relative,
                                language = languageFor(relative),
                                content = file.readText(Charsets.UTF_8).take(50_000),
                                isModified = true
                            )
                        } catch (_: Exception) {
                            null
                        }
                    }
                    .sortedBy { it.path }
                    .toList()
                if (files.isNotEmpty()) {
                    _uiState.update { state ->
                        state.copy(
                            repoFiles = files,
                            selectedFile = state.selectedFile?.let { sel ->
                                files.find { it.path == sel.path } ?: files.firstOrNull()
                            } ?: files.firstOrNull()
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun languageFor(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "xml" -> "xml"
        "json" -> "json"
        "md" -> "markdown"
        "py" -> "python"
        "js", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        else -> "text"
    }
}
