package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val selectedFile: RepoFile? = null
)

class DevAgentViewModel : ViewModel() {

    private val engine = DevAgentEngine()
    private var activeJob: Job? = null

    private val _uiState = MutableStateFlow(
        DevAgentUiState(
            repoFiles = createInitialRepoFiles()
        )
    )
    val uiState: StateFlow<DevAgentUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(selectedFile = it.repoFiles.firstOrNull()) }
    }

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
                                // If patch was applied, mark files as modified
                                val updatedFiles = state.repoFiles.map { file ->
                                    if (file.path.contains("rate_limit") || file.path.contains("auth.py")) {
                                        file.copy(isModified = true)
                                    } else file
                                }

                                state.copy(
                                    messages = updatedMessages,
                                    isThinking = false,
                                    repoFiles = updatedFiles
                                )
                            }
                        }

                        is AgentExecutionEvent.Error -> {
                            _uiState.update { it.copy(isThinking = false) }
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isThinking = false) }
            }
        }
    }

    private fun createInitialRepoFiles(): List<RepoFile> {
        return listOf(
            RepoFile(
                path = "src/api/routes/auth.py",
                language = "python",
                content = """
                    from fastapi import APIRouter, Depends, HTTPException, status
                    from fastapi.security import OAuth2PasswordRequestForm
                    from src.core.security import create_access_token, verify_password
                    
                    router = APIRouter(prefix="/auth", tags=["auth"])
                    
                    @router.post("/login")
                    async def login(form_data: OAuth2PasswordRequestForm = Depends()):
                        # Validação de credenciais de usuário
                        user = authenticate_user(form_data.username, form_data.password)
                        if not user:
                            raise HTTPException(
                                status_code=status.HTTP_401_UNAUTHORIZED,
                                detail="Incorrect username or password"
                            )
                        token = create_access_token(data={"sub": user.username})
                        return {"access_token": token, "token_type": "bearer"}
                """.trimIndent()
            ),
            RepoFile(
                path = "src/middleware/rate_limit.py",
                language = "python",
                content = """
                    from fastapi import Request, HTTPException, status
                    import time
                    from collections import defaultdict
                    
                    class InMemoryRateLimiter:
                        def __init__(self, requests_per_minute: int = 60):
                            self.rate = requests_per_minute
                            self.history = defaultdict(list)
                            
                        async def __call__(self, request: Request):
                            client_ip = request.client.host
                            now = time.time()
                            self.history[client_ip] = [t for t in self.history[client_ip] if now - t < 60]
                            if len(self.history[client_ip]) >= self.rate:
                                raise HTTPException(
                                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                                    detail="Rate limit exceeded"
                                )
                            self.history[client_ip].append(now)
                """.trimIndent(),
                isModified = true
            ),
            RepoFile(
                path = "tests/test_auth.py",
                language = "python",
                content = """
                    import pytest
                    from fastapi.testclient import TestClient
                    from src.main import app
                    
                    client = TestClient(app)
                    
                    def test_login_success():
                        response = client.post("/auth/login", data={"username": "alice", "password": "secretpassword"})
                        assert response.status_code == 200
                        assert "access_token" in response.json()
                        
                    def test_rate_limiter_blocks_abusive_requests():
                        for _ in range(65):
                            res = client.post("/auth/login", data={"username": "alice", "password": "wrong"})
                        assert res.status_code == 429
                """.trimIndent()
            ),
            RepoFile(
                path = "pyproject.toml",
                language = "toml",
                content = """
                    [tool.poetry]
                    name = "modern-auth-service"
                    version = "0.2.0"
                    description = "Enterprise FastApi auth service with Docker sandbox"
                    
                    [tool.poetry.dependencies]
                    python = "^3.12"
                    fastapi = "^0.115.0"
                    uvicorn = "^0.30.0"
                    pydantic = "^2.8.0"
                    pytest = "^8.3.0"
                """.trimIndent()
            )
        )
    }
}
