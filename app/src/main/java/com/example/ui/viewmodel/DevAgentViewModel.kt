package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.ApiKeyStore
import com.example.data.api.GeminiClient
import com.example.data.api.GitHubRepositoryClient
import com.example.data.api.LlmGateway
import com.example.data.api.OpenRouterClient
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
    val hasWriteToken: Boolean = false,
    val hasLlmKey: Boolean = false,
    val llmProvider: LlmProvider = LlmProvider.GEMINI,
    val openRouterModelId: String = LlmProvider.DEFAULT_OPENROUTER_MODEL,
    val llmStatusLabel: String = "",
    val apiKeyStatus: String = ""
)

class DevAgentViewModel(application: Application) : AndroidViewModel(application) {

    private val workspaceDir = File(getApplication<Application>().filesDir, "devagent-workspace")
    private val engine: DevAgentEngine by lazy { DevAgentEngine(workspaceDir) }
    private val githubClient = GitHubRepositoryClient()
    private val commandRunner by lazy { WorkspaceCommandRunner(workspaceDir) }
    private val apiKeyStore = ApiKeyStore(application)
    private var activeJob: Job? = null

    private var sessionToken: String = ""
    private var defaultBranch: String = "main"
    private var mutationDecision: CompletableDeferred<Boolean>? = null

    private val _uiState = MutableStateFlow(DevAgentUiState())
    val uiState: StateFlow<DevAgentUiState> = _uiState.asStateFlow()

    init {
        applyStoredLlmConfig()
    }

    private fun applyStoredLlmConfig() {
        val provider = apiKeyStore.getProvider()
        val orModel = apiKeyStore.getOpenRouterModelId()
        val geminiKey = apiKeyStore.resolveGeminiKey()
        val orKey = apiKeyStore.getOpenRouterKey()

        if (geminiKey.isNotBlank()) GeminiClient.setApiKey(geminiKey)
        if (orKey.isNotBlank()) OpenRouterClient.setApiKey(orKey)

        LlmGateway.activeProvider = provider
        LlmGateway.openRouterModelId = orModel

        _uiState.update {
            it.copy(
                llmProvider = provider,
                openRouterModelId = orModel,
                hasLlmKey = LlmGateway.isConfigured(),
                llmStatusLabel = LlmGateway.statusLabel()
            )
        }
    }

    fun getStoredGeminiKey(): String = apiKeyStore.getGeminiKey()
    fun getStoredOpenRouterKey(): String = apiKeyStore.getOpenRouterKey()

    fun saveLlmSettings(
        provider: LlmProvider,
        geminiKey: String,
        openRouterKey: String,
        openRouterModelId: String
    ) {
        apiKeyStore.setProvider(provider)
        apiKeyStore.setGeminiKey(geminiKey)
        apiKeyStore.setOpenRouterKey(openRouterKey)
        apiKeyStore.setOpenRouterModelId(openRouterModelId.ifBlank { LlmProvider.DEFAULT_OPENROUTER_MODEL })

        val gKey = apiKeyStore.resolveGeminiKey()
        GeminiClient.setApiKey(gKey)
        OpenRouterClient.setApiKey(apiKeyStore.getOpenRouterKey())

        LlmGateway.activeProvider = provider
        LlmGateway.openRouterModelId = apiKeyStore.getOpenRouterModelId()

        _uiState.update {
            it.copy(
                llmProvider = provider,
                openRouterModelId = LlmGateway.openRouterModelId,
                hasLlmKey = LlmGateway.isConfigured(),
                llmStatusLabel = LlmGateway.statusLabel(),
                apiKeyStatus = "Salvo · ${LlmGateway.statusLabel()}"
            )
        }
    }

    fun setMode(mode: AgentMode) { _uiState.update { it.copy(selectedMode = mode) } }
    fun setSpeed(speed: ReasoningSpeed) { _uiState.update { it.copy(selectedSpeed = speed) } }
    fun setPrompt(prompt: String) { _uiState.update { it.copy(currentPrompt = prompt) } }
    fun setTab(tab: Int) { _uiState.update { it.copy(activeTab = tab) } }
    fun selectFile(file: RepoFile) { _uiState.update { it.copy(selectedFile = file) } }

    fun toggleToolExpand(messageId: String, toolId: String) {
        _uiState.update { state ->
            state.copy(messages = state.messages.map { msg ->
                if (msg.id == messageId) {
                    msg.copy(toolCalls = msg.toolCalls.map { t ->
                        if (t.id == toolId) t.copy(isExpanded = !t.isExpanded) else t
                    })
                } else msg
            })
        }
    }

    fun stopExecution() {
        mutationDecision?.complete(false)
        mutationDecision = null
        activeJob?.cancel()
        activeJob = null
        _uiState.update { it.copy(isThinking = false, pendingMutation = null) }
    }

