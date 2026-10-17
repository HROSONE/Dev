package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.GitHubRepositoryClient
import com.example.data.engine.AgentExecutionEvent
import com.example.data.engine.DevAgentEngine
import com.example.data.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class DevAgentUiState(
    val selectedMode: AgentMode = AgentMode.BUILD,
    val selectedSpeed: ReasoningSpeed = ReasoningSpeed.DEEP_THINKING,
    val activeRepo: String = "octocat/modern-auth-service",
    val messages: List<ChatMessage> = emptyList(),
    val isThinking: Boolean = false,
    val thinkingSeconds: Double = 0.0,
    val commandsExecuted: Int = 0,
    val currentPrompt: String = "",
    val activeTab: Int = 0, // 0: Chat, 1: Sandbox & Files, 2: Arquitetura
    val repoFiles: List<RepoFile> = emptyList(),
    val selectedFile: RepoFile? = null,
    val isConnectingRepo: Boolean = false,
    val repositoryStatus: String = ""
)

class DevAgentViewModel : ViewModel() {

    private val engine = DevAgentEngine()
    private val githubClient = GitHubRepositoryClient()
    private var activeJob: Job? = null

    private val _uiState = MutableStateFlow(
        DevAgentUiState(
            repoFiles = emptyList()
        )
    )
    val uiState: StateFlow<DevAgentUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(selectedFile = it.repoFiles.firstOrNull()) }
    }

    fun setMode(mode: AgentMode) {
        _uiState.update { it.copy(selectedMode = mode) }
    }

    /**
     * Reads real repository files from GitHub. The optional token is used only for this request
     * and is not saved in the ViewModel or persistent storage.
     */
    fun connectRepository(repository: String, token: String = "") {
        if (_uiState.value.isConnectingRepo) return
        _uiState.update {
            it.copy(isConnectingRepo = true, repositoryStatus = "Conectando ao GitHub…")
        }
        viewModelScope.launch {
            val result = githubClient.readRepository(repository, token)
            result.fold(
                onSuccess = { snapshot ->
                    val files = snapshot.files.map { remote ->
                        RepoFile(
                            path = remote.path,
                            language = remote.language,
                            content = remote.content
                        )
                    }
                    _uiState.update { state ->
                        state.copy(
                            activeRepo = snapshot.fullName,
                            repoFiles = files,
                            selectedFile = files.firstOrNull(),
                            isConnectingRepo = false,
                            repositoryStatus = "Conectado a ${snapshot.fullName} · branch ${snapshot.defaultBranch} · ${files.size} arquivos lidos"
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
        activeJob?.cancel()
        activeJob = null
        _uiState.update { it.copy(isThinking = false) }
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
                thinkingSeconds = 0.0
            )
        }

        activeJob = viewModelScope.launch {
            try {
                engine.executeTask(
                    userPrompt = prompt,
                    mode = _uiState.value.selectedMode,
                    speed = _uiState.value.selectedSpeed,
                    repositoryName = _uiState.value.activeRepo
                ).collect { event ->
                    when (event) {
                        is AgentExecutionEvent.ThinkingTick -> {
                            _uiState.update { state ->
                                val updatedMessages = state.messages.map { msg ->
                                    if (msg.id == agentMessageId) {
                                        msg.copy(thinkingSeconds = event.seconds)
                                    } else msg
                                }
                                state.copy(
                                    thinkingSeconds = event.seconds,
                                    messages = updatedMessages
                                )
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
                                        val updatedTools = msg.toolCalls.map { t ->
                                            if (t.id == event.toolCall.id) event.toolCall else t
                                        }
                                        msg.copy(toolCalls = updatedTools)
                                    } else msg
                                }
                                state.copy(messages = updatedMessages)
                            }
                        }

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
                                    isThinking = false
                                )
                            }
                        }

                        is AgentExecutionEvent.Error -> {
                            _uiState.update { state ->
                                state.copy(
                                    isThinking = false,
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
                _uiState.update { state ->
                    state.copy(
                        isThinking = false,
                        messages = state.messages.map { msg ->
                            if (msg.id == agentMessageId) {
                                msg.copy(
                                    text = "A execução foi interrompida: ${e.message ?: "erro inesperado"}. Nenhuma alteração foi confirmada.",
                                    isThinking = false
                                )
                            } else msg
                        }
                    )
                }
            }
        }
    }

}
