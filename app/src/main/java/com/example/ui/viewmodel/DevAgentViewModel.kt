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
import com.example.data.engine.WorkspaceChangeTracker
import com.example.data.model.*
import com.example.data.update.AppUpdateChecker
import com.example.data.update.AppUpdateInfo
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
    val apiKeyStatus: String = "",
    val updateAvailable: AppUpdateInfo? = null,
    val updateStatus: String = "",
    val isCheckingUpdate: Boolean = false,
    val isDownloadingUpdate: Boolean = false,
    val updateDownloadProgress: Float = 0f,
    val updateApkReady: Boolean = false,
    val updateBannerDismissed: Boolean = false
)

class DevAgentViewModel(application: Application) : AndroidViewModel(application) {

    private val workspaceDir = File(getApplication<Application>().filesDir, "devagent-workspace")
    private val engine: DevAgentEngine by lazy { DevAgentEngine(workspaceDir) }
    private val githubClient = GitHubRepositoryClient()
    private val commandRunner by lazy { WorkspaceCommandRunner(workspaceDir) }
    private val apiKeyStore = ApiKeyStore(application)
    private val updateChecker = AppUpdateChecker()
    private var activeJob: Job? = null

    private var sessionToken: String = ""
    private var defaultBranch: String = "main"
    private var repositoryBaseline: Map<String, String>? = null
    private var mutationDecision: CompletableDeferred<Boolean>? = null

    private val _uiState = MutableStateFlow(DevAgentUiState())
    val uiState: StateFlow<DevAgentUiState> = _uiState.asStateFlow()