    fun respondToMutation(approved: Boolean) {
        mutationDecision?.complete(approved)
        mutationDecision = null
        _uiState.update { it.copy(pendingMutation = null) }
    }

    fun connectRepository(repository: String, token: String = "") {
        if (_uiState.value.isConnectingRepo) return
        _uiState.update { it.copy(isConnectingRepo = true, repositoryStatus = "Conectando ao GitHub…") }
        viewModelScope.launch {
            githubClient.readRepository(repository, token).fold(
                onSuccess = { snapshot ->
                    sessionToken = token.trim()
                    defaultBranch = snapshot.defaultBranch
                    val files = snapshot.files.map {
                        RepoFile(path = it.path, language = it.language, content = it.content)
                    }
                    engine.syncRepositoryToWorkspace(files)
                    _uiState.update {
                        it.copy(
                            activeRepo = snapshot.fullName,
                            repoFiles = files,
                            selectedFile = files.firstOrNull(),
                            isConnectingRepo = false,
                            hasWriteToken = sessionToken.isNotBlank(),
                            repositoryStatus = "Conectado a ${snapshot.fullName} · ${files.size} arquivos"
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isConnectingRepo = false, repositoryStatus = err.message ?: "Falha")
                    }
                }
            )
        }
    }

    fun runTerminalCommand(command: String) {
        val cmd = command.trim()
        if (cmd.isBlank()) return
        viewModelScope.launch {
            val lines = mutableListOf("> $cmd")
            try {
                val result = withContext(Dispatchers.IO) { commandRunner.run(cmd) }
                if (result.output.isNotBlank()) lines += result.output
                lines += if (result.timedOut) "[TIMEOUT]" else "[exit ${result.exitCode}]"
            } catch (e: Exception) {
                lines += (e.message ?: "Erro")
            }
            _uiState.update { state ->
                state.copy(
                    terminalLogs = (state.terminalLogs + lines).takeLast(200),
                    commandsExecuted = state.commandsExecuted + 1
                )
            }
        }
    }

