package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.ApiKeyStore
import com.example.data.api.GeminiClient
import com.example.data.api.GitHubRepositoryClient
import com.example.data.api.LlmGateway
import com.example.data.api.LocalModelManager
import com.example.data.api.OpenRouterClient
import com.example.data.api.QwenCoderClient
import com.example.data.engine.AgentExecutionEvent
import com.example.data.engine.ChatHistoryStore
import com.example.data.engine.DevAgentEngine
import com.example.data.engine.LocalProjectCreator
import com.example.data.engine.WorkspaceChangeTracker
import com.example.data.engine.WorkspaceCommandRunner
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
    val autoApproveEdits: Boolean = true,
    val activeRepo: String = "",
    val isLocalOnlyProject: Boolean = false,
    val activeSessionId: String = "",
    val chatSessions: List<ChatSessionSummary> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val isThinking: Boolean = false,
    val thinkingSeconds: Double = 0.0,
    val commandsExecuted: Int = 0,
    val currentPrompt: String = "",
    val activeTab: Int = 0,
    val repoFiles: List<RepoFile> = emptyList(),
    val selectedFile: RepoFile? = null,
    val isConnectingRepo: Boolean = false,
    val isCreatingGitHubRepo: Boolean = false,
    val repositoryStatus: String = "",
    val pendingMutation: PendingMutation? = null,
    val terminalLogs: List<String> = listOf(
        "Workspace local ativo. Comandos: pwd, ls, cat, grep, find, head, tail, wc, tree.",
        "Ex.: tree  |  grep ViewModel app/src  |  cat README.md"
    ),
    val isCommitting: Boolean = false,
    val lastCommitStatus: String = "",
    val hasWriteToken: Boolean = false,
    val hasLlmKey: Boolean = false,
    val llmProvider: LlmProvider = LlmProvider.GEMINI,
    val openRouterModelId: String = LlmProvider.DEFAULT_OPENROUTER_MODEL,
    val qwenVariant: QwenModelVariant = QwenModelVariant.QWEN_1_5B,
    val qwenLocalEndpoint: String = QwenCoderClient.DEFAULT_LOCAL_ENDPOINT,
    val isQwenModelDownloaded: Boolean = false,
    val isDownloadingQwenModel: Boolean = false,
    val qwenDownloadProgress: Float = 0f,
    val qwenDownloadStatus: String = "",
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
    private val localModelManager = LocalModelManager(application)
    private val chatHistoryStore = ChatHistoryStore(application)
    private val updateChecker = AppUpdateChecker()
    private var activeJob: Job? = null
    private var qwenDownloadJob: Job? = null

    private var sessionToken: String = ""
    private var defaultBranch: String = "main"
    private var repositoryBaseline: Map<String, String>? = null
    private var mutationDecision: CompletableDeferred<Boolean>? = null

    private val _uiState = MutableStateFlow(DevAgentUiState())
    val uiState: StateFlow<DevAgentUiState> = _uiState.asStateFlow()

    init {
        applyStoredLlmConfig()
        restoreLastChatSession()
        refreshWorkspaceListing()
        checkForAppUpdate(autoDownload = true)
    }

    private fun restoreLastChatSession() {
        val sessions = chatHistoryStore.listSessions()
        val lastId = chatHistoryStore.getActiveSessionId().ifBlank {
            sessions.firstOrNull()?.id.orEmpty()
        }
        if (lastId.isNotBlank()) {
            val loaded = chatHistoryStore.loadSession(lastId)
            if (loaded != null) {
                val (savedRepo, savedMessages) = loaded
                _uiState.update {
                    it.copy(
                        activeSessionId = lastId,
                        activeRepo = savedRepo.ifBlank { it.activeRepo },
                        messages = savedMessages,
                        chatSessions = sessions
                    )
                }
                return
            }
        }
        val newId = chatHistoryStore.newSessionId()
        chatHistoryStore.setActiveSessionId(newId)
        _uiState.update {
            it.copy(activeSessionId = newId, chatSessions = sessions)
        }
    }

    private fun persistCurrentChatSession() {
        val state = _uiState.value
        val sessionId = state.activeSessionId.ifBlank { chatHistoryStore.newSessionId() }
        chatHistoryStore.saveSession(
            sessionId = sessionId,
            activeRepo = state.activeRepo,
            messages = state.messages
        )
        _uiState.update {
            it.copy(
                activeSessionId = sessionId,
                chatSessions = chatHistoryStore.listSessions()
            )
        }
    }

    fun startNewChatSession() {
        stopExecution()
        val newId = chatHistoryStore.newSessionId()
        chatHistoryStore.setActiveSessionId(newId)
        _uiState.update {
            it.copy(
                activeSessionId = newId,
                messages = emptyList(),
                thinkingSeconds = 0.0,
                chatSessions = chatHistoryStore.listSessions()
            )
        }
    }

    fun selectChatSession(sessionId: String) {
        stopExecution()
        val loaded = chatHistoryStore.loadSession(sessionId) ?: return
        chatHistoryStore.setActiveSessionId(sessionId)
        val (savedRepo, savedMessages) = loaded
        _uiState.update {
            it.copy(
                activeSessionId = sessionId,
                activeRepo = savedRepo.ifBlank { it.activeRepo },
                messages = savedMessages,
                chatSessions = chatHistoryStore.listSessions()
            )
        }
    }

    fun deleteChatSession(sessionId: String) {
        chatHistoryStore.deleteSession(sessionId)
        val remaining = chatHistoryStore.listSessions()
        if (_uiState.value.activeSessionId == sessionId) {
            val next = remaining.firstOrNull()
            if (next != null) {
                selectChatSession(next.id)
            } else {
                startNewChatSession()
            }
        } else {
            _uiState.update { it.copy(chatSessions = remaining) }
        }
    }

    private fun applyStoredLlmConfig() {
        val provider = apiKeyStore.getProvider()
        val orModel = apiKeyStore.getOpenRouterModelId()
        val geminiKey = apiKeyStore.resolveGeminiKey()
        val orKey = apiKeyStore.getOpenRouterKey()
        val qwenVariant = apiKeyStore.getQwenVariant()
        val qwenEndpoint = apiKeyStore.getQwenLocalEndpoint()
        val hfKey = apiKeyStore.getHuggingFaceKey()

        if (geminiKey.isNotBlank()) GeminiClient.setApiKey(geminiKey)
        if (orKey.isNotBlank()) OpenRouterClient.setApiKey(orKey)

        QwenCoderClient.activeVariant = qwenVariant
        QwenCoderClient.localEndpointUrl = qwenEndpoint
        QwenCoderClient.hfApiKey = hfKey
        val downloaded = localModelManager.isModelDownloaded(qwenVariant)
        QwenCoderClient.downloadedModelPath =
            if (downloaded) localModelManager.getModelFile(qwenVariant).absolutePath else ""

        LlmGateway.activeProvider = provider
        LlmGateway.openRouterModelId = orModel

        val dlBytes = localModelManager.getDownloadedSizeBytes(qwenVariant)
        val dlStatus = if (downloaded) {
            "Baixado no celular (${LocalModelManager.formatSize(dlBytes)})"
        } else if (dlBytes > 0L) {
            "Parcial (${LocalModelManager.formatSize(dlBytes)} / ${LocalModelManager.formatSize(qwenVariant.approxBytes)})"
        } else {
            "Não baixado (~${LocalModelManager.formatSize(qwenVariant.approxBytes)})"
        }

        _uiState.update {
            it.copy(
                llmProvider = provider,
                openRouterModelId = orModel,
                qwenVariant = qwenVariant,
                qwenLocalEndpoint = qwenEndpoint,
                isQwenModelDownloaded = downloaded,
                qwenDownloadStatus = dlStatus,
                hasLlmKey = LlmGateway.isConfigured(),
                llmStatusLabel = LlmGateway.statusLabel()
            )
        }
    }

    fun getStoredGeminiKey(): String = apiKeyStore.getGeminiKey()
    fun getStoredOpenRouterKey(): String = apiKeyStore.getOpenRouterKey()
    fun getStoredHuggingFaceKey(): String = apiKeyStore.getHuggingFaceKey()

    fun saveLlmSettings(
        provider: LlmProvider,
        geminiKey: String,
        openRouterKey: String,
        openRouterModelId: String,
        qwenVariant: QwenModelVariant = _uiState.value.qwenVariant,
        qwenLocalEndpoint: String = _uiState.value.qwenLocalEndpoint,
        huggingFaceKey: String = getStoredHuggingFaceKey()
    ) {
        apiKeyStore.setProvider(provider)
        apiKeyStore.setGeminiKey(geminiKey)
        apiKeyStore.setOpenRouterKey(openRouterKey)
        apiKeyStore.setOpenRouterModelId(openRouterModelId.ifBlank { LlmProvider.DEFAULT_OPENROUTER_MODEL })
        apiKeyStore.setQwenVariant(qwenVariant)
        apiKeyStore.setQwenLocalEndpoint(qwenLocalEndpoint)
        apiKeyStore.setHuggingFaceKey(huggingFaceKey)

        applyStoredLlmConfig()
        _uiState.update {
            it.copy(apiKeyStatus = "Salvo · ${LlmGateway.statusLabel()}")
        }
    }

    fun downloadQwenModel(variant: QwenModelVariant, hfToken: String = "") {
        if (_uiState.value.isDownloadingQwenModel) return
        apiKeyStore.setQwenVariant(variant)
        if (hfToken.isNotBlank()) apiKeyStore.setHuggingFaceKey(hfToken)

        _uiState.update {
            it.copy(
                qwenVariant = variant,
                isDownloadingQwenModel = true,
                qwenDownloadProgress = 0f,
                qwenDownloadStatus = "Iniciando download de ${variant.fileName}…"
            )
        }

        qwenDownloadJob = viewModelScope.launch {
            localModelManager.downloadModel(
                variant = variant,
                hfToken = hfToken.ifBlank { apiKeyStore.getHuggingFaceKey() },
                onProgress = { downloaded, total ->
                    val progress = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                    _uiState.update {
                        it.copy(
                            qwenDownloadProgress = progress,
                            qwenDownloadStatus = "Baixando: ${LocalModelManager.formatSize(downloaded)} / ${LocalModelManager.formatSize(total)} (${(progress * 100).toInt()}%)"
                        )
                    }
                }
            ).fold(
                onSuccess = { file ->
                    QwenCoderClient.activeVariant = variant
                    QwenCoderClient.downloadedModelPath = file.absolutePath
                    _uiState.update {
                        it.copy(
                            isDownloadingQwenModel = false,
                            isQwenModelDownloaded = true,
                            qwenDownloadProgress = 1f,
                            qwenDownloadStatus = "Pronto no aparelho: ${file.name} (${LocalModelManager.formatSize(file.length())})",
                            hasLlmKey = LlmGateway.isConfigured(),
                            llmStatusLabel = LlmGateway.statusLabel()
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isDownloadingQwenModel = false,
                            qwenDownloadStatus = "Download pausado/falhou: ${e.message ?: "erro"}"
                        )
                    }
                }
            )
        }
    }

    fun cancelQwenDownload() {
        qwenDownloadJob?.cancel()
        qwenDownloadJob = null
        _uiState.update {
            it.copy(
                isDownloadingQwenModel = false,
                qwenDownloadStatus = "Download pausado (você pode retomar de onde parou)."
            )
        }
    }

    fun deleteDownloadedQwenModel(variant: QwenModelVariant) {
        cancelQwenDownload()
        localModelManager.deleteModel(variant)
        QwenCoderClient.downloadedModelPath = ""
        _uiState.update {
            it.copy(
                isQwenModelDownloaded = false,
                qwenDownloadProgress = 0f,
                qwenDownloadStatus = "Modelo removido do aparelho."
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
    fun toggleAutoApproveEdits() { _uiState.update { it.copy(autoApproveEdits = !it.autoApproveEdits) } }
    fun setPrompt(prompt: String) { _uiState.update { it.copy(currentPrompt = prompt) } }
    fun setTab(tab: Int) { _uiState.update { it.copy(activeTab = tab) } }
    fun selectFile(file: RepoFile) { _uiState.update { it.copy(selectedFile = file) } }

    /**
     * Cria um projeto / repositório local do zero diretamente no aparelho a partir de um template.
     */
    fun createLocalProject(projectName: String, template: ProjectTemplate) {
        val cleanName = projectName.trim().ifBlank { "meu-projeto" }
        viewModelScope.launch {
            try {
                val createdPaths = withContext(Dispatchers.IO) {
                    LocalProjectCreator.createProjectFromTemplate(
                        workspaceRoot = workspaceDir,
                        projectName = cleanName,
                        template = template
                    )
                }
                repositoryBaseline = emptyMap()
                _uiState.update {
                    it.copy(
                        activeRepo = "local/$cleanName",
                        isLocalOnlyProject = true,
                        repositoryStatus = "Projeto local '$cleanName' criado (${createdPaths.size} arquivos · ${template.label}). Você já pode editar ou publicar como repositório novo no GitHub."
                    )
                }
                refreshWorkspaceListing(selectPath = createdPaths.firstOrNull())
                persistCurrentChatSession()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(repositoryStatus = "Erro ao criar projeto local: ${e.message}")
                }
            }
        }
    }

    /**
     * Importa arquivos avulsos ou um pacote .zip inteiro selecionado da memória do celular.
     */
    fun importFilesFromDevice(uris: List<Uri>, targetSubfolder: String = "") {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    LocalProjectCreator.importUrisFromDevice(
                        context = app,
                        workspaceRoot = workspaceDir,
                        uris = uris,
                        targetSubfolder = targetSubfolder
                    )
                }
                if (repositoryBaseline == null) {
                    repositoryBaseline = emptyMap()
                }
                val currentRepo = _uiState.value.activeRepo.ifBlank { "local/projeto-importado" }
                _uiState.update {
                    it.copy(
                        activeRepo = currentRepo,
                        isLocalOnlyProject = currentRepo.startsWith("local/"),
                        repositoryStatus = "Importado(s) ${imported.size} arquivo(s) do celular para o workspace."
                    )
                }
                refreshWorkspaceListing(selectPath = imported.firstOrNull())
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(repositoryStatus = "Falha ao importar do celular: ${e.message}")
                }
            }
        }
    }

    /**
     * Publica o projeto local atual criando um repositório NOVO no GitHub (POST /user/repos)
     * e enviando todos os arquivos do workspace.
     */
    fun createGitHubRepoFromWorkspace(
        repoName: String,
        description: String,
        isPrivate: Boolean,
        token: String
    ) {
        if (_uiState.value.isCreatingGitHubRepo) return
        val effectiveToken = token.trim().ifBlank { sessionToken }
        if (effectiveToken.isBlank()) {
            _uiState.update {
                it.copy(lastCommitStatus = "Informe seu token do GitHub (escopo repo) para criar o repositório.")
            }
            return
        }
        _uiState.update {
            it.copy(
                isCreatingGitHubRepo = true,
                lastCommitStatus = "Criando repositório '$repoName' no GitHub e enviando arquivos…"
            )
        }
        viewModelScope.launch {
            try {
                val filesMap = withContext(Dispatchers.IO) {
                    val root = workspaceDir.canonicalFile
                    if (!root.exists()) emptyMap()
                    else {
                        root.walkTopDown()
                            .filter { it.isFile }
                            .take(50)
                            .associate { f ->
                                val rel = f.relativeTo(root).invariantSeparatorsPath
                                rel to (runCatching { f.readText(Charsets.UTF_8) }.getOrDefault(""))
                            }
                    }
                }

                githubClient.createRepositoryAndPush(
                    repoName = repoName,
                    description = description,
                    isPrivate = isPrivate,
                    files = filesMap,
                    token = effectiveToken
                ).fold(
                    onSuccess = { created ->
                        sessionToken = effectiveToken
                        defaultBranch = created.defaultBranch
                        repositoryBaseline = filesMap
                        _uiState.update {
                            it.copy(
                                isCreatingGitHubRepo = false,
                                activeRepo = created.fullName,
                                isLocalOnlyProject = false,
                                hasWriteToken = true,
                                repositoryStatus = "Conectado ao novo repositório ${created.fullName} (${filesMap.size} arquivos publicados)",
                                lastCommitStatus = "Repositório criado no GitHub: ${created.fullName} (${created.initialCommitSha.take(7)})"
                            )
                        }
                        refreshWorkspaceListing()
                        persistCurrentChatSession()
                    },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(
                                isCreatingGitHubRepo = false,
                                lastCommitStatus = err.message ?: "Falha ao criar repositório no GitHub"
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isCreatingGitHubRepo = false,
                        lastCommitStatus = e.message ?: "Erro ao preparar arquivos para o GitHub"
                    )
                }
            }
        }
    }

    fun saveManualFileEdit(relativePath: String, newContent: String) {
        val cleanPath = relativePath.trim().removePrefix("/")
        if (cleanPath.isBlank()) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val root = workspaceDir.canonicalFile
                    if (!root.exists()) root.mkdirs()
                    val target = File(root, cleanPath).canonicalFile
                    if (target == root || !target.toPath().startsWith(root.toPath())) {
                        throw IllegalArgumentException("Caminho fora dos limites do workspace.")
                    }
                    target.parentFile?.mkdirs()
                    target.writeText(newContent, Charsets.UTF_8)
                }
                if (repositoryBaseline == null) repositoryBaseline = emptyMap()
                refreshWorkspaceListing(selectPath = cleanPath)
                _uiState.update {
                    it.copy(repositoryStatus = "Arquivo salvo no workspace: $cleanPath")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(repositoryStatus = "Erro ao salvar $cleanPath: ${e.message}")
                }
            }
        }
    }

    fun revertFileToBaseline(relativePath: String) {
        val baselineContent = repositoryBaseline?.get(relativePath) ?: return
        saveManualFileEdit(relativePath, baselineContent)
    }

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
                            isLocalOnlyProject = false,
                            repoFiles = files,
                            selectedFile = files.firstOrNull(),
                            isConnectingRepo = false,
                            hasWriteToken = sessionToken.isNotBlank(),
                            repositoryStatus = syncSummary
                        )
                    }
                    persistCurrentChatSession()
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
        if (repo.isBlank() || repo.startsWith("local/")) {
            _uiState.update {
                it.copy(lastCommitStatus = "Este é um projeto local. Use 'Publicar Novo Repositório no GitHub' logo abaixo.")
            }
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
        if (repo.isBlank() || repo.startsWith("local/")) {
            _uiState.update {
                it.copy(lastCommitStatus = "Projeto local. Publique primeiro como um repositório no GitHub.")
            }
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
                        text = "Configure a API key ou baixe o Qwen 2.5 Coder em Configurações (engrenagem)."
                    )
                )
            }
            persistCurrentChatSession()
            return
        }

        val priorHistory = _uiState.value.messages
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
        persistCurrentChatSession()

        activeJob = viewModelScope.launch {
            try {
                engine.executeTask(
                    userPrompt = prompt,
                    mode = _uiState.value.selectedMode,
                    speed = _uiState.value.selectedSpeed,
                    repositoryName = _uiState.value.activeRepo,
                    repositoryFiles = _uiState.value.repoFiles,
                    conversationHistory = priorHistory,
                    confirmMutation = { toolName, path, contentPreview ->
                        if (repositoryBaseline == null) repositoryBaseline = emptyMap()
                        if (_uiState.value.autoApproveEdits) {
                            refreshWorkspaceListing()
                            return@executeTask true
                        }
                        val oldContent = _uiState.value.repoFiles.find { it.path == path }?.content.orEmpty()
                        val deferred = CompletableDeferred<Boolean>()
                        mutationDecision = deferred
                        _uiState.update {
                            it.copy(
                                pendingMutation = PendingMutation(
                                    toolName = toolName,
                                    path = path,
                                    contentPreview = contentPreview,
                                    messageId = agentMessageId,
                                    diffLines = DevAgentEngine.computeUnifiedDiff(oldContent, contentPreview)
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
                        is AgentExecutionEvent.ToolCallUpdated -> {
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
                            if (event.toolCall.type in setOf(ToolType.EDIT_FILE, ToolType.WRITE_FILE, ToolType.DELETE_FILE)) {
                                refreshWorkspaceListing()
                            }
                            persistCurrentChatSession()
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
                            persistCurrentChatSession()
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
                            refreshWorkspaceListing()
                            persistCurrentChatSession()
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
                persistCurrentChatSession()
            } finally {
                activeJob = null
                mutationDecision = null
            }
        }
    }

    private fun refreshWorkspaceListing(selectPath: String? = null) {
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
                val trackedFiles = WorkspaceChangeTracker.markChanges(files, repositoryBaseline)
                _uiState.update { s ->
                    val targetPath = selectPath ?: s.selectedFile?.path
                    s.copy(
                        repoFiles = trackedFiles,
                        selectedFile = targetPath?.let { p ->
                            trackedFiles.find { it.path == p }
                        } ?: trackedFiles.firstOrNull()
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun languageFor(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "xml" -> "xml"
        "json" -> "json"
        "md" -> "markdown"
        "py" -> "python"
        else -> "text"
    }
}