    init {
        applyStoredLlmConfig()
        // Auto-update: checa e baixa em background ao abrir
        checkForAppUpdate(autoDownload = true)
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

    fun checkForAppUpdate(autoDownload: Boolean = true) {
        if (_uiState.value.isCheckingUpdate || _uiState.value.isDownloadingUpdate) return
        _uiState.update {
            it.copy(
                isCheckingUpdate = true,
                updateStatus = "Procurando atualização…",
                updateBannerDismissed = false
            )
        }
        viewModelScope.launch {
            updateChecker.checkForUpdate().fold(
                onSuccess = { info ->
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateAvailable = info,
                            updateStatus = if (info == null) "App atualizado."
                            else "Nova versão ${info.versionName} disponível"
                        )
                    }
                    if (info != null && autoDownload) downloadUpdateOnly()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isCheckingUpdate = false, updateStatus = "Falha ao checar: ${e.message}")
                    }
                }
            )
        }
    }

    fun dismissUpdateBanner() {
        _uiState.update { it.copy(updateBannerDismissed = true) }
    }

    fun downloadUpdateOnly() {
        val info = _uiState.value.updateAvailable ?: return
        if (_uiState.value.isDownloadingUpdate || _uiState.value.updateApkReady) return
        val app = getApplication<Application>()
        _uiState.update {
            it.copy(isDownloadingUpdate = true, updateDownloadProgress = 0f, updateStatus = "Baixando…")
        }
        viewModelScope.launch {
            updateChecker.downloadApk(app, info.apkUrl) { downloaded, total ->
                if (total > 0) {
                    val p = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    _uiState.update { it.copy(updateDownloadProgress = p) }
                }
            }.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isDownloadingUpdate = false,
                            updateApkReady = true,
                            updateDownloadProgress = 1f,
                            updateStatus = "Atualização pronta"
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isDownloadingUpdate = false, updateStatus = "Download falhou: ${e.message}")
                    }
                }
            )
        }
    }

    fun downloadAndInstallUpdate() {
        val info = _uiState.value.updateAvailable ?: return
        val app = getApplication<Application>()
        if (!updateChecker.canRequestPackageInstalls(app)) {
            _uiState.update {
                it.copy(updateStatus = "Permita instalar apps deste app e volte.")
            }
            try {
                app.startActivity(updateChecker.installPermissionSettingsIntent(app))
            } catch (_: Exception) {
            }
            return
        }
        viewModelScope.launch {
            val cached = File(app.cacheDir, "updates/devagent-update.apk")
            val file = if (cached.isFile && cached.length() > 10_000L) {
                cached
            } else {
                _uiState.update { it.copy(isDownloadingUpdate = true, updateStatus = "Baixando APK…") }
                updateChecker.downloadApk(app, info.apkUrl) { downloaded, total ->
                    if (total > 0) {
                        _uiState.update {
                            it.copy(updateDownloadProgress = (downloaded.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }.getOrElse { e ->
                    _uiState.update {
                        it.copy(isDownloadingUpdate = false, updateStatus = "Download falhou: ${e.message}")
                    }
                    return@launch
                }
            }
            _uiState.update {
                it.copy(isDownloadingUpdate = false, updateApkReady = true, updateStatus = "Abrindo instalador…")
            }
            try {
                app.startActivity(updateChecker.installApk(app, file))
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(updateStatus = "Não foi possível abrir instalador: ${e.message}")
                }
            }
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
                    repositoryBaseline = files.associate { it.path to it.content }
                    val sync = withContext(Dispatchers.IO) {
                        engine.syncRepositoryToWorkspace(files)
                    }
                    val syncSummary = buildString {
                        append("Conectado a ${snapshot.fullName} · ${files.size} arquivos")
                        append(" · workspace: ${sync.copiedFiles} copiados")
                        if (sync.failedFiles.isNotEmpty()) {
                            append(" · ${sync.failedFiles.size} falhas")
                            sync.errors.take(3).forEach { error ->
                                append("\n")
                                append(error)
                            }
                            if (sync.errors.size > 3) {
                                append("\n… e mais ${sync.errors.size - 3} erro(s)")
                            }
                        }
                    }
                    _uiState.update {
                        it.copy(
                            activeRepo = snapshot.fullName,
                            repoFiles = files,
                            selectedFile = files.firstOrNull(),
                            isConnectingRepo = false,
                            hasWriteToken = sessionToken.isNotBlank(),
                            repositoryStatus = syncSummary
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


    fun commitModifiedFilesToGitHub(commitMessage: String = "") {
        val state = _uiState.value
        val repo = state.activeRepo
        val modified = state.repoFiles.filter { it.isModified }
        if (repo.isBlank()) {
            _uiState.update { it.copy(lastCommitStatus = "Conecte um repositório antes.") }
            return
        }
        if (sessionToken.isBlank()) {
            _uiState.update { it.copy(lastCommitStatus = "Token contents:write necessário.") }
            return
        }
        if (modified.isEmpty()) {
            _uiState.update { it.copy(lastCommitStatus = "Nenhum arquivo modificado para enviar.") }
            return
        }
        if (modified.size > 50) {
            _uiState.update { it.copy(lastCommitStatus = "Limite de 50 arquivos por commit. Divida as alterações.") }
            return
        }
        if (state.isCommitting) return
        _uiState.update { it.copy(isCommitting = true, lastCommitStatus = "Preparando ${modified.size} arquivo(s)…") }
        viewModelScope.launch {
            try {
                val contents = withContext(Dispatchers.IO) {
                    val root = workspaceDir.canonicalFile
                    modified.associate { entry ->
                        val target = File(root, entry.path).canonicalFile
                        if (target == root || !target.toPath().startsWith(root.toPath())) {
                            throw IllegalArgumentException("Caminho fora do workspace: ${entry.path}")
                        }
                        if (!target.isFile) throw IllegalArgumentException("Arquivo não encontrado: ${entry.path}")
                        entry.path to target.readText(Charsets.UTF_8)
                    }
                }
                val msg = commitMessage.ifBlank { "DevAgent: atualiza ${contents.size} arquivos" }
                githubClient.commitFiles(repo, contents, msg, sessionToken, defaultBranch).fold(
                    onSuccess = { commit ->
                        repositoryBaseline = repositoryBaseline.orEmpty() + contents
                        _uiState.update { current ->
                            current.copy(
                                isCommitting = false,
                                lastCommitStatus = "Commit OK: ${commit.commitSha.take(7)} · ${contents.size} arquivo(s)",
                                repoFiles = current.repoFiles.map { file ->
                                    contents[file.path]?.let { content ->
                                        file.copy(content = content, isModified = false)
                                    } ?: file
                                },
                                selectedFile = current.selectedFile?.let { selected ->
                                    contents[selected.path]?.let { content ->
                                        selected.copy(content = content, isModified = false)
                                    } ?: selected
                                }
                            )
                        }
                    },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(isCommitting = false, lastCommitStatus = err.message ?: "Falha no commit em lote")
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isCommitting = false, lastCommitStatus = e.message ?: "Falha ao preparar commit em lote")
                }
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
                    repositoryBaseline = repositoryBaseline.orEmpty() + (path to content)
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
                        text = "Configure a API key em Configurações (engrenagem)."
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
                        is AgentExecutionEvent.MutationProposed -> { }
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
                            refreshWorkspaceListing()
                        }
                        is AgentExecutionEvent.Error -> {
                            _uiState.update { s ->
                                s.copy(
                                    isThinking = false,
                                    pendingMutation = null,
                                    messages = s.messages.map {
                                        if (it.id == agentMessageId) it.copy(
                                            text = event.message, isThinking = false
                                        ) else it
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { s ->
                    s.copy(
                        isThinking = false,
                        pendingMutation = null,
                        messages = s.messages.map {
                            if (it.id == agentMessageId) it.copy(
                                text = e.message ?: "Erro", isThinking = false
                            ) else it
                        }
                    )
                }
            } finally {
                activeJob = null
                mutationDecision = null
            }
        }
    }

    private fun buildSimpleDiff(oldContent: String, newContent: String): List<DiffLine> {
        val oldLines = oldContent.lines()
        val newLines = newContent.lines()
        val result = mutableListOf<DiffLine>()
        val max = maxOf(oldLines.size, newLines.size).coerceAtMost(80)
        for (i in 0 until max) {
            val o = oldLines.getOrNull(i)
            val n = newLines.getOrNull(i)
            when {
                o == null && n != null -> result += DiffLine(DiffLineType.ADDED, "+ $n")
                n == null && o != null -> result += DiffLine(DiffLineType.REMOVED, "- $o")
                o != null && n != null && o != n -> {
                    result += DiffLine(DiffLineType.REMOVED, "- $o")
                    result += DiffLine(DiffLineType.ADDED, "+ $n")
                }
                o != null -> result += DiffLine(DiffLineType.CONTEXT, "  $o")
            }
        }
        return result
    }

    private fun refreshWorkspaceListing() {
        viewModelScope.launch {
            try {
                val root = workspaceDir
                if (!root.exists()) return@launch
                val files = withContext(Dispatchers.IO) {
                    root.walkTopDown().filter { it.isFile }.map { f ->
                        val rel = f.relativeTo(root).path.replace('\\', '/')
                        RepoFile(
                            path = rel,
                            language = languageFor(rel),
                            content = try { f.readText() } catch (_: Exception) { "" }
                        )
                    }.sortedBy { it.path }.toList()
                }
                if (files.isNotEmpty()) {
                    val trackedFiles = WorkspaceChangeTracker.markChanges(files, repositoryBaseline)
                    _uiState.update { s ->
                        s.copy(
                            repoFiles = trackedFiles,
                            selectedFile = s.selectedFile?.let { sel ->
                                files.find { it.path == sel.path }
                            } ?: files.firstOrNull()
                        )
                    }
                }
            } catch (_: Exception) {
            }
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