    fun commitFileToGitHub(path: String, commitMessage: String = "") {
        val repo = _uiState.value.activeRepo
        if (repo.isBlank()) {
            _uiState.update { it.copy(lastCommitStatus = "Conecte um repositório antes.") }
            return
        }
        if (sessionToken.isBlank()) {
            _uiState.update { it.copy(lastCommitStatus = "Token contents:write necessário.") }
            return
        }
        val file = File(workspaceDir, path)
        if (!file.isFile) {
            _uiState.update { it.copy(lastCommitStatus = "Arquivo não encontrado: $path") }
            return
        }
        _uiState.update { it.copy(isCommitting = true, lastCommitStatus = "Enviando $path…") }
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
            val msg = commitMessage.ifBlank { "DevAgent: atualiza $path" }
            githubClient.commitFile(repo, path, content, msg, sessionToken, defaultBranch).fold(
                onSuccess = { commit ->
                    _uiState.update {
                        it.copy(
                            isCommitting = false,
                            lastCommitStatus = "Commit OK: ${commit.commitSha.take(7)}",
                            repoFiles = it.repoFiles.map { f ->
                                if (f.path == path) f.copy(isModified = false, content = content) else f
                            }
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isCommitting = false, lastCommitStatus = err.message ?: "Falha")
                    }
                }
            )
        }
    }

    fun submitPrompt() {
        val prompt = _uiState.value.currentPrompt.trim()
        if (prompt.isBlank() || _uiState.value.isThinking) return

        if (!LlmGateway.isConfigured()) {
            _uiState.update {
                it.copy(
                    messages = it.messages + ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = MessageRole.SYSTEM,
                        text = "Configure a API key em Configurações (engrenagem). " +
                            "Provedor atual: ${_uiState.value.llmProvider.label}."
                    )
                )
            }
            return
        }

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

        _uiState.update {
            it.copy(
                messages = it.messages + listOf(userMessage, initialAgentMessage),
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
                    confirmMutation = { _, path, contentPreview ->
                        val oldContent = _uiState.value.repoFiles.find { it.path == path }?.content.orEmpty()
                        val deferred = CompletableDeferred<Boolean>()
                        mutationDecision = deferred
                        _uiState.update {
                            it.copy(
                                pendingMutation = PendingMutation(
                                    toolName = "write_file",
                                    path = path,
                                    contentPreview = contentPreview,
                                    messageId = agentMessageId,
                                    diffLines = buildSimpleDiff(oldContent, contentPreview)
                                )
                            )
                        }
                        deferred.await()
                    }
                ).collect { event ->
                    when (event) {
                        is AgentExecutionEvent.ThinkingTick ->
                            _uiState.update { s ->
                                s.copy(
                                    thinkingSeconds = event.seconds,
                                    messages = s.messages.map {
                                        if (it.id == agentMessageId) it.copy(thinkingSeconds = event.seconds) else it
                                    }
                                )
                            }
                        is AgentExecutionEvent.ThoughtStepAdded ->
                            _uiState.update { s ->
                                s.copy(messages = s.messages.map {
                                    if (it.id == agentMessageId) it.copy(thoughtSteps = it.thoughtSteps + event.step) else it
                                })
                            }
                        is AgentExecutionEvent.ToolCallStarted ->
                            _uiState.update { s ->
                                s.copy(
                                    commandsExecuted = s.commandsExecuted + 1,
                                    messages = s.messages.map {
                                        if (it.id == agentMessageId) it.copy(toolCalls = it.toolCalls + event.toolCall) else it
                                    }
                                )
                            }
                        is AgentExecutionEvent.ToolCallUpdated ->
                            _uiState.update { s ->
                                s.copy(messages = s.messages.map { msg ->
                                    if (msg.id != agentMessageId) msg
                                    else {
                                        val exists = msg.toolCalls.any { it.id == event.toolCall.id }
                                        msg.copy(
                                            toolCalls = if (exists) msg.toolCalls.map {
                                                if (it.id == event.toolCall.id) event.toolCall else it
                                            } else msg.toolCalls + event.toolCall
                                        )
                                    }
                                })
                            }
                        is AgentExecutionEvent.MutationProposed -> {}
                        is AgentExecutionEvent.FinalResponseChunk -> {
                            _uiState.update { s ->
                                s.copy(
                                    isThinking = false,
                                    pendingMutation = null,
                                    messages = s.messages.map {
                                        if (it.id == agentMessageId) it.copy(
                                            text = event.text, isThinking = false, metrics = event.metrics
                                        ) else it
                                    }
                                )
                            }
                            refreshWorkspaceFiles()
                        }
                        is AgentExecutionEvent.Error ->
                            _uiState.update { s ->
                                s.copy(
                                    isThinking = false,
                                    pendingMutation = null,
                                    messages = s.messages.map {
                                        if (it.id == agentMessageId) it.copy(text = event.message, isThinking = false) else it
                                    }
                                )
                            }
                    }
                }
            } catch (e: Exception) {
                mutationDecision?.complete(false)
                mutationDecision = null
                _uiState.update { s ->
                    s.copy(
                        isThinking = false,
                        pendingMutation = null,
                        messages = s.messages.map {
                            if (it.id == agentMessageId) it.copy(
                                text = "Interrompido: ${e.message ?: "erro"}",
                                isThinking = false
                            ) else it
                        }
                    )
                }
            }
        }
    }

    private fun buildSimpleDiff(oldText: String, newText: String): List<DiffLine> {
        val oldLines = oldText.lines()
        val newLines = newText.lines()
        if (oldLines == newLines) return listOf(DiffLine(DiffLineType.CONTEXT, "(sem diferenças)"))
        val result = mutableListOf(
            DiffLine(DiffLineType.HEADER, "--- anterior"),
            DiffLine(DiffLineType.HEADER, "+++ proposto")
        )
        var shown = 0
        val maxLen = maxOf(oldLines.size, newLines.size)
        for (i in 0 until maxLen) {
            if (shown >= 80) {
                result += DiffLine(DiffLineType.CONTEXT, "…")
                break
            }
            val o = oldLines.getOrNull(i)
            val n = newLines.getOrNull(i)
            when {
                o == n -> {}
                o != null && n == null -> { result += DiffLine(DiffLineType.REMOVED, "- $o"); shown++ }
                o == null && n != null -> { result += DiffLine(DiffLineType.ADDED, "+ $n"); shown++ }
                o != null && n != null -> {
                    result += DiffLine(DiffLineType.REMOVED, "- $o")
                    result += DiffLine(DiffLineType.ADDED, "+ $n")
                    shown += 2
                }
            }
        }
        if (result.size <= 2) result += DiffLine(DiffLineType.ADDED, "(arquivo novo ou alteração completa)")
        return result
    }

    private fun refreshWorkspaceFiles() {
        viewModelScope.launch {
            try {
                if (!workspaceDir.exists()) return@launch
                val files = workspaceDir.walkTopDown().filter { it.isFile }.mapNotNull { file ->
                    val relative = file.relativeTo(workspaceDir).invariantSeparatorsPath
                    try {
                        RepoFile(relative, languageFor(relative), file.readText().take(50_000), isModified = true)
                    } catch (_: Exception) { null }
                }.sortedBy { it.path }.toList()
                if (files.isNotEmpty()) {
                    _uiState.update { s ->
                        s.copy(
                            repoFiles = files,
                            selectedFile = s.selectedFile?.let { sel -> files.find { it.path == sel.path } } ?: files.firstOrNull()
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun languageFor(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "md" -> "markdown"
        "py" -> "python"
        else -> "text"
    }
}
